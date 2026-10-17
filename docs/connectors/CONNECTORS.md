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
kinds: [trade]                    # optional: limit the kinds it serves
description: Trading lake         # optional
settings:                         # the plugin's settings; nesting is flattened to dotted keys
  root: ${DRISHTI_DELTA_ROOT:${drishti.data.dir:./data}/delta}
  domain: trading
```

- Edit the file in your editor, in **Admin → Connectors** (a form generated from the plugin's settings, a YAML tab, **Test connection**, history, restore), or with
  `drishti.py connector list|get|apply|delete|test|plugins|enable|disable|reset|history`. A change is applied to that connector without a restart.
- A credential (`password`, `secret…`, `token`, `api-key`, `access-key`, `private-key`, `credential`) is only ever an `${ENV_VAR}` or a `file:/path` reference.
- Ready-made files for every store are in [`config/connectors.examples/`](../../config/connectors.examples) (`postgres/`, `duckdb/`, `aerospike/`, `mongodb/`, `iceberg/`,
  `redis/`, `files/`, `other/`). Every connector document ends its settings with an "As a connector file" section.
- A site that reaches the internet only through a forward proxy sets it once for every HTTP-based connector (REST, feeds, news, yfinance, S3, Delta and Iceberg on S3, the
  Kafka schema registry), AI provider and MCP server: `drishti.http.proxy.*` in `application.yaml`, or a `proxy:` block (`url`, `username`, `password` as `${ENV}`, `no-proxy`)
  in one connector's `settings:`, or `proxy: none` to go direct. https targets are tunnelled with `CONNECT` and keep their `tls.*`. Admin → Connectors shows the effective proxy,
  masked, and where it came from. See [TLS.md](TLS.md#outbound-http-proxy-for-connectors-ai-providers-and-mcp-servers).
- A connector over the network takes the shared `tls.*` settings for a private CA or a client certificate ([TLS.md](TLS.md), with a matrix of what each does) and, when it speaks HTTP, the shared `proxy:` block ([TLS.md](TLS.md#outbound-http-proxy-for-connectors-ai-providers-and-mcp-servers)).
- `drishti.sources.connectors` in `application.yaml` and the per-store profiles (`SPRING_PROFILES_ACTIVE=postgres`, …) still work but are **deprecated**.

### Precedence

Highest first (the full rules are in [CONNECTOR_FILES.md](CONNECTOR_FILES.md#precedence)):

| Source | Role |
|---|---|
| environment variables and `drishti.sources.connectors.*` in the server's own configuration (**deprecated**) | override a file setting by setting until you move them to the file |
| `config/connectors/<name>.yaml` | the site's definition; it replaces the pack's template for that name wholesale |
| the pack's template (`connectors:` mapping or `connector-templates:` in `pack.yaml`) | the pack's suggestion, written to the file once at the first start |

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
| in DuckDB, MongoDB, Redis or Aerospike | `duckdb`, `mongodb`, `redis`, `aerospike` | [duckdb](#duckdb), [mongodb](#mongodb), [redis](#redis), [aerospike](#aerospike) |
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

Everything else (how it reads, every setting, scale and limits, troubleshooting, a walk-through): [ICEBERG_CONNECTOR.md](ICEBERG_CONNECTOR.md).

## DuckDB

Plugin `duckdb`. A book in one embedded file.

A connector file (`config/connectors/<name>.yaml`):

```yaml
# config/connectors/trading-store.yaml
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
      columns: [tradeId, productType, productName, direction, currency, notional, mtm, pnl1d, maturityDate,
                tradeDate, book, desk, status, assetClass, counterparty.id, counterparty.name, nettingSet,
                risk.dv01, sourceSystem]
```

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

Everything else (how it reads, every setting, scale and limits, troubleshooting, a walk-through): [AEROSPIKE_CONNECTOR.md](AEROSPIKE_CONNECTOR.md).

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

Everything else (how it reads, every setting, scale and limits, troubleshooting, a walk-through): [FEEDS_CONNECTOR.md](FEEDS_CONNECTOR.md).

## News

Plugin `news`. Headlines for a named entity, computed on demand.

| You ask for | You get |
|---|---|
| `news NEWS-Federal_Reserve` | the newest headlines that name the Federal Reserve (or *Fed*, *FOMC*, ...), newest first, each with publisher, time, link and a short snippet; a count by publisher; and the list of steps that chose them |
| `news NEWS-Toyota@ja-JP` | the same from the Japanese edition |
| `news NEWS-Federal_Reserve~all` | everything the feed returned, without the relevance filter (the publisher lists and de-duplication still apply) |

Everything else (how it reads, every setting, scale and limits, troubleshooting, a walk-through): [NEWS.md](NEWS.md).

## Yahoo Finance

Plugin `yfinance`. A quote and price history for a ticker (samples only).

`QUOTE-AAPL` returns the last price, the change from the previous close in money and percent, the currency, the market state, the
day's and the 52-week range, the volume, the market capitalisation when Yahoo gives one, and the closing prices (with open, high,
low and volume) for the configured range at the configured interval. The console lays it out with the `quote` Sutra of the
`news-and-markets` pack: a strip, a closing-price line, the ranges and a table of bars.

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

Everything else (how it reads, every setting, scale and limits, troubleshooting, a walk-through): [RABBITMQ_CONNECTOR.md](RABBITMQ_CONNECTOR.md).


## Demo data

Building the demo book in each store, small to a million trades a day: [DEMO_DATA.md](DEMO_DATA.md). Reading your own database with your own SQL: [JDBC_QUERIES.md](JDBC_QUERIES.md).
