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
# The RabbitMQ connector: live entities from AMQP queues

The `rabbitmq` connector reads RabbitMQ queues (AMQP 0-9-1) and turns each message into the latest document of one
entity: a margin call, an order, a credit limit. A queue delivers a message once and keeps nothing after it is
acknowledged, so the connector keeps the latest document of every entity itself, in a RocksDB **state store** on local
disk, and pushes every change to the views that are open. A message is acknowledged only once the store has kept it,
by default synced to disk, so neither a crash nor a power loss loses an acknowledged message. This document explains
how messages arrive and become entities, the state store and what survives a restart, its durability and disk budget,
every read path and its cost, how the connector behaves at scale and through outages, and every setting, exactly as
the code has them.

The code is `RabbitMqSourcePlugin` (plugin `drishti-plugin-rabbitmq`, about 130 lines: connection, queues, consumers,
health) on top of `MessageStateSource` (module `drishti-messaging`: message parsing, the state store, the memory cache,
type-ahead and live fan-out), which the ActiveMQ connector shares ([ACTIVEMQ_CONNECTOR.md](ACTIVEMQ_CONNECTOR.md)). The
state store is `DiskCache` (module `drishti-diskcache`), also used by the Kafka connector's disk cache
([KAFKA_CONNECTOR.md](KAFKA_CONNECTOR.md)). For a first setup see
[CONNECTOR_GUIDE.md, chapter 12](CONNECTOR_GUIDE.md#12-a-message-queue-rabbitmq); every setting is also listed in
[CONFIGURATION.md](../admin/CONFIGURATION.md#activemq-and-rabbitmq--message-queues).

## Contents

1. [When to use it](#1-when-to-use-it)
2. [How messages arrive](#2-how-messages-arrive)
3. [What a message holds](#3-what-a-message-holds)
4. [Configuration](#4-configuration)
5. [The state store](#5-the-state-store)
6. [Durability and disk budget](#6-durability-and-disk-budget)
7. [How the connector reads](#7-how-the-connector-reads)
8. [Live updates](#8-live-updates)
9. [Scale and limits](#9-scale-and-limits)
10. [History: RabbitMQ with a lake](#10-history-rabbitmq-with-a-lake)
11. [Failure and recovery](#11-failure-and-recovery)
12. [Security](#12-security)
13. [Diagnosing](#13-diagnosing)
14. [Settings](#14-settings)
15. [Checklist for production](#15-checklist-for-production)

---

## 1. When to use it

| Use it for | Prefer another connector for |
|---|---|
| a system that already publishes changes to a RabbitMQ exchange (margin calls, orders, limit changes) | history by business date: a queue has none (pair it with a lake, [section 10](#10-history-rabbitmq-with-a-lake)) |
| live views that update within a moment of the change | a stream you want to replay from the beginning (Kafka keeps the log; [KAFKA_CONNECTOR.md](KAFKA_CONNECTOR.md)) |
| tens or hundreds of thousands of entities, each changing now and then | reverse lookups (*Linked entities*, impact with F8): this connector declares none |
| a feed that should reach Drishti even while Drishti is down (messages wait in the queue) | a store other systems also query: the state lives only in Drishti's local disk |

The connector is **undated** (it knows only "now"), **live** (it pushes changes), and supports **search** (type-ahead
from memory). It does not support reverse lookups or dated reads. Its manifest declares
`SourceCapabilities(live = true, reverseLookup = false, search = true, dated = false)`.

## 2. How messages arrive

At start the connector opens **one AMQP connection and one channel**, named `drishti-<source-name>` (visible in the
RabbitMQ management UI), and for each queue in `queues`:

1. with `declare: true` (the default), declares the queue **durable, not exclusive, not auto-delete, without
   arguments** (`queueDeclare(queue, true, false, false, null)`), and, when `bind.<queue>` holds `exchange:routing.key`,
   binds it to that exchange (the exchange must already exist; the connector never declares exchanges);
2. starts a consumer with tag `drishti-<source-name>-<queue>`, **manual acknowledgement**, and the channel's
   `prefetch` (`basicQos`, 100 by default: at most 100 unacknowledged messages per consumer in flight).

```
 collateral system                         RabbitMQ                                    Drishti
 ─────────────────      publish        ┌──────────────────┐   bind             ┌──────────────────────────────┐
 margin.call.new  ────────────────────▶│ exchange          │──────────────────▶│ queue drishti.margin-calls   │
                                       │ "collateral"      │ margin.#          │ (durable)                    │
                                       └──────────────────┘                    └──────────────┬───────────────┘
                                                                                              │ deliver (prefetch 100)
                                                                         ┌────────────────────▼────────────────────┐
                                                                         │ margin-mq: parse → state store (RocksDB)│
                                                                         │ → memory cache → type-ahead → open views│
                                                                         │ → basicAck                              │
                                                                         └─────────────────────────────────────────┘
```

Each delivery is handled in order on the channel's consumer thread:

1. the body is read as UTF-8 text; the id comes from the `id` header, else the message's `message_id` property;
2. the message is parsed and applied (`MessageStateSource.accept`): the document is written to the state store and
   the memory cache, the id is added to type-ahead, and the document is pushed to every view subscribed to the entity;
3. only then is the message acknowledged (`basicAck(deliveryTag, multiple = false)`): one acknowledgement per message.
   If the state store could not keep it (a full disk, an I/O error), it is **not** acknowledged: a second later it is
   returned to the queue (`basicNack(deliveryTag, false, requeue = true)`) and delivered again
   ([section 6.2](#62-when-the-store-cannot-keep-a-message)).

All queues of one connector share the one channel, so their deliveries are applied **one at a time**, in the order the
channel receives them. Within one queue that is the queue's order.

A message the connector cannot use (not JSON, no kind, no id) is counted as `rejected` and **acknowledged all the
same**: it is dropped, not requeued and not dead-lettered ([section 3](#3-what-a-message-holds)), since it could never
be read and requeueing it would bring it back forever.

## 3. What a message holds

The body is JSON in UTF-8. Two headers are read; everything else (other headers, content type, routing key, exchange,
timestamp) is ignored.

| Header or property | Meaning |
|---|---|
| header `id` | the entity id (any header type; its text is used) |
| property `message_id` | the id when there is no `id` header |
| header `deleted` | the text `true` (any case) deletes the entity |

### 3.1 A document on a queue with a kind

When the queue has a kind (`kind.<queue>`, or `kind` for every queue), the body is the entity's document as it is. The
id is the `id` header (or `message_id`), else the body's field `id-field.<queue>` (else `id-field`, default `id`).

```text
exchange:     collateral                 routing key: margin.call.new
queue:        drishti.margin-calls       (kind.drishti.margin-calls: margin-call, id-field.drishti.margin-calls: callId)
properties:   delivery_mode 2 (persistent), headers {"id": "MC-CORAL-FRA-2"}        (the header is optional)
body:
{"callId": "MC-CORAL-FRA-2", "callDate": "2026-09-28", "marginType": "Variation", "direction": "We call",
 "amount": 128000.0, "status": "Settled", "disputeAmount": 0,
 "events": [{"at": "2026-09-28 09:05", "event": "Call issued", "by": "collateral-system"},
            {"at": "2026-09-28 11:40", "event": "Agreed", "by": "counterparty ops"},
            {"at": "2026-09-29 10:15", "event": "Settled", "by": "payments"}],
 "nettingSet": "NS-CORAL-FRA", "csa": "CSA-CORAL", "currency": "USD"}
```

The stored document is the body exactly (re-serialised); the connector adds nothing to it and removes nothing.

### 3.2 An envelope on a queue without a kind

On a queue without a kind (no `kind.<queue>` and no `kind`), the body is an envelope:

```json
{"kind": "netting-set", "id": "NS-CORAL-FRA", "doc": {"nettingSetId": "NS-CORAL-FRA", "counterparty": "CP-CORAL", "netMtm": 4120000.0}}
```

Here the envelope's `"id"` wins; the `id` header (or `message_id`) is used only when the envelope has no `"id"`. One
queue of envelopes can carry any number of kinds; each kind seen joins the connector's kinds.

### 3.3 What each message becomes

| Message | Becomes |
|---|---|
| a JSON body on a queue with a kind, with an id (header, `message_id` or body field) | that kind's latest document: stored, pushed to open views |
| a JSON envelope `{kind, id, doc}` on a queue without a kind | the envelope's entity |
| header `deleted: true`, with an empty or JSON body | a delete (the id must still be found: header, body field, or envelope; on a queue without a kind an empty body is rejected) |
| an envelope with `"doc": null` or no `doc` | a delete |
| an empty body with an `id` header, on a queue with a kind | a delete |
| an empty body on a queue without a kind | rejected |
| a body that is not JSON, or no kind, or no id | rejected |

A delete removes the entity from the state store, the memory cache and type-ahead, and is **pushed to open views of
it**: the view keeps its last document, greyed, under a banner saying when the entity was deleted, and stops updating
([LIVE.md](../architecture/LIVE.md#deleted-entities)). A later message for the entity repaints it. Every message,
rejected or not, counts in `received` and moves the connector's `lastUpdate` (used by `stale-after`).

A delete with a header looks like this (from the connector's test):

```text
queue:    drishti.trades       headers {"id": "Q-9", "deleted": "true"}       body: (empty)
```

## 4. Configuration

The connector is normally a **named connector**, declared by a pack or by the site. Running the plugin as itself
(`drishti.sources.plugins.rabbitmq`) is off in the shipped `application.yaml` (`enabled:
${DRISHTI_RABBITMQ_ENABLED:false}`).

### 4.1 Pack form

In `packs/<pack>/pack.yaml` (dotted keys written flat, as for every pack setting):

```yaml
connectors:
  margin-mq:
    plugin: rabbitmq
    enabled: ${DRISHTI_MARGIN_MQ:false}                            # off until switched on
    kinds: [margin-call]
    settings:
      uri: ${RABBIT_URI:amqp://guest:guest@localhost:5672/%2f}     # amqps:// for TLS
      queues: drishti.margin-calls                                 # comma list
      declare: true                                                # declare each queue durable, and bind it
      bind.drishti.margin-calls: collateral:margin.#               # exchange:routing.key; the exchange must exist
      kind.drishti.margin-calls: margin-call
      id-field.drishti.margin-calls: callId
      prefetch: 100
      state.max-gb: 20                                             # this connector's disk budget
      state.when-full: evict-oldest                                # the default; warn keeps everything
      stale-after: 30m                                             # quiet for 30 minutes: Health turns amber
```

### 4.2 Site form

In `application.local.yaml` (or any Spring configuration), under `drishti.sources.connectors`. A setting key with
characters other than letters, digits, `-` and `.` must be bracketed and quoted, for example
`"[kind.desk_orders]": order`:

```yaml
drishti:
  sources:
    connectors:
      margin-mq:
        plugin: rabbitmq
        kinds: [margin-call]
        settings:
          uri: ${RABBIT_URI}                       # amqps://drishti:…@rabbit.bank.example:5671/collateral
          queues: drishti.margin-calls,drishti.collateral-events
          declare: false                           # the platform team owns the queues; bind.* is ignored
          kind.drishti.margin-calls: margin-call
          id-field.drishti.margin-calls: callId
          # drishti.collateral-events has no kind: its messages are envelopes {kind, id, doc}
          heartbeat-seconds: 20
          recovery-interval-ms: 2000
          cache-mb: 256
          state.root: /var/lib/drishti/state       # the store goes to /var/lib/drishti/state/margin-mq
          state.max-gb: 20
          state.durability: sync                   # the default: no acknowledged message is lost, even on power loss
```

Give the connector `kinds:` whenever you can. Without it (and with no `kind` setting and an empty state store) the
manifest lists no kinds, which means *every* kind: the router then asks this connector for every entity it reads (a
memory and a RocksDB miss each time) until the first message arrives.

### 4.3 Queues, declaring and binding

| `declare` | What happens at start and after each automatic recovery |
|---|---|
| `true` (default) | each queue is declared durable with no arguments, and bound per `bind.<queue>`; topology recovery repeats both after a reconnect, so a broker that lost the queue gets it back |
| `false` | the queues must exist; `bind.<queue>` is ignored; the connector only consumes |

Use `declare: false` when the queue needs arguments (quorum type, message TTL, dead-letter exchange, length limit) or
exists already with any: declaring it again with different arguments fails with `PRECONDITION_FAILED - inequivalent
arg` and the connector never starts consuming ([section 13](#13-diagnosing)).

**One queue per Drishti server.** Consumers of the same queue share its messages (round robin), so two servers on one
queue would each hold part of the entities. Give each server its own queue, bound to the same exchange (with a
`topic` or `fanout` exchange every bound queue receives every message).

## 5. The state store

### 5.1 What it is

A RocksDB database in `state.dir` (default `<state.root>/<source-name>`, that is `./data/state/<connector>`), one key
per entity, `<kind>/<id>`, holding the latest document as JSON bytes. It is opened **persistent**: the newest
generation folder (`gen-<millis>-<n>`) found at start is reopened, older generation folders are deleted. Each
connector has its own store, write-ahead log and budget.

| RocksDB setting (from `DiskCache`) | Value |
|---|---|
| compression | LZ4 |
| compaction | level compaction: only the latest value of each key is kept (overwrites and deletes are compacted away) |
| write buffer (memtable) | 32 MB |
| write-ahead log | per `state.durability`: synced on every write (`sync`, the default), written but not synced (`wal`), or off (`none`); capped at 64 MB |
| a write that fails | fails loudly: the message is not acknowledged ([section 6.2](#62-when-the-store-cannot-keep-a-message)) |

Because compaction keeps only the newest value of each key, the store's size follows **the number of live entities
times their compressed size**, not the number of messages: a margin call updated fifty times costs one document's
space once compacted, and a delete gives its space back. Between compactions overwritten values still take some
room. `stateMb` in the cache figures is the table files plus the write buffers still in memory (which the write-ahead
log mirrors on disk).

### 5.2 What survives a restart

| Event | Result |
|---|---|
| Drishti stopped cleanly and started again | the store is reopened; every entity is read once at start to rebuild the id set and type-ahead; reads answer at once |
| messages published while Drishti was down | they wait in the queue (durable queue, persistent messages) and arrive when the connector reconnects |
| the RabbitMQ broker restarted | durable queues and persistent messages survive; transient messages (`delivery_mode` 1) are lost by the broker |
| the Drishti process killed (`kill -9`, out-of-memory kill) | with `sync` or `wal`: nothing acknowledged is lost (the write-ahead log is replayed when the store reopens); with `none`: writes still in the 32 MB memtable are lost, although those messages were acknowledged |
| a power loss or kernel crash | with `sync` (the default): nothing acknowledged is lost; with `wal`: the last moments the operating system had not yet written can be lost; with `none`: up to the 32 MB memtable |
| the state folder deleted (server stopped) | every entity is forgotten; the queue cannot send them again: only new messages rebuild the state |

A message is acknowledged only after the store has kept it, so a crash *between* the write and the acknowledgement
redelivers it (harmless: the same document is written again).

### 5.3 Daily clearing

`state.reset-at: "06:00"` (with `state.zone`, `America/New_York` by default) clears the store every day at that time:
a fresh, empty RocksDB generation takes over at once and the old one is deleted when its last reader leaves. Use it for
state that should start empty each day (intraday orders). The clearing is recomputed every day, so daylight-saving
changes are honoured.

Clearing empties the store only. The id set, type-ahead and the memory cache are not cleared: until the next restart,
type-ahead still offers yesterday's ids, an entity still in the memory cache still answers, and one that is not answers
"not held".

### 5.4 Purging

Admin → Caches → Purge (or `Purge all`) drops the **memory cache only**. The state store is the only copy of what the
queues delivered, so it is never purged; clear it with `state.reset-at`, or by deleting its folder with the server
stopped.

## 6. Durability and disk budget

### 6.1 Durability: what an acknowledged message survives

`state.durability` chooses how each write reaches the disk. `basicAck` is sent only after the write returns, so the
level decides what an **acknowledged** message survives:

| `state.durability` | How a write is kept | Survives a crash of the process | Survives a power loss or a kernel crash | Cost |
|---|---|---|---|---|
| `sync` (default) | written to the write-ahead log, which is synced to the disk (`fsync`) before the write returns | yes | yes: no acknowledged message is lost | one disk sync per message: the connector's throughput is bounded by the disk's sync latency, typically thousands of messages a second on an SSD |
| `wal` | written to the write-ahead log, not synced: the operating system writes it out shortly after | yes | no: the last moments of messages (what the operating system had not yet written) can be lost | a write to the page cache: close to `none` |
| `none` | no write-ahead log: the write lands only in the 32 MB memtable until it is flushed to a table file | no: up to the 32 MB memtable is lost, although those messages were acknowledged | no | the fastest |

`none` is the behaviour of earlier versions. Any other value stops the connector from starting (it is listed under
`failedToStart`).

**The cost of `sync`, in more detail.** All queues of a connector are applied one message at a time on its channel's
consumer thread, and with `sync` each message waits for its own disk sync. RocksDB's group commit folds writes that
arrive together into one sync, which helps a store written by several threads but not one connector, whose writes
come one after another; separate connectors have separate stores and logs and sync independently. So a connector's
rate is about one message per sync: an SSD (best, one with power-loss protection) gives thousands a second, a desktop
SSD without it hundreds, a spinning disk tens. These are general figures for the hardware, not measurements of this
connector. A larger `prefetch` does not raise this bound (it only keeps the next messages ready). When the rate
matters more than the last moments before a power loss, use `wal`; or split a heavy feed over several connectors,
each with its own store.

### 6.2 When the store cannot keep a message

A failed write (disk full, an I/O error, a store already closed) is not swallowed: the connector waits a second and
sends `basicNack(deliveryTag, multiple = false, requeue = true)`, so the message goes back on the queue and is
delivered again. Meanwhile health is `DOWN: <reason> (messages are not acknowledged and come again)`, for example
`DOWN: state store write failed: IO error: No space left on device … (messages are not acknowledged and come
again)`, until a message is kept again. Free the disk (or raise the budget, [section 6.3](#63-the-disk-budget-per-connector))
and the connector carries on by itself; nothing is lost while the queue holds the messages.

Two consequences of requeueing:

- **Order.** Up to `prefetch` later messages are already on their way to the consumer when one is requeued; if the
  store recovers while they are applied, a later message can be kept before the requeued earlier one, and an entity
  may then hold an older document until its next message. Where that matters, use `prefetch: 1`.
- **Delivery limits.** A classic queue requeues without limit. A quorum queue counts deliveries: past its delivery
  limit (20 by default from RabbitMQ 4.0) the message is dead-lettered, or dropped when the queue has no dead-letter
  exchange. If disk outages may be long, raise the limit with a policy (`delivery-limit`) on queues you own.

Unreadable messages are a different case: they are acknowledged and counted in `rejected`
([section 3.3](#33-what-each-message-becomes)).

### 6.3 The disk budget, per connector

`state.max-gb` (10) is the budget of **this connector's** store (each connector has its own `state.dir`, so budgets
add up across connectors on one disk). Every `state.check-seconds` (60) the store's size (`stateMb`) is compared
with the budget, and `state.when-full` decides what happens past it:

| `state.when-full` | Past the budget |
|---|---|
| `evict-oldest` (default) | the entities **written longest ago** are removed until the store is estimated to be under 90% of the budget (from the average size of an entity), then the store is compacted so the disk gives the space back. If it is still over, the next check removes more. Each eviction is logged at WARN (`margin-mq: state store over its budget (20500 MB of 20480 MB): evicted the 41250 entities written longest ago (now 18300 MB)`) and counted in `evicted`. Health reads `UP (state store over its budget: X of Y GB; the oldest entities are being evicted)` until the store is back under |
| `warn` | nothing is removed. Health reads `UP (state store over its budget: X of Y GB; nothing is dropped: raise state.max-gb or add disk)` while the store is over. Size it before the disk fills: a full disk makes writes fail ([section 6.2](#62-when-the-store-cannot-keep-a-message)) |

"Written longest ago" is the time of the entity's last message in this run. Entities found in the store at start
count as older than anything this run writes, and among themselves go in key order (`<kind>/<id>`), since the store
does not record when they were written. An evicted entity is gone from the store, the memory cache and type-ahead,
as if a delete had arrived, except that open views are not told (the entity was not deleted at its source: an open
view keeps its last document); its next message brings it back.

The health text gives sizes in the same gigabytes as `state.max-gb` (1,024³ bytes), so a budget of `10` shows as
`10.0 GB`. `budgetMb` in the cache figures is the budget in MB (`10240` for `10`). Any `state.when-full` other than
`evict-oldest` or `warn` stops the connector at start (`failedToStart`), so a typo never evicts.

### 6.4 Sizing the disk

- **The store**: the number of entities held times their compressed size (LZ4; JSON typically shrinks several times),
  plus some room for values not yet compacted. For example 200,000 margin calls of 4 KB is about 0.8 GB before
  compression. Set `state.max-gb` above the expected total with a margin, so `evict-oldest` only acts on entities
  that are genuinely stale.
- **Beyond `stateMb`**: up to 64 MB of write-ahead log (none with `durability: none`), RocksDB's small `LOG` and
  `MANIFEST` files, and room for a compaction to write its new files before it deletes the old ones; the full
  compaction after an eviction can briefly need up to the store's size again. Plan about twice `state.max-gb` of disk
  per connector, and add up every connector on the same disk.
- **Back it up**: it is the only copy of what the queues delivered (stop the server, copy the folder, start; or rely
  on the producers to resend).

## 7. How the connector reads

| Read | Supported | How | Cost |
|---|---|---|---|
| one entity (`MC MC-CORAL-FRA-2 <GO>`) | yes | memory cache, else one RocksDB get and one JSON parse | memory: microseconds; disk: one key lookup in LZ4 files |
| type-ahead | yes | the in-memory `HitIndex`: ids starting with the text first, then ids containing it, then titles | memory only; no broker call |
| a picked business date | no (undated) | the date is ignored; dated connectors are asked first | — |
| reverse lookups (*Linked entities*, impact) | no | `reverseLookup` is false; other connectors answer | — |
| searches (`MC where …`), pick lists, derived kinds | through the engine | members are listed from type-ahead and read one by one | one single read per member |
| live push | yes | each applied message is pushed to the entity's subscribers | in the consumer thread, per message |

### 7.1 Opening one entity

The memory cache (Caffeine, bounded by weight to `cache-mb`, 128 MB; an entry weighs twice its JSON size plus 64 bytes)
is checked first. On a miss the document's bytes are read from RocksDB, parsed, put in the memory cache and returned.
An id the connector never received answers "not held" and the router asks the next candidate.

Provenance carries the connector's `source-name`, `live: true`, and a `generation` from a counter that increases with
every document built (each message, and each read from disk): it is monotonic within one run of the server and starts
again after a restart. `businessDate` is `null`.

### 7.2 Type-ahead

Every entity received is in type-ahead, with the subtitle `<kind> · <source-name>` (for example `margin-call ·
margin-mq`). A new id is appended to a pending list, folded into the sorted arrays every 4,096 additions. A delete of a
known entity is a cheap removal on the consumer thread: dropped from the pending list, or marked removed in the sorted
arrays (searches skip it) and folded out every 4,096 removals.

## 8. Live updates

A view opened Live subscribes through the router to the first live connector that holds the entity (a real stream
before the demo samples). When a message for that entity is applied, the document is pushed to every subscriber at
once, on the consumer thread, before the acknowledgement; one subscriber that throws does not stop the others. The
connector does not declare `pushes(ref)`, so it ticks only entities it holds itself, not entities another connector
answers.

A view of a picked business date is a static snapshot and does not tick.

## 9. Scale and limits

### 9.1 Memory

| Held in the heap | Per entity | Bounded by |
|---|---|---|
| recent documents | about twice the JSON size | `cache-mb` (128) |
| the id set, the weight map, the type-ahead entry, the last-written time (for `evict-oldest`) | a few small objects (an estimate of the order of half a kilobyte, not a measurement) | nothing: one entry per entity held |

A delete or an eviction removes the entity's entry from every one of these. With a million entities, plan for several
hundred megabytes of heap for the id structures beyond `cache-mb`.

### 9.2 Disk

Each message writes its document, LZ4-compressed; compaction keeps only the newest value of each entity, so the store
follows the entities held, within `state.max-gb` ([section 6.3](#63-the-disk-budget-per-connector)). With the default
`sync`, each write is also synced to the write-ahead log (at most 64 MB). At start the whole store is iterated once
to rebuild the id set, so start time grows with the number of entities.

### 9.3 Throughput

Every message of a connector is applied on one consumer thread, in order: parse the body, serialise the document,
write to RocksDB (with the default `state.durability: sync`, the write waits for a disk sync, which usually
dominates: about one message per sync, typically thousands a second on an SSD; [section 6.1](#61-durability-what-an-acknowledged-message-survives)),
parse it again into Drishti's document, update the cache and type-ahead, push to subscribers, acknowledge. There is
no batching and no parallelism across queues of one connector. To spread a heavy
feed, split it over several connectors (each with its own connection, channel and state store) by kind or by queue.

Costs that grow with the number of entities:

- **Deletes** of a known entity are cheap: marked removed in the type-ahead index and folded out every 4,096 removals
  (one pass over that kind's ids).
- **New ids** are folded into the index every 4,096 additions (a merge and sort of that kind's ids).
- **Evictions** (`state.when-full: evict-oldest`) remove many entities at once and rebuild the index once per check.

### 9.4 A million entities a day

A queue is a fine carrier for a million *changes* a day of a few thousand entities. It is a poor home for a book of a
million entities a day: each would be a message, the state store would grow with every new entity until the budget
evicts the oldest (or, with `warn`, the disk fills), there are no dated reads, and searches read members one by one. Load such a book into a dated store (Delta Lake,
PostgreSQL, Aerospike: see [DELTA_CONNECTOR.md](DELTA_CONNECTOR.md), [POSTGRES_CONNECTOR.md](POSTGRES_CONNECTOR.md),
[AEROSPIKE_CONNECTOR.md](AEROSPIKE_CONNECTOR.md)) and use the queue only for the day's live changes of the entities
that change.

## 10. History: RabbitMQ with a lake

The router orders candidates by the date asked ([CONNECTOR_GUIDE.md, section 1](CONNECTOR_GUIDE.md#how-a-request-picks-a-connector)):

| The user asks for | Order tried | Who answers |
|---|---|---|
| Live | `margin-mq` (live) → `collateral-store` (the route, dated) → others | RabbitMQ, if it has received the entity; else the lake's newest date |
| a picked date | `collateral-store` (dated first) → … → `margin-mq` | the lake, for that date |
| a date the lake does not hold | as above | `margin-mq`'s current document, with the banner *margin-mq is not a dated source: this shows its current data, not <date>* |

The counterparty-risk pack routes `margin-call` to `collateral-store` (Delta Lake). Adding `margin-mq` serving
`margin-call` needs no route change: Live reads go to the live connector first, picked dates to the lake. Each
evening's load into the lake carries the day's final state; the queue only has to carry the changes during the day,
and `state.reset-at` can clear it each morning.

## 11. Failure and recovery

### 11.1 Health texts

| Health | When |
|---|---|
| `DOWN: not started` | before the first connection attempt has finished (an unreachable host can hold this for the client's connection timeout) |
| `DOWN: <Exception>: <message> (retrying)` | the first connection, or declaring, binding or consuming at start, failed; retried after 1 s, doubling to 30 s (1, 2, 4, 8, 16, 30, 30 … s) |
| `UP` | connected and consuming every queue |
| `DOWN: connection lost (recovering)` | the connection is closed and the client's automatic recovery is reconnecting (shown whenever the connection reports not open) |
| `DOWN: consumer cancelled on <queue>` | the broker cancelled the consumer of that queue (for example, the queue was deleted) |
| `DOWN: <reason> (messages are not acknowledged and come again)` | the state store failed to keep a message (for example `DOWN: state store write failed: … (messages are not acknowledged and come again)`); shown over every other text but `DOWN: connection lost (recovering)` until a message is kept again ([section 6.2](#62-when-the-store-cannot-keep-a-message)) |
| `UP (state store over its budget: X of Y GB; the oldest entities are being evicted)` | connected, and the store is past `state.max-gb` with `state.when-full: evict-oldest`; clears once evictions bring it under |
| `UP (state store over its budget: X of Y GB; nothing is dropped: raise state.max-gb or add disk)` | connected, and the store is past `state.max-gb` with `state.when-full: warn` |

Examples as produced: `DOWN: ConnectException: Connection refused (retrying)`,
`DOWN: IllegalArgumentException: Wrong scheme in AMQP URI: http (retrying)`.

The start-up supervisor (a virtual thread named `drishti-rabbitmq-<source-name>`) runs only until the first
connection succeeds. From then on the RabbitMQ client's **automatic recovery** (`automaticRecoveryEnabled`,
`topologyRecoveryEnabled`) reconnects every `recovery-interval-ms` (2,000), redeclares the queues and bindings it
declared, and restarts the consumers; health returns to `UP` once the connection is open again. A dead connection is
noticed through the heartbeat (`heartbeat-seconds`, 20).

`DOWN: consumer cancelled on <queue>` is set by the cancel callback and is not cleared by a recovery: it stays until
the server restarts. Restart once the queue exists again.

### 11.2 While the broker is down

- Reads keep answering from the memory cache and the state store; views stop ticking.
- When only the connection is down, new messages wait in the queue; when the broker itself is down, producers cannot publish until it returns.
- Messages delivered but not yet acknowledged when the connection broke are redelivered after reconnection, in queue
  order; applying one again writes the same latest state.

### 11.3 Freshness

`lastUpdate` is the time of the last message received (any message, including rejected ones). With `stale-after`
set, Admin → Health shows the connector amber and the overall status `DEGRADED` when nothing arrived for that long,
and views of its entities carry a banner ([CONFIGURATION.md](../admin/CONFIGURATION.md#connector-settings-plugin-by-plugin)).

### 11.4 Cache figures

`GET /api/v1/admin/health` and Admin → Caches show, for the connector (illustrative values):

```json
{"entities": 1840, "memoryEntries": 1840, "stateMb": 6.4, "durability": "sync", "budgetMb": 20480, "evicted": 0,
 "received": 22571, "rejected": 3}
```

| Figure | Meaning |
|---|---|
| `entities` | ids known (received and not deleted since start, plus those found in the store at start) |
| `memoryEntries` | documents in the memory cache |
| `stateMb` | the store's size in MB: RocksDB table files plus the memtables not yet flushed; compared with the budget |
| `durability` | `sync`, `wal` or `none` (`state.durability`) |
| `budgetMb` | `state.max-gb` in MB |
| `evicted` | entities removed in this run to keep within the budget (`state.when-full: evict-oldest`) |
| `received` | messages received since start |
| `rejected` | messages skipped and acknowledged (not JSON, no kind, no id, or not a document); messages the store failed to keep are not counted: they are requeued |

## 12. Security

- **Credentials** are in the URI: `amqp://<user>:<password>@<host>:<port>/<vhost>`. URL-encode special characters in
  the password and the vhost (`%2f` is the default vhost `/`). Keep the whole URI in the environment
  (`uri: ${RABBIT_URI}`); health and `/api/v1/sources` never show settings.
- **TLS**: `amqps://host:5671/vhost`. The client (amqp-client 5.36) then uses the JVM's default `SSLContext` with
  **hostname verification on**: the server certificate must chain to the JVM's trust store and match the host name.
  To trust a private CA, or to present a client certificate, set the JVM's standard properties when starting the
  server: `-Djavax.net.ssl.trustStore=… -Djavax.net.ssl.trustStorePassword=…`, and `-Djavax.net.ssl.keyStore=…
  -Djavax.net.ssl.keyStorePassword=…`. The connector has no settings of its own for certificates, and no setting for
  SASL EXTERNAL (certificate-based login); the login is the URI's user and password (PLAIN).
- **Permissions** the user needs on the vhost: *configure* on the queues (only with `declare: true`), *write* on the
  queues and *read* on the exchange (to bind, with `bind.<queue>`), and *read* on the queues (to consume). With
  `declare: false`, read on the queues is enough.
- **The state store** holds every document in plain (LZ4-compressed) form on local disk: protect `state.root` as you
  would the source data.

## 13. Diagnosing

`GET /api/v1/admin/health` (Admin → Health) shows the connector's `health`, `reads` and `cache`; the RabbitMQ
management UI shows the connection `drishti-<source-name>`, its channel, and a consumer `drishti-<source-name>-<queue>`
on each queue.

| Symptom | Likely cause | What to do |
|---|---|---|
| `DOWN: ConnectException: Connection refused (retrying)` | the broker is not listening on that host and port | start it, or fix `uri`; the connector keeps retrying by itself |
| `DOWN: not started` for a minute | the host is unreachable (packets dropped) and the client waits for its connection timeout | check routing and firewalls to port 5672 (5671 for TLS) |
| `DOWN: AuthenticationFailureException: ACCESS_REFUSED … (retrying)` | wrong user or password | fix the URI's credentials (URL-encoded) |
| `DOWN: IOException: … (retrying)` and the broker log says `NOT_FOUND - no exchange 'collateral'` | `bind.<queue>` names an exchange that does not exist | create the exchange, or fix `bind.<queue>` |
| `DOWN: IOException: … (retrying)` and the broker log says `PRECONDITION_FAILED - inequivalent arg` | the queue exists with other arguments (quorum, TTL, DLX) | `declare: false` |
| `DOWN: IOException: … (retrying)` and the broker log says `access to vhost … refused` or `ACCESS_REFUSED` | the user lacks permissions on the vhost | grant configure/write/read as in [section 12](#12-security) |
| `DOWN: IllegalArgumentException: Wrong scheme in AMQP URI: … (retrying)` | the URI does not start with `amqp://` or `amqps://` | fix `uri` |
| `DOWN: connection lost (recovering)` that does not clear | the broker is down, or a network path is broken | bring the broker back; recovery is automatic |
| `DOWN: consumer cancelled on <queue>` | the queue was deleted | recreate it (or let `declare: true` do it), then restart the server |
| `UP`, nothing arrives | the queue is not bound to the exchange the producer uses, or another consumer takes the messages | `bind.<queue>`, or bind it on the broker; give each server its own queue |
| `rejected` grows | bodies that are not JSON, or no id: no `id` header, no `message_id`, and the body lacks `id-field.<queue>`; or envelopes without `kind` on a queue with no kind | check the queue's `kind.<queue>` and `id-field.<queue>` against a real message |
| a deleted entity still offered by type-ahead | a delete arrived for an id spelled differently; or the state was cleared by `state.reset-at` (type-ahead keeps ids until restart); or it is in the command line's "recent" list (cleared only when an open view saw the delete) | send the delete with the exact id; restart to rebuild type-ahead from the store |
| an old entity reads "not held" and is gone from type-ahead; `evicted` grows; WARN `state store over its budget … evicted …` | `state.max-gb` reached: `evict-oldest` removed the entities written longest ago | raise `state.max-gb` (and the disk), or `state.when-full: warn`; republish the entity |
| `UP (state store over its budget: … nothing is dropped …)` | `state.when-full: warn` and the store is past `state.max-gb` | raise `state.max-gb` or add disk before it fills; or switch to `evict-oldest` |
| `DOWN: … (messages are not acknowledged and come again)`; the queue's unacknowledged and redelivered counts rise | the state store cannot write: the disk is full, or an I/O error | free or grow the disk (`df -h` on `state.root`); the connector resumes by itself. On a quorum queue, mind its delivery limit ([section 6.2](#62-when-the-store-cannot-keep-a-message)) |
| messages arrive slowly, the disk is busy, the queue's ready count grows | `state.durability: sync` waits for a disk sync per message | a faster disk (an SSD with power-loss protection); `wal` if losing the last moments before a power loss is acceptable; or split the feed over several connectors |
| `failedToStart`: `No enum constant … Durability.…` | `state.durability` is not `sync`, `wal` or `none` | fix the value |
| an old entity will not go away | the store keeps it until a delete arrives | send a delete (`deleted: true`), or `state.reset-at`, or clear the folder with the server stopped |
| the view does not tick, but reads work | the view was opened on a picked date, or another live connector holds the entity first | open it Live; check `provenance.source` of the view |
| the server takes long to start | the state store holds many entities: it is iterated once at start | fewer entities: a lower `state.max-gb` (with `evict-oldest`), or clear it daily if the state is intraday |

## 14. Settings

On a `rabbitmq` connector (`drishti.sources.connectors.<name>.settings`, or `drishti.sources.plugins.rabbitmq.settings`).

Read by `RabbitMqSourcePlugin`:

| Setting | Default | Meaning |
|---|---|---|
| `uri` | `amqp://guest:guest@localhost:5672/%2f` | AMQP URI with user, password, host, port and vhost; `amqps://` for TLS (port 5671 by default) |
| `queues` | empty | comma list of queues to consume; empty means the connector connects and consumes nothing |
| `declare` | `true` | declare each queue durable (no arguments) and bind it; `false` uses existing queues and ignores `bind.*` |
| `bind.<queue>` | none | `exchange:routing.key`; split at the first `:`; only with `declare: true` |
| `prefetch` | `100` | unacknowledged messages in flight per consumer (`basicQos`) |
| `recovery-interval-ms` | `2000` | the automatic recovery's wait between reconnection attempts |
| `heartbeat-seconds` | `20` | the requested AMQP heartbeat |

Read by `MessageStateSource` (shared with `activemq`):

| Setting | Default | Meaning |
|---|---|---|
| `kind` | none | every queue carries documents of this kind |
| `kind.<queue>` | none | that queue's kind; a queue with no kind (and no `kind`) carries envelopes `{kind, id, doc}` |
| `id-field` | `id` | the body's id field, for every queue with a kind |
| `id-field.<queue>` | none | the id field on that queue |
| `cache-mb` | `128` | memory for recent documents |
| `state.root` | `./data/state` | the parent of the default state folder |
| `state.dir` | `<state.root>/<source-name>` | the RocksDB state store's folder |
| `state.durability` | `sync` | `sync` (write-ahead log synced on every write: no acknowledged message lost on a crash or a power loss), `wal` (not synced: survives a crash of the process, a power loss can lose the last moments), `none` (no log: a crash can lose up to the 32 MB memtable) ([section 6.1](#61-durability-what-an-acknowledged-message-survives)) |
| `state.max-gb` | `10` | this connector's disk budget (decimal values accepted); what happens past it is `state.when-full` |
| `state.when-full` | `evict-oldest` | `evict-oldest`: past the budget, remove the entities written longest ago until under 90% of it, then compact (logged, counted in `evicted`); `warn`: remove nothing, say so in health ([section 6.3](#63-the-disk-budget-per-connector)) |
| `state.check-seconds` | `60` | how often the store's size is checked against the budget |
| `state.reset-at` | `never` | a daily clearing time, `HH:mm` (`06:00`) |
| `state.zone` | `America/New_York` | the zone of `state.reset-at` |
| `source-name` | the connector's name (as itself: `rabbitmq`) | provenance source, type-ahead subtitle, and the default state folder's name |

Read by the engine for every connector:

| Setting | Default | Meaning |
|---|---|---|
| `stale-after` | none | a duration (`15m`, `2h`); nothing received for longer marks the connector stale |

Connector keys (beside `settings`): `plugin: rabbitmq`, `enabled` (`true`), `kinds` (every kind the connector has
seen or been configured with).

## 15. Checklist for production

1. Give each Drishti server its **own durable queue**, bound to the producers' exchange; never share a queue between
   servers.
2. Ask producers to publish **persistent** messages (`delivery_mode` 2) with JSON bodies and the id in an `id` header
   or a known body field; agree how deletes are sent (`deleted: true` header).
3. Decide who owns the queues: `declare: true` with `bind.<queue>`, or `declare: false` for queues with arguments
   (quorum, TTL, dead-lettering) created by the platform team.
4. Set `kinds:` on the connector, and `kind.<queue>` / `id-field.<queue>` for every queue of plain documents.
5. Put `state.root` on **local disk** (an SSD: `state.durability: sync` waits for a disk sync per message), back it
   up, and size `state.max-gb` for the entities held, with a margin, and the disk for about twice that per connector
   ([section 6.4](#64-sizing-the-disk)); choose `evict-oldest` (the default) or `warn` for what happens past it; use
   `state.reset-at` for intraday state.
6. Keep `state.durability: sync` unless the feed is faster than the disk can sync and producers resend state; give the
   process enough memory (`cache-mb` plus the id structures) to avoid an out-of-memory kill.
7. Keep the URI in the environment; use `amqps://` with a trust store that holds the broker's CA.
8. Set `stale-after` to the longest quiet period that is normal for the feed.
9. Keep history in a dated store for the same kinds ([section 10](#10-history-rabbitmq-with-a-lake)).
10. Check Admin → Health: `UP`, `received` growing, `rejected` flat, `stateMb` well under `budgetMb`, `evicted` flat;
    open an entity Live and watch it tick. Alert on a health ending `(messages are not acknowledged and come again)`.
