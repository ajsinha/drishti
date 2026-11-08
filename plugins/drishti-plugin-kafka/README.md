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
connector: kafka
category: plugin
summary: Live entities from Kafka topics, the latest message per key, read from the beginning of every partition with no consumer group; views tick on each message.
capabilities:
- fetch
- search
- subscribe
settings:
- bootstrap-servers
- topics
- kind
- id-field
- kind.*
- id-field.*
- mode
- search
- poll-ms
- cache-mb
- disk-cache.*
- client.*
- value-format
- flavour
- security.protocol
- sasl.mechanism
- sasl.username
- sasl.password
- sasl.password-file
- api-key
- api-secret
- api-secret-file
- sasl.oauth.token-endpoint
- sasl.oauth.client-id
- sasl.oauth.client-secret
- sasl.oauth.client-secret-file
- sasl.oauth.scope
- sasl.kerberos.principal
- sasl.kerberos.keytab
- sasl.kerberos.service-name
- sasl.kerberos.use-ticket-cache
- sasl.kerberos.krb5-conf
- schema-registry.url
- schema-registry.basic-auth
- schema-registry.basic-auth-file
- schema-registry.bearer-token
- schema-registry.bearer-token-file
- schema-registry.timeout-ms
- tls.*
- proxy.*
auth: None, TLS or mutual TLS (tls.*, PEM or PKCS12/JKS), SASL PLAIN, SCRAM-SHA-256/512, OAUTHBEARER and GSSAPI (Kerberos keytab or ticket cache), Confluent Cloud API key and secret (flavour confluent); Schema Registry by basic auth or bearer token. Secrets as ${ENV} or a *-file setting.
licence: Apache Kafka client (kafka-clients, Apache-2.0) and Apache Avro (Apache-2.0, for Schema Registry payloads), bundled; the optional disk cache uses RocksDB (rocksdbjni, Apache-2.0/GPL-2.0), bundled.
windows: Works on Windows (pure Java client); the optional disk cache's RocksDB library is unpacked from its jar into the server's temp folder (no .exe). The broker runs elsewhere; the Testcontainers tests skip where Docker is not reachable.
doc: plugins/drishti-plugin-kafka/README.md
---
# The kafka connector

Plugin `kafka` (`plugins/drishti-plugin-kafka`): the latest state of each entity from one or more topics, kept current for as long
as the server runs, with views that repaint within a frame of a new message. Pair it with a dated store (Delta Lake, PostgreSQL,
Aerospike) for history.

## What it reads

A **compacted topic keyed by entity** is the store: at start the connector assigns itself every partition of each topic in `topics`
(`assign`, not `subscribe`), seeks to the beginning and reads for as long as it runs; the latest message per entity wins. It sets no
`group.id` and commits nothing, so every Drishti server reads the whole topic on its own. It keeps an index of where each entity's
latest message is, a memory cache (and, if enabled, a RocksDB disk cache), and type-ahead by id. It is undated (no business dates),
declares no reverse lookups and no field searches.

`mode: ticks` keeps no index and answers no reads: it only pushes new messages to open views of its kinds, whichever store answered
them (a lake holds the day, the topic is the change feed).

### When to use it

| Use it for | Prefer another store for |
|---|---|
| entities whose latest state is published to a topic whenever it changes (trades, positions, quotes, limits) | history: the connector is undated; pair it with a dated store ([With a lake for history](#with-a-lake-for-history)) |
| views that must tick within a frame of a change | reverse lookups (*Linked entities*, impact with F8): it declares none |
| a change feed over data a store already serves (`ticks` mode) | searches over fields of the documents: it offers type-ahead by id only |
| a topic other systems already consume, read without a consumer group | a queue that delivers each message once ([ActiveMQ](../drishti-plugin-activemq/README.md), [RabbitMQ](../drishti-plugin-rabbitmq/README.md): they keep their own state store) |

On a compacted topic Kafka keeps the latest message per key, so the topic's size follows the number of entities, not the
number of changes, and a restart replays one message per entity.

### How it reads a topic

- **No consumer group, no commits.** `enable.auto.commit` is `false` and no `group.id` is set. Every Drishti server reads the
  whole topic on its own and builds the same state; adding a server rebalances nobody.
- **One consumer thread per connector** (virtual thread `drishti-kafka-<source-name>`) polls every `poll-ms` and applies records
  in poll order. In state mode a second consumer, the **reader** (`drishti-<source-name>-reader`, `max.poll.records` 1), reads a
  single record back by offset when a document is not cached.
- **Ordering** is per partition: the latest message for a key wins provided every message of an entity goes to the same partition
  (the producer's default partitioner does that for a fixed key). An entity written under two keys (`NS-1` and `netting-set/NS-1`)
  in two partitions has no defined winner.
- **Generation and time:** the generation is the message offset, `fetchedAt` the message timestamp. The newest applied timestamp is
  `lastUpdate` in Admin → Health, compared with `stale-after` (the trading pack sets `15m` on `trading-stream`).
- How a tick reaches the browser: [DATA_ARCHITECTURE.md, Streaming sources](../../docs/architecture/DATA_ARCHITECTURE.md#streaming-sources).

## Settings

<!-- settings:begin (generated by tools/connector_readmes.py from drishti-engine/src/main/resources/drishti/plugin-settings.yaml; edit the catalogue, not this table) -->
| Setting | Type | Default | Meaning |
|---|---|---|---|
| `bootstrap-servers` | string | `localhost:9092` | Broker list host:port,... |
| `topics` | list |  | **Required.** Comma list; without it the plugin stays idle (`PluginNotConfigured`) |
| `kind` | string |  | Mapped messages on every topic: their kind |
| `id-field` | string | `id` | Mapped messages without a key: the id field, every topic |
| `kind.*` | string |  | Kind per topic: kind.<topic> |
| `id-field.*` | string |  | Id field per kind: id-field.<kind> |
| `mode` | one of state, ticks | `state` | `state` (index, caches, reads) or `ticks` (no reads; pushes to views of its kinds) |
| `search` | boolean | `true` | Keep ids for type-ahead (state mode only) |
| `poll-ms` | int | `200` | Poll interval |
| `cache-mb` | int | `256` | Memory cache, weighed by message length |
| `disk-cache.*` | string |  | On-disk cache (enabled, dir, max-gb, ...) |
| `client.*` | string |  | Any Kafka client property (client.max.poll.records); applied last, wins over everything above. Fetches default to `fetch.max.bytes` 4 MB and `max.partition.fetch.bytes` 1 MB (Kafka's own default is 50 MB; each poll thread reads a fetch through a temporary direct buffer): `client.fetch.max.bytes` and `client.max.partition.fetch.bytes` raise them |
| `value-format` | one of string, confluent |  | string (default), or confluent to read the Confluent wire format (implied by schema-registry.url) |
| `flavour` | one of apache, confluent | `apache` | confluent: Confluent Cloud defaults (SASL PLAIN with api-key and api-secret, DNS lookup of all addresses) |
| `security.protocol` | one of PLAINTEXT, SSL, SASL_PLAINTEXT, SASL_SSL |  | `PLAINTEXT`, `SSL`, `SASL_PLAINTEXT` or `SASL_SSL`. Absent: `SASL_SSL` if `sasl.mechanism` is set and TLS is wanted (`tls.enabled: true`), `SASL_PLAINTEXT` with a mechanism and no TLS, `SSL` with `tls.enabled`, else `PLAINTEXT`. Always write it down in production. |
| `sasl.mechanism` | one of PLAIN, SCRAM-SHA-256, SCRAM-SHA-512, OAUTHBEARER, GSSAPI |  | `PLAIN`, `SCRAM-SHA-256`, `SCRAM-SHA-512`, `OAUTHBEARER` or `GSSAPI`. |
| `sasl.username` | string |  | PLAIN and SCRAM. Confluent Cloud calls them `api-key` and `api-secret`, accepted as aliases. |
| `sasl.password` | string |  | PLAIN and SCRAM. Confluent Cloud calls them `api-key` and `api-secret`, accepted as aliases. (secret: give `${ENV_VAR}` or `file:/path`, never the value) |
| `sasl.password-file` | path |  | PLAIN and SCRAM. Confluent Cloud calls them `api-key` and `api-secret`, accepted as aliases. |
| `api-key` | string |  | Confluent Cloud API key (same as sasl.username) |
| `api-secret` | string |  | Confluent Cloud API secret (same as sasl.password): ${ENV} placeholder or api-secret-file (secret: give `${ENV_VAR}` or `file:/path`, never the value) |
| `api-secret-file` | path |  | File whose first line is the Confluent Cloud API secret |
| `sasl.oauth.token-endpoint` | string |  | OAUTHBEARER: the identity provider's token URL. |
| `sasl.oauth.client-id` | string |  | OAUTHBEARER: the client-credentials pair. |
| `sasl.oauth.client-secret` | string |  | OAUTHBEARER client secret: ${ENV} placeholder or sasl.oauth.client-secret-file (secret: give `${ENV_VAR}` or `file:/path`, never the value) |
| `sasl.oauth.client-secret-file` | path |  | File whose first line is the OAUTHBEARER client secret |
| `sasl.oauth.scope` | string |  | OAUTHBEARER: the scope to request. |
| `sasl.kerberos.principal` | string |  | GSSAPI: e.g. `drishti@BANK.EXAMPLE`. |
| `sasl.kerberos.keytab` | path |  | GSSAPI keytab file |
| `sasl.kerberos.service-name` | string | `kafka` | GSSAPI broker service name |
| `sasl.kerberos.use-ticket-cache` | boolean | `false` | GSSAPI: use the ticket cache instead of a keytab |
| `sasl.kerberos.krb5-conf` | path |  | GSSAPI: path of `krb5.conf` (sets `java.security.krb5.conf` if the JVM has none yet). |
| `schema-registry.url` | string |  | Confluent Schema Registry URL (http:// or https://); reads Avro, Protobuf and JSON Schema payloads |
| `schema-registry.basic-auth` | string |  | KEY:SECRET for the registry: ${SR_KEY}:${SR_SECRET}, or schema-registry.basic-auth-file (secret: give `${ENV_VAR}` or `file:/path`, never the value) |
| `schema-registry.basic-auth-file` | path |  | File whose first line is KEY:SECRET |
| `schema-registry.bearer-token` | string |  | Bearer token for the registry (not with basic-auth): ${ENV} placeholder or schema-registry.bearer-token-file (secret: give `${ENV_VAR}` or `file:/path`, never the value) |
| `schema-registry.bearer-token-file` | path |  | File whose first line is the bearer token |
| `schema-registry.timeout-ms` | int | `10000` | Connect and read timeout for a registry call. |

Every connector also takes `source-name`, `mode.*`, `domain`, `stale-after`, `union` (docs/connectors/CONNECTOR_FILES.md). It takes the shared `tls.*` settings (docs/connectors/TLS.md), also under `schema-registry.tls.*`. It takes the shared `proxy.*` settings for an outbound HTTP proxy (CONNECTOR_DEVELOPER_GUIDE.md).
<!-- settings:end -->

The site-wide summary is in [CONFIGURATION.md](../../docs/admin/CONFIGURATION.md#kafka-tls-proxy); the pack environment variables
(`DRISHTI_STREAM_TRADING`, `DRISHTI_KAFKA_BOOTSTRAP`, …) in
[CONFIGURATION.md](../../docs/admin/CONFIGURATION.md#environment-variables-used-by-the-packs-and-profiles).

- Setting keys with dots are literal: a topic such as `desk_orders` is simply `kind.desk_orders: order` (nested YAML flattens to
  the same key).
- Give a Kafka connector `kinds:`. Its manifest lists `kind`, every `kind.<topic>` and every kind seen in a message; an envelope
  connector with no `kinds` serves *every* kind until its first message.
- Set by the connector and not to be overridden: `key.deserializer` and `value.deserializer`, `enable.auto.commit` (`false`),
  `client.id` (`drishti-<source-name>`; set `client.client.id` when broker quotas keyed by client id must not be shared between
  servers). Fixed in the code: 10 s without a broker connection before health says so, 30 s to list a topic's partitions, 5 s for a
  cold read, supervisor backoff 1 s to 30 s.

## Data structure

Keys are strings and values JSON text (or the Confluent wire format with `value-format: confluent` / `schema-registry.url`, decoded
to JSON: Avro, Protobuf, JSON Schema). A topic is read in one of two shapes:

- **Mapped** (the topic has `kind.<topic>`, or `kind` for every topic): the value is the whole document. **Id rule:** the message key;
  without a key, the document's field `id-field.<topic>` (else `id-field`, default `id`). Keep the key equal to the id: a keyed message
  is indexed by its key without parsing the value.

  ```text
  topic: drishti.trading.trades      key: MX-20000001
  value: {"SOURCE_SYSTEM": "MUREX", "TRADE_ID": "MX-20000001", "PRODUCT_TYPE": "IRS_FIXFLOAT", "CURRENCY": "AUD", "LEGS": […], …}
  ```

- **Envelope** (no kind for the topic): one topic carries many kinds. Key it `<kind>/<id>` so compaction and tombstones work.

  ```text
  topic: risk.envelopes              key: netting-set/NS-ALDERSHOT-FRA
  value: {"kind": "netting-set", "id": "NS-ALDERSHOT-FRA", "doc": {"NETTING_SET_ID": "NS-ALDERSHOT-FRA", "NET_MTM": -339550, …}}
  ```

- **Deletes:** a null value (a tombstone) on a mapped key or an envelope key `<kind>/<id>`, or an envelope with `"doc": null`, removes
  the entity from the index, the caches and type-ahead, and is pushed to open views (greyed, with when it was deleted). An empty value is
  not a tombstone; an envelope tombstone keyed by a bare id is ignored.
- **Provenance:** `generation` is the message offset, `fetchedAt` the message timestamp, `live: true`, no `businessDate`. The document
  is the value exactly; nested structures (`LEGS[].CASH_FLOWS[]`, `EVENTS[]`) stay inside it.

A keyed message on a mapped topic is indexed under its key **without parsing the value**; the value is parsed only when someone views
the entity or it is cached. That is what makes mapped topics cheap at volume. If the document's `id-field` disagrees with the key, a
parsed message is tracked under both. A compacted topic refuses a message without a key. Every envelope is parsed; an envelope whose
value is not JSON, or has no `kind` or `id`, is skipped. A mapped message whose value is not JSON is indexed by key, and a later read
of it answers "not held".

A delete is pushed to open views as a `deleted` patch: the view keeps its last document, greyed, under a banner with the tombstone's
time, and repaints if a later message brings the entity back
([DATA_ARCHITECTURE.md, Deleted entities](../../docs/architecture/DATA_ARCHITECTURE.md#deleted-entities)); this works in
`mode: ticks` too. The id leaves type-ahead at once, and opening it answers "not held" so the next connector is asked. With
`kafka-console-producer.sh` send a real null with `--property null.marker=NULL` and the line `MX-20000001|NULL`.

## Examples

```yaml
# config/connectors/trading-stream.yaml
plugin: kafka
enabled: ${DRISHTI_STREAM_TRADING:false}
kinds: [trade]
description: Live trades from Kafka
settings:
  bootstrap-servers: ${DRISHTI_KAFKA_BOOTSTRAP:localhost:9092}
  topics: [drishti.trading.trades]
  kind: trade
  id-field: TRADE_ID
  stale-after: 15m
  disk-cache.enabled: true          # cold reads from local disk instead of the one reader consumer
```

Ready-made files in `config/connectors.examples/other/`: `trading-stream.yaml` (mutual TLS, PKCS12), `kafka-mtls-pem.yaml` (mutual TLS,
PEM) and `kafka-confluent-cloud.yaml` (Confluent Cloud with Schema Registry). `tools/samplegen/stream.py` publishes and ticks the
trading samples for a try-out. A connector file is applied within seconds without a restart, and edited in any editor, in
**Admin → Connectors** or with `drishti.py connector apply` ([CONNECTOR_FILES.md](../../docs/connectors/CONNECTOR_FILES.md)).

The trading pack suggests `trading-stream` in `config/packs/banking/trading/pack.yaml`, off until `DRISHTI_STREAM_TRADING=true`; the
server writes it to `config/connectors/trading-stream.yaml` at the first start, and the file is then the site's:

```yaml
connectors:
  trading-stream:
    plugin: kafka
    enabled: ${DRISHTI_STREAM_TRADING:false}
    kinds: [trade]
    settings:
      bootstrap-servers: ${DRISHTI_KAFKA_BOOTSTRAP:localhost:9092}
      topics: ${DRISHTI_TRADING_TOPIC:drishti.trading.trades}
      kind: trade
      id-field: TRADE_ID
      stale-after: ${DRISHTI_STREAM_STALE_AFTER:15m}
      disk-cache.enabled: ${DRISHTI_STREAM_DISK_CACHE:true}
      disk-cache.root: ${DRISHTI_CACHE_ROOT:${drishti.data.dir:./data}/cache}    # → ./data/cache/trading-stream
      disk-cache.max-gb: ${DRISHTI_STREAM_CACHE_GB:10}
      disk-cache.reset-at: ${DRISHTI_CACHE_RESET_AT:02:00}
      disk-cache.zone: America/New_York
```

Envelopes and a mapped topic on a secured cluster (`config/connectors/risk-stream.yaml`):

```yaml
plugin: kafka
kinds: [netting-set, credit-limit]                    # without it an envelope connector serves every kind
settings:
  bootstrap-servers: kafka1.bank.example:9093,kafka2.bank.example:9093
  topics: risk.envelopes,risk.limits
  kind.risk.limits: credit-limit                      # this topic: whole credit-limit documents
  id-field.risk.limits: limitId
  cache-mb: '512'
  stale-after: 30m
  security.protocol: SASL_SSL
  sasl.mechanism: SCRAM-SHA-512
  sasl.username: drishti
  sasl.password: ${KAFKA_PASSWORD}
  tls.ca-file: /etc/drishti/tls/ca.pem
```

The stream as a change feed over the lake: the same file as `trading-stream` with `mode: ticks` (the file replaces the pack's
template wholesale, so it repeats the topic, kind and id field).

### Try it

```bash
docker compose -f deploy/docker/compose.data.yaml up -d kafka            # apache/kafka 3.9.1, single-node KRaft, compacted
uv run --with kafka-python python tools/samplegen/stream.py \
    --bootstrap localhost:9092 --topic drishti.trading.trades --samples config/packs/banking/trading/samples --rate 5 --seconds 0
DRISHTI_PACKS=trading DRISHTI_STREAM_TRADING=true DRISHTI_DEMO_ENABLED=false \
  java -jar drishti-server/target/drishti-server-*-exec.jar
```

`stream.py` publishes every trade of the pack's `samples/trade.jsonl` keyed by `TRADE_ID` (`published 765 trades to
drishti.trading.trades; ticking 5.0/s`), then `--rate` times a second moves a random trade's `PRICE` by a Gaussian step (seed 7),
stamps `SOURCE_UPDATE_TIME` and publishes the whole document again; `--seconds 0` runs until Ctrl+C. `TRD MX-20000001 <GO>` on Live
shows provenance `trading-stream` and ticks; pick a date and the same trade comes from `trade-endur` (the lake), a static snapshot.
The samples are switched off because they hold the same ids: a trade Kafka does not hold then falls through to the lake, not to a
random-walk sample.

One envelope by hand (create the topic first on a broker that does not create topics by itself):

```bash
docker compose -f deploy/docker/compose.data.yaml exec kafka /opt/kafka/bin/kafka-topics.sh \
    --bootstrap-server localhost:9092 --create --if-not-exists --topic risk.envelopes --config cleanup.policy=compact
docker compose -f deploy/docker/compose.data.yaml exec -T kafka /opt/kafka/bin/kafka-console-producer.sh \
    --bootstrap-server localhost:9092 --topic risk.envelopes --property parse.key=true --property key.separator='|' <<'EOF'
netting-set/NS-ALDERSHOT-FRA|{"kind":"netting-set","id":"NS-ALDERSHOT-FRA","doc":{"NETTING_SET_ID":"NS-ALDERSHOT-FRA","TRADE_COUNT":3,"NET_MTM":-339550}}
EOF
curl -s localhost:18480/api/v1/entities/netting-set/NS-ALDERSHOT-FRA/raw | jq -c .provenance   # "source":"risk-stream","live":true,"generation":0
```

## Modes: state and ticks

| | `state` (default) | `ticks` |
|---|---|---|
| reads (`fetch`) | answered from the topic | never: always "not held" |
| index, memory cache, disk cache, reader consumer | yes (disk cache when enabled) | no |
| type-ahead | yes (`search: true`) | no |
| pushes new messages to open views | views it answers | views of its kinds, whichever store answered |
| reads the topic from the beginning at start | yes | yes (to reach the end; mapped keyed messages are not parsed unless watched) |
| use it when | the topic is the store for Live | a lake or database already holds the day's data, or the topic is too large to index |

In `ticks` mode a view of `TRD MX-20000001` reads from `trade-endur` (its provenance names the lake), shows the live dot, and each
message for the trade repaints it within a frame ([CONNECTORS.md, Combining connectors](../../docs/connectors/CONNECTORS.md#combining-connectors)).

## Read paths and caches

| Path | State mode | Ticks mode |
|---|---|---|
| one entity, cached in memory | a map lookup, no I/O | not held |
| one entity, on the disk cache | one RocksDB get and a JSON parse | not held |
| one entity, neither | one record read back from Kafka by offset, through the one reader | not held |
| type-ahead | in memory, prefix then substring match, subtitle `<kind> · <source-name>` | none |
| a picked business date | asked after the dated stores; answers its current document with the *No data held for <date>* banner, no ticks | not held |
| reverse lookups, field searches, pick lists, derived kinds, impact | none; the dated stores serve them | none |
| live push | every message for a watched entity | every message for a watched entity of its kinds |

**One entity, state mode:** (1) the memory cache, which a new message for a cached or watched entity replaces as it arrives;
(2) the index (topic, partition, offset); no entry is "not held"; (3) the disk cache; (4) the reader seeks to the offset and polls
(200 ms at a time, 5 s at most). Cold reads run one at a time behind one lock (at most 5 s wait). A record that cannot be read (broker
away, offset removed by retention, not JSON) is a miss, not an error. A document read this way is cached only if the index still
points at the same offset, so a stale or deleted document never sticks. With the default `drishti.sources.fetch-timeout` (10 s) a
cold read while the broker is away answers "not held" after 5 s and the next store answers; at or below 5 s it ends with `DRS-1004`.

**Live push:** each message for a watched entity is parsed once on the consumer thread and handed to every listener; the engine
merges ticks within a frame (`drishti.live.frame`, 50 ms) and keeps one subscription per entity however many watch it
([DATA_ARCHITECTURE.md, Guarantees and limits](../../docs/architecture/DATA_ARCHITECTURE.md#guarantees-and-limits)). Type-ahead is
built in one go at the first catch-up (empty until then), then grows per new entity.

**Memory.** The index (kind, id, topic, partition, offset per entity) and the type-ahead index grow with the number of entities;
recently read and watched documents are bounded by `cache-mb`, weighed by message text length (4,096 when unknown). Parsed documents
take more heap than their text, so budget a multiple of `cache-mb`; `cache-mb: 0` keeps nothing.

**Disk** (`disk-cache.enabled: true`, state mode): every applied message is also written to the connector's own RocksDB store, so a
document not in memory is read from local disk instead of Kafka. It lives in `disk-cache.dir`, default
`<disk-cache.root>/<source-name>`, one `gen-<millis>-<n>` folder per generation; mapped values raw, envelopes as their `doc`, keyed
`<kind>/<id>`, LZ4, no write-ahead log. FIFO compaction drops the oldest files past `disk-cache.max-gb` (10). It is cleared nightly at
`disk-cache.reset-at` (`02:00`, `never` or blank never) in `disk-cache.zone` by swapping in an empty store (readers never wait), and
deleted at every start because the topic is replayed anyway. Health shows `diskMb`, `diskHits`, `diskMisses`, `diskClears`. **Purge**
(Admin → Caches) drops the memory cache and clears the disk cache; the index stays.

## Start, restarts and outages

| Moment | What happens | Health |
|---|---|---|
| start | settings read; the reader created (state mode); the consumer thread started | `DOWN: not started` |
| first connection | partitions listed (30 s at most per topic), assigned, each seeked to its beginning; end offsets noted | `UP (catching up)` |
| catch-up | every message applied; reads answer from what is applied so far (possibly an older version) | `UP (catching up)` |
| every partition at the end offset noted at start | type-ahead built | `UP` |

**A restart of Drishti** starts from nothing and reads the topic again from the beginning (one message per entity on a compacted
topic); nothing is lost while Drishti is down. **A reconnect inside a running server** resumes per partition at the next offset after
the last applied, without replay. The Kafka client rides out a broker outage inside `poll()`; with no broker connection for 10 s the
health says `DOWN: no connection to the broker (reconnecting)` and returns to `UP` when a connection is back. When the client gives
up (broker down at start, topic missing, a fatal error), a supervisor recreates the consumer, waiting 1 s doubling to 30 s.

| Health | When |
|---|---|
| `DOWN: <SimpleName>: <message> (reconnecting)` | the consumer failed, e.g. `IllegalStateException: topic … has no partitions yet`, or `TimeoutException` when no broker answered the metadata request within 30 s |
| `DOWN: <message> (retrying)` | the supervisor could not create a consumer (ticks mode; in state mode a configuration error the client rejects stops the start: `failedToStart`) |

While the broker is away, cached documents still answer, other reads fall through to the next store after the 5 s reader wait, and
open views resume ticking with the next message. If retention removed messages during the outage, the consumer applies
`auto.offset.reset`, Kafka's default `latest` unless `client.auto.offset.reset: earliest`; with `latest` the messages between are
skipped until the next restart.

## Scale

- **Memory per entity** (an estimate from the data structures): an index entry of about 150–200 bytes and a type-ahead entry of about
  200–250 bytes for an 11-character id, so roughly **0.4–0.5 GB of heap per million entities**, plus the memory cache and the
  client's fetch buffers.
- **Throughput:** one consumer thread applies every message; keyed mapped messages are decoded as text and indexed without parsing
  JSON unless watched or cached; envelopes are parsed one by one. Catch-up time is reading the topic once; watch `indexedEntities` grow
  in Health. Cold reads queue behind the one reader; the disk cache removes most of them.
- **A million entities a day:** a compacted topic replays about a million messages at restart. With documents of about 7.8 KB of JSON
  one version of every trade is about 7.8 GB before LZ4; keep `disk-cache.max-gb` above the compressed size of the day's distinct
  documents. A topic retained by time replays every change: keep retention near one business day or compact it. Too large to index,
  or already loaded into a store: `mode: ticks`.
- **Several servers:** each reads every partition in full and keeps its own index, so the broker serves the topic once per server.

### With a lake for history

Configure the same kind on a dated store as well and the router does the rest
([CONNECTORS.md, Combining connectors](../../docs/connectors/CONNECTORS.md#combining-connectors)):

| The user asks for | Order tried | Who answers |
|---|---|---|
| Live | `trading-stream` (live first) → `trade-endur` → `file` | Kafka; a trade Kafka does not hold (matured, deleted) falls through to the lake's newest date |
| a picked date | `trade-endur` (dated first) → `file` → `trading-stream` | the lake for that date; Kafka's current document comes last, with the *No data held for <date>* banner |

With `mode: ticks` the lake answers every read and Kafka only ticks the open views. Long history in the lake:
[the delta connector](../drishti-plugin-delta/README.md).

## Security: TLS, SASL and Confluent

The connector speaks to Apache Kafka and Confluent (Cloud and Platform) over plaintext, TLS, SASL over plaintext and SASL over TLS.
The first-class settings (`security.protocol`, `tls.*` from the [shared TLS module](../../docs/connectors/TLS.md), `sasl.*`,
`flavour`, `schema-registry.*`) are checked at start (files read by Drishti itself: PEM, PKCS12, JKS; the file and reason named when
one is wrong; contradictions such as `sasl.mechanism` with `SSL`, or a mechanism without credentials, refused) and translated to
Kafka properties. `client.<property>` passes any Kafka property through, applied last:

```
connector defaults  <  flavour preset  <  first-class settings (security.protocol, tls.*, sasl.*)  <  client.<property>
```

| Your cluster | Use |
|---|---|
| Apache Kafka, TLS, brokers on a private CA | `security.protocol: SSL`, `tls.ca-file` (or `tls.truststore` for PKCS12/JKS) |
| brokers require a client certificate | `tls.cert-file`/`tls.key-file` (PKCS#8, PKCS#1, SEC1 or encrypted; `tls.key-password`) or `tls.keystore` (+ `tls.key-alias` when it holds several keys) |
| user name and password over TLS | `SASL_SSL` + `SCRAM-SHA-512` (or `PLAIN`), `sasl.username`, `sasl.password` or `sasl.password-file` |
| an identity provider issues tokens (Keycloak, Okta, Entra ID) | `SASL_SSL` + `OAUTHBEARER`, `sasl.oauth.token-endpoint`, `-client-id`, `-client-secret`, optional `-scope` |
| Kerberos / Active Directory | `SASL_SSL` + `GSSAPI`, `sasl.kerberos.principal`, `-keytab`, `-service-name`, optional `-krb5-conf` |
| Confluent Cloud | `flavour: confluent` + `api-key`, `api-secret` |
| Confluent Platform on-premises with mTLS | `flavour: confluent`, `security.protocol: SSL`, `tls.*` |
| a dev broker | nothing (`PLAINTEXT`) |

Use the listener's own port and protocol (from the broker's `listeners` and `sasl.enabled.mechanisms`): a wrong protocol fails with a
timeout, not a message. `tls.trust-jvm-default: true` keeps the public CAs beside a private one (the sets are merged into one PEM
truststore). `tls.insecure-trust-all` is not available for Kafka; use `tls.ca-file` and, if names mismatch, `tls.verify-hostname:
false`. Secrets are `${ENV}` placeholders or `…-file` paths, never inline.

- **Mutual TLS:** the broker maps the certificate to a principal (by default the whole subject, `CN=drishti`); grant it the ACLs below.
- **SCRAM:** users are created on the brokers (`kafka-configs.sh --alter --add-config 'SCRAM-SHA-512=[password=…]' --entity-type users
  --entity-name drishti`). `SASL_PLAINTEXT` leaves the messages readable: only on networks you trust completely.
- **OAUTHBEARER:** Drishti fetches and renews the token. A private-CA token endpoint must be trusted by the JVM. The connector adds its
  endpoint to the JVM property `org.apache.kafka.sasl.oauthbearer.allowed.urls` (set a longer list yourself when several connectors use
  different providers).
- **Kerberos:** the keytab readable by the server's user only; the host in `bootstrap-servers` must be the one in the broker's service
  principal and resolve both ways; `sasl.kerberos.use-ticket-cache: true` uses a `kinit` ticket you must renew (use a keytab in
  production); clock skew over 5 minutes fails.
- **The Confluent flavour** presets `security.protocol=SASL_SSL`, `sasl.mechanism=PLAIN`, `client.dns.lookup=use_all_dns_ips`,
  `request.timeout.ms=30000`, `session.timeout.ms=45000`, `reconnect.backoff.max.ms=10000`; any of them is overridden by setting it.
  With `security.protocol: SSL` the PLAIN mechanism is not used and no `api-key` is needed.

```yaml
# Confluent Cloud: public certificates, so no tls.*
settings:
  flavour: confluent
  bootstrap-servers: pkc-xxxxx.us-east-1.aws.confluent.cloud:9092
  api-key: ${CONFLUENT_API_KEY}
  api-secret: ${CONFLUENT_API_SECRET}
  topics: risk.envelopes
  schema-registry.url: https://psrc-xxxxx.us-east-1.aws.confluent.cloud
  schema-registry.basic-auth: ${CONFLUENT_SR_KEY}:${CONFLUENT_SR_SECRET}   # the registry's own key, not the Kafka one
```

### Schema Registry and the Confluent wire format

Confluent's serializers write **a zero byte, a 4-byte schema id, then the payload**. With `schema-registry.url` (or `value-format:
confluent`) the connector recognises the format by its first byte and turns the message back into JSON text: **JSON Schema** has the
5 bytes stripped; **Avro** is decoded with the writer schema fetched by id (cached for good) and written as JSON (unions as their
value, enums as their symbol, `bytes`/`fixed` as base64, logical types as their underlying value); **Protobuf** is not supported and is
skipped. Values not in the wire format are read as plain JSON, so mixed topics work. Without a registry only JSON Schema payloads can be
stripped.

**A message that cannot be read is skipped, never treated as a delete**: an unknown id, refused registry credentials, a damaged payload
or Protobuf is logged (the first ten, then every thousandth) and the entity keeps its previous state. The registry is called with
`schema-registry.basic-auth` or `schema-registry.bearer-token`, and `schema-registry.tls.*` for a private CA or mutual TLS. Avro schema
references are not followed; the connector never registers schemas.

### Verifying a setup

From the server's machine, check the broker certificate and your client certificate first, then Kafka's own tools with the same files,
then Drishti:

```
openssl s_client -connect kafka1.bank.example:9093 -servername kafka1.bank.example -CAfile ca.pem \
    -cert client.pem -key client-pkcs8.key -verify_hostname kafka1.bank.example -verify_return_error </dev/null   # Verification: OK
kafka-topics.sh --bootstrap-server kafka1.bank.example:9094 --command-config client.properties --list
kafka-console-consumer.sh --bootstrap-server kafka1.bank.example:9094 --consumer.config client.properties \
    --topic risk.envelopes --from-beginning --max-messages 1
```

`s_client` failures: `62 (hostname mismatch)`, `19 (self-signed certificate in certificate chain)` (give `-CAfile`), `alert
certificate required` (an mTLS listener, no client certificate sent). Then Admin → Health shows `UP (catching up)` and `UP`; **Test
connection** in Admin → Connectors reports a start-up error with the file and reason
([TLS.md, start-up checks](../../docs/connectors/TLS.md#8-start-up-checks-and-their-messages)). Within 30 days of a certificate's
expiry health says `UP (TLS certificate CN=drishti (tls.cert-file) expires in 12 days (2026-10-22))`.

### ACLs

`Describe` and `Read` on the topics; no group ACL (no consumer group, no commits):
`kafka-acls.sh --bootstrap-server … --command-config admin.properties --add --allow-principal User:drishti --operation Read
--operation Describe --topic risk.envelopes`. Give the connector its own read-only principal; prefer SCRAM-SHA-512 or OAUTHBEARER over
PLAIN, and PLAIN only over TLS.

### Common errors and fixes

| What you see | Cause | Fix |
|---|---|---|
| `TimeoutException: Timed out waiting for a node assignment` / `Bootstrap broker … disconnected` | protocol does not match the listener, wrong port, firewall | `security.protocol` matching the listener; its own port |
| `PKIX path building failed … unable to find valid certification path` | the brokers' certificate is not trusted | `tls.ca-file` / `tls.truststore` (+ `tls.trust-jvm-default: true` for public CAs too) |
| `No subject alternative DNS name matching … found` | the certificate lacks the host in `bootstrap-servers` or `advertised.listeners` | re-issue with the names as SANs; `tls.verify-hostname: false` as a last resort |
| `bad_certificate` / `certificate_unknown` | mTLS: the broker does not trust your client certificate | its CA in the broker's truststore |
| `Authentication failed … invalid credentials with SASL mechanism SCRAM-SHA-512` | wrong user/password, or no credential for that mechanism | check `sasl.username`, `sasl.password` and the user's SCRAM credential |
| `Unsupported SASL mechanism` | the listener does not enable it | one of the broker's `sasl.enabled.mechanisms` |
| `TopicAuthorizationException: Not authorized to access topics` | no ACL | [ACLs](#acls); with mTLS the principal is the certificate subject |
| `tls.key-file … does not match the certificate in tls.cert-file` and other start-up messages | a file or password | fix the named setting |
| `sasl.mechanism OAUTHBEARER needs sasl.oauth.token-endpoint, …` / `The URL … is not allowed` | a missing OAuth setting / the token-endpoint allow-list overwritten | endpoint, id and secret; check `org.apache.kafka.sasl.oauthbearer.allowed.urls` |
| `Cannot locate default realm` / `Server not found in Kerberos database` / `Clock skew too great` | no `krb5.conf`, wrong broker host name, clocks apart | `sasl.kerberos.krb5-conf`; exact broker host; NTP |
| `a message in the Confluent wire format could not be read (…)`, `the schema registry refused the credentials (HTTP 401)` | Protobuf, an unknown id, wrong registry credentials | the registry's own key in `schema-registry.basic-auth` |
| `schema id N needs schema-registry.url` | an Avro message and no registry | set `schema-registry.url` |

## Outbound proxy

The shared rules are in [TLS.md, proxy](../../docs/connectors/TLS.md#outbound-http-proxy-for-connectors-ai-providers-and-mcp-servers)
(keys in [CONFIGURATION.md](../../docs/admin/CONFIGURATION.md#shared-proxy)). Kafka's brokers speak the Kafka protocol, not HTTP, and are
**not** proxied. The one HTTP part, the **Schema Registry**, goes through the proxy when one applies (plain forwarding, or `CONNECT` for
an `https://` registry with `schema-registry.tls.*` kept); a refused proxy login surfaces as a message that names the proxy.

## The VaR PV topic (var-pv-stream)

The market-risk pack's connector `pv-updates` (plugin `var-pv-stream`, off by default: `DRISHTI_VAR_PV_STREAM=true`) reads a pricing
system's **PV topic** the same way (every partition from the beginning, no consumer group, the same `bootstrap-servers`, security and
`client.*` settings) and turns it into **intraday batches of the risk lake**
([VAR_GUIDE.md, What-if pricing and intraday recomputes](../../docs/guides/VAR_GUIDE.md#84-what-if-pricing-and-intraday-recomputes)).

- **The topic:** a message's value is JSONL text, one row a line of the `scenario-pv` or `pnl-vector` contract with `COMPUTED_AT`
  ([market-risk contracts](../../docs/contracts/packs/market-risk.md)); the key is not read. A cancel is a line with `TRADE_STATUS:
  CANCELLED` and a null vector; a tombstone is ignored; a malformed line is rejected (`DRS-5201`, with partition, offset and line) and
  the message's other lines applied.
- **The window:** records collect for `drishti.var.intraday.kafka.window` (`5m`), the latest per trade (by `COMPUTED_AT`, then
  partition, offset, line), flushed early at `drishti.var.intraday.kafka.max-trades` (`500`) or on **Apply now**
  (`POST /api/v1/var/intraday/streams/pv-updates/apply`). A window is one intraday batch (`RUN_KIND: INTRADAY`,
  `BATCH_ID: K-<date>-<time>-<n>`) written by Drishti's lake writer and announced as a data load.
- **The guards:** another business date, a record computed before the morning run or not later than what the day holds (a redelivery,
  a replay) is ignored; another scenario set (`DRS-5203`), a failed scenario (`DRS-5206`) or a non-contract object (`DRS-5201`) is
  rejected, never filled with zero, and listed in `GET /api/v1/var/intraday/streams` and the VaR page's provenance.
- **Sizing:** a record is 4–8 KB; 10,000 updates a day are 40–80 MB on the topic.
- **Settings:** `bootstrap-servers` (`${DRISHTI_KAFKA_BOOTSTRAP:localhost:9092}`), `topic` (`${DRISHTI_VAR_PV_TOPIC:drishti.risk.pv-updates}`),
  `lake` (`${DRISHTI_DELTA_ROOT:./data/delta/demo}`, tables `<lake>/risk/scenario-pv` and `<lake>/risk/pnl-vector`), `poll-ms` (`200`).
- **The publisher:** `tools/pvgen/publish-kafka.sh` (`tools\windows\pvgen-publish-kafka.ps1`) sends amended, new and cancelled trades of
  the demo's date (`--per-message`, `--rate`, `--seconds`, `--seed`, `--pv-systems`, `--pnl-systems`, `--all-vectors`, `--dry-run`);
  `--save` writes the rows for `tools/pvgen/check.sh`, which recomputes the answer and compares it with the server's
  ([VAR_GUIDE.md, Producing P&L vectors](../../docs/guides/VAR_GUIDE.md#1512-producing-pl-vectors-and-scenario-pvs)).

```bash
DRISHTI_VAR_PV_STREAM=true DRISHTI_KAFKA_BOOTSTRAP=localhost:9092 ./drishti.sh start
tools/pvgen/publish-kafka.sh --bootstrap localhost:9092 --rate 20 --seconds 60 --save pv-sent.jsonl
```

## Troubleshooting

`GET /api/v1/admin/health`, the connector's row:
`{"name": "trading-stream", "health": "UP", "live": true, "dated": false, "search": true, "cache": {"indexedEntities": 750,
"memoryEntries": 12, "memoryMb": 0.1, "diskMb": 3.4, "diskHits": 9, "diskMisses": 0, "diskClears": 0}}`.

| Symptom | Likely cause | What to do |
|---|---|---|
| log: `source plugin kafka is installed but not configured (kafka needs settings.topics); it stays idle` | the plugin runs as itself with no `topics` | harmless; or configure it |
| `failedToStart` names the connector with a client message | unresolvable `bootstrap-servers`, a bad `client.*` property | fix the setting |
| `DOWN: not started` for a while | no broker answered yet (the first metadata request waits up to 30 s) | check `bootstrap-servers` and the network |
| `DOWN: IllegalStateException: topic … has no partitions yet (reconnecting)` | the topic does not exist | create it (compacted, keyed) |
| `DOWN: TimeoutException: … (reconnecting)` behind TLS or SASL | missing security settings, or a wrong advertised listener | [Security](#security-tls-sasl-and-confluent); check the broker's advertised listeners |
| `DOWN: no connection to the broker (reconnecting)` | unreachable for 10 s | bring it back; it resumes where it stopped |
| `UP (catching up)` for long | a large or uncompacted topic | watch `indexedEntities`; compact it or use `mode: ticks` |
| Health shows the connector stale | nothing new for `stale-after` | check the producer; `lastUpdate` is the newest message's time |
| a view does not tick | another live source answered (the demo samples), or the entity is not in the topic | `DRISHTI_DEMO_ENABLED=false`; check `provenance.source` |
| a delete does not delete | an envelope tombstone keyed by a bare id, or an empty value instead of null | key envelopes `<kind>/<id>`; send a real null |
| an entity disappears after a bad message | a mapped value that is not JSON became its latest | fix the producer; publish a good message for the key |
| cold reads slow under load | they queue behind the one reader | `disk-cache.enabled: true`; raise `cache-mb` |
| `DRS-1004` while the broker is down | a cold read waited for Kafka and the reader lock past `fetch-timeout` | keep `fetch-timeout` at 10 s so reads fall through; enable the disk cache |
| messages skipped after a long outage | resume offset removed by retention, `auto.offset.reset` `latest` | `client.auto.offset.reset: earliest`, or restart to replay |

Security errors are in [Common errors and fixes](#common-errors-and-fixes).

## Checklist for production

1. A **compacted topic keyed by entity id** (`<kind>/<id>` for envelopes); each entity under one key.
2. Prefer **mapped** topics (a kind per topic) for volume: keyed mapped messages are indexed without parsing.
3. Give the connector **`kinds:`** and **`stale-after`**.
4. **State or ticks:** state when the topic is the store for Live, ticks when a dated store already holds the data or the topic is too
   large to index.
5. Heap about 0.5 GB per million entities plus `cache-mb` several times over; the **disk cache** on local disk with
   `disk-cache.max-gb` above a day's distinct documents.
6. First-class security settings, secrets in the environment, `Describe` and `Read` on the topics.
7. Consider `client.auto.offset.reset: earliest` so a long outage never skips messages.
8. A **dated store** for the same kinds; check the order in [With a lake for history](#with-a-lake-for-history).
9. After start: `UP (catching up)` then `UP`, `indexedEntities` near the number of entities, and a view's `provenance.source`.

## See also

- [Connector files](../../docs/connectors/CONNECTOR_FILES.md) and [TLS](../../docs/connectors/TLS.md).
- [The connector developer's guide](../../docs/connectors/CONNECTOR_DEVELOPER_GUIDE.md) (combining a stream with a lake; the README standard).
