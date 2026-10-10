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
# The Kafka connector: live entities from a topic

The `kafka` connector serves entities from Kafka topics and makes views tick as messages arrive. The topic is the
state: the latest message per entity is the entity, as on a compacted topic. This document explains how the connector
reads a topic, the two message shapes, the two modes (`state` and `ticks`), its memory and disk caches, each read path
and what it costs, what happens at start, on an outage and on a restart, how it scales, how to secure it, and every
setting.

For a first setup see [the walk-through at the end of this document](#walk-through-step-by-step); the plugin's
settings in brief are in [the configuration examples at the end of this document](#configuration-by-example) and
[CONFIGURATION.md](../admin/CONFIGURATION.md#kafka--a-live-stream); how a tick reaches the browser is in
[LIVE.md](../architecture/LIVE.md#streaming-sources). History for the same kinds comes from a dated store such as
[DELTA_CONNECTOR.md](DELTA_CONNECTOR.md) or [POSTGRES_CONNECTOR.md](POSTGRES_CONNECTOR.md). The message-queue
connectors, which keep their own copy because a queue cannot be replayed, are in
[ACTIVEMQ_CONNECTOR.md](ACTIVEMQ_CONNECTOR.md) and [RABBITMQ_CONNECTOR.md](RABBITMQ_CONNECTOR.md).

## Contents

1. [When to use it](#1-when-to-use-it)
2. [How the connector reads a topic](#2-how-the-connector-reads-a-topic)
3. [The messages](#3-the-messages)
4. [Two modes: state and ticks](#4-two-modes-state-and-ticks)
5. [Configuration](#5-configuration)
6. [Read paths and what each costs](#6-read-paths-and-what-each-costs)
7. [The memory cache and the disk cache](#7-the-memory-cache-and-the-disk-cache)
8. [Start, catching up, and restarts](#8-start-catching-up-and-restarts)
9. [Outages and reconnection](#9-outages-and-reconnection)
10. [Scale](#10-scale)
11. [Security: TLS, SASL and Confluent](#11-security-tls-sasl-and-confluent)
12. [Limits and trade-offs](#12-limits-and-trade-offs)
13. [Diagnosing](#13-diagnosing)
14. [Settings](#14-settings)
15. [Trying it: `stream.py`](#15-trying-it-streampy)
16. [Checklist for production](#16-checklist-for-production)

---

## 1. When to use it

| Use it for | Prefer another store for |
|---|---|
| entities whose latest state is published to a topic whenever it changes (trades, positions, quotes, limits) | history: the connector is undated; pair it with Delta Lake, PostgreSQL, Aerospike or files ([section 10.4](#104-with-a-lake-for-history)) |
| views that must tick within a frame of a change | reverse lookups (*Linked entities*, impact with F8): it declares none |
| a change feed over data a store already serves (`ticks` mode) | searches over fields of the documents: it offers type-ahead by id only |
| a topic other systems already consume, read without a consumer group | a queue that delivers each message once (ActiveMQ, RabbitMQ: those connectors keep their own state store) |

The connector fits a **compacted topic keyed by entity**: Kafka keeps the latest message per key, so the topic's
size follows the number of entities, not the number of changes, and a restart replays one message per entity.

## 2. How the connector reads a topic

- **Every partition, from the beginning.** At start the connector asks the broker for each topic's partitions,
  assigns all of them to itself (`assign`, not `subscribe`) and seeks each to its beginning. It then reads for as long
  as it runs. The latest message per entity wins.
- **No consumer group, no commits.** `enable.auto.commit` is always `false` and no `group.id` is set; nothing is
  committed, so no offsets are stored in Kafka for Drishti. Every Drishti server reads the whole topic on its own and
  builds the same state. Two servers do not share partitions, and adding a server does not rebalance anyone.
- **One consumer thread per connector** (a virtual thread named `drishti-kafka-<source-name>`) polls every
  `poll-ms` (200) and applies records in the order the poll returns them.
- **A second consumer, the reader** (state mode only, client id `drishti-<source-name>-reader`,
  `max.poll.records` 1), reads a single record back by its offset when a document is not cached
  ([section 6.1](#61-one-entity)).
- **Ordering.** Kafka orders messages within a partition. The connector applies each partition's messages in
  offset order, so the latest message for a key is the one it keeps, provided every message of an entity goes to the
  same partition, which the producer's default partitioner does for a fixed key. Across partitions there is no order:
  if one entity's messages are written under different keys (an envelope keyed sometimes `NS-1`, sometimes
  `netting-set/NS-1`) and land in different partitions, whichever the consumer applies last wins.
- **Generation and time.** A document's generation is its message offset (monotonic within a partition, which is
  where an entity's messages live); its `fetchedAt` is the message timestamp. Documents are live (`live: true`) and
  undated (`businessDate: null`).
- **When it last received data.** The connector reports the newest message timestamp it has applied
  (`lastUpdate` in Admin → Health), which the engine compares with `stale-after`
  ([CONFIGURATION.md](../admin/CONFIGURATION.md#connector-settings-plugin-by-plugin)). The trading pack sets
  `stale-after: ${DRISHTI_STREAM_STALE_AFTER:15m}` on `trading-stream`.

## 3. The messages

Keys are strings and values are JSON text (both read with Kafka's `StringDeserializer`).

### 3.1 Mapped: the value is the document

A topic with a kind (`kind.<topic>`, or `kind` for every topic) carries whole documents of that kind. The id is the
message key; for a message without a key it is the field `id-field.<topic>` (else `id-field`, default `id`) of the
document. This is how `tools/samplegen/stream.py` publishes the trading samples:

```text
topic: drishti.trading.trades
key:   MX-20000001
value: {"tradeId": "MX-20000001", "productType": "IRS_FIXFLOAT", "currency": "AUD", "notional": 242000000.0,
        "mtm": 1900000, "pnl1d": 24137, "book": "BOOK-RATES-3", "counterparty": {"id": "CP-MERIDIAN-RE", …}, …}
```

**Keep the key equal to the id.** A keyed message on a mapped topic is indexed under its key *without parsing the
value*; the value is parsed only when someone is viewing the entity or it is in the memory cache. That is what makes
mapped topics cheap at volume ([section 10.2](#102-throughput-and-catching-up)). If the document's `id-field` disagrees
with the key, a parsed message is tracked under both, which is never what you want.

A compacted topic refuses a message without a key (`InvalidRecordException: Compacted topic cannot accept message
without key`), so on compacted topics every mapped message has its key.

### 3.2 Envelope: kind, id and document in the value

A topic without a kind carries envelopes, so one topic can carry many kinds:

```text
topic: risk.envelopes
key:   netting-set/NS-ALDERSHOT-FRA
value: {"kind": "netting-set", "id": "NS-ALDERSHOT-FRA",
        "doc": {"nettingSetId": "NS-ALDERSHOT-FRA", "tradeCount": 3, "netMtm": -339550, "collateral": -229552, "pfePeak": 347288}}
```

Every envelope is parsed (the kind and id are inside the value). **Key envelopes `<kind>/<id>`**: compaction keeps the
latest message per key, so the key must be unique across kinds, and a tombstone has no value to read the kind from,
so its key must carry it. An envelope keyed by the bare id is still read (the test suite sends one), but it cannot be
deleted by a tombstone.

### 3.3 Deletes, and what is skipped

| Message | Effect |
|---|---|
| mapped topic, key `MX-20000001`, value `null` (a tombstone) | `trade/MX-20000001` deleted: its index entry, memory-cache entry, disk-cache entry and type-ahead entry go, and open views are told |
| envelope topic, key `netting-set/NS-1`, value `null` | `netting-set/NS-1` deleted, as above |
| envelope with `"doc": null` | deleted, as above |
| envelope whose value is not JSON, or has no `kind` or `id` | skipped; the stream goes on |
| envelope tombstone keyed by a bare id (no `/`) | ignored: the kind is unknown |
| mapped message with a key whose value is not JSON | indexed by its key without parsing (see above); a later read of that entity fails to parse and answers "not held" |

Notes, from the code:

- A delete is **pushed to open views**: the view keeps its last document, greyed, under a banner saying when the
  entity was deleted (the tombstone's timestamp), and stops updating. If a later message brings the entity back, the
  view repaints. Under the hood the connector hands its subscribers a deletion
  (`EntityDocument.deleted(ref, provenance)`); the server sends it on the view's live stream as a `deleted` patch
  ([LIVE.md](../architecture/LIVE.md#deleted-entities)). This works in `mode: ticks` too.
- A deleted id **leaves type-ahead at once** (a cheap removal from the index, not a rebuild), and leaves the
  "recent" list of the command line when a view of it was open. Before the connector has caught up, the index is
  built from what is left at the end, so deletes during the replay are reflected too.
- Opening a deleted entity answers "not held", so the next connector is asked.
- An empty value is not a tombstone. With `kafka-console-producer.sh`, send a real null with
  `--property null.marker=NULL` and the line `MX-20000001|NULL`.

## 4. Two modes: state and ticks

| | `state` (default) | `ticks` |
|---|---|---|
| reads (`fetch`) | answered from the topic | never: always "not held" |
| index of where each entity's latest message is | yes | no |
| memory cache, disk cache, reader consumer | yes (disk cache when enabled) | no |
| type-ahead | yes (`search: true`) | no |
| pushes new messages to open views | views it answers | views of its kinds, whichever store answered (`pushes(ref)` is true for the connector's kinds) |
| reads the topic from the beginning at start | yes | yes (to reach the end; mapped keyed messages are not parsed unless watched) |
| use it when | the topic is the store for Live | a lake or database already holds the day's data, and the topic is only the change feed, or too large to index |

In `ticks` mode a view of `TRD MX-20000001` reads from `trading-store` (its provenance names the lake), shows the live
dot, and each message for MX-20000001 repaints it within a frame. This is described with the routing in
[CONNECTOR_DEVELOPER_GUIDE.md, Combining connectors](CONNECTOR_DEVELOPER_GUIDE.md#combining-connectors).

## 5. Configuration

### 5.1 Pack form: the trading pack's `trading-stream`

The pack's suggested template, as shipped in `config/packs/trading/pack.yaml`, off until `DRISHTI_STREAM_TRADING=true`. At the first start the server writes it to
`config/connectors/trading-stream.yaml` (same settings, the file form in [As a connector file](#as-a-connector-file)), which is then the site's:

```yaml
connectors:
  trading-stream:
    plugin: kafka
    enabled: ${DRISHTI_STREAM_TRADING:false}
    kinds:
    - trade
    settings:
      bootstrap-servers: ${DRISHTI_KAFKA_BOOTSTRAP:localhost:9092}
      topics: ${DRISHTI_TRADING_TOPIC:drishti.trading.trades}
      kind: trade                                            # mapped: every topic carries trade documents
      id-field: tradeId                                      # the id of a message without a key
      stale-after: ${DRISHTI_STREAM_STALE_AFTER:15m}         # engine setting: amber in Health after 15 quiet minutes
      disk-cache.enabled: ${DRISHTI_STREAM_DISK_CACHE:true}
      disk-cache.root: ${DRISHTI_CACHE_ROOT:${drishti.data.dir:./data}/cache}    # → ./data/cache/trading-stream
      disk-cache.max-gb: ${DRISHTI_STREAM_CACHE_GB:10}
      disk-cache.reset-at: ${DRISHTI_CACHE_RESET_AT:02:00}
      disk-cache.zone: America/New_York
```

The pack routes `trade` to `trading-store` (Delta Lake); the stream is found as "every other connector serving
`trade`", and is tried first on Live because it is live ([section 10.4](#104-with-a-lake-for-history)). The
environment variables are listed in
[CONFIGURATION.md](../admin/CONFIGURATION.md#environment-variables-used-by-the-packs-and-profiles).

### 5.2 Site form: envelopes and a mapped topic on a secured cluster

The connector file `config/connectors/risk-stream.yaml`. The Kafka client's own properties are `client.<property>`; the JAAS line is
a credential, so it is an environment reference:

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

The `client.<property>` form passes Kafka's own properties through and always works. The connector also has first-class
security settings (`security.protocol`, `tls.*`, `sasl.*`, PEM and PKCS12/JKS files, Confluent Cloud, Schema Registry)
that check the files at start and say what is wrong; the same cluster there is
`security.protocol: SASL_SSL`, `sasl.mechanism: SCRAM-SHA-512`, `sasl.username`, `sasl.password: "${KAFKA_PASSWORD}"` and
`tls.ca-file` ([section 11](#11-security-tls-sasl-and-confluent)).

Give a Kafka connector `kinds:`. Its manifest lists `kind`, every `kind.<topic>` and every kind it has seen in a
message; an envelope connector with no `kinds` serves *every* kind until its first message, so it is asked (and
answers "not held") for every read.

Setting keys with dots are literal keys. In a connector file they are written as they are (or nested, which is flattened to the same dotted key); no bracketing is needed, a topic name
such as `desk_orders` is simply `kind.desk_orders: order`.

### 5.3 Site form: ticks over the lake

The connector file `config/connectors/trading-stream.yaml` with the stream switched to `ticks`. The file replaces the pack's template wholesale, so it
repeats the topic, kind and id field:

```yaml
plugin: kafka
enabled: ${DRISHTI_STREAM_TRADING:false}
kinds: [trade]
settings:
  bootstrap-servers: ${DRISHTI_KAFKA_BOOTSTRAP:localhost:9092}
  topics: ${DRISHTI_TRADING_TOPIC:drishti.trading.trades}
  kind: trade
  id-field: tradeId
  mode: ticks                                         # keep nothing; push to views the lake answers
```

## 6. Read paths and what each costs

| Path | State mode | Ticks mode |
|---|---|---|
| one entity, cached in memory | a map lookup, no I/O | not held |
| one entity, on the disk cache | one RocksDB get and a JSON parse | not held |
| one entity, neither | one record read back from Kafka by offset, serialised through one reader | not held |
| type-ahead | in memory (`HitIndex`) | none |
| a picked business date | not dated: asked after the dated stores; answers its current document | not held |
| reverse lookups | none (capability not declared) | none |
| searches over fields, pick lists, derived kinds, impact | none from this connector; the dated stores serve them | none |
| live push | every message for a watched entity | every message for a watched entity of its kinds |

### 6.1 One entity

`TRD MX-20000001` on Live, in state mode:

1. **Memory cache.** A document read before, or for an entity someone is watching, is answered at once. A new
   message for a cached or watched entity replaces the cached document as it arrives, so the cache is never behind
   the stream.
2. **Index.** The connector looks up where the entity's latest message is (topic, partition, offset). No entry: "not
   held", and the next connector is asked.
3. **Disk cache**, if enabled: the stored value, parsed, then kept in the memory cache.
4. **Kafka.** Otherwise the reader is assigned the partition, seeks to the offset and polls (200 ms at a time,
   5 s at most) until it gets that record. The reader is one consumer guarded by one lock, so cold reads run one at a
   time; a read waits at most 5 s for the lock. A record that cannot be read (the broker is away, the offset was
   removed by retention, the value is not JSON) is a miss, not an error: the read answers "not held".

A document read this way is cached only if the index still points at the same offset when it is stored; a newer
message or a tombstone applied meanwhile drops it, so a stale or deleted document never sticks in the cache.

The engine's whole read is bounded by `drishti.sources.fetch-timeout` (2 s), shorter than the reader's 5 s, so a cold
read while the broker is away ends with `DRS-1004` rather than falling through to the next store. Raising
`fetch-timeout` above 5 s turns such a read into "not held", and the next store (the lake) answers.

### 6.2 Type-ahead

Every entity in the index is in Drishti's in-memory type-ahead index, with the subtitle `<kind> · <source-name>`
(`trade · trading-stream`). Ids are matched by prefix (a binary search), then by substring. The index is built in one
go when the connector first catches up; after that each new entity is added as it arrives. During the first
catch-up type-ahead from this connector is empty. `search: false` turns it off (and it is always off in ticks mode).

### 6.3 Dated reads

The connector is undated. For a picked business date the router asks the dated connectors first (the lake), and asks
Kafka only when none holds the entity on that date; Kafka then answers its current document, and the console says
*No data held for 2026-08-01: the current data of trading-stream, a source that keeps no dates*. A picked date is always a static
snapshot: no ticks.

### 6.4 Reverse lookups

None: the connector declares `reverseLookup: false`. *Linked entities* and impact come from the dated stores that
serve the same kinds.

### 6.5 Live push

A view subscribes to the entity on the first live connector that holds it (a real stream before the demo samples).
Each message for that entity is parsed once on the consumer thread and handed to every listener of the entity; the
engine merges ticks within a frame (`drishti.live.frame`, 50 ms), keeps one source subscription per entity however
many people watch it, and closes it when the last viewer leaves ([LIVE.md](../architecture/LIVE.md#guarantees-and-limits)).
An entity nobody watches and nobody has read is not parsed at all on a mapped topic.

## 7. The memory cache and the disk cache

### 7.1 Memory

| Held | Bounded by |
|---|---|
| the index: kind, id, topic, partition and offset of each entity's latest message | the number of entities in the topic |
| the type-ahead index: each id, lowercased, and its label | the number of entities |
| recently read and watched documents (parsed) | `cache-mb` (256), weighed by message length |

`cache-mb` counts each document as the length of its message text (4,096 for an entry whose length is not known).
The parsed document takes more heap than its text, so the cache's real heap use is a multiple of `cache-mb`; budget
the heap accordingly. `cache-mb: 0` keeps nothing: every read goes to the disk cache or Kafka.

### 7.2 Disk

With `disk-cache.enabled: true` (state mode only) every applied message is also written to the connector's own
RocksDB store, so a document not in memory is read from local disk instead of from Kafka:

- **Where:** `disk-cache.dir`, default `<disk-cache.root>/<source-name>` (`./data/cache/trading-stream`), one
  sub-folder `gen-<millis>-<n>` per generation.
- **What:** mapped messages as their raw value, envelopes as their `doc`, keyed `<kind>/<id>`; LZ4-compressed; no
  write-ahead log (it is a cache: after a crash it is refilled).
- **Bounded:** FIFO compaction drops the oldest table files once `disk-cache.max-gb` (10) is reached. An entity whose
  entry was dropped is read from Kafka again.
- **Cleared nightly** at `disk-cache.reset-at` (`02:00`) in `disk-cache.zone` (`America/New_York`); `never` or blank
  never clears. Clearing swaps in an empty store at once (readers never wait) and the old one is deleted when its last
  reader leaves. After the clearing, reads of entities that have not changed since go to Kafka until they change.
- **Not kept across restarts:** the store is deleted when the connector starts, because the topic is replayed anyway
  (contrast the message-queue state stores, which are the only copy).

The disk cache's figures in Health are `diskMb` (RocksDB table files; the 32 MB write buffer is not counted),
`diskHits`, `diskMisses` and `diskClears`.

**Purge** (Admin → Caches) drops the memory cache and clears the disk cache. The index stays, so reads come back from
Kafka by offset.

## 8. Start, catching up, and restarts

| Moment | What happens | Health |
|---|---|---|
| start | settings read; the reader consumer created (state mode); the consumer thread started | `DOWN: not started` |
| first connection | partitions listed (30 s at most per topic), assigned, each seeked to its beginning; the end offsets noted | `UP (catching up)` |
| catch-up | every message applied; reads answer from what has been applied so far | `UP (catching up)` |
| every partition reached its end offset noted at start | the type-ahead index built | `UP` |
| afterwards | each message applied as it arrives and pushed to watchers | `UP` |

During catch-up a read may return an **older** version of an entity (its latest message not yet applied), and a view
open during catch-up receives the replayed messages in order, ending at the latest.

**A restart of Drishti** starts from nothing: the index, the memory cache, the offsets reached and the disk cache are
gone, and the topic is read again from the beginning. On a compacted topic this is one message per entity (plus what
compaction has not yet cleaned). Nothing is lost while Drishti is down: the messages are in Kafka.

**A reconnect inside a running server** (see [section 9](#9-outages-and-reconnection)) is different: the new consumer
resumes at the next offset after the last message applied, per partition, and does not replay.

## 9. Outages and reconnection

Two layers keep the connector going without a restart of Drishti:

1. **The Kafka client** rides out a broker outage inside `poll()`: it keeps reconnecting by itself and carries on
   where it was. The connector watches the client's `connection-count` metric: with no broker connection for
   10 s it sets `DOWN: no connection to the broker (reconnecting)`; when a connection is back, health returns to `UP`
   (or `UP (catching up)`). Blips shorter than 10 s do not show.
2. **A supervisor** recreates the consumer when the client gives up (the broker was down at start, the topic does
   not exist, a fatal error). It waits 1 s, doubling to 30 s between attempts (back to 1 s when the last consumer had
   run for more than a minute), closes the old consumer (2 s at most), creates a new one and resumes at the last
   applied offset.

Every health text, exactly as the code sets it:

| Health | When |
|---|---|
| `DOWN: not started` | before the first connection |
| `UP (catching up)` | connected; replaying the topic to the end offsets seen at start |
| `UP` | caught up |
| `DOWN: no connection to the broker (reconnecting)` | no broker connection for 10 s while running |
| `DOWN: <SimpleName>: <message> (reconnecting)` | the consumer failed; for example `DOWN: IllegalStateException: topic drishti.trading.trades has no partitions yet (reconnecting)` when the topic does not exist, or the client's `TimeoutException` when no broker answered the metadata request within 30 s |
| `DOWN: <message> (retrying)` | the supervisor could not create a consumer (a client configuration error) |

In state mode the reader consumer is created in `start` with the same properties, so a configuration error the client
rejects at construction (an unresolvable `bootstrap-servers`, a malformed `client.*` property) stops the connector
from starting: it is listed under `failedToStart` with the client's message, and `(retrying)` is seen only in ticks
mode. A broker that is merely down does not stop the start.

While the broker is away: documents in the memory or disk cache still answer; other reads go to Kafka and miss after
the 5 s reader wait (`DRS-1004` within the default 2 s `fetch-timeout`); open views stop ticking and resume with the
next message after the broker returns.

**Offsets out of range after an outage.** If retention removed messages while the connector was disconnected, the
offset it resumes at may no longer exist. The consumer then applies its `auto.offset.reset` policy, which is Kafka's
default `latest` unless you set `client.auto.offset.reset: earliest`; with `latest` the messages between are skipped
until the next restart. (Derived from the client's documented behaviour; the connector does not set the property.)

## 10. Scale

### 10.1 Memory per entity

Estimated from the data structures (not measured): each entity costs an index entry (its id string, an `EntityRef`,
a position record and a hash-map node, about 150–200 bytes for an 11-character id) and a type-ahead entry (the id
lowercased and its label text, about 200–250 bytes), so roughly **0.4–0.5 GB of heap per million entities**, plus the
memory cache (`cache-mb`, weighed by message text) and the Kafka client's fetch buffers. The class comment in
`KafkaSourcePlugin` says "tens of bytes per entity" for the index alone; that counts only the position, not the id
and map overhead.

### 10.2 Throughput and catching up

- One consumer thread applies every message of every partition of the connector's topics. On a mapped topic with
  keys it decodes the value as text and updates the index, without parsing JSON, unless the entity is watched or
  cached; with the disk cache on, it also writes the value to RocksDB. Envelopes are parsed one by one.
- Catch-up time is the time to read the topic once: on a compacted topic, roughly one message per entity. No
  measurement is recorded for this connector; watch `indexedEntities` grow in Health to see the rate on your cluster.
- Cold reads (neither cache) go through one reader and run one at a time, each a seek and a fetch from the broker.
  A burst of first opens of different entities queues behind it; the disk cache removes most of them.

### 10.3 A million entities a day

A topic carrying a million trades whose state changes during the day:

- **Compacted topic:** the topic holds about one message per trade; a restart replays about a million messages.
  The index and type-ahead take about 0.4–0.5 GB of heap (estimate above).
- **Disk cache:** with documents of about 7.8 KB of JSON each (the generated trade size quoted in
  [FILE_CONNECTOR.md](FILE_CONNECTOR.md#8-memory-and-size)), one version of every trade is about 7.8 GB before LZ4
  compression; keep `disk-cache.max-gb` above the compressed size of the day's distinct documents, or older entries
  are dropped and read from Kafka again.
- **Not compacted** (retention by time): every change is replayed on restart; keep retention near one business day
  or use compaction.
- **Too large to index**, or already loaded into a store: use `mode: ticks`.

### 10.4 With a lake for history

The connector is undated. Configure the same kind on a dated store as well, and the router does the rest
([CONNECTOR_DEVELOPER_GUIDE.md, Combining connectors](CONNECTOR_DEVELOPER_GUIDE.md#combining-connectors)):

| The user asks for | Order tried | Who answers |
|---|---|---|
| Live | `trading-stream` (live first) → `trading-store` → `file` | Kafka; a trade Kafka does not hold (matured, deleted) falls through to the lake's newest date |
| a picked date | `trading-store` (dated first) → `file` → `trading-stream` | the lake for that date; when the lake does not hold the date, the next store is asked, and Kafka's current document comes last, with the *No data held for <date>* banner |

With `mode: ticks` the lake answers every read and Kafka only ticks the open views. Seven years of history in the lake
is described in [DELTA_CONNECTOR.md, section 8](DELTA_CONNECTOR.md#8-seven-years-of-history).

### 10.5 Several servers

Each server reads every partition in full and keeps its own index, so the broker serves the topic once per server.
They share the client id `drishti-<source-name>` unless you set `client.client.id`; broker quotas keyed by client id
are then shared between them.

## 11. Security: TLS, SASL and Confluent

The connector speaks to Apache Kafka and to Confluent (Cloud and Platform) over every transport Kafka offers: plaintext,
TLS, SASL over plaintext, SASL over TLS. There are two ways to set it up, and they combine:

- **First-class settings** (this section): `security.protocol`, `tls.*` (the [shared TLS module](TLS.md)), `sasl.*`,
  `flavour`, `schema-registry.*`. Drishti checks them at start, reads the certificate files itself (PEM, PKCS12, JKS),
  names the file and the reason when one is wrong, and translates them to Kafka client properties.
- **`client.<property>`**: any Kafka consumer property, passed through as `<property>`. It is applied **last** and
  **wins** over everything the first-class settings produce, so any Kafka property the first-class settings do not cover
  can still be set, and any value they choose can be overridden.

```
connector defaults  <  flavour preset  <  first-class settings (security.protocol, tls.*, sasl.*)  <  client.<property>
```

### 11.1 Which setup is mine?

| Your cluster | Use | Example |
|---|---|---|
| Apache Kafka, TLS, brokers on a private CA | `security.protocol: SSL`, `tls.ca-file` | [A](#a-apache-kafka-ssl-with-a-pem-ca) |
| Apache Kafka, TLS, Java stores (PKCS12/JKS) | `tls.truststore` | [B](#b-apache-kafka-ssl-with-a-pkcs12-or-jks-truststore) |
| Apache Kafka, brokers require a client certificate | `tls.cert-file`/`tls.key-file` or `tls.keystore` | [C](#c-mutual-tls-mtls) |
| Apache Kafka, user name and password over TLS | `SASL_SSL` + `SCRAM-SHA-512` (or `PLAIN`) | [D](#d-sasl_ssl-with-scram) |
| An identity provider issues tokens (Keycloak, Okta, Entra ID) | `SASL_SSL` + `OAUTHBEARER` | [E](#e-oauthbearer-oidc-client-credentials) |
| Kerberos / Active Directory | `SASL_SSL` + `GSSAPI` | [F](#f-kerberos-gssapi) |
| Confluent Cloud | `flavour: confluent` + API key and secret | [G](#g-confluent-cloud-with-api-keys) |
| Confluent Platform on-premises (mTLS, Schema Registry) | `flavour: confluent`, `security.protocol: SSL` | [H](#h-confluent-platform-on-premises-mtls-and-schema-registry) |
| a dev broker, no security | nothing (`PLAINTEXT`) | the defaults |

Kafka listeners differ in what they ask of a client. Use the listener's own port: a `SASL_SSL` listener cannot be read
with `SSL`, and the connector fails with a timeout, not a message, if the protocol is wrong
([11.9](#119-common-errors-and-fixes)). Ask the cluster operator for the **listener's security protocol and its SASL
mechanism**; they are in the broker's `listeners` and `sasl.enabled.mechanisms`.

### 11.2 Settings

| Setting | Default | Meaning |
|---|---|---|
| `flavour` | `apache` | `apache`, or `confluent` for the preset in [11.4](#114-the-confluent-flavour). |
| `security.protocol` | inferred | `PLAINTEXT`, `SSL`, `SASL_PLAINTEXT` or `SASL_SSL`. Absent: `SASL_SSL` if `sasl.mechanism` is set and TLS is wanted (`tls.enabled: true`), `SASL_PLAINTEXT` with a mechanism and no TLS, `SSL` with `tls.enabled`, else `PLAINTEXT`. Always write it down in production. |
| `tls.*` | | The shared settings, [TLS.md](TLS.md#1-the-settings), used when the protocol is `SSL` or `SASL_SSL`. |
| `sasl.mechanism` | none (`PLAIN` with the Confluent flavour) | `PLAIN`, `SCRAM-SHA-256`, `SCRAM-SHA-512`, `OAUTHBEARER` or `GSSAPI`. |
| `sasl.username`, `sasl.password` (`sasl.password-file`) | | PLAIN and SCRAM. Confluent Cloud calls them `api-key` and `api-secret`, accepted as aliases. |
| `sasl.oauth.token-endpoint` | | OAUTHBEARER: the identity provider's token URL. |
| `sasl.oauth.client-id`, `sasl.oauth.client-secret` (`-file`) | | OAUTHBEARER: the client-credentials pair. |
| `sasl.oauth.scope` | none | OAUTHBEARER: the scope to request. |
| `sasl.kerberos.principal` | | GSSAPI: e.g. `drishti@BANK.EXAMPLE`. |
| `sasl.kerberos.keytab` | | GSSAPI: the keytab file. |
| `sasl.kerberos.use-ticket-cache` | `false` | GSSAPI: use the ticket from `kinit` instead of a keytab. |
| `sasl.kerberos.service-name` | `kafka` | GSSAPI: the brokers' service name. |
| `sasl.kerberos.krb5-conf` | the JVM's | GSSAPI: path of `krb5.conf` (sets `java.security.krb5.conf` if the JVM has none yet). |
| `value-format` | `string`; `confluent` when `schema-registry.url` is set | `confluent` also reads the Confluent wire format ([11.5](#115-schema-registry-and-the-confluent-wire-format)). |
| `schema-registry.url` | none | The registry's `https://` (or `http://`) URL. |
| `schema-registry.basic-auth` (`-file`) | none | `${SR_KEY}:${SR_SECRET}`. |
| `schema-registry.bearer-token` (`-file`) | none | A bearer token instead of basic auth. |
| `schema-registry.timeout-ms` | `10000` | Connect and read timeout for a registry call. |
| `schema-registry.tls.*` | the JVM's trust | The same keys as `tls.*`, for the registry. |

Secrets are written as `${ENV}` placeholders or `…-file` paths, never inline
([TLS.md, section 4](TLS.md#4-passwords-never-go-in-a-document)). The first-class settings also check each other and
refuse a contradiction at start: `sasl.mechanism` with `security.protocol: SSL`; `tls.enabled: true` with `PLAINTEXT`; a
SASL protocol without a mechanism; a mechanism without its credentials.

### 11.3 What each setting becomes

This is exactly what the connector hands to the Kafka client (the property names are Kafka's; you can set the same ones by
hand with `client.`).

| You write | Kafka property |
|---|---|
| `security.protocol: X` | `security.protocol=X` |
| `tls.verify-hostname` (default true / `false`) | `ssl.endpoint.identification.algorithm=https` / the empty string |
| `tls.protocols: TLSv1.3,TLSv1.2` | `ssl.enabled.protocols=TLSv1.3,TLSv1.2`, `ssl.protocol=TLSv1.3` (or `TLSv1.2` when 1.3 is not listed) |
| `tls.cipher-suites` | `ssl.cipher.suites` |
| `tls.ca-file` | `ssl.truststore.type=PEM`, `ssl.truststore.certificates=<the certificates>` |
| `tls.truststore` alone | `ssl.truststore.location`, `.password`, `.type` (the store is handed to Kafka as it is) |
| `tls.ca-file` with `tls.truststore`, or with `tls.trust-jvm-default: true` | one merged PEM set: `ssl.truststore.type=PEM`, `ssl.truststore.certificates` (Kafka cannot merge sources itself) |
| only the JVM default trust | no truststore property: Kafka uses the JVM's |
| `tls.cert-file` + `tls.key-file` | `ssl.keystore.type=PEM`, `ssl.keystore.certificate.chain`, `ssl.keystore.key` (re-written as unencrypted PKCS#8 whatever the file's format was, so PKCS#1, SEC1 and encrypted keys all work) |
| `tls.keystore` | `ssl.keystore.location`, `.password`, `.type`, `ssl.key.password` (as it is) |
| `tls.keystore` with `tls.key-alias` | the chosen key and chain as PEM (Kafka's own settings cannot choose an alias) |
| `sasl.mechanism` | `sasl.mechanism` |
| PLAIN | `sasl.jaas.config=…plain.PlainLoginModule required username="…" password="…";` |
| SCRAM | `sasl.jaas.config=…scram.ScramLoginModule required username="…" password="…";` |
| OAUTHBEARER | `sasl.oauthbearer.token.endpoint.url`, `sasl.login.callback.handler.class=org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginCallbackHandler`, `sasl.jaas.config=…OAuthBearerLoginModule required clientId="…" clientSecret="…" scope="…";` |
| GSSAPI | `sasl.kerberos.service.name`, `sasl.jaas.config=com.sun.security.auth.module.Krb5LoginModule required useKeyTab=true storeKey=true keyTab="…" principal="…";` |

`tls.insecure-trust-all` is not available for Kafka (the client builds its own TLS): use `tls.ca-file`, and
`tls.verify-hostname: false` if the names do not match.

### 11.4 The Confluent flavour

`flavour: confluent` is a preset for Confluent Cloud. It sets, before your own settings:

| Property | Value | Why |
|---|---|---|
| `security.protocol` | `SASL_SSL` | Confluent Cloud only listens on it |
| `sasl.mechanism` | `PLAIN` | the API key and secret are the user name and password |
| `client.dns.lookup` | `use_all_dns_ips` | Cloud endpoints resolve to several addresses |
| `request.timeout.ms` | `30000` | Confluent's recommended client value |
| `session.timeout.ms` | `45000` | Confluent's recommended client value |
| `reconnect.backoff.max.ms` | `10000` | keeps reconnects prompt after a blip |

Everything else is as for Apache. Any of them is overridden by setting it: `security.protocol: SSL` for a Confluent
Platform cluster with mutual TLS ([example H](#h-confluent-platform-on-premises-mtls-and-schema-registry)), or
`client.request.timeout.ms: 60000`.

### 11.5 Schema Registry and the Confluent wire format

Confluent's serializers write each message as a **zero byte, a 4-byte schema id, then the payload**. A plain string
deserializer would hand that to the JSON parser and every message would be skipped. With `schema-registry.url` set (or
`value-format: confluent`), the connector recognises the format by its first byte, finds out what the id means, and turns
the message back into JSON text, the same text the rest of the connector reads:

| The registry says the schema is | What the connector does |
|---|---|
| **JSON Schema** | strips the 5 bytes; the rest is the JSON document |
| **Avro** | fetches the writer schema by id from the registry (once; it is cached for good, a schema never changes under its id), decodes the payload with it (Apache Avro), and writes the record as JSON: unions are their value, enums their symbol, arrays and maps as JSON, `bytes` and `fixed` as base64 text; logical types appear as their underlying value (a timestamp-millis is a number, a decimal is its base64 bytes) |
| **Protobuf** | not supported yet: the message is skipped and logged |

Values that are not in the wire format (they do not start with a zero byte) are plain JSON text as before, so a topic that
mixes both is read correctly. Without `schema-registry.url`, only JSON Schema payloads can be stripped (a zero byte, an
id, then `{` or `[`); an Avro message there is skipped and logged with the hint to set the registry.

**A message that cannot be read is skipped, never treated as a delete.** An unknown schema id, a registry that refused the
credentials, a damaged payload or a Protobuf message is logged (the first ten, then every thousandth, with the reason) and
the entity keeps its previous state. Keys are always plain strings.

The registry is called over HTTPS with `schema-registry.basic-auth` (Confluent Cloud: the registry's own key and secret,
which are **not** the Kafka API key) or a `schema-registry.bearer-token`, and `schema-registry.tls.*` for a private CA or
mutual TLS:

```yaml
settings:
  schema-registry.url: https://schema-registry.bank.example:8081
  schema-registry.basic-auth: "${SR_KEY}:${SR_SECRET}"
  schema-registry.tls.ca-file: /etc/drishti/tls/ca.pem
```

Limits: Avro schema *references* (a schema that imports another subject) are not followed; a message written with a
schema that has references is skipped and logged. Subject name strategies do not matter (the id identifies the schema).
The connector reads; it never registers schemas.

### 11.6 Examples

Each example is a complete site entry (`application.local.yaml`; in a pack, the same `settings:` under the connector).
Placeholders such as `${KAFKA_PASSWORD}` are environment variables of the server process.

#### A. Apache Kafka, SSL with a PEM CA

Brokers present certificates issued by your private CA; no client certificate is asked for.

```yaml
drishti:
  sources:
    connectors:
      risk-stream:
        plugin: kafka
        kinds: [netting-set]
        settings:
          bootstrap-servers: kafka1.bank.example:9093,kafka2.bank.example:9093
          topics: risk.envelopes
          security.protocol: SSL
          tls.ca-file: /etc/drishti/tls/ca-bundle.pem        # one or many CA certificates
```

#### B. Apache Kafka, SSL with a PKCS12 or JKS truststore

```yaml
          security.protocol: SSL
          tls.truststore: /etc/drishti/tls/truststore.p12
          tls.truststore-password: "${KAFKA_TRUSTSTORE_PASSWORD}"
          # tls.truststore-type: JKS          # only if the file name does not say; PKCS12 and JKS are detected
```

To trust the private CA **and** the public ones (a cluster behind a public certificate for some brokers and a private one
for others): add `tls.trust-jvm-default: true`.

#### C. Mutual TLS (mTLS)

The brokers require a client certificate (`ssl.client.auth=required`). With **PEM files**:

```yaml
          security.protocol: SSL
          tls.ca-file: /etc/drishti/tls/ca.pem
          tls.cert-file: /etc/drishti/tls/client.pem          # the certificate, then any intermediates
          tls.key-file: /etc/drishti/tls/client.key           # PKCS#8, PKCS#1 RSA, SEC1 EC, or encrypted PKCS#8
          tls.key-password: "${CLIENT_KEY_PASSWORD}"          # only for an encrypted key
```

With **Java stores**:

```yaml
          security.protocol: SSL
          tls.truststore: /etc/drishti/tls/truststore.jks
          tls.truststore-password: "${KAFKA_TRUSTSTORE_PASSWORD}"
          tls.keystore: /etc/drishti/tls/client.p12
          tls.keystore-password: "${KAFKA_KEYSTORE_PASSWORD}"
          tls.key-alias: drishti                              # only if the keystore holds several keys
```

The broker maps the certificate to a principal (`ssl.principal.mapping.rules`, by default the whole subject
`CN=drishti`): grant that principal the ACLs of [11.8](#118-acls).

#### D. SASL_SSL with SCRAM

```yaml
          security.protocol: SASL_SSL
          sasl.mechanism: SCRAM-SHA-512                       # or SCRAM-SHA-256, or PLAIN
          sasl.username: drishti
          sasl.password: "${KAFKA_PASSWORD}"                  # or sasl.password-file: /run/secrets/kafka-password
          tls.ca-file: /etc/drishti/tls/ca.pem
```

SCRAM users are created on the brokers (`kafka-configs.sh --alter --add-config 'SCRAM-SHA-512=[password=…]' --entity-type
users --entity-name drishti`). `SASL_PLAINTEXT` (no TLS) is the same without the `tls.*` keys and is for networks you
trust completely: the password travels as SCRAM proofs, but the messages are readable.

#### E. OAUTHBEARER (OIDC client credentials)

Drishti asks the identity provider for a token with its client id and secret and presents it to Kafka; it renews it before
it expires.

```yaml
          security.protocol: SASL_SSL
          sasl.mechanism: OAUTHBEARER
          sasl.oauth.token-endpoint: https://idp.bank.example/realms/prod/protocol/openid-connect/token
          sasl.oauth.client-id: drishti
          sasl.oauth.client-secret: "${KAFKA_OAUTH_SECRET}"
          sasl.oauth.scope: kafka                             # optional
          tls.ca-file: /etc/drishti/tls/ca.pem
```

The token endpoint is itself HTTPS. If its certificate is from a private CA, the JVM must trust it
(`-Djavax.net.ssl.trustStore=…` in `JAVA_TOOL_OPTIONS`), or pass Kafka's own settings for that call through
`client.<property>`. Kafka 3.9.1 and later only call token endpoints on an allow-list; the connector adds the configured endpoint to the JVM property
`org.apache.kafka.sasl.oauthbearer.allowed.urls` for you (set the property yourself to a longer comma-separated list if
several connectors use different providers).

#### F. Kerberos (GSSAPI)

```yaml
          security.protocol: SASL_SSL
          sasl.mechanism: GSSAPI
          sasl.kerberos.principal: drishti@BANK.EXAMPLE
          sasl.kerberos.keytab: /etc/drishti/drishti.keytab
          sasl.kerberos.service-name: kafka                   # the brokers' primary; "kafka" by default
          sasl.kerberos.krb5-conf: /etc/krb5.conf             # optional; the JVM reads one krb5.conf per process
          tls.ca-file: /etc/drishti/tls/ca.pem
```

The keytab must be readable by the server's user (`chmod 400`). The broker host name you put in `bootstrap-servers` must
be the one in the broker's service principal (`kafka/kafka1.bank.example@BANK.EXAMPLE`) and must resolve forward and
back. To use a ticket from `kinit` instead of a keytab: `sasl.kerberos.use-ticket-cache: true` and no keytab; you must
renew the ticket yourself, so use a keytab in production. Clock skew over 5 minutes fails authentication.

#### G. Confluent Cloud with API keys

```yaml
drishti:
  sources:
    connectors:
      risk-stream:
        plugin: kafka
        kinds: [netting-set]
        settings:
          flavour: confluent
          bootstrap-servers: pkc-xxxxx.us-east-1.aws.confluent.cloud:9092
          api-key: "${CONFLUENT_API_KEY}"
          api-secret: "${CONFLUENT_API_SECRET}"
          topics: risk.envelopes
          # Avro or JSON Schema values in Schema Registry:
          schema-registry.url: https://psrc-xxxxx.us-east-1.aws.confluent.cloud
          schema-registry.basic-auth: "${CONFLUENT_SR_KEY}:${CONFLUENT_SR_SECRET}"
```

Confluent Cloud's certificates are from a public CA, so no `tls.*` is needed. The Kafka API key and the Schema Registry
key are different keys; both are created in the Cloud console. Give the Kafka key `READ` on the topics (and `DESCRIBE`).

#### H. Confluent Platform on-premises: mTLS and Schema Registry

```yaml
drishti:
  sources:
    connectors:
      risk-stream:
        plugin: kafka
        kinds: [netting-set]
        settings:
          flavour: confluent
          security.protocol: SSL                               # overrides the preset's SASL_SSL
          bootstrap-servers: cp-kafka1.bank.example:9093,cp-kafka2.bank.example:9093
          topics: risk.envelopes
          tls.ca-file: /etc/drishti/tls/ca.pem
          tls.cert-file: /etc/drishti/tls/client.pem
          tls.key-file: /etc/drishti/tls/client.key
          schema-registry.url: https://cp-schema-registry.bank.example:8081
          schema-registry.tls.ca-file: /etc/drishti/tls/ca.pem
          schema-registry.tls.cert-file: /etc/drishti/tls/client.pem   # if the registry requires a client certificate
          schema-registry.tls.key-file: /etc/drishti/tls/client.key
```

With the preset, the PLAIN mechanism is only used when the protocol is a `SASL_` one, so overriding the protocol to `SSL`
needs no `api-key`.

### 11.7 Verifying a setup

**Before Drishti**, from the machine it runs on, check that the broker's certificate and your client certificate work.
These runs are against a local test listener with the CA of [TLS.md](TLS.md#7-making-and-converting-certificates-and-keys):

```
$ openssl s_client -connect kafka1.bank.example:9093 -servername kafka1.bank.example -CAfile ca.pem \
    -cert client.pem -key client-pkcs8.key -verify_hostname kafka1.bank.example -verify_return_error </dev/null
depth=1 CN=Example Internal CA
depth=0 CN=kafka1.bank.example
subject=CN=kafka1.bank.example
issuer=CN=Example Internal CA
Verification: OK
New, TLSv1.3, Cipher is TLS_AES_256_GCM_SHA384
Verify return code: 0 (ok)
```

The failures you meet, as `s_client` prints them:

```
verify error:num=62:hostname mismatch                                     # the certificate does not carry that name
Verify return code: 62 (hostname mismatch)

verify error:num=19:self-signed certificate in certificate chain           # the CA is not known: give -CAfile
Verify return code: 19 (self-signed certificate in certificate chain)

tlsv13 alert certificate required ... SSL alert number 116                 # mTLS listener, no client certificate sent
```

Then **with Kafka's own tools**, using the same files, to separate a cluster problem from a Drishti one. Write
`client.properties`:

```
security.protocol=SASL_SSL
sasl.mechanism=SCRAM-SHA-512
sasl.jaas.config=org.apache.kafka.common.security.scram.ScramLoginModule required username="drishti" password="…";
ssl.truststore.type=PEM
ssl.truststore.location=/etc/drishti/tls/ca.pem
```

```
$ kafka-topics.sh --bootstrap-server kafka1.bank.example:9094 --command-config client.properties --list
$ kafka-console-consumer.sh --bootstrap-server kafka1.bank.example:9094 --consumer.config client.properties \
    --topic risk.envelopes --from-beginning --max-messages 1
```

**Then Drishti:** Admin → Health shows the source `UP` (`UP (catching up)` while it reads the topic), and Admin → Packs →
Data source → *Test connection* reports a start-up error with the file and reason
([TLS.md, section 8](TLS.md#8-start-up-checks-and-their-messages)). Within 30 days of a certificate's expiry the health
text says so: `UP (TLS certificate CN=drishti (tls.cert-file) expires in 12 days (2026-10-22))`.

The connector's TLS and SASL paths are tested against a real Apache Kafka 3.9 broker in Docker with two secured
listeners (`KafkaSecureBrokerTest`): mutual TLS with PEM files, with a PKCS12 keystore and JKS truststore, and with a
merged trust and a keystore alias; `SASL_SSL` with SCRAM-SHA-512; and the refusals (a wrong SCRAM password, no client
certificate, an untrusted CA). The property translation for every other combination, and the wire-format decoding with
recorded bytes, are unit tests (`KafkaSecurityTest`, `ConfluentValueDeserializerTest`). OAUTHBEARER, Kerberos, Confluent
Cloud and a live Schema Registry were not run against real services: their properties are the ones Kafka documents for
each, built and validated by the Kafka client's own configuration parser in the tests.

### 11.8 ACLs

The connector needs `Describe` and `Read` on its topics. It joins no consumer group and commits nothing, so it needs no
group ACL. For example:

```
kafka-acls.sh --bootstrap-server … --command-config admin.properties --add --allow-principal User:drishti \
  --operation Read --operation Describe --topic risk.envelopes
```

### 11.9 Common errors and fixes

| What you see (health text or log) | Cause | Fix |
|---|---|---|
| `DOWN: TimeoutException: Timed out waiting for a node assignment` / `Bootstrap broker … disconnected` | the protocol does not match the listener's (SSL against PLAINTEXT, or the reverse), a wrong port, or a firewall | check `security.protocol` against the listener; the port is the listener's own |
| `SSL handshake failed` ... `PKIX path building failed … unable to find valid certification path` | the brokers' certificate is not trusted | `tls.ca-file` / `tls.truststore`; with a private CA the public ones are not trusted unless `tls.trust-jvm-default: true` |
| `No subject alternative DNS name matching kafka1.bank.example found` | the certificate does not carry the host name you put in `bootstrap-servers` (or in the broker's `advertised.listeners`, which the client is told to connect to after the first call) | re-issue with the names as SANs; or `tls.verify-hostname: false` as a last resort |
| `Failed authentication with … (SSL handshake failed)` plus `Received fatal alert: bad_certificate` / `certificate_unknown` | mTLS: the broker does not trust your client certificate | the client certificate's CA must be in the broker's truststore |
| `Authentication failed during authentication due to invalid credentials with SASL mechanism SCRAM-SHA-512` | wrong user or password, or the user has no credential for that mechanism | check `sasl.username`, `sasl.password`, and that the user was created for SCRAM-SHA-512 (captured from a real broker) |
| `Unsupported SASL mechanism` / `SaslAuthenticationException … mechanism` | the listener does not enable that mechanism | use one in the broker's `sasl.enabled.mechanisms` |
| `TopicAuthorizationException: Not authorized to access topics: [risk.envelopes]` | the principal has no ACL | [11.8](#118-acls); with mTLS the principal is the certificate subject |
| `tls.key-file … does not match the certificate in tls.cert-file …` and the other [start-up messages](TLS.md#8-start-up-checks-and-their-messages) | a file or password | fix the named setting |
| `tls.insecure-trust-all is not supported for Kafka` | the setting is for the other connectors | `tls.ca-file`, and `tls.verify-hostname: false` if names mismatch |
| `sasl.mechanism OAUTHBEARER needs sasl.oauth.token-endpoint, …` | a missing OAuth setting | all three of endpoint, id and secret are required |
| OAuth: `The URL … is not allowed` / `allowed.urls` | Kafka's token-endpoint allow-list | the connector adds its endpoint; check no other component overwrote `org.apache.kafka.sasl.oauthbearer.allowed.urls` |
| Kerberos: `Cannot locate default realm` / `Server not found in Kerberos database` | no `krb5.conf` for the JVM, or the broker name is not its service principal's host | `sasl.kerberos.krb5-conf`; use the exact broker host name |
| Kerberos: `Clock skew too great` | the clocks differ by more than 5 minutes | fix NTP |
| the connector is `UP` but no entity appears and the log says `a message in the Confluent wire format could not be read (… )` | Schema Registry: Protobuf, an unknown id, or refused credentials | see the reason; `schema-registry.basic-auth` is the registry's key, not the Kafka one |
| `the schema registry refused the credentials (HTTP 401)` | wrong registry credentials | `schema-registry.basic-auth: ${SR_KEY}:${SR_SECRET}` |
| `schema id N needs schema-registry.url (only JSON Schema payloads can be read without it)` | an Avro message and no registry configured | set `schema-registry.url` |

Everything not about security (offsets, topics that do not exist, slow catch-up) is in [section 13](#13-diagnosing).

### 11.10 Good practice

- Keep secrets in the environment or in mounted files; Health and `/api/v1/sources` do not show settings.
- Give the connector its own principal with read-only ACLs on its topics.
- Prefer SCRAM-SHA-512 or OAUTHBEARER over PLAIN; use PLAIN only over TLS (Confluent Cloud's API keys are the one common
  case).
- Rotate certificates before expiry ([TLS.md, section 10](TLS.md#10-rotation)); the health text warns 30 days ahead.
- `client.enable.auto.commit` is overridden only if you set it; leave it alone. The deserializers are set by the connector;
  overriding them breaks it.

## 12. Limits and trade-offs

- **Undated**: no history; a picked date comes from a dated store.
- **No reverse lookups, no field searches**: those come from the stores serving the same kinds.
- **Every server reads the whole topic**, at start and continuously.
- **A restart replays the topic**; search is empty and reads may be behind until it has caught up.
- **Cold reads are serial** through one reader; enable the disk cache for topics with many entities.
- **Deletes need a tombstone keyed `<kind>/<id>`** on an envelope topic; a bare-id envelope key cannot be deleted.
- **Ordering is per partition**: keep each entity on one key.

## 13. Diagnosing

`GET /api/v1/admin/health`, the connector's row (the fields as the code reports them; the values are illustrative):

```json
{"name": "trading-stream", "health": "UP", "live": true, "dated": false, "search": true,
 "cache": {"indexedEntities": 750, "memoryEntries": 12, "memoryMb": 0.1, "diskMb": 3.4, "diskHits": 9, "diskMisses": 0, "diskClears": 0}}
```

| Symptom | Likely cause | What to do |
|---|---|---|
| log: `source plugin kafka is installed but not configured (kafka needs settings.topics); it stays idle` | the plugin runs as itself with no `topics` | harmless; or configure it |
| `failedToStart` names the connector with a client message | a client configuration error (unresolvable `bootstrap-servers`, a bad `client.*` property) | fix the setting; restart |
| health stays `DOWN: not started` | no broker answered yet (the first metadata request waits up to 30 s) | check `bootstrap-servers` and the network |
| `DOWN: IllegalStateException: topic … has no partitions yet (reconnecting)` | the topic does not exist and the broker does not create topics | create it (compacted, keyed) |
| `DOWN: TimeoutException: … (reconnecting)` behind TLS or SASL | missing security settings, or a wrong advertised listener | set `security.protocol` and the TLS/SASL settings of your listener ([section 11](#11-security-tls-sasl-and-confluent)); check the broker's advertised listeners |
| `DOWN: no connection to the broker (reconnecting)` | the broker has been unreachable for 10 s | bring it back; the connector resumes by itself |
| health stays `UP (catching up)` | a large or uncompacted topic | wait; watch `indexedEntities`; compact the topic or use `mode: ticks` |
| Health shows the connector stale | nothing new for `stale-after` | check the producer; `lastUpdate` is the newest message's timestamp |
| a view does not tick | another live source answered (the demo samples), or the entity is not in the topic | `DRISHTI_DEMO_ENABLED=false`; check `provenance.source` |
| a delete does not delete | envelope tombstone keyed by a bare id, or an empty value instead of null | key envelopes `<kind>/<id>`; send a real null |
| an entity disappears after a bad message | a mapped message whose value is not JSON became its latest | fix the producer; publish a good message for the key |
| cold reads are slow under load | they queue behind the one reader | `disk-cache.enabled: true`; raise `cache-mb` |
| `DRS-1004` while the broker is down | a cold read waits up to 5 s for Kafka | expected until the broker returns; or raise `fetch-timeout` above 5 s to fall through to the lake |
| messages skipped after a long outage | resume offset removed by retention, `auto.offset.reset` is `latest` | `client.auto.offset.reset: earliest`, or restart the server to replay |

## As a connector file

A connector is a site resource: one YAML file in `config/connectors/`, and the file name is the connector's name. The settings of this document go under `settings:` in that file, with nesting flattened to dotted keys (`layout: {trade: {columns: [...]}}` is `layout.trade.columns`); `${ENV_VAR}` placeholders are resolved when the connector starts, and a credential is only ever an `${ENV_VAR}` or a `file:/path` reference. A pack names the connectors it reads through and may suggest a template; the server writes the template to the file once, at the first start, and the file is then the site's. A complete file:

```yaml
# config/connectors/trading-stream.yaml
plugin: kafka
enabled: ${DRISHTI_STREAM_TRADING:false}
kinds: [trade]
description: Live trades from Kafka
settings:
  bootstrap-servers: kafka-1.example.com:9093,kafka-2.example.com:9093
  topics: [drishti.trading.trades]
  kind: trade
  id-field: tradeId
  stale-after: 15m
  disk-cache:
    enabled: true
    root: ./data/cache
    max-gb: '10'
  tls:                          # the shared TLS block; see CONNECTOR_FILES.md
    enabled: true
    truststore: { path: /etc/drishti/tls/ca.p12, password: ${KAFKA_TRUSTSTORE_PASSWORD} }
    keystore:   { path: /etc/drishti/tls/client.p12, password: ${KAFKA_KEYSTORE_PASSWORD} }
```

The file is applied to the running server within seconds, without a restart, and is edited in the editor of your choice, in **Admin → Connectors** (a form generated from this document's settings, a YAML tab, **Test connection**, history) or with `drishti.py connector apply`. The folder, the format, live reload, precedence and the deprecated `drishti.sources.connectors` form are in [CONNECTOR_FILES.md](CONNECTOR_FILES.md). The same over TLS is `config/connectors.examples/other/trading-stream.yaml`.

## 14. Settings

In the connector file's `settings:` (see [As a connector file](#as-a-connector-file)):

| Setting | Default | Meaning |
|---|---|---|
| `bootstrap-servers` | `localhost:9092` | the brokers |
| `topics` | none (required) | comma list; without it the plugin stays idle (`PluginNotConfigured`) |
| `kind` | none | mapped messages on every topic: their kind |
| `kind.<topic>` | none | mapped messages on that topic: their kind (wins over `kind`) |
| `id-field` | `id` | mapped messages without a key: the id field, every topic |
| `id-field.<topic>` | none | the id field on that topic (wins over `id-field`) |
| `mode` | `state` | `state` (index, caches, reads) or `ticks` (no reads; pushes to views of its kinds) |
| `cache-mb` | `256` | memory cache, weighed by message length |
| `search` | `true` | keep ids for type-ahead (state mode only) |
| `poll-ms` | `200` | consumer poll interval in milliseconds |
| `source-name` | `kafka` (a named connector: its name) | provenance source, Health name, client id `drishti-<source-name>` |
| `client.<property>` | none | any Kafka consumer property, for both consumers; applied last, so it wins over the security settings below |
| `flavour`, `security.protocol`, `tls.*`, `sasl.*` | `apache`, inferred | TLS, SASL and the Confluent preset; see [section 11](#11-security-tls-sasl-and-confluent) |
| `value-format`, `schema-registry.*` | `string` | Confluent wire format and Schema Registry; see [section 11.5](#115-schema-registry-and-the-confluent-wire-format) |
| `disk-cache.enabled` | `false` | write each message to a RocksDB store on local disk (state mode only) |
| `disk-cache.root` | `./data/cache` | parent of the default folder |
| `disk-cache.dir` | `<disk-cache.root>/<source-name>` | the store's folder |
| `disk-cache.max-gb` | `10` | disk budget (decimal allowed); the oldest data goes first beyond it |
| `disk-cache.reset-at` | `02:00` | daily clearing time; `never` or blank: never |
| `disk-cache.zone` | `America/New_York` | the zone of `reset-at` |
| `stale-after` | none | engine setting: Health marks the connector stale after this long without a new message |

Set by the connector and not to be overridden: `key.deserializer` and `value.deserializer` (a string deserializer; with the Confluent wire format, one that also strips and decodes it),
`enable.auto.commit` (`false`), `client.id` (`drishti-<source-name>`; the reader `drishti-<source-name>-reader`, with
`max.poll.records` 1). Fixed in the code: 10 s without a broker connection before health says so, 30 s to list a
topic's partitions, 5 s for a cold read, supervisor backoff 1 s to 30 s.

## 15. Trying it: `stream.py`

```bash
docker compose -f deploy/compose.data.yaml up -d kafka            # apache/kafka 3.9.1, single-node KRaft, cleanup.policy compact
uv run --with kafka-python python tools/samplegen/stream.py \
    --bootstrap localhost:9092 --topic drishti.trading.trades --samples config/packs/trading/samples/trade --rate 5 --seconds 0
DRISHTI_PACKS=trading DRISHTI_STREAM_TRADING=true DRISHTI_DEMO_ENABLED=false \
  java -jar drishti-server/target/drishti-server-*-exec.jar
```

`stream.py` reads every `*.json` under `--samples` (750 trades in the trading pack), drops each one's `_meta`, and
publishes it keyed by `tradeId` (`linger.ms` 20). It prints

```text
published 750 trades to drishti.trading.trades; ticking 5.0/s
```

then picks a random trade `--rate` times a second (a fixed seed, 7), moves its `mtm` by a Gaussian step of
`max(500, |mtm| × 0.002 + notional × 1e-5)` and adds the same move to `pnl1d`, and publishes the whole document again.
`--seconds 0` (the default) runs until Ctrl+C; any other value stops after that many seconds.

`TRD MX-20000001 <GO>` on Live then shows provenance `trading-stream` and ticks whenever the trade is picked. Pick a
date and the same trade comes from `trading-store`. Why switch the demo off: the trading samples hold the same ids;
Kafka is asked before the samples on Live anyway, but with the samples off a trade Kafka does not hold falls through
to the lake rather than to a random-walk sample.

## 16. Checklist for production

1. Use a **compacted topic keyed by entity id** (`<kind>/<id>` for envelopes); produce each entity under one key.
2. Prefer **mapped** topics (a kind per topic) for volume: keyed mapped messages are indexed without parsing.
3. Give the connector **`kinds:`** and **`stale-after`**.
4. Decide **state or ticks**: state when the topic is the store for Live, ticks when a dated store already holds the
   data or the topic is too large to index.
5. Size the heap: about 0.5 GB per million entities (estimate) plus `cache-mb` several times over; enable the
   **disk cache** on local disk with `disk-cache.max-gb` above a day's distinct documents.
6. Put the security properties under `client.` and secrets in the environment; grant `Describe` and `Read` on the
   topics.
7. Consider `client.auto.offset.reset: earliest` so a long outage never skips messages.
8. Pair it with a **dated store** for the same kinds, and check the order in [section 10.4](#104-with-a-lake-for-history).
9. After start, check Health: `UP (catching up)` then `UP`, `indexedEntities` near the number of entities, and a
   view's `provenance.source`.

---

## Appendix: walk-through and worked examples

Moved here from the former connector guides, so that everything about this connector is in one document.

### Walk-through, step by step

**The situation.** Your trading system publishes each trade's latest state to a Kafka topic whenever it changes. You
want trade views to tick as messages arrive, and the full history from the lake when a user picks a date. How the
connector reads the topic is in [section 2](#2-how-the-connector-reads-a-topic), the two message shapes in
[section 3](#3-the-messages), memory in [section 10.1](#101-memory-per-entity), and the health texts in
[section 9](#9-outages-and-reconnection).

1. **Configure it.** Use the pack form in [section 5.1](#51-pack-form-the-trading-packs-trading-stream) for the
   trading stream, or the site form in [section 5.2](#52-site-form-envelopes-and-a-mapped-topic-on-a-secured-cluster)
   for envelopes on a secured cluster. The settings are in [section 14](#14-settings).
2. **Publish.** `stream.py` ([section 15](#15-trying-it-streampy)) publishes every sample trade once, keyed by
   `tradeId`, then gives `--rate` random trades a second a new `mtm` and `pnl1d`; `--seconds 0` runs until Ctrl+C.
3. **Start the server.** In another terminal:

   ```bash
   DRISHTI_STREAM_TRADING=true DRISHTI_DEMO_ENABLED=false DRISHTI_PACKS=counterparty-risk,market-risk \
     java -jar drishti-server/target/drishti-server-*-exec.jar
   ```

4. **Look at it in the terminal.** `TRD MX-20000001 <GO>` on Live: the *Live* badge shows, provenance is
   `trading-stream`, and the MTM moves every time `stream.py` touches the trade. Pick a date: the same trade comes
   from `trading-store` (the lake), because dated connectors go first for a picked date, and the view is a static
   snapshot. Why the demo is off is explained at the end of [section 15](#15-trying-it-streampy).
5. **Watch Health.** `UP (catching up)` until the end offsets seen at start are reached, then `UP`. This holds in
   `ticks` mode too: it keeps nothing but still replays the topic at every start
   ([section 8](#8-start-catching-up-and-restarts)).

Send one envelope by hand, to a connector reading `risk.envelopes` (such as the `risk-stream` site form, with
`bootstrap-servers: localhost:9092` and no `client.*` security lines). Create the topic first: on a broker that does
not create topics by itself, the connector waits for a topic that does not exist yet. The compose broker does create
them (Kafka's default `auto.create.topics.enable`), so a connector started first has already created its topics, and
`--if-not-exists` keeps the command from failing with `TopicExistsException`:

```bash
docker compose -f deploy/compose.data.yaml exec kafka /opt/kafka/bin/kafka-topics.sh \
    --bootstrap-server localhost:9092 --create --if-not-exists --topic risk.envelopes --config cleanup.policy=compact
docker compose -f deploy/compose.data.yaml exec -T kafka /opt/kafka/bin/kafka-console-producer.sh \
    --bootstrap-server localhost:9092 --topic risk.envelopes \
    --property parse.key=true --property key.separator='|' <<'EOF'
netting-set/NS-ALDERSHOT-FRA|{"kind":"netting-set","id":"NS-ALDERSHOT-FRA","doc":{"nettingSetId":"NS-ALDERSHOT-FRA","tradeCount":3,"netMtm":-339550}}
EOF
```

`curl -s localhost:18480/api/v1/entities/netting-set/NS-ALDERSHOT-FRA/raw | jq -c .provenance` then shows
`"source":"risk-stream"`, `"live":true`, `"generation":0` (the offset). A tombstone keyed the same way deletes it
(the console producer sends one with `--property null.marker=NULL` and the line
`netting-set/NS-ALDERSHOT-FRA|NULL`); the next read falls through to the lake (`credit-store`).

### Configuration by example

The pack's template and the secured-cluster connector file are in [section 5](#5-configuration); the settings are in
[section 14](#14-settings). The disk cache starts empty on every run (the topic is replayed anyway); it saves
re-reading Kafka for documents not in memory.

To send one mapped message by hand:

```bash
docker compose -f deploy/compose.data.yaml exec kafka /opt/kafka/bin/kafka-console-producer.sh \
    --bootstrap-server localhost:9092 --topic drishti.trading.trades --property parse.key=true --property key.separator='|'
MX-20000001|{"tradeId":"MX-20000001","productType":"IRS","mtm":1900000,"notional":242000000.0}
```
