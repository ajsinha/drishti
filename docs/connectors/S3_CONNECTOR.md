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
# The S3 connector: one JSON object per entity in a bucket

The `s3` connector reads entity documents stored as JSON objects in Amazon S3 or any S3-compatible store (MinIO,
Ceph RGW, an on-premises appliance). The layout is the `file` connector's file-per-entity layout, in a bucket: one
object per entity, optionally one folder per business date. There is no index to build, no table format and no
loader: anything that can `PUT` an object can feed it. This document explains the layout, every read path and what
it costs in S3 requests, how far it scales, how it fails and recovers, its security, and every setting, exactly as
`S3SourcePlugin` (`plugins/drishti-plugin-s3`) implements them.

For a first setup see [CONNECTOR_GUIDE.md, chapter 13](CONNECTOR_GUIDE.md#13-an-object-store-s3); the settings in
brief are in [CONFIGURATION.md](../admin/CONFIGURATION.md#s3--documents-in-s3-or-an-s3-compatible-store). For a book
of a million entities a day in object storage use Delta Lake on `s3a://` instead ([DELTA_CONNECTOR.md](DELTA_CONNECTOR.md));
for files on local disk see [FILE_CONNECTOR.md](FILE_CONNECTOR.md).

## Contents

1. [When to use it](#1-when-to-use-it)
2. [The layout](#2-the-layout)
3. [What an object holds](#3-what-an-object-holds)
4. [Configuration](#4-configuration)
5. [How the connector reads](#5-how-the-connector-reads)
6. [Dates](#6-dates)
7. [Writing objects](#7-writing-objects)
8. [Scale, memory and request cost](#8-scale-memory-and-request-cost)
9. [S3 with a lake for history](#9-s3-with-a-lake-for-history)
10. [Failure and recovery](#10-failure-and-recovery)
11. [Security](#11-security)
12. [Limits and trade-offs](#12-limits-and-trade-offs)
13. [Diagnosing](#13-diagnosing)
14. [Settings](#14-settings)
15. [Checklist for production](#15-checklist-for-production)

---

## 1. When to use it

| Use it for | Prefer another store for |
|---|---|
| documents another system already writes to a bucket, one per entity (stress results, reports, model outputs) | a book of hundreds of thousands or millions of entities a day (Delta Lake on `s3a://`, Iceberg, PostgreSQL) |
| a few thousand to a few tens of thousands of objects in all, under the prefix | searches over fields, pick lists, desk totals and impact (needs promoted columns: Delta Lake, Iceberg, PostgreSQL, Aerospike) |
| reference documents kept by hand in a bucket, the same for every date (undated objects) | reverse lookups (*Linked entities*, F8 impact): this connector has none |
| sites where object storage is the only shared store, and no table format is wanted | data that changes during the day and must tick on screen (Kafka, ActiveMQ, RabbitMQ, Redis) |
| a short daily history (`lookback-days`), cleaned by a bucket lifecycle rule | years of history read by date (a lake keeps it compactly; see [section 9](#9-s3-with-a-lake-for-history)) |

What the connector can do, from its manifest (`SourceCapabilities(live = false, reverseLookup = false, search = true,
dated = true)`):

| Capability | Supported |
|---|---|
| open one entity, Live or on a picked date | yes: one `GET` per date folder tried, cached |
| type-ahead (search by id) | yes, from memory, built by listing the bucket |
| structured searches over fields | no promoted columns: the engine reads documents, one `GET` each |
| reverse lookups | no |
| live ticks | no |
| *known at* (time travel) | no: an overwritten object replaces the earlier one |

## 2. The layout

```
s3://risk-docs/                                        the bucket (setting `bucket`)
└── eod/                                               the prefix (setting `prefix`; a trailing / is added)
    ├── stress-result/
    │   └── STR-CLIMATE-2026Q3.json                    undated: serves every date
    ├── stress-scenario/
    │   └── SCN-NGFS-DISORDERLY.json                   undated
    ├── 2026-09-29/
    │   └── stress-result/
    │       ├── STR-COVID2020-COMM.json                for 29 September
    │       └── STR-GFC2008-RATES.json
    └── 2026-09-30/
        └── stress-result/
            ├── STR-COVID2020-COMM.json                for 30 September
            └── STR-GFC2008-RATES.json
```

The rules, as the listing in `rescan()` applies them to each key with the prefix removed:

| Key (after the prefix) | Read as |
|---|---|
| `<kind>/<id>.json` | an undated document of `<kind>` with id `<id>` |
| `<yyyy-MM-dd>/<kind>/<id>.json` | the document of `<kind>/<id>` for that business date |
| a key not ending in `.json` (`notes.txt`, `x.JSON`, `x.json.gz`) | ignored |
| a key with more levels (`stress-result/2026/x.json`), or three levels whose first is not a date | ignored |
| a key outside the prefix (`other/trade/T-9.json` when the prefix is `risk/`) | never listed, never read |

- The kind is the folder name, the id the object name without `.json`, both case-sensitive. An id cannot contain `/`.
- A date folder is any first-level folder whose name matches `\d{4}-\d{2}-\d{2}`. Its name must be a real date: a
  folder such as `2026-13-01/` makes every listing fail ([section 13](#13-diagnosing)).
- A first-level folder that is neither a date nor a kind (one level of `.json` objects under it) is taken as a kind.

## 3. What an object holds

One JSON document, whole. The connector parses it and hands it to Drishti unchanged; nothing in it is required, not
even the id (the id comes from the key). For example `eod/2026-09-30/stress-result/STR-COVID2020-COMM.json`, as the
market-risk pack's samples have it:

```json
{
  "resultId": "STR-COVID2020-COMM",
  "scenarioName": "COVID-19 March 2020",
  "pnl": -79275425,
  "limit": -75800000.0,
  "byBook": [
    {"book": "BOOK-COMM-1", "pnl": -24551466},
    {"book": "BOOK-COMM-2", "pnl": -40288036},
    {"book": "BOOK-COMM-3", "pnl": -14435923}
  ],
  "scenario": "SCN-COVID2020",
  "desk": "DESK-COMM"
}
```

Each document read carries this provenance:

| Field | Value |
|---|---|
| `source` | `source-name`: the connector's name for a named connector, `s3` for the plugin running as itself |
| `generation` | the object's `Last-Modified` time in epoch milliseconds (0 if the store gives none): rewriting an object raises it |
| `fetchedAt` | when it was read from S3 (a cached read keeps the time of the original `GET`) |
| `live` | `false` |
| `businessDate` | the date folder it came from; `null` for an undated object |

Only `.json` objects are read. Unlike the `file` connector there is no CSV form and no JSON-lines form.

## 4. Configuration

### 4.1 Pack form

A pack declares the connector in its `pack.yaml`, off until switched on, with the kinds it serves:

```yaml
# packs/<pack>/pack.yaml
connectors:
  risk-docs:
    plugin: s3
    enabled: ${DRISHTI_RISK_DOCS:false}
    kinds: [stress-result]
    settings:
      bucket: ${RISK_DOCS_BUCKET:risk-docs}   # required
      prefix: eod/                            # optional; a trailing / is added
      region: us-east-1
      rescan-seconds: 60                      # list date folders, kinds and ids
      cache-seconds: 30                       # how long a read, or a miss, is kept
      lookback-days: 10                       # oldest date folder used for a date asked
routes:
  stress-result: risk-docs
```

### 4.2 Site form

In `application.local.yaml`, an S3-compatible store with static credentials from the environment:

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
          access-key: ${S3_ACCESS_KEY}            # otherwise the AWS credential chain
          secret-key: ${S3_SECRET_KEY}
```

On AWS itself leave out `endpoint`, `access-key` and `secret-key`, set `region` to the bucket's region, and give the
server an instance role ([section 11](#11-security)).

### 4.3 The plugin as itself

The shipped `application.yaml` lists the plugin as itself, off:

```yaml
drishti:
  sources:
    plugins:
      s3:                           # documents in Amazon S3 or an S3-compatible store; off until configured
        enabled: ${DRISHTI_S3_ENABLED:false}
```

With `DRISHTI_S3_ENABLED=true` it takes the same settings under `drishti.sources.plugins.s3.settings` and is known as
`s3` (its `source-name`). Without `bucket` it fails to start with `s3 plugin needs settings.bucket` (listed under
`failedToStart`; the server still runs). Use named connectors for anything more than one bucket.

### 4.4 Always give `kinds`

The kinds the connector reports are those found by its last successful listing. Until a listing succeeds, and also
when a listing finds no objects at all, that set is empty, and an empty set means **every kind**: the connector is
then a candidate for every read in the server and spends a `GET` on each ([section 5.1](#51-opening-one-entity)). A
`kinds:` list on the connector replaces what the plugin reports and keeps it to those kinds whatever the bucket holds.

## 5. How the connector reads

### 5.1 Opening one entity

`STR STR-COVID2020-COMM` on 30 September, with date folders `2026-09-29` and `2026-09-30`:

1. The kind and id are checked: an empty value, `/`, `\`, `.` or `..` answers "not held" at once, so an id cannot
   reach outside its folder.
2. The connector walks its list of date folders, newest first, skipping folders after the date asked and folders
   older than the date minus `lookback-days` (calendar days). For each one left it reads
   `<prefix><date>/<kind>/<id>.json`; the first that exists answers.
3. If no date folder holds it, it reads the undated `<prefix><kind>/<id>.json`.
4. If that does not exist either, the answer is "not held" and the router asks the next connector.

Every object read goes through a cache keyed by the object key (`cache-seconds`, 30; `cache-entries`, 10,000). The
cache keeps **misses as well as hits**, and concurrent reads of the same key wait for one `GET` instead of each
sending one.

| Situation | S3 requests |
|---|---|
| the entity is in the newest date folder in range | 1 `GET` |
| it is only in an older folder, `n` folders back | `n + 1` `GET`s (the misses are `404`s) |
| it is only undated | one `GET` per date folder in range, plus one |
| it is held nowhere | the same as undated: every folder in range, plus one |
| any of these again within `cache-seconds` | none |

With daily folders and the default `lookback-days` of 10, a miss costs at most about 8 business-day folders plus the
undated key, so 9 `GET`s; each `GET` is a round trip to the store (a few milliseconds inside the same region, more
across the internet). The whole read, across every connector tried, must finish within
`drishti.sources.fetch-timeout` (2 s), so keep the bucket close to the server.

A view reads the entity, then the entities it links to (each its own read, the same way). The document is parsed in
full; there is no partial read of an object.

### 5.2 Type-ahead

From memory. Every `rescan-seconds` (60) the connector lists the whole prefix and keeps one entry per distinct
`(kind, id)` found in any folder, dated or undated, in Drishti's `HitIndex`. A hit's title is the id and its
subtitle `<kind> · <source-name>`. Matching is by id prefix first, then by id substring, then by the subtitle text,
without case. No request is sent to S3 per keystroke.

Type-ahead does not look at the business date: it offers every id the bucket holds under the prefix, including ids
that exist only on dates other than the one picked (opening one then says it is not held, or falls through to
another store).

### 5.3 Dated reads

See [section 6](#6-dates). A picked date is a static snapshot; the connector has no live push, so a view it answers
never ticks.

### 5.4 Searches, pick lists, derived kinds and impact

The connector has no promoted columns, so a structured search (`STR where pnl < -50m`) is answered the slow way by
the engine (`StructuredSearch`): it takes the kind's ids from the type-ahead index (at most `drishti.search.max-scan`,
20,000), reads every one of those documents through the router, one read as in section 5.1 each, and evaluates the
condition in memory, all within `drishti.search.budget` (3 s). Documents not read in time are left out and the result
says `partial: true`. A derived kind over an S3 kind likewise reads each member. This is acceptable for a kind of a
few hundred entities; for more, keep the kind in a store with columns.

### 5.5 Reverse lookups

None: the connector declares `reverseLookup = false`, so the router never asks it. *Linked entities* still shows the
links a document itself names (forward links, read as in [section 5.1](#51-opening-one-entity)); "what refers to this
entity" is answered only by other connectors.

### 5.6 Live push

None. For ticks on a kind kept in S3, add a Kafka connector in `ticks` mode for the same kind
([KAFKA_CONNECTOR.md](KAFKA_CONNECTOR.md)): the view reads from S3 and repaints on each message.

## 6. Dates

The connector is dated and has one behaviour for every kind (there is no `mode.<kind>` setting):

| The date asked | Answer |
|---|---|
| a date with a folder that holds the entity | that folder's object; `businessDate` is that date |
| a date whose folder lacks the entity (or a date with no folder) | the newest **older** folder that holds it, within `lookback-days`; `businessDate` is that older date |
| a date older than every folder in range, or beyond the lookback | the undated object, if any (`businessDate` null) |
| none of these | not held: the next connector is asked |

This is not the `file` connector's `snapshot` mode. There, an entity missing from the newest day on or before the
date is gone. Here, it is shown from an earlier folder until it falls out of the `lookback-days` window, and an
undated object answers for every date. The test `S3SourcePluginTest` checks the cases: with `T-1` in `2026-09-28/`
and `2026-09-29/`, 29 September reads `mtm` 99, 28 September reads 98, 30 September reads 99 (the newest on or
before); `T-2`, only in `2026-09-29/`, is not held on 28 September.

So to make an entity disappear from a date onward, delete its undated object and stop writing it into date folders;
it remains readable from the older folders for up to `lookback-days`.

The Live view asks for the current business date (the console resolves Live to it), so the same rules apply with
that date. A read with no date at all (an internal call that passes `AsOf.LATEST`) skips the lookback and tries
every date folder, newest first, then the undated object.

The list of date folders is the one from the last successful listing: a new folder is used after the next rescan
(`rescan-seconds`).

## 7. Writing objects

The connector never writes. Any S3 client writes the objects:

```bash
aws s3 cp STR-COVID2020-COMM.json s3://risk-docs/eod/2026-09-30/stress-result/STR-COVID2020-COMM.json
aws s3 sync ./out/2026-09-30/ s3://risk-docs/eod/2026-09-30/        # a whole day, from a local folder
aws --endpoint-url https://minio.bank.example s3 cp …              # an S3-compatible store
```

What to know when writing:

- **A day is not atomic.** S3 has no transaction across objects. While a day's folder is being written, the entities
  already written answer from it and the others from the previous folder (section 6), so a partly written day shows
  a mix of today and yesterday. Write the day under a staging prefix outside the connector's prefix and copy it in,
  or write it in the hours nobody reads it.
- **When a change is seen.** A new date folder and new ids appear after the next listing (`rescan-seconds`, 60). A
  rewritten object, or one that was missing and now exists, is served once its cached read or miss expires
  (`cache-seconds`, 30). A purge of the connector (Admin → Caches) drops the cached reads at once; it does not
  relist.
- **One object per entity.** Keep each object one JSON document; the whole object is parsed for every read.
- **Clean up with a lifecycle rule.** The listing covers everything under the prefix, every date ever kept
  ([section 8](#8-scale-memory-and-request-cost)). Expire date folders older than you need with a bucket lifecycle
  rule (on AWS an expiration rule on the prefix; on MinIO `mc ilm rule add`).

## 8. Scale, memory and request cost

The figures below are **estimates derived from the code**, not measurements: no performance test of this connector
exists.

**The listing.** Each rescan sends one delimited `ListObjectsV2` request for the first-level folders (a page holds
1,000 names, so one request for a few hundred dates) and then lists **every object under the prefix**, one request
per 1,000 keys, one page after another. The first listing runs inside `start`, so the server's start waits for it.
Rescans are scheduled with a fixed delay, so a slow listing never overlaps the next.

| Objects under the prefix | List requests per rescan | Per day at `rescan-seconds` 60 |
|---|---|---|
| 10,000 | 11 | about 16,000 |
| 100,000 | 101 | about 145,000 |
| 1,000,000 | 1,001 | about 1.44 million, and a rescan of the order of minutes |

**Memory.** The type-ahead index holds one entry per distinct `(kind, id)` (an id present on ten dates counts once),
a few hundred bytes each; a rescan builds the new index beside the old one before swapping, so for a moment both are
held. The read cache holds up to `cache-entries` (10,000) parsed documents; a parsed document takes several times its
JSON size, so 10,000 documents of 8 KB take a few hundred megabytes. Lower `cache-entries` for large documents.

**A million entities a day.** That is a million `PUT`s a day by the writer, a million more keys to list on every
rescan, a million more type-ahead entries for every day of new ids, and no fast searches (no columns). The connector
is not built for it. Keep such a book in Delta Lake (which can itself live in S3, `root: s3a://bucket/lake`) or in
another store with promoted columns, and use this connector for the documents around it.

**Throughput.** Reads are independent `GET`s on the AWS SDK's URL-connection HTTP client, called concurrently from
the server's virtual threads; S3 serves thousands of `GET`s per second per prefix. The practical bound is the round
trip per `GET` times the misses per read (section 5.1).

## 9. S3 with a lake for history

A connector that does not hold a date answers "not held", and the router asks the next one. So S3 can serve recent
days and a lake every older one. With both serving `stress-result` and the route to S3:

```yaml
drishti:
  sources:
    connectors:
      risk-docs:                     # recent result documents, as the stress engine writes them
        plugin: s3
        kinds: [stress-result]
        settings: { bucket: risk-docs, prefix: eod/, lookback-days: 10 }
    routes:
      stress-result: risk-docs       # asked first; the pack's risk-store (Delta Lake) after it
```

| The user asks for | Order tried | Who answers |
|---|---|---|
| Live (today) | `risk-docs` → … → `risk-store` (neither is live, so the order of candidates is kept) | S3, if today's or a folder within 10 days holds it |
| a picked date within the lookback | `risk-docs` → `risk-store` (both dated, order kept) | S3 |
| a date older than the lookback | as above | S3 holds nothing in range, so the lake answers for that date |

Two cautions:

- **An undated object answers every date.** If `eod/stress-result/X.json` exists, S3 holds `X` for every date and the
  lake is never asked for it. Keep undated objects only for kinds that truly do not change by date.
- **The lookback is the boundary.** Set `lookback-days` to the history S3 keeps (and the lifecycle rule to match);
  beyond it the lake answers. A date inside the window whose folder lacks the entity is answered from an older S3
  folder, not from the lake.

The general rules for combining connectors are in [CONNECTOR_GUIDE.md, chapter 16](CONNECTOR_GUIDE.md#16-combining-connectors).

## 10. Failure and recovery

The connector keeps nothing long-lived: each call is a separate HTTPS request on the SDK's client (connection timeout
5 s, socket timeout 20 s, fixed in the code), so there is no connection to lose and nothing to reconnect. It starts
even when the store is unreachable.

| What fails | Effect | Health |
|---|---|---|
| a listing (at start, or a rescan) | the last good listing is kept: date folders, kinds and type-ahead stay as they were (empty if no listing has ever succeeded) | `DOWN: <Exception>: <message> (retrying)` |
| a `GET` (other than "no such key") | that read fails: the view shows `DRS-1003 <connector> failed reading <kind>/<id>`, and the router does **not** fall through to another store | `DOWN: <Exception>: <message> (retrying)` |
| a `GET` slower than the fetch timeout | the read ends with `DRS-1004 timed out reading <kind>/<id>` after `fetch-timeout` (2 s); the `GET` itself may run on up to the 20 s socket timeout | unchanged |
| an object that is not valid JSON | that read fails (`DRS-1003`) | unchanged |

`health` is exactly `"UP"` when the last error is cleared, else `"DOWN: " + <exception's simple class name> + ": " +
<message> + " (retrying)"`. The error is cleared by the next successful listing or the next successful `GET` (a "no
such key" answer does not clear it). Typical texts from the AWS SDK:

```text
DOWN: SdkClientException: Unable to execute HTTP request: Connection refused (retrying)
DOWN: S3Exception: Access Denied (Service: S3, Status Code: 403, Request ID: …) (retrying)
DOWN: NoSuchBucketException: The specified bucket does not exist (Service: S3, Status Code: 404, Request ID: …) (retrying)
```

Recovery needs nothing: the next rescan (every `rescan-seconds`) or read tries again, and health returns to `UP` on
the first success. Because either path clears the error, health can show `UP` after a successful read while
listings are still failing; check that `datedFolders` and `indexed` in the cache figures move.

Cache figures (Admin → Caches, and `cache` in `GET /api/v1/admin/health`):

```json
{"name": "risk-docs", "health": "UP", "cache": {"cachedObjects": 212, "indexed": 1840, "datedFolders": 10}}
```

| Figure | Meaning |
|---|---|
| `cachedObjects` | reads and misses in the cache now |
| `indexed` | distinct `(kind, id)` in the type-ahead index |
| `datedFolders` | date folders found by the last good listing |

A purge drops the cached reads and misses only; the listing is refreshed by the next rescan.

## 11. Security

**Permissions.** The connector only reads: it needs `s3:ListBucket` on the bucket (restricted to the prefix if you
like, with an `s3:prefix` condition) and `s3:GetObject` on `<bucket>/<prefix>*`. Give it nothing else:

```json
{"Version": "2012-10-17", "Statement": [
  {"Effect": "Allow", "Action": "s3:ListBucket", "Resource": "arn:aws:s3:::risk-docs",
   "Condition": {"StringLike": {"s3:prefix": ["eod/*"]}}},
  {"Effect": "Allow", "Action": "s3:GetObject", "Resource": "arn:aws:s3:::risk-docs/eod/*"}]}
```

Objects encrypted with SSE-S3 or SSE-KMS are read transparently (SSE-KMS also needs `kms:Decrypt` on the key);
objects encrypted with a customer-provided key (SSE-C) cannot be read, as there is no setting for the key.

**Credentials.** With `access-key` blank the AWS SDK's default credential chain is used: Java system properties, the
`AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY` (and `AWS_SESSION_TOKEN`) environment variables, a web identity token
(EKS service accounts), the shared profile (`~/.aws/credentials`, `AWS_PROFILE`), container credentials (ECS) and the
EC2 instance role. Prefer the role or web identity: nothing to store, and credentials rotate. With `access-key` set,
the connector uses that key and `secret-key` as static credentials, with no session token, so temporary credentials
must come through the chain. Never write a key in a YAML file; use `${S3_ACCESS_KEY}` placeholders
([CONNECTOR_GUIDE.md, Secrets](CONNECTOR_GUIDE.md#secrets)). Health and `/api/v1/sources` never show settings.

**TLS.** AWS endpoints are HTTPS. With `endpoint`, the scheme you write is used: write `https://` in production
(`http://localhost:9000` is for a local MinIO). The server's JVM trust store decides which certificates are trusted;
for a store with a private certificate authority, add the authority to that trust store (or start the JVM with
`-Djavax.net.ssl.trustStore=…`).

**Addressing.** With `endpoint`, requests use path-style addressing (`https://minio.bank.example/risk-docs/eod/…`)
unless `path-style: false`; without `endpoint` the SDK uses AWS's virtual-hosted style and `path-style` is ignored.

**What users see.** Drishti's entitlements and redaction apply to documents from S3 like any other: the connector
reads everything under its prefix, and the server decides what each user may see.

## 12. Limits and trade-offs

- **No searches over fields, no reverse lookups, no live push.** Type-ahead by id only.
- **The listing covers the whole prefix**, all dates ever kept: its cost grows with history, not with the lookback.
  Keep the prefix small with a lifecycle rule.
- **A day is not atomic**, and an entity missing from a folder is shown from an older one within the lookback
  (section 6). There is no "gone on this date" short of the lookback running out.
- **Misses are cached** for `cache-seconds`: an object written just after a read that missed it is not seen until the
  miss expires.
- **Only `.json` objects**, one document each; no compression (`.json.gz` is ignored), no CSV, no JSON lines.
- **Timeouts are fixed** in the code (5 s connect, 20 s socket); the overall bound is `drishti.sources.fetch-timeout`.
- **No *known at***: object versioning in the bucket is not used; the current version of each object is read.

## 13. Diagnosing

| Symptom | Likely cause | What to do |
|---|---|---|
| `failedToStart`: `s3 plugin needs settings.bucket` | no `bucket` setting | set `bucket` |
| `failedToStart`: `NumberFormatException …` | a non-numeric `rescan-seconds`, `cache-seconds`, `cache-entries` or `lookback-days` | fix the value |
| `DOWN: SdkClientException: Unable to execute HTTP request: … (retrying)` | the endpoint is unreachable (DNS, firewall, proxy) | `curl -sI <endpoint>` from the server host; recovers by itself |
| `DOWN: S3Exception: Access Denied (… Status Code: 403 …) (retrying)` | credentials or bucket policy | `aws s3 ls s3://<bucket>/<prefix>` with the same credentials; check `s3:ListBucket` and `s3:GetObject` |
| `DOWN: NoSuchBucketException: … (retrying)` | bucket name wrong, or the wrong `endpoint` | fix `bucket` / `endpoint` |
| `DOWN: S3Exception: … Status Code: 301 …` | `region` is not the bucket's region | set `region` |
| `DOWN: DateTimeParseException: Text '2026-13-01' could not be parsed … (retrying)` and type-ahead never changes | a first-level folder looks like a date but is not one; every listing fails | rename or remove that folder |
| a new object is not found | its miss is cached (`cache-seconds`), or, for type-ahead and a new date folder, the next rescan has not run | wait, or purge the connector's cache (reads only) |
| a picked date shows an older `businessDate` | the folder for that date lacks the entity; an older folder within `lookback-days` answers | expected (section 6); write the entity into every day's folder |
| the lake is never asked for an old date | an undated object exists for the entity | remove the undated object, or accept it serves every date |
| `notHeld` on this connector grows for kinds it does not hold | no `kinds:` and the listing found nothing (or never succeeded), so it serves every kind | give the connector `kinds:` |
| a read fails with `DRS-1003` but health is `UP` | the object is not valid JSON | check the object; parse errors do not change health |
| `DRS-1004 timed out reading …` | `GET`s far from the server, or many misses per read | keep the bucket in the server's region; lower `lookback-days`; route the kind so faster stores are not behind it |
| server start is slow | the first listing runs inside start, one request per 1,000 keys | shrink the prefix (lifecycle rule, a narrower `prefix`) |

## 14. Settings

On an `s3` connector (`drishti.sources.connectors.<name>.settings`) or the plugin as itself
(`drishti.sources.plugins.s3.settings`), as `S3SourcePlugin.start` reads them:

| Setting | Default | Meaning |
|---|---|---|
| `bucket` | none (required) | the bucket; missing or blank fails the start with `s3 plugin needs settings.bucket` |
| `prefix` | empty | the key prefix (e.g. `eod/`); a trailing `/` is added when missing |
| `region` | `us-east-1` | the region used for requests and signing |
| `endpoint` | empty | the URL of an S3-compatible store (MinIO, Ceph); empty means AWS |
| `path-style` | `true` | path-style addressing; read only when `endpoint` is set |
| `access-key` | empty | a static access key; blank means the AWS default credential chain |
| `secret-key` | empty | the static secret key, used with `access-key` |
| `rescan-seconds` | `60` | how often date folders, kinds and ids are listed (first listing at start) |
| `cache-seconds` | `30` | how long a read, or a miss, is kept |
| `cache-entries` | `10000` | the most reads and misses kept |
| `lookback-days` | `10` | the oldest date folder used: the date asked minus this many calendar days |
| `source-name` | `s3` (a named connector: its name) | the name in provenance and type-ahead subtitles |

Connector keys (`plugin`, `enabled`, `kinds`) are as for every connector
([PLUGIN_GUIDE.md](PLUGIN_GUIDE.md#configuration)). Fixed in the code, not settings: connection timeout 5 s, socket
timeout 20 s.

## 15. Checklist for production

1. Give the connector `kinds:`, and a route if it should be asked before other stores for those kinds.
2. Use an instance role or web identity; if static keys are unavoidable, set them through environment placeholders.
   Grant only `s3:ListBucket` (on the prefix) and `s3:GetObject`.
3. Use `https://` for any `endpoint`, and put a private certificate authority in the JVM trust store.
4. Keep the bucket in the server's region; set `region` to it.
5. Keep the prefix small: a lifecycle rule that expires date folders beyond `lookback-days`, and a dedicated prefix
   for Drishti's objects.
6. Write each day to a staging prefix and copy it in, or write it outside reading hours; write every entity into
   every day's folder.
7. Size `cache-entries` for your documents' size, and the heap for the type-ahead index (a few hundred bytes per id).
8. For history beyond the lookback, configure a lake connector for the same kinds after this one
   ([section 9](#9-s3-with-a-lake-for-history)).
9. Check Admin → Health (`UP`, `datedFolders`, `indexed`), then open an entity Live and on a picked date and check
   `provenance.businessDate`.
