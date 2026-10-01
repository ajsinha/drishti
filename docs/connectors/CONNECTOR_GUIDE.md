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
# Connector guide

This guide is for the person who has to get **their own data** into Drishti: an operator, a data engineer, a
platform team. It explains, in plain words, what a connector is, helps you pick one, and then walks through every
built-in connector as a complete worked example: the data you have, the configuration (with every line explained),
the commands to load sample data or point at a real system, what you type in the terminal and what you should see,
how the connector shows in health, what happens when the store goes down, and the errors people usually hit.

You do not need to know Java. If you want to *write* a connector, or you need the complete table of every setting a
plugin reads, see [PLUGIN_GUIDE.md](PLUGIN_GUIDE.md). For packs (kinds, mnemonics, Sutras) see [PACKS.md](../guides/PACKS.md);
for running the server in production see [OPERATIONS.md](../admin/OPERATIONS.md).

## Contents

1. [Concepts in plain words](#1-concepts-in-plain-words)
2. [Which connector should I use?](#2-which-connector-should-i-use)
3. [Before you start: the common steps](#3-before-you-start-the-common-steps)
4. [Files on disk: `file`](#4-files-on-disk-file)
5. [An HTTP service: `rest`](#5-an-http-service-rest)
6. [A database, your own schema: `jdbc` query mode](#6-a-database-your-own-schema-jdbc-query-mode)
7. [A database loaded for Drishti: `jdbc` table mode](#7-a-database-loaded-for-drishti-jdbc-table-mode)
8. [A data lake: `delta`](#8-a-data-lake-delta)
9. [A key-value store: `aerospike`](#9-a-key-value-store-aerospike)
10. [An event stream: `kafka`](#10-an-event-stream-kafka)
11. [A message queue: `activemq`](#11-a-message-queue-activemq)
12. [A message queue: `rabbitmq`](#12-a-message-queue-rabbitmq)
13. [An object store: `s3`](#13-an-object-store-s3)
14. [Public market data: `feed`](#14-public-market-data-feed)
15. [The sample data: `demo`](#15-the-sample-data-demo)
16. [Combining connectors](#16-combining-connectors)
17. [Operating connectors](#17-operating-connectors)
18. [Troubleshooting](#18-troubleshooting)
19. [More stores: `redis`, `mongodb`, `iceberg`](#19-more-stores-redis-mongodb-iceberg)

---

## 1. Concepts in plain words

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
later corrections; Delta Lake only).

A **live** connector pushes changes to open views (Kafka, ActiveMQ, RabbitMQ, and the demo samples). The others are
read when a view opens.

### How a request picks a connector

This is the single most useful thing to understand. For a read of `<kind>/<id>`, Drishti
(`SourceRouter` in `drishti-engine`) makes a list of candidates:

1. the kind's **route**, if it names a running connector that serves the kind;
2. the **default route** (`demo`), if it serves the kind;
3. **every other** running connector that serves the kind. A connector with no `kinds` list serves *every* kind
   (the shipped `demo` and `file` do; so does a Kafka or message-queue connector before its first message, unless you
   give it `kinds`).

Then it re-orders the list, keeping the order inside each group:

- **For a picked date:** dated connectors first, then undated ones. History comes from history.
- **For Live:** live connectors first, and among them a real stream before the default route's samples; then the
  rest.

Drishti asks the candidates one by one. A connector that does not hold the entity says so and the next is asked. The
first that holds it answers. A connector that **fails** (the database is down, the service answers 500) stops the
read with `DRS-1003`: Drishti does not silently show you another store's data instead. The whole read must finish
within `drishti.sources.fetch-timeout` (2 s) or it ends with `DRS-1004`. If nobody holds the entity the answer is
`DRS-1001`; if no connector serves the kind at all, `DRS-1002` (rare, because `demo` and `file` serve every kind).

Live updates follow the read: the ticks come from the first live connector that holds the entity, in the same order
(a real stream before the samples). So if a trade comes from Kafka, it ticks from Kafka.

Search (the type-ahead under the command line) asks every connector that supports search and merges the hits.
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
  *murex-rates is not a dated source: this shows its current data, not 2026-08-01.*

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

## 2. Which connector should I use?

### By situation

| Your data is… | Use | Why | Watch out for |
|---|---|---|---|
| JSON or CSV files dropped in a folder by a nightly job | [`file`](#4-files-on-disk-file) | no infrastructure; one folder per business date gives history | search sees new files after `rescan-seconds`; no live updates |
| behind an in-house HTTP/JSON API, one entity per GET | [`rest`](#5-an-http-service-rest) | uses the API the owning system already has | undated, no search, no reverse lookups; every view is a request to that service |
| in an existing database schema | [`jdbc` query mode](#6-a-database-your-own-schema-jdbc-query-mode) | one SQL statement per kind; your tables stay as they are | no search or reverse lookups; you write the SQL for dates |
| loaded by you into PostgreSQL for Drishti | [`jdbc` table mode](#7-a-database-loaded-for-drishti-jdbc-table-mode) | one table per data domain, dated, search and reverse lookups from SQL | you run the load; size the indexes |
| in a data lake (Delta Lake on disk, S3, MinIO) | [`delta`](#8-a-data-lake-delta) | the shipped default; years of history, *known at* time travel, search, reverse lookups, no Spark | partitions by `business_date`; keep it bounded with `tools/lake/maintain.py` |
| in Aerospike | [`aerospike`](#9-a-key-value-store-aerospike) | sub-millisecond key reads (two per view), millions of entities a day, retention by record TTL | searches and reverse lookups need the pack's promoted fields (`layout`) |
| on Kafka topics (latest state per entity) | [`kafka`](#10-an-event-stream-kafka) | live views that tick; the topic is the state | undated: pair it with a lake for history |
| on ActiveMQ queues or topics | [`activemq`](#11-a-message-queue-activemq) | live; the connector keeps the latest document per entity on local disk | a queue delivers once: the local state store is the only copy |
| on RabbitMQ queues | [`rabbitmq`](#12-a-message-queue-rabbitmq) | as ActiveMQ, AMQP 0-9-1 | as ActiveMQ |
| JSON objects in a bucket (S3, MinIO, Ceph) | [`s3`](#13-an-object-store-s3) | the `file` layout in object storage, dated by folder | cost of listing large buckets; reads cached `cache-seconds` |
| a total or summary of another kind (a book's P&L from its trades) | `derived` ([PACKS.md](../guides/PACKS.md#derived-kinds-entities-computed-from-other-kinds)) | computed by the server from the other kind, through whichever connectors serve it | reads every member once per `refresh`; keep `max-scan` above the member count |
| public rates and FX | [`feed`](#14-public-market-data-feed) | NY Fed SOFR, ECB €STR and FX, US Treasury curve, FRED; switched on by one variable each | needs internet (or a mirror); FRED needs a free key |

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
  [Combining connectors](#16-combining-connectors).
- **Reverse lookups** (the *Linked entities* panel, impact analysis with F8) need a connector that indexes references:
  Delta Lake, PostgreSQL table mode, Aerospike, or the demo samples.
- **Cost.** `rest` and `jdbc` query mode put one request on your system per view opened (plus linked entities).
  Delta Lake, S3 and Aerospike read from storage Drishti does not load. Kafka, ActiveMQ and RabbitMQ read everything
  that arrives, whether or not anyone is looking.

---

## 3. Before you start: the common steps

Every chapter below uses the same few commands. They are collected here once.

**1. Build once** (from the repository root; see [GETTING_STARTED.md](../guides/GETTING_STARTED.md) for Java 21 and the
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

## 4. Files on disk: `file`

For more than a handful of entities, keep one **JSON-lines file per kind per business day**
(`<root>/<domain>/<yyyy-MM-dd>/<kind>.jsonl`, written by `tools/load-files.sh`, served by the `files` profile): the
connector indexes each day once and serves single reads, searches and reverse lookups without reading whole files.
[FILE_CONNECTOR.md](FILE_CONNECTOR.md) explains it in full. The rest of this chapter shows the file-per-entity layout.

### The situation

Your credit system writes one JSON file per credit limit every evening into a shared folder,
`/srv/drops/limits`. You want them in Drishti as `credit-limit` entities (mnemonic `LIM`, from the
`counterparty-risk` pack), with history by date.

### The data

The kind is the folder name, the id is the file name without `.json` (or `.csv`). A folder named
`yyyy-MM-dd` holds that business date's files; files outside a date folder are undated.

```text
/srv/drops/limits/
├── credit-limit/
│   └── LIM-HARBOURVIEW.json          undated: served when no dated file is found
├── 2026-09-29/
│   └── credit-limit/
│       └── LIM-HARBOURVIEW.json      the limit as at 29 September
└── 2026-09-30/
    └── credit-limit/
        └── LIM-HARBOURVIEW.json      the limit as at 30 September
```

`2026-09-30/credit-limit/LIM-HARBOURVIEW.json`:

```json
{
  "limitId": "LIM-HARBOURVIEW",
  "counterpartyName": "Harbourview Capital LLP",
  "counterparty": "CP-HARBOURVIEW",
  "measure": "PFE 95 peak",
  "limit": 250000000.0,
  "used": 187500000,
  "utilisation": 0.75,
  "status": "Within limit",
  "approvedBy": "Credit Risk Committee",
  "reviewDue": "2027-03-31"
}
```

A CSV file needs a header row and becomes `{"rows": [...]}`, numbers and booleans typed. `make_data.py` writes one
(nothing under `data/` is in git): `data/feeds/fixing/SOFR-HISTORY.csv` starts

```text
date,rate,volume_bn
2026-09-01,3.95,1910
2026-09-02,3.95,1879
```

and is served as `{"rows": [{"date": "2026-09-01", "rate": 3.95, "volume_bn": 1910}, …]}`.

### Configure it

**Site form** (`application.local.yaml`):

```yaml
drishti:
  sources:
    connectors:
      limits-drop:                         # the connector's name: health, provenance and routes use it
        plugin: file                       # the file plugin
        kinds: [credit-limit]              # serve only credit limits from this folder
        settings:
          root: ${LIMITS_DROP_DIR:/srv/drops/limits}   # the folder; an environment variable can move it
          rescan-seconds: 30               # how often new date folders and new ids (for search) are listed
          lookback-days: 5                 # a picked date may fall back at most 5 days to an older date folder
```

**Pack form** (`packs/<your-pack>/pack.yaml`), the same connector shipped with a pack:

```yaml
connectors:
  limits-drop:                             # one entry per connector
    plugin: file
    kinds:
    - credit-limit                         # a list: a site `kinds:` would replace it whole
    settings:
      root: ${LIMITS_DROP_DIR:/srv/drops/limits}
      rescan-seconds: 30
      lookback-days: 5
```

No route is needed: the pack routes `credit-limit` to `credit-store` (the lake), and the lake simply does not hold
`LIM-HARBOURVIEW`, so the read passes on to `limits-drop`. Add `routes: { credit-limit: limits-drop }` only if the
folder should be asked *before* the lake for every credit limit.

The shipped `application.yaml` also runs the plugin as itself, serving `./data/feeds` (`DRISHTI_FEEDS`) with
`source-name: feed-file`; leave it, or point `DRISHTI_FEEDS` at your folder for the simplest setup.

### Try it

```bash
mkdir -p /tmp/limits/credit-limit /tmp/limits/2026-09-30/credit-limit
cat > /tmp/limits/2026-09-30/credit-limit/LIM-HARBOURVIEW.json <<'EOF'
{"limitId": "LIM-HARBOURVIEW", "counterpartyName": "Harbourview Capital LLP", "counterparty": "CP-HARBOURVIEW",
 "measure": "PFE 95 peak", "limit": 250000000.0, "used": 187500000, "utilisation": 0.75, "status": "Within limit"}
EOF
LIMITS_DROP_DIR=/tmp/limits java -jar drishti-server/target/drishti-server-*-exec.jar
```

```bash
curl -s "http://localhost:18480/api/v1/entities/credit-limit/LIM-HARBOURVIEW/raw?asOf=2026-09-30" | jq -c .provenance
```

You should see `"source":"limits-drop"`, `"live":false` and `"businessDate":"2026-09-30"`; `generation` is the
file's modification time in milliseconds.

### In the terminal

Type `LIM LIM-HARB`: the type-ahead offers `LIM-HARBOURVIEW` with the subtitle `credit-limit · limits-drop`. Press
Enter (or type `LIM LIM-HARBOURVIEW <GO>`). *How this view was built* names `limits-drop`. With the full layout above,
pick 29 September in the top bar and the 29 September file answers. Had the 30 September folder lacked the file, a
read for 30 September would take 29 September's, and the view would say *Latest data on or before 2026-09-30 is from
2026-09-29.* For a date with no date folder on or before it within `lookback-days`, the undated `credit-limit/`
folder answers (if it has the file), and the view says *limits-drop is not a dated source: this shows its current
data, not …*, because that document has no business date.

### Health, and when the folder goes away

`health` is `UP`, or `DOWN: no directory /srv/drops/limits` when the folder is missing (an unmounted share). There is
no connection to lose: each read opens the file afresh. While the folder is missing, reads find nothing
(`DRS-1001 no source holds credit-limit/LIM-HARBOURVIEW`); when it comes back, reads work at once and search catches
up at the next rescan.

### Common errors

| You see | Cause | Fix |
|---|---|---|
| `DRS-1001 no source holds credit-limit/LIM-X` | the file is not at `<root>/<kind>/<id>.json` or `<root>/<date>/<kind>/<id>.json` | check the kind folder spelling (`credit-limit`, not `credit_limit` or `limits`) |
| a new date folder is ignored for a few seconds | date folders are listed every `rescan-seconds` | wait, or lower `rescan-seconds` |
| the type-ahead does not offer a new file | search is rebuilt every `rescan-seconds` | wait; the read itself works at once |
| an old date shows undated data | the picked date is more than `lookback-days` after the newest date folder holding the file | raise `lookback-days`, or keep a file per date |
| an id with `/` or `..` is not found | ids that would leave the root are refused | use plain ids |

---

## 5. An HTTP service: `rest`

[REST_CONNECTOR.md](REST_CONNECTOR.md) explains the connector in full: requests and responses, headers, generations, timeouts, failure and every setting.

### The situation

Your client-onboarding system has an API that returns one counterparty as JSON:
`GET https://crm.bank.example/api/counterparty/CP-HARBOURVIEW`. You want those counterparties in Drishti
(`CPTY`, from `banking-core`) without copying them anywhere.

### The data

`200` with a JSON object is the document. `404` means *not held here* (the next connector is asked). Any other
status of 400 or more, or a connection error, is a failure (`DRS-1003`).

```http
GET /api/counterparty/CP-HARBOURVIEW HTTP/1.1
Accept: application/json
Authorization: Bearer eyJ…

HTTP/1.1 200 OK
Content-Type: application/json
X-Version: 42

{"counterpartyId": "CP-HARBOURVIEW", "name": "Harbourview Capital LLP", "lei": "5493001KJTIIGC8Y1R12",
 "rating": "A", "sector": "Asset management", "country": "GB", "type": "Fund manager"}
```

### Configure it

**Site form:**

```yaml
drishti:
  sources:
    connectors:
      crm-api:
        plugin: rest
        kinds: [counterparty]                         # only counterparties are asked of this service
        settings:
          base-url: ${CRM_URL:https://crm.bank.example/api}   # required; a trailing / is dropped
          path: /{kind}/{id}                          # appended to base-url; {kind} and {id} are URL-encoded
          timeout-ms: 3000                            # connect and request timeout (default 2000)
          generation-header: X-Version                # a numeric response header used as the version (default ETag)
          header.Authorization: "Bearer ${CRM_TOKEN}" # any request header: header.<Name>; the token from the environment
```

**Pack form:**

```yaml
connectors:
  crm-api:
    plugin: rest
    kinds: [counterparty]
    settings:
      base-url: ${CRM_URL:http://localhost:9000/api}
      path: /{kind}/{id}
      timeout-ms: 3000
      header.Authorization: Bearer ${CRM_TOKEN:}       # flat key with a dot: write it this way in pack.yaml
```

The shipped `application.yaml` also has the plugin as itself, off: `DRISHTI_REST_ENABLED=true` with
`DRISHTI_REST_URL` turns it on, serving any kind at `/{kind}/{id}` under the name `rest`.

### Try it

Any static file server will do as a stand-in for the API:

```bash
mkdir -p /tmp/crm/api/counterparty
echo '{"counterpartyId": "CP-HARBOURVIEW", "name": "Harbourview Capital LLP", "rating": "A", "country": "GB"}' \
  > /tmp/crm/api/counterparty/CP-HARBOURVIEW.json
(cd /tmp/crm && python3 -m http.server 9000)
```

With `base-url: http://localhost:9000/api` and `path: /{kind}/{id}.json`, start the server, then:

```bash
curl -s http://localhost:18480/api/v1/entities/counterparty/CP-HARBOURVIEW/raw | jq -c '{provenance, data}'
```

You should see `"source":"crm-api"`, `"live":false`, `"businessDate":null` and the document.

### In the terminal

`CPTY CP-HARBOURVIEW <GO>`. The type-ahead does **not** offer the id (a REST connector cannot search). Live: the
demo samples and the reference lake do not hold `CP-HARBOURVIEW`, so the read reaches `crm-api`. Pick a date and the
view says *crm-api is not a dated source: this shows its current data, not 2026-09-29.*

### Health, and when the service goes down

`health` is `UP` once started (`DOWN: not started` only if it never started). The plugin keeps no connection, so a
service that is down does **not** change health; it shows as failed reads: views say
`DRS-1003 crm-api failed reading counterparty/CP-HARBOURVIEW`, and the connector's `reads.errors` and `lastError`
in `/api/v1/admin/health` count them. When the service is back, the next read works.

### Common errors

| You see | Cause | Fix |
|---|---|---|
| the server log says `rest plugin needs settings.base-url`, and the connector is under `failedToStart` | `base-url` empty | set it (or `DRISHTI_REST_URL` for the plugin as itself) |
| `DRS-1003 … failed reading` | the service answered ≥ 400 (not 404) or refused the connection | `curl -i` the URL the connector builds: `<base-url><path>` |
| `DRS-1004 timed out reading …` | the service took longer than `fetch-timeout` (2 s) | raise `timeout-ms` *and* `drishti.sources.fetch-timeout` |
| every view of other kinds is slow | the connector has no `kinds`, so it is asked for everything | give it `kinds:` |

---

## 6. A database, your own schema: `jdbc` query mode

A kind can have several queries: the entity, its parts from other tables (`query.<kind>.<part>`), its ids for
type-ahead (`ids.<kind>`), a day's fields for searches (`columns.<kind>`) and its reverse lookups (`reverse.<kind>`).
[JDBC_QUERIES.md](JDBC_QUERIES.md) explains each in full; this chapter starts with the first.

### The situation

Your trades live in a PostgreSQL table `desk.trades`, one row per trade per business date. You want `TRD <id>` to
read them, with history, without changing the schema.

### The data

```sql
CREATE SCHEMA IF NOT EXISTS desk;
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
INSERT INTO desk.trades VALUES
  ('MX-21770001', '2026-09-29', 'IRS', 'CP-ALDERSHOT', 50000000, 412300.00, 'USD'),
  ('MX-21770001', '2026-09-30', 'IRS', 'CP-ALDERSHOT', 50000000, 398150.00, 'USD');
```

### Configure it

You write one SQL statement per kind under `query.<kind>`. Use `:id` for the entity id and `:asOf` for the business
date (a SQL `DATE`); each may appear several times. (A statement with a single `?` also works, bound to the id.)
A connector whose statement uses `:asOf` is **dated**: picked dates are passed to it, and on Live it receives the
current date.

**Site form:**

```yaml
drishti:
  sources:
    connectors:
      desk-db:
        plugin: jdbc
        kinds: [trade]                                       # optional: the query.* kinds are what it serves anyway
        settings:
          url: ${DESK_DB_URL:jdbc:postgresql://db.bank.example:5432/desk}
          user: ${DESK_DB_USER:}                             # credentials from the environment, never in the file
          password: ${DESK_DB_PASSWORD:}
          pool-size: 4                                       # connections, each opened on first use
          # the newest row on or before the date asked
          query.trade: >-
            SELECT trade_id, business_date, product, counterparty, notional, mtm, currency
            FROM desk.trades
            WHERE trade_id = :id
              AND business_date = (SELECT MAX(business_date) FROM desk.trades
                                   WHERE trade_id = :id AND business_date <= :asOf)
```

**Pack form** (the statement on one line, or as a YAML block scalar, as above):

```yaml
connectors:
  desk-db:
    plugin: jdbc
    kinds: [trade]
    settings:
      url: ${DESK_DB_URL:jdbc:postgresql://localhost:5432/drishti}
      user: ${DESK_DB_USER:drishti}
      password: ${DESK_DB_PASSWORD:drishti}
      pool-size: 4
      query.trade: >-
        SELECT trade_id, business_date, product, counterparty, notional, mtm, currency FROM desk.trades
        WHERE trade_id = :id AND business_date = (SELECT MAX(business_date) FROM desk.trades
        WHERE trade_id = :id AND business_date <= :asOf)
```

**How rows become documents.** The first row is the document. Each column becomes a field in camel case
(`trade_id` → `tradeId`); a `NUMERIC` value without decimals becomes an integer, others a decimal number; `DATE`
becomes `2026-09-30`, `TIMESTAMP` an ISO instant. Three column names are special:

| Column | Effect |
|---|---|
| `json` | its text is the whole document (`SELECT doc::text AS json FROM …`); other columns are ignored for the content |
| `generation` (a number) | the version shown in provenance; otherwise the read time |
| `business_date` (a `DATE`) | the date the row is for (provenance), also added as `businessDate` |

**JSON inside a row.** A column of type `json` or `jsonb` becomes nested data under its field name, so a trade with
its legs in one column reads as `legs[0].rate` in a Sutra. JSON kept in a text column needs naming:

```yaml
settings:
  query.trade: SELECT trade_id, notional, legs, extras FROM desk.trades WHERE trade_id = :id
  json-columns: extras            # a TEXT/VARCHAR column holding JSON; `legs` is jsonb and needs no listing
```

A cell that is not valid JSON stays as text, so one bad row never fails the view.

A statement for documents already stored as JSON:

```yaml
query.counterparty: SELECT doc::text AS json FROM desk.counterparties WHERE cpty_id = ?
```

The PostgreSQL driver ships with the server. For another database (Oracle, SQL Server, …) put its driver jar on the
class path or in the plugin folder (`DRISHTI_PLUGIN_DIR`).

### Try it

```bash
docker compose -f deploy/compose.data.yaml up -d postgres          # user, password and database: drishti
docker compose -f deploy/compose.data.yaml exec -T postgres psql -U drishti -d drishti < desk.sql   # the SQL above
DESK_DB_URL=jdbc:postgresql://localhost:5432/drishti DESK_DB_USER=drishti DESK_DB_PASSWORD=drishti \
  java -jar drishti-server/target/drishti-server-*-exec.jar
```

```bash
curl -s "http://localhost:18480/api/v1/entities/trade/MX-21770001/raw?asOf=2026-09-30" | jq -c '{provenance, data}'
```

You should see (real output; the generation is the read time):

```json
{"provenance":{"source":"desk-db","generation":1790828309088,"fetchedAt":"2026-10-01T04:18:29.093601107Z","live":false,"businessDate":"2026-09-30"},
 "data":{"tradeId":"MX-21770001","businessDate":"2026-09-30","product":"IRS","counterparty":"CP-ALDERSHOT",
         "notional":50000000,"mtm":398150.0,"currency":"USD"}}
```

### In the terminal

`TRD MX-21770001 <GO>`. Pick 29 September: `mtm` is `412,300`. The `counterparty` value `CP-ALDERSHOT` is a link
(Drishti recognises the id), so the counterparty opens with a click. The type-ahead does not offer `MX-21770001` (query
mode cannot search); users type the id.

### Health, and when the database goes down

`health` is `UP`, `DOWN: not started`, or `DOWN: <driver message> (reconnecting)` after a failed read. The
connector starts even when the database is down: each pooled connection is opened on first use and reopened when
broken. While the database is down, reads fail with `DRS-1003 desk-db failed reading trade/MX-21770001` and health reads
(real output):

```text
DOWN: Connection to localhost:5432 refused. Check that the hostname and port are correct and that the postmaster is accepting TCP/IP connections. (reconnecting)
```

`reads.lastError` in admin health carries the same message with the exception's name (`PSQLException: …`). When the
database is back, the next read reconnects and health returns to `UP`.

### Common errors

| You see | Cause | Fix |
|---|---|---|
| the connector is missing from `/sources` (and not under `failures`); the log says `source plugin desk-db is installed but not configured (jdbc needs settings.url); it stays idle` | `url` empty (an unset variable with an empty default) | export the variable, or give a default |
| `DRS-1003 … failed reading`, health `DOWN: <driver message> (reconnecting)`, `reads.lastError` `PSQLException: …` | SQL error, wrong credentials, unreachable host | run the statement in `psql` with the id and date substituted |
| `No suitable driver` | the database's driver is not on the class path | put the jar in `DRISHTI_PLUGIN_DIR` |
| a picked date shows the latest data | the statement does not use `:asOf` | add the `business_date <= :asOf` condition |
| fields named `TRADE_ID` | — | they are converted: `TRADE_ID` and `trade_id` both become `tradeId` |

---

## 7. A database loaded for Drishti: `jdbc` table mode

### The situation

You load data into PostgreSQL *for* Drishti and want everything the lake gives: picked dates, search, reverse
lookups. Table mode reads a whole data domain from one table of JSON documents.

### The data

One row per entity per business date:

```sql
CREATE SCHEMA IF NOT EXISTS trading;
CREATE TABLE trading.entities (
  kind          text  NOT NULL,                  -- the entity's kind: trade
  id            text  NOT NULL,                  -- its id: MX-20000001
  business_date date  NOT NULL,                  -- the date the document is for
  doc           jsonb NOT NULL,                  -- the document
  PRIMARY KEY (kind, id, business_date)          -- dated reads
);
CREATE INDEX ON trading.entities (kind, business_date);               -- snapshot dates
CREATE INDEX ON trading.entities USING gin (doc jsonb_path_ops);      -- reverse lookups
INSERT INTO trading.entities VALUES
  ('trade', 'MX-20000001', '2026-09-30',
   '{"tradeId": "MX-20000001", "counterparty": "CP-NORTHBRIDGE", "nettingSet": "NS-NORTHBRIDGE-IRS", "mtm": 1875863, "businessDate": "2026-09-30"}');
```

The document's business date is the row's `business_date`; its version is that date's day number. Reverse lookups
find rows whose document holds the target id anywhere (`jsonb_path_exists(doc, '$.** ? (@ == $v)')`); search matches
ids containing the typed text.

### Configure it

**Site form, replacing a pack's lake with PostgreSQL.** The banking packs declare `<domain>-store` connectors on
Delta Lake. The `postgres` profile (`application-postgres.yaml`) switches six of them to PostgreSQL by naming only
`plugin` and `settings`; the pack's `kinds`, route and `mode.<kind>` settings stay. Your site can do the same for
one connector:

```yaml
drishti:
  sources:
    connectors:
      trading-store:                                 # the trading pack's connector, by name
        plugin: jdbc                                 # replaces the pack's "delta"; kinds and route stay
        settings:
          url: ${DRISHTI_PG_URL:jdbc:postgresql://localhost:5432/drishti}
          user: ${DRISHTI_PG_USER:drishti}
          password: ${DRISHTI_PG_PASSWORD:drishti}
          table: trading.entities                    # setting table turns table mode on
          pool-size: 8
          # the pack's root and domain stay too; jdbc ignores them
```

**Pack form, a pack that owns its PostgreSQL store:**

```yaml
connectors:
  trading-store:
    plugin: jdbc
    kinds: [trade]
    settings:
      url: ${DRISHTI_PG_URL:jdbc:postgresql://localhost:5432/drishti}
      user: ${DRISHTI_PG_USER:drishti}
      password: ${DRISHTI_PG_PASSWORD:drishti}
      table: trading.entities                        # schema.table; plain SQL names only
      mode.trade: snapshot                           # the default; effective for reference data
      lookback-days: 10                              # snapshot kinds: how far back a picked date may fall
      # kinds: trade                                 # optional; otherwise the kinds the table holds
routes:
  trade: trading-store
```

Column names other than `kind`, `id`, `business_date`, `doc` are set with `kind-column`, `id-column`, `date-column`,
`doc-column`.

### Try it

```bash
docker compose -f deploy/compose.data.yaml up -d postgres
tools/load-postgres.sh jdbc:postgresql://localhost:5432/drishti
SPRING_PROFILES_ACTIVE=postgres DRISHTI_PACKS=counterparty-risk,market-risk \
  java -jar drishti-server/target/drishti-server-*-exec.jar
```

`tools/load-postgres.sh` (re)creates one `<schema>.entities` table per domain (`reference`, `market`, `trading`,
`risk`, `credit`, `collateral`) with ten business days, partitioned by month, the pack's promoted fields as columns,
and prints `postgres: loaded 17,910 rows in 2 s`. Add `--trades 50000` (a medium demo) or `--trades 1000000 --days 3`
(the scale test) for a larger trade book, streamed from the generator into the loader. How the layout serves a
million trades a day is in [POSTGRES_CONNECTOR.md](POSTGRES_CONNECTOR.md).

```bash
curl -s "http://localhost:18480/api/v1/entities/trade/MX-20000001/raw?asOf=2026-09-29" | jq -c .provenance
```

You should see `"source":"trading-store"` and `"businessDate":"2026-09-29"`.

### In the terminal

`TRD MX-20000001 <GO>`, then pick an earlier date: the trade's numbers change. `CPTY CP-NORTHBRIDGE <GO>` on a picked
date: the *Netting sets* panel lists the four netting sets that mention the counterparty, and impact analysis (F8, or
`GET /api/v1/impact/counterparty/CP-NORTHBRIDGE?asOf=2026-09-29`) lists them with its 35 trades: reverse lookups by
SQL. (*Linked entities* shows the ids the counterparty itself refers to: its group and credit limit.)

### Health, and when the database goes down

As query mode: `UP` or `DOWN: <driver message> (reconnecting)`; connections reopen by themselves. A table without
the columns the pack declares says `UP (not laid out as the pack declares: trade (0 of 19 columns); searches read
documents)`. If the database is down when the server starts and you did not list `kinds` in the settings, the
connector reads its catalogue again 10 seconds later and then every `refresh-seconds`; until then it holds nothing, so
reads of its kinds go on to the next connector (the samples, when they are on).

### Common errors

| You see | Cause | Fix |
|---|---|---|
| `not a plain SQL identifier: …` under `failedToStart` | `table` or a column name holds anything but letters, digits, `_` and one `.` | use plain names |
| searches say `partial: true` | the table has no promoted columns (an earlier table, or written another way) | reload with `tools/load-postgres.sh`, or add the columns the pack declares |
| `… is a plain table of the old layout: load with --recreate` | the loader found a table of the earlier form | `--recreate` (the samples' load does it) |
| a picked date returns nothing for a snapshot kind | the newest date on or before it is more than `lookback-days` older | load every business date, or set `mode.<kind>: effective` for data that changes rarely |
| the connector serves no kinds after an outage at start | it reads its catalogue again after 10 s, then every `refresh-seconds` | wait, or list `kinds:` in settings |

---

## 8. A data lake: `delta`

### The situation

Your batch jobs (Spark, Databricks, Python `deltalake`) write the end-of-day data of each domain to Delta Lake
tables: years of business dates. This is Drishti's default store: every banking pack reads one domain of
`./data/delta`.

### The data

One table per kind, under `<root>/<domain>/<kind>/`, partitioned by `business_date` (a `DATE`), with two columns:
`id STRING` (the entity id) and `doc STRING` (the JSON document).

```text
data/delta/
└── trading/                                  the domain (settings.domain)
    └── trade/                                the kind (one table)
        ├── _delta_log/00000000000000000000.json …
        ├── business_date=2026-09-29/part-00000-….parquet
        └── business_date=2026-09-30/part-00000-….parquet
```

A row:

| id | doc | business_date |
|---|---|---|
| `MX-20000001` | `{"tradeId": "MX-20000001", "productType": "IRS_FIXFLOAT", "counterparty": "CP-NORTHBRIDGE", "mtm": 1875863, "businessDate": "2026-09-30", …}` | `2026-09-30` |

Writing a day from Python (the same calls `tools/samplegen/lake.py` uses): append a new date, or replace one date
that was restated:

```python
import json, datetime, pyarrow as pa
from deltalake import write_deltalake

day = datetime.date(2026, 9, 30)
docs = {"MX-20000001": {"tradeId": "MX-20000001", "mtm": 1875863, "businessDate": day.isoformat()}}
table = pa.table({"id": pa.array(list(docs), pa.string()),
                  "doc": pa.array([json.dumps(d) for d in docs.values()], pa.string()),
                  "business_date": pa.array([day] * len(docs), pa.date32())})
write_deltalake("data/delta/trading/trade", table, mode="overwrite",
                partition_by=["business_date"], predicate=f"business_date = '{day.isoformat()}'")
```

The `predicate` replaces only that date, so the table keeps its history, and the replaced version stays readable
through *known at* until it is vacuumed.

### Configure it

**Pack form**, as shipped in `packs/trading/pack.yaml`:

```yaml
connectors:
  trading-store:                          # routes, health and provenance use this name
    plugin: delta
    enabled: ${DRISHTI_LAKE_ENABLED:true} # one switch for every pack's lake
    kinds:
    - trade                               # serve only trades from this domain
    settings:
      root: ${DRISHTI_DELTA_ROOT:./data/delta}   # one variable moves every pack's lake
      domain: trading                     # tables live under <root>/trading/<kind>/
routes:
  trade: trading-store                    # trades are asked of the lake first
```

Reference data that changes rarely is `effective` (from `packs/banking-core/pack.yaml`):

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

**Site form**, your own domain:

```yaml
drishti:
  sources:
    connectors:
      treasury-lake:
        plugin: delta
        kinds: [funding-source, hqla-holding]    # what this connector serves
        settings:
          root: /srv/lake                        # local folder (or s3a://bucket/path, below)
          domain: treasury                       # /srv/lake/treasury/<kind>/
          mode.funding-source: effective
          lookback-days: 10                      # snapshot kinds
          refresh-seconds: 10                    # how long a table's latest version is cached
          cache-mb: 512                          # partitions kept in memory, by size
```

**The lake in S3 or an S3-compatible store** (MinIO, Ceph). A site moves a pack's lake without touching the pack:

```yaml
drishti:
  sources:
    connectors:
      trading-store:
        settings:
          root: s3a://risk-lake/banking            # read through Hadoop's S3A
          s3.region: us-east-1
          # credentials: the AWS chain (environment, profile, instance role), or
          # s3.access-key: ${LAKE_ACCESS_KEY}
          # s3.secret-key: ${LAKE_SECRET_KEY}
          # an S3-compatible store:
          # s3.endpoint: https://minio.bank.example # path-style; TLS when https
          # hadoop.fs.s3a.connection.maximum: "200" # any Hadoop setting, prefixed hadoop.
```

`DRISHTI_DELTA_ROOT=s3a://risk-lake/banking` moves every pack's lake at once.

### Load sample data

```bash
# every banking domain (reference, market, trading, risk, credit, collateral), ten business days
uv run --with deltalake --with pyarrow --with pyyaml python tools/packgen/banking/make_data.py --lake data/delta --days 10
# or one pack's samples into one domain
uv run --with deltalake --with pyarrow --with pyyaml python tools/samplegen/lake.py \
    --samples packs/finance/samples --root data/delta --domain finance --days 10 [--as-of 2026-09-30] [--calendar USNY]
DRISHTI_PACKS=counterparty-risk,market-risk java -jar drishti-server/target/drishti-server-*-exec.jar
```

`lake.py` prints `<table>: <n> entities x 10 business days` per table. It writes the newest date twice (the second
commit restates one document per kind), so *known at* before that commit shows the original.

### In the terminal

`TRD MX-20000001 <GO>`; pick 29 September in the top bar and the numbers change; *Raw JSON* (F9) shows `businessDate`.
`CPTY CP-NORTHBRIDGE <GO>` on a picked date lists its netting sets (*Netting sets* panel) and F8 (impact) its trades:
reverse lookups, for which the lake indexes every value that looks like an identifier (capital letters and digits with
at least one dash, such as `CP-NORTHBRIDGE` or `NS-NORTH-01`).

### Millions of entities a day: the layout

The full design, with every read path, memory figures, maintenance and measurements, is in
[DELTA_CONNECTOR.md](DELTA_CONNECTOR.md).

A table of a few thousand entities a day can be read any way. For a book of a million trades a day, kept for years,
declare a layout on the connector ([PACKS.md, Large kinds](../guides/PACKS.md#large-kinds-the-lake-layout)). The connector then
reads:

| What | How |
|---|---|
| one trade (a view) | the day's id map (from the id column) says which file; Parquet skips to the one row group whose id range holds it |
| type-ahead | the newest day's ids, sorted in memory: a prefix is a binary search |
| searches, pick lists, desk P&L, impact | the day's promoted columns, read once, kept (`columns-cache-mb`), the newest day loaded in the background |
| reverse lookups (linked entities) | the promoted link columns (`nettingSet`, `book`, `counterparty.id`) |

Without a layout everything still works, but a day is read whole, and a large day exceeds the read deadline.

### Keep the lake bounded: `tools/lake/maintain.py`

Drishti only reads the lake. A scheduled job keeps it from growing for ever: it deletes business dates older than
the history window, compacts small files, writes checkpoints and vacuums files no longer referenced. The settings are
in `deploy/lake-maintenance.yaml`:

```yaml
schedule:
  at: "02:30"                    # after the nightly load, before the day starts
  zone: America/New_York
lakes:
  - root: ./data/delta           # a local lake: every domain, every table
    domains: ["*"]
    keep-business-days: 520      # about two years; older partitions are deleted (null keeps all)
    compact: true                # merge each day's small files
    target-file-mb: 128
    checkpoint: true             # readers replay a short log
    vacuum-hours: 168            # files unreferenced for 7 days are removed; "known at" reaches back 7 days
  # - root: s3://risk-lake/banking
  #   storage-options: { AWS_REGION: us-east-1 }
```

See what it would do, changing nothing:

```bash
uv run --with deltalake --with pyarrow --with pyyaml python tools/lake/maintain.py \
    --config deploy/lake-maintenance.yaml --once --dry-run
```

You should see one line of JSON per table (real output):

```json
{"at": "2026-09-30T21:57:32-04:00", "event": "maintained", "dry_run": true, "table": "data/delta/civic/bill", "before": {"files": 10, "mb": 0.03}, "retention": {"cutoff": "2024-10-02", "would_remove_files": 0}, "vacuum": {"files": 0, "dry_run": true}, "after": {"files": 10, "mb": 0.03}}
```

For a laid-out table, `compact` re-sorts only the business days that drifted from the layout (an intraday load
appended a small file, so ids overlap) and keeps file statistics on the id, the date and the promoted columns only,
so the log stays small over years of daily files. For seven years of history set `keep-business-days: 1800`.
`maintain.py relayout --root data/delta --domain trading` lays out a table written another way.

Run it for real with `--once` (from cron), or keep it running with `--daemon` (every day at `schedule.at`). A table
that fails is logged as `"event": "failed"` and the rest go on. Note that the maintenance job uses `s3://` URIs
(Python), while the server reads `s3a://` (Hadoop).

### Health, and when the store goes down

`health` is `UP`, `DOWN: cannot reach <root>/<domain>` (the folder or bucket is not reachable), or
`DOWN: no Delta tables under <root>/<domain>` (reachable, but nothing with a `_delta_log`). There is nothing
long-lived to lose: each read goes to storage afresh, so reads recover as soon as the storage does. While an
`s3a://` store is unreachable, a read that is not cached ends with `DRS-1004 timed out reading …` (S3A retries for
longer than `fetch-timeout`) or `DRS-1003`. Cache figures:
`tables`, `partitions` (in memory) and `timeTravel` (*known at* versions held).

The connector lists the domain's tables when it starts and again at every reindex (every six `refresh-seconds`, a
minute by default), unless `kinds` is given in its *settings*. A table added to the lake while the server runs is
served from the next reindex, with no restart: check with `curl -s $B/sources | jq '.sources[] | select(.name=="trading-store").kinds'`.

### Common errors

| You see | Cause | Fix |
|---|---|---|
| views say *No data available*; health `DOWN: no Delta tables under ./data/delta/trading` | the lake was not built, or `DRISHTI_DELTA_ROOT` points elsewhere | build it (above), or set `DRISHTI_DELTA_ROOT` and restart; tables built while the server runs are found within a minute |
| `domain escapes the Delta root` under `failedToStart` | `domain` contains `..` or starts with `/` | a plain folder name |
| an old picked date falls back to the samples | the date is more than `lookback-days` past the newest partition on or before it | load that date, or raise `lookback-days` |
| *known at* shows the latest data | the lake was copied without keeping file times (Delta resolves instants against `_delta_log` modification times) | copy with `cp -p` / `rsync -t`; for S3, times are upload times |
| S3: `DOWN: cannot reach s3a://…` | credentials, region or endpoint | try `aws s3 ls s3://<bucket>/<path>/` with the same credentials |

---

## 9. A key-value store: `aerospike`

### The situation

Your platform keeps entity data in Aerospike, a book of up to millions of entities a day kept for years. Each data
domain has three sets in one namespace: a record per entity per business date, an index record per entity listing
its dates, and a small record per kind listing the kind's dates. A dated read is two key lookups. The scaling design,
with measured numbers, is in [AEROSPIKE_CONNECTOR.md](AEROSPIKE_CONNECTOR.md).

### The data

The three record types, for the trade `MX-20000001` on 30 September 2026 in the `trading` domain:

```text
namespace test · set trading · key "trade/MX-20000001/20260930"       one record per entity per business date
  kind            = "trade"
  id              = "MX-20000001"
  date            = 20260930                                           yyyyMMdd, a number
  doc             = "{\"tradeId\": \"MX-20000001\", \"sourceSystem\": \"Murex\", …, \"mtm\": 1875863, …}"
  mtm             = 1875863.0                                          promoted fields (layout.trade.columns)
  book            = "BOOK-RATES-3"
  desk            = "DESK-RATES"
  counterparty_id = "CP-MERIDIAN-RE"
  counterpar_f5fe = "Meridian Reinsurance Ltd"                         counterparty.name: longer than 15 characters
  nettingSet      = "NS-MERIDIAN-RE-NY"
  risk_dv01       = -155245.0
  …                                                                    one bin per promoted field

namespace test · set trading_ix · key "trade/MX-20000001"              one index record per entity
  kind  = "trade"
  id    = "MX-20000001"
  dates = [20260917, 20260918, 20260921, …, 20260929, 20260930]        sorted

namespace test · set trading_kinds · key "trade"                       one record per kind
  kind  = "trade"
  dates = [20260917, 20260918, …, 20260930]                            the dates the kind has
```

`doc` is the day's JSON document as text. The promoted bins are the fields the pack lists under
`layout.<kind>.columns` (the trading pack promotes 19 trade fields, from `tradeId` to `sourceSystem`): numbers are
stored as doubles, everything else as text. Aerospike bin names are at most 15 characters, so a promoted path is
named by its path with dots as underscores (`counterparty.id` is `counterparty_id`); a longer name is cut to its
first 10 characters, an underscore and the first 4 hex digits of the SHA-1 of the path (`counterparty.name` is
`counterpar_f5fe`). A field named `kind`, `id`, `date`, `doc` or `dates` gets the hashed form too. Writers and the
connector share this rule (`AerospikeLayout.bin`); compute the name of any path with
`python3 -c "import hashlib;print(hashlib.sha1(b'counterparty.name').hexdigest()[:4])"`.

### How it reads

| What | How |
|---|---|
| one entity (a view) | get the index record `kind/id` (bin `dates`), pick the day, get `kind/id/yyyyMMdd` (bin `doc`) |
| type-ahead | the index set's `kind` and `id` bins, scanned every `refresh-seconds` and kept in memory; no document is read |
| searches, pick lists, derived kinds (desk P&L), impact | one day's promoted bins, read by a scan the server filters by `kind` and `date`, its 4096 partitions split into `scan-threads` ranges scanned at once; kept in memory (`columns-cache-mb`) for `columns-seconds`; after each refresh the newest day is read in the background |
| reverse lookups (linked entities) | the day's promoted bins that hold the target's id (`nettingSet`, `book`, `counterparty.id`); a kind without promoted bins scans that day's documents instead |

A `snapshot` kind takes the kind's newest date on or before the date asked, within `lookback-days`; an entity with
no record that day is *gone*. An `effective` kind takes the entity's own newest date on or before it. Promoted bins
serve only `snapshot` kinds: an `effective` kind's searches and reverse lookups read documents. A search that names
a field the pack does not promote reads documents one at a time, which is slow on a large book.

The connector runs at most two of these day scans at a time; a further one waits for one of them to finish.

### Configure it

**Site form, through the `aerospike` profile.** `application-aerospike.yaml` points the banking packs' stores at
Aerospike, naming only the plugin, hosts and namespace. The pack's other settings stay: its `domain` becomes the set
and its `layout` the promoted bins:

```yaml
drishti:
  sources:
    connectors:
      trading-store:
        plugin: aerospike                                     # replaces the pack's "delta"
        settings:
          hosts: "${DRISHTI_AEROSPIKE_HOSTS:localhost:3000}"  # host:port, comma-separated
          namespace: "${DRISHTI_AEROSPIKE_NAMESPACE:test}"
          # set: not given, so the pack's domain (trading) is the set
          # layout: the pack's layout.trade.columns are the promoted bins
```

**Pack form:**

```yaml
connectors:
  trading-store:
    plugin: aerospike
    kinds: [trade]
    settings:
      hosts: ${DRISHTI_AEROSPIKE_HOSTS:localhost:3000}
      namespace: ${DRISHTI_AEROSPIKE_NAMESPACE:test}
      set: trading                 # the domain: sets trading, trading_ix and trading_kinds
      layout:
        trade:
          columns: [tradeId, mtm, book, desk, counterparty.id, counterparty.name, nettingSet, risk.dv01]
      refresh-seconds: 60          # re-read the kinds' dates and the ids
      scan-threads: 8              # partition ranges scanned at once for a day's promoted bins
      columns-cache-mb: 1024       # memory for days of promoted bins
      columns-seconds: 300         # a day's bins are read again after this
      # user: ${AS_USER}            # security-enabled clusters
      # password: ${AS_PASSWORD}
routes:
  trade: trading-store
```

| Setting | Default | What it does |
|---|---|---|
| `hosts` | `localhost:3000` | Seed hosts, `host:port`, comma-separated. |
| `namespace` | `test` | The namespace. |
| `set` | the `domain` setting, else `drishti` | The domain's set; the index and kinds sets are `<set>_ix` and `<set>_kinds`. |
| `kinds` | what the kinds set lists | Kinds served; a read of another kind finds nothing. |
| `mode.<kind>` | `snapshot` | `snapshot` or `effective` (above). |
| `lookback-days` | `10` | How far back a `snapshot` kind looks for its newest date. |
| `refresh-seconds` | `60` | How often the kinds' dates and the ids are re-read. |
| `layout.<kind>.columns` | none | The promoted fields; must match what the loader wrote. |
| `scan-threads` | `8` | Partition ranges scanned at once when a day's bins or documents are scanned. |
| `columns-cache-mb` | `1024` | Memory for days of promoted bins. |
| `columns-seconds` | `300` | A day's bins are read again after this. |
| `reverse-index` | `true` | `false` turns reverse lookups off (the connector reports no reverse capability). |
| `max-load-rows` | `200000` | The record limit of a reverse-lookup scan over documents (kinds without promoted bins). |
| `connect-timeout-ms` | `3000` | Connection timeout. |
| `user`, `password` | none | For security-enabled clusters. |
| `source-name` | `aerospike` (a connector: its name) | The provenance source. |

### Load sample data

```bash
docker compose -f deploy/compose.data.yaml up -d aerospike
tools/load-aerospike.sh localhost:3000 test                                 # 1,791 documents x 10 business days
tools/load-aerospike.sh localhost:3000 test --trades 1000000 --days 3       # and a book of a million trades a day
SPRING_PROFILES_ACTIVE=aerospike DRISHTI_PACKS=counterparty-risk,market-risk \
  java -jar drishti-server/target/drishti-server-*-exec.jar
```

`load-aerospike.sh [hosts] [namespace] [--ttl-days N] [--trades N --days D]` runs
`make_data.py --jsonl data/banking.jsonl` (it prints `jsonl: <n> rows in data/banking.jsonl`), builds the plugin with
`./mvnw -o` and runs `AerospikeLoader`. With `--trades N` it then streams `bulk_trades.py --trades N --days D --jsonl -`
(`--days` defaults to 3) straight into a second loader on standard input, so no file of gigabytes is written. The
loader reads one JSON line per entity per date:

```json
{"domain": "trading", "kind": "trade", "id": "MX-20000001", "date": "2026-09-30", "doc": "{\"tradeId\": …}",
 "columns": {"mtm": 1875863, "book": "BOOK-RATES-3", "counterparty.name": "Meridian Reinsurance Ltd", …}}
```

It writes each line's day record into the set named by `domain` and adds the date to the entity's index record,
keeping 128 writes in flight, and records each kind's dates once at the end. A file name of `-` reads standard input.
The loader prints `aerospike: <n> rows (<s> s)` every 100,000 rows and
`aerospike: loaded <n> rows into namespace test in <s> s` at the end. Your own loader can write the same layout; it
must name promoted bins by the rule above.

Count what was loaded with `asinfo`, which the server image includes:

```bash
docker compose -f deploy/compose.data.yaml exec aerospike asinfo -v sets/test/trading
# objects=7500:tombstones=0:…   after the sample load: 750 trades x 10 business days
```

`sets/test/trading_ix` counts the trades (one index record each) and `sets/test/trading_kinds` the kinds.

**Retention.** `--ttl-days N` gives every record written a time to live of N days, so Aerospike expires old business
dates itself; no maintenance job runs. Without it, records never expire. An index record's time to live restarts at
each write, so it outlives its oldest days; a read of an expired day finds no document and the entity is *gone* that
day.

### In the terminal

`TRD MX-20000001 <GO>`, dated. Type-ahead uses the ids the last refresh read, so a record written after it is
readable at once but offered by type-ahead after `refresh-seconds`. The first search of a day waits for its promoted
bins unless the background read has them already; later ones read memory.

### Health, and when the cluster goes down

`health` is `UP` or `DOWN: not connected to Aerospike`. The client tends the cluster in the background and does not
fail at start if the cluster is down; reads fail (`DRS-1003`) until it reconnects, and the next refresh refills the
catalogue. Cache figures: `kinds`, `datesIndexed`, `ids` and `columnSets` (days of promoted bins held). A purge
(Admin → Caches) forgets the promoted bins and refreshes the dates and ids now.

### Common errors

| You see | Cause | Fix |
|---|---|---|
| health `DOWN: not connected to Aerospike` | wrong `hosts`, port closed, cluster down | `nc -z <host> 3000` |
| the container exits at start | too few file descriptors | `deploy/compose.data.yaml` sets `nofile` to 15000; do the same |
| a snapshot kind shows *gone* for one entity | it has no record `kind/id/yyyyMMdd` for the kind's newest date | write every entity each date, or use `mode.<kind>: effective` |
| searches are slow, or read documents | the fields searched are not all in `layout.<kind>.columns`, or the kind is `effective` | promote them in the pack and reload; the bins must exist in the records |
| Aerospike error 22, `operation not allowed at this time` | too many scans at once on the cluster | the connector runs at most two scans at a time and a refused reverse-lookup scan returns no referrers; keep other scans of the namespace (other servers, tools) few |
| the loader fails with `max connections … would be exceeded` | a loader built before the connection limit was raised | rebuild: `load-aerospike.sh` builds the plugin first; the loader allows 256 connections per node, above its 128 writes in flight |
| memory | the promoted bins of the days read (`columns-cache-mb`) and one id per entity in the index set; it does not grow with the days kept | lower `columns-cache-mb`, or promote fewer fields; set `--ttl-days` so old entities' index records expire |

---

## 10. An event stream: `kafka`

[KAFKA_CONNECTOR.md](KAFKA_CONNECTOR.md) explains the connector in full: `state` and `ticks` modes, the index and disk cache, offsets, ordering, restarts, reconnection and every setting.

### The situation

Your trading system publishes each trade's latest state to a Kafka topic whenever it changes. You want trade views
to tick as messages arrive, and the full history from the lake when a user picks a date.

### How the connector reads Kafka

- It reads **every partition from the beginning** at start (the topic *is* the state: the latest message per entity
  wins, as with a compacted topic), then keeps reading. It uses no consumer group and commits nothing, so every
  Drishti server reads the whole topic on its own.
- Health is `UP (catching up)` until it has reached the end offsets seen at start, then `UP`.
- Memory stays small: it keeps *where* each entity's latest message is (tens of bytes per entity) and a cache of
  recently read documents (`cache-mb`, 256). A document nobody has opened is read back from Kafka by its offset when
  someone does.
- Documents are **live and undated**. The generation is the message offset.

### The data: two message shapes

Values are JSON text, keys are strings.

**Mapped** (a kind configured for the topic): the value is the whole document. The id is the message key or, for a
message without a key, the `id-field`. Keep the key equal to the id. (A compacted topic, such as every topic on the
compose broker below, refuses a message without a key: the producer gets `InvalidRecordException: Compacted topic
cannot accept message without key`.)

```text
topic: drishti.trading.trades
key:   MX-20000001
value: {"tradeId": "MX-20000001", "productType": "IRS_FIXFLOAT", "currency": "AUD", "notional": 242000000.0, "mtm": 1900000, "pnl1d": 24137}
```

**Envelope** (no kind for the topic): the value carries kind, id and document, so one topic can carry many kinds.
**Key envelopes `<kind>/<id>`**: Kafka compaction keeps the latest message per key, so the key must be unique per
entity across kinds, and a delete (a tombstone, which has no value to read the kind from) is only understood with
that key.

```text
topic: risk.envelopes
key:   netting-set/NS-ALDERSHOT-FRA
value: {"kind": "netting-set", "id": "NS-ALDERSHOT-FRA",
        "doc": {"nettingSetId": "NS-ALDERSHOT-FRA", "tradeCount": 3, "netMtm": -339550, "collateral": -229552, "pfePeak": 347288}}
```

**Deletes.** A tombstone (null value) deletes: on a mapped topic keyed by the id, on an envelope topic keyed
`<kind>/<id>`. An envelope with `"doc": null` deletes too. A value that is not JSON is skipped.

### Configure it

**Pack form**, as shipped in `packs/trading/pack.yaml` (off until `DRISHTI_STREAM_TRADING=true`):

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
      kind: trade                                            # every topic carries trade documents (mapped)
      id-field: tradeId                                      # the id field, for a message without a key
      disk-cache.enabled: ${DRISHTI_STREAM_DISK_CACHE:true}  # also keep documents in a RocksDB store on local disk
      disk-cache.root: ${DRISHTI_CACHE_ROOT:./data/cache}    # → ./data/cache/trading-stream
      disk-cache.max-gb: ${DRISHTI_STREAM_CACHE_GB:10}       # disk budget; the oldest go first beyond it
      disk-cache.reset-at: ${DRISHTI_CACHE_RESET_AT:02:00}   # cleared nightly (never: never)
      disk-cache.zone: America/New_York
```

**Site form**, an envelope topic for counterparty-risk kinds, plus one mapped topic, on a secured cluster:

```yaml
drishti:
  sources:
    connectors:
      risk-stream:
        plugin: kafka
        kinds: [netting-set, credit-limit]                  # limit what it serves (see the warning below)
        settings:
          bootstrap-servers: kafka1.bank.example:9093,kafka2.bank.example:9093
          topics: risk.envelopes,risk.limits                # comma list
          kind.risk.limits: credit-limit                    # this topic: whole credit-limit documents (mapped)
          id-field.risk.limits: limitId                     # its id field; risk.envelopes has no kind: envelopes
          cache-mb: 512                                     # recently read documents in memory
          client.security.protocol: SASL_SSL                # any Kafka consumer property: client.<property>
          client.sasl.mechanism: SCRAM-SHA-512
          client.sasl.jaas.config: "${KAFKA_JAAS}"          # the secret from the environment
```

Give a Kafka connector `kinds:`. Without it, an envelope connector serves *every* kind until messages arrive, so
it is asked (and answers "not held") for every read.

### Try it

```bash
docker compose -f deploy/compose.data.yaml up -d kafka            # single-node KRaft broker, compaction on
uv run --with kafka-python python tools/samplegen/stream.py \
    --bootstrap localhost:9092 --topic drishti.trading.trades --rate 5 --seconds 0
```

`stream.py` publishes every sample trade once, keyed by `tradeId`, prints
`published <n> trades to drishti.trading.trades; ticking 5.0/s`, then gives five random trades a second a new
`mtm` and `pnl1d`. `--seconds 0` runs until you press Ctrl+C. In another terminal:

```bash
DRISHTI_STREAM_TRADING=true DRISHTI_DEMO_ENABLED=false DRISHTI_PACKS=counterparty-risk,market-risk \
  java -jar drishti-server/target/drishti-server-*-exec.jar
```

Send one envelope by hand, to a connector reading `risk.envelopes` (such as the `risk-stream` site form above, with
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

### In the terminal

`TRD MX-20000001 <GO>` on Live: the *Live* badge shows, provenance is `trading-stream`, and the MTM moves every time
`stream.py` touches the trade. Pick a date: the same trade comes from `trading-store` (the lake), because dated
connectors go first for a picked date, and the view is a static snapshot.

Why `DRISHTI_DEMO_ENABLED=false`? The trading pack's samples hold the same trade ids. A real stream is asked before
the samples on Live, so Kafka answers anyway; switching the demo off also keeps the samples out of search and makes
sure a trade Kafka does not hold falls through to the lake rather than to a random-walk sample.

### Health, and when the broker goes down

| Health | Meaning |
|---|---|
| `DOWN: not started` | the consumer has not connected yet |
| `UP (catching up)` | reading the topic from the beginning |
| `UP` | caught up; new messages are applied as they arrive |
| `DOWN: no connection to the broker (reconnecting)` | the broker has been unreachable for 10 s while the connector ran; the Kafka client keeps reconnecting by itself |
| `DOWN: IllegalStateException: topic risk.envelopes has no partitions yet (reconnecting)` | the topic does not exist (and the broker does not create topics automatically) |
| `DOWN: <Exception>: <message> (reconnecting)` | the consumer gave up (broker down at start, fatal error) |

The Kafka client rides out a broker outage by itself and carries on where it was when the broker is back (health
returns to `UP`); blips shorter than 10 s do not show in health. When the client gives up, a supervisor waits (1 s,
doubling to 30 s), creates a new consumer and **resumes at the last offset it applied** (no replay). While the broker
is away, documents in the memory or disk cache still answer; others are read back from Kafka by offset, so those
reads fail (`DRS-1004`) until it returns. Cache figures:
`indexedEntities`, `memoryEntries`, `memoryMb`, and with the disk cache `diskMb`, `diskHits`, `diskMisses`,
`diskClears`.

### Common errors

| You see | Cause | Fix |
|---|---|---|
| the log says `source plugin kafka is installed but not configured (kafka needs settings.topics); it stays idle` | the plugin runs as itself with no `topics` | harmless; or configure it |
| health stays `UP (catching up)` | a very large topic, or messages arriving faster than read | wait; watch `indexedEntities` grow |
| the view does not tick | the trade is answered by another live source (the samples) | `DRISHTI_DEMO_ENABLED=false`, or check `provenance.source` |
| an envelope is ignored | not JSON, or no `kind`/`id` | validate the value with `jq` |
| a delete does not delete | envelope tombstone keyed by the bare id | key envelopes `<kind>/<id>` |
| `DOWN: … TimeoutException …` behind TLS/SASL | missing `client.*` security settings | copy the properties your other consumers use, prefixed `client.` |

---

## 11. A message queue: `activemq`

[ACTIVEMQ_CONNECTOR.md](ACTIVEMQ_CONNECTOR.md) explains the connector in full: queues and durable topics, the state store, acknowledgement, failover and every setting.

### The situation

Your limit-management system publishes each credit limit to the ActiveMQ Classic queue `limits` whenever it
changes. You want `LIM <id>` to show the latest version, live.

### How message-queue connectors keep data

A queue delivers each message **once** and keeps no history. So the connector keeps the latest document of every
entity itself, in a **state store** on local disk (RocksDB, `./data/state/<connector>`), with recent documents in
memory (`cache-mb`, 128). The store survives restarts. Nothing is lost while Drishti is down: queued messages wait in
the broker, and topics are read through durable subscriptions. Each message is acknowledged **after** it is stored,
so a crash redelivers rather than loses.

### The data

A `TextMessage` (or `BytesMessage`, read as UTF-8) whose body is JSON, with optional **string properties**:

| Property | Meaning |
|---|---|
| `id` | the entity id; if absent, the body's id field (`id-field.<destination>`, else `id-field`, default `id`) |
| `deleted` | `true` deletes the entity |

On `limits`, configured with a kind (mapped):

```text
destination: queue://limits
property id: LIM-ALDERSHOT                        (optional: else the body's limitId)
body:        {"limitId": "LIM-ALDERSHOT", "counterparty": "CP-ALDERSHOT", "limit": 338000000.0,
              "used": 241900000, "utilisation": 0.7157, "status": "Within limit"}
```

On a destination without a kind, the body is an envelope `{"kind": "credit-limit", "id": "LIM-ALDERSHOT", "doc": {…}}`
(the `id` property stands in for a missing `"id"`).

| Message | Becomes |
|---|---|
| a JSON body on a mapped destination, with an id (property or field) | that kind's document, stored and pushed to open views |
| a JSON envelope on any other destination | the envelope's entity |
| property `deleted=true`, or an envelope with `"doc": null` | a delete |
| an empty body with an `id` property, on a mapped destination | a delete |
| anything else (not JSON, no kind, no id) | skipped and counted in `rejected` |

### Configure it

**Pack form:**

```yaml
connectors:
  limits-mq:
    plugin: activemq
    enabled: ${DRISHTI_LIMITS_MQ:false}                       # off until switched on
    kinds: [credit-limit]
    settings:
      broker-url: ${AMQ_URL:failover:(tcp://localhost:61616)} # failover reconnects by itself
      user: ${AMQ_USER:}
      password: ${AMQ_PASSWORD:}
      destinations: queue:limits,topic:limit-breaches         # queue:<name>, topic:<name>, or a bare name (a queue)
      kind.limits: credit-limit                               # destination name without queue:/topic:
      id-field.limits: limitId
      kind.limit-breaches: credit-limit
      id-field.limit-breaches: limitId
      client-id: drishti-prod-1-limits-mq                     # unique per server; durable topic subscriptions use it
      state.max-gb: 20                                        # disk budget of the state store
```

**Site form**, the same under `drishti.sources.connectors` (bracket and quote a key that has characters other than
letters, digits, `-` and `.`, for example `"[kind.desk_limits]": credit-limit`):

```yaml
drishti:
  sources:
    connectors:
      limits-mq:
        plugin: activemq
        kinds: [credit-limit]
        settings:
          broker-url: "failover:(tcp://mq1.bank.example:61616,tcp://mq2.bank.example:61616)"
          user: ${AMQ_USER}
          password: ${AMQ_PASSWORD}
          destinations: queue:limits
          kind.limits: credit-limit
          id-field.limits: limitId
          cache-mb: 128                       # recent documents in memory
          state.root: /var/lib/drishti/state  # the store goes to /var/lib/drishti/state/limits-mq
```

Two Drishti servers on the same broker need different `client-id`s (ActiveMQ refuses a second connection with the
same id); each then has its own durable subscription to each topic, named `drishti-<connector>-<topic>`. Two servers
reading the same **queue** share its messages, so each would hold only part of the entities: give each server its own
queue (or read a topic).

### Try it

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

### In the terminal

`LIM LIM-ALDERSHOT <GO>`: provenance `limits-mq`, live; send another message and the view updates. The type-ahead
lists every entity received; one only the queue holds has the subtitle `credit-limit · limits-mq` (an id a dated store
also holds, such as `LIM-ALDERSHOT`, shows that store's subtitle). Pick a date: the lake (`credit-store`)
answers, since dated connectors go first.

### Health, and when the broker goes down

| Health | Meaning |
|---|---|
| `DOWN: not started` | before the first connection attempt |
| `DOWN: connecting to failover:(tcp://localhost:61616)` | connecting (the failover transport waits for the broker) |
| `UP` | consuming |
| `DOWN: connection to the broker lost (reconnecting)` | the failover transport lost the broker and is reconnecting |
| `DOWN: <Exception>: <message> (reconnecting)` | any other failure; a supervisor rebuilds the session (1 s doubling to 30 s) |

While the broker is down, views keep showing the stored documents (the state store answers reads) and stop ticking.
Cache figures: `entities`, `memoryEntries`, `stateMb`, `received`, `rejected`.

### Common errors

| You see | Cause | Fix |
|---|---|---|
| `DOWN: … activemq plugin needs settings.destinations (reconnecting)` | `destinations` empty | set it |
| `rejected` grows | bodies that are not JSON, or no id (no `id` property and no id field) | check `id-field.<destination>` matches the body |
| `DOWN: … InvalidClientIDException …` | another server uses the same `client-id` | a unique `client-id` per server |
| old entities still shown | the state store keeps them until a delete arrives | send deletes; or `state.reset-at` (daily clearing), or stop the server and delete `data/state/<connector>` |

---

## 12. A message queue: `rabbitmq`

[RABBITMQ_CONNECTOR.md](RABBITMQ_CONNECTOR.md) explains the connector in full: queues and bindings, prefetch and acknowledgement, the state store, recovery and every setting.

### The situation

Your collateral system publishes margin calls to the RabbitMQ exchange `collateral` with routing keys like
`margin.call.new`. You want them as `margin-call` entities (`MC`), live.

### The data

The body is JSON (UTF-8). The id is the `id` **header**, else the message's `message_id` property, else the body's
id field; a `deleted` header of `true` deletes. Envelopes and the rejected cases are as for
[ActiveMQ](#11-a-message-queue-activemq).

```text
exchange:    collateral          routing key: margin.call.new
queue:       drishti.margin-calls
headers:     {"id": "MC-ALDERSHOT-FRA-2"}          (optional)
body:        {"callId": "MC-ALDERSHOT-FRA-2", "callDate": "2026-09-30", "marginType": "Variation",
              "direction": "They call", "amount": 250000.0, "status": "Issued", "nettingSet": "NS-ALDERSHOT-FRA",
              "csa": "CSA-ALDERSHOT", "currency": "USD"}
```

### Configure it

**Pack form:**

```yaml
connectors:
  margin-mq:
    plugin: rabbitmq
    enabled: ${DRISHTI_MARGIN_MQ:false}
    kinds: [margin-call]
    settings:
      uri: ${RABBIT_URI:amqp://guest:guest@localhost:5672/%2f}   # amqps:// for TLS
      queues: drishti.margin-calls                                 # comma list of queues to consume
      declare: true                                                # declare each queue durable, and bind it
      bind.drishti.margin-calls: collateral:margin.#               # exchange:routing.key (the exchange must exist)
      kind.drishti.margin-calls: margin-call
      id-field.drishti.margin-calls: callId
      prefetch: 100                                                # unacknowledged messages in flight
```

**Site form:** the same block under `drishti.sources.connectors`, with the secret from the environment:

```yaml
drishti:
  sources:
    connectors:
      margin-mq:
        plugin: rabbitmq
        kinds: [margin-call]
        settings:
          uri: ${RABBIT_URI}                       # amqps://drishti:…@rabbit.bank.example:5671/collateral
          queues: drishti.margin-calls
          declare: false                           # use the queue your platform team created; bind.* is ignored
          kind.drishti.margin-calls: margin-call
          id-field.drishti.margin-calls: callId
          heartbeat-seconds: 20
          recovery-interval-ms: 2000
```

### Try it

```bash
docker run -d --name rabbit -p 5672:5672 -p 15672:15672 rabbitmq:management
curl -u guest:guest -X PUT -H 'content-type: application/json' \
  http://localhost:15672/api/exchanges/%2f/collateral -d '{"type":"topic","durable":true}'
DRISHTI_MARGIN_MQ=true java -jar drishti-server/target/drishti-server-*-exec.jar    # it declares and binds the queue
curl -u guest:guest -H 'content-type: application/json' -X POST \
  http://localhost:15672/api/exchanges/%2f/collateral/publish \
  -d '{"routing_key":"margin.call.new","properties":{"headers":{"id":"MC-ALDERSHOT-FRA-2"}},"payload_encoding":"string",
       "payload":"{\"callId\":\"MC-ALDERSHOT-FRA-2\",\"amount\":250000.0,\"status\":\"Issued\",\"nettingSet\":\"NS-ALDERSHOT-FRA\"}"}'
```

The publish answers `{"routed":true}` when the binding matched.

### In the terminal

`MC MC-ALDERSHOT-FRA-2 <GO>`: provenance `margin-mq`, live. Publish again with `"status":"Agreed"` and the view
updates.

### Health, and when the broker goes down

| Health | Meaning |
|---|---|
| `DOWN: <Exception>: <message> (retrying)` | the first connection has not succeeded yet; retried 1 s doubling to 30 s |
| `UP` | consuming |
| `DOWN: connection lost (recovering)` | the client's automatic recovery is reconnecting and re-subscribing |
| `DOWN: consumer cancelled on drishti.margin-calls` | the broker cancelled the consumer (for example, the queue was deleted) |

Stored documents keep answering reads while the broker is away.

### Common errors

| You see | Cause | Fix |
|---|---|---|
| `DOWN: IOException: … (retrying)`, and the RabbitMQ log says `NOT_FOUND - no exchange 'collateral'` | binding to an exchange that does not exist | create the exchange, or fix `bind.<queue>` |
| `DOWN: … ACCESS_REFUSED …` | user or vhost permissions (`%2f` is the default vhost `/`) | check the URI's user and vhost |
| `DOWN: IOException: … (retrying)`, and the RabbitMQ log says `PRECONDITION_FAILED - inequivalent arg` | the queue exists with other settings | `declare: false` to use it as it is |
| nothing arrives | the queue is not bound | `bind.<queue>: exchange:key`, or bind it on the broker |

---

## 13. An object store: `s3`

[S3_CONNECTOR.md](S3_CONNECTOR.md) explains the connector in full: the key layout, listing and caching, credentials, cost and every setting.

### The situation

Your risk engine writes each stress result as a JSON object to a bucket, one folder per business date. You want
them as `stress-result` entities (`STR`, market-risk pack), dated by folder.

### The data

The `file` layout, in a bucket: `<prefix><kind>/<id>.json` (undated) and `<prefix><yyyy-MM-dd>/<kind>/<id>.json`.
Only `.json` objects are read.

```text
s3://risk-docs/eod/stress-result/STR-CLIMATE-2026Q3.json                 undated
s3://risk-docs/eod/2026-09-29/stress-result/STR-CLIMATE-2026Q3.json      for 29 September
s3://risk-docs/eod/2026-09-30/stress-result/STR-CLIMATE-2026Q3.json      for 30 September
```

```json
{"resultId": "STR-CLIMATE-2026Q3", "scenarioName": "Disorderly transition 2026Q3", "pnl": -41250000,
 "limit": -60000000.0, "scenario": "SCN-NGFS-DISORDERLY", "desk": "DESK-RATES"}
```

### Configure it

**Pack form:**

```yaml
connectors:
  risk-docs:
    plugin: s3
    enabled: ${DRISHTI_RISK_DOCS:false}
    kinds: [stress-result]
    settings:
      bucket: ${RISK_DOCS_BUCKET:risk-docs}   # required
      prefix: eod/                            # optional; a trailing / is added
      region: us-east-1
      rescan-seconds: 60                      # list kinds, ids and date folders (for search and dates)
      cache-seconds: 30                       # how long a read, or a miss, is cached
      lookback-days: 10                       # as file
```

**Site form**, an S3-compatible store with static credentials from the environment:

```yaml
drishti:
  sources:
    connectors:
      risk-docs:
        plugin: s3
        kinds: [stress-result]
        settings:
          bucket: risk-docs
          prefix: eod/
          endpoint: https://minio.bank.example    # S3-compatible store; path-style addressing (path-style: true)
          access-key: ${S3_ACCESS_KEY}            # otherwise the AWS chain: environment, profile, instance role
          secret-key: ${S3_SECRET_KEY}
```

The shipped `application.yaml` also has the plugin as itself, off (`DRISHTI_S3_ENABLED`).

### Try it

```bash
docker run -d --name minio -p 9000:9000 -p 9001:9001 minio/minio server /data --console-address :9001
export AWS_ACCESS_KEY_ID=minioadmin AWS_SECRET_ACCESS_KEY=minioadmin
aws --endpoint-url http://localhost:9000 s3 mb s3://risk-docs
aws --endpoint-url http://localhost:9000 s3 cp STR-CLIMATE-2026Q3.json \
    s3://risk-docs/eod/2026-09-30/stress-result/STR-CLIMATE-2026Q3.json
# connector settings: endpoint: http://localhost:9000 (credentials from the same environment variables)
DRISHTI_RISK_DOCS=true java -jar drishti-server/target/drishti-server-*-exec.jar
```

### In the terminal

`STR STR-CLIMATE-2026Q3 <GO>`, dated by folder. The generation is the object's last-modified time.

### Health, and when the store goes down

`health` is `UP`, or `DOWN: <exception>: <message> (retrying)` after a failed listing or read. Each call connects
afresh, so it recovers by itself. Cache figures: `cachedObjects`, `indexed`, `datedFolders`; a purge empties the read
cache.

### Common errors

| You see | Cause | Fix |
|---|---|---|
| `s3 plugin needs settings.bucket` under `failedToStart` | no bucket | set `bucket` |
| `DOWN: … 403 …` | credentials or bucket policy | `aws s3 ls s3://<bucket>/<prefix>` with the same credentials |
| a new object is not found | a miss is cached for `cache-seconds` | wait, or purge the connector's cache |
| a new date folder is ignored | date folders are listed every `rescan-seconds` | wait, or lower it (listing large buckets costs requests) |

---

## 14. Public market data: `feed`

[FEEDS_CONNECTOR.md](FEEDS_CONNECTOR.md) explains each feed in full: the source it calls, the entities it makes, schedules, history, offline use and every setting.

### The situation

You want real public rates next to your own data: NY Fed SOFR, ECB €STR, ECB reference FX rates, the US Treasury par
curve and FRED series. The `market-data` pack declares one connector per feed, each **off until its variable is
set**. Entity ids name the feed (`FIX-SOFR-NYFED`), so real data never collides with your ids.

### Switch them on

| Variable | Connector | Kind and ids |
|---|---|---|
| `DRISHTI_FEED_NYFED_SOFR=true` | `nyfed-sofr-feed` | `rate-fixing` `FIX-SOFR-NYFED` (last 60 fixings) |
| `DRISHTI_FEED_ECB_ESTR=true` | `ecb-estr-feed` | `rate-fixing` `FIX-ESTR-ECB` (last 60) |
| `DRISHTI_FEED_ECB_FX=true` | `ecb-fx-feed` | `fx-spot` `FX-EURUSD-ECB`, `FX-EURGBP-ECB`, `FX-EURJPY-ECB`, `FX-EURCHF-ECB`, `FX-GBPUSD-ECB`, `FX-USDJPY-ECB`, `FX-USDCHF-ECB`, `FX-AUDUSD-ECB`, `FX-USDCAD-ECB`, `FX-USDCNH-ECB` (90 days) |
| `DRISHTI_FEED_US_TREASURY=true` | `us-treasury-feed` | `ir-curve` `CRV-USD-UST` (about two months) |
| `DRISHTI_FEED_FRED=true` plus `FRED_API_KEY` (free, from the St. Louis Fed) | `fred-feed` | `rate-fixing` `FIX-FRED-<series>`, series from `DRISHTI_FRED_SERIES` (default `DGS10,DFF`) |

```bash
DRISHTI_PACKS=counterparty-risk,market-risk \
DRISHTI_FEED_NYFED_SOFR=true DRISHTI_FEED_ECB_FX=true DRISHTI_FEED_US_TREASURY=true \
  java -jar drishti-server/target/drishti-server-*-exec.jar
```

Each feed is fetched at start and every `refresh-minutes` (60), and keeps its recent history for picked dates.

### The configuration, as shipped

```yaml
# packs/market-data/pack.yaml (one of five)
connectors:
  nyfed-sofr-feed:
    plugin: feed
    enabled: ${DRISHTI_FEED_NYFED_SOFR:false}       # off until the variable is true
    kinds:
    - rate-fixing
    settings:
      feed: nyfed-sofr                              # which feed: nyfed-sofr, ecb-estr, ecb-fx, us-treasury, fred
      user-agent: ${drishti.branding.product:feed} public data feed connector   # sent with each request
      refresh-minutes: 60
```

A site behind a proxy, or without internet, points a feed at a mirror or a file of the same format:

```yaml
drishti:
  sources:
    connectors:
      nyfed-sofr-feed:
        enabled: true                               # on, whatever the variable says
        settings:
          url: file:///srv/mirror/sofr-last-60.json # file: URLs are read directly; same format as the public API
          timeout-seconds: 20                       # HTTP request timeout (the default)
```

For `us-treasury` and `fred`, `url` is a comma list (one per month, or one per series in the order of `series`).

### What you see

Real output from a server with the three feeds above on:

```bash
curl -s http://localhost:18480/api/v1/entities/rate-fixing/FIX-SOFR-NYFED/raw | jq -c .provenance
curl -s "http://localhost:18480/api/v1/entities/rate-fixing/FIX-SOFR-NYFED/raw?asOf=2026-09-15" \
  | jq -c '{provenance, latest: .data.latest, first: .data.fixings[0]}'
```

```json
{"source":"nyfed-sofr-feed","generation":1790818508749,"fetchedAt":"2026-10-01T01:35:08.749439520Z","live":false,"businessDate":"2026-09-29"}
{"provenance":{"source":"nyfed-sofr-feed","generation":1790818508749,"fetchedAt":"2026-10-01T01:35:08.749439520Z","live":false,"businessDate":"2026-09-15"},
 "latest":0.0364,"first":{"date":"2026-09-15","rate":0.0364,"ratePct":3.64,"volumeBn":2952.0}}
```

The business date is the latest observation on or before the date asked (SOFR for a day is published the next
business day, so Live shows 29 September). A date before the history kept is *not held*, and the next connector
answers.

In the terminal: `FIX FIX-SOFR-NYFED <GO>`, `FX FX-EURUSD-ECB <GO>`, `CRV CRV-USD-UST <GO>`. Typing
`FX FX-EURUSD-E` offers `FX-EURUSD-ECB` with the subtitle `fx-spot · ecb-fx-feed (public feed)`.

### Health, and when the feed is unreachable

`DOWN: not fetched yet`, `UP`, `DOWN: the feed returned no data`, or `DOWN: <Exception>: <message>` (for example
`HTTP 503 from markets.newyorkfed.org`). A failed fetch **keeps the last good data**, so views go on working; the next
refresh tries again. Cache figures (real): `{"series": 10, "observations": 640, "fetchedAt": "2026-10-01T01:35:09.770409225Z"}`
for `ecb-fx-feed`. A purge (Admin → Caches) clears and refetches at once.

In `GET /api/v1/admin/health`, a feed you have not switched on is listed under its pack's `connectorsOff`
(`"connectorsOff": ["ecb-estr-feed", "fred-feed"]`), not as a failure.

### Common errors

| You see | Cause | Fix |
|---|---|---|
| `FIX FIX-SOFR-NYFED` → `DRS-1001` | the feed is off | set its variable to `true`; restart |
| `DOWN: … ConnectException …` / `HttpTimeoutException` | no internet from the server, or a proxy | a mirror (`url: file://…`) or allow the host |
| `fred-feed` `DOWN: … HTTP 400 …` | no or bad `FRED_API_KEY` | get a free key |
| the log says `feed needs settings.feed … it stays idle` | the plugin runs as itself without `feed` | harmless; feeds run as the pack's named connectors |

---

## 15. The sample data: `demo`

[DEMO_CONNECTOR.md](DEMO_CONNECTOR.md) explains the connector in full: where samples come from, live ticks, routing, memory and every setting.

The `demo` plugin serves every enabled pack's `samples/` folder, live: documents tick while someone watches (fields
named in each sample's `_meta.walk` random-walk every `tick-ms`, 400). It is the `default-route`, serves every kind,
and needs nothing installed. It is meant for trying Drishti, screenshots and tests, not for production data.

```yaml
# application.yaml (shipped)
drishti:
  sources:
    default-route: demo
    plugins:
      demo:
        enabled: ${DRISHTI_DEMO_ENABLED:true}   # DRISHTI_DEMO_ENABLED=false switches the samples off
```

**Switch it off in production** (`DRISHTI_DEMO_ENABLED=false`): otherwise an id your stores do not hold may be
answered by a sample with the same id, on Live and for dates older than your history (see the 1 August example in
[section 1](#how-a-request-picks-a-connector)). Its health is always `UP`; it has no cache figures.

---

## 16. Combining connectors

### History from a lake, live from Kafka, for the same kind

This is what the trading pack ships: `trading-store` (Delta Lake, dated) and `trading-stream` (Kafka, live), both
serving `trade`, with `routes: { trade: trading-store }`. With the stream on and the demo off, the candidates for
`trade` are the route (`trading-store`), then every other connector serving `trade` (`trading-stream`, and `file`,
which serves every kind). Then:

| The user asks for | Order tried | Who answers |
|---|---|---|
| Live | `trading-stream` (live first) → `trading-store` → `file` | Kafka; the view ticks from Kafka. A trade Kafka does not hold (matured, compacted away) falls through to the lake's newest date |
| a picked date | `trading-store` (dated first) → `file` → `trading-stream` | the lake, for that date; a static snapshot |
| a date the lake does not hold | as above | `file` if it has a dated folder; else Kafka's current document, with the banner *trading-stream is not a dated source* |

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

---

## 17. Operating connectors

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
| `rest` | `UP`, `DOWN: not started` |
| `jdbc` | `UP`, `DOWN: not started`, `DOWN: <error> (reconnecting)` |
| `delta` | `UP`, `DOWN: cannot reach <root>/<domain>`, `DOWN: no Delta tables under <root>/<domain>` |
| `aerospike` | `UP`, `DOWN: not connected to Aerospike` |
| `kafka` | `DOWN: not started`, `UP (catching up)`, `UP`, `DOWN: no connection to the broker (reconnecting)`, `DOWN: <message> (retrying)`, `DOWN: <Exception>: <message> (reconnecting)` |
| `activemq` | `DOWN: not started`, `DOWN: connecting to <broker-url>`, `UP`, `DOWN: connection to the broker lost (reconnecting)`, `DOWN: <message> (reconnecting)`, `DOWN: <Exception>: <message> (reconnecting)` |
| `rabbitmq` | `DOWN: not started`, `DOWN: <Exception>: <message> (retrying)`, `UP`, `DOWN: connection lost (recovering)`, `DOWN: consumer cancelled on <queue>` |
| `s3` | `UP`, `DOWN: <error> (retrying)` |
| `feed` | `DOWN: not fetched yet`, `UP`, `DOWN: the feed returned no data`, `DOWN: <Exception>: <message>` |

Every connector recovers without restarting Drishti, and every one starts even when its store is down (see
[Reconnecting](PLUGIN_GUIDE.md#reconnecting)).

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
| `feed` | clears and refetches now |

Purge after you correct data in place (a restated lake date) when you do not want to wait for `refresh-seconds`.

### Disk state

| Folder | Whose | Can it be deleted? |
|---|---|---|
| `./data/state/<connector>` (`state.root`, `state.dir`) | ActiveMQ and RabbitMQ state stores | only deliberately, server stopped: it is the only copy of what the queues delivered |
| `./data/cache/<connector>` (`DRISHTI_CACHE_ROOT`, `disk-cache.dir`) | Kafka disk cache | yes: it starts empty on every run anyway (the topic is replayed) |

Put both on local disk, not a network share, and size them: `state.max-gb` and `disk-cache.max-gb` (10 each) are
budgets beyond which the **oldest** files are dropped, so set the state store's budget well above what its entities
need. `state.reset-at: "06:00"` clears a state store daily, for state that should start empty each day.

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

- **Memory per connector**: `delta` `cache-mb` (512) of partitions; `kafka` `cache-mb` (256) plus tens of bytes per
  entity for its index; `activemq`/`rabbitmq` `cache-mb` (128); `aerospike` `columns-cache-mb` (1024) of promoted
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

---

## 18. Troubleshooting

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
| A picked date shows *… is not a dated source: this shows its current data* | `curl -s "$B/entities/<kind>/<id>/raw?asOf=<date>" \| jq .provenance` | the dated store does not hold that date (`lookback-days`, history kept); load it |
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
| Feed `DOWN: not fetched yet` for minutes | the server's outbound internet | a proxy rule, or `url: file://…` |
| The overall status is `DEGRADED` | `curl -s $B/admin/health \| jq '{summary, failedToStart, packs: [.packs[] \| select(.status!="OK") \| {name, connectorsDown, sutraProblems}]}'` | fix what is listed; a switched-off connector does not degrade |
| Admin health answers `403` | the caller is not an administrator | use `/api/v1/sources` (anyone), or an admin token |

If none of these fit, [TROUBLESHOOTING.md](../guides/TROUBLESHOOTING.md) covers the server and console generally, and
[PLUGIN_GUIDE.md](PLUGIN_GUIDE.md) has every setting of every plugin and the contract for writing your own.

---

## 19. More stores: `redis`, `mongodb`, `iceberg`

Each of these stores has its own design document with the layout, loading, every read path, sizing, measurements and
settings; this chapter gets you from nothing to a running view.

### Redis: today and recent days in memory, live

Use Redis for the newest business days and live updates, with Delta Lake behind it for history: a read or a search on a
day Redis does not hold goes to the next store for the kind.

```bash
docker run -d --name redis -m 2g -p 6379:6379 redis:8 --maxmemory 1gb
tools/load-redis.sh redis://localhost:6379                         # the samples, 10 business days
tools/load-redis.sh redis://localhost:6379 --trades 10000          # and 10,000 trades a day for 3 days
SPRING_PROFILES_ACTIVE=redis DRISHTI_PACKS=market-risk,counterparty-risk java -jar drishti-server/target/drishti-server-*-exec.jar
```

`--ttl-days N` lets Redis expire each day; `--publish` announces each written entity, so open views update. Health shows
`UP` with the catalogue counts, or `DOWN: <reason>` while Redis is unreachable. Full design:
[REDIS_CONNECTOR.md](REDIS_CONNECTOR.md).

### MongoDB: a document database

Use MongoDB when entities already live there as documents, or when you want replica sets and sharding without a
lake. One document per entity per business day (`_id` `trade/MX-20000001/20260930`), the pack's promoted fields beside
it, and a narrow `<domain>_columns` copy that searches read.

```bash
docker run -d --name mongo -m 2g -p 27017:27017 mongo:7 --wiredTigerCacheSizeGB 0.5
tools/load-mongodb.sh mongodb://localhost:27017 drishti                       # the samples, 10 business days
tools/load-mongodb.sh mongodb://localhost:27017 drishti --trades 10000        # and 10,000 trades a day for 3 days
SPRING_PROFILES_ACTIVE=mongodb DRISHTI_PACKS=market-risk,counterparty-risk java -jar drishti-server/target/drishti-server-*-exec.jar
```

`--keep-days N` deletes older days; `--ttl-days N` lets a TTL index expire them. MongoDB 8.0 does not start on some
recent Linux kernels; `mongo:7` does. Full design: [MONGODB_CONNECTOR.md](MONGODB_CONNECTOR.md).

### Apache Iceberg: the other lake format

Use Iceberg when your lake is Iceberg (Snowflake, AWS Glue and Athena, Dremio, Trino, Polaris). Tables follow the same
layout as Delta Lake: a table per kind, partitioned by business date, sorted by id, the pack's promoted columns beside
the document.

```bash
tools/load-iceberg.sh ./data/iceberg                                   # the samples, 10 business days
tools/load-iceberg.sh ./data/iceberg --trades 10000                    # and 10,000 trades a day for 3 days
SPRING_PROFILES_ACTIVE=iceberg DRISHTI_PACKS=market-risk,counterparty-risk java -jar drishti-server/target/drishti-server-*-exec.jar
```

A REST catalog instead of folders: `--catalog rest --uri https://catalog.example.com --warehouse risk --credential …`
on the loader, and `catalog: rest`, `uri`, `warehouse`, `credential` on the connector. Delete files are applied by every
read; *known at* reads the snapshot current then. Full design: [ICEBERG_CONNECTOR.md](ICEBERG_CONNECTOR.md).

