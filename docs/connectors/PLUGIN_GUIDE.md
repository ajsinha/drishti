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
# Writing a source plugin

A source plugin brings entity documents from one system into Drishti. Plugins depend only on
`drishti-api` (no Spring, no Jackson), so they stay small and cannot clash with the server's libraries.

The first half of this guide is the contract and the configuration model. The second half,
[Every plugin, by example](#every-plugin-by-example), takes each built-in plugin in turn: what it is for, a working
configuration (site and pack), every setting it reads, what the stored data must look like, how to try it with the
tools in this repository, and what the user types to see it. [A pack with several connectors](#a-pack-with-several-connectors)
ends with routing, inheritance and site overrides worked through on one pack.

## Reconnecting

Every connector recovers from an outage without a restart of Drishti, and starts even when its store is down:

| Connector | How |
|---|---|
| `jdbc` | a pool of lazy slots: each opens its connection on first use and reopens it when broken; table kinds are listed in the background (every 10 s) until the database answers |
| `kafka` | the Kafka client rides out a broker outage and carries on (health `DOWN: no connection to the broker (reconnecting)` after 10 s without one); a supervisor recreates the consumer after a fatal error (backoff 1 s → 30 s) and resumes at the last applied offset |
| `mongodb` | the driver connects in the background; reads of kinds not yet catalogued answer "not held"; the next refresh fills the catalogue |
| `redis` | Lettuce reconnects by itself; the next refresh refills the catalogue and the `<domain>:changes` subscription resumes |
| `aerospike` | the client tends the cluster in the background (`failIfNotConnected` off); the next refresh refills the catalogue |
| `activemq` | the failover transport reconnects; its interruptions show in health; a supervisor rebuilds the session after any other failure |
| `rabbitmq` | a supervisor retries until the first connection succeeds; then the client's automatic recovery reconnects and re-subscribes |
| `delta`, `file`, `rest`, `s3`, feeds | nothing long-lived to lose: each call reads or connects afresh |

While a store is down its reads fail (views say which source failed) and `health` reports `DOWN: … (reconnecting)`.

## The contract

```java
public interface SourcePlugin extends AutoCloseable {
    PluginManifest manifest();                                   // name, version, kinds, capabilities
    void start(SourceContext ctx) throws Exception;              // settings, JSON parser, scheduler
    Optional<EntityDocument> fetch(EntityRef ref) throws Exception;
    default Optional<EntityDocument> fetch(EntityRef ref, AsOf asOf)           // dated sources override
    default Subscription subscribe(EntityRef ref, Consumer<EntityDocument> l)  // live updates
    default List<EntityRef> reverse(EntityRef target, String kind)             // e.g. netting set → trades
    default List<EntityRef> reverse(EntityRef target, String kind, AsOf asOf)
    default List<EntityHit> search(String kind, String text, int limit)        // command type-ahead
    default List<EntityHit> search(String kind, String text, int limit, AsOf asOf)
    default String health()
    default boolean pushes(EntityRef ref)                                       // ticks a kind another source serves
}
```

- **Business dates.** `AsOf(businessDate, knownAt, live)` says which day's data is wanted and, optionally, the
  data as known at an instant (before later corrections). A source that keeps history declares
  `SourceCapabilities(live, reverseLookup, search, dated = true)`, overrides the `AsOf` overloads, and stamps
  `Provenance.businessDate` with the date its document is for (which may be earlier than the one asked, for
  example the last business day that has data). Dated sources always receive a concrete date. Undated sources
  need do nothing: the defaults ignore the date, and the view tells the user the data is current, not historic.
  For a picked date, dated sources are tried before undated ones (ADR-012).

- **Thread safety.** `fetch` is called concurrently from many virtual threads. It may block (JDBC,
  HTTP), but it must be thread-safe.
- **Provenance.** Every `EntityDocument` carries `Provenance(source, generation, fetchedAt, live)`.
  - `source` is the name users see in *How this view was built* (`aero-risk`, `eod-futures`).
  - `generation` must be monotonic: higher means newer data.
- **Search.** `search` powers the dropdown under the command line, so it must answer from memory.
  `HitIndex` in the API is a ready-made, lock-free index: call `add(...)` at start, and `replaceAll(...)` on refresh.
- **Capabilities.** Declare `SourceCapabilities(live, reverseLookup, search)` honestly. The engine only
  calls optional methods that a plugin declares.
- **Kinds.** `PluginManifest.kinds` lists the kinds the plugin serves; an empty set means *any kind*, so the plugin
  is a candidate for every read (it answers "not held" for what it does not have).
- **Reading other kinds.** `SourceContext.reader()` reads other kinds through the server's routing: `list(kind,
  asOf, limit)` and `read(refs, asOf)` (concurrent). It is for connectors built on others, such as `derived`; use
  it when serving reads, not inside `start` (the router is built after the plugins start). It applies no
  entitlements: the server redacts what it serves.
- **Settings.** `SourceContext.settings()` is a flat `Map<String, String>`; `setting(key, fallback)` returns the
  fallback for a missing or blank value. A pack may nest settings for readability; nested maps arrive as dotted keys
  (`book-pnl.fields.mtm`) and lists as comma lists. Prefix families (`query.<kind>`, `mode.<kind>`, `header.<Name>`,
  `client.<property>`) are read by iterating the map.

## Registration

1. Put the plugin's fully qualified class name in `META-INF/services/com.ash.drishti.api.SourcePlugin`.
2. Either add the jar to the server's classpath, or drop it into `drishti.sources.plugin-dir`, where it
   gets its own class loader.

## Configuration

A plugin runs in one of two ways.

**As itself**, under `drishti.sources.plugins.<plugin>`: one instance, with one set of settings.

```yaml
drishti:
  sources:
    fetch-timeout: 2s
    default-route: demo             # tried after a kind's own route
    routes: { curve: aero, trade: aero }
    plugins:
      file:
        enabled: true
        settings: { root: ./data/feeds, source-name: eod-futures, rescan-seconds: 30 }
```

**As named connectors**, under `drishti.sources.connectors.<name>`: the same plugin several times, each with its own
settings, name and, optionally, kinds.

```yaml
drishti:
  sources:
    connectors:
      finance-lake: { plugin: delta, settings: { root: ./data/delta, domain: finance } }
      risk-lake:    { plugin: delta, kinds: [netting-set, exposure-profile], settings: { domain: risk } }
```

| Connector key | Default | Meaning |
|---|---|---|
| `plugin` | — | the plugin's name: `demo`, `file`, `rest`, `jdbc`, `delta`, `aerospike`, `kafka`, `activemq`, `rabbitmq`, `s3`, `feed` |
| `enabled` | `true` | whether it starts |
| `kinds` | every kind the plugin reports | limits what the connector serves: reads, subscriptions, reverse lookups and search for other kinds answer nothing |
| `settings` | `{}` | handed to the plugin; `source-name` defaults to the connector's name, so documents say which connector they came from |

Rules worth knowing:

- **Which instances start.** Every plugin on the class path runs as itself unless `plugins.<name>.enabled` is
  `false`, *except* a plugin used by any named connector: that one runs as itself only if it is also listed under
  `plugins`. So list a plugin you use only through connectors with `enabled: false` (as `application.yaml` does for
  `delta` and `aerospike`), or leave it out; a plugin that is neither listed nor used by a connector starts with empty
  settings. One that needs a setting (`kafka` needs `topics`, `feed` needs `feed`, `jdbc` needs `url`) then stays
  idle: it throws `PluginNotConfigured` from `start`, the log says *installed but not configured*, and health does
  not count it as a failure. Your own plugins should do the same when they have nothing to run with.
- **Names.** Routes, health and *How this view was built* use the connector's name. A plugin running as itself is
  known by its manifest name: `demo`, `file`, `rest` and `jdbc` always; `delta`, `aerospike`, `kafka`, `s3`, `feed`,
  `activemq` and `rabbitmq` by their `source-name` setting.
- **Setting keys with dots** (`mode.trade`, `kind.orders`, `header.Authorization`) are literal keys. In a pack's
  `pack.yaml` write them flat (`mode.trade: effective`); a nested map there is turned into a string. In
  `application.yaml` either form works, but Spring drops characters other than letters, digits, `-` and `.` from map
  keys unless the key is bracketed and quoted: `"[kind.desk_orders]": order`.
- **Values are strings.** Settings are `String` values; YAML numbers and booleans are converted. Placeholders
  (`${DRISHTI_DELTA_ROOT:./data/delta}`) are resolved by Spring, in packs as well as in site files.

**Every dated store passes the same contract.** `DatedSourceContract` (in `drishti-testkit`) loads one set of rows and
checks dates, snapshot and effective kinds, reverse lookups and search. The Delta Lake, PostgreSQL and Aerospike
connectors each extend it; the database tests run PostgreSQL 18 and Aerospike CE in Docker (Testcontainers) and are
skipped where Docker is not reachable.

**Routing order for a kind.** Its configured route, then `default-route`, then every other plugin or connector whose
manifest serves the kind. That list is then re-ordered, keeping the order within each group:

- for a **picked business date**, dated sources first, then undated ones;
- for **Live**, live sources first (and among them a real stream before the `default-route`'s samples), then the rest.

The first source that holds the entity answers; a source that does not hold it passes to the next. A source that
*fails* (an exception) ends the read with `DRS-1003`; a read that takes longer than `fetch-timeout` ends with
`DRS-1004`; no source holding it is `DRS-1001`; no source serving the kind at all is `DRS-1002`.

Live updates are subscribed separately (`SourceRouter.subscribe`), in the same live order as reads (a real stream
before the `default-route`'s samples): to the first live source that holds the entity, so ticks come from the source
that answered the read; only when none holds it yet, to the first that accepts the subscription. Search asks every search-capable source and
merges the hits; reverse lookups ask every source that declares them.

## Built-in plugins

| Plugin | Serves | Notes |
|---|---|---|
| `demo` | The enabled packs' sample entities (`packs/<name>/samples/`): finance has the 36 behind the four mockups; logistics has shipments, containers, vessels and ports | Samples are generated by each pack's `tools/`. Supports search, reverse lookup and live ticking (`_meta.walk` random-walks the named fields). The directories come from the packs (`settings.dirs`). [Example](#demo) |
| `delta` | Delta Lake tables `<root>/<domain>/<kind>/`: `(id STRING, doc STRING)` rows partitioned by `business_date` | Read with Delta Kernel (Java, no Spark). Snapshot tables take the newest partition on or before the date; `effective` tables the last change on or before it. `knownAt` is Delta time travel: unless a table uses in-commit timestamps, Delta resolves the instant against the `_delta_log` files' modification times, so copy lakes with times preserved (`cp -p`, `rsync -t`). Reverse lookups index every identifier a document mentions. Run it as named connectors, one per domain. `root` is a local folder or `s3a://bucket/lake` (S3 and S3-compatible stores: `s3.region`, `s3.endpoint`, `s3.access-key`/`s3.secret-key`, any `hadoop.fs.s3a.*`; see OPERATIONS). Build a sample lake with `tools/samplegen/lake.py`; keep it bounded with `tools/lake/maintain.py`. [Example](#delta) |
| `file` | **JSON lines**: one file per kind per business day, `<root>/<domain>/<yyyy-MM-dd>/<kind>.jsonl` (or undated `<kind>.jsonl`), indexed once (ids with byte offsets, the pack's promoted fields as columns), see [FILE_CONNECTOR.md](FILE_CONNECTOR.md); or a file per entity, `<root>/<kind>/<id>.json` or `.csv`, and dated `<root>/<yyyy-MM-dd>/<kind>/<id>.json` (ships with `data/feeds/fixing/SOFR-HISTORY.csv`: try `FIX SOFR-HISTORY <GO>`) | Generation is the file's modification time. Identifiers that would escape the root are refused. A CSV becomes `{"rows": [...]}` with typed cells. [Example](#file) |
| `jdbc` | Per-kind SQL (`query.<kind>` with `:id`, `:asOf`; also parts `query.<kind>.<part>`, `ids.<kind>`, `columns.<kind>`, `reverse.<kind>` with `:target`: [JDBC_QUERIES.md](JDBC_QUERIES.md)), or **table mode** (`table: trading.entities`): all kinds of a data domain in one PostgreSQL table `(kind, id, business_date, doc jsonb, <promoted columns>)` partitioned by month, dated; type-ahead from memory, searches and aggregates from the pack's promoted columns, reverse lookups from link columns or SQL/JSON path | The driver comes with the server for PostgreSQL. `mode.<kind>` is `snapshot` or `effective`. Load with `tools/load-postgres.sh`. Scale design: [POSTGRES_CONNECTOR.md](POSTGRES_CONNECTOR.md). [Example](#jdbc) |
| `mongodb` | A collection per data domain: a document per entity per business date (`_id` `kind/id/yyyyMMdd`, the document and the promoted fields under `c`) and a narrow `<domain>_columns` copy | A read is one lookup by `_id`; dates and type-ahead ids from the `{kind, date, id}` index; a day's promoted fields read in parallel id ranges, cached. Retention by `--keep-days` or a TTL index. Load with `tools/load-mongodb.sh`. [MONGODB_CONNECTOR.md](MONGODB_CONNECTOR.md) |
| `redis` | Per entity per business day a compressed document (zstd with a dictionary trained per kind); per kind and day the promoted fields column-wise in chunks of 10,000; days and ids in sorted sets | Today and recent days in memory (about 1 GB per million trades a day), live through `<domain>:changes`, TTL retention; older days from Delta Lake. Load with `tools/load-redis.sh`. [REDIS_CONNECTOR.md](REDIS_CONNECTOR.md) |
| `aerospike` | Three sets per data domain: a record per entity per business date (`kind/id/yyyyMMdd`: the document and the pack's promoted fields as bins), an index record per entity (`kind/id`: its dates) and a record per kind (its dates) | A read is two key lookups. Type-ahead ids come from the index set every `refresh-seconds`; searches, pick lists, derived kinds, impact and reverse lookups read one day's promoted bins (`layout.<kind>.columns`) by partition scans, cached. Retention by record TTL. Load with `tools/load-aerospike.sh`. [Example](#aerospike) |
| `feed` | Public data: `nyfed-sofr`, `ecb-estr`, `ecb-fx`, `us-treasury`, `fred` | One connector per feed, declared by the market-data pack, each off until switched on. Entities carry the feed in their id (`FIX-SOFR-NYFED`). Keeps history for picked dates; a failed fetch keeps the last good data and shows in health. [Example](#feed) |
| `kafka` | Live entities from Kafka topics: an envelope `{kind, id, doc}`, or whole-document messages with `kind` and `id-field` | Reads every partition from the beginning (the topic is the state: the latest message per entity), then pushes each new message to open views. Tombstones delete. No consumer-group commits. The trading pack declares `trading-stream`, off until `DRISHTI_STREAM_TRADING=true`; `tools/samplegen/stream.py` replays and ticks the samples. [Example](#kafka) |
| `rest` | An HTTP/JSON service: `base-url` + `path` per kind | Headers from settings; the generation from a response header. [Example](#rest) |
| `s3` | Documents in Amazon S3 or any S3-compatible store (MinIO, Ceph, on-prem): `<prefix><kind>/<id>.json` and dated `<prefix><yyyy-MM-dd>/<kind>/<id>.json` | `bucket`, `prefix`, `region`, `endpoint` (S3-compatible stores, path-style), `access-key`/`secret-key` or the AWS credential chain (environment, profile, instance role). Identifiers and dates are listed every `rescan-seconds` for search; reads are cached `cache-seconds`. Only the SDK's S3 module and the JDK HTTP client (about 9 MB). [Example](#s3) |
| `activemq` | Live entities from ActiveMQ Classic queues and topics (`destinations: queue:trades,topic:quotes`) | Topics through durable subscriptions. Each message is acknowledged after it is stored. See *Message queues* below. [Example](#activemq) |
| `derived` | Kinds computed from other kinds: members grouped by an expression, with `count`, `sum`, `avg`, `min`, `max`, `distinct`, `first` and per-member rows | Built into the engine. Reads its members through the routing, so it works over any source; a picked date is computed from that date's members. Recomputed at most every `refresh` per date. See [PACKS.md](../guides/PACKS.md#derived-kinds-entities-computed-from-other-kinds). |
| `rabbitmq` | Live entities from RabbitMQ queues (`queues: trades,quotes`; `bind.<queue>: exchange:routing.key`) | Queues declared durable unless `declare: false`; manual acknowledgement after storing; `prefetch` 100. See *Message queues* below. [Example](#rabbitmq) |

### Message queues (ActiveMQ, RabbitMQ)

A queue delivers each message once and keeps no history, unlike a Kafka topic. So these connectors keep the latest
document of every entity themselves, in a **persistent state store** per connector: RocksDB on local disk
(`state.dir`, default `<state.root>/<source-name>`, that is `./data/state/<connector>`; `state.max-gb`, 10), which
survives restarts of Drishti, with the recent documents in a memory cache (`cache-mb`, 128). Nothing is lost while
Drishti is down: queue messages wait in the broker, and topics are read through durable subscriptions. Messages are
acknowledged only after they are stored, so a crash redelivers rather than loses.

| Message | Becomes |
|---|---|
| a body on a destination with `kind.<destination>` (or any destination when `kind` is set) | that kind's document; the id is the `id` header, else the field `id-field.<destination>` (else `id-field`, default `id`) |
| a body `{"kind", "id", "doc"}` on any other destination | the envelope's entity (the `id` header stands in for a missing `"id"`) |
| `"doc": null` in an envelope, a `deleted: true` header, or an empty body on a destination with a kind and an `id` header | a delete (an empty body anywhere else is counted as rejected) |
| anything else (not JSON, no kind or id) | skipped and counted as `rejected` in the connector's cache figures |

Every change is pushed to open views, search finds everything received, and a purge (Admin → Caches) clears only
the memory cache: the state store is the only copy, so it is never purged (clear it deliberately with
`state.reset-at`, or by deleting its folder). The store is bounded by `state.max-gb`: beyond it the oldest files are
dropped, so size it well above what the entities need. Declare them as named connectors in a pack or site
configuration (full examples: [activemq](#activemq), [rabbitmq](#rabbitmq)):

```yaml
drishti:
  sources:
    connectors:
      desk-orders:
        plugin: rabbitmq
        kinds: [order]
        settings: { uri: "${RABBIT_URI}", queues: orders, kind.orders: order, id-field.orders: orderId }
      market-quotes:
        plugin: activemq
        settings: { broker-url: "failover:(tcp://mq1:61616,tcp://mq2:61616)", destinations: "topic:quotes", kind.quotes: quote }
```

---

# Every plugin, by example

Each plugin below follows the same plan: **what it is for**, **configuration** (site and pack), **settings** (every
key the code reads), **the data**, **try it**, and **what the user sees**. Common ground first.

**Where configuration goes.**

| File | Who writes it | Precedence |
|---|---|---|
| `packs/<pack>/pack.yaml` → `connectors:` and `routes:` | the pack author | lowest: the packs are added as the last property source |
| `drishti-server/src/main/resources/application.yaml` | shipped defaults | above the packs |
| `application-postgres.yaml`, `application-aerospike.yaml` | profiles (`SPRING_PROFILES_ACTIVE`) | above `application.yaml` |
| `./application.local.yaml` (git-ignored, next to where the server runs) | the site | above the shipped files |
| environment variables, `--key=value` | the operator | highest |

A site entry overrides a pack's **key by key**: `drishti.sources.connectors.trading-store.plugin: jdbc` in
`application.local.yaml` replaces only the plugin; the pack's `kinds`, `settings.domain` and `settings.mode.*` stay.
A list (such as `kinds`) is replaced as a whole. See [A pack with several connectors](#a-pack-with-several-connectors).

**What the user types.** `<MNEMONIC> <ID> <GO>`: the mnemonic comes from a pack's `mnemonics:` (`TRD` → `trade`), the
id is the entity's id in the store. The command line suggests ids as you type, from every source that declares
search. The business date is picked in the top bar (API: `X-Drishti-As-Of: 2026-09-29` or `?asOf=`); *known at*
is `X-Drishti-Known-At` / `?knownAt=`.

**Where health shows.** The console's *Admin → Health* page (`/admin/health`) lists every connector with its status,
health text, kinds, read counts and latencies, and cache figures, plus connectors that failed to start and the
pack overrides. The same data is `GET /api/v1/admin/health` (admins); `GET /api/v1/sources` lists the sources and
their health for anyone. *Admin → Caches* (`POST /api/v1/admin/caches/{name}/purge`) purges one connector's caches.

**Dated plugins and business dates at a glance.**

| Plugin | Dated | Live | Search | Reverse | How a date is chosen |
|---|---|---|---|---|---|
| `demo` | no | yes (ticks) | yes | yes | — |
| `file` | yes | no | yes | yes (JSON lines: from promoted columns, else the lines) | newest `<yyyy-MM-dd>/` folder on or before the date (within `lookback-days`), then the undated file or folder; `mode.<kind>: effective` takes an entity's latest line |
| `rest` | no | no | no | no | — |
| `jdbc` (queries) | when a query uses `:asOf` | no | no | no | your SQL decides |
| `jdbc` (table) | yes | no | yes | yes | `snapshot` or `effective` per kind |
| `delta` | yes | no | yes | yes | `snapshot` or `effective` per kind; `knownAt` time travel |
| `mongodb` | yes | no | yes | yes, from promoted fields (`reverse-index`) | `snapshot` or `effective` per kind; a day it does not hold goes to the next store |
| `redis` | yes | yes (`live`) | yes | yes, from promoted columns | `snapshot` or `effective` per kind; a day Redis does not hold goes to the next store |
| `aerospike` | yes | no | yes | yes, from promoted bins (`reverse-index`) | `snapshot` or `effective` per kind |
| `kafka` | no | yes | yes (`state` mode) | no | — |
| `activemq`, `rabbitmq` | no | yes | yes | no | — |
| `s3` | yes | no | yes | no | as `file` |
| `feed` | yes | no | yes | no | the observations on or before the date |

**`snapshot` and `effective`.** A `snapshot` kind is stored in full for every business date: a read takes the newest
date on or before the one asked, but no older than `lookback-days` (10); an entity missing from that date is gone.
An `effective` kind stores a row only when the entity changes (reference data): a read takes each entity's last row
on or before the date, without a limit. For Live (no date), both take the newest.

---

## demo

**What it is for.** Sample data that works with nothing installed: each enabled pack's `samples/` folder, served
live, ticking while someone watches. It is the `default-route`, so it answers every kind a pack has samples for,
and it is what the screenshots and golden tests use. Not for production data.

**Configuration.** The packs supply the directories; the site only switches it on or off.

```yaml
# application.yaml (shipped)
drishti:
  sources:
    default-route: demo                     # tried after a kind's own route
    plugins:
      demo:
        enabled: ${DRISHTI_DEMO_ENABLED:true}
        # settings.dirs is filled by the packs: every enabled pack's samples/ folder, comma-separated
        settings:
          ticking: true                     # false freezes the documents (golden tests)
          tick-ms: 400                      # one random-walk step every 400 ms while someone subscribes
```

A pack does not declare a demo connector; it names its sample folder (`samples: samples`, the default) and the
loader adds it to `drishti.sources.plugins.demo.settings.dirs`.

**Settings.**

| Key | Default | Meaning |
|---|---|---|
| `dirs` | (from the packs) | comma-separated directories, each with `catalog.json` and `<kind>/<id>.json` |
| `ticking` | `true` | tick live documents while subscribed |
| `tick-ms` | `400` | tick interval in milliseconds |

**The data.** `packs/<pack>/samples/catalog.json` lists the entities; each document lives in `<kind>/<id>.json`.

```json
[
  { "kind": "trade", "id": "IRS-48213", "title": "IRS-48213", "subtitle": "Interest rate swap · Northbridge Capital · USD 50m · 5Y" }
]
```

Every document needs `_meta` with `source` (the name shown in provenance), `generation` and `live`; `walk` is
optional. A finance FX spot (shortened), and `packs/trading/samples/trade/MX-20000001.json` (shortened), which walks its MTM:

```json
{
  "pair": "EUR/USD", "spot": 1.174, "bid": 1.17396, "ask": 1.17404, "spotDate": "2026-10-02",
  "_meta": { "source": "aero-fx", "generation": 88, "live": true }
}
```

```json
{
  "tradeId": "MX-20000001", "productType": "…", "notional": 242000000.0, "mtm": 1875863, "nettingSet": "…",
  "_meta": { "source": "murex-rates", "generation": 1, "live": true, "walk": { "mtm": 6172 } }
}
```

`_meta` is removed from the document. When `live` is true and someone subscribes, each tick moves the fields named in
`walk` (`{"field": stepSize}`, top-level numbers; a step of 1 or more rounds to whole numbers), or, without `walk`,
the finance kinds' built-in walks (`curve`, `fx-spot`, `netting-set`, `trade`). Reverse lookups find documents whose
top-level fields (or top-level arrays) hold the target id.

**Try it.** Nothing to install: start the server (`java -jar drishti-server/target/drishti-server-*-exec.jar`) with
the default `finance` pack.

**What the user sees.** `TRD IRS-48213 <GO>`, `NSET NS-NORTH-01 <GO>`. Provenance shows `aero-risk`, `aero-fx`, …
(the `_meta.source`), live. Health: `demo`, always `UP`.

---

## file

**What it is for.** End-of-day files dropped in a folder by another system: JSON documents or CSV tables, optionally
one folder per business date. No database, no service; a rewritten file is newer data.

**Configuration.** The shipped `application.yaml` runs it as itself:

```yaml
drishti:
  sources:
    plugins:
      file:
        enabled: true
        settings:
          root: ${DRISHTI_FEEDS:./data/feeds}   # the folder to serve
          source-name: feed-file                # shown in provenance (the route name stays `file`)
          rescan-seconds: 30                    # how often the search index and the dated folders are re-listed
          lookback-days: 10                     # how far back a picked date may fall to an older dated folder
```

A pack (or a site) can run more folders as named connectors:

```yaml
# packs/<pack>/pack.yaml
connectors:
  eod-futures:
    plugin: file
    kinds: [settlement]                         # only this kind is read from the folder
    settings:
      root: ${EOD_FUTURES_DIR:/data/eod/futures}
      lookback-days: 5
routes:
  settlement: eod-futures                       # try this connector first for settlements
```

**Settings.**

| Key | Default | Meaning |
|---|---|---|
| `root` | `data/feeds` | the folder (resolved to an absolute path) |
| `source-name` | `file` (a connector: its name) | provenance source |
| `rescan-seconds` | `30` | re-list kinds, ids and dated folders |
| `lookback-days` | `10` | a dated folder older than the picked date minus this is not used |

**The data.**

```text
data/feeds/
├── fixing/
│   └── SOFR-HISTORY.csv            undated: kind "fixing", id "SOFR-HISTORY"
├── settlement/
│   └── CL-DEC26.json               undated: kind "settlement", id "CL-DEC26"
├── 2026-09-29/
│   └── settlement/CL-DEC26.json    dated: the settlement for 29 September
└── 2026-09-30/
    └── settlement/CL-DEC26.json
```

The kind is the folder name and the id the file name without `.json` or `.csv`. A JSON file is the document as is.
A CSV file needs a header row and becomes `{"rows": [...]}`, numbers and booleans typed:

```text
date,rate,volume_bn
2026-09-01,3.95,1910
```

```json
{ "rows": [ { "date": "2026-09-01", "rate": 3.95, "volume_bn": 1910 } ] }
```

Business dates: a read for a date takes the newest dated folder on or before it (within `lookback-days`) that has
the file, then the undated folder; Live takes the newest dated folder that has it, then the undated one. The
document's business date is its folder's date (none for the undated folder). Generation is the file's modification
time. An id containing `..` or a path separator that would leave the root is refused.

**Try it.** `data/feeds/fixing/SOFR-HISTORY.csv` ships with the repository; add your own files and wait for the
next rescan (search only; reads find new files at once).

**What the user sees.** `FIX SOFR-HISTORY <GO>` with the finance pack (its `FIX` mnemonic is kind `fixing`); laid
out by inference. Health: `UP`, or `DOWN: no directory <root>`.

---

## rest

**What it is for.** An in-house HTTP service that answers one entity as JSON per GET. Undated and fetch-only (no
search, no live updates); use it when the owning system already has an API.

**Configuration.**

```yaml
# application.local.yaml
drishti:
  sources:
    plugins:
      rest:
        enabled: true
        settings:
          base-url: https://positions.bank.example/api      # required; trailing slashes are dropped
          path: /v2/{kind}s/{id}                            # {kind} and {id} are URL-encoded and substituted
          kinds: position,limit                             # serve only these kinds (empty: any kind)
          source-name: positions-api
          timeout-ms: 3000                                  # connect and request timeout
          generation-header: X-Version                      # a numeric response header used as the generation
          header.Authorization: "Bearer ${POSITIONS_TOKEN}" # any request header: header.<Name>
    routes:
      position: rest                                        # running as itself, its route name is "rest"
```

As a pack connector (for example, two services):

```yaml
connectors:
  positions-api:
    plugin: rest
    kinds: [position]
    settings: { base-url: "${POSITIONS_URL:http://localhost:9000/api}", path: "/{kind}/{id}" }
routes:
  position: positions-api
```

**Settings.**

| Key | Default | Meaning |
|---|---|---|
| `base-url` | — (required) | the service root |
| `path` | `/{kind}/{id}` | appended to `base-url` |
| `kinds` | (any) | comma list of kinds served |
| `source-name` | `rest` (a connector: its name) | provenance source |
| `timeout-ms` | `2000` | connect and request timeout |
| `generation-header` | `ETag` | response header holding a numeric version (quotes removed); otherwise the fetch time |
| `header.<Name>` | — | request header `<Name>` |

**The data.** `GET https://positions.bank.example/api/v2/positions/POS-77` with `Accept: application/json`:
`200` with a JSON object is the document; `404` means *not held here* (the next source is tried); any other status
of 400 or more is an error (`DRS-1003`).

**Try it.** Any static server works: lay out `api/position/POS-77.json` and run
`python3 -m http.server 9000` in the parent folder, with `base-url: http://localhost:9000/api` and
`path: /{kind}/{id}.json`, and a mnemonic for `position` in a pack.

**What the user sees.** `<mnemonic> POS-77 <GO>`; provenance `positions-api`, not live, no business date. Health:
`UP` once started (the plugin keeps no connection, so a down service shows as failed reads, not in health).

---

## jdbc

Two modes. **Query mode** runs one SQL statement per kind against any database with a JDBC driver: use it for an
existing schema. **Table mode** reads a whole data domain from one PostgreSQL table of JSON documents, dated, with
search and reverse lookups: use it when you load the data for Drishti.

The PostgreSQL driver is on the server's class path; for another database put its driver jar on the class path or
in `drishti.sources.plugin-dir`.

### Query mode

**Configuration.** The shipped `application.yaml` has a switched-off `jdbc` entry; a site fills it in:

```yaml
# application.local.yaml
drishti:
  sources:
    plugins:
      jdbc:
        enabled: true
        settings:
          url: jdbc:postgresql://db.bank.example:5432/desk
          user: ${DRISHTI_JDBC_USER:}
          password: ${DRISHTI_JDBC_PASSWORD:}
          source-name: desk-db
          pool-size: 4
          # one statement per kind; :id is the entity id, :asOf the business date (SQL DATE)
          query.trade: >-
            SELECT trade_id, business_date, product, counterparty, notional, mtm, currency
            FROM desk.trades
            WHERE trade_id = :id
              AND business_date = (SELECT MAX(business_date) FROM desk.trades
                                   WHERE trade_id = :id AND business_date <= :asOf)
          query.counterparty: SELECT doc::text AS json FROM desk.counterparties WHERE cpty_id = ?
    routes:
      trade: jdbc                     # running as itself, its route name is "jdbc"
```

**Settings (query mode).**

| Key | Default | Meaning |
|---|---|---|
| `url` | — (required) | JDBC URL |
| `user`, `password` | empty | credentials (take them from the environment) |
| `source-name` | `jdbc` (a connector: its name) | provenance source |
| `pool-size` | `4` | connections; each opened on first use and reopened when broken |
| `query.<kind>` | — | the statement for `<kind>`: either one `?` for the id, or named `:id` / `:asOf` (each may appear several times) |
| `json-columns` | — | text columns that hold JSON (comma list), parsed into nested data; columns of type `json`/`jsonb` are parsed without being listed; a cell that is not JSON stays text |

The manifest's kinds are the `query.*` kinds, and the source is dated when any query uses `:asOf`.

**The data.** The first row is the document. Each column becomes a field named in camel case (`TRADE_ID` and
`trade_id` → `tradeId`); `NUMERIC` with no fraction becomes an integer, other numbers a double, `DATE` an ISO date,
`TIMESTAMP` an ISO instant. Special columns:

| Column | Effect |
|---|---|
| `json` | its JSON text is the whole document (other columns are ignored for the content) |
| `generation` (a number) | the version; otherwise the read time |
| `business_date` (a `DATE`) | the date the row is for (provenance), also added as `businessDate`; otherwise the asked date when the query uses `:asOf` |

```sql
CREATE TABLE desk.trades (
  trade_id      text           NOT NULL,
  business_date date           NOT NULL,
  product       text,
  counterparty  text,
  notional      numeric(18,0),
  mtm           numeric(18,2),
  currency      text,
  PRIMARY KEY (trade_id, business_date)
);
INSERT INTO desk.trades VALUES ('MX-20000001', '2026-09-30', 'IRS', 'CP-NORTHBRIDGE', 242000000, 1875863.00, 'USD');
```

`query.trade` above, for `TRD MX-20000001` on 2026-09-30, returns:

```json
{ "tradeId": "MX-20000001", "businessDate": "2026-09-30", "product": "IRS", "counterparty": "CP-NORTHBRIDGE",
  "notional": 242000000, "mtm": 1875863.0, "currency": "USD" }
```

### Table mode

**Configuration.** No pack in the repository declares a `jdbc` connector; the `postgres` profile
(`application-postgres.yaml`) switches the banking packs' `<domain>-store` connectors from Delta Lake to
PostgreSQL by overriding only `plugin` and `settings`:

```yaml
# drishti-server/src/main/resources/application-postgres.yaml (one of six lines)
drishti:
  sources:
    connectors:
      trading-store:
        plugin: jdbc                                   # replaces the pack's "delta"
        settings:
          url: "${DRISHTI_PG_URL:jdbc:postgresql://localhost:5432/drishti}"
          user: "${DRISHTI_PG_USER:drishti}"
          password: "${DRISHTI_PG_PASSWORD:drishti}"
          table: trading.entities                      # table mode: <schema>.<table>
          pool-size: "8"
```

The connector keeps the pack's `kinds: [trade]` and route; for `reference-store` it also keeps the pack's
`mode.<kind>: effective` settings. A pack that owns its PostgreSQL store declares it directly:

```yaml
# packs/<pack>/pack.yaml
connectors:
  trading-store:
    plugin: jdbc
    kinds: [trade]
    settings:
      url: ${DRISHTI_PG_URL:jdbc:postgresql://localhost:5432/drishti}
      user: ${DRISHTI_PG_USER:drishti}
      password: ${DRISHTI_PG_PASSWORD:drishti}
      table: trading.entities
      mode.trade: snapshot                 # the default; effective for reference data
routes:
  trade: trading-store
```

**Settings (table mode)**, in addition to `url`, `user`, `password`, `source-name` and `pool-size`:

| Key | Default | Meaning |
|---|---|---|
| `table` | — | `schema.table` (or `table`); setting it turns table mode on and `query.*` off |
| `kinds` | the kinds the table holds | comma list; without it the kinds are found by the catalogue refresh, every `refresh-seconds` (the first retry after 10 s while the database is down) |
| `mode.<kind>` | `snapshot` | `snapshot` or `effective` |
| `lookback-days` | `10` | snapshot kinds: how far back a picked date may fall |
| `layout.<kind>.columns` | none | the paths the pack promotes; each is a column of the table (`counterparty.id` is `"counterparty__id"`) that searches, pick lists, derived kinds, impact and reverse lookups read instead of documents |
| `refresh-seconds` | `60` | how often each kind's dates and the newest day's ids (type-ahead) are re-read |
| `scan-threads` | `4` | id ranges of a day read at once on as many pooled connections when reading a day's columns |
| `columns-cache-mb` | `1024` | memory for days of promoted columns |
| `columns-seconds` | `300` | how long a day's columns are kept before they are read again (a new load clears them at once) |
| `max-load-rows` | `200000` | the most documents a reverse lookup reads for a kind without promoted link columns |
| `reverse-index` | `true` | `false` turns reverse lookups off |
| `kind-column`, `id-column`, `date-column`, `doc-column` | `kind`, `id`, `business_date`, `doc` | column names |

Table and column names must be plain SQL identifiers, so they cannot carry SQL.

**The data.** One row per entity per business date. `tools/load-postgres.sh` (with `PostgresLoader`) creates this
layout; [POSTGRES_CONNECTOR.md](POSTGRES_CONNECTOR.md) explains it in full:

```sql
CREATE TABLE trading.entities (
  kind          text  NOT NULL,
  id            text  NOT NULL,
  business_date date  NOT NULL,
  doc           jsonb COMPRESSION lz4 NOT NULL,
  "tradeId" text, "productType" text, notional double precision, mtm double precision, book text,
  "counterparty__id" text, "nettingSet" text, …                           -- the pack's promoted columns
  PRIMARY KEY (kind, id, business_date)                                    -- single reads
) PARTITION BY RANGE (business_date);                                      -- entities_y2026m09: a month each
CREATE INDEX entities_day_ids ON trading.entities (kind, business_date, id);   -- a day's ids from the index alone
CREATE TABLE trading.entity_dates (kind text, business_date date, rows bigint, loaded_at timestamptz,
                                   PRIMARY KEY (kind, business_date));     -- each kind's dates
```

A table of the earlier form (`(kind, id, business_date, doc jsonb)`, not partitioned, no dates table) is still read:
the dates come from a skip scan of the index and searches read documents.

**Try it.**

```bash
docker compose -f deploy/compose.data.yaml up -d postgres
tools/load-postgres.sh jdbc:postgresql://localhost:5432/drishti                      # the samples, 10 business days
tools/load-postgres.sh jdbc:postgresql://localhost:5432/drishti --trades 50000       # and a book of 50,000 trades a day
SPRING_PROFILES_ACTIVE=postgres DRISHTI_PACKS=trading java -jar drishti-server/target/drishti-server-*-exec.jar
```

**What the user sees.** `TRD MX-20000001 <GO>`; pick an earlier date in the top bar and the trade's numbers change.
Provenance `trading-store`, with the business date. Health: `UP`, or `DOWN: <driver message> (reconnecting)`.

---

## delta

**What it is for.** History. A Delta Lake holds every business date of a data domain, written by your batch jobs
(Spark, `deltalake`, Databricks); Drishti reads it with Delta Kernel, without Spark. It is the default store of
every banking pack, and gives picked dates, *known at* time travel, reverse lookups and search.

**Configuration (local disk).** `packs/trading/pack.yaml`, as shipped:

```yaml
connectors:
  trading-store:                          # the connector's name: routes, health and provenance use it
    plugin: delta
    enabled: ${DRISHTI_LAKE_ENABLED:true} # one switch for every pack's lake
    kinds:
    - trade                               # serve only trades from this domain
    settings:
      root: ${DRISHTI_DELTA_ROOT:./data/delta}
      domain: trading                     # tables live under <root>/trading/<kind>/
routes:
  trade: trading-store                    # trades are asked of the lake first
```

`packs/banking-core/pack.yaml` declares reference data, which changes rarely, as `effective`:

```yaml
connectors:
  reference-store:
    plugin: delta
    enabled: ${DRISHTI_LAKE_ENABLED:true}
    kinds: [counterparty, counterparty-group, issuer, agreement, ccp, legal-entity, book, desk, trader, calendar, csa, clearing-account]
    settings:
      root: ${DRISHTI_DELTA_ROOT:./data/delta}
      domain: reference
      mode.counterparty: effective        # a row only when the counterparty changes
      mode.book: effective
      # … one mode.<kind> line per kind
```

`packs/finance/pack.yaml` declares its lake without `kinds`, so it serves every table it finds:
`finance-lake: { plugin: delta, enabled: ${DRISHTI_LAKE_ENABLED:true}, settings: { root: "${DRISHTI_DELTA_ROOT:./data/delta}", domain: finance, lookback-days: 10 } }`.

**Configuration (S3 or an S3-compatible store).** A site moves a pack's lake to object storage without touching the
pack:

```yaml
# application.local.yaml
drishti:
  sources:
    connectors:
      trading-store:
        settings:
          root: s3a://risk-lake/banking              # a scheme: read through Hadoop's S3A file system
          s3.region: us-east-1
          # credentials: the AWS chain (environment, profile, instance role), or:
          # s3.access-key: ${LAKE_ACCESS_KEY}
          # s3.secret-key: ${LAKE_SECRET_KEY}
          # an S3-compatible store (MinIO, Ceph):
          # s3.endpoint: https://minio.bank.example   # path-style addressing; TLS when https
          # s3.path-style: "false"                    # only with an endpoint; default true
          # hadoop.fs.s3a.connection.maximum: "200"   # any Hadoop setting, prefixed hadoop.
```

`domain` and `kinds` still come from the pack; the tables are read at `s3a://risk-lake/banking/trading/<kind>`.
Setting `DRISHTI_DELTA_ROOT=s3a://risk-lake/banking` moves every pack's lake at once.

**Settings.**

| Key | Default | Meaning |
|---|---|---|
| `root` | `./data/delta` | a local folder (`file:` allowed), or a URI with a scheme (`s3a://bucket/path`) |
| `domain` | empty | sub-folder of `root`; may not contain `..` or start with `/` |
| `kinds` | every table under the domain (a folder with `_delta_log`) | comma list of tables to read |
| `mode.<kind>` | `snapshot` | `snapshot` or `effective` |
| `lookback-days` | `10` | snapshot kinds: how far back a picked date may fall |
| `id-column`, `doc-column`, `date-column` | `id`, `doc`, `business_date` | column names; the date column is the partition column |
| `refresh-seconds` | `10` | how long a table's latest version is cached; the search index is rebuilt every 6 × this |
| `cache-mb` | `512` | partitions kept in memory, by size |
| `source-name` | `delta` (a connector: its name) | provenance source |
| `s3.region` | — | `fs.s3a.endpoint.region` |
| `s3.endpoint` | — | `fs.s3a.endpoint`; also sets path-style access and TLS by scheme |
| `s3.path-style` | `true` | `fs.s3a.path.style.access`, only with `s3.endpoint` |
| `s3.access-key`, `s3.secret-key` | — | static credentials; otherwise the AWS chain |
| `hadoop.<key>` | — | passed to Hadoop as `<key>` |

**The data.**

```text
data/delta/trading/trade/
├── _delta_log/00000000000000000000.json …
├── business_date=2026-09-29/part-00000-….parquet
└── business_date=2026-09-30/part-00000-….parquet
```

One table per kind, partitioned by `business_date` (`DATE`), with columns `id STRING` (the entity id) and
`doc STRING` (the JSON document). A `doc` value:

```json
{ "tradeId": "MX-20000001", "productType": "IRS", "counterparty": "CP-NORTHBRIDGE", "nettingSet": "NS-…", "mtm": 1875863,
  "notional": 242000000.0, "businessDate": "2026-09-30" }
```

The kind is the table's folder name, the id the `id` column. The business date is the partition's date (a table
without the partition column serves its rows undated). The generation is the table's Delta version. A Live read
takes the newest partition (snapshot) or each entity's newest row (effective). *Known at* reads the table version
current at that instant: a correction committed later is not seen. Reverse lookups index every string value in a
document that looks like an identifier (capital letters and digits with at least one dash, such as `CP-NORTHBRIDGE`
or `NS-NORTH-01`, up to 64 characters).

**Try it.**

```bash
# every banking domain (reference, market, trading, risk, credit, collateral), ten business days
uv run --with deltalake --with pyarrow --with pyyaml python tools/packgen/banking/make_data.py --lake data/delta [--days 10]
# or one pack's samples into one domain (as the finance pack expects)
uv run --with deltalake --with pyarrow python tools/samplegen/lake.py \
    --samples packs/finance/samples --root data/delta --domain finance --days 10 [--as-of 2026-09-30] [--calendar USNY]
DRISHTI_PACKS=trading java -jar drishti-server/target/drishti-server-*-exec.jar
```

`lake.py` writes the newest date twice (the second commit restates one document per kind), so *known at* before
that commit shows the original. It writes to local disk only; for S3 build locally and copy the folders up
(`aws s3 sync data/delta/trading s3://risk-lake/banking/trading`), bearing in mind that time travel then resolves
against the upload times. Keep a lake bounded with `tools/lake/maintain.py --config deploy/lake-maintenance.yaml
--once` (see OPERATIONS).

**What the user sees.** `TRD MX-20000001 <GO>`, then a date in the top bar; *Raw JSON* shows `businessDate`.
Provenance `trading-store`. Health: `UP`, `DOWN: cannot reach <root>/<domain>`, or `DOWN: no Delta tables under …`.

---

## aerospike

**What it is for.** A key-value store with sub-millisecond reads, for sites that keep their data in Aerospike, sized
for millions of entities a day kept for years. Each data domain has a record per entity per business date, an index
record per entity listing its dates, and a record per kind listing the kind's dates, so a dated read is two key
lookups. The scaling design is in [AEROSPIKE_CONNECTOR.md](AEROSPIKE_CONNECTOR.md).

**Configuration.** The `aerospike` profile (`application-aerospike.yaml`) points the banking packs' `<domain>-store`
connectors at Aerospike, keeping each pack's kinds, routes, modes, `domain` (which becomes the set) and `layout` (the
promoted bins):

```yaml
drishti:
  sources:
    connectors:
      trading-store:
        plugin: aerospike                                            # replaces the pack's "delta"
        settings:
          hosts: "${DRISHTI_AEROSPIKE_HOSTS:localhost:3000}"         # host:port, comma-separated
          namespace: "${DRISHTI_AEROSPIKE_NAMESPACE:test}"
          # set: not given, so the pack's `domain: trading` is the set
```

As a pack's own connector:

```yaml
connectors:
  trading-store:
    plugin: aerospike
    kinds: [trade]
    settings:
      hosts: ${DRISHTI_AEROSPIKE_HOSTS:localhost:3000}
      namespace: ${DRISHTI_AEROSPIKE_NAMESPACE:test}
      set: trading
      layout:
        trade:
          columns: [tradeId, mtm, book, desk, counterparty.id, counterparty.name, nettingSet]   # promoted bins
      refresh-seconds: 60          # re-read the kinds' dates and the ids
routes:
  trade: trading-store
```

**Settings.**

| Key | Default | Meaning |
|---|---|---|
| `hosts` | `localhost:3000` | seed hosts (`host:port`, comma-separated; port 3000 when omitted) |
| `namespace` | `test` | namespace |
| `set` | the `domain` setting, else `drishti` | the domain's set; the index and kinds sets are `<set>_ix` and `<set>_kinds` |
| `domain` | — | used as the set when `set` is not given |
| `kinds` | what the kinds set lists | comma list of kinds served |
| `mode.<kind>` | `snapshot` | `snapshot` or `effective` |
| `lookback-days` | `10` | snapshot kinds: how far back a picked date may fall |
| `refresh-seconds` | `60` | how often the kinds' dates and the ids (the index set) are re-read |
| `layout.<kind>.columns` | — | the fields promoted to bins; searches, pick lists, derived kinds, impact and reverse lookups read them |
| `scan-threads` | `8` | partition ranges scanned at once when a day is scanned |
| `columns-cache-mb` | `1024` | memory for days of promoted bins |
| `columns-seconds` | `300` | a day's bins are read again after this |
| `reverse-index` | `true` | `false` turns reverse lookups off |
| `max-load-rows` | `200000` | record limit of a reverse-lookup scan over documents (kinds without promoted bins) |
| `user`, `password` | — | security-enabled clusters |
| `connect-timeout-ms` | `3000` | client connect timeout |
| `source-name` | `aerospike` (a connector: its name) | provenance source |

**The data.** Three sets per domain:

```text
namespace test · set trading · key "trade/MX-20000001/20260930"
  kind = "trade" · id = "MX-20000001" · date = 20260930
  doc  = "{\"tradeId\": \"MX-20000001\", …, \"mtm\": 1875863, …}"
  mtm = 1875863.0 · book = "BOOK-RATES-3" · counterparty_id = "CP-MERIDIAN-RE"
  counterpar_f5fe = "Meridian Reinsurance Ltd" · nettingSet = "NS-MERIDIAN-RE-NY" · …

namespace test · set trading_ix · key "trade/MX-20000001"
  kind = "trade" · id = "MX-20000001" · dates = [20260917, 20260918, …, 20260930]

namespace test · set trading_kinds · key "trade"
  kind = "trade" · dates = [20260917, 20260918, …, 20260930]
```

The day record holds `kind`, `id`, `date` (yyyyMMdd as a number), `doc` (the JSON document as text) and one bin per
promoted field (numbers as doubles, everything else as text). A bin name is at most 15 characters: the path with dots
as underscores, or, when longer, its first 10 characters, an underscore and the first 4 hex digits of the path's
SHA-1 (`counterparty.name` is `counterpar_f5fe`). The index record's `dates` are sorted and unique.

A read gets the index record, picks the day and gets that day's record. A snapshot kind's date is the kind's newest
date on or before the one asked (within `lookback-days`), and an entity without a record that day is gone; an
effective kind takes the entity's own newest date on or before it. Generation is the day record's generation.
Type-ahead comes from the index set's `kind` and `id` bins, re-read every `refresh-seconds`. Searches, pick lists,
derived kinds, impact and reverse lookups of a snapshot kind read one day's promoted bins with partition scans the
server filters by `kind` and `date`, at most two at a time, kept in memory (`columns-cache-mb`, `columns-seconds`);
the newest day is read in the background after each refresh. Reverse lookups match the target's id against the
promoted text bins (`nettingSet`, `book`, `counterparty.id`); a kind without promoted bins, or an effective kind,
scans documents for identifiers instead (same rule as Delta), up to `max-load-rows` records.

**Try it.**

```bash
docker compose -f deploy/compose.data.yaml up -d aerospike
tools/load-aerospike.sh localhost:3000 test        # writes data/banking.jsonl, then loads it (needs ./mvnw, offline)
tools/load-aerospike.sh localhost:3000 test --trades 1000000 --days 3 --ttl-days 30   # and a million trades a day
SPRING_PROFILES_ACTIVE=aerospike DRISHTI_PACKS=trading java -jar drishti-server/target/drishti-server-*-exec.jar
```

`tools/load-aerospike.sh [hosts] [namespace] [--ttl-days N] [--trades N --days D]` runs
`make_data.py --jsonl data/banking.jsonl` and then `AerospikeLoader`; with `--trades` it also streams
`bulk_trades.py --jsonl -` into a second loader on standard input. The loader reads lines
`{"domain", "kind", "id", "date", "doc", "columns"}` (`columns`: the promoted values by path) from a file or `-`,
writes each day record into the set named by `domain` and adds the date to the entity's index record, 128 writes in
flight, then records each kind's dates once. `--ttl-days N` lets Aerospike expire records after N days (retention
without a maintenance job); without it they never expire. Your own loader can write the same layout
(`AerospikeLayout`).

**What the user sees.** `TRD MX-20000001 <GO>`, dated. Health: `UP` or `DOWN: not connected to Aerospike`; the cache
figures show `kinds`, `datesIndexed`, `ids` and `columnSets`, and a purge forgets the promoted bins and refreshes.

---

## kafka

**What it is for.** Live entities from a stream: each message is an entity's latest state, views tick as messages
arrive. The topic is the state (compacted-topic semantics), so every server reads it from the beginning and needs no
consumer group. Pair it with a dated store for history.

**Configuration.** `packs/trading/pack.yaml`, as shipped:

```yaml
connectors:
  trading-stream:
    plugin: kafka
    enabled: ${DRISHTI_STREAM_TRADING:false}                 # off until switched on
    kinds:
    - trade
    settings:
      bootstrap-servers: ${DRISHTI_KAFKA_BOOTSTRAP:localhost:9092}
      topics: ${DRISHTI_TRADING_TOPIC:drishti.trading.trades}
      kind: trade                                            # every topic carries trade documents (mapped messages)
      id-field: tradeId                                      # the document's id field (when a message has no key)
      disk-cache.enabled: ${DRISHTI_STREAM_DISK_CACHE:true}  # keep the day's documents on local disk
      disk-cache.root: ${DRISHTI_CACHE_ROOT:./data/cache}    # → ./data/cache/trading-stream
      disk-cache.max-gb: ${DRISHTI_STREAM_CACHE_GB:10}
      disk-cache.reset-at: ${DRISHTI_CACHE_RESET_AT:02:00}   # cleared nightly
      disk-cache.zone: America/New_York
```

A site connector with envelopes on several topics and a secured cluster:

```yaml
# application.local.yaml
drishti:
  sources:
    connectors:
      risk-stream:
        plugin: kafka
        settings:
          bootstrap-servers: kafka1.bank.example:9093,kafka2.bank.example:9093
          topics: risk.exposures,risk.limits                 # no kind: messages are envelopes
          kind.risk.limits: credit-limit                     # except this topic: whole documents of one kind
          id-field.risk.limits: limitId
          cache-mb: 512
          client.security.protocol: SASL_SSL                 # any consumer property: client.<property>
          client.sasl.mechanism: SCRAM-SHA-512
          client.sasl.jaas.config: "${KAFKA_JAAS}"
```

**Settings.**

| Key | Default | Meaning |
|---|---|---|
| `bootstrap-servers` | `localhost:9092` | brokers |
| `topics` | — (required) | comma list |
| `kind` | — | mapped messages on every topic: their kind |
| `kind.<topic>` | — | mapped messages on that topic |
| `id-field` | `id` | mapped messages: the id field, for every topic |
| `id-field.<topic>` | — | the id field on that topic |
| `mode` | `state` | `state` keeps an index and serves reads; `ticks` keeps nothing and serves no reads, but pushes every message to open views of its kinds that a store answers (the plugin's `pushes(ref)` is true, so those views are live; see CONNECTOR_GUIDE.md) |
| `cache-mb` | `256` | recently read documents in memory (a miss reads the one record back from Kafka by offset) |
| `search` | `true` | keep ids for type-ahead (`state` mode only) |
| `poll-ms` | `200` | poll interval |
| `source-name` | `kafka` (a connector: its name) | provenance source and client id `drishti-<source-name>` |
| `client.<property>` | — | any Kafka consumer property; auto-commit is always off |
| `disk-cache.enabled` | `false` | also keep each document in a RocksDB store on local disk (`state` mode) |
| `disk-cache.dir` | `<disk-cache.root>/<source-name>` | the store's folder |
| `disk-cache.root` | `./data/cache` | parent of the default folder |
| `disk-cache.max-gb` | `10` | disk budget; the oldest data goes first beyond it |
| `disk-cache.reset-at` | `02:00` | daily clearing time (`never` or blank: never) |
| `disk-cache.zone` | `America/New_York` | the zone of `reset-at` |

The disk cache starts empty on every run (the topic is replayed anyway); it saves re-reading Kafka for documents
not in memory.

**The data.** Two message shapes. Values are JSON text; keys are strings.

*Mapped* (a kind configured for the topic): the value is the whole document; the id is the message key, or, for a
message without a key, the `id-field`. Keep the key equal to the id.

```text
key:   MX-20000001
value: {"tradeId": "MX-20000001", "productType": "IRS", "mtm": 1875863, "pnl1d": 4120, "notional": 242000000.0, …}
```

*Envelope* (no kind for the topic): the value carries kind, id and document. Key it `<kind>/<id>`.

```text
key:   netting-set/NS-NORTH-01
value: {"kind": "netting-set", "id": "NS-NORTH-01", "doc": {"nettingSetId": "NS-NORTH-01", "netMtm": -1200000, …}}
```

A **tombstone** (null value) deletes: mapped, keyed by the id; envelope, keyed `<kind>/<id>`. An envelope with
`"doc": null` deletes too. A message that is not JSON is skipped. The generation is the offset, the fetch time the
message timestamp, and documents are live and undated. Health is `UP (catching up)` until the end offsets seen at
start are reached, then `UP`; `DOWN: no connection to the broker (reconnecting)` once the broker has been unreachable
for 10 s, and `UP` again when it is back.

**Try it.**

```bash
docker compose -f deploy/compose.data.yaml up -d kafka            # single-node KRaft broker, topics compacted
uv run --with kafka-python python tools/samplegen/stream.py \
    [--bootstrap localhost:9092] [--topic drishti.trading.trades] \
    [--samples packs/trading/samples/trade] [--rate 5] [--seconds 0]
DRISHTI_PACKS=trading DRISHTI_STREAM_TRADING=true DRISHTI_DEMO_ENABLED=false java -jar drishti-server/target/drishti-server-*-exec.jar
```

`stream.py` publishes every sample trade once (keyed by `tradeId`), then gives `--rate` random trades per second a
new `mtm` and `pnl1d`; `--seconds 0` runs until interrupted. To send one message by hand:

```bash
docker compose -f deploy/compose.data.yaml exec kafka /opt/kafka/bin/kafka-console-producer.sh \
    --bootstrap-server localhost:9092 --topic drishti.trading.trades --property parse.key=true --property key.separator='|'
MX-20000001|{"tradeId":"MX-20000001","productType":"IRS","mtm":1900000,"notional":242000000.0}
```

**What the user sees.** `TRD MX-20000001 <GO>` on Live: provenance `trading-stream`, live, and the MTM ticks. The
trading pack's samples hold the same trade ids, so start the server with `DRISHTI_DEMO_ENABLED=false` to have the
ticks come from Kafka rather than from the samples' random walk (see *Routing order*). Pick a date and the same
trade comes from `trading-store` (the lake), because dated sources go first for a picked date. Health:
`trading-stream` with `indexedEntities`, `memoryMb` and, with the disk cache, `diskMb`, `diskHits`, `diskClears`.

---

## activemq

**What it is for.** Live entities pushed by systems that publish to ActiveMQ Classic (OpenWire) queues or topics.
The connector keeps each entity's latest document itself (see [Message queues](#message-queues-activemq-rabbitmq)),
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

| Key | Default | Meaning |
|---|---|---|
| `broker-url` | `failover:(tcp://localhost:61616)?initialReconnectDelay=1000&maxReconnectDelay=30000` | OpenWire URL |
| `user`, `password` | — | credentials |
| `destinations` | — (required) | comma list of `queue:<name>`, `topic:<name>` or `<name>` (a queue) |
| `client-id` | `drishti-<source-name>` | JMS client id; topics are durable subscriptions named `drishti-<source-name>-<topic>` |
| shared message settings | | `kind`, `kind.<destination>`, `id-field`, `id-field.<destination>`, `cache-mb`, `state.*`, `source-name`: [below](#shared-message-queue-settings) |

ActiveMQ refuses a second connection with the same client id, so two Drishti servers reading the same broker need
different `client-id`s (and then each has its own durable subscription).

**The data.** A `TextMessage` (or `BytesMessage`, read as UTF-8) whose body is JSON; optional string properties
`id` and `deleted`. On `orders` above (mapped):

```text
destination: queue://orders
property id: O-55120                       (optional; else the body's orderId)
body:        {"orderId": "O-55120", "side": "BUY", "instrument": "EQ-NVTK", "qty": 2500, "status": "WORKING"}
```

On a destination without a kind, the body is an envelope `{"kind": "order", "id": "O-55120", "doc": {…}}`. A
message with property `deleted=true` or `"doc": null` deletes (an empty body deletes only on a destination with a kind,
with the `id` header naming the entity). Generation is a counter that rises with
every message; documents are live and undated.

**Try it.**

```bash
docker run -d --name amq -p 61616:61616 -p 8161:8161 apache/activemq-classic
# switch the connector on, start the server, then send a message from the web console
# (http://localhost:8161/admin, admin/admin → Queues → orders → Send To) with the JSON body above
```

**What the user sees.** `<mnemonic for order> O-55120 <GO>`, live; the view updates on every message. Health:
`UP`, `DOWN: connection to the broker lost (reconnecting)`; cache figures `entities`, `memoryEntries`, `stateMb`,
`received`, `rejected`.

---

## rabbitmq

**What it is for.** The same as ActiveMQ, for RabbitMQ (AMQP 0-9-1) queues, optionally declared and bound to an
exchange by Drishti.

**Configuration.**

```yaml
connectors:
  desk-orders:
    plugin: rabbitmq
    kinds: [order]
    settings:
      uri: ${RABBIT_URI:amqp://guest:guest@localhost:5672/%2f}
      queues: drishti.orders                               # comma list
      declare: true                                        # declare each queue durable (and bind it)
      bind.drishti.orders: orders.exchange:orders.#        # exchange:routing.key
      kind.drishti.orders: order
      id-field.drishti.orders: orderId
      prefetch: 100
```

**Settings.**

| Key | Default | Meaning |
|---|---|---|
| `uri` | `amqp://guest:guest@localhost:5672/%2f` | AMQP URI (`amqps://` for TLS) |
| `queues` | empty | comma list of queues to consume |
| `declare` | `true` | declare each queue durable; `false` uses existing queues and ignores `bind.*` |
| `bind.<queue>` | — | `exchange:routing.key` binding for a declared queue |
| `prefetch` | `100` | unacknowledged messages in flight |
| `recovery-interval-ms` | `2000` | automatic recovery interval |
| `heartbeat-seconds` | `20` | requested heartbeat |
| shared message settings | | [below](#shared-message-queue-settings) |

**The data.** The body is JSON (UTF-8). The id is the `id` header, else the message's `message_id` property, else
the `id-field`; a `deleted` header of `true` deletes.

```text
queue:    drishti.orders
headers:  {"id": "O-55120"}                 (optional)
body:     {"orderId": "O-55120", "side": "BUY", "instrument": "EQ-NVTK", "qty": 2500, "status": "WORKING"}
```

**Try it.**

```bash
docker run -d --name rabbit -p 5672:5672 -p 15672:15672 rabbitmq:management
# start the server with the connector (it declares drishti.orders), then publish through the management API:
curl -u guest:guest -H 'content-type: application/json' -X POST \
  http://localhost:15672/api/exchanges/%2f/amq.default/publish \
  -d '{"routing_key":"drishti.orders","properties":{"headers":{"id":"O-55120"}},"payload_encoding":"string",
       "payload":"{\"orderId\":\"O-55120\",\"side\":\"BUY\",\"qty\":2500,\"status\":\"WORKING\"}"}'
```

**What the user sees.** As ActiveMQ. Health: `UP`, `DOWN: connection lost (recovering)`, or
`DOWN: consumer cancelled on <queue>` (for example, the queue was deleted).

### Shared message-queue settings

Read by both `activemq` and `rabbitmq` (`MessageStateSource` in `drishti-messaging`):

| Key | Default | Meaning |
|---|---|---|
| `kind` | — | every destination carries documents of this kind |
| `kind.<destination>` | — | that destination's kind (otherwise messages are envelopes) |
| `id-field` | `id` | the id field, for every destination |
| `id-field.<destination>` | — | the id field on that destination |
| `cache-mb` | `128` | recent documents in memory |
| `state.dir` | `<state.root>/<source-name>` | the RocksDB state store's folder |
| `state.root` | `./data/state` | parent of the default folder |
| `state.max-gb` | `10` | disk budget; beyond it the oldest files go |
| `state.reset-at` | `never` | a daily clearing time (`HH:mm`), for state that should start empty each day |
| `state.zone` | `America/New_York` | the zone of `state.reset-at` |
| `source-name` | `activemq` / `rabbitmq` (a connector: its name) | provenance source; also names the default folder |

---

## s3

**What it is for.** Documents written as JSON files to Amazon S3 or an S3-compatible store (MinIO, Ceph, an
on-premises appliance): the `file` plugin's layout, in a bucket. Optionally dated.

**Configuration.**

```yaml
# packs/<pack>/pack.yaml
connectors:
  risk-docs:
    plugin: s3
    enabled: ${DRISHTI_RISK_DOCS:false}
    kinds: [stress-result]
    settings:
      bucket: ${RISK_DOCS_BUCKET:risk-docs}
      prefix: eod/                          # optional; a trailing / is added
      region: us-east-1
      # endpoint: http://localhost:9000     # an S3-compatible store; path-style addressing
      # access-key: ${S3_ACCESS_KEY}        # otherwise the AWS chain (environment, profile, instance role)
      # secret-key: ${S3_SECRET_KEY}
      rescan-seconds: 60
routes:
  stress-result: risk-docs
```

The shipped `application.yaml` also has `plugins.s3` (switched off by `DRISHTI_S3_ENABLED`); running it as itself
takes the same settings under `drishti.sources.plugins.s3.settings`.

**Settings.**

| Key | Default | Meaning |
|---|---|---|
| `bucket` | — (required) | bucket |
| `prefix` | empty | key prefix |
| `region` | `us-east-1` | region |
| `endpoint` | — | an S3-compatible endpoint |
| `path-style` | `true` | path-style addressing, with `endpoint` |
| `access-key`, `secret-key` | — | static credentials; otherwise the AWS credential chain |
| `rescan-seconds` | `60` | list kinds, ids and dated folders |
| `cache-seconds` | `30` | how long a read (or a miss) is cached |
| `cache-entries` | `10000` | cached objects |
| `lookback-days` | `10` | a dated folder older than the picked date minus this is not used |
| `source-name` | `s3` (a connector: its name) | provenance source |

**The data.**

```text
s3://risk-docs/eod/stress-result/ST-2026-Q3.json                undated
s3://risk-docs/eod/2026-09-30/stress-result/ST-2026-Q3.json     for 30 September
```

Only `.json` objects; the kind is the folder, the id the file name. Dates work as in `file`. The generation is the
object's last-modified time. The kinds served are those found by the last listing (until the first listing
succeeds, any kind). A miss is cached too, so a new object is found within `cache-seconds`.

**Try it.**

```bash
docker run -d --name minio -p 9000:9000 -p 9001:9001 minio/minio server /data --console-address :9001
export AWS_ACCESS_KEY_ID=minioadmin AWS_SECRET_ACCESS_KEY=minioadmin
aws --endpoint-url http://localhost:9000 s3 mb s3://risk-docs
aws --endpoint-url http://localhost:9000 s3 cp ST-2026-Q3.json s3://risk-docs/eod/2026-09-30/stress-result/ST-2026-Q3.json
# connector settings: endpoint: http://localhost:9000 (credentials from the same environment variables)
```

**What the user sees.** `<mnemonic> ST-2026-Q3 <GO>`, dated by folder. Health: `UP` or
`DOWN: <exception>: <message> (retrying)`; cache figures `cachedObjects`, `indexed`, `datedFolders`.

---

## feed

**What it is for.** Real public market data next to the samples: NY Fed SOFR, ECB €STR, ECB reference FX rates, US
Treasury par yields and FRED series. Each feed is one connector, fetched at start and every `refresh-minutes`, with
its recent history kept for picked dates. Entity ids name the feed, so real data never collides with sample ids.

**Configuration.** `packs/market-data/pack.yaml` declares all five, each off until its variable is set:

```yaml
connectors:
  nyfed-sofr-feed:
    plugin: feed
    enabled: ${DRISHTI_FEED_NYFED_SOFR:false}
    kinds:
    - rate-fixing
    settings:
      feed: nyfed-sofr                 # which feed
      refresh-minutes: 60
  ecb-estr-feed:
    plugin: feed
    enabled: ${DRISHTI_FEED_ECB_ESTR:false}
    kinds: [rate-fixing]
    settings: { feed: ecb-estr, refresh-minutes: 60 }
  ecb-fx-feed:
    plugin: feed
    enabled: ${DRISHTI_FEED_ECB_FX:false}
    kinds: [fx-spot]
    settings: { feed: ecb-fx, refresh-minutes: 60 }
  us-treasury-feed:
    plugin: feed
    enabled: ${DRISHTI_FEED_US_TREASURY:false}
    kinds: [ir-curve]
    settings: { feed: us-treasury, refresh-minutes: 60 }
  fred-feed:
    plugin: feed
    enabled: ${DRISHTI_FEED_FRED:false}
    kinds: [rate-fixing]
    settings:
      feed: fred
      refresh-minutes: 60
      api-key: ${FRED_API_KEY:}                    # a free key from the St. Louis Fed
      series: ${DRISHTI_FRED_SERIES:DGS10,DFF}     # FRED series ids
```

A site behind a proxy or without internet points a feed at a mirror or a file:

```yaml
drishti:
  sources:
    connectors:
      nyfed-sofr-feed:
        enabled: true
        settings:
          url: file:///srv/mirror/sofr-last-60.json   # read directly; same format as the public API
```

**Settings.**

| Key | Default | Meaning |
|---|---|---|
| `feed` | — (required) | `nyfed-sofr`, `ecb-estr`, `ecb-fx`, `us-treasury`, `fred` |
| `refresh-minutes` | `60` | refetch interval |
| `timeout-seconds` | `20` | HTTP request timeout |
| `url` | the public URL | override; `file:` URLs are read directly; for `us-treasury` and `fred`, a comma list (one per month or series) |
| `api-key` | empty | FRED |
| `series` | `DGS10,DFF` | FRED series, in the same order as a `url` list |
| `source-name` | the feed's name (a connector: its name) | provenance source |

**The data.**

| Feed | Fetched from | Kind and ids | History kept |
|---|---|---|---|
| `nyfed-sofr` | `markets.newyorkfed.org/api/rates/secured/sofr/last/60.json` | `rate-fixing` `FIX-SOFR-NYFED` | last 60 fixings |
| `ecb-estr` | `data-api.ecb.europa.eu/service/data/EST/B.EU000A2X2A25.WT?lastNObservations=60&format=csvdata` | `rate-fixing` `FIX-ESTR-ECB` | last 60 |
| `ecb-fx` | `www.ecb.europa.eu/stats/eurofxref/eurofxref-hist-90d.xml` | `fx-spot` `FX-EURUSD-ECB`, `FX-EURGBP-ECB`, `FX-EURJPY-ECB`, `FX-EURCHF-ECB`, `FX-GBPUSD-ECB`, `FX-USDJPY-ECB`, `FX-USDCHF-ECB`, `FX-AUDUSD-ECB`, `FX-USDCAD-ECB`, `FX-USDCNH-ECB` (crosses from the euro rates; CNY stands in for CNH) | 90 days |
| `us-treasury` | `home.treasury.gov` daily par yield curve XML, last month and this month | `ir-curve` `CRV-USD-UST` | about two months |
| `fred` | `api.stlouisfed.org/fred/series/observations?series_id=…` | `rate-fixing` `FIX-FRED-<series>` (`FIX-FRED-DGS10`) | last 60 per series |

A `rate-fixing` document (SOFR) as of a date holds up to the 20 most recent fixings on or before it:

```json
{ "indexId": "FIX-SOFR-NYFED", "name": "Secured Overnight Financing Rate (NY Fed)", "latest": 0.0431,
  "administrator": "Federal Reserve Bank of New York", "tenor": "Overnight", "currency": "USD",
  "fixings": [ { "date": "2026-09-29", "rate": 0.0431, "ratePct": 4.31, "volumeBn": 2512.0 }, … ] }
```

An `fx-spot` document has `pair`, `pairName`, `mid` (also as `bid` and `ask`), `change1d`, `spotDate`, 30 days of
`history` and `conventions`; the `ir-curve` document has `curveId`, `tenY`, `slope2s10s` (bp), `asOf` and `points`
(`tenor`, `maturity`, `quote`, `zeroRate`, `df`) from 1M to 30Y. The business date of a document is its latest
observation on or before the date asked; a date before the history kept is *not held* (the next source answers). A
failed fetch keeps the last good data.

**Try it.**

```bash
DRISHTI_PACKS=market-data DRISHTI_FEED_NYFED_SOFR=true DRISHTI_FEED_ECB_FX=true DRISHTI_FEED_US_TREASURY=true \
  java -jar drishti-server/target/drishti-server-*-exec.jar
# FRED also needs a key:  DRISHTI_FEED_FRED=true FRED_API_KEY=… [DRISHTI_FRED_SERIES=DGS10,DFF,SOFR]
```

**What the user sees.** `FIX FIX-SOFR-NYFED <GO>`, `FX FX-EURUSD-ECB <GO>`, `CRV CRV-USD-UST <GO>` (the market-data
pack's mnemonics). `rate-fixing`, `fx-spot` and `ir-curve` are routed to `market-store`; neither the lake nor
the samples hold these ids, so the read passes on until the feed connector answers. Search lists them with the
subtitle `<kind> · <connector> (public feed)`. Health: `UP`, `DOWN: not fetched yet`, `DOWN: the feed returned no
data`, or `DOWN: <exception>`; cache figures `series`, `observations`, `fetchedAt`; a purge refetches.

---

## A pack with several connectors

A pack for an equities desk, `packs/equity-desk/pack.yaml` (illustrative; the structure is exactly that of the
shipped `trading` pack), keeps history in Delta Lake and takes live orders from Kafka:

```yaml
pack: equity-desk
version: 1.0.0
title: Equity desk
extends:
- banking-core                 # counterparties, books, traders… and banking-core's reference-store connector
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
      root: ${DRISHTI_DELTA_ROOT:./data/delta}
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

**Inheritance.** Enabling `equity-desk` (`DRISHTI_PACKS=equity-desk`) loads `banking-core` and `market-data` too
(`extends:` and `requires:` both pull parents in), and every connector the three declare runs: `reference-store`,
`market-store`, the five feeds (off unless switched on), `equity-store` and `equity-orders`. Where two packs declare
the same connector name (or route, mnemonic, field, badge or role):

- identically: nothing happens, it runs once;
- differently, and one pack inherits from the other (or a loaded pack inherits from both): the more specific one
  wins *as a whole* (the child over its parents, the rightmost parent over those before it, by C3 linearisation, as
  `PackLineage` computes), and the override is listed in health (`overrides`, from `drishti.packs.overrides`);
- differently, from unrelated packs: the server refuses to start, naming both packs.

So a child pack that redefines `market-store` replaces its whole definition (plugin, kinds and every setting); it
does not merge with the parent's.

**Site overrides.** The site's files sit above every pack and merge key by key, so a site changes only what it
names:

```yaml
# application.local.yaml
drishti:
  sources:
    connectors:
      equity-store:
        plugin: jdbc                               # same kinds, modes and route; a different store
        settings:
          url: jdbc:postgresql://pg.bank.example:5432/drishti
          user: ${PG_USER}
          password: ${PG_PASSWORD}
          table: equity.entities                   # the pack's root and domain stay, and jdbc ignores them
      equity-orders:
        enabled: true                              # the stream on, whatever DRISHTI_STREAM_EQUITY says
        settings:
          bootstrap-servers: kafka1.bank.example:9092
      fred-feed:
        enabled: false                             # a parent pack's connector, switched off for this site
    routes:
      position: equity-store                       # routes merge the same way
```

Remember that a site `kinds:` list replaces the pack's whole list, and that keys the new plugin does not read are
simply ignored (`root` and `domain` above; but `domain` *is* read by `aerospike`, as the set, and so is `layout`, as the promoted
bins, which is why the `aerospike` profile needs to give only `hosts` and `namespace`).
