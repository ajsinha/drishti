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
# Connectors: a developer's guide

This guide is for the person who connects Drishti to data: choosing among the fifteen connectors that ship, combining
and operating them, and, when none fits, writing a new one. It teaches. The exact settings, data layouts, health texts
and limits of each connector live in that connector's own document (`docs/connectors/<NAME>_CONNECTOR.md`), and this
guide links there instead of repeating them.

| If you want to… | Go to |
|---|---|
| understand the words (entity, kind, connector, route, dated, live) and how a read picks a connector | [Concepts in plain words](#concepts-in-plain-words) |
| pick a connector for your data | [Which connector should I use?](#which-connector-should-i-use) |
| see each shipped connector in one screen, with a minimal configuration | [The shipped connectors](#the-shipped-connectors) |
| write your own connector | [The SourcePlugin SPI](#the-sourceplugin-spi) and [A worked example](#a-worked-example-the-dayfolder-plugin) |
| run several connectors together, check them, fix them | [Combining connectors](#combining-connectors), [Operating connectors](#operating-connectors), [Troubleshooting](#troubleshooting) |

## Contents

1. [Concepts in plain words](#concepts-in-plain-words)
2. [Which connector should I use?](#which-connector-should-i-use)
3. [Before you start: the common steps](#before-you-start-the-common-steps)
4. [The SourcePlugin SPI](#the-sourceplugin-spi)
5. [Registration and discovery](#registration-and-discovery)
6. [Configuration binding](#configuration-binding)
7. [Reconnecting and failures](#reconnecting-and-failures)
8. [Testing with drishti-testkit](#testing-with-drishti-testkit)
9. [A worked example: the dayfolder plugin](#a-worked-example-the-dayfolder-plugin)
10. [The shipped connectors](#the-shipped-connectors)
11. [Combining connectors](#combining-connectors)
12. [Operating connectors](#operating-connectors)
13. [Troubleshooting](#troubleshooting)

---

## Concepts in plain words

### Entities, kinds and documents

Everything Drishti shows is an **entity**: a trade, a counterparty, a curve, a margin call. Each entity has a
**kind** (`trade`, `counterparty`, `ir-curve`) and an **id** (`MX-20000001`, `CP-ALDERSHOT`). The data behind an
entity is one **document**: a JSON object, as your system holds it. Drishti lays the document out on screen by
itself (and with a Sutra, if a pack ships one for the kind), so you do not reshape your data for Drishti; you only
tell Drishti where to find it.

A user opens an entity by typing `<MNEMONIC> <ID> <GO>` in the terminal, for example `TRD MX-20000001 <GO>`. The mnemonic
(`TRD`) comes from a pack and stands for a kind (`trade`). A connector can serve any kind, but a user can only type a
kind that some enabled pack gives a mnemonic (or an id pattern) to; through the API any kind can be read
(`GET /api/v1/views/{kind}/{id}`).

### Plugin, connector, pack connector, route

| Word | What it is | Example |
|---|---|---|
| **plugin** | a piece of software that knows how to talk to one *type* of store | `jdbc` talks to databases, `kafka` to Kafka |
| **connector** | one plugin pointed at one *particular* store, with a name and its own settings | `desk-db`: the `jdbc` plugin, pointed at `jdbc:postgresql://db.bank.example/desk` |
| **pack connector** | a connector declared by a pack in its `pack.yaml`, so it comes with the pack | the trading pack's `trading-store` (Delta Lake) and `trading-stream` (Kafka) |
| **site connector** | a connector declared in your site's `application.local.yaml` | anything you add yourself |
| **route** | "for this kind, ask this connector first" | `trade: trading-store` |
| **default route** | the connector asked second, for every kind | `demo` (the sample data), as shipped |

A plugin can also run **as itself**, without a connector name, under `drishti.sources.plugins.<plugin>`. That is how
the shipped `demo` and `file` run. It is fine for one store; for anything more, use named connectors: you can run the
same plugin several times, each connector shows under its own name in health, and documents say which connector they
came from.

### Dated and undated; snapshot and effective

A **dated** connector keeps history: ask it for 29 September and it answers with the data for 29 September. Delta
Lake, the database table mode, Aerospike, dated folders on disk or in S3, and the public feeds are dated. An
**undated** connector only knows "now": a REST service, a Kafka topic, a message queue.

Dated stores keep each kind in one of two ways:

| Mode | Stored | A read for a date returns |
|---|---|---|
| `snapshot` (the default) | every entity, in full, for every business date | the newest date on or before the one asked, but no older than `lookback-days` (10); an entity missing from that date is gone |
| `effective` | a row only when the entity changes (reference data: counterparties, books) | each entity's last row on or before the date, however old |

### Live and picked dates

The top bar of the console has a business date. By default it shows **Live** (the current business date, latest
version): views can tick as new data arrives. When a user picks an earlier date, or the current date explicitly, the
view is a **static snapshot** for that date. API callers choose the same way with `X-Drishti-As-Of: 2026-09-29` or
`?asOf=2026-09-29`; `X-Drishti-Known-At` / `?knownAt=` asks for the data *as it was known* at an instant (before
later corrections; Delta Lake and Iceberg only: other dated connectors refuse it with `DRS-1007`).

A **live** connector pushes changes to open views (Kafka, ActiveMQ, RabbitMQ, and the demo samples). The others are
read when a view opens.

### How a request picks a connector

This is the single most useful thing to understand. For a read of `<kind>/<id>`, Drishti
(`SourceRouter` in `drishti-engine`) makes a list of candidates:

1. the kind's **route**, if it names a running connector that serves the kind;
2. the **default route** (`demo`), if it serves the kind;
3. **every other** running connector that serves the kind, **in the order they are written in the config** (the order of the keys under `drishti.sources.connectors`, kept after a reload; with packs, pack order then the order inside each pack). A connector with no `kinds` list serves *every* kind
   (the shipped `demo` and `file` do; so does a Kafka or message-queue connector before its first message, unless you
   give it `kinds`).

Then it re-orders the list, keeping the order inside each group:

- **For a picked date:** dated connectors first, then undated ones. History comes from history.
- **For Live:** live connectors first, and among them a real stream before the default route's samples; then the
  rest.

Drishti asks the candidates one by one. A connector that does not hold the entity says so and the next is asked. The
first that holds it answers. A connector that **holds the date** is authoritative for it: an entity it does not list
for that date is not held, and the connectors behind it are not asked (`DRS-1001 recent-files holds trade for
2026-09-29 and does not list trade/MX-30000006`). A recent store that dropped a trade is not overruled by the lake
behind it, and the view agrees with a search of the same date. Only a date the connector does not hold passes on (a
recent store of the last weeks in front of years in a lake). Which connectors can tell: the file connector over dated
JSON-lines snapshots (a date is held when a day's file serves it, within `lookback-days`); others cannot yet tell, and
an entity they do not hold passes to the next as before (see [`coverage`](#coveragekind-asof-which-dates-you-hold) in the SPI). A connector
that **fails** (the database is down, the service answers 500, a file or
table it holds cannot be read, a store it has not reached yet so it cannot tell what it holds) stops the read with
`DRS-1003 <connector> failed reading <kind>/<id>`: Drishti does not silently show you another store's data instead.
When the connector says what to do (a Delta date in a codec its engine does not read), the error says so too
(`…: trade 2026-09-30 cannot be read: …; rewrite the date with Snappy or ZSTD …`). The whole read must finish
within `drishti.sources.fetch-timeout` (2 s) or it ends with `DRS-1004`. If nobody holds the entity the answer is
`DRS-1001`; if no connector serves the kind at all, `DRS-1002` (rare, because `demo` and `file` serve every kind).
A read *as known at* an instant (`knownAt`) is only put to connectors that keep earlier versions (Delta Lake,
Iceberg). A dated connector without them that may hold the date stops the read with `DRS-1007` (400) naming it
(`recent-files keeps no earlier versions, so a read 'as known at' an instant cannot be answered from it …`), rather
than showing today's data as if it were what was known then; one that does not hold the date is passed over.
Undated connectors have one version and are read as usual (the view says the data is current).

Live updates follow the read: the ticks come from the first live connector that holds the entity, in the same order
(a real stream before the samples). So if a trade comes from Kafka, it ticks from Kafka.

Search (the type-ahead under the command line) asks every connector that supports search and merges the hits.
A structured search (`TRD where …`) lists the kind the same way and reads what it lists, in read order up to the
first connector that holds the date (the ones behind it are not listed, as they are not read); a connector that fails, does
not answer within the search budget, says its list of the kind is incomplete (a table it could not index), or cannot
answer a *known at* search, makes the answer `partial: true` and is named in it with why (`failed`), so a failure
never reads as "nothing matched".
Reverse lookups (what refers to this entity) ask every connector that supports them.

**A real example.** On a server running the trading pack with its Delta Lake and the demo samples on (and the Kafka
stream off), the same trade is read three ways:

```bash
B=http://localhost:18480/api/v1
curl -s $B/entities/trade/MX-20000001/raw | jq -c .provenance
curl -s "$B/entities/trade/MX-20000001/raw?asOf=2026-09-29" | jq -c .provenance
curl -s "$B/entities/trade/MX-20000001/raw?asOf=2026-08-01" | jq -c .provenance
```

You should see:

```json
{"source":"murex-rates","generation":2,"fetchedAt":"2026-10-01T01:57:52.264798849Z","live":true,"businessDate":null}
{"source":"trading-store","generation":7,"fetchedAt":"2026-10-01T01:57:52.290912912Z","live":false,"businessDate":"2026-09-29"}
{"source":"murex-rates","generation":2,"fetchedAt":"2026-10-01T01:59:42.090962906Z","live":true,"businessDate":null}
```

- **Live**: the demo samples are live, so they go first; the sample's own source name (`murex-rates`) is shown.
- **29 September**: dated sources go first, so the lake (`trading-store`) answers, with `businessDate`.
- **1 August**: before the lake's first date (the sample lake holds ten business days), so the lake does not hold it
  and the undated samples answer. The console says so above the view:
  *No data held for 2026-08-01: the current data of murex-rates, a source that keeps no dates*

### Where configuration goes

| File | Who writes it | Precedence |
|---|---|---|
| `packs/<pack>/pack.yaml` → `connectors:` and `routes:` | the pack author | lowest |
| `drishti-server/src/main/resources/application.yaml` | shipped defaults | above the packs |
| `application-postgres.yaml`, `application-aerospike.yaml` | profiles (`SPRING_PROFILES_ACTIVE=postgres`) | above `application.yaml` |
| `./application.local.yaml` (git-ignored, in the folder you start the server from) | your site | above the shipped files |
| environment variables, `--key=value` on the command line | the operator | highest |

A site entry overrides a pack's **key by key**: you can change one setting of a pack connector without repeating the
rest. A **list** (such as `kinds:`) is replaced as a whole. Values like `${DRISHTI_DELTA_ROOT:./data/delta}` are
placeholders: the environment variable if set, else the default after the colon. They work in packs and site files
alike, and are how secrets stay out of files.

---

## Which connector should I use?

### By situation

| Your data is… | Use | Why | Watch out for |
|---|---|---|---|
| JSON or CSV files dropped in a folder by a nightly job | [`file`](#file) | no infrastructure; one folder per business date gives history | search sees new files after `rescan-seconds`; no live updates |
| behind an in-house HTTP/JSON API, one entity per GET | [`rest`](#rest) | uses the API the owning system already has | undated, no search, no reverse lookups; every view is a request to that service |
| in an existing database schema | [`jdbc` query mode](#jdbc-query-mode) | one SQL statement per kind; your tables stay as they are | no search or reverse lookups; you write the SQL for dates |
| loaded by you into PostgreSQL for Drishti | [`jdbc` table mode](#jdbc-table-mode) | one table per data domain, dated, search and reverse lookups from SQL | you run the load; size the indexes |
| in a data lake (Delta Lake on disk, S3, MinIO) | [`delta`](#delta) | the shipped default; years of history, *known at* time travel, search, reverse lookups, no Spark | partitions by `business_date`; keep it bounded with `tools/lake/maintain.py` |
| in Aerospike | [`aerospike`](#aerospike) | sub-millisecond key reads (two per view), millions of entities a day, retention by record TTL | searches and reverse lookups need the pack's promoted fields (`layout`) |
| on Kafka topics (latest state per entity) | [`kafka`](#kafka) | live views that tick; the topic is the state | undated: pair it with a lake for history |
| on ActiveMQ queues or topics | [`activemq`](#activemq) | live; the connector keeps the latest document per entity on local disk | a queue delivers once: the local state store is the only copy |
| on RabbitMQ queues | [`rabbitmq`](#rabbitmq) | as ActiveMQ, AMQP 0-9-1 | as ActiveMQ |
| JSON objects in a bucket (S3, MinIO, Ceph) | [`s3`](#s3) | the `file` layout in object storage, dated by folder | cost of listing large buckets; reads cached `cache-seconds` |
| a total or summary of another kind (a book's P&L from its trades) | `derived` ([PACK_DEVELOPER_GUIDE.md](../guides/PACK_DEVELOPER_GUIDE.md#derived-kinds-entities-computed-from-other-kinds)) | computed by the server from the other kind, through whichever connectors serve it | reads every member once per `refresh`; keep `max-scan` above the member count |
| public rates and FX | [`feed`](#feed) | NY Fed SOFR, ECB €STR and FX, US Treasury curve, FRED; switched on by one variable each | needs internet (or a mirror); FRED needs a free key |

### What each connector can do

| Connector | History (picked dates) | Live ticks | Search | Reverse lookups | Long-lived connection | Local disk |
|---|---|---|---|---|---|---|
| `demo` | no | yes | yes | yes | — | — |
| `file` | yes, by folder | no | yes | yes (JSON lines) | — | — |
| `rest` | no | no | no | no | — | — |
| `jdbc` query mode | when a query uses `:asOf` | no | no | no | a small pool | — |
| `jdbc` table mode | yes (`snapshot`/`effective`) | no | yes | yes | a small pool | — |
| `delta` | yes (`snapshot`/`effective`, *known at*) | no | yes | yes | — | read cache in memory |
| `aerospike` | yes (`snapshot`/`effective`) | no | yes | yes, from promoted bins (`reverse-index`) | cluster client | promoted bins cached in memory |
| `iceberg` | yes (`snapshot`/`effective`, *known at*) | no | yes | yes, from promoted columns | — | read cache in memory |
| `duckdb` | yes (`snapshot`/`effective`) | no | yes | yes, from promoted columns | — (the file is read in-process) | the DuckDB file; a day's promoted columns cached in memory |
| `mongodb` | yes (`snapshot`/`effective`) | no | yes | yes, from promoted fields (`reverse-index`) | driver pool | promoted fields cached in memory |
| `redis` | recent days (`snapshot`/`effective`) | yes (`<domain>:changes`) | yes | yes, from promoted columns | Lettuce connection | everything in Redis memory |
| `kafka` | no | yes | yes (`state` mode) | no | consumer | optional disk cache |
| `activemq`, `rabbitmq` | no | yes | yes | no | broker connection | state store (required) |
| `s3` | yes, by folder | no | yes | no | — | — |
| `feed` | yes, recent history | no | yes | no | — | — |
| `derived` | yes, wherever its members have it | no | yes | no | — | computations cached per date |

**Rules of thumb.**

- **History lives in a dated store; "now" can live in a stream.** The banking packs ship exactly this: a Delta Lake
  connector per data domain, plus a Kafka connector for live trades (off until switched on). See
  [Combining connectors](#combining-connectors).
- **Reverse lookups** (the *Linked entities* panel, impact analysis with F8) need a connector that indexes references:
  Delta Lake, PostgreSQL table mode, Aerospike, or the demo samples.
- **Cost.** `rest` and `jdbc` query mode put one request on your system per view opened (plus linked entities).
  Delta Lake, S3 and Aerospike read from storage Drishti does not load. Kafka, ActiveMQ and RabbitMQ read everything
  that arrives, whether or not anyone is looking.

---

## Before you start: the common steps

Every chapter below uses the same few commands. They are collected here once.

**1. Build once** (from the repository root; see [GETTING_STARTED.md](../guides/GETTING_STARTED.md) for Java 25 and the
console's Python environment):

```bash
./mvnw -q package -DskipTests
```

**2. Choose the packs.** A pack gives your kinds their mnemonics and its connectors. The examples use the banking
packs; `counterparty-risk` pulls in `trading`, `market-data` and `banking-core`, and `market-risk` adds stress
results:

```bash
export DRISHTI_PACKS=counterparty-risk,market-risk
```

**3. Put site configuration in `application.local.yaml`** in the folder you start the server from (the repository
root when developing). It is git-ignored. The server imports it at start (`spring.config.import:
optional:file:./application.local.yaml`), so a missing file is fine. Every chapter shows the lines to add under:

```yaml
drishti:
  sources:
    connectors:
      # your connectors go here
    routes:
      # optional: kind -> connector
```

**4. Start the server**, then the console in a second terminal:

```bash
java -jar drishti-server/target/drishti-server-*-exec.jar
console/.venv/bin/python console/run_drishti_web.py
```

The server listens on `http://localhost:18480`, the console on `http://localhost:17480`. Configuration is read at
start: **after changing `application.local.yaml`, a pack or an environment variable, restart the server.**

**5. Check the connectors.** For anyone (security on or off):

```bash
curl -s http://localhost:18480/api/v1/sources | jq -c '.sources[] | {name, kinds, health}'
curl -s http://localhost:18480/api/v1/sources | jq '.failures'
```

For administrators (with security on, add `-H "Authorization: Bearer $TOKEN"`; see
[API_GUIDE.md](../guides/API_GUIDE.md#minting-a-token-for-a-script)):

```bash
curl -s http://localhost:18480/api/v1/admin/health | jq '{status, summary, failedToStart}'
curl -s http://localhost:18480/api/v1/admin/health | jq '.sources[] | select(.name=="trading-store")'
```

A healthy connector looks like this (real output from a running server):

```json
{
  "name": "trading-store",
  "version": "1.0",
  "status": "UP",
  "health": "UP",
  "kinds": ["trade"],
  "live": false,
  "dated": true,
  "search": true,
  "reads": { "reads": 14, "found": 5, "notHeld": 9, "errors": 0, "lastError": null, "lastErrorAt": null,
             "lastOkAt": "2026-10-01T01:48:56.510246522Z", "p50Ms": 2.938, "p99Ms": 72.586 },
  "cache": { "timeTravel": 0, "partitions": 3, "tables": 1 }
}
```

In the console the same information is **Admin → Health** (`/admin/health`): a table of connectors (Connector,
Status, Kinds, Reads, Errors, Last error, Cache), the packs with their connectors, and anything that *failed to
start*. It refreshes every 5 seconds.

**6. Read one entity directly** to see which connector answered, before you look at the console:

```bash
curl -s http://localhost:18480/api/v1/entities/<kind>/<id>/raw | jq -c '{ref, provenance}'
```

`provenance.source` is the connector (or, for the demo samples, the sample's own source name); `businessDate` is
the date the document is for (`null` for undated connectors); `live` says whether it can tick.

---

## The SourcePlugin SPI

A source plugin brings entity documents from one system into Drishti. Plugins depend only on `drishti-api` (no
Spring, no Jackson), so they stay small and cannot clash with the server's libraries. The interface is
`com.ash.drishti.api.SourcePlugin`; the types around it (`EntityRef`, `EntityDocument`, `AsOf`, `Provenance`,
`PluginManifest`, `SourceCapabilities`, `SourceContext`, `HitIndex`, `ColumnSet`, `DateCoverage`) are in the same
package. Only two methods are abstract beyond `manifest`/`start`/`fetch`: everything else has a default that says "I do
not do that", so a first plugin is three methods long.

```java
public interface SourcePlugin extends AutoCloseable {
    PluginManifest manifest();
    void start(SourceContext context) throws Exception;
    Optional<EntityDocument> fetch(EntityRef ref) throws Exception;
    default Optional<EntityDocument> fetch(EntityRef ref, AsOf asOf) throws Exception;
    default DateCoverage coverage(String kind, AsOf asOf);
    default boolean timeTravel();
    default Subscription subscribe(EntityRef ref, Consumer<EntityDocument> listener);
    default boolean pushes(EntityRef ref);
    default java.time.Instant lastUpdate();
    default java.util.Set<String> columnar(String kind);
    default Optional<ColumnSet> columns(String kind, Collection<String> paths, AsOf asOf) throws Exception;
    default List<EntityRef> reverse(EntityRef target, String kind);
    default List<EntityRef> reverse(EntityRef target, String kind, AsOf asOf);
    default List<EntityHit> search(String kind, String text, int limit);
    default List<EntityHit> search(String kind, String text, int limit, AsOf asOf);
    default Optional<String> listingProblem(String kind);
    default Map<String, Object> cacheStats();
    default void purgeCaches();
    default String health();
    default void close();
}
```

### The vocabulary

| Type | What it is |
|---|---|
| `EntityRef(kind, id)` | the address of one entity; both parts non-blank |
| `EntityDocument(ref, data, provenance, deleted)` | a document: `data` is a `DataNode` tree (object, array, value; `get`, `at(path)`, `asText`, `asDouble`), `deleted` marks a tombstone pushed to open views |
| `Provenance(source, generation, fetchedAt, live, businessDate)` | where the document came from; the console shows it in *How this view was built* |
| `AsOf(businessDate, knownAt, live)` | which data is wanted: `AsOf.LATEST` (live, current), `AsOf.of(date)` (a picked business date), optionally `knownAt` (as known at an instant, before later corrections) |
| `EntityHit(ref, title, subtitle)` | one line of type-ahead |

### `manifest()`: name, version, kinds, capabilities

```java
new PluginManifest("dayfolder", "1.0", kinds, new SourceCapabilities(live, reverseLookup, search, dated))
```

- `name` is the plugin's name, the value of `plugin:` in a connector. A plugin running as itself is also known by it.
- `kinds` lists the kinds served. An **empty set means any kind**: the plugin is a candidate for every read and
  answers "not held" for what it does not have. A plugin that learns its kinds from its store has an empty set until
  it has looked.
- `SourceCapabilities(live, reverseLookup, search, dated)` must be honest: the engine calls an optional method only
  when its capability is declared. `dated = true` means the plugin overrides the `AsOf` overloads and stamps
  `Provenance.businessDate`; dated sources always receive a concrete date.

### `start(SourceContext)`: settings, JSON parser, scheduler

`SourceContext` gives a plugin everything it may use from the host:

| Method | Use |
|---|---|
| `settings()` / `setting(key, fallback)` | the connector's flat `Map<String, String>`; `setting` returns the fallback for a missing or blank value. Nested maps in a pack arrive as dotted keys, lists as comma lists. Prefix families (`query.<kind>`, `mode.<kind>`, `header.<Name>`) are read by iterating the map |
| `parseJson(InputStream)` | the host's JSON parser, so the plugin carries none: returns a `DataNode` |
| `scheduler()` | a shared `ScheduledExecutorService` for background refreshes; cancel your tasks in `close()` |
| `reader()` | reads *other* kinds through the server's routing (`list`, `read`); for connectors built on others, such as `derived`. Use it when serving reads, not inside `start` (the router is built after the plugins start). It applies no entitlements: the server redacts what it serves |

`start` runs once. If the plugin cannot run without a setting, throw `PluginNotConfigured`: the plugin is then
*installed but not configured*, stays idle and is not counted as a failure (see
[Reconnecting and failures](#reconnecting-and-failures)). Any other exception fails the start and shows under
`failedToStart` in Admin → Health; the server still runs. **A store that is down at start should not fail the
start**: start a background task that retries and report `DOWN` through `health()` until it answers.

### `fetch(ref)` and `fetch(ref, asOf)`: reading one entity

```java
Optional<EntityDocument> fetch(EntityRef ref) throws Exception;
default Optional<EntityDocument> fetch(EntityRef ref, AsOf asOf) throws Exception { return fetch(ref); }
```

- Called concurrently from many virtual threads: it may block (JDBC, HTTP) but must be thread-safe.
- **Return `Optional.empty()` only when you know you do not hold the entity** (no row, no key, a date outside what you
  keep). Anything else throws: the store is down, a file is there but unreadable, a line does not parse, and also *not
  connected yet*. An empty answer for a failure would let the next store answer with its own, different data. See
  [Failure semantics](#failure-semantics).
- **Dated sources** override the second form, read the date from `asOf.businessDate()` and stamp
  `Provenance.businessDate` with the date the document is *for*, which may be earlier than the one asked (the last
  business day that has data). Undated sources need do nothing: the default ignores the date, and the view tells the
  user the data is current.
- `Provenance.source` is the name users see (take it from the `source-name` setting, defaulting to the connector's
  name). `generation` must be monotonic: higher means newer. A file's modification time, a table version, a counter
  or an ETag number all work.

### `coverage(kind, asOf)`: which dates you hold

`DateCoverage` is `HELD`, `NOT_HELD` or `UNKNOWN` (the default). It must be cheap: no remote call. A source that
reports `HELD` for a date is **authoritative** for it: an entity it does not list is not held, the read stops with
`DRS-1001` naming the source, the sources behind it are not asked, and a structured search lists the kind only up to
it, so views and searches agree. Only a date you report `NOT_HELD` passes on to older stores. When you cannot say,
return `UNKNOWN` and the next source is asked as before.

### `timeTravel()`: *known at* reads

Return `true` only if you keep earlier versions and honour `AsOf.knownAt` (Delta Lake, Iceberg). A read *as known at*
an instant is never put to a dated plugin that does not, and may hold the date: it ends with `DRS-1007` (400) naming
the source, and a search is partial.

### `search(kind, text, limit[, asOf])` and `HitIndex`

`search` powers the type-ahead under the command line and structured searches, so it **must answer from memory**: it
runs on every keystroke. `HitIndex` is a ready-made, lock-free index: call `replaceAll(hits)` after each scan,
`add`/`remove` for single changes, and `search(kind, text, limit)` ranks ids by prefix, then by containment, then by
title. Declare `search = true` in the capabilities. The four-argument form defaults to the three-argument one.

### `listingProblem(kind)`: an honest "partial"

If the index of a kind could not be rebuilt (a table you cannot read, a scan that failed), keep the previous index and
say so: return `Optional.of("reason")`, which must name what cannot be read **without secrets**. Structured searches
that list the kind are then marked `partial: true` with that reason, instead of looking exact. Empty means complete.

### `columnar(kind)` and `columns(kind, paths, asOf)`: promoted fields as columns

For stores that keep a pack's promoted fields beside the document (Delta, PostgreSQL table mode, DuckDB, Redis,
MongoDB, Aerospike, Iceberg). `columnar` lists the document paths held as columns; `columns` returns every entity of
the kind on the date, column by column (`ColumnSet`: `ids`, `numbers`, `texts`, `mixed`), so searches, pick lists,
derived kinds and impact analysis read columns instead of documents. Return `Optional.empty()` when you cannot answer
as columns and documents are read instead. `columns` is only called with paths `columnar` lists. A plugin that does not
keep columns leaves both as they are.

### `reverse(target, kind[, asOf])`: what refers to this entity

Returns the entities of `kind` that refer to `target` (for example the trades whose netting set is `NS-A`): this feeds
the *Linked entities* panel and impact analysis. Declare `reverseLookup = true`. The server asks every source that
declares it and merges the answers. The date form defaults to the undated one.

### `subscribe(ref, listener)`, `pushes(ref)` and `lastUpdate()`: live data

A live plugin (`live = true`) returns a `Subscription` whose `close()` stops the pushes; each call to `listener` with a
new `EntityDocument` repaints open views (a document with `deleted = true` says the entity was removed). The default
throws, and is never called for a plugin that does not declare `live`. Subscriptions follow the read: they go to the
first live source that holds the entity, so ticks come from the source that answered. `pushes(ref)` returns `true` for
a plugin that pushes a kind that another source serves (a Kafka connector in `ticks` mode over a lake). `lastUpdate()`
says when new data last arrived (null when it cannot tell, as for a database read on demand): views show how old their
data is, and the connector's `stale-after` setting turns it into a warning.

### `health()`, `cacheStats()`, `purgeCaches()`, `close()`

| Method | Contract |
|---|---|
| `health()` | `"UP"`, `"UP (detail)"`, `"DEGRADED: reason"` (serving, but some data cannot be read: amber in Admin → Health, overall status `DEGRADED`) or anything else, which is *down* (`"DOWN: reason (reconnecting)"`). Cheap, no secrets, no remote call |
| `cacheStats()` | a small `Map` for Admin → Caches (entry counts, sizes, hits); empty when nothing is cached |
| `purgeCaches()` | drop everything cached (memory and disk) so the next reads refill from the source of truth. Called by an admin at any time; must be safe while reads are in flight. Never purge the only copy of data (the message-queue state stores do not) |
| `close()` | stop background tasks and release connections; called at shutdown and when a connector is reloaded |

### Failure semantics

The router asks the candidate sources one by one (see [How a request picks a connector](#how-a-request-picks-a-connector)).
What your plugin throws decides what the user sees:

| Your plugin | Result |
|---|---|
| returns `Optional.empty()` | not held here: the next source is asked |
| holds the date (`coverage` = `HELD`) and returns empty | not held, and authoritative: `DRS-1001`, no further source asked |
| throws any exception | the read ends with `DRS-1003 <connector> failed reading <kind>/<id>`; the **store order** does not matter: a failing store stops the read, and Drishti never silently shows another store's data instead |
| throws `UnreadableData(message, cause)` | as above, but the *message* is shown in the `DRS-1003` error and with the searches it makes partial. Use it when the data is there but cannot be read for a reason the reader can act on (an unsupported codec, a line that is not JSON). The message must carry no secrets: any other exception's message only reaches the log and Admin → Health |
| takes longer than `drishti.sources.fetch-timeout` | the whole read ends with `DRS-1004` |
| no source holds the entity / no source serves the kind | `DRS-1001` / `DRS-1002` |

So a plugin returns empty **only** when it knows it does not hold the entity. A plugin that learns its kinds from its
store and has not yet reached it cannot tell what it holds, so it throws until its first catalogue read succeeds (the
`jdbc`, `duckdb` and `mongodb` plugins do).

---

## Registration and discovery

Drishti finds plugins with the JDK's `ServiceLoader` (`PluginDiscovery` in `drishti-engine`). Two steps:

1. Put the plugin's fully qualified class name, one per line, in
   `META-INF/services/com.ash.drishti.api.SourcePlugin` inside the jar:

   ```text
   com.ash.drishti.examples.dayfolder.DayFolderSourcePlugin
   ```

   The class needs a public no-argument constructor.
2. Make the jar visible, in either of two ways. On the **class path** of the server (a Maven dependency of
   `drishti-server`, as the fifteen shipped plugins are), or in a folder named by `drishti.sources.plugin-dir`
   (`DRISHTI_PLUGIN_DIR`): every `*.jar` in it is loaded in **its own class loader**, so its libraries cannot clash
   with the server's, and a jar added there needs no rebuild of the server, only a restart.

A plugin whose name no connector uses and that is not listed under `plugins` starts with empty settings, and, if it
needs one, stays idle (see below). `GET /api/v1/sources` lists the running connectors; the failures are under
`failures`.

---

## Configuration binding

A plugin runs in one of two ways. **As itself**, under `drishti.sources.plugins.<plugin>`: one instance, one set of
settings. **As named connectors**, under `drishti.sources.connectors.<name>` (or `connectors:` in a pack's
`pack.yaml`): the same plugin several times, each with its own settings, name and, optionally, kinds.

```yaml
drishti:
  sources:
    plugin-dir: ./plugins                       # extra jars, each in its own class loader
    connectors:
      desk-days:
        plugin: dayfolder
        kinds: [trade, counterparty]
        settings: { root: ./data/days, id-field: id, mode.counterparty: effective }
    routes: { trade: desk-days }
```

A connector has four keys: `plugin`, `enabled` (default true), `kinds` (limits what it serves: reads, subscriptions,
reverse lookups and search for other kinds answer nothing) and `settings` (handed to the plugin as `Map<String,
String>`; `source-name` defaults to the connector's name, so documents say which connector they came from). **The
settings of each shipped plugin, with defaults, are in that connector's own document** (the table in
[CONFIGURATION.md](../admin/CONFIGURATION.md#connector-settings-plugin-by-plugin) lists the plugin names a connector
can name); a plugin of yours documents its own.

Rules worth knowing when you write a plugin:

- **Which instances start.** Every plugin on the class path runs as itself unless `plugins.<name>.enabled` is `false`,
  *except* a plugin used by any named connector: that one runs as itself only if it is also listed under `plugins`.
  A plugin that is neither listed nor used by a connector starts with empty settings; one that needs a setting then
  throws `PluginNotConfigured` from `start`, the log says *installed but not configured*, and health does not count it
  as a failure. Do the same for your plugin when it has nothing to run with.
- **Names.** Routes, health and *How this view was built* use the connector's name. A plugin running as itself is known
  by its manifest name or its `source-name`.
- **Setting keys with dots** (`mode.trade`, `header.Authorization`) are literal keys. In a pack's `pack.yaml` write them
  flat (`mode.trade: effective`); a nested map there arrives as dotted keys only where documented. In `application.yaml`
  Spring drops characters other than letters, digits, `-` and `.` from map keys unless the key is bracketed and
  quoted: `"[kind.desk_orders]": order`.
- **Values are strings.** YAML numbers and booleans are converted; parse them yourself and fail with a clear message.
  Placeholders (`${DESK_ROOT:./data}`) are resolved by Spring, in packs as well as in site files, so secrets stay out of
  files.
- **Routing order** for a kind is its route, then the `default-route`, then every other source serving it, re-ordered
  for a picked date (dated first) or Live (live first): see [How a request picks a connector](#how-a-request-picks-a-connector).

---

## Reconnecting and failures

Every connector recovers from an outage without a restart of Drishti, and starts even when its store is down. Your
plugin should too: **start must not fail because the store is down.** Keep the connection lazy or retry in the
background, answer `health()` with `DOWN: … (reconnecting)` meanwhile, and throw from `fetch` (never return empty) until
you know what you hold. How each shipped connector does it:

| Connector | How |
|---|---|
| `jdbc` | a pool of lazy slots: each opens its connection on first use and reopens it when broken; table kinds are listed in the background (every 10 s) until the database answers |
| `kafka` | the Kafka client rides out a broker outage and carries on (health `DOWN: no connection to the broker (reconnecting)` after 10 s without one); a supervisor recreates the consumer after a fatal error (backoff 1 s → 30 s) and resumes at the last applied offset (a restart of the server replays the topic from the beginning, in both modes) |
| `iceberg` | each table's snapshot is checked every `refresh-seconds`; a failed check keeps the last one, and the next succeeds by itself |
| `duckdb` | nothing to reconnect to: the file is read in-process. A missing or unopenable file shows `DOWN: no DuckDB file at …` or `DOWN: DuckDB file … not opened: …`; the file is checked every `refresh-seconds` and opened once it is there. A file replaced by a load is reopened as a new generation, and reads in flight finish on the old one |
| `mongodb` | the driver connects in the background; reads of kinds not yet catalogued answer "not held"; the next refresh fills the catalogue |
| `redis` | Lettuce reconnects by itself; the next refresh refills the catalogue and the `<domain>:changes` subscription resumes. While health says `DOWN`, reads fail at once (`fail-fast`) and a check every `recheck-ms` (1 s) resumes them as soon as Redis answers |
| `aerospike` | the client tends the cluster in the background (`failIfNotConnected` off); the next refresh refills the catalogue |
| `activemq` | the failover transport reconnects; its interruptions show in health; a supervisor rebuilds the session after any other failure |
| `rabbitmq` | a supervisor retries until the first connection succeeds; then the client's automatic recovery reconnects and re-subscribes (a consumer the broker cancels, `DOWN: consumer cancelled on <queue>`, is not re-subscribed until a restart) |
| `delta`, `file`, `rest`, `s3`, feeds | nothing long-lived to lose: each call reads or connects afresh |

While a store is down its reads fail (views say which source failed) and `health` reports `DOWN: … (reconnecting)`.

---

## Testing with drishti-testkit

`drishti-testkit` holds the contract tests every plugin must pass: write the setup, inherit the assertions. Add it as
a test dependency (with `drishti-common` for its JSON codec).

| Class | For | You supply | It checks |
|---|---|---|---|
| `DatedSourceContract` | any dated store | `plugin()`: the started plugin over a store holding `ROWS` (trades as `snapshot` data over three business days, counterparties as `effective` data), with `mode.counterparty = effective`; `context(settings)` builds a `SourceContext` | it is dated and serves `trade` and `counterparty`; each business date reads its own data; a snapshot kind takes the newest date on or before the one asked, an entity absent from it is gone; an effective kind carries the last change forward; reverse lookups follow the date; search finds ids |
| `MessageSourceContract` | a message-queue connector, against a real broker | `start(Path state)`, `send(destination, id, body, deleted)` | documents and envelopes become entities; updates reach live views; deletes remove; the latest state survives a restart of Drishti; messages sent while Drishti was down arrive when it returns; search finds what arrived |
| `BrokerOutage` | a message connector's recovery | `broker(port)` (kill it by closing), `start(port, state)`, `send(port, destination, body)` | a connector started before its broker exists connects when the broker comes up, and again after the broker is killed and started on the same port |

The `file`, `jdbc`, `delta`, `iceberg`, `duckdb`, `mongodb`, `redis` and `aerospike` plugins all extend
`DatedSourceContract`, and `activemq` and `rabbitmq` extend `MessageSourceContract` and `BrokerOutage`; the tests of
the stores that need a server run it in Docker with Testcontainers and skip where Docker is not reachable. Use Testcontainers or a
port nothing else listens on for your own store; never point a test at a service that is already running.

`DatedSourceContract.plugin()` is called more than once per test, so build the plugin once and cache it. The teaching
plugin's test is the whole recipe:

```java
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DayFolderContractTest extends DatedSourceContract {
    private SourcePlugin plugin;

    @Override
    protected synchronized SourcePlugin plugin() throws Exception {
        if (plugin == null) {
            Path folder = Files.createTempDirectory("dayfolder-contract");
            // write ROWS as <kind>/<date>.jsonl, one line per entity ...
            DayFolderSourcePlugin p = new DayFolderSourcePlugin();
            p.start(context(Map.of("root", folder.toString(), "id-field.trade", "tradeId",
                    "mode.counterparty", "effective", "link.trade.netting-set", "nettingSet", "rescan-seconds", "0")));
            plugin = p;
        }
        return plugin;
    }
}
```

The contract covers the happy path. **Add your own tests for the failure semantics**, which are where plugins go wrong:
`PluginNotConfigured` without the required setting, `UnreadableData` for a bad line, `health()` going `DOWN` or
`DEGRADED` and healing, `Optional.empty()` versus a thrown exception, and discovery through `ServiceLoader`
(`DayFolderFailureTest` does each).

---

## A worked example: the dayfolder plugin

`docs/guides/examples/connector/` is a complete, small, **dated** source plugin written for teaching. It is built and
tested with the rest of the project (`./mvnw verify`), but it is **not shipped**: the server does not depend on it,
it is not one of the fifteen plugins, and it exists to be copied.

```text
docs/guides/examples/connector/
  pom.xml                                          depends on drishti-api only (+ testkit for tests)
  src/main/java/.../DayFolderSourcePlugin.java     the plugin (under 300 lines)
  src/main/resources/META-INF/services/com.ash.drishti.api.SourcePlugin
  src/test/java/.../DayFolderContractTest.java     the DatedSourceContract
  src/test/java/.../DayFolderFailureTest.java      discovery, not configured, unreadable data, a failed rescan
  sample/                                          a folder you can point it at
```

**The data.** A folder `<root>/<kind>/<yyyy-MM-dd>.jsonl`, one JSON document per line:

```text
sample/trade/2026-09-29.jsonl         {"id":"T-1","book":"RATES-1","mtm":100.5,"nettingSet":"NS-A"}  ...
sample/trade/2026-09-30.jsonl         three trades (T-3 appears only here)
sample/counterparty/2026-09-29.jsonl  {"id":"CP-X","name":"Acme Bank","rating":"A"}
```

**How it maps onto the SPI**, method by method:

| SPI | In the plugin |
|---|---|
| `manifest` | `new PluginManifest("dayfolder", "1.0", kinds, new SourceCapabilities(false, true, true, true))`: not live, reverse lookups, search, dated. The kinds are the folders found; before `start` the set is empty (any kind) |
| `start` | reads `root` (throws `PluginNotConfigured` when missing), loads the whole folder once (fails loudly if it cannot), then schedules a rescan every `rescan-seconds` on `ctx.scheduler()` |
| the scan | builds an immutable `Snapshot` (kind → day → id → `DataNode`), parsed with `ctx.parseJson`, and swaps it in with one volatile write; concurrent reads see the old or the new, never half. A bad line throws `UnreadableData("trade/2026-09-28.jsonl line 2 is not a document")` naming the file and line and no content |
| `fetch(ref, asOf)` | `view(kind, date)`: for a `snapshot` kind the newest day on or before the date; for an `effective` kind every day up to it, merged. Returns the entity as an `EntityDocument` whose `Provenance` carries the day the document is for. Empty only because it is not in that view |
| `coverage` | `HELD` when the kind has a day on or before the date, `NOT_HELD` when it has none, `UNKNOWN` without a date |
| `search` | a `HitIndex` rebuilt by each scan: answers from memory |
| `reverse` | setting `link.<kind>.<target-kind>` names the document field that refers to the target; returns the ids in the date's view whose field equals the target id |
| `listingProblem` / `health` | after a failed rescan the previous scan keeps serving, `health()` says `DEGRADED: … (serving the previous scan)` and `listingProblem` makes searches partial; the next good scan heals both |
| `cacheStats` / `purgeCaches` | the counts of the snapshot; a purge rescans |
| `close` | cancels the rescan |

The settings it reads are in the class's javadoc: `root`, `source-name`, `id-field` and `id-field.<kind>`,
`mode.<kind>` (`snapshot` or `effective`), `link.<kind>.<target-kind>`, `rescan-seconds`.

**Run its tests** (they run in every `./mvnw verify`):

```bash
./mvnw -q -o test -pl docs/guides/examples/connector -am -Dtest='DayFolder*' -Dsurefire.failIfNoSpecifiedTests=false
```

**Serve a view from it.** Build the jar, drop it in a plugin folder, and declare a connector:

```bash
./mvnw -q -o package -DskipTests -pl docs/guides/examples/connector -am
mkdir -p plugins-extra && cp docs/guides/examples/connector/target/drishti-example-dayfolder-*.jar plugins-extra/
```

```yaml
# application.local.yaml
drishti:
  sources:
    plugin-dir: ./plugins-extra
    connectors:
      desk-days:
        plugin: dayfolder
        kinds: [trade, counterparty]
        settings: { root: docs/guides/examples/connector/sample, source-name: desk-days, mode.counterparty: effective,
                    link.trade.netting-set: nettingSet }
    routes: { trade: desk-days, counterparty: desk-days }
```

Restart, then `curl -s localhost:18480/api/v1/sources | jq -c '.sources[] | select(.name=="desk-days")'` and open
`trade T-1` in the console (a pack that gives `trade` a mnemonic is needed for typing it; the API reads any kind).
Pick 29 September in the business-date picker: the view shows an MTM of 101 (100.5 in the file); pick the 30th: 112. *How this view was built*
names `desk-days`.

![Admin → Health: desk-days (the example plugin) UP, a database and a broker that nothing answers on DOWN with the reason, and the shipped connectors](img/connectors/sources-health.jpg)

![The business-date picker on a dated source](img/connectors/business-date-picker.jpg)

![A view served by the example plugin](img/connectors/dayfolder-view.jpg)

![How this view was built: the provenance names the source](img/connectors/provenance.jpg)

**What to take from it.** Keep the scan separate from the reads; swap state atomically; never return empty for a
failure; keep search in memory; say `DEGRADED` instead of lying; test the failure paths as well as the contract. What
it leaves out, on purpose: streaming a large file instead of reading it whole, a persistent index, columns
(`columnar`/`columns`), live updates and time travel. The shipped plugins show each of those: the table below says
which.

| To learn… | Read the shipped plugin |
|---|---|
| persistent indexes, id offsets, columns | `drishti-plugin-file` (JSON lines), `drishti-plugin-duckdb` |
| live updates, supervisors, backoff | `drishti-plugin-kafka`, `drishti-plugin-rabbitmq` |
| time travel (`knownAt`) | `drishti-plugin-delta`, `drishti-plugin-iceberg` |
| lazy connection slots and reconnecting | `drishti-plugin-jdbc` |
| a plugin built on other kinds | the engine's `derived` source (uses `SourceContext.reader()`) |

---

## The shipped connectors

One short section per connector: what it is for, a minimal configuration, the layout it expects, how the data gets in,
and where it stops. Everything else (every setting and default, health texts, scale, diagnosing) is in the document the
last line of each section links to. Configuration goes in `application.local.yaml` under `drishti.sources` (or in a
pack's `pack.yaml`): see [Where configuration goes](#where-configuration-goes).

### `file`

**For.** End-of-day files dropped in a folder by another system. **Use it when** you have no infrastructure and want a
connector in five minutes, or a large book in plain files (it indexes each day once and reads one line by offset).

```yaml
drishti: { sources: { plugins: { file: { enabled: true, settings: { root: ./data/files, source-name: eod-files } } } } }
```

**Layout.** JSON lines, one file per kind per business day: `<root>/<domain>/<yyyy-MM-dd>/<kind>.jsonl` (an undated
`<kind>.jsonl` also works); or a file per entity, `<root>/<kind>/<id>.json` or `.csv`, and dated
`<root>/<yyyy-MM-dd>/<kind>/<id>.json`. **Loader:** `tools/load-files.sh [root] [--trades N --days D]`.
**Limits:** no live updates; new files reach search after `rescan-seconds`; a kind or id that would leave its folder is
refused. → full reference: [FILE_CONNECTOR.md](FILE_CONNECTOR.md)

### `rest`

**For.** An in-house HTTP service that answers one entity as JSON per GET. **Use it when** the owning system already
has an API and you want to copy nothing.

```yaml
drishti:
  sources:
    connectors:
      positions-api:
        plugin: rest
        kinds: [position]
        settings: { base-url: "${POSITIONS_URL:http://localhost:9000/api}", path: "/{kind}/{id}", header.Authorization: "Bearer ${POSITIONS_TOKEN}" }
    routes: { position: positions-api }
```

**Layout.** `GET <base-url><path>` with `{kind}` and `{id}` substituted; `404` means not held, any other status of 400
or more is a failure. **Loader:** none. **Limits:** undated; no search, no reverse lookups; every view is a request to
the service. → full reference: [REST_CONNECTOR.md](REST_CONNECTOR.md)

### `jdbc` query mode

**For.** A database whose schema you keep: one SQL statement per kind. **Use it when** the data lives in tables you do
not want to copy and you can write the SQL.

```yaml
connectors:
  trading-db:
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
connectors:
  trading-store: { plugin: jdbc, settings: { url: "${DRISHTI_PG_URL:jdbc:postgresql://localhost:5432/drishti}",
                   user: "${DRISHTI_PG_USER:drishti}", password: "${DRISHTI_PG_PASSWORD}", table: trading.entities } }
```

**Layout.** One row per entity per business date, the pack's promoted fields as columns. **Loader:**
`tools/load-postgres.sh`. **Limits:** you run the load and size the indexes; `mode.<kind>` chooses `snapshot` or
`effective`. → full reference: [POSTGRES_CONNECTOR.md](POSTGRES_CONNECTOR.md)

### `delta`

**For.** A Delta Lake: the shipped default for history. **Use it when** you want years of business dates, *known at*
time travel, search and reverse lookups, with no Spark.

```yaml
connectors:
  trading-store: { plugin: delta, kinds: [trade], settings: { root: "${DRISHTI_DELTA_ROOT:./data/delta}", domain: trading } }
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
connectors:
  trading-store: { plugin: iceberg, kinds: [trade], settings: { root: "${DRISHTI_ICEBERG_ROOT:./data/iceberg}", domain: trading } }
```

**Layout.** A table per kind, partitioned by business date, sorted by id, promoted columns beside the document.
**Loader:** `tools/load-iceberg.sh`; maintain with `IcebergMaintenance`. **Limits:** as for Delta Lake; delete files are
applied by every read. → full reference: [ICEBERG_CONNECTOR.md](ICEBERG_CONNECTOR.md)

### `duckdb`

**For.** One embedded DuckDB file for every data domain, read in-process. **Use it when** a desk, a laptop, a demo or a
single server holds a large book in one file and wants day-wide searches without a database server or a lake.

```yaml
connectors:
  trading-store: { plugin: duckdb, settings: { path: "${DRISHTI_DUCKDB_PATH:data/duckdb/drishti.duckdb}", table: trading.entities } }
```

**Layout.** A schema per domain, `<domain>.entities (kind, id, business_date, doc, <promoted columns>)`, each day sorted
by id. **Loader:** `tools/load-duckdb.sh` (`--keep-days`); a load writes a new file and renames it over the old one.
**Limits:** one file, one machine; native memory is outside the Java heap (`memory-limit`). → full reference:
[DUCKDB_CONNECTOR.md](DUCKDB_CONNECTOR.md)

### `mongodb`

**For.** A document database. **Use it when** entities already live in MongoDB, or you want replica sets and sharding
without a lake.

```yaml
connectors:
  trading-store: { plugin: mongodb, settings: { uri: "${DRISHTI_MONGODB_URI:mongodb://localhost:27017}", database: drishti } }
```

**Layout.** A collection per domain, a document per entity per business date (`_id` `kind/id/yyyyMMdd`), the promoted
fields under `c`, and a narrow `<domain>_columns` copy for searches. **Loader:** `tools/load-mongodb.sh`
(`--keep-days`, `--ttl-days`). **Limits:** promoted fields are cached in memory; MongoDB 8.0 does not start on some
recent kernels (use 7). → full reference: [MONGODB_CONNECTOR.md](MONGODB_CONNECTOR.md)

### `redis`

**For.** Today and recent days in memory, live through `<domain>:changes`. **Use it when** you need the newest days
at memory speed with live ticks, and Delta Lake behind it for history.

```yaml
connectors:
  trading-store: { plugin: redis, settings: { uri: "${DRISHTI_REDIS_URI:redis://localhost:6379}" } }
```

**Layout.** A compressed document per entity per day (zstd, dictionary per kind); promoted fields column-wise in chunks
of 10,000; days and ids in sorted sets. **Loader:** `tools/load-redis.sh` (`--ttl-days`, `--publish`). **Limits:**
about 1 GB of memory per million trades a day; TTL retention; a day Redis does not hold goes to the next store.
→ full reference: [REDIS_CONNECTOR.md](REDIS_CONNECTOR.md)

### `aerospike`

**For.** A key-value store with sub-millisecond reads. **Use it when** you have millions of entities a day and want
two key lookups per view, with retention by record TTL.

```yaml
connectors:
  trading-store: { plugin: aerospike, settings: { hosts: "${DRISHTI_AEROSPIKE_HOSTS:localhost:3000}", namespace: "${DRISHTI_AEROSPIKE_NAMESPACE:test}" } }
```

**Layout.** Three sets per domain: a record per entity per business date (`kind/id/yyyyMMdd`: the document and the
promoted bins), an index record per entity (its dates), a record per kind (its dates). **Loader:**
`tools/load-aerospike.sh`. **Limits:** searches, pick lists and reverse lookups need the pack's promoted bins
(`layout.<kind>.columns`). → full reference: [AEROSPIKE_CONNECTOR.md](AEROSPIKE_CONNECTOR.md)

### `kafka`

**For.** Live entities from Kafka topics. **Use it when** the latest state per entity lives on a topic and views should
tick; pair it with a lake for history.

```yaml
connectors:
  trading-stream:
    plugin: kafka
    kinds: [trade]
    settings: { bootstrap-servers: "${DRISHTI_KAFKA_BOOTSTRAP:localhost:9092}", topics: drishti.trading.trades, kind: trade, id-field: tradeId }
```

**Layout.** Messages are an envelope `{kind, id, doc}`, or whole documents with `kind` and `id-field`; a tombstone
deletes. **Loader:** none for production; `tools/samplegen/stream.py` replays and ticks the samples. **Limits:**
undated; reads every partition from the beginning (the topic is the state), so a restart replays it; `mode: ticks`
keeps nothing and only pushes. → full reference: [KAFKA_CONNECTOR.md](KAFKA_CONNECTOR.md)

### `activemq`

**For.** Live entities from ActiveMQ Classic queues and topics. **Use it when** systems publish entity changes to
ActiveMQ.

```yaml
connectors:
  limits-mq:
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
connectors:
  margin-mq:
    plugin: rabbitmq
    kinds: [margin-call]
    settings: { uri: "${RABBIT_URI:amqp://guest:guest@localhost:5672/%2f}", queues: drishti.margin-calls, kind.drishti.margin-calls: margin-call, id-field.drishti.margin-calls: callId }
```

**Layout, loader, limits:** as for ActiveMQ; queues are declared durable unless `declare: false`, messages are
acknowledged only once the state store has kept them. A consumer the broker cancels is not re-subscribed until a
restart. → full reference: [RABBITMQ_CONNECTOR.md](RABBITMQ_CONNECTOR.md)

### `s3`

**For.** JSON documents in Amazon S3 or an S3-compatible store (MinIO, Ceph). **Use it when** the `file` layout already
lives in object storage.

```yaml
connectors:
  risk-docs: { plugin: s3, kinds: [stress-result], settings: { bucket: "${RISK_DOCS_BUCKET:risk-docs}", prefix: eod/, region: us-east-1 } }
```

**Layout.** `<prefix><kind>/<id>.json` and dated `<prefix><yyyy-MM-dd>/<kind>/<id>.json`. **Loader:** none (any
uploader: `aws s3 sync`, `mc cp`). **Limits:** listing a large bucket costs; ids and dates are listed every
`rescan-seconds` and reads cached `cache-seconds`; no reverse lookups. → full reference: [S3_CONNECTOR.md](S3_CONNECTOR.md)

### `feed`

**For.** Public market data: NY Fed SOFR, ECB €STR and FX, the US Treasury curve, FRED. **Use it when** you want real
rates next to your own data; each is off until one variable switches it on.

```yaml
connectors:
  nyfed-sofr-feed: { plugin: feed, enabled: true, kinds: [rate-fixing], settings: { feed: nyfed-sofr, refresh-minutes: 60 } }
```

**Layout.** One connector per feed, declared by the market-data pack; entities carry the feed in their id
(`FIX-SOFR-NYFED`). **Loader:** none. **Limits:** needs the internet (or a mirror: `url: file://…`); FRED needs a free
key; a failed fetch keeps the last good data and shows in health. → full reference: [FEEDS_CONNECTOR.md](FEEDS_CONNECTOR.md)

### `demo`

**For.** The enabled packs' sample entities (`packs/<pack>/samples/`), so everything works with nothing installed.
It runs as itself and serves every kind; it ticks live (`_meta.walk` random-walks the named fields).

```yaml
drishti: { sources: { plugins: { demo: { enabled: false } } } }    # or DRISHTI_DEMO_ENABLED=false
```

**Layout.** `samples/catalog.json` per pack lists the documents. **Loader:** each pack's `tools/` generate the samples.
**Limits:** it is the `default-route`: switch it off in production so a live view cannot tick from samples. → full
reference: [DEMO_CONNECTOR.md](DEMO_CONNECTOR.md) (the sample data itself: [DEMO_DATA.md](DEMO_DATA.md))

**Also built in: `derived`.** Kinds computed from other kinds (members grouped by an expression, with `count`, `sum`,
`avg`, …), read through whichever connectors serve the members. It is part of the engine, not a plugin: see
[PACK_DEVELOPER_GUIDE.md](../guides/PACK_DEVELOPER_GUIDE.md#derived-kinds-entities-computed-from-other-kinds).

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

This is what the trading pack ships: `trading-store` (Delta Lake, dated) and `trading-stream` (Kafka, live), both
serving `trade`, with `routes: { trade: trading-store }`. With the stream on and the demo off, the candidates for
`trade` are the route (`trading-store`), then every other connector serving `trade` (`trading-stream`, and `file`,
which serves every kind). Then:

| The user asks for | Order tried | Who answers |
|---|---|---|
| Live | `trading-stream` (live first) → `trading-store` → `file` | Kafka; the view ticks from Kafka. A trade Kafka does not hold (matured, compacted away) falls through to the lake's newest date |
| a picked date | `trading-store` (dated first) → `file` → `trading-stream` | the lake, for that date; a static snapshot |
| a date the lake does not hold | as above | `file` if it has a dated folder; else Kafka's current document, with the banner *No data held for <date>* (the current data of trading-stream) |

**`state` or `ticks`.** In `state` mode (the default) Kafka keeps an index of the topic and answers Live reads
itself, as above. With `mode: ticks` it keeps nothing (no index, no cache, no disk) and answers no reads: the lake
or database answers every read, and Kafka only pushes each new message to the views that are open. Use it when the
store already has the day's data and the topic is just the change feed, or the topic is too large to index:

```yaml
drishti:
  sources:
    connectors:
      trading-stream:
        plugin: kafka
        settings: { mode: ticks, bootstrap-servers: "${DRISHTI_KAFKA_BOOTSTRAP}", topics: drishti.trading.trades,
                    kind: trade, id-field: tradeId }
```

The view is live because a connector *pushes* the kind (the plugin answers `pushes(ref)` with true in ticks mode),
not because the document that answered is live: `TRD MX-20000001` reads from `trading-store` (its provenance names the
lake), shows the green live dot, and each message for MX-20000001 on the topic repaints it within a frame. A picked
business date stays a static snapshot either way.

### Several domains

Run one connector per data domain. A server with the banking packs runs, for example (real output of
`GET /api/v1/sources`, shortened):

```json
{"sources":[
  {"name":"demo","kinds":[],"live":true,"search":true,"reverseLookup":true,"health":"UP"},
  {"name":"file","kinds":[],"live":false,"search":true,"reverseLookup":false,"health":"UP"},
  {"name":"trading-store","kinds":["trade"],"live":false,"search":true,"reverseLookup":true,"health":"UP"},
  {"name":"credit-store","kinds":["credit-limit","netting-set","sa-ccr","cva","exposure-profile"],"live":false,"search":true,"reverseLookup":true,"health":"UP"},
  {"name":"reference-store","kinds":["book","agreement","counterparty", "…"],"live":false,"search":true,"reverseLookup":true,"health":"UP"},
  {"name":"nyfed-sofr-feed","kinds":["rate-fixing"],"live":false,"search":true,"reverseLookup":false,"health":"UP"}],
 "failures":{}}
```

Each connector lists its own `kinds`, has its own health, its own cache and its own read statistics. Keep connector
names stable: routes, health history, cache purges and provenance all use them.

### Overriding a pack's connector from the site

The site's files sit above every pack and merge **key by key**. Change only what you name:

```yaml
# application.local.yaml
drishti:
  sources:
    connectors:
      trading-store:
        settings:
          root: /srv/lake                  # only the root changes; plugin, kinds, domain, route stay the pack's
      trading-stream:
        enabled: true                      # the stream on, whatever DRISHTI_STREAM_TRADING says
        settings:
          bootstrap-servers: kafka1.bank.example:9092
          topics: trading.trades.v2        # the pack's kind, id-field and disk-cache settings stay
      credit-store:
        kinds: [credit-limit, netting-set] # a list is REPLACED: this connector now serves only these two kinds
      fred-feed:
        enabled: false                     # a parent pack's connector, switched off for this site
    routes:
      credit-limit: limits-drop            # routes merge the same way: this one kind changes
```

Points to remember:

- **Lists replace.** `kinds:` in the site replaces the pack's whole list; kinds you leave out are no longer served by
  that connector.
- **Keys the new plugin does not read are ignored.** Switching `trading-store` to `jdbc` leaves the pack's `root`
  and `domain` in place, harmlessly. (Aerospike *does* read `domain`, as its set, and `layout`, as its promoted bins.)
- **Typos create new connectors.** A site entry whose name no pack declares and that has no `plugin:` fails to start:
  `GET /api/v1/admin/health` shows `"failedToStart": {"trading-stor": "no plugin named 'null'"}`.
- **Between packs**, a child pack that redefines a parent's connector replaces it *as a whole*, and the override is
  listed in health (`overrides`); two unrelated packs that define the same connector differently stop the server at
  start, naming both. See [PACKS.md](../guides/PACKS.md).

### Switching connectors off

| To | Do |
|---|---|
| switch one connector off | `enabled: false` under its name in `application.local.yaml` |
| switch every pack's lake off | `DRISHTI_LAKE_ENABLED=false` |
| switch the samples off | `DRISHTI_DEMO_ENABLED=false` |
| switch a plugin running as itself off | `drishti.sources.plugins.<plugin>.enabled: false` (`DRISHTI_REST_ENABLED`, `DRISHTI_JDBC_ENABLED`, `DRISHTI_S3_ENABLED`, `DRISHTI_ACTIVEMQ_ENABLED`, `DRISHTI_RABBITMQ_ENABLED`) |
| one value for one start | `java -jar … --drishti.sources.connectors.trading-stream.enabled=true` |

A connector that is off is listed under its pack's `connectorsOff` in health. Note that **Admin → Packs** (switching a
pack off for everyone) hides the pack's kinds from users; it does not stop its connectors. Use `enabled: false` for
that.

### Secrets

Never write a password in a YAML file. Use a placeholder and set the variable where the server runs (systemd
`Environment=`, a Kubernetes secret, the shell):

```yaml
settings:
  password: ${DESK_DB_PASSWORD}             # no default: make sure it is set (see below)
  user: ${DESK_DB_USER:drishti}             # with a default
  header.Authorization: "Bearer ${CRM_TOKEN}"
  client.sasl.jaas.config: "${KAFKA_JAAS}"
```

Spring leaves a placeholder it cannot resolve as it is, so with the variable unset the connector receives the text
`${DESK_DB_PASSWORD}` and its connection fails; give a default (`${X:}` for empty) where empty is acceptable.
Health and `/api/v1/sources` do not show settings. Prefer the AWS credential chain (instance roles) to `access-key`
and `secret-key` for S3 and S3A.

### A pack with several connectors, worked through

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

---

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
| `feed` | `UP`, `DOWN: <Exception>: <message>`, `DOWN: the feed returned no data (serving the last data)`, `DOWN: the feed returned no data` (`DOWN: not fetched yet` only before `start` has run: the first fetch is inside it) |

Every connector recovers without restarting Drishti, and every one starts even when its store is down (see
[Reconnecting and failures](#reconnecting-and-failures)).

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
  {"name": "trading-store", "type": "Connector", "stats": {"timeTravel": 0, "partitions": 3, "tables": 1}},
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

`drishti.sources.fetch-timeout` (2 s) bounds the **whole** read, across every connector tried in turn, not each one.
If a slow store sits in front of others (a REST service, a remote database), either raise it or move the slow store
later in the order (a route to the fast one):

```yaml
drishti:
  sources:
    fetch-timeout: 5s
```

A read that runs over ends with `DRS-1004 timed out reading <kind>/<id>`; the connector's own timeouts
(`timeout-ms` for `rest`, `timeout-seconds` for `feed`, `connect-timeout-ms` for `aerospike`) should be at or below
it.

### Capacity tips

- **Memory per connector**: `delta` `cache-mb` (512) of partitions; `kafka` `cache-mb` (256) plus about 0.4–0.5 GB
  per million entities for its index and type-ahead (estimated); `activemq`/`rabbitmq` `cache-mb` (128) plus a few
  hundred bytes per entity held (estimated); `aerospike` `columns-cache-mb` (1024) of promoted
  bins plus one id per entity in the index set (not the days kept). Add them up across connectors when sizing the heap (`-Xmx`).
- **Database connections**: `pool-size` (4) per `jdbc` connector, per server. The `postgres` profile uses 8 for
  `trading-store`, the busiest domain.
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
| A connector is missing from `/sources` | `curl -s $B/sources \| jq .failures`; the server log for `failed to start` or `installed but not configured` | fix the setting named in the message; check `enabled` and the pack in `DRISHTI_PACKS` |
| `failedToStart` shows `no plugin named 'null'` | the connector name in `application.local.yaml` | a typo: no pack declares that name, so it has no `plugin` |
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
each connector's own document (see [The shipped connectors](#the-shipped-connectors)) has every setting, and
[The SourcePlugin SPI](#the-sourceplugin-spi) is the contract for writing your own.
