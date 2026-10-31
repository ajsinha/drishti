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

# Connectors: one reference, a section per plugin

Connecting Drishti to your data means choosing a **connector** (one configured instance of a source plugin) and writing it down as a **connector file**. This page is the reference for
every shipped plugin: what it is for, a connector file for it, and a link to its full document (how it reads, every setting, scale and limits, troubleshooting). To do it once with a worked example, follow
[tutorial 4](../tutorials/04-connect-your-data.md). To write a plugin of your own: [CONNECTOR_DEVELOPER_GUIDE.md](CONNECTOR_DEVELOPER_GUIDE.md). The files, live reload, Admin and the API:
[CONNECTOR_FILES.md](CONNECTOR_FILES.md). TLS and the proxy: [TLS.md](TLS.md).

## One file per connector

A connector is a **site** resource: one YAML file in `config/connectors/`, and **the file name is the connector's name** (`config/connectors/trading-lake.yaml` is the
connector `trading-lake`). A pack does not carry connection settings; it **names** the connectors it reads through (`connectors: [trading-lake]` and `routes:` in
its `pack.yaml`) and may **suggest** a template for each, which the server writes to the file once, at the first start, and never overwrites.

```yaml
# config/connectors/trading-lake.yaml
plugin: delta                     # required: which source plugin (delta, jdbc, file, kafka, redis, …)
enabled: true                     # optional, default true
kinds: [trade]                    # optional: the exact kinds it serves (see the rule below)
description: Trading lake         # optional
settings:                         # the plugin's settings; nesting is flattened to dotted keys
  root: ${DRISHTI_DELTA_ROOT:${drishti.data.dir:./data}/delta}
  domain: trading
```

- Edit the file in your editor, in **Admin → Connectors** (a form generated from the plugin's settings, a YAML tab, **Test connection**, history, restore), or with
  `drishti.py connector list|get|apply|delete|test|plugins|enable|disable|reset|history`. A change is applied to that connector without a restart.
- A credential (`password`, `secret…`, `token`, `api-key`, `access-key`, `private-key`, `credential`) is only ever an `${ENV_VAR}` or a `file:/path` reference.
- Ready-made files for every store are in [`config/connectors.examples/`](../../config/connectors.examples) (`postgres/`, `duckdb/`, `aerospike/`, `cassandra/`, `mongodb/`, `iceberg/`,
  `redis/`, `files/`, `other/`). Every connector document ends its settings with an "As a connector file" section.
- A site that reaches the internet only through a forward proxy sets it once for every HTTP-based connector (REST, feeds, news, yfinance, S3, Delta and Iceberg on S3, the
  Kafka schema registry), AI provider and MCP server: `drishti.http.proxy.*` in `application.yaml`, or a `proxy:` block (`url`, `username`, `password` as `${ENV}`, `no-proxy`)
  in one connector's `settings:`, or `proxy: none` to go direct. https targets are tunnelled with `CONNECT` and keep their `tls.*`. Admin → Connectors shows the effective proxy,
  masked, and where it came from. See [TLS.md](TLS.md#outbound-http-proxy-for-connectors-ai-providers-and-mcp-servers).
- A connector over the network takes the shared `tls.*` settings for a private CA or a client certificate ([TLS.md](TLS.md), with a matrix of what each does) and, when it speaks HTTP, the shared `proxy:` block ([TLS.md](TLS.md#outbound-http-proxy-for-connectors-ai-providers-and-mcp-servers)).
- `drishti.sources.connectors` in `application.yaml` and the per-store profiles (`SPRING_PROFILES_ACTIVE=postgres`, …) still work but are **deprecated**.

### Precedence and kinds

Which definition wins (environment and the deprecated `drishti.sources.connectors.*`, then the file, then the pack's template) is in
[CONNECTOR_FILES.md, Precedence](CONNECTOR_FILES.md#precedence); how `routes:` and a file's `kinds:` decide which connector answers a kind
is in [CONNECTOR_FILES.md, How packs and files fit together](CONNECTOR_FILES.md#6-how-packs-and-files-fit-together).

### Several connectors for one kind: a union

When one kind comes from several systems at once (trades from Endur, Calypso and Murex, each with its own extract), give each system
its own connector serving the kind and mark them `union: true`. Ids must be unique across them (the trading pack's are: Endur deal
numbers, `CLY-…`, `MX-…`), and each document says where it came from (the trading pack's `SOURCE_SYSTEM`).

```yaml
# config/connectors/trade-calypso.yaml (trade-endur.yaml and trade-murex.yaml differ only in the table and the name)
plugin: delta
kinds: [trade]
settings:
  root: ${DRISHTI_DELTA_ROOT:./data/delta}
  domain: trading
  union: true                   # one slice of the kind: the union of the three serves it
  table: { trade: trade_calypso }
  source-name: trade-calypso
```

| What | Without `union` | With `union: true` |
|---|---|---|
| open `TRD CLY-3000086` | the first connector that holds the date answers; one that holds it and does not list the id ends the read (*not found*) | every union connector is asked in turn; *not found* only when none lists it |
| search and type-ahead | the first connector holding the date lists the kind | every union connector lists it; their hits are taken in turn, so a short type-ahead shows each system |
| a structured search, a pick list, a pivot over columns | the columns of one connector | the columns of all of them, put together (a number in one system and text in another is kept as it is) |
| a BI table over the kind (no `connector:` named) | one connector's rows | every union connector's rows in one table; naming a connector reads that one only |

A connector behind the union (a stream of today's changes, the demo samples) is still not asked once the union holds the date. The
trading pack ships its three trade connectors this way ([DELTA_CONNECTOR.md](DELTA_CONNECTOR.md#4-declaring-the-layout-in-a-pack));
`drishti.py lake build --connector trade-endur` writes the table that connector reads ([TOOLS.md](../guides/TOOLS.md)).

## Which connector?

| Your data is… | Use | Section |
|---|---|---|
| JSON or CSV files in a folder | `file` | [file](#file) |
| behind an in-house HTTP/JSON API | `rest` | [rest](#rest) |
| in an existing database schema (your own SQL) | `jdbc` query mode | [postgresql and jdbc](#postgresql-and-jdbc) |
| in PostgreSQL loaded for Drishti | `jdbc` table mode | [postgresql and jdbc](#postgresql-and-jdbc) |
| in a Delta Lake or an Iceberg catalog | `delta`, `iceberg` | [delta lake](#delta-lake), [apache iceberg](#apache-iceberg) |
| in Iceberg tables other engines write (Spark, Flink, Trino, Glue), read-only | `iceberg-external` | [ICEBERG_EXTERNAL_CONNECTOR.md](ICEBERG_EXTERNAL_CONNECTOR.md) |
| in DuckDB, MongoDB, Redis or Aerospike | `duckdb`, `mongodb`, `redis`, `aerospike` | [duckdb](#duckdb), [mongodb](#mongodb), [redis](#redis), [aerospike](#aerospike) |
| in Apache Cassandra, ScyllaDB, DataStax Astra or Amazon Keyspaces | `cassandra` | [apache cassandra](#apache-cassandra) |
| on Kafka, ActiveMQ or RabbitMQ (live) | `kafka`, `activemq`, `rabbitmq` | [kafka](#kafka), [activemq](#activemq), [rabbitmq](#rabbitmq) |
| JSON objects in a bucket (S3, MinIO, Ceph) | `s3` | [S3_CONNECTOR.md](S3_CONNECTOR.md) |
| public rates and FX | `feed` | [public feeds](#public-feeds) |
| headlines for a named entity, computed on demand (code, not a store) | `news` | [news](#news) |
| a quote and price history for a ticker, computed on demand (samples only) | `yfinance` | [yahoo finance](#yahoo-finance) |
| the packs' sample entities | `demo` (runs as itself, no file) | [demo](#demo) |

The comparison of what each can do (history, live ticks, search, reverse lookups) and the rules of thumb are in
[Which connector should I use?](CONNECTOR_DEVELOPER_GUIDE.md#which-connector-should-i-use). To write a plugin of your own, see
[The SourcePlugin SPI](CONNECTOR_DEVELOPER_GUIDE.md#the-sourceplugin-spi). All about the files, live reload, the Admin page, the API and the CLI:
[CONNECTOR_FILES.md](CONNECTOR_FILES.md).

## Demo

Plugin `demo`. Every pack's samples, in memory, ticking.

| Use it for | Do not use it for |
|---|---|
| a first look at Drishti on a laptop: nothing to install, every pack's screens filled | production data of any kind |
| demos and screenshots: live views tick without a stream | history: it has no business dates, every date gets the same document |
| writing a pack: put a few documents in `samples/`, see them laid out at once | more than a few thousand documents: everything is held in memory and scanned for reverse lookups |
| tests: `ticking: false` freezes every document (the golden tests run that way) | data that changes outside the server: files are read once, at start |

**Layout.** `samples/catalog.jsonl` per pack lists the documents. **Loader:** each pack's `tools/` generate the samples.
**Limits:** it is the `default-route`: switch it off in production so a live view cannot tick from samples.

**Also built in: `derived`.** Kinds computed from other kinds (members grouped by an expression, with `count`, `sum`,
`avg`, …), read through whichever connectors serve the members. It is part of the engine, not a plugin: see
[PACK_DEVELOPER_GUIDE.md](../guides/PACK_DEVELOPER_GUIDE.md#derived-kinds-entities-computed-from-other-kinds).


Everything else (how it reads, every setting, scale and limits, troubleshooting, a walk-through): [DEMO_CONNECTOR.md](DEMO_CONNECTOR.md).

## File

Plugin `file`. JSON lines on disk, from a demo to a large book.

| Use it for | Prefer another store for |
|---|---|
| a demo on a laptop, a training environment | a book of a million entities a day kept for years (Delta Lake, PostgreSQL) |
| data a team exports as files every night | data that changes during the day and must show at once (Kafka, Redis) |
| reference data kept by hand (books, desks, limits) | many writers at once |
| a first connector, before a database is chosen | queries other systems also run against the same store |

A connector file (`config/connectors/<name>.yaml`):

```yaml
# config/connectors/site-quotes.yaml
plugin: file
kinds: [quote]
description: Quotes from the desk's drop folder
settings:
  root: /srv/desk/lake
  rescan-seconds: '30'
  lookback-days: '10'
```

**Layout.** JSON lines, one file per kind per business day: `<root>/<domain>/<yyyy-MM-dd>/<kind>.jsonl` (an undated
`<kind>.jsonl` also works); or a file per entity, `<root>/<kind>/<id>.json` or `.csv` (CSV deprecated: `drishti.py convert`), and dated
`<root>/<yyyy-MM-dd>/<kind>/<id>.json`. **Loader:** `tools/load-files.sh [root] [--trades N --days D]`.
**Limits:** no live updates; new files reach search after `rescan-seconds`; a kind or id that would leave its folder is
refused.

Everything else (how it reads, every setting, scale and limits, troubleshooting, a walk-through): [FILE_CONNECTOR.md](FILE_CONNECTOR.md).

## Delta Lake

Plugin `delta`. Business dates in a Delta lake, at a million trades a day for seven years.

A connector file (`config/connectors/<name>.yaml`):

```yaml
plugin: delta
kinds: [customer]
description: Customer tables in the data lake over TLS
settings:
  root: s3://lake-prod/delta
  domain: customer
  s3.endpoint: https://minio.example.com:9000
  s3.region: us-east-1
  s3.access-key: ${LAKE_ACCESS_KEY}
  s3.secret-key: ${LAKE_SECRET_KEY}
  tls:
    ca-file: /etc/drishti/tls/ca.pem
```

**Layout.** A table per kind, `<root>/<domain>/<kind>/` (or the folder `table.<kind>` names, as the trading pack's `trade_endur`; [DELTA_CONNECTOR.md section 3](DELTA_CONNECTOR.md#3-what-is-on-disk)), rows `(id, doc)` plus the pack's promoted columns,
partitioned by `business_date`; `root` may be `s3a://bucket/lake`. **Loader:** `tools/load-delta.sh [root] [--trades N
--days D]` for samples; `tools/lake/maintain.py` keeps it bounded. **Limits:** *known at* resolves against the log
files' modification times (copy lakes with `cp -p`); BROTLI and LZO pages are refused with a clear error (every codec per engine: [DELTA_CONNECTOR.md section 16](DELTA_CONNECTOR.md#16-engines-native-and-hadoop)).

Everything else (how it reads, every setting, scale and limits, troubleshooting, a walk-through): [DELTA_CONNECTOR.md](DELTA_CONNECTOR.md).

## Apache Iceberg

Plugin `iceberg`. Business dates in Iceberg tables, at scale.

A connector file (`config/connectors/<name>.yaml`):

```yaml
plugin: iceberg
kinds: [trade]
description: Iceberg tables behind a REST catalog, object store over TLS
settings:
  catalog: rest
  uri: http://catalog.example.com:8181
  warehouse: s3://lake/warehouse
  namespace: risk
  s3.endpoint: https://minio.example.com:9000
  s3.region: us-east-1
  s3.access-key: ${LAKE_ACCESS_KEY}
  s3.secret-key: ${LAKE_SECRET_KEY}
  tls:
    ca-file: /etc/drishti/tls/ca.pem
```

**Layout.** A table per kind, partitioned by business date, sorted by id, promoted columns beside the document.
**Loader:** `tools/load-iceberg.sh`; maintain with `IcebergMaintenance`. **Limits:** as for Delta Lake; delete files are
applied by every read.

Everything else (how it reads, every setting, scale and limits, troubleshooting, a walk-through): [ICEBERG_CONNECTOR.md](ICEBERG_CONNECTOR.md).

Iceberg tables that other engines write, in their own layout, are read (never written) by the plugin `iceberg-external`:
[ICEBERG_EXTERNAL_CONNECTOR.md](ICEBERG_EXTERNAL_CONNECTOR.md).

## DuckDB

Plugin `duckdb`. A book in one embedded file.

A connector file (`config/connectors/<name>.yaml`):

```yaml
# config/connectors/trade-endur.yaml
plugin: duckdb
kinds: [trade]
description: Trading documents in an embedded DuckDB file
settings:
  domain: trading
  path: ${DRISHTI_DUCKDB_PATH:${drishti.data.dir:./data}/duckdb/drishti.duckdb}
  memory-limit: ${DRISHTI_DUCKDB_MEMORY:1GB}
  table: trading.entities
  pool-size: '8'
  layout:
    trade:
      columns: [TRADE_ID, SOURCE_SYSTEM, PRODUCT_TYPE, PRODUCT_SUB_TYPE, PRODUCT_GROUP, TRADE_STATUS, TRADE_VERSION, BUY_SELL_CODE, CURRENCY, QUANTITY, TRADE_DATE, MATURITY_DATE, COUNTERPARTY, LEGAL_ENTITY, PORTFOLIO, TRADER_ID]
```

**Layout.** A schema per domain, `<domain>.entities (kind, id, business_date, doc, <promoted columns>)`, each day sorted
by id. **Loader:** `tools/load-duckdb.sh` (`--keep-days`); a load writes a new file and renames it over the old one.
**Limits:** one file, one machine; native memory is outside the Java heap (`memory-limit`).

Everything else (how it reads, every setting, scale and limits, troubleshooting, a walk-through): [DUCKDB_CONNECTOR.md](DUCKDB_CONNECTOR.md).

## PostgreSQL and JDBC

Plugin `jdbc`. A database through JDBC, in table mode (Drishti's own table) or, with your own SQL, in query mode ([JDBC_QUERIES.md](JDBC_QUERIES.md)).

A connector file (`config/connectors/<name>.yaml`):

```yaml
plugin: jdbc
kinds: [trade]
description: Trades in PostgreSQL, verified TLS
settings:
  url: jdbc:postgresql://db.example.com:5432/drishti
  user: drishti
  password: ${DRISHTI_DB_PASSWORD}
  table: entities
  tls:
    enabled: true
    ca-file: /etc/drishti/tls/ca.pem
```

**Query mode** (a database whose schema you keep: one SQL statement per kind):

```yaml
# config/connectors/trading-db.yaml
plugin: jdbc
settings:
  url: ${DRISHTI_TRADES_URL:jdbc:postgresql://db:5432/trades}
  user: ${DRISHTI_TRADES_USER:drishti}
  password: ${DRISHTI_TRADES_PASSWORD}
  query.trade: SELECT … FROM trades WHERE trade_id = :id AND business_date = :asOf
```

**Layout.** Your tables; the first row of a query is the document, each column a field; a query that uses `:asOf`
makes the kind dated. **Loader:** none. **Limits:** search and reverse lookups only through the optional `ids.<kind>`,
`columns.<kind>` and `reverse.<kind>` queries; one request on your database per view.

**Table mode** (PostgreSQL loaded for Drishti: every kind of a data domain in one table `(kind, id, business_date, doc jsonb, <promoted columns>)`, partitioned by month): **Layout.** One row per entity per business date, the pack's promoted fields as columns. **Loader:**
`tools/load-postgres.sh`. **Limits:** you run the load and size the indexes; `mode.<kind>` chooses `snapshot` or
`effective`.

Everything else (how it reads, every setting, scale and limits, troubleshooting, a walk-through): [POSTGRES_CONNECTOR.md](POSTGRES_CONNECTOR.md).

## MongoDB

Plugin `mongodb`. A document per entity per day, read as columns.

A connector file (`config/connectors/<name>.yaml`):

```yaml
plugin: mongodb
kinds: [position]
settings:
  uri: mongodb://${MONGO_USER}:${MONGO_PASSWORD}@mongo.example.com:27017/?authSource=admin
  database: drishti
  collection: positions
  tls:
    enabled: true
    ca-file: /etc/drishti/tls/ca.pem
```

**Layout.** A collection per domain, a document per entity per business date (`_id` `kind/id/yyyyMMdd`), the promoted
fields under `c`, and a narrow `<domain>_columns` copy for searches. **Loader:** `tools/load-mongodb.sh`
(`--keep-days`, `--ttl-days`). **Limits:** promoted fields are cached in memory; MongoDB 8.0 does not start on some
recent kernels (use 7).

Everything else (how it reads, every setting, scale and limits, troubleshooting, a walk-through): [MONGODB_CONNECTOR.md](MONGODB_CONNECTOR.md).

## Redis

Plugin `redis`. A million trades a day, in memory.

A connector file (`config/connectors/<name>.yaml`):

```yaml
plugin: redis
kinds: [trade]
description: Trades in Redis over mutual TLS
settings:
  domain: trading
  uri: rediss://redis.example.com:6380
  user: drishti
  password: ${DRISHTI_REDIS_PASSWORD}
  tls:
    ca-file: /etc/drishti/tls/ca.pem
    cert-file: /etc/drishti/tls/client.pem
    key-file: /etc/drishti/tls/client.key
```

**Layout.** A compressed document per entity per day (zstd, dictionary per kind); promoted fields column-wise in chunks
of 10,000; days and ids in sorted sets. **Loader:** `tools/load-redis.sh` (`--ttl-days`, `--publish`). **Limits:**
about 1 GB of memory per million trades a day; TTL retention; a day Redis does not hold goes to the next store.

Everything else (how it reads, every setting, scale and limits, troubleshooting, a walk-through): [REDIS_CONNECTOR.md](REDIS_CONNECTOR.md).

## Aerospike

Plugin `aerospike`. A million trades a day, for years.

A connector file (`config/connectors/<name>.yaml`):

```yaml
plugin: aerospike
kinds: [trade]
description: Trades in Aerospike over mutual TLS
settings:
  hosts: as-1.example.com:4333,as-2.example.com:4333
  namespace: drishti
  tls-name: aerospike.example.com
  auth-mode: pki
  tls:
    enabled: true
    ca-file: /etc/drishti/tls/ca.pem
    cert-file: /etc/drishti/tls/client.pem
    key-file: /etc/drishti/tls/client.key
```

**Layout.** Three sets per domain: a record per entity per business date (`kind/id/yyyyMMdd`: the document and the
promoted bins), an index record per entity (its dates), a record per kind (its dates). **Loader:**
`tools/load-aerospike.sh`. **Limits:** searches, pick lists and reverse lookups need the pack's promoted bins
(`layout.<kind>.columns`).

Everything else (how it reads, every setting, scale and limits, troubleshooting, a walk-through): [AEROSPIKE_CONNECTOR.md](AEROSPIKE_CONNECTOR.md).

## Apache Cassandra

Plugin `cassandra`. Apache Cassandra 4 and 5, ScyllaDB, DataStax Astra (and Amazon Keyspaces, checked by hand first): a partition per business day per
bucket, keyed by id; a day appears whole or not at all (the loader writes a new generation and switches to it last); no secondary index, nothing scans a table.

A connector file (`config/connectors/<name>.yaml`):

```yaml
plugin: cassandra
kinds: [trade]
description: Trades in Cassandra over mutual TLS
settings:
  domain: trading                       # the keyspace (or keyspace: …)
  contact-points: cass-1.example.com:9142,cass-2.example.com:9142
  local-datacenter: dc-east
  username: drishti_reader
  password: ${DRISHTI_CASSANDRA_PASSWORD}
  tls:
    enabled: true
    ca-file: /etc/drishti/tls/ca.pem
    cert-file: /etc/drishti/tls/client.pem
    key-file: /etc/drishti/tls/client.key
```

Load it with `tools/load-cassandra.sh` (or `drishti.py data load --store cassandra`). Everything else (the table layout, how it reads, generations, retention,
sizing, measured numbers, every setting): [CASSANDRA_CONNECTOR.md](CASSANDRA_CONNECTOR.md).

**Layout.** A keyspace per domain; a table per kind with a row per entity per business date (the document and the promoted
columns, partitioned by date, generation and bucket), plus `entity_days` and `kind_days`; a day loaded as a new generation
appears whole or not at all. **Loader:** `tools/load-cassandra.sh` (`tools\windows\load-cassandra.ps1`).

## S3

Plugin `s3`. One JSON object per entity in a bucket (S3, MinIO, Ceph).

| Use it for | Prefer another store for |
|---|---|
| documents another system already writes to a bucket, one per entity (stress results, reports, model outputs) | a book of hundreds of thousands or millions of entities a day (Delta Lake on `s3a://`, Iceberg, PostgreSQL) |
| a few thousand to a few tens of thousands of objects in all, under the prefix | searches over fields, pick lists, desk totals and impact (needs promoted columns: Delta Lake, Iceberg, PostgreSQL, Aerospike) |
| reference documents kept by hand in a bucket, the same for every date (undated objects) | reverse lookups (*Linked entities*, F8 impact): this connector has none |
| sites where object storage is the only shared store, and no table format is wanted | data that changes during the day and must tick on screen (Kafka, ActiveMQ, RabbitMQ, Redis) |
| a short daily history (`lookback-days`), cleaned by a bucket lifecycle rule | years of history read by date (a lake keeps it compactly; see [section 9](S3_CONNECTOR.md#9-s3-with-a-lake-for-history)) |

A connector file (`config/connectors/<name>.yaml`):

```yaml
plugin: s3
kinds: [stress-result]
settings:
  bucket: risk-docs
  prefix: eod/
  endpoint: https://minio.bank.example    # S3-compatible store; path-style addressing (path-style: true)
  access-key: ${S3_ACCESS_KEY}            # otherwise the AWS credential chain
  secret-key: ${S3_SECRET_KEY}
```

**Layout.** `<prefix><kind>/<id>.json` and dated `<prefix><yyyy-MM-dd>/<kind>/<id>.json`. **Loader:** none (any
uploader: `aws s3 sync`, `mc cp`). **Limits:** listing a large bucket costs; ids and dates are listed every
`rescan-seconds` and reads cached `cache-seconds`; no reverse lookups.

Everything else (how it reads, every setting, scale and limits, troubleshooting, a walk-through): [S3_CONNECTOR.md](S3_CONNECTOR.md).

## REST

Plugin `rest`. One HTTP GET per entity.

| Use it for | Prefer another store for |
|---|---|
| an owning system that already has a per-entity JSON API, and you want its current answer, not a copy | history: a picked business date (Delta Lake, PostgreSQL table mode, files) |
| reference or account data opened one entity at a time (a counterparty, a client, a limit) | kinds users search for, type ahead on, or filter (`TRD where …`): the connector cannot search |
| a first integration before a feed or a database load exists | links back to an entity (*Linked entities*, impact with F8): it has no reverse lookups |
| data whose owner does not allow copies | anything that must tick live: it has no subscriptions |
| a small number of views a minute | a million entities a day listed, aggregated or scanned: it reads one entity per request |

A connector file (`config/connectors/<name>.yaml`):

```yaml
plugin: rest
kinds: [counterparty]                            # only counterparties are asked of this service
settings:
  base-url: ${CRM_URL:https://crm.bank.example/api}   # required; trailing slashes are dropped
  path: /{kind}/{id}                             # {kind} and {id} are URL-encoded
  timeout-ms: '2500'                             # connect timeout and request timeout
  generation-header: X-Version                   # a numeric response header (default ETag)
  header.Authorization: Bearer ${CRM_TOKEN}      # any request header: header.<Name>
  header.X-Client: drishti
```

**Layout.** `GET <base-url><path>` with `{kind}` and `{id}` substituted; `404` means not held, any other status of 400
or more is a failure. **Loader:** none. **Limits:** undated; no search, no reverse lookups; every view is a request to
the service.

Everything else (how it reads, every setting, scale and limits, troubleshooting, a walk-through): [REST_CONNECTOR.md](REST_CONNECTOR.md).

## Public feeds

Plugin `feed`. SOFR, EUR STR, ECB FX, the Treasury curve and FRED.

| Use it for | Prefer another store for |
|---|---|
| real overnight rates (SOFR, €STR) beside a pack's own fixings, for comparison or a demo | an official, audited record of fixings (load them into a dated store you control) |
| ECB reference FX rates for information (crosses for ten pairs) | traded FX prices: the ECB rate is a once-a-day reference, and the connector sets `bid` = `ask` = `mid` |
| the US Treasury par curve as an `ir-curve` | a bootstrapped discount curve: the connector's `zeroRate` is the par yield, not a zero rate (section 3.4) |
| any daily FRED series (`DGS10`, `DFF`, `SOFR`, …) | history beyond the last 60 observations, 90 days of FX or about two months of Treasury curve: archive it ([section 7](FEEDS_CONNECTOR.md#7-scale-limits-and-history-from-a-lake)) |
| a server with outbound HTTPS, or a mirror on disk | a server that must make no outbound request and has no mirror process |

A connector file (`config/connectors/<name>.yaml`):

```yaml
plugin: feed
enabled: true                         # on, whatever DRISHTI_FEED_ECB_FX says
kinds: [fx-spot]
settings:
  feed: ecb-fx
  stale-after: 4d
  refresh-minutes: '30'
  timeout-seconds: '10'
```

**Layout.** One connector (one file) per feed, suggested by the market-data pack; entities carry the feed in their id
(`FIX-SOFR-NYFED`). **Loader:** none. **Limits:** needs the internet (or a mirror: `url: file://…`); FRED needs a free
key; a failed fetch keeps the last good data and shows in health.

Everything else (how it reads, every setting, scale and limits, troubleshooting, a walk-through): [FEEDS_CONNECTOR.md](FEEDS_CONNECTOR.md).

## News

Plugin `news`. Headlines for a named entity, computed on demand.

| You ask for | You get |
|---|---|
| `news NEWS-Federal_Reserve` | the newest headlines that name the Federal Reserve (or *Fed*, *FOMC*, ...), newest first, each with publisher, time, link and a short snippet; a count by publisher; and the list of steps that chose them |
| `news NEWS-Toyota@ja-JP` | the same from the Japanese edition |
| `news NEWS-Federal_Reserve~all` | everything the feed returned, without the relevance filter (the publisher lists and de-duplication still apply) |

**Layout.** Ids `NEWS-<name with underscores>[@lang-CC][~all]`. **Loader:** none. **Limits:** needs the internet; the entity names are sent to
Google (off as shipped); the first view of a name is pending under the 40 ms link budget.

Everything else (how it reads, every setting, scale and limits, troubleshooting, a walk-through): [NEWS.md](NEWS.md).

## Yahoo Finance

Plugin `yfinance`. A quote and price history for a ticker (samples only).

`QUOTE-AAPL` returns the last price, the change from the previous close in money and percent, the currency, the market state, the
day's and the 52-week range, the volume, the market capitalisation when Yahoo gives one, and the closing prices (with open, high,
low and volume) for the configured range at the configured interval. The console lays it out with the `quote` Sutra of the
`news-and-markets` pack: a strip, a closing-price line, the ranges and a table of bars.

**Layout.** Ids `QUOTE-<symbol>`. **Loader:** none. **Limits:** needs the internet; not for production or redistribution; an unknown symbol is not
held.

Everything else (how it reads, every setting, scale and limits, troubleshooting, a walk-through): [YFINANCE.md](YFINANCE.md).

## Kafka

Plugin `kafka`. Live entities from a topic.

| Use it for | Prefer another store for |
|---|---|
| entities whose latest state is published to a topic whenever it changes (trades, positions, quotes, limits) | history: the connector is undated; pair it with Delta Lake, PostgreSQL, Aerospike or files ([section 10.4](KAFKA_CONNECTOR.md#104-with-a-lake-for-history)) |
| views that must tick within a frame of a change | reverse lookups (*Linked entities*, impact with F8): it declares none |
| a change feed over data a store already serves (`ticks` mode) | searches over fields of the documents: it offers type-ahead by id only |
| a topic other systems already consume, read without a consumer group | a queue that delivers each message once (ActiveMQ, RabbitMQ: those connectors keep their own state store) |

A connector file (`config/connectors/<name>.yaml`):

```yaml
plugin: kafka
kinds: [netting-set, credit-limit]                    # without it an envelope connector serves every kind
settings:
  bootstrap-servers: kafka1.bank.example:9093,kafka2.bank.example:9093
  topics: risk.envelopes,risk.limits                  # comma list (or a YAML list)
  kind.risk.limits: credit-limit                      # this topic: whole credit-limit documents
  id-field.risk.limits: limitId
  cache-mb: '512'
  stale-after: 30m
  client.security.protocol: SASL_SSL                  # any consumer property: client.<property>
  client.sasl.mechanism: SCRAM-SHA-512
  client.sasl.jaas.config: ${KAFKA_JAAS}
```

**Layout.** Messages are an envelope `{kind, id, doc}`, or whole documents with `kind` and `id-field`; a tombstone
deletes. **Loader:** none for production; `tools/samplegen/stream.py` replays and ticks the samples. **Limits:**
undated; reads every partition from the beginning (the topic is the state), so a restart replays it; `mode: ticks`
keeps nothing and only pushes. **Security:** `security.protocol`, `tls.*` ([TLS.md](TLS.md)), `sasl.*` (PLAIN, SCRAM, OAUTHBEARER, GSSAPI), `flavour: confluent` and the Schema Registry; `client.<property>` passes any Kafka property and wins.

Everything else (how it reads, every setting, scale and limits, troubleshooting, a walk-through): [KAFKA_CONNECTOR.md](KAFKA_CONNECTOR.md).

## ActiveMQ

Plugin `activemq`. Live entities from queues and topics, kept on local disk.

| Use it for | Prefer another connector for |
|---|---|
| a system that already publishes each change of an entity to an ActiveMQ Classic queue or topic | history by business date (Delta Lake, PostgreSQL, Aerospike, files): this connector is undated |
| live views of a modest set of entities (orders, limits, quotes, alerts) that tick on every message | a topic that is the system of record and can be replayed (Kafka: [KAFKA_CONNECTOR.md](KAFKA_CONNECTOR.md)) |
| state that should start empty each day (`state.reset-at`) | reverse lookups (*Linked entities*, impact with F8): not supported |
| a desk that cannot run Kafka but has a broker | searches over promoted fields of millions of entities a day (it has no columns) |
| | ActiveMQ Artemis with AMQP or a broker other than Classic: the client speaks OpenWire only |

A connector file (`config/connectors/<name>.yaml`):

```yaml
plugin: activemq
kinds: [credit-limit]
settings:
  broker-url: failover:(ssl://mq1.bank.example:61617,ssl://mq2.bank.example:61617)?initialReconnectDelay=1000&maxReconnectDelay=30000
  user: ${AMQ_USER}
  password: ${AMQ_PASSWORD}
  destinations: queue:limits
  kind.limits: credit-limit
  id-field.limits: limitId
  client-id: drishti-prod-1-limits-mq
  cache-mb: '256'
  state.root: /var/lib/drishti/state     # the store goes to /var/lib/drishti/state/limits-mq
  state.max-gb: '20'
  state.durability: sync                 # the default: no acknowledged message is lost, even on power loss
```

**Layout.** A JSON message body on a destination with a `kind.<destination>`, or an envelope `{kind, id, doc}`; a
delete is `doc: null`. **Loader:** none: publish messages. **Limits:** a queue delivers once, so the connector keeps
the latest document per entity in a local RocksDB state store, the only copy, with a disk budget; see
[Message queues](#rabbitmq).

Everything else (how it reads, every setting, scale and limits, troubleshooting, a walk-through): [ACTIVEMQ_CONNECTOR.md](ACTIVEMQ_CONNECTOR.md).

## RabbitMQ

Plugin `rabbitmq`. Live entities from AMQP queues.

| Use it for | Prefer another connector for |
|---|---|
| a system that already publishes changes to a RabbitMQ exchange (margin calls, orders, limit changes) | history by business date: a queue has none (pair it with a lake, [section 10](RABBITMQ_CONNECTOR.md#10-history-rabbitmq-with-a-lake)) |
| live views that update within a moment of the change | a stream you want to replay from the beginning (Kafka keeps the log; [KAFKA_CONNECTOR.md](KAFKA_CONNECTOR.md)) |
| tens or hundreds of thousands of entities, each changing now and then | reverse lookups (*Linked entities*, impact with F8): this connector declares none |
| a feed that should reach Drishti even while Drishti is down (messages wait in the queue) | a store other systems also query: the state lives only in Drishti's local disk |

A connector file (`config/connectors/<name>.yaml`):

```yaml
plugin: rabbitmq
kinds: [margin-call]
settings:
  uri: ${RABBIT_URI}                         # amqps://drishti:…@rabbit.bank.example:5671/collateral
  queues: drishti.margin-calls,drishti.collateral-events
  declare: false                             # the platform team owns the queues; bind.* is ignored
  kind.drishti.margin-calls: margin-call
  id-field.drishti.margin-calls: callId
  # drishti.collateral-events has no kind: its messages are envelopes {kind, id, doc}
  heartbeat-seconds: '20'
  recovery-interval-ms: '2000'
  cache-mb: '256'
  state.root: /var/lib/drishti/state         # the store goes to /var/lib/drishti/state/margin-mq
  state.max-gb: '20'
  state.durability: sync                     # the default: no acknowledged message is lost, even on power loss
```

**Both message queues (ActiveMQ, RabbitMQ).** A queue delivers each message once and keeps no history, unlike a Kafka topic, so these two connectors keep the latest
document of every entity themselves, in a persistent state store on local disk that survives restarts, and acknowledge
a message only after the store has kept it. How a message becomes an entity, the durability modes, the disk budget and
eviction are in [ACTIVEMQ_CONNECTOR.md](ACTIVEMQ_CONNECTOR.md#7-durability-and-disk-budget) and
[RABBITMQ_CONNECTOR.md](RABBITMQ_CONNECTOR.md#6-durability-and-disk-budget); minimal configurations are the
[activemq](#activemq) and [rabbitmq](#rabbitmq) sections.

Everything else (how it reads, every setting, scale and limits, troubleshooting, a walk-through): [RABBITMQ_CONNECTOR.md](RABBITMQ_CONNECTOR.md).


## Combining connectors

### History from a lake, live from Kafka, for the same kind

This is what the trading pack names: `trade-endur` (Delta Lake, dated) and `trading-stream` (Kafka, live), both
serving `trade`, with `routes: { trade: trade-endur }`. With the stream on and the demo off, the candidates for
`trade` are the route (`trade-endur`), then every other connector serving `trade` (`trading-stream`, and `file`,
which serves every kind). Then:

| The user asks for | Order tried | Who answers |
|---|---|---|
| Live | `trading-stream` (live first) → `trade-endur` → `file` | Kafka; the view ticks from Kafka. A trade Kafka does not hold (matured, compacted away) falls through to the lake's newest date |
| a picked date | `trade-endur` (dated first) → `file` → `trading-stream` | the lake, for that date; a static snapshot |
| a date the lake does not hold | as above | `file` if it has a dated folder; else Kafka's current document, with the banner *No data held for <date>* (the current data of trading-stream) |

**`state` or `ticks`.** In `state` mode (the default) Kafka keeps an index of the topic and answers Live reads
itself, as above. With `mode: ticks` it keeps nothing (no index, no cache, no disk) and answers no reads: the lake
or database answers every read, and Kafka only pushes each new message to the views that are open. Use it when the
store already has the day's data and the topic is just the change feed, or the topic is too large to index:

```yaml
# config/connectors/trading-stream.yaml
plugin: kafka
kinds: [trade]
settings: { mode: ticks, bootstrap-servers: "${DRISHTI_KAFKA_BOOTSTRAP}", topics: drishti.trading.trades,
            kind: trade, id-field: tradeId }
```

The view is live because a connector *pushes* the kind (the plugin answers `pushes(ref)` with true in ticks mode),
not because the document that answered is live: `TRD MX-20000001` reads from `trade-endur` (its provenance names the
lake), shows the green Live button, and each message for MX-20000001 on the topic repaints it within a frame. A picked
business date stays a static snapshot either way.

### Several domains

Run one connector per data domain. A server with the banking packs runs, for example (real output of
`GET /api/v1/sources`, shortened):

```json
{"sources":[
  {"name":"demo","kinds":[],"live":true,"search":true,"reverseLookup":true,"health":"UP"},
  {"name":"file","kinds":[],"live":false,"search":true,"reverseLookup":false,"health":"UP"},
  {"name":"trade-endur","kinds":["trade"],"live":false,"search":true,"reverseLookup":true,"health":"UP"},
  {"name":"credit-store","kinds":["credit-limit","netting-set","sa-ccr","cva"],"live":false,"search":true,"reverseLookup":true,"health":"UP"},
  {"name":"reference-store","kinds":["book","agreement","counterparty", "…"],"live":false,"search":true,"reverseLookup":true,"health":"UP"},
  {"name":"nyfed-sofr-feed","kinds":["rate-fixing"],"live":false,"search":true,"reverseLookup":false,"health":"UP"}],
 "failures":{}}
```

Each connector lists its own `kinds`, has its own health, its own cache and its own read statistics. Keep connector
names stable: routes, health history, cache purges and provenance all use them.

### Changing a pack's connector from the site

The connector files are the site's, so change a pack's connector by editing its file (in **Admin → Connectors**, or in your editor). A file **replaces** the
pack's template for that name **wholesale**: the settings are not merged, so what the file does not say is not inherited. To change only the root of
`trade-endur`, start from the generated file (`drishti.py connector get trade-endur`) and edit that line:

```yaml
# config/connectors/trade-endur.yaml (generated from the trading pack's template, then edited)
plugin: delta
kinds: [trade]
settings:
  root: /srv/lake                  # the line the site changed
  domain: trading
  layout.trade.columns: TRADE_ID,SOURCE_SYSTEM,PRODUCT_TYPE,...
```

```yaml
# config/connectors/trading-stream.yaml: the stream on, whatever DRISHTI_STREAM_TRADING says
plugin: kafka
enabled: true
kinds: [trade]
settings:
  bootstrap-servers: kafka1.bank.example:9092
  topics: trading.trades.v2
  kind: trade
  id-field: tradeId
```

Points to remember:

- **A file is the whole definition.** `kinds:` in the file is the connector's list; kinds you leave out are no longer served by that connector. **Reset to pack default**
  (Admin → Connectors, or `drishti.py connector reset <name>`) puts the pack's template back and keeps your text in the history.
- **Switch one off with `enabled: false`** in its file (`drishti.py connector disable fred-feed`).
- **Keys the plugin does not read are ignored** (with a warning on the form and from the validator). Switching `trade-endur` to `jdbc` and leaving `root` in the file is
  harmless. (Aerospike *does* read `domain`, as its set, and `layout`, as its promoted bins.)
- **A file with no `plugin:`** fails to start. A file name that no pack names and that is not a typo of one defines a new site connector; a pack that names a
  connector nobody defined loads, and its kinds answer `DRS-1011 connector <name> is not configured` ([CONNECTOR_FILES.md](CONNECTOR_FILES.md#a-pack-names-a-connector-nobody-defined)).
- **Between packs**, a child pack that redefines a parent's connector template replaces it *as a whole*, and the override is
  listed in health (`overrides`); two unrelated packs that define the same connector differently stop the server at
  start, naming both. See [PACK_DEVELOPER_GUIDE.md](../guides/PACK_DEVELOPER_GUIDE.md#part-v-domain-packs-the-packs-that-ship-turning-them-on-who-sees-which).
- **The deprecated form still works.** `drishti.sources.connectors.<name>` in `application.yaml` overrides a file setting by setting, with a warning that names the file to write.

### Switching connectors off

| To | Do |
|---|---|
| switch one connector off | `enabled: false` in its file (Admin → Connectors **Disable**, or `drishti.py connector disable <name>`) |
| switch every pack's lake off | `DRISHTI_LAKE_ENABLED=false` |
| switch the samples off | `DRISHTI_DEMO_ENABLED=false` |
| switch a plugin running as itself off | `drishti.sources.plugins.<plugin>.enabled: false` (`DRISHTI_REST_ENABLED`, `DRISHTI_JDBC_ENABLED`, `DRISHTI_S3_ENABLED`, `DRISHTI_ACTIVEMQ_ENABLED`, `DRISHTI_RABBITMQ_ENABLED`) |
| one value for one start | `java -jar … --drishti.sources.connectors.trading-stream.enabled=true` (the deprecated form; it overrides the file for that start) |

A connector that is off is listed under its pack's `connectorsOff` in health. Note that **Admin → Packs** (switching a
pack off for everyone) hides the pack's kinds from users; it does not stop its connectors. Use `enabled: false` for
that.

### Secrets

A connector file never holds a secret. A setting whose name says it is a credential (`password`, `secret…`, `token`, `api-key`, `access-key`, `private-key`, `credential`)
must be an environment reference or a file reference; set the variable where the server runs (systemd `Environment=`, a Kubernetes secret, the shell), or mount the
secret as a file:

```yaml
settings:
  password: ${DESK_DB_PASSWORD}                    # an environment reference, no default
  # password: file:/run/secrets/desk-db-password   # or a file reference: the first line is the secret
  user: ${DESK_DB_USER:drishti}                    # not a credential: a default is fine
  header.Authorization: "Bearer ${CRM_TOKEN}"
  client.sasl.jaas.config: ${KAFKA_JAAS}
  tls.keystore-password: ${CLIENT_KEYSTORE_PASSWORD}   # tls.* settings: see TLS.md; …-password-file also works
```

A literal value, a default inside the reference (`${X:fallback}`) and a password inside a URL (`jdbc:…//user:pw@host`, `?password=…`) are **refused** by Admin → Connectors,
the API and the CLI. A variable that is unset and has no default refuses that file, and the last good configuration keeps running. Health and `/api/v1/sources` do not
show settings. Prefer the AWS credential chain (instance roles) to `access-key` and `secret-key` for S3 and S3A. Details: [CONNECTOR_FILES.md](CONNECTOR_FILES.md#5-credentials-and-tls).

### A pack with several connectors, worked through

A pack for an equities desk, `config/packs/equity-desk/pack.yaml` (illustrative; the structure is exactly that of the
shipped `trading` pack), keeps history in Delta Lake and takes live orders from Kafka. Its `connectors:` mapping is the pack's **suggested templates**: at the first start the server writes
`config/connectors/equity-store.yaml` and `config/connectors/equity-orders.yaml` from them, and those files are then the site's (a pack that only wants to name its connectors writes
`connectors: [equity-store, equity-orders]` and lets the site create the files):

```yaml
pack: equity-desk
version: 1.0.0
title: Equity desk
extends:
- core                 # counterparties, books, traders… and core's reference-store connector
- market-data                  # equities, curves… and market-store plus the five feeds
kinds:                         # a kind belongs to exactly one pack
- order
- position
mnemonics:                     # <MNEMONIC> <ID> <GO>
  ORD: { kind: order, label: Order }
  POS: { kind: position, label: Position }
graph:
  id-patterns:
  - { pattern: "^O-", kind: order }
  - { pattern: "^POS-", kind: position }
connectors:
  equity-store:                # history: one Delta domain folder for both kinds
    plugin: delta
    enabled: ${DRISHTI_LAKE_ENABLED:true}
    kinds: [order, position]
    settings:
      root: ${DRISHTI_DELTA_ROOT:${drishti.data.dir:./data}/delta}
      domain: equity           # <root>/equity/order/, <root>/equity/position/
      mode.position: snapshot
      mode.order: effective    # an order row is written only when the order changes
  equity-orders:               # live: the order book as it moves
    plugin: kafka
    enabled: ${DRISHTI_STREAM_EQUITY:false}
    kinds: [order]
    settings:
      bootstrap-servers: ${DRISHTI_KAFKA_BOOTSTRAP:localhost:9092}
      topics: equity.orders
      kind: order
      id-field: orderId
routes:
  order: equity-store
  position: equity-store
```

**Routing, for `ORD O-55120 <GO>`.** The candidates are, in order: the route (`equity-store`), the
`default-route` (`demo`, which serves any kind), then every other source serving `order` (`equity-orders`, and any
undeclared-kinds plugin such as `file`). Then:

- **Live**, stream on: live sources first, a real stream before the default route's samples, so
  `equity-orders` → `demo` → `equity-store` → `file`. The view ticks from Kafka. An order that Kafka does not hold
  (filled last week, compacted away) falls through to the lake's newest row.
- **Live**, stream off: `demo` → `equity-store` → `file`. With no `samples/` for `order`, the demo source holds
  nothing and the lake answers.
- **A picked date**: dated sources first, so `equity-store` → `file` (dated folders) → `demo` → `equity-orders`;
  the lake answers for that date, and the view is a static snapshot.
- A source that holds nothing passes on; one that fails stops the read (`DRS-1003`), so a lake that is down shows as
  an error rather than silently serving other data.

**Inheritance.** Enabling `equity-desk` (`DRISHTI_PACKS=equity-desk`) loads `core` and `market-data` too
(`extends:` and `requires:` both pull parents in), and every connector the three name runs: `reference-store`,
`market-store`, the five feeds (off unless switched on), `equity-store` and `equity-orders`. Where two packs declare
the same connector name (or route, mnemonic, field, badge or role):

- identically: nothing happens, it runs once;
- differently, and one pack inherits from the other (or a loaded pack inherits from both): the more specific one
  wins *as a whole* (the child over its parents, the rightmost parent over those before it, by C3 linearisation, as
  `PackLineage` computes), and the override is listed in health (`overrides`, from `drishti.packs.overrides`);
- differently, from unrelated packs: the server refuses to start, naming both packs.

So a child pack that redefines `market-store` replaces its whole definition (plugin, kinds and every setting); it
does not merge with the parent's.

**Site overrides.** The site's connector files replace the pack's templates wholesale, so a site edits the file it was given. `config/connectors/equity-store.yaml`,
moved to PostgreSQL (same kinds, modes and route; a different store):

```yaml
plugin: jdbc
kinds: [order, position]
settings:
  url: jdbc:postgresql://pg.bank.example:5432/drishti
  user: ${PG_USER}
  password: ${PG_PASSWORD}
  table: equity.entities
  mode.position: snapshot
  mode.order: effective
```

`config/connectors/equity-orders.yaml`, the stream on whatever `DRISHTI_STREAM_EQUITY` says, and the parent pack's feed off for this site (`config/connectors/fred-feed.yaml` with
`enabled: false`, or `drishti.py connector disable fred-feed`):

```yaml
plugin: kafka
enabled: true
kinds: [order]
settings:
  bootstrap-servers: kafka1.bank.example:9092
  topics: equity.orders
  kind: order
  id-field: orderId
```

Remember that a file's `kinds:` list is the connector's whole list, and that keys the new plugin does not read are
simply ignored (`root` and `domain` above would be; but `domain` *is* read by `aerospike`, as the set, and so is `layout`, as the promoted
bins). Routes stay the pack's (`routes:` in its `pack.yaml`).

---

## Demo data

Building the demo book in each store, small to a million trades a day: [DEMO_DATA.md](DEMO_DATA.md). Reading your own database with your own SQL: [JDBC_QUERIES.md](JDBC_QUERIES.md).
