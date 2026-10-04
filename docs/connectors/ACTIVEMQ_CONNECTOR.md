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
# The ActiveMQ connector: live entities from queues and topics, kept on local disk

The `activemq` connector reads JSON documents from ActiveMQ Classic queues and topics (OpenWire) and serves each
entity's latest document, live. A queue delivers a message once and keeps nothing afterwards, so the connector keeps
the latest document of every entity itself, in a RocksDB **state store** on local disk that survives restarts of
Drishti and, by default, crashes and power loss: a message is acknowledged only once the store has kept it. This
document explains how messages become entities, what is acknowledged when, the state store, its durability and disk
budget, every read path and what it costs, the limits at scale, what happens when the broker goes away, and every
setting.

The plugin is `plugins/drishti-plugin-activemq` (`ActiveMqSourcePlugin`, about 200 lines); everything that is not
ActiveMQ-specific (message shapes, the state store, the memory cache, search, live push) is the shared base
`MessageStateSource` in `drishti-messaging`, which the RabbitMQ connector uses too
([RABBITMQ_CONNECTOR.md](RABBITMQ_CONNECTOR.md)). For a Kafka topic, which keeps its own history, see
[KAFKA_CONNECTOR.md](KAFKA_CONNECTOR.md). A first walk-through is in
[the walk-through at the end of this document](#walk-through-step-by-step); the settings summary is in
[CONFIGURATION.md](../admin/CONFIGURATION.md#activemq-and-rabbitmq--message-queues).

## Contents

1. [When to use it](#1-when-to-use-it)
2. [How it works, in one picture](#2-how-it-works-in-one-picture)
3. [Queues, topics and durable subscriptions](#3-queues-topics-and-durable-subscriptions)
4. [Messages: what the connector accepts](#4-messages-what-the-connector-accepts)
5. [Configuration](#5-configuration)
6. [The state store](#6-the-state-store)
7. [Durability and disk budget](#7-durability-and-disk-budget)
8. [Read paths and what each costs](#8-read-paths-and-what-each-costs)
9. [Live push](#9-live-push)
10. [Acknowledgement, ordering and delivery](#10-acknowledgement-ordering-and-delivery)
11. [Scale and limits](#11-scale-and-limits)
12. [History: combining with a lake](#12-history-combining-with-a-lake)
13. [Failure and recovery](#13-failure-and-recovery)
14. [Security](#14-security)
15. [Diagnosing](#15-diagnosing)
16. [Settings](#16-settings)
17. [Checklist for production](#17-checklist-for-production)

---

## 1. When to use it

| Use it for | Prefer another connector for |
|---|---|
| a system that already publishes each change of an entity to an ActiveMQ Classic queue or topic | history by business date (Delta Lake, PostgreSQL, Aerospike, files): this connector is undated |
| live views of a modest set of entities (orders, limits, quotes, alerts) that tick on every message | a topic that is the system of record and can be replayed (Kafka: [KAFKA_CONNECTOR.md](KAFKA_CONNECTOR.md)) |
| state that should start empty each day (`state.reset-at`) | reverse lookups (*Linked entities*, impact with F8): not supported |
| a desk that cannot run Kafka but has a broker | searches over promoted fields of millions of entities a day (it has no columns) |
| | ActiveMQ Artemis with AMQP or a broker other than Classic: the client speaks OpenWire only |

The state store is the **only copy** of what the queue delivered. Losing the folder loses the entities until each one
is sent again. Treat it as data, not as a cache ([section 6](#6-the-state-store)).

## 2. How it works, in one picture

```
 producer ──► queue://limits ─┐                                    ┌─► memory cache (cache-mb, 128)
                               ├─► one session, CLIENT_ACKNOWLEDGE ─┤
 producer ──► topic://quotes ─┘   (durable subscriber for topics)   ├─► state store: RocksDB, ./data/state/<connector>
                                                                    ├─► type-ahead index (every id received)
                                                                    └─► open live views of that entity
                                    message.acknowledge()  ◄── after the steps above
```

- One virtual thread per connector (`drishti-activemq-<source-name>`) runs a **supervisor** that opens a connection,
  a session in `CLIENT_ACKNOWLEDGE` mode and one consumer per destination, then receives in a loop.
- Each message is turned into an entity (or a delete), written to the state store (synced to disk by default) and the
  memory cache, added to the type-ahead index, pushed to the views open on that entity, and only then acknowledged.
  A message the store cannot keep is not acknowledged: the session is recovered a second later and the broker
  delivers it again ([section 10](#10-acknowledgement-ordering-and-delivery)).
- Reads never touch the broker: they come from the memory cache, else the state store.

## 3. Queues, topics and durable subscriptions

`destinations` is a comma list. Each entry is `queue:<name>`, `topic:<name>`, or a bare `<name>`, which is a queue.

| | Queue | Topic |
|---|---|---|
| Consumer | `session.createConsumer(queue)` | `session.createDurableSubscriber(topic, "drishti-<source-name>-<topic>")` |
| While Drishti is down | messages wait in the queue | messages wait in the durable subscription |
| Two Drishti servers | **compete**: each message goes to one of them, so each holds only part of the entities | each needs its own `client-id`; each gets its own durable subscription and every message |
| Removing it from `destinations` | the queue is no longer read | the durable subscription **stays on the broker** and keeps collecting messages until you remove it (web console → Subscribers, or `activemq` admin tools) |

**The client id.** `client-id` defaults to `drishti-<source-name>`, and `source-name` of a named connector is the
connector's name, so the connector `limits-mq` connects as `drishti-limits-mq` and its durable subscription to
`limit-breaches` is named `drishti-limits-mq-limit-breaches`. ActiveMQ refuses a second connection with a client id
already connected, so two servers (or a test server pointed at the production broker) must each set their own
`client-id`; otherwise the second one stays `DOWN: InvalidClientIDException: … (reconnecting)` while the first is
connected.

The client id names the durable subscriptions, so **changing it creates new subscriptions**: the new ones receive only
messages sent after they are made, and the old ones keep filling on the broker until removed. Choose it once per
server and keep it.

## 4. Messages: what the connector accepts

### 4.1 The message

A `TextMessage`, or a `BytesMessage` whose bytes are read as UTF-8. Any other message type (`MapMessage`,
`ObjectMessage`) has no body for the connector and is treated as an empty body. Two optional **string properties**
are read:

| Property | Meaning |
|---|---|
| `id` | the entity id; when absent or blank, the id comes from the body |
| `deleted` | `true` (any case) deletes the entity |

Other properties and headers (`JMSType`, `JMSCorrelationID`, priority, expiry) are ignored.

### 4.2 A destination with a kind

When the destination has a kind (`kind.<destination>`, or `kind` for every destination), the body is the document
itself. The id is the `id` property, else the body's field `id-field.<destination>`, else `id-field` (default `id`).
`<destination>` is the name without `queue:` or `topic:`. On `limits` with `kind.limits: credit-limit` and
`id-field.limits: limitId`:

```text
destination: queue://limits
property id: LIM-ALDERSHOT                       (optional: else the body's limitId)
body:        {"limitId": "LIM-ALDERSHOT", "counterparty": "CP-ALDERSHOT", "currency": "USD",
              "limit": 338000000.0, "used": 241900000, "utilisation": 0.7157, "status": "Within limit",
              "asOf": "2026-09-30T16:05:12Z"}
```

The id field is read at the top level only (`doc.path(field)`): `counterparty.id` is a field named
`counterparty.id`, not a nested path. A numeric id field is read as its text (`12345`).

### 4.3 A destination without a kind: the envelope

Any other destination carries an envelope:

```json
{"kind": "netting-set", "id": "NS-MERIDIAN-RE-ISDA",
 "doc": {"nettingSetId": "NS-MERIDIAN-RE-ISDA", "counterparty": "CP-MERIDIAN-RE", "agreement": "ISDA-2002",
         "netMtm": 18450210.55, "trades": 1282, "csa": true}}
```

The `id` property stands in for a missing `"id"`. One destination can carry several kinds this way, and the kinds the
connector serves grow as new ones arrive.

### 4.4 Deletes

| Message | Result |
|---|---|
| any message with property `deleted=true` whose entity can be named (kind from the destination or envelope, id from the property or body) | the entity is deleted |
| an envelope with `"doc": null`, **or with no `"doc"` at all** | the envelope's entity is deleted |
| an empty (or blank) body with an `id` property, on a destination with a kind | that entity is deleted |
| an empty body on a destination without a kind | rejected |

A delete removes the entity from the state store, the memory cache and the type-ahead index, and is **pushed to open
views**: a view already showing the entity keeps its last document, greyed, under a banner saying when it was deleted,
and stops updating ([LIVE.md](../architecture/LIVE.md#deleted-entities)). A later message for the entity repaints it.
Reopened, the read answers "not held" and the next connector is asked.

A delete with property `deleted=true` and a body must still have a body that is JSON (or blank); a body that is not
JSON is rejected before the delete flag is looked at.

### 4.5 Rejected messages

A message is counted in `rejected` (Admin → Caches, the connector's figures) and **acknowledged**, so it leaves the
queue and is gone:

- a body that is not JSON;
- no kind (no `kind.<destination>`, no `kind`, and no `"kind"` in the envelope);
- no id (no `id` property, no id field in the body, no `"id"` in the envelope), or a blank one;
- an empty body on a destination without a kind.

The connector does not dead-letter: if a producer may send bad messages you want kept, route a copy to a separate
queue on the broker side. (Acknowledging an unreadable message is deliberate: it can never be read, so returning it
would bring it back forever.) A readable message that the state store fails to keep is a different case: it is
**not** acknowledged and comes again ([section 10](#10-acknowledgement-ordering-and-delivery)).

### 4.6 What is stored

The document is stored as compact JSON (re-serialised by Jackson: whitespace removed, field order kept), under the key
`<kind>/<id>`. Each stored document becomes an `EntityDocument` with `Provenance(source = source-name, generation,
fetchedAt, live = true)`, no business date. `generation` is a counter that rises by one for every document the
connector builds in this run (received, or loaded from the state store); it starts again at 1 after a restart.
`fetchedAt` is when the document was built: the time it was received, or the time it was first read back from disk
after a restart.

## 5. Configuration

The plugin is normally used through named connectors. It also runs as itself under `drishti.sources.plugins.activemq`,
which the shipped `application.yaml` leaves off (`enabled: ${DRISHTI_ACTIVEMQ_ENABLED:false}`).

### 5.1 Pack form

In `packs/<pack>/pack.yaml`, so the connector comes with the pack. Dotted keys (`kind.limits`) are written flat:

```yaml
connectors:
  limits-mq:
    plugin: activemq
    enabled: ${DRISHTI_LIMITS_MQ:false}                       # off until switched on
    kinds: [credit-limit, netting-set]
    settings:
      stale-after: 15m                                        # warn when nothing arrives for 15 minutes
      broker-url: ${AMQ_URL:failover:(tcp://localhost:61616)}
      user: ${AMQ_USER:}
      password: ${AMQ_PASSWORD:}
      destinations: queue:limits,topic:limit-breaches,queue:risk.entities
      kind.limits: credit-limit
      id-field.limits: limitId
      kind.limit-breaches: credit-limit
      id-field.limit-breaches: limitId
      # risk.entities has no kind: its messages are envelopes {"kind", "id", "doc"}
      client-id: ${AMQ_CLIENT_ID:drishti-limits-mq}
      state.max-gb: 20                                        # this connector's disk budget
      state.when-full: evict-oldest                           # the default; warn keeps everything
routes:
  credit-limit: credit-store                                  # history from the lake; see section 12
```

### 5.2 Site form

The same under `drishti.sources.connectors` in `application.local.yaml`. In Spring files a key with characters other
than letters, digits, `-` and `.` must be bracketed and quoted (`"[kind.desk_limits]": credit-limit`):

```yaml
drishti:
  sources:
    connectors:
      limits-mq:
        plugin: activemq
        kinds: [credit-limit]
        settings:
          broker-url: "failover:(ssl://mq1.bank.example:61617,ssl://mq2.bank.example:61617)?initialReconnectDelay=1000&maxReconnectDelay=30000"
          user: ${AMQ_USER}
          password: ${AMQ_PASSWORD}
          destinations: queue:limits
          kind.limits: credit-limit
          id-field.limits: limitId
          client-id: drishti-prod-1-limits-mq
          cache-mb: 256
          state.root: /var/lib/drishti/state     # the store goes to /var/lib/drishti/state/limits-mq
          state.max-gb: 20
          state.durability: sync                 # the default: no acknowledged message is lost, even on power loss
```

A site entry overrides a pack's connector key by key, so a site can change only `broker-url` and `client-id` and keep
the pack's kinds and mappings ([CONNECTOR_DEVELOPER_GUIDE.md, Combining connectors](CONNECTOR_DEVELOPER_GUIDE.md#combining-connectors)).

### 5.3 Which kinds it serves

With a `kinds:` list, exactly those. Without one, the connector's manifest is built each time the router asks: every
`kind.<destination>` value, `kind`, and every kind received so far (including kinds found in the state store at
start). Before any of those exist, the set is empty, which means **every kind**: the connector is then a candidate for
every read and answers "not held" quickly from memory. Give it `kinds:` to keep it out of reads it cannot answer.

## 6. The state store

### 6.1 Where it is

`state.dir`, default `<state.root>/<source-name>` with `state.root` `./data/state` (relative to the folder the server
starts in). Inside it, RocksDB lives in a generation folder `gen-<millis>-<n>`. At start the newest generation is
reopened and older leftovers are deleted. Every connector has its own store, its own write-ahead log and its own
budget.

### 6.2 How it is written

| Property | Value (from `DiskCache`) | Consequence |
|---|---|---|
| compression | LZ4 | JSON documents typically shrink several times |
| compaction | level compaction | only the **latest value of each entity** is kept: overwritten and deleted values are compacted away |
| write buffer | 32 MB | up to 32 MB of recent writes live in memory before a flush to a table file |
| write-ahead log | per `state.durability`: `sync` (default), `wal` or `none`; capped at 64 MB | what a crash or a power loss can lose ([section 7](#7-durability-and-disk-budget)) |
| a failed write | fails loudly | the message is not acknowledged and comes again ([section 10](#10-acknowledgement-ordering-and-delivery)) |
| on a clean stop | the store is closed and its files kept for the next run | |

**The store follows the entities, not the messages.** An update of an entity writes a new value; RocksDB's
compaction later merges it with the older one and keeps only the newest, and a delete removes the entity's value the
same way. So the store's size follows **the number of live entities times their compressed size**, not the number of
messages received: a hundred updates of one limit cost one document's space once compacted. Between compactions,
overwritten values still take some room (RocksDB's level compaction typically keeps this to around a tenth of the
store). The write-ahead log never grows past 64 MB: past that RocksDB flushes the write buffers and drops the old
log.

The current size is `stateMb` in the connector's cache figures: the table files plus the write buffers still in
memory (which the write-ahead log mirrors on disk). The log itself, RocksDB's small `LOG` and `MANIFEST` files, and
the room a compaction needs while it runs are not counted: leave headroom on the disk
([section 7.4](#74-sizing-the-disk)).

### 6.3 What survives a restart

| Survives | Does not |
|---|---|
| every stored document (the state store) | the memory cache (refilled on first reads) |
| the kinds and ids (rebuilt from the store at start: every key is read) | generation numbers (start again at 1) |
| durable topic subscriptions and queued messages on the broker | with `state.durability: none` only, writes still in the 32 MB write buffer if the process **crashed** ([section 7](#7-durability-and-disk-budget)) |

With the default `sync`, a stop of any kind (clean, `kill -9`, an out-of-memory kill, a power loss) loses no message
that was acknowledged: RocksDB replays its write-ahead log when the store is reopened.

At start the connector iterates the whole store once, in key order, to rebuild the set of known entities, the kinds
and the type-ahead index, before it connects to the broker. Start time therefore grows with the number of entities
held.

### 6.4 Clearing it

| Want | Do |
|---|---|
| state that starts empty every day | `state.reset-at: "06:00"` (`HH:mm`) in `state.zone` (`America/New_York`); a fresh empty generation takes over at that time each day, recomputed daily so daylight saving is honoured |
| clear it once | stop the server, delete the folder, start; then have the producers send every entity again |
| clear memory only | Admin → Caches → Purge: drops the memory cache; the state store is never purged |
| keep it within a size | `state.max-gb` with `state.when-full: evict-oldest` (the default) removes the entities written longest ago ([section 7.3](#73-the-disk-budget-per-connector)) |

After a daily `state.reset-at` clearing, the type-ahead index and the set of known entities are **not** cleared until
the next restart: type-ahead still offers yesterday's ids, and a recently read entity may still be served from the
memory cache, while others answer "not held". If you depend on a daily reset, plan a restart after it, or have the
producers send the day's state promptly after the reset time.

## 7. Durability and disk budget

### 7.1 Durability: what an acknowledged message survives

`state.durability` chooses how each write reaches the disk. The message is acknowledged only after the write
returns, so the level decides what an **acknowledged** message survives:

| `state.durability` | How a write is kept | Survives a crash of the process | Survives a power loss or a kernel crash | Cost |
|---|---|---|---|---|
| `sync` (default) | written to the write-ahead log, which is synced to the disk (`fsync`) before the write returns | yes | yes: no acknowledged message is lost | one disk sync per message: the connector's throughput is bounded by the disk's sync latency, typically thousands of messages a second on an SSD |
| `wal` | written to the write-ahead log, not synced: the operating system writes it out shortly after | yes | no: the last moments of messages (what the operating system had not yet written) can be lost | a write to the page cache: close to `none` |
| `none` | no write-ahead log: the write lands only in the 32 MB write buffer until it is flushed to a table file | no: up to the 32 MB write buffer is lost, although those messages were acknowledged | no | the fastest |

`none` is the behaviour of earlier versions. Any other value stops the connector from starting (it is listed under
`failedToStart`).

**The cost of `sync`, in more detail.** The connector applies one message at a time, and with `sync` each one waits
for its own disk sync. RocksDB's group commit folds writes that arrive together into one sync, which helps a store
written by several threads but not one connector, whose writes come one after another; separate connectors have
separate stores and logs and sync independently. So a connector's rate is about one message per sync: an SSD (best,
one with power-loss protection, which acknowledges a sync from its own cache) gives thousands a second, a desktop SSD
without it hundreds, a spinning disk tens. These are general figures for the hardware, not measurements of this
connector. When the rate matters more than the last moments before a power loss, use `wal`; producers that resend
the full state of every entity periodically, or a dated store behind the connector, cover the gap.

### 7.2 When the store cannot keep a message

A failed write (disk full, an I/O error, a store already closed) is not swallowed: the message is **not
acknowledged**, the connector waits a second and recovers the session, and the broker delivers it again. Meanwhile
health is `DOWN: <reason> (messages are not acknowledged and come again)`, for example `DOWN: state store write failed:
IO error: No space left on device … (messages are not acknowledged and come again)`, until a message is kept again.
Free the disk (or raise the budget, [section 7.3](#73-the-disk-budget-per-connector)) and the connector carries on
by itself; nothing is lost while the broker holds the messages.

The connector redelivers without limit (`max-redeliveries: -1`, the default), so a long disk outage loses nothing: the
ActiveMQ client's own default would give up after six redeliveries and send the message to the broker's dead-letter
queue (`ActiveMQ.DLQ`). Set `max-redeliveries` to a number when a dead-letter queue is wanted instead.

Unreadable messages are a different case: they are acknowledged and counted in `rejected`
([section 4.5](#45-rejected-messages)).

### 7.3 The disk budget, per connector

`state.max-gb` (10) is the budget of **this connector's** store (each connector has its own `state.dir`, so budgets
add up across connectors on one disk). Every `state.check-seconds` (60) the store's size (`stateMb`) is compared
with the budget, and `state.when-full` decides what happens past it:

| `state.when-full` | Past the budget |
|---|---|
| `evict-oldest` (default) | the entities **written longest ago** are removed until the store is estimated to be under 90% of the budget (from the average size of an entity), then the store is compacted so the disk gives the space back. If it is still over, the next check removes more. Each eviction is logged at WARN (`limits-mq: state store over its budget (20500 MB of 20480 MB): evicted the 41250 entities written longest ago (now 18300 MB)`) and counted in `evicted`. Health reads `UP (state store over its budget: X of Y GB; the oldest entities are being evicted)` until the store is back under |
| `warn` | nothing is removed. Health reads `UP (state store over its budget: X of Y GB; nothing is dropped: raise state.max-gb or add disk)` while the store is over. Size it before the disk fills: a full disk makes writes fail ([section 7.2](#72-when-the-store-cannot-keep-a-message)) |

"Written longest ago" is the time of the entity's last message in this run. Entities found in the store at start
count as older than anything this run writes, and among themselves go in key order (`<kind>/<id>`), since the store
does not record when they were written. An evicted entity is gone from the store, the memory cache and type-ahead,
as if a delete had arrived, except that open views are not told (the entity was not deleted at its source: an open
view keeps its last document); its next message brings it back.

The health text gives sizes in the same gigabytes as `state.max-gb` (1,024³ bytes), so a budget of `10` shows as
`10.0 GB`. `budgetMb` in the cache figures is the budget in MB (`10240` for `10`). Any `state.when-full` other than
`evict-oldest` or `warn` stops the connector at start (`failedToStart`), so a typo never evicts.

### 7.4 Sizing the disk

- **The store**: the number of entities held times their compressed size (LZ4; JSON typically shrinks several times),
  plus some room for values not yet compacted. Set `state.max-gb` above that with a margin, so `evict-oldest` only
  acts on entities that are genuinely stale.
- **Beyond `stateMb`**: up to 64 MB of write-ahead log (none with `durability: none`), and room for a compaction to
  write its new files before it deletes the old ones; the full compaction after an eviction can briefly need up to
  the store's size again. Plan about twice `state.max-gb` of disk per connector, and add up every connector on the
  same disk.
- **Back it up**: it is the only copy of what the queues delivered (stop the server, copy the folder, start; or rely
  on the producers to resend).

## 8. Read paths and what each costs

The connector declares `SourceCapabilities(live = true, reverseLookup = false, search = true, dated = false)`.

| Read | How | Cost |
|---|---|---|
| one entity (`LIM LIM-ALDERSHOT <GO>`, `GET /api/v1/views/{kind}/{id}`) | the memory cache; on a miss, one RocksDB `get` of `<kind>/<id>` and a JSON parse, then cached | from memory: no I/O; from disk: one key lookup (LZ4 block read) and a parse |
| type-ahead | the in-memory `HitIndex` of every id received: id prefix first (binary search over sorted ids), then substring | memory only; subtitle `<kind> · <source-name>` |
| a picked business date | not dated: ignores the date and returns the current document; dated connectors serving the kind are asked first | the console shows *No data held for <date>: the current data of …, a source that keeps no dates* |
| *known at* | not supported | — |
| structured search (`LIM where utilisation > 0.9`) and pick lists | no columns: the engine lists the kind's ids from the type-ahead index and reads documents, at most `drishti.search.max-scan` (20,000) within `budget` (3 s); beyond that the result says `partial: true` ([CONFIGURATION.md](../admin/CONFIGURATION.md#drishtisearch--structured-search)) | one read per entity, mostly from memory |
| derived kinds, impact (F8) over a kind served here | through the routing, document by document, as for searches | as above |
| reverse lookups (*Linked entities*) | not supported: another connector must provide them | — |
| live updates | pushed on every message ([section 9](#9-live-push)) | one call per open view of that entity |

Nothing is read from the broker on a view: a view of an entity no message has delivered answers "not held".

## 9. Live push

`subscribe(ref, listener)` registers the view's listener for that entity (whether or not it is held yet). Each stored
message for the entity calls every listener, on the consumer thread, with the new document; an exception in one
listener is caught so the others still receive it. The router subscribes a live view to the first live connector that
holds the entity, so a view that read its document from this connector ticks from it.

| Event | Open view |
|---|---|
| a new document for the entity | repainted with it |
| a delete | told: keeps its last document, greyed, under a banner "deleted at <time>"; stops updating |
| the broker is down | keeps the last document, stops ticking; health is `DOWN` |
| a picked date | static: no subscription |

## 10. Acknowledgement, ordering and delivery

**Acknowledgement.** The session uses `CLIENT_ACKNOWLEDGE`. `message.acknowledge()` is called only after the state
store has kept the message (written with `state.durability`, `sync` by default: synced to disk), the memory cache, the
index and the listeners have it. A crash before that point leaves the message unacknowledged, and the broker delivers
it again on the next connection (with `JMSRedelivered` set), which is harmless: storing the same document twice gives
the same state.

**What an acknowledgement promises.** With `sync`, an acknowledged message survives anything short of losing the
disk: a clean stop, `kill -9`, an out-of-memory kill, a power loss. With `wal` it survives a crash of the process but
not a power loss; with `none` a crash can lose up to the 32 MB write buffer of acknowledged messages
([section 7.1](#71-durability-what-an-acknowledged-message-survives)).

**When the store cannot keep it.** A write that fails (disk full, an I/O error) is **not acknowledged**: the connector
waits a second and calls `session.recover()`, so the broker delivers the message (and any others not yet
acknowledged) again, in order. Health is `DOWN: <reason> (messages are not acknowledged and come again)` until a
message is kept again. The client's redelivery policy (by default six redeliveries, then the broker's dead-letter
queue) applies; see [section 7.2](#72-when-the-store-cannot-keep-a-message).

**Unreadable messages** (not JSON, not a document, no kind or id) are acknowledged and counted in `rejected`
([section 4.5](#45-rejected-messages)): they can never be read, and returning them would bring them back forever.

**Ordering.** One session, one thread: messages are applied one at a time. Within one queue or topic they are applied
in the order the broker delivers them (for a queue with one consumer, the order they were sent, subject to the
broker's priorities and redeliveries). Across destinations the connector takes at most one message from each
destination in turn, so the relative order of messages on different destinations is not kept. The last message
applied for an entity wins: if two destinations carry the same entity, send its updates on one of them.

**Several Drishti servers on one queue** compete for its messages (section 3). For several servers, use a topic (each
server with its own `client-id`), or a queue per server fed by the broker (virtual destinations, composite queues).

## 11. Scale and limits

### 11.1 Throughput

- **One message at a time per connector.** For each message: a JSON parse, a re-serialisation, a RocksDB put, a second
  parse into Drishti's document, the listeners, an acknowledgement. With the default `state.durability: sync` the put
  waits for a disk sync, which usually dominates: about one message per sync, typically thousands a second on an SSD
  ([section 7.1](#71-durability-what-an-acknowledged-message-survives)); `wal` or `none` remove that wait. No figures
  have been measured for this connector.
- **Several destinations on one connector slow each other.** With more than one destination the loop calls
  `receive(50 ms)` on each in turn, so every idle destination costs up to 50 ms per round. With one busy and one idle
  destination the busy one is read at about **one message per 50 ms (20 a second)**. With a single destination the
  loop calls `receive(500 ms)` and is not slowed. For a high-volume destination, give it **its own connector**.
- The broker prefetches messages to the consumer (ActiveMQ's own defaults: 1,000 for a queue consumer, 100 for a
  durable topic subscriber); change it with `jms.prefetchPolicy.*` options on `broker-url` if you need to.

### 11.2 Memory in the Drishti server

| Held | Per entity | Bounded by |
|---|---|---|
| recent documents | estimated by the connector as twice the stored JSON bytes plus 64 | `cache-mb` (128 MB) |
| the set of known entities and the type-ahead index | an entity reference and a search hit (its id, a subtitle) | nothing: one entry per entity held |
| a size entry used to weigh cached documents | an entity reference and a number | nothing: one entry per entity held (a delete removes it) |
| when each entity was last written (for `evict-oldest`) | an entity reference and a number | one entry per entity held |

The last three grow with the number of entities and are not bounded. Estimated from those structures (not measured),
they come to a few hundred bytes per entity: a few hundred megabytes of heap for a million entities, plus `cache-mb`.

### 11.3 A million entities

The connector works with a million entities, with these costs:

| Operation | Cost at a million entities |
|---|---|
| start | the whole state store is read once to rebuild the index (seconds to minutes, with the number of entities) |
| a delete of a held entity | a cheap removal from the type-ahead index (marked removed, folded out every 4,096 removals); open views are told |
| a structured search | reads at most 20,000 documents (`partial: true` beyond) |
| disk | the compressed latest document of each entity (section 6.2), within `state.max-gb` |
| a million **new** entities a day | the store grows with the entities until `state.max-gb`; then `evict-oldest` removes the entities written longest ago (an eviction of many entities also rebuilds the type-ahead index), or `state.reset-at` clears it daily |

For a book of a million entities a day with searches and history, keep the data in a dated store (Delta Lake,
PostgreSQL, Aerospike) and use the broker only for live ticks of the entities that change, or use Kafka in `ticks`
mode ([KAFKA_CONNECTOR.md](KAFKA_CONNECTOR.md)).

### 11.4 Disk

Put `state.root` on local disk, not a network share (RocksDB needs file locks, and `sync` needs a low sync latency).
One store per connector, so two connectors must never share `state.dir`: RocksDB refuses to open a store another
process or connector holds, and the connector fails to start with `cannot open disk cache at …`. Each connector's
`state.max-gb` is its own; plan the disk for the sum ([section 7.4](#74-sizing-the-disk)).

## 12. History: combining with a lake

The connector is undated. For history, serve the same kind from a dated connector as well, and let the router choose:

```yaml
# packs/counterparty-risk/pack.yaml (sketch)
connectors:
  credit-store:  { plugin: delta, settings: { root: "${DRISHTI_DELTA_ROOT:./data/delta}", domain: credit } }
  limits-mq:     { plugin: activemq, kinds: [credit-limit], settings: { destinations: queue:limits,
                   kind.limits: credit-limit, id-field.limits: limitId } }
routes:
  credit-limit: credit-store
```

| The user asks for | Order tried | Who answers |
|---|---|---|
| Live | `limits-mq` (live first) → `credit-store` | the queue's latest document, ticking; an entity the queue has not delivered falls through to the lake's newest date |
| a picked date | `credit-store` (dated first) → `limits-mq` | the lake, for that date; static |
| a date the lake does not hold | as above | the queue's current document, with the banner *No data held for <date>: the current data of limits-mq, a source that keeps no dates* |

The same holds for any dated store (PostgreSQL, Aerospike, files, S3). The broker covers today as it happens; the
nightly load into the dated store covers history. See
[CONNECTOR_DEVELOPER_GUIDE.md, Combining connectors](CONNECTOR_DEVELOPER_GUIDE.md#combining-connectors).

## 13. Failure and recovery

### 13.1 The two layers

- **The failover transport.** With the default `failover:(…)` URL, the ActiveMQ client reconnects by itself, with a
  delay from `initialReconnectDelay` (1,000 ms in the default URL) doubling to `maxReconnectDelay` (30,000 ms). While
  it reconnects, the session and consumers stay valid and nothing is rebuilt; a transport listener reports the state
  in health.
- **The supervisor.** Any other failure ends the receive loop (an exception from the client, a refused client id, a
  missing `destinations`). The connection is closed and opened again after a pause of 1 s, doubling to 30 s; the pause
  returns to 1 s once a connection has lasted more than 60 s.

A `broker-url` without `failover:` (`tcp://host:61616`) works, but every broker outage then goes through the
supervisor (a new connection and session) instead of the transport.

The connector starts even when the broker is down: the state store answers reads at once, and health stays `DOWN`
until the broker answers (tested in `ActiveMqOutageTest`: started before its broker, killed, recovered with a new
broker).

### 13.2 Health, exactly as the code produces it

| Health | When |
|---|---|
| `DOWN: not started` | before the supervisor's first attempt |
| `DOWN: connecting to <broker-url>` | each (re)connection attempt; `<broker-url>` is the setting as written, or `failover:(tcp://localhost:61616)` when unset. With the failover transport this stays while the broker is unreachable at start |
| `UP` | the consumers are created and the connection started; and again when the failover transport resumes |
| `DOWN: connection to the broker lost (reconnecting)` | the failover transport lost the broker (`transportInterupted`) |
| `DOWN: <message> (reconnecting)` | the transport reported an I/O error (`onException`) |
| `DOWN: <Exception>: <message> (reconnecting)` | the receive loop failed; the supervisor will retry, e.g. `DOWN: IllegalStateException: activemq plugin needs settings.destinations (reconnecting)`, `DOWN: InvalidClientIDException: … (reconnecting)`, `DOWN: JMSSecurityException: … (reconnecting)` |
| `DOWN: <reason> (messages are not acknowledged and come again)` | the state store failed to keep a message (for example `DOWN: state store write failed: … (messages are not acknowledged and come again)`); shown over any other text until a message is kept again ([section 7.2](#72-when-the-store-cannot-keep-a-message)) |
| `UP (state store over its budget: X of Y GB; the oldest entities are being evicted)` | connected, and the store is past `state.max-gb` with `state.when-full: evict-oldest`; clears once evictions bring it under |
| `UP (state store over its budget: X of Y GB; nothing is dropped: raise state.max-gb or add disk)` | connected, and the store is past `state.max-gb` with `state.when-full: warn` |

Health starting with `UP` counts as up in Admin → Health. With `stale-after` set (an engine setting on every
connector, [CONFIGURATION.md](../admin/CONFIGURATION.md#connector-settings-plugin-by-plugin)), a connector that has
received nothing for that long is reported stale and the overall status `DEGRADED`; `lastUpdate` is the time of the
last message received, rejected messages included.

### 13.3 Cache figures

`GET /api/v1/admin/caches`, or the connector's `cache` in `GET /api/v1/admin/health`:

```json
{"name": "limits-mq", "health": "UP", "cache": {"entities": 4210, "memoryEntries": 4210, "stateMb": 3.1, "durability": "sync",
  "budgetMb": 20480, "evicted": 0, "received": 18942, "rejected": 0}}
```

| Figure | Meaning |
|---|---|
| `entities` | entities held (known ids) |
| `memoryEntries` | documents in the memory cache |
| `stateMb` | the state store's size in MB: its table files plus the write buffers (`-1` once closed); compared with the budget |
| `durability` | `sync`, `wal` or `none` (`state.durability`) |
| `budgetMb` | `state.max-gb` in MB |
| `evicted` | entities removed in this run to keep within the budget (`state.when-full: evict-oldest`) |
| `received` | messages received in this run |
| `rejected` | of those, skipped as unreadable and acknowledged (section 4.5); messages the store failed to keep are not counted here: they come again |

## 14. Security

- **Credentials.** `user` and `password` are passed to the connection factory. Keep them out of files:
  `password: ${AMQ_PASSWORD}` with the variable set where the server runs. Health and `/api/v1/sources` never show
  settings. Do not put credentials in `broker-url`: the URL appears in health while connecting.
- **TLS.** Use the `ssl://` transport inside the failover URL:
  `failover:(ssl://mq1.bank.example:61617,ssl://mq2.bank.example:61617)`. The connector uses the plain
  `ActiveMQConnectionFactory`, so TLS uses the JVM's default key and trust stores: start the server with
  `-Djavax.net.ssl.trustStore=… -Djavax.net.ssl.trustStorePassword=…`, and for client certificates
  `-Djavax.net.ssl.keyStore=… -Djavax.net.ssl.keyStorePassword=…`. There are no TLS settings on the connector itself.
- **Broker permissions.** The user needs to read (consume) its queues; for topics with durable subscriptions also to
  create them (ActiveMQ's `admin` right on the topic, plus `ActiveMQ.Advisory.>` as usual for OpenWire clients).
- **The state store** holds every document in plain (LZ4-compressed) form on local disk: protect the folder like the
  data itself (file permissions, encrypted volume).
- **Entitlements** apply on top as for every connector: the server redacts what it serves.

## 15. Diagnosing

| Symptom | Likely cause | What to do |
|---|---|---|
| `DOWN: connecting to failover:(…)` for minutes | the broker is unreachable from the server (address, firewall, TLS) | `nc -z <host> <port>`; check the URL; it connects by itself once reachable |
| `DOWN: IllegalStateException: activemq plugin needs settings.destinations (reconnecting)` | `destinations` empty | set it; restart |
| `DOWN: InvalidClientIDException: … (reconnecting)` | another connection (another server) uses the same `client-id` | a unique `client-id` per server |
| `DOWN: JMSSecurityException: … (reconnecting)` | wrong `user`/`password`, or no permission on a destination | fix credentials or broker authorisation |
| `DOWN: connection to the broker lost (reconnecting)` | the broker went away | nothing: the failover transport reconnects; views keep the stored documents |
| `rejected` grows | bodies that are not JSON, no kind, or no id | check `kind.<destination>` and `id-field.<destination>` against the bodies; the names are without `queue:`/`topic:` |
| a message was sent but `LIM <id>` says not held | the id came from a different field (or header) than you expect; or another server on the same queue took it | look at `received`/`rejected`; read `/api/v1/entities/<kind>/<id>/raw` |
| entities are split between two servers | both read the same queue | a topic, or a queue per server |
| a deleted entity still shows, not greyed | the delete named a different id, or the view is on a picked business date (static: no live stream) | send the delete with the exact id |
| an old entity will not go away | no delete was ever sent | send a delete; or `state.reset-at`; or stop the server and delete the state folder |
| `DOWN: … (messages are not acknowledged and come again)` | the state store cannot write: the disk is full, or an I/O error | free or grow the disk (`df -h` on `state.root`); the connector resumes by itself and the broker redelivers, without limit unless `max-redeliveries` is set |
| messages in `ActiveMQ.DLQ` after a disk problem | `max-redeliveries` was set to a number and was reached while the store could not write | move them back to the queue from the broker console; leave `max-redeliveries` at `-1` |
| entities vanished without deletes; `evicted` grows; WARN `state store over its budget … evicted …` | `state.max-gb` reached: `evict-oldest` removed the entities written longest ago | raise `state.max-gb` (and the disk); or `state.when-full: warn`; have producers resend full state |
| `UP (state store over its budget: … nothing is dropped …)` | `state.when-full: warn` and the store is past `state.max-gb` | raise `state.max-gb` or add disk before it fills; or switch to `evict-oldest` |
| messages arrive slowly, the disk is busy | `state.durability: sync` waits for a disk sync per message | a faster disk (an SSD with power-loss protection); or `wal` if losing the last moments before a power loss is acceptable |
| `failedToStart`: `No enum constant … Durability.…` | `state.durability` is not `sync`, `wal` or `none` | fix the value |
| type-ahead offers ids that answer "not held" after a daily reset | the index is not cleared by `state.reset-at` | restart after the reset time |
| a busy destination lags while another is quiet | the 50 ms poll per idle destination (section 11.1) | one connector per busy destination |
| start is slow | the whole state store is read at start | fewer entities: a smaller `state.max-gb` (with `evict-oldest`), or `state.reset-at` |
| `failedToStart`: `cannot open disk cache at …` | the state folder is held by another connector or process, or not writable | a distinct `state.dir` per connector; permissions |
| removed a topic from `destinations`, broker disk fills | its durable subscription still collects messages | remove the subscription on the broker |

## 16. Settings

On an `activemq` connector (`drishti.sources.connectors.<name>.settings`, or `drishti.sources.plugins.activemq.settings`):

| Setting | Default | Meaning |
|---|---|---|
| `broker-url` | `failover:(tcp://localhost:61616)?initialReconnectDelay=1000&maxReconnectDelay=30000` (health shows `failover:(tcp://localhost:61616)`) | OpenWire URL; `failover:(…)` reconnects by itself |
| `max-redeliveries` | `-1` | redeliveries of a message the state store could not keep before the broker dead-letters it; `-1`: without limit |
| `user` | none | broker user |
| `password` | none | broker password |
| `destinations` | empty (required: the connector stays `DOWN` without it) | comma list: `queue:<name>`, `topic:<name>`, or `<name>` (a queue) |
| `client-id` | `drishti-<source-name>` | JMS client id; durable subscriptions are named `drishti-<source-name>-<topic>` |
| `kind` | none | the kind of every destination (bodies are documents) |
| `kind.<destination>` | none | that destination's kind; without a kind, bodies are envelopes |
| `id-field` | `id` | the body's id field, every destination |
| `id-field.<destination>` | `id-field` | the body's id field on that destination |
| `cache-mb` | `128` | memory for recent documents |
| `state.root` | `./data/state` | parent of the default state folder |
| `state.dir` | `<state.root>/<source-name>` | the RocksDB state store |
| `state.durability` | `sync` | `sync` (write-ahead log synced on every write: no acknowledged message lost on a crash or a power loss), `wal` (not synced: survives a crash of the process, a power loss can lose the last moments), `none` (no log: a crash can lose up to the 32 MB write buffer) (section 7.1) |
| `state.max-gb` | `10` | this connector's disk budget (decimal allowed); what happens past it is `state.when-full` (section 7.3) |
| `state.when-full` | `evict-oldest` | `evict-oldest`: past the budget, remove the entities written longest ago until under 90% of it, then compact (logged, counted in `evicted`); `warn`: remove nothing, say so in health |
| `state.check-seconds` | `60` | how often the store's size is checked against the budget |
| `state.reset-at` | `never` | a daily clearing time, `HH:mm` |
| `state.zone` | `America/New_York` | the zone of `state.reset-at` |
| `source-name` | `activemq`; a named connector: its name | provenance source; names the default state folder and client id |

On the connector entry, beside `settings`: `plugin`, `enabled`, `kinds`. Under `settings` the engine also reads
`stale-after`, a duration (`15m`, `2h`), as for every connector
([CONFIGURATION.md](../admin/CONFIGURATION.md#connector-settings-plugin-by-plugin)).

The plugin is built against `activemq-client` 6.3.2 (Jakarta JMS) and tested against ActiveMQ Classic 6.1.7 in Docker
(`ActiveMqSourcePluginTest`, `ActiveMqOutageTest`, skipped where Docker is not reachable).

## 17. Checklist for production

1. Give the connector `kinds:` and, for each destination with plain documents, `kind.<destination>` and
   `id-field.<destination>`; agree the `id` and `deleted` properties with the producers.
2. Use a `failover:(…)` URL listing every broker of the pair or network, over `ssl://` with the JVM trust store set.
3. Set a `client-id` unique to each server, once, and keep it; remove durable subscriptions you no longer read.
4. Never let two servers read the same queue unless splitting is intended; use topics or a queue per server.
5. Put `state.root` on local disk (an SSD: `state.durability: sync` waits for a disk sync per message), back it up
   (it is the only copy), and size `state.max-gb` for the entities held (their number times their compressed size,
   with a margin); plan about twice that of disk per connector. Decide between `evict-oldest` (the default) and
   `warn` for what happens past it.
6. Decide whether the state should start empty each day (`state.reset-at`), and if so restart after the reset or have
   producers resend state promptly.
7. Give a busy destination its own connector.
8. Serve the same kinds from a dated store for history and searches over large books; route the kind to it.
9. Set `stale-after` to the longest quiet period that is normal, so a silent producer shows in Admin → Health.
10. Watch `rejected`, `stateMb` against `budgetMb`, and `evicted` in Admin → Caches after go-live; alert on a health
    that ends `(messages are not acknowledged and come again)`.
11. Keep `max-redeliveries` at `-1` (the default) so messages the store could not keep are retried through a disk
    outage rather than dead-lettered.

---

## Appendix: walk-through and worked examples

Moved here from the former connector guides, so that everything about this connector is in one document.

### Walk-through, step by step

[ACTIVEMQ_CONNECTOR.md](ACTIVEMQ_CONNECTOR.md) explains the connector in full: queues and durable topics, the state store, acknowledgement, failover and every setting.

#### The situation

Your limit-management system publishes each credit limit to the ActiveMQ Classic queue `limits` whenever it
changes. You want `LIM <id>` to show the latest version, live.

#### How message-queue connectors keep data

If the store cannot keep a message (disk full, an I/O error), the message is **not** acknowledged: ActiveMQ redelivers
it a second later (`session.recover()`, without limit unless `max-redeliveries` sets one), RabbitMQ requeues it, and
health reads `DOWN: <reason> (messages are not acknowledged and come again)` until one is kept again. A message the
connector cannot read (not JSON, not a document) is acknowledged and counted in `rejected`, since it would otherwise
come back forever: there is no dead-lettering of those.

Three more properties of the store matter in production:

#### The data

A `TextMessage` (or `BytesMessage`, read as UTF-8) whose body is JSON, with optional **string properties**:

On `limits`, configured with a kind (mapped):

#### Configure it

**Pack form:**

**Site form**, the same under `drishti.sources.connectors` (bracket and quote a key that has characters other than
letters, digits, `-` and `.`, for example `"[kind.desk_limits]": credit-limit`):

Two Drishti servers on the same broker need different `client-id`s (ActiveMQ refuses a second connection with the
same id); each then has its own durable subscription to each topic, named `drishti-<connector>-<topic>`. A durable
subscription stays on the broker after its topic is removed from `destinations`, and the broker keeps queueing for
it: remove it on the broker. With several destinations the connector polls them in turn, and each idle one costs a
50 ms wait per loop, so a busy destination listed with idle ones is read more slowly. Two servers
reading the same **queue** share its messages, so each would hold only part of the entities: give each server its own
queue (or read a topic).

#### Try it

```bash
docker run -d --name amq -p 61616:61616 -p 61613:61613 -p 8161:8161 apache/activemq-classic
DRISHTI_LIMITS_MQ=true java -jar drishti-server/target/drishti-server-*-exec.jar     # with the pack form above
```

Send a message with an `id` property, for example over STOMP (ActiveMQ turns STOMP headers into message
properties):

```python
# uv run --with stomp.py python send_limit.py
import json, stomp
c = stomp.Connection([("localhost", 61613)]); c.connect("admin", "admin", wait=True)
c.send("/queue/limits", json.dumps({"limitId": "LIM-ALDERSHOT", "limit": 338000000.0, "used": 241900000,
                                    "utilisation": 0.7157, "status": "Within limit"}), headers={"id": "LIM-ALDERSHOT"})
c.disconnect()
```

or from the web console (`http://localhost:8161/admin`, Queues → `limits` → Send To) with the JSON body.

#### In the terminal

`LIM LIM-ALDERSHOT <GO>`: provenance `limits-mq`, live; send another message and the view updates. The type-ahead
lists every entity received; one only the queue holds has the subtitle `credit-limit · limits-mq` (an id a dated store
also holds, such as `LIM-ALDERSHOT`, shows that store's subtitle). Pick a date: the lake (`credit-store`)
answers, since dated connectors go first.

### Configuration by example

**What it is for.** Live entities pushed by systems that publish to ActiveMQ Classic (OpenWire) queues or topics.
The connector keeps each entity's latest document itself (see [Message queues](CONNECTOR_DEVELOPER_GUIDE.md#message-queues-activemq-rabbitmq)),
so entities sent before a restart are still there.

**Configuration.**

```yaml
# packs/<pack>/pack.yaml (or the same under drishti.sources.connectors in application.local.yaml)
connectors:
  desk-orders:
    plugin: activemq
    enabled: ${DRISHTI_ORDERS_ENABLED:false}
    kinds: [order, quote]
    settings:
      broker-url: ${AMQ_URL:failover:(tcp://localhost:61616)}   # failover reconnects by itself
      user: ${AMQ_USER:}
      password: ${AMQ_PASSWORD:}
      destinations: queue:orders,topic:quotes                    # a bare name is a queue
      kind.orders: order                                         # destination name without queue:/topic:
      id-field.orders: orderId
      kind.quotes: quote
      id-field.quotes: quoteId
      client-id: drishti-prod-1-desk-orders                      # unique per server: durable topic subscriptions use it
      state.max-gb: 20
```

**Settings.**

**The data.** A `TextMessage` (or `BytesMessage`, read as UTF-8) whose body is JSON; optional string properties
`id` and `deleted`. On `orders` above (mapped):

```text
destination: queue://orders
property id: O-55120                       (optional; else the body's orderId)
body:        {"orderId": "O-55120", "side": "BUY", "instrument": "EQ-NVTK", "qty": 2500, "status": "WORKING"}
```

On a destination without a kind, the body is an envelope `{"kind": "order", "id": "O-55120", "doc": {…}}`. A
message with property `deleted=true`, `"doc": null` or no `"doc"` key deletes (an empty body deletes only on a destination with a kind,
with the `id` header naming the entity). Generation is a counter that rises with
every message; documents are live and undated.

**Try it.**

```bash
docker run -d --name amq -p 61616:61616 -p 8161:8161 apache/activemq-classic
# switch the connector on, start the server, then send a message from the web console
# (http://localhost:8161/admin, admin/admin → Queues → orders → Send To) with the JSON body above
```

**What the user sees.** `<mnemonic for order> O-55120 <GO>`, live; the view updates on every message. Health:
`UP`, `DOWN: connection to the broker lost (reconnecting)`, `DOWN: <reason> (messages are not acknowledged and come
again)` when the state store cannot write, `UP (state store over its budget: …)` past `state.max-gb`; cache figures
`entities`, `memoryEntries`, `stateMb`, `durability`, `budgetMb`, `evicted`, `received`, `rejected`.
