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
# Operating connectors, and their troubleshooting

Running connectors on a server (health, switching on and off, history, restarts) and what to do when one fails. These sections were part of [CONNECTOR_DEVELOPER_GUIDE.md](CONNECTOR_DEVELOPER_GUIDE.md); they moved here so that guide stays readable, and its old headings point here. The connector files themselves are in [CONNECTOR_FILES.md](CONNECTOR_FILES.md).

## Operating connectors

### Health in one call

`GET /api/v1/admin/health` (Admin → Health) answers, in order:

| Field | Meaning |
|---|---|
| `status` | `OK`; `DEGRADED` (a connector is down or stale, one failed to start, or a pack has problems); `DOWN` (no connector is up) |
| `summary` | counts: `sources`, `sourcesDown`, `failedToStart`, `packs`, `packsWithProblems` |
| `sources[]` | one row per running connector: `status` (`UP` when `health` starts with `UP`, else `DOWN`), `health` text, `kinds`, `live`, `dated`, `search`, `reads` (`reads`, `found`, `notHeld`, `errors`, `lastError`, `lastErrorAt`, `lastOkAt`, `p50Ms`, `p99Ms`), `cache`, `lastUpdate` (when it last received new data; null for sources read on demand), `staleAfter` and `stale` (nothing new within the connector's `stale-after` setting, see [CONFIGURATION.md](../admin/CONFIGURATION.md#connector-settings-plugin-by-plugin)). Down connectors are listed first |
| `failedToStart` | connector name → error message |
| `packs[]` | each pack's `connectors`, `connectorsDown` (running but down), `connectorsOff` (switched off, or failed to start) |
| `overrides` | pack definitions overridden by a child pack |

```bash
curl -s http://localhost:18480/api/v1/admin/health | jq -r '.sources[] | select(.status=="DOWN") | "\(.name): \(.health)"'
curl -s http://localhost:18480/api/v1/admin/health | jq '.packs[] | {name, connectorsDown, connectorsOff}'
```

### Every health text

| Connector | Health texts |
|---|---|
| `demo` | `UP` |
| `derived` | `UP (nothing computed yet)`, `UP`, `DOWN: <why the last computation failed>` |
| `file` | `UP`, `DOWN: no directory <root>` |
| `rest` | `UP` (a failed start is under `failedToStart`; `DOWN: not started` only before `start` has run) |
| `jdbc` | `UP`, `DOWN: not started`, `DOWN: <error> (reconnecting)` |
| `delta` | `UP`, `DOWN: cannot reach <root>/<domain>`, `DOWN: no Delta tables under <root>/<domain>` |
| `aerospike` | `UP`, `DOWN: not connected to Aerospike` |
| `kafka` | `DOWN: not started`, `UP (catching up)`, `UP`, `DOWN: no connection to the broker (reconnecting)`, `DOWN: <message> (retrying)`, `DOWN: <Exception>: <message> (reconnecting)` |
| `activemq` | `DOWN: not started`, `DOWN: connecting to <broker-url>`, `UP`, `DOWN: connection to the broker lost (reconnecting)`, `DOWN: <message> (reconnecting)`, `DOWN: <Exception>: <message> (reconnecting)`, `DOWN: <reason> (messages are not acknowledged and come again)`, `UP (state store over its budget: X of Y GB; the oldest entities are being evicted)`, `UP (state store over its budget: X of Y GB; nothing is dropped: raise state.max-gb or add disk)` |
| `rabbitmq` | `DOWN: not started`, `DOWN: <Exception>: <message> (retrying)`, `UP`, `DOWN: connection lost (recovering)`, `DOWN: consumer cancelled on <queue>`, and the same three state-store texts as `activemq` |
| `s3` | `UP`, `DOWN: <error> (retrying)` |
| `news`, `yfinance` | `UP` (nothing is fetched at start), `DOWN: <reason> (tried again on the next request)` after a failed call, `UP` after the next success |
| `feed` | `UP`, `DOWN: <Exception>: <message>`, `DOWN: the feed returned no data (serving the last data)`, `DOWN: the feed returned no data` (`DOWN: not fetched yet` only before `start` has run: the first fetch is inside it) |

Every connector recovers without restarting Drishti, and every one starts even when its store is down (see
[Reconnecting and failures](CONNECTOR_DEVELOPER_GUIDE.md#reconnecting-and-failures)).

### Installed but not configured

Every plugin on the class path runs as itself unless it is switched off, except one used only through named
connectors. A plugin that cannot run without a setting (`kafka` needs `topics`, `feed` needs `feed`, `jdbc` needs
`url`), as itself or as a named connector, then logs

```text
source plugin kafka is installed but not configured (kafka needs settings.topics); it stays idle
```

and is **not** counted as a failure (nor listed under `/sources`). Other plugins missing a required setting (`rest`
without `base-url`, `s3` without `bucket`) fail to start and appear under `failedToStart`; the server still runs.

### Caches and purging

`GET /api/v1/admin/caches` (Admin → Caches) lists the engine's cache and every connector that has one (real output,
shortened):

```json
[
  {"name": "engine", "type": "Layouts and shape fingerprints", "stats": {"layoutHitRate": 0.774, "fingerprints": 139, "layouts": 103}},
  {"name": "trade-endur", "type": "Connector", "stats": {"timeTravel": 0, "partitions": 3, "tables": 1}},
  {"name": "ecb-fx-feed", "type": "Connector", "stats": {"series": 10, "observations": 640, "fetchedAt": "2026-10-01T01:35:09.770409225Z"}}
]
```

**Purge** (one connector, or **Purge all**) is safe at any time and is written to the audit log. What it does
depends on the connector:

| Connector | A purge |
|---|---|
| `delta` | drops cached partitions, latest versions and time-travel versions; the next reads go to storage |
| `kafka` | drops the memory cache and clears the disk cache; reads come back from Kafka by offset |
| `activemq`, `rabbitmq` | drops the memory cache only: the state store is the only copy and is never purged |
| `aerospike` | forgets the days of promoted bins and re-reads the kinds' dates and the ids now |
| `s3` | drops cached reads and misses |
| `feed` | clears the data, then refetches now (a failed refetch leaves nothing to serve until the next good refresh) |

Purge after you correct data in place (a restated lake date) when you do not want to wait for `refresh-seconds`.

### Disk state

| Folder | Whose | Can it be deleted? |
|---|---|---|
| `./data/state/<connector>` (`state.root`, `state.dir`) | ActiveMQ and RabbitMQ state stores | only deliberately, server stopped: it is the only copy of what the queues delivered |
| `./data/cache/<connector>` (`DRISHTI_CACHE_ROOT`, `disk-cache.dir`) | Kafka disk cache | yes: it starts empty on every run anyway (the topic is replayed) |

Put both on local disk, not a network share, and size them; the two work differently:

| | State store (ActiveMQ, RabbitMQ) | Kafka disk cache |
|---|---|---|
| What it keeps | the latest value of each entity (level compaction): size follows the entities held | every message of the day (FIFO compaction): size follows the messages |
| Budget | `state.max-gb` (10), per connector; past it `state.when-full`: `evict-oldest` (default) removes the entities written longest ago until under 90%, logged and counted in `evicted`; `warn` keeps everything and says so in health | `disk-cache.max-gb` (10); past it the oldest files are dropped |
| After a crash | nothing acknowledged is lost with `state.durability: sync` (default); `wal` can lose the last moments on a power loss; `none` up to the 32 MB write buffer | refilled from the topic (no write-ahead log) |
| Disk to plan | about twice `state.max-gb` (a compaction after an eviction briefly needs room), plus the 64 MB write-ahead log | `disk-cache.max-gb` |

`state.reset-at: "06:00"` clears a state store's disk daily (the memory cache and type-ahead keep what they hold), for
state that should start empty each day.

### Timeouts

`drishti.sources.fetch-timeout` (5 s) bounds the **whole** read, across every connector tried in turn, not each one.
If a slow store sits in front of others (a REST service, a remote database), either raise it or move the slow store
later in the order (a route to the fast one):

```yaml
drishti:
  sources:
    fetch-timeout: 10s
```

A read that runs over ends with `DRS-1004 timed out reading <kind>/<id>`; the connector's own timeouts
(`timeout-ms` for `rest`, `timeout-seconds` for `feed`, `connect-timeout-ms` for `aerospike`) should be at or below
it.

### Capacity tips

- **Memory per connector**: `delta` `cache-mb` (512) of partitions; `kafka` `cache-mb` (256) plus about 0.4–0.5 GB
  per million entities for its index and type-ahead (estimated); `activemq`/`rabbitmq` `cache-mb` (128) plus a few
  hundred bytes per entity held (estimated); `aerospike` `columns-cache-mb` (1024) of promoted
  bins plus one id per entity in the index set (not the days kept). Add them up across connectors when sizing the heap (`-Xmx`).
- **Database connections**: `pool-size` (20, the site default `drishti.db.pool.size`) per database connector, per server; the sum of the pools of all
  servers must fit the database's `max_connections` ([OPERATIONS.md](../admin/OPERATIONS.md#sizing-the-database-connection-pools)).
- **Writing a database connector**: do not open connections yourself. Build a `ResourcePool` from `com.ash.drishti.api.pool` with
  `PoolSettings.resolve(ctx.settings())` (it reads `pool-size`, the site default and the other `pool-*` keys), borrow in a try-with-resources
  (`try (var lease = pool.borrow()) { ... }`) for one unit of work, `lease.invalidate()` on a broken connection, and return `pool.stats()` from
  `SourcePlugin.poolStats()` so Admin and Prometheus show it. For a driver with its own pool (MongoDB, Aerospike) map the same settings onto it. `ConnectionDisciplineTest`
  fails the build on a connection opened outside a try-with-resources and on a field that holds one; the JDBC plugin's `JdbcPool` is the model.
- **Background work**: `refresh-seconds` (Delta 10, with a search re-index every 6 × that; Aerospike 60),
  `rescan-seconds` (`file` 30, `s3` 60), `refresh-minutes` (feeds 60). Shorter means fresher search and more load on
  the store.
- **Read latency** per connector is in `reads.p50Ms` and `reads.p99Ms`; a high `notHeld` count on a connector early in
  the order means it is being asked for kinds or ids it does not have: give it `kinds:`.
- **Several servers** each read every Kafka topic in full and each need their own `client-id` (ActiveMQ) and their own
  queues (both message brokers).
- **Message-queue throughput**: with `state.durability: sync` (the default) each message waits for a disk sync, so a
  connector applies about one message per sync, typically thousands a second on an SSD. Put `state.root` on an SSD,
  split a heavy feed over several connectors (each has its own store), or use `wal` where the last moments before a
  power loss may be lost.

---

## Troubleshooting

Set `B=http://localhost:18480/api/v1` first. With security on, add `-H "Authorization: Bearer $TOKEN"` to admin
calls.

| Symptom | Check | Fix |
|---|---|---|
| The view says `DRS-1001 no source holds trade/MX-20000001` | `curl -s $B/sources \| jq -c '.sources[] \| select(.kinds==[] or (.kinds \| index("trade"))) \| {name, health}'` (who serves the kind) | the id is spelled differently in the store; the store is off; or the date is outside its history |
| `DRS-1002 no source serves kind 'x'` | `curl -s $B/sources \| jq '.sources[].kinds'` | no running connector serves the kind (both `demo` and `file` are off): add `kinds` to a connector, or switch one on |
| `DRS-1003 <connector> failed reading …` | `curl -s $B/admin/health \| jq '.sources[] \| select(.name=="<connector>") \| {health, reads}'` | the store is down or rejecting the query: `lastError` says why |
| `DRS-1004 timed out reading …` | `reads.p99Ms` of the connectors serving the kind | raise `drishti.sources.fetch-timeout`; route the kind to the fast store |
| A connector is missing from `/sources` | `curl -s $B/sources \| jq .failures`; `drishti.py connector list` (state and problems); the server log for `failed to start`, `connector file … is not usable` or `installed but not configured` | fix the setting named in the message; check `enabled` and the pack in `DRISHTI_PACKS`; a bad file keeps the last good configuration running |
| `DRS-1011 connector <name> is not configured` | `ls config/connectors`; Admin → Packs → Data source | a pack routes the kind to a connector nobody defined: create it (Admin → Connectors, **Create**) |
| `failedToStart` shows `no plugin named 'null'` | the connector's file in `config/connectors/` (`drishti.py connector get <name>`) | the file has no `plugin:` (or a typo in a name defined only in the deprecated `application.yaml` form) |
| `failedToStart` shows `no plugin named 'x'` | `ls drishti-server/target/*exec.jar`; `DRISHTI_PLUGIN_DIR` | misspelt plugin name (`postgres` is not a plugin; it is `jdbc`) |
| Health `DOWN: no Delta tables under ./data/delta/<domain>` | `ls data/delta/<domain>/*/_delta_log` | build the lake or set `DRISHTI_DELTA_ROOT`; restart |
| A picked date shows *No data held for <date>* (the current data of an undated source) | `curl -s "$B/entities/<kind>/<id>/raw?asOf=<date>" \| jq .provenance` | the dated store does not hold that date (`lookback-days`, history kept); load it |
| A picked date shows *Latest data on or before … is from …* | — | expected: the store's newest date on or before the one picked |
| Live view does not tick | `jq .provenance` of the view (`/api/v1/views/<kind>/<id>`): `live` must be `true` | no live connector answers or pushes the kind: switch the stream on (`state` or `ticks` mode), the demo off |
| Live view ticks from samples, not Kafka | `provenance.source` is a sample name (`murex-rates`) | `DRISHTI_DEMO_ENABLED=false` |
| Type-ahead does not offer an id | `curl -s "$B/command/suggest?q=<MNEMONIC>%20<id-prefix>"` | `rest` and `jdbc` query mode cannot search; others list ids every `rescan-seconds`/`refresh-seconds` |
| *Linked entities* is empty | does any connector with `reverseLookup: true` serve the linking kinds? | reverse lookups need `delta`, `jdbc` table mode, `aerospike` (`reverse-index`), or `demo` |
| Kafka health stays `UP (catching up)` | `jq '.sources[] \| select(.name=="trading-stream") \| .cache'`: `indexedEntities` grows | wait for the replay; a very large topic needs compaction |
| Kafka health `DOWN: … has no partitions yet` | `kafka-topics.sh --describe --topic <topic>` | create the topic |
| Kafka health `DOWN: no connection to the broker (reconnecting)` | `nc -z <host> <port>` for each `bootstrap-servers` address, and the broker's advertised listeners | bring the broker back; the connector carries on by itself, no restart |
| ActiveMQ/RabbitMQ `rejected` grows | the message bodies and `id` headers | bodies must be JSON with an id (header or `id-field.<destination>`) |
| An old entity will not go away (message queues) | `stateMb`, `entities` in the connector's cache figures | send a delete (`deleted=true`), or clear the state store (server stopped) |
| ActiveMQ/RabbitMQ health `DOWN: … (messages are not acknowledged and come again)` | `df -h` on `state.root`; the server log | the state store cannot write: free or grow the disk; the connector resumes by itself and the broker redelivers (ActiveMQ without limit unless `max-redeliveries` is set) |
| ActiveMQ/RabbitMQ entities gone without deletes | `evicted` in the cache figures; WARN `state store over its budget` in the log | the store passed `state.max-gb` with `evict-oldest`: raise the budget and the disk, or `state.when-full: warn` |
| ActiveMQ/RabbitMQ health `UP (state store over its budget: …)` | `stateMb` against `budgetMb` | raise `state.max-gb` or add disk (`warn`), or let `evict-oldest` finish |
| Feed `DOWN: ConnectException: …` or `DOWN: HttpTimeoutException: …` | the server's outbound internet (or a proxy) | a proxy rule, or `url: file://…` |
| The overall status is `DEGRADED` | `curl -s $B/admin/health \| jq '{summary, failedToStart, packs: [.packs[] \| select(.status!="OK") \| {name, connectorsDown, sutraProblems}]}'` | fix what is listed; a switched-off connector does not degrade |
| Admin health answers `403` | the caller is not an administrator | use `/api/v1/sources` (anyone), or an admin token |

If none of these fit, [TROUBLESHOOTING.md](../guides/TROUBLESHOOTING.md) covers the server and console generally, and
each connector's own document (see [The shipped connectors](CONNECTOR_RECIPES.md#the-shipped-connectors)) has every setting, and
[The SourcePlugin SPI](CONNECTOR_DEVELOPER_GUIDE.md#the-sourceplugin-spi) is the contract for writing your own.
