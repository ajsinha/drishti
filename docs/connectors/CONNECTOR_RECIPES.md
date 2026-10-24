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
# Connector recipes: the shipped connectors, and combining them

A connector file for each shipped connector type, and how connectors combine (several sources for one kind, a store with a stream, a pack with several connectors). These sections were part of [CONNECTOR_DEVELOPER_GUIDE.md](CONNECTOR_DEVELOPER_GUIDE.md); they moved here so that guide stays readable, and its old headings point here. Every setting of every type is in the per-connector pages listed in [CONNECTORS.md](CONNECTORS.md); running them is in [CONNECTOR_OPERATIONS.md](CONNECTOR_OPERATIONS.md).

## The shipped connectors

One short section per connector: what it is for, a minimal configuration, the layout it expects, how the data gets in,
and where it stops. Everything else (every setting and default, health texts, scale, diagnosing) is in the document the
last line of each section links to. Each example below is the connector file (`config/connectors/<name>.yaml`); a pack may suggest the same settings as a template in its `pack.yaml`. See
[Where configuration goes](CONNECTOR_DEVELOPER_GUIDE.md#where-configuration-goes) and [CONNECTOR_FILES.md](CONNECTOR_FILES.md).

### `file`

**For.** End-of-day files dropped in a folder by another system. **Use it when** you have no infrastructure and want a
connector in five minutes, or a large book in plain files (it indexes each day once and reads one line by offset).

```yaml
drishti: { sources: { plugins: { file: { enabled: true, settings: { root: ./data/files, source-name: eod-files } } } } }
```

or as a connector file (`config/connectors/eod-files.yaml`): `plugin: file`, `settings: { root: ./data/files }`.

**Layout.** JSON lines, one file per kind per business day: `<root>/<domain>/<yyyy-MM-dd>/<kind>.jsonl` (an undated
`<kind>.jsonl` also works); or a file per entity, `<root>/<kind>/<id>.json` or `.csv`, and dated
`<root>/<yyyy-MM-dd>/<kind>/<id>.json`. **Loader:** `tools/load-files.sh [root] [--trades N --days D]`.
**Limits:** no live updates; new files reach search after `rescan-seconds`; a kind or id that would leave its folder is
refused. → full reference: [FILE_CONNECTOR.md](FILE_CONNECTOR.md)

### `rest`

**For.** An in-house HTTP service that answers one entity as JSON per GET. **Use it when** the owning system already
has an API and you want to copy nothing.

```yaml
# config/connectors/positions-api.yaml
plugin: rest
kinds: [position]
settings: { base-url: "${POSITIONS_URL:http://localhost:9000/api}", path: "/{kind}/{id}", header.Authorization: "Bearer ${POSITIONS_TOKEN}" }
# the pack (or application.yaml) routes the kind:  routes: { position: positions-api }
```

**Layout.** `GET <base-url><path>` with `{kind}` and `{id}` substituted; `404` means not held, any other status of 400
or more is a failure. **Loader:** none. **Limits:** undated; no search, no reverse lookups; every view is a request to
the service. → full reference: [REST_CONNECTOR.md](REST_CONNECTOR.md)

### `jdbc` query mode

**For.** A database whose schema you keep: one SQL statement per kind. **Use it when** the data lives in tables you do
not want to copy and you can write the SQL.

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
`columns.<kind>` and `reverse.<kind>` queries; one request on your database per view. → full reference:
[JDBC_QUERIES.md](JDBC_QUERIES.md)

### `jdbc` table mode

**For.** PostgreSQL loaded for Drishti: every kind of a data domain in one table `(kind, id, business_date, doc jsonb,
<promoted columns>)`, partitioned by month. **Use it when** you want dated reads, search and reverse lookups from SQL at
scale.

```yaml
# config/connectors/trade-endur.yaml
plugin: jdbc
settings:
  url: ${DRISHTI_PG_URL:jdbc:postgresql://localhost:5432/drishti}
  user: ${DRISHTI_PG_USER:drishti}
  password: ${DRISHTI_PG_PASSWORD}
  table: trading.entities
```

**Layout.** One row per entity per business date, the pack's promoted fields as columns. **Loader:**
`tools/load-postgres.sh`. **Limits:** you run the load and size the indexes; `mode.<kind>` chooses `snapshot` or
`effective`. → full reference: [POSTGRES_CONNECTOR.md](POSTGRES_CONNECTOR.md)

### `delta`

**For.** A Delta Lake: the shipped default for history. **Use it when** you want years of business dates, *known at*
time travel, search and reverse lookups, with no Spark.

```yaml
# config/connectors/trade-endur.yaml
plugin: delta
kinds: [trade]
settings: { root: "${DRISHTI_DELTA_ROOT:${drishti.data.dir:./data}/delta}", domain: trading }
```

**Layout.** A table per kind, `<root>/<domain>/<kind>/`, rows `(id, doc)` plus the pack's promoted columns,
partitioned by `business_date`; `root` may be `s3a://bucket/lake`. **Loader:** `tools/load-delta.sh [root] [--trades N
--days D]` for samples; `tools/lake/maintain.py` keeps it bounded. **Limits:** *known at* resolves against the log
files' modification times (copy lakes with `cp -p`); codecs other than Snappy and ZSTD are refused with a clear error.
→ full reference: [DELTA_CONNECTOR.md](DELTA_CONNECTOR.md)

### `iceberg`

**For.** The other lake format: tables in an Iceberg catalog (path-based, or REST: Polaris, Snowflake Open Catalog,
Glue). **Use it when** your lake is Iceberg.

```yaml
# config/connectors/trade-endur.yaml
plugin: iceberg
kinds: [trade]
settings: { root: "${DRISHTI_ICEBERG_ROOT:${drishti.data.dir:./data}/iceberg}", domain: trading }
```

**Layout.** A table per kind, partitioned by business date, sorted by id, promoted columns beside the document.
**Loader:** `tools/load-iceberg.sh`; maintain with `IcebergMaintenance`. **Limits:** as for Delta Lake; delete files are
applied by every read. → full reference: [ICEBERG_CONNECTOR.md](ICEBERG_CONNECTOR.md)

### `duckdb`

**For.** One embedded DuckDB file for every data domain, read in-process. **Use it when** a desk, a laptop, a demo or a
single server holds a large book in one file and wants day-wide searches without a database server or a lake.

```yaml
# config/connectors/trade-endur.yaml
plugin: duckdb
settings: { path: "${DRISHTI_DUCKDB_PATH:${drishti.data.dir:./data}/duckdb/drishti.duckdb}", table: trading.entities }
```

**Layout.** A schema per domain, `<domain>.entities (kind, id, business_date, doc, <promoted columns>)`, each day sorted
by id. **Loader:** `tools/load-duckdb.sh` (`--keep-days`); a load writes a new file and renames it over the old one.
**Limits:** one file, one machine; native memory is outside the Java heap (`memory-limit`). → full reference:
[DUCKDB_CONNECTOR.md](DUCKDB_CONNECTOR.md)

### `mongodb`

**For.** A document database. **Use it when** entities already live in MongoDB, or you want replica sets and sharding
without a lake.

```yaml
# config/connectors/trade-endur.yaml
plugin: mongodb
settings: { uri: "${DRISHTI_MONGODB_URI:mongodb://localhost:27017}", database: drishti }
```

**Layout.** A collection per domain, a document per entity per business date (`_id` `kind/id/yyyyMMdd`), the promoted
fields under `c`, and a narrow `<domain>_columns` copy for searches. **Loader:** `tools/load-mongodb.sh`
(`--keep-days`, `--ttl-days`). **Limits:** promoted fields are cached in memory; MongoDB 8.0 does not start on some
recent kernels (use 7). → full reference: [MONGODB_CONNECTOR.md](MONGODB_CONNECTOR.md)

### `redis`

**For.** Today and recent days in memory, live through `<domain>:changes`. **Use it when** you need the newest days
at memory speed with live ticks, and Delta Lake behind it for history.

```yaml
# config/connectors/trade-endur.yaml
plugin: redis
settings: { uri: "${DRISHTI_REDIS_URI:redis://localhost:6379}" }
```

**Layout.** A compressed document per entity per day (zstd, dictionary per kind); promoted fields column-wise in chunks
of 10,000; days and ids in sorted sets. **Loader:** `tools/load-redis.sh` (`--ttl-days`, `--publish`). **Limits:**
about 1 GB of memory per million trades a day; TTL retention; a day Redis does not hold goes to the next store.
→ full reference: [REDIS_CONNECTOR.md](REDIS_CONNECTOR.md)

### `aerospike`

**For.** A key-value store with sub-millisecond reads. **Use it when** you have millions of entities a day and want
two key lookups per view, with retention by record TTL.

```yaml
# config/connectors/trade-endur.yaml
plugin: aerospike
settings: { hosts: "${DRISHTI_AEROSPIKE_HOSTS:localhost:3000}", namespace: "${DRISHTI_AEROSPIKE_NAMESPACE:test}" }
```

**Layout.** Three sets per domain: a record per entity per business date (`kind/id/yyyyMMdd`: the document and the
promoted bins), an index record per entity (its dates), a record per kind (its dates). **Loader:**
`tools/load-aerospike.sh`. **Limits:** searches, pick lists and reverse lookups need the pack's promoted bins
(`layout.<kind>.columns`). → full reference: [AEROSPIKE_CONNECTOR.md](AEROSPIKE_CONNECTOR.md)

### `cassandra`

**For.** Apache Cassandra (and ScyllaDB or DataStax Astra): a book of entities a day kept for years. **Use it when** you
already run Cassandra and want one entity in a few milliseconds and day-wide searches from a few narrow columns.

```yaml
# config/connectors/trade-endur.yaml (from config/connectors.examples/cassandra/)
plugin: cassandra
kinds: [trade]
settings:
  domain: trading
  contact-points: ${DRISHTI_CASSANDRA_CONTACT_POINTS:localhost:9042}
  local-datacenter: ${DRISHTI_CASSANDRA_DATACENTER:datacenter1}
```

**Layout.** A keyspace per domain; a table per kind with a row per entity per business date (the document and the promoted
columns, partitioned by date, generation and bucket), plus `entity_days` and `kind_days`; a day loaded as a new generation
appears whole or not at all. **Loader:** `tools/load-cassandra.sh` (`tools\windows\load-cassandra.ps1`).
→ full reference: [CASSANDRA_CONNECTOR.md](CASSANDRA_CONNECTOR.md)

### `iceberg-external`

**For.** Iceberg tables another system writes, read as they are (no Drishti layout). → full reference:
[ICEBERG_EXTERNAL_CONNECTOR.md](ICEBERG_EXTERNAL_CONNECTOR.md)

### `kafka`

**For.** Live entities from Kafka topics. **Use it when** the latest state per entity lives on a topic and views should
tick; pair it with a lake for history.

```yaml
# config/connectors/trading-stream.yaml
plugin: kafka
kinds: [trade]
settings: { bootstrap-servers: "${DRISHTI_KAFKA_BOOTSTRAP:localhost:9092}", topics: drishti.trading.trades, kind: trade, id-field: tradeId }
```

**Layout.** Messages are an envelope `{kind, id, doc}`, or whole documents with `kind` and `id-field`; a tombstone
deletes. **Loader:** none for production; `tools/samplegen/stream.py` replays and ticks the samples. **Limits:**
undated; reads every partition from the beginning (the topic is the state), so a restart replays it; `mode: ticks`
keeps nothing and only pushes. **Security:** `security.protocol`, `tls.*` ([TLS.md](TLS.md)), `sasl.*` (PLAIN, SCRAM, OAUTHBEARER, GSSAPI), `flavour: confluent` and the Schema Registry; `client.<property>` passes any Kafka property and wins. → full reference: [KAFKA_CONNECTOR.md](KAFKA_CONNECTOR.md)

### `activemq`

**For.** Live entities from ActiveMQ Classic queues and topics. **Use it when** systems publish entity changes to
ActiveMQ.

```yaml
# config/connectors/limits-mq.yaml
plugin: activemq
kinds: [credit-limit]
settings: { broker-url: "failover:(tcp://localhost:61616)", destinations: "queue:limits", kind.limits: credit-limit, id-field.limits: limitId }
```

**Layout.** A JSON message body on a destination with a `kind.<destination>`, or an envelope `{kind, id, doc}`; a
delete is `doc: null`. **Loader:** none: publish messages. **Limits:** a queue delivers once, so the connector keeps
the latest document per entity in a local RocksDB state store, the only copy, with a disk budget; see
[Message queues](#message-queues-activemq-rabbitmq). → full reference: [ACTIVEMQ_CONNECTOR.md](ACTIVEMQ_CONNECTOR.md)

### `rabbitmq`

**For.** The same as ActiveMQ, for RabbitMQ (AMQP 0-9-1).

```yaml
# config/connectors/margin-mq.yaml
plugin: rabbitmq
kinds: [margin-call]
settings: { uri: "${RABBIT_URI}", queues: drishti.margin-calls, kind.drishti.margin-calls: margin-call, id-field.drishti.margin-calls: callId }
```

**Layout, loader, limits:** as for ActiveMQ; queues are declared durable unless `declare: false`, messages are
acknowledged only once the state store has kept them. A consumer the broker cancels is not re-subscribed until a
restart. → full reference: [RABBITMQ_CONNECTOR.md](RABBITMQ_CONNECTOR.md)

### `s3`

**For.** JSON documents in Amazon S3 or an S3-compatible store (MinIO, Ceph). **Use it when** the `file` layout already
lives in object storage.

```yaml
# config/connectors/risk-docs.yaml
plugin: s3
kinds: [stress-result]
settings: { bucket: "${RISK_DOCS_BUCKET:risk-docs}", prefix: eod/, region: us-east-1 }
```

**Layout.** `<prefix><kind>/<id>.json` and dated `<prefix><yyyy-MM-dd>/<kind>/<id>.json`. **Loader:** none (any
uploader: `aws s3 sync`, `mc cp`). **Limits:** listing a large bucket costs; ids and dates are listed every
`rescan-seconds` and reads cached `cache-seconds`; no reverse lookups. → full reference: [S3_CONNECTOR.md](S3_CONNECTOR.md)

### `feed`

**For.** Public market data: NY Fed SOFR, ECB €STR and FX, the US Treasury curve, FRED. **Use it when** you want real
rates next to your own data; each is off until one variable switches it on.

```yaml
# config/connectors/nyfed-sofr-feed.yaml
plugin: feed
enabled: true
kinds: [rate-fixing]
settings: { feed: nyfed-sofr, refresh-minutes: '60' }
```

**Layout.** One connector (one file) per feed, suggested by the market-data pack; entities carry the feed in their id
(`FIX-SOFR-NYFED`). **Loader:** none. **Limits:** needs the internet (or a mirror: `url: file://…`); FRED needs a free
key; a failed fetch keeps the last good data and shows in health. → full reference: [FEEDS_CONNECTOR.md](FEEDS_CONNECTOR.md)

### `news`

**For.** Headlines about a named entity, **computed on demand** from Google News RSS (no key): the id carries the name, the connector
searches, filters for relevance, drops duplicates and denied publishers, sorts newest first and caches per name. **Use it when** a page
should show the news about the entity being viewed. It is the example of [a connector that is code, not a store](CONNECTOR_DEVELOPER_GUIDE.md#connectors-that-compute-data-on-demand).

```yaml
# config/connectors/news.yaml  (written from the news-and-markets pack's template, enabled: false)
plugin: news
enabled: true
kinds: [news]
settings: { relevance: title, max-items: '15', cache-ttl-seconds: '600', deny-sources: "Some Spam Site", aliases.Federal_Reserve: "Fed, FOMC" }
```

**Layout.** Ids `NEWS-<name with underscores>[@lang-CC][~all]`. **Loader:** none. **Limits:** needs the internet; the entity names are sent to
Google (off as shipped); the first view of a name is pending under the 40 ms link budget. → full reference: [NEWS.md](NEWS.md)

### `yfinance`

**For.** A quote and recent price history for a ticker, **computed on demand** from Yahoo Finance's public chart endpoint (no key). **Use it
for** samples and internal demonstrations only: the endpoint is unofficial and Yahoo's terms restrict its use.

```yaml
# config/connectors/yfinance.yaml  (written from the news-and-markets pack's template, enabled: false)
plugin: yfinance
enabled: true
kinds: [quote]
settings: { range: 6mo, interval: 1d, cache-ttl-seconds: '60' }
```

**Layout.** Ids `QUOTE-<symbol>`. **Loader:** none. **Limits:** needs the internet; not for production or redistribution; an unknown symbol is not
held. → full reference: [YFINANCE.md](YFINANCE.md)

### `demo`

**For.** The enabled packs' sample entities (`config/packs/<pack>/samples/`), so everything works with nothing installed.
It runs as itself and serves every kind; it ticks live (`_meta.walk` random-walks the named fields).

```yaml
drishti: { sources: { plugins: { demo: { enabled: false } } } }    # or DRISHTI_DEMO_ENABLED=false
```

**Layout.** `samples/catalog.json` per pack lists the documents. **Loader:** each pack's `tools/` generate the samples.
**Limits:** it is the `default-route`: switch it off in production so a live view cannot tick from samples. → full
reference: [DEMO_CONNECTOR.md](DEMO_CONNECTOR.md) (the sample data itself: [DEMO_DATA.md](DEMO_DATA.md))

**Also built in: `derived`.** Kinds computed from other kinds (members grouped by an expression, with `count`, `sum`,
`avg`, …), read through whichever connectors serve the members. It is part of the engine, not a plugin: see
[PACK_DEVELOPER_GUIDE.md](../guides/PACK_MODELLING_GUIDE.md#derived-kinds-entities-computed-from-other-kinds).

### Message queues (ActiveMQ, RabbitMQ)

A queue delivers each message once and keeps no history, unlike a Kafka topic, so these two connectors keep the latest
document of every entity themselves, in a persistent state store on local disk that survives restarts, and acknowledge
a message only after the store has kept it. How a message becomes an entity, the durability modes, the disk budget and
eviction are in [ACTIVEMQ_CONNECTOR.md](ACTIVEMQ_CONNECTOR.md#7-durability-and-disk-budget) and
[RABBITMQ_CONNECTOR.md](RABBITMQ_CONNECTOR.md#6-durability-and-disk-budget); minimal configurations are the
[activemq](#activemq) and [rabbitmq](#rabbitmq) sections above.

---

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
  {"name":"credit-store","kinds":["credit-limit","netting-set","sa-ccr","cva","exposure-profile"],"live":false,"search":true,"reverseLookup":true,"health":"UP"},
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
  start, naming both. See [PACKS.md](../guides/PACKS.md).
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
