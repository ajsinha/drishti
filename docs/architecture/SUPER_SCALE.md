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
# Super scale: 500 GB of JSON a day for seven years, on one shared Delta lake in S3

Status: **design, not built.** Release not decided (product owner, 2026-10-20). Builds on
[SCALE_STRATEGY.md](SCALE_STRATEGY.md) (one server, millions of trades a day: built and measured),
[DRISHTI_NET.md](DRISHTI_NET.md) (many servers, one Drishti: built in 2.5.0) and [AWS_DEPLOYMENT.md](AWS_DEPLOYMENT.md)
(ECS, EFS, S3: designed). Every number is an **estimate** unless marked *measured*; step S8 replaces the estimates with
measurements.

## Contents

1. [The ask](#1-the-ask)
2. [The size of it](#2-the-size-of-it)
3. [What breaks if nothing changes](#3-what-breaks-if-nothing-changes)
4. [Principles](#4-principles)
5. [The architecture](#5-the-architecture)
6. [The lake in S3](#6-the-lake-in-s3)
7. [Loading 500 GB a day](#7-loading-500-gb-a-day)
8. [Products: computed when loading, not when asked](#8-products-computed-when-loading-not-when-asked)
9. [Reading one entity](#9-reading-one-entity)
10. [Many servers on one lake: slices and owners](#10-many-servers-on-one-lake-slices-and-owners)
11. [Searches, lists, pages and BI](#11-searches-lists-pages-and-bi)
12. [Type-ahead](#12-type-ahead)
13. [Memory and caches on a server](#13-memory-and-caches-on-a-server)
14. [Freshness and consistency](#14-freshness-and-consistency)
15. [Seven years: storage tiers and retention](#15-seven-years-storage-tiers-and-retention)
16. [Consoles](#16-consoles)
17. [Failures](#17-failures)
18. [Security at scale](#18-security-at-scale)
19. [Sizing and cost](#19-sizing-and-cost)
20. [Observability](#20-observability)
21. [Settings](#21-settings-proposed)
22. [Build plan](#22-build-plan)
23. [Tests](#23-tests)
24. [Risks and open questions](#24-risks-and-open-questions)

## 1. The ask

The product owner (2026-10-20): Drishti must serve **500 GB of JSON a day, kept for seven years**. The store is
**Delta Lake**, one **common lake in S3**, read by **several servers and several consoles** joined by **Drishti*net**.
Every user, on any console, sees one Drishti; any server can answer for any data.

Targets (SCALE_STRATEGY.md section 1, at fifty times its volume):

| | Target |
|---|---|
| Open one entity, today's business day | p95 under 300 ms |
| Open one entity, any of the 1,764 business days of seven years | p95 under 1 s (a cold day years back) |
| Form search over one day (50 million rows) | p95 under 2 s |
| Counterparty, portfolio, legal-entity and netting-set pages | p95 under 1 s, members paged |
| BI over a day / a month / seven years | seconds / seconds (rollups) / seconds (rollups) |
| The day's 500 GB loaded | within a 2-hour window after the last dump arrives; the day appears whole or not at all |
| Heap per server | **8 GB** (scale out, not up) |
| Truth | the Delta lake in S3; everything else is a product of the load or a cache, rebuildable from it |
| Users | 500 at once, each opening a page every few seconds (about 250 requests a second, peaks of 500) |

## 2. The size of it

500 GB of JSON documents a day. Trade documents measure 6.5–14 KB each (*measured*, SCALE_STRATEGY.md section 4.3),
about 10 KB on average; JSON compresses about 8 times in Parquet with ZSTD (*measured*: 0.7 KB per 6.5 KB trade,
ICEBERG_CONNECTOR.md section 3).

| | A day | A year (252 business days) | Seven years (1,764 days) |
|---|---|---|---|
| JSON from the sources | 500 GB | 126 TB | 882 TB |
| Documents (rows) | **50 million** | 12.6 billion | **88 billion** |
| Parquet: documents and promoted columns | about 65 GB | about 16 TB | **about 115 TB** |
| Products (section 8: link indexes, groups, rollups, id lists) | about 4 GB | about 1 TB | about 7 TB |
| Parquet files (about 250,000 rows each) | about 200 | about 50,000 | about 350,000 |
| Delta commits (one per slice, section 7) | about 150 | about 38,000 | about 265,000 |

Assumed mix (to be replaced by the real sources): three source systems; trades the large majority of documents;
reference data (counterparties, netting sets, books) a few hundred thousand documents a day; market data and risk
results sized like today's packs.

## 3. What breaks if nothing changes

| Built today (SCALE_STRATEGY.md) | Fine for | At 50 million a day |
|---|---|---|
| An **id map** per table and day: every id → its file, about 60 B per id, in the heap | 1–2 million a day | 3 GB per day: a server holds one or two days of 1,764 |
| A day's **promoted columns** in the heap: about 230 MB per million rows for 19 fields | 1–2 million a day | 11.5 GB per day: more than the heap |
| **Grouped pages** built by each server in one pass over the day: 45 s for 1.16 million (*measured*) | one server | about 30 minutes per server per day, repeated on every server |
| **One Delta table** per source and kind for all history | months | 350,000 files in one log: every snapshot lists them all |
| **One loader** per source system: about 50 MB of JSON a second (*measured*) | 3 GB a day | about 3 hours for 500 GB, and parallel loaders on one table need a safe commit |
| Servers on one lake are **replicas**: each caches whatever it is asked | a few servers | every server repeats the same work; adding servers adds no cache |
| **Type-ahead** ids of the newest days in memory, about 40 B per id | millions | 2 GB per day per server |

Each line has its answer: sections 9, 11, 8, 6, 7, 10 and 12.

## 4. Principles

1. **The lake is the truth; everything else is derived from it.** Products (section 8) are tables in the lake written
   by the loaders; caches are on the servers. Both are rebuildable from the documents.
2. **Compute when loading, not when asked.** Anything every server would compute for every user (grouped pages, link
   lists, totals, id lists) is computed once, by the slice that has the rows sorted in hand.
3. **Find by ranges, not by maps.** Files of a day hold sorted, disjoint id ranges; the Delta log already records each
   file's lowest and highest id. Nothing holds one entry per entity.
4. **Each server owns a share.** On one lake, the Drishti*net ring gives each slice of the data an owner; the cluster's
   memory adds up instead of repeating.
5. **Name the date, read one day** (SCALE_STRATEGY.md section 2): every interactive read touches one business day,
   except the history of one entity and rollups.
6. **The heap holds ids and numbers, never documents in bulk**, and is 8 GB whatever the volume.
7. **A day appears whole or not at all**, and a reload replaces it whole.
8. **Every limit is a setting, and every limit that bites says so** in Admin → Health.

## 5. The architecture

### 5.1 The whole

```
  SOURCE SYSTEMS                     LOADING (batch window)                          THE LAKE (S3, one bucket)
  ┌────────────┐  dumps (JSON)   ┌─────────────────────────────┐   Parquet + commits ┌──────────────────────────────┐
  │ ENDUR      │───────────────► │ planner: the day's slices   │ ──────────────────► │ data/   trades, reference,   │
  │ CALYPSO    │  s3://…/landing │ loader tasks ×32 (ECS)      │                     │         market, risk …       │
  │ MUREX      │                 │   parse → promote → sort →  │ ──────────────────► │ products/ links, groups,     │
  │ reference  │                 │   write → products → commit │                     │         rollups, ids         │
  └────────────┘                 │ publisher: merge, register  │ ──────────────────► │ register/ days published     │
                                 └─────────────────────────────┘                     └──────────────┬───────────────┘
                                                                                                    │ ranged GETs
                                                                                                    │ (task roles, VPC endpoint)
  SERVING                                                                                           │
  ┌──────────────┐   HTTPS   ┌───────────────────┐                ┌─────────────────────────────────┴──────────────┐
  │ users        │ ────────► │ load balancer     │ ─────────────► │ consoles ×4 (a service; any console, any user) │
  └──────────────┘           └───────────────────┘                └───────┬──────────────┬──────────────┬──────────┘
                                                                          ▼              ▼              ▼
                                       ┌───────────── Drishti*net: gossip, ring, forwarding, scatter-gather ────────┐
                                       │  server A    server B    server C    server D    server E    server F       │
                                       │  each owns ~1/6 of the slices; caches only them; any server answers any read│
                                       └──────────────────────────────────────────────────────────────────────────────┘
                                                   │ caches and state per server: EFS (AWS_DEPLOYMENT.md)
```

### 5.2 One request's path

```
 user opens TRD MX-20000017 on 2026-09-30
   console ──► any server (say B)
                B: slice of (trades/murex/2026, 2026-09-30, group 7) → owners on the ring: E (first), A (second)
                B ──forward──► E                                     (one hop; masked again on B for the user)
                               E: range map → file 0042 → footer (cached) → row group 3 → one ranged GET, 1 MB
                               E: document → its Sutra's data → back to B
                B ──► console ──► page
```

### 5.3 Components and what is new

| Component | Today | New for this scale |
|---|---|---|
| Lake layout | one table per source and kind | tables per year; Bloom filters on `id`; disjoint id ranges guaranteed (section 6) |
| Loaders | `tools/load-delta.sh`, one process per source | a planned fleet of slice loaders, safe concurrent commits, a publisher and a day register (section 7) |
| Products | none (servers compute) | link indexes, group totals, rollups, id lists written with the data (section 8) |
| Single reads | id map | range map (section 9) |
| Drishti*net on one lake | replicas; any one answers | slices owned on the ring; forwarding to owners; split scans (section 10) |
| Searches and BI | a day's columns in the heap | split by owner; link indexes; rollups (section 11) |
| Type-ahead | ids in memory | the day's id list as a product, memory-mapped (section 12) |
| Caches | heap first | owned slices only; warm tier memory-mapped (section 13) |

## 6. The lake in S3

### 6.1 Layout

```
s3://<org>-drishti-prod-lake/
├── landing/                                   the sources' dumps (JSON lines, gzip), kept 30 days
│   └── 2026-09-30/endur/trades-0001.jsonl.gz …
├── data/                                      the documents: one Delta table per source, kind and YEAR
│   ├── trades/endur/2026/
│   │   ├── _delta_log/                        commits, checkpoints (every 10 commits), _last_checkpoint
│   │   ├── business_date=2026-09-30/
│   │   │   ├── PRODUCT_TYPE=IRS_FIXFLOAT/
│   │   │   │   ├── part-00000-….zstd.parquet  ids ENDUR-0000001 … ENDUR-0250000   (sorted, disjoint)
│   │   │   │   └── part-00001-….zstd.parquet  ids ENDUR-0250001 … ENDUR-0498112
│   │   │   └── PRODUCT_TYPE=FX_FORWARD/ …
│   │   └── business_date=2026-10-01/ …
│   ├── trades/endur/2025/ …                   closed: compacted, checkpointed, vacuumed, read-only
│   ├── trades/calypso/2026/ …
│   └── counterparty/ref/2026/ …
├── products/                                  written by the loaders (section 8), same year split
│   ├── links/trade/COUNTERPARTY/2026/         (value, id, business_date, listed columns), sorted by value
│   ├── groups/counterparty-trades/2026/       one row per group per day: totals
│   ├── rollups/trading/2026/                  per day × grain: sums, counts, sketches
│   └── ids/trade/2026/                        the day's sorted id list (type-ahead)
└── register/                                  the day register (section 7.5): a small Delta table
```

### 6.2 The table of documents

| Column | Type | Content |
|---|---|---|
| `id` | string | the entity's id; sorted within each file; files of a partition hold disjoint ranges |
| `doc` | string | the whole JSON document, exactly as the source sent it |
| `business_date` | date | partition |
| `PRODUCT_TYPE` (trades) | string | partition |
| promoted fields (`COUNTERPARTY`, `PORTFOLIO`, `LEGAL_ENTITY`, `CURRENCY`, `NOTIONAL`, `MTM`, …) | string / double | copies of the fields searches, forms, lists, links and BI use (the pack's `layout.<kind>.columns`) |
| `_slice` | string | the slice that wrote the row (section 7.1): a reload of a slice replaces exactly its rows |

| Property | Value | Why |
|---|---|---|
| File size | about 250,000 rows, about 330 MB | few files (about 200 a day), each one read only by ranged GETs |
| Row groups | 1 MB (*measured* balance on local disk; to be re-measured on S3, step S8) | one read decodes about 1 MB |
| Compression | ZSTD | about 8 times on documents |
| Statistics | min/max for `id` and every promoted column, in the Delta log and the Parquet footers | file and row-group skipping |
| Bloom filter | on `id` (Parquet split-block filter, about 1 % false positives) | a read for an id a file does not hold reads no data (section 9) |
| Checkpoints | every 10 commits; v2 checkpoints with sidecars when Delta Kernel supports them for reading | a snapshot reads the checkpoint plus a few commits |

### 6.3 Tables by year

A year's table holds about 50,000 files and 38,000 commits; seven years in one table would hold 350,000 files, all of
which every snapshot of the table must list. The connector takes a pattern instead of a root:

```yaml
# config/connectors/trade-endur.yaml
plugin: delta
kinds: [trade]
settings:
  root: s3://acme-drishti-prod-lake/data/trades/endur/{year}     # {year} of the business date asked
  years: 2020-                                                    # the years that exist (or: listed from S3)
  engine: native
```

- A read names a business date, so it opens exactly one year's table.
- `inventory` and `latestDate` read the day register (section 7.5), not seven logs.
- **Closing a year** (first business day of January, by the maintenance job): compact the year's small files, write a
  final checkpoint, vacuum, then the table is read-only. Its log never changes again, so a server reads its snapshot
  once and keeps it.

### 6.4 S3 request rates

S3 serves at least 3,500 writes and 5,500 reads a second **per prefix**, and the partitions are prefixes
(`…/business_date=…/PRODUCT_TYPE=…/`). A busy day is spread over its product-type prefixes; at 500 reads a second
(section 1) the load is far below the limits. A `503 Slow Down` is retried with jittered back-off by the native
engine's AWS client and counted in Admin → Health.

## 7. Loading 500 GB a day

### 7.1 Slices

A **slice** is one source system × one kind × one product type (for trades) × one business day: the unit a loader
task writes and the unit a reload replaces. About 150 slices a day; the largest about 30–40 GB of JSON.

### 7.2 The flow

```
 dumps arrive in s3://…/landing/2026-09-30/
      │  S3 event, or a schedule (Amazon EventBridge)
      ▼
 PLANNER (one small task)
   reads the day's manifest of dumps (or lists landing/), groups them into slices,
   writes the day's plan to the register: state PLANNED, slices, expected rows
      │
      ▼
 LOADER TASKS (an ECS task per slice, up to 32 at once; AWS Step Functions "Map" over the slices)
   ┌───────────────────────────────────────────────────────────────────────────────────────────────┐
   │ read  : the slice's dump objects from landing/ (streamed, gzip)                               │
   │ parse : each line a document; LoadGuard: date checks, required fields                         │
   │ promote: the pack's promoted fields (layout.<kind>.columns), nested paths flattened            │
   │ sort  : by id, external sort, runs spilled to the task's EFS work folder (the AWS rule)        │
   │ write : Parquet files of 250,000 rows, 1 MB row groups, ZSTD, Bloom filter on id;             │
   │         disjoint id ranges, uploaded by S3 multipart                                          │
   │ products (same pass): link-index rows, group partials, rollup partials, the slice's id list   │
   │ commit: one Delta commit per table touched (replaceWhere the slice), safe concurrent (7.3)    │
   │ report: rows, files, bytes, id range, fingerprint → register (slice LOADED)                   │
   └───────────────────────────────────────────────────────────────────────────────────────────────┘
      │  every slice LOADED
      ▼
 PUBLISHER (one task)
   merges group and rollup partials into the day's rows; merges the slices' id lists;
   checks rows against the plan; writes the day PUBLISHED with its fingerprint
      │
      ▼
 servers see the published day at their next register poll (section 14) and warm their owned slices
```

### 7.3 Safe concurrent commits

Many slices commit to the same table (one year of one source) at once. A Delta commit is the creation of the next
`_delta_log/<version>.json`; two writers must never both create the same version.

| Mechanism | How | Use |
|---|---|---|
| **S3 conditional writes** (`If-None-Match: *` on the commit file) | S3 refuses the second create of a version; the loser re-reads the log, checks its commit still applies (its slice is its own partition: it always does), and retries at the next version | the default; supported by S3 since 2024 and by delta-rs |
| **DynamoDB lock** (delta-rs's `dynamodb` locking provider) | a lock row per table serialises commits | S3-compatible stores without conditional writes |

```
 slice A                         _delta_log/                       slice B
   ├─ write data files             …                                 ├─ write data files
   ├─ create 000…0412.json  ──►  000…0412.json (A)                   │
   │                                                                 ├─ create 000…0412.json ──► refused (exists)
   │                                                                 ├─ read 0412, check: B's partition untouched
   │                                                                 └─ create 000…0413.json ──► 000…0413.json (B)
```

Retries are bounded (`commit-retries`, 20, with jittered back-off); a slice that cannot commit fails and is re-run.

### 7.4 Throughput

| Step | Rate | For 500 GB with 32 tasks |
|---|---|---|
| Parse and promote | about 50 MB of JSON a second per task (*measured* single-loader rate, 3 GB in about 60 s) | about 5 minutes |
| External sort (spill and merge) | bounded by EFS throughput (Elastic: several GB a second across the file system) | 1 TB written and read: about 10–20 minutes |
| Write Parquet and upload | about 65 GB over S3 multipart | a few minutes |
| Commits | about 150, each under a second | seconds |
| Publisher | merges of thousands of group rows | a minute |
| **Total** | | **about 30–45 minutes** after the last dump arrives |

The window is bounded by when the dumps arrive, not by Drishti. More tasks shorten it; the planner caps them
(`max-tasks`) so S3 and EFS stay within limits.

### 7.5 The day register

A small Delta table, one row per (kind, business day) and one per slice:

| Column | Content |
|---|---|
| `kind`, `business_date`, `slice` (empty for the day row) | the key |
| `state` | `PLANNED` → `LOADING` → `LOADED` → `PUBLISHED`; `RELOADING`; `FAILED` |
| `rows`, `files`, `bytes`, `id_min`, `id_max` | counts and range |
| `fingerprint` | a hash of the slices' file lists: the data version of the day |
| `tables` | the tables and versions the day's commits produced |
| `published_at`, `published_by` | when and which run |

```
           plan                 first slice starts         every slice LOADED, checks pass
 (none) ─────────► PLANNED ───────────────────► LOADING ──────────────────────────────► PUBLISHED
                                                  │ a slice fails after retries                │ reload asked
                                                  ▼                                            ▼
                                                FAILED ──── re-run the failed slices ───►  RELOADING ──► PUBLISHED (new fingerprint)
```

**Servers read a day only when it is PUBLISHED**, and read the fingerprint recorded there. During a reload the old
files stay readable (Delta keeps them until vacuum), and the servers keep serving the old fingerprint's files until the
new one is published: no half day, ever.

### 7.6 Reloads and corrections

- **One slice** (a source re-sent its swaps): re-run that slice's task; its commit replaces its rows (`replaceWhere`
  on `_slice`); the publisher re-merges the day's products and publishes a new fingerprint.
- **A whole day**: the planner plans it again; same flow.
- **Late slices** (a source's dump arrives after the day was published): the day goes to `RELOADING` and is
  republished with the slice; the console shows the day's publication time.
- **LoadGuard** as today: dates checked, rows after tomorrow refused, a drop of more than half a table refused without
  `--force-drop`.

## 8. Products: computed when loading, not when asked

Each slice writes its products in the same pass that writes its documents; the publisher merges what must be merged
across slices. Servers read products like any table.

### 8.1 Link indexes

For each promoted **link field** of a kind (a field that names another entity: counterparty, netting set, portfolio,
legal entity, index), a table sorted by the field's value:

| Column | Content |
|---|---|
| `value` | the linked entity's id (`CP-MERIDIAN-RE`) |
| `id` | the entity that links to it (`MX-20000017`) |
| `business_date` | partition |
| listed columns | the columns a list of such entities shows (product type, notional, MTM, maturity, currency): a covering index, so a list needs no document |

```
 products/links/trade/COUNTERPARTY/2026/business_date=2026-09-30/
   part-00000  values CP-AARDVARK … CP-FALCON     (sorted by value, then id)
   part-00001  values CP-FALCON-2 … CP-MERIDIAN
   part-00002  values CP-MERIDIAN-RE … CP-ZEPHYR
                       │
   "trades of CP-MERIDIAN-RE on 2026-09-30" = range map on value → part-00002 → row groups of that value
```

Each slice writes its rows sorted; the publisher does not rewrite them: a link table's day has one file set per slice,
and a lookup reads the matching range of each (about 150 small range reads, in parallel) — or, with
`links.merge: true`, the publisher merges them into a few sorted files (a few minutes, recommended for the most used
fields).

### 8.2 Group totals

For each grouped kind (counterparty trades, portfolio trades, legal-entity trades, index trades; the `derived`
connector's pages today), one row per group per day:

| Column | Content |
|---|---|
| `group` | the group's id |
| `business_date` | partition |
| `members` | count |
| sums, extremes | notional, MTM, … (the page's totals) |
| distinct values | currencies, product types (small sets) |
| payments ladder | sums and counts of events in the next 7, 30, 90 days |
| `first_member` | the first member by id (the page's default selection) |

Slices write partials (per slice, per group); the publisher adds them up (sums add, extremes compare, distinct sets
unite, ladders add). A counterparty page reads its one row, and its member list from the link index, a page at a time.

### 8.3 Rollups

Per model, per day, at the model's common grains (desk, portfolio, counterparty, product type, currency, and their
pairs the model declares): sums, counts, min and max, and **HyperLogLog sketches** for distinct counts (sketches add
across slices and days). BI over a range longer than `rollup-after-days` (5) reads rollups.

### 8.4 Id lists

Per kind and day, the sorted list of ids (the slices' lists merged), written as a compact file: the type-ahead's source
(section 12).

### 8.5 Product sizes

| Product | Per row | A day |
|---|---|---|
| Link indexes, five fields with listed columns | about 15 B compressed (sorted values compress well) | about 3.5 GB |
| Group totals | about 200 B | well under 100 MB |
| Rollups | about 100 B | well under 100 MB |
| Id lists | about 8 B (prefix-compressed) | about 400 MB |
| **Total** | | **about 4 GB** |

Products carry the day's fingerprint; a reload replaces them with the data.

## 9. Reading one entity

### 9.1 The range map

Per (table, business day): the day's files with each file's `id` min and max, read from the Delta log's add actions
(`stats`), sorted by min. About 200 entries a day over all tables (about 60 for one source's trades), 100 B each.
A server keeps the range maps of the slices it owns (section 10): for seven years of all trades, about 35 MB in total,
about 6 MB per server. Range maps never change for a published day; a new fingerprint replaces the day's map.

### 9.2 The read

```
  1. date → year → table                      (no I/O)
  2. register: the day is PUBLISHED            (memory, refreshed by the poll)
  3. range map: binary search on id → file    (memory)
  4. footer of the file: row groups' id ranges (cache; else one ranged GET of the footer, about 1–2 MB)
  5. Bloom filter of the row group: "maybe"    (in the footer's index; "no" ends the read: not on this day)
  6. one ranged GET of the row group           (about 1 MB, 30–80 ms in-Region, first byte 10–30 ms)
  7. decode the columns asked (id, doc), cut the document; document cache
```

| Step | Cold | Warm |
|---|---|---|
| Range map, register | memory | memory |
| Footer | 30–60 ms (one GET) | memory (footer cache, *measured* to double single-read throughput) |
| Row group | 30–80 ms (one GET) | page cache / document cache |
| Decode | 5–15 ms | — |
| View (Sutra, links, masks) | 10–30 ms | 10–30 ms |
| **Total** | **80–190 ms** | **15–40 ms** |

The id map of SCALE_STRATEGY.md remains an option (`id-map: true`) for small tables on local disk, where it saves the
footer read.

### 9.3 Effective kinds

For a kind stored only when it changes (`mode: effective`, reference data), "the entity as of date D" is its latest
row on or before D. Each day's id list (section 8.4) says whether a day holds the id; the server walks back day by
day through the id lists (memory-mapped, binary searches, microseconds each) and reads the first day that has it.
Reference kinds are small (hundreds of thousands a day), so their id lists for all days owned fit easily.

## 10. Many servers on one lake: slices and owners

### 10.1 Slices of serving

For serving, a **serving slice** is (table, business day, group), where the group is one of `groups` (12) parts of
the day: a file's group is its index in the day's range map, modulo 12. A day of one source's trades (about 60
files) has 12 serving slices of about 5 files each.

### 10.2 The ring

Drishti*net's ring (the donut of Admin → Net → Members) places each server at several points (virtual nodes, 64 each)
by the hash of its node id. A slice's owners are the first servers clockwise from `hash(table, day, group)`:

```
                         hash(trades/murex/2026, 2026-09-30, g7)
                                      ▼
                    A ●───────● C           owners of this slice: E (first), A (second, a hot day)
                  ●             ●
               F ●               ● B         newest `hot-days` (5) days: two owners
                 ●               ●           older days: one owner
                  ●             ●
                    D ●───────● E ◄─ first owner clockwise
```

| Days | Owners | Why |
|---|---|---|
| The newest `hot-days` (5) business days | 2 | most reads; a server's restart leaves a warm second owner |
| Older days | 1 | rare reads; any server can still read them (section 10.4) |

With N servers each owns about 1/N of the slices (2/N for hot days), so the cluster's caches add up: six servers hold
six times one server's working set.

### 10.3 Routing

```
 request for (kind, id, date) arrives at server X
   slice ← (table of the date, date, group of the file holding id)    — X's register and range map (all servers keep
                                                                        the range maps of hot days; older days: section 10.5)
   owners ← ring(slice)
   X ∈ owners?   yes → read locally
                 no  → forward to owners[0]          (data forwarding, DRISHTI_NET.md N4: masked there and here)
                        no answer within forward-timeout → owners[1] (if any) → X itself from S3
```

### 10.4 Anyone can read anything

Ownership decides **who caches**, not who may read: every server has the lake's address and role. When owners are
down or slow, the asking server reads the slice itself from S3 (cold, correct). So a server's failure costs speed for
a moment, never answers.

### 10.5 Range maps of old days

A server forwarding a read on an old day needs the file's group, which needs the day's range map. Old days' range maps
are small and immutable: every server keeps them for the days its users touch, loaded on demand (one Delta log read
per closed year, then the day's add actions from the year's final checkpoint, kept as a memory-mapped file). Or the
asking server forwards on `hash(table, day)` to a "day coordinator" that knows the groups: chosen in step S5 by
measurement.

### 10.6 Joining, leaving and rebalancing

| Event | What happens |
|---|---|
| A server joins | it takes about 1/N of the slices (the ring's virtual nodes spread them evenly); it warms them from S3 on first use, or prefetches its hot slices' range maps, footers and id lists at once (`prefetch-hot: true`) |
| A server leaves or dies | gossip marks it dead (`dead-after`, 10 s); its hot slices' second owners already have them warm; its older slices go to the next server clockwise, cold for their first reads |
| A server is slow | forwarding times out to the second owner; Admin → Net shows the slow peer |
| Hot spot (month-end: everyone reads one day) | the day's 12 groups × 2 owners already spread it over the cluster; `hot-owners` (2) can be raised for the newest day |

### 10.7 What changes in Drishti*net

| Today (2.5.0) | New |
|---|---|
| Two nodes with the same table identity are replicas: a search asks one | a table can be **owned in slices** (`ownership: sliced` on the connector): reads forward to the owner, searches go to each slice's owner |
| Capability digests name tables and latest dates | they also name the ring's version, so every node computes the same owners |
| Admin → Net → Members donut shows members | it also shows each member's share of slices and its forwarding |

## 11. Searches, lists, pages and BI

### 11.1 A form search over one day

```
  search: trades where COUNTERPARTY = CP-MERIDIAN-RE and MTM < -1m on 2026-09-30
    │
    ├─ a link index exists for COUNTERPARTY → use it (11.2): one range, no scan
    │
    └─ otherwise: split by slices
         asking server ──► owners of the day's 12 groups (each group to its first owner)
             each owner: its files' promoted columns (warm tier, section 13), skipping files and row groups
                         whose min/max cannot match; filter; first `limit` rows by the order asked
         asking server ◄── partial results ── merge (rows by kind and id; order; cut to limit; totals added)
         partial and failed groups named (DRISHTI_NET.md N5), as today
```

| Search | Expected cost |
|---|---|
| By a link field (counterparty, netting set, portfolio, legal entity, index) | under 300 ms (one range of the link index) |
| By promoted fields, the day split over six servers (about 8 million rows each) | 1–2 s cold columns, 200–500 ms warm |
| By a field that is not promoted | documents scanned up to `max-doc-scan` (20,000), answer marked `partial`; promote the field (a pack change and a reload) to make it fast |

### 11.2 Lists and pages

| Page | Reads |
|---|---|
| Counterparty (reference) | one document |
| Counterparty trades (`CTRD`), portfolio trades (`PTRD`), legal-entity and index trades | the group's totals row (section 8.2) plus the link index range for the members, `page-size` (100) rows a page, with listed columns: no document |
| Netting set | one document, plus the netting set's trades from the link index |
| One trade's history over seven years | the trade's day list (id lists, section 8.4: which days hold it) then one read per day, 64 at a time | 
| Impact (F8: what depends on this) | link indexes of the entity's id across the kinds that link to it |

The 20,000-trade portfolio page (7.4 MB, 3.2 s, *measured*, SCALE_STRATEGY.md section 4.3) becomes its totals and its
first 100 members: under 1 s.

### 11.3 BI

```
 BI query (model "trading", measure sum(MTM) by desk, 2026-09-01 … 2026-09-30)
   range longer than rollup-after-days (5)?
     yes → rollups/trading/2026, the 21 business days × grain desk → DuckDB on the asking server   (seconds)
     no  → split by slices: each owner aggregates its files' columns (DuckDB over Parquet columns),
           returns partial aggregates (sum, count, min, max; sketches for distinct counts)
           → the asking server merges                                                         (seconds)
```

- Measures that do not add (medians, percentiles, ratios of sums) are computed from what does add (sums and counts
  for ratios; t-digest sketches for percentiles, in the rollups when the model declares them).
- Row-level security and masks apply on each owner for the asking user, as every scattered request does today.
- A grain the rollups do not have falls back to the split path for ranges up to `max-split-days` (31); beyond that the
  answer says so and suggests adding the grain to the model's rollups.

### 11.4 Questions over all 88 billion rows

Not interactive in Drishti. The same Delta tables are readable by **Amazon Athena**, **Trino** or **Spark**; Drishti's
SQL target can hand such a question to one of them and show the result when it is ready (minutes). Drishti's own
budget stays seconds.

## 12. Type-ahead

- Source: the day's **id list** product (section 8.4): sorted, prefix-compressed, about 400 MB for 50 million ids.
- Every server memory-maps the newest day's id lists (type-ahead must be local: its budget is 150 ms, too short for
  forwarding): about 400 MB of page cache per server, off the heap.
- Prefix search: binary search on the mapped file, then the next `limit` ids: microseconds.
- Past dates: the type-ahead index's past days (`index.days`) map those days' id lists the same way.

## 13. Memory and caches on a server

### 13.1 The memory of a server (64 GB task)

```
 ┌──────────────────────────────────────────── 64 GB task ────────────────────────────────────────────┐
 │ JVM heap 8 GB                                                                                       │
 │   range maps (owned days) ~10 MB · register ~1 MB · footers of owned files ≤ 1.5 GB                 │
 │   hot columns of owned hot slices ≤ 3 GB · document cache ≤ 1 GB · requests, views ~2 GB            │
 │ off-heap ~8 GB                                                                                      │
 │   RocksDB block cache (document disk cache) 2 GB · DuckDB buffer pool 4 GB · Arrow, ZGC ~2 GB      │
 │ page cache ~44 GB (the operating system's; serves the memory-mapped files)                          │
 │   warm columns of owned days (memory-mapped) · id lists of the newest days · RocksDB files         │
 └─────────────────────────────────────────────────────────────────────────────────────────────────────┘
```

### 13.2 The tiers

| Tier | Holds (owned slices only) | Where | Bound |
|---|---|---|---|
| Hot | range maps; footers; columns of owned hot slices; recent documents | heap | the one memory budget (SCALE_STRATEGY.md section 5) |
| Warm | columns of owned days as sorted column files, memory-mapped | data folder (EFS, per AWS_DEPLOYMENT.md) and the page cache | `warm-max-gb` per server (200) |
| Documents | documents already read | RocksDB document cache (SCALE_STRATEGY.md section 8.1) | size cap, TTL, version purge |
| Lake | everything | S3 | — |

A day's promoted columns are about 4–6 GB in the warm format for 50 million rows; a server owning 1/6 of a day (2/6
for hot days) holds about 1–2 GB of it per day, so its five hot days take about 8 GB of page cache, and older days come
in on demand.

### 13.3 Warming

When a day is published, each server prefetches, for its owned hot slices: range maps (immediately), footers (in the
background, bounded), id lists (immediately, every server), and columns of the newest day (in the background, after
the footers). Users never wait for warming: a read of a slice not yet warm reads cold, correctly.

## 14. Freshness and consistency

- **The register drives everything.** Servers poll it every `register-poll` (10 s; one small Delta table read). A new
  PUBLISHED fingerprint for a day is the only signal that the day changed.
- **Caches are keyed by fingerprint**, not by table version: a commit of another day (the next slice, the next day)
  leaves every other day's caches in place.
- **Atomic switch.** When a day's fingerprint changes, a server serves the old one until its owned slices' new range
  maps are loaded, then switches; the old caches are dropped a minute later. A user sees the old day or the new day,
  never a mixture.
- **Across servers**, two servers may switch a few seconds apart; a page shows the fingerprint's publication time, so a
  user comparing two windows can tell.
- **Live overlays** (Kafka-fed intraday updates) stay as today: an overlay on top of the published day, per entity.

## 15. Seven years: storage tiers and retention

### 15.1 Tiers

```
 age:  0 ──────────── 90 days ─────────────────────────── 2 years ───────────────────────────── 7 years ──► dropped
       │  S3 Standard  │  S3 Standard-IA / Intelligent-Tiering │  S3 Glacier Instant Retrieval       │
       │  read all day │  month-ends, audits, history          │  rare reads, still milliseconds     │
```

| Age | Storage class | Read latency | Storage, list price | Retrieval |
|---|---|---|---|---|
| Up to 90 days | S3 Standard | milliseconds | about USD 23 per TB-month | free |
| 90 days to 2 years | Standard-IA or Intelligent-Tiering | milliseconds | about USD 12.5 per TB-month | about USD 10 per TB read (IA) |
| 2 to 7 years | Glacier Instant Retrieval | milliseconds | about USD 4 per TB-month | about USD 30 per TB read |

**Never** Glacier Flexible Retrieval or Deep Archive for tables Drishti reads: their objects need a restore of hours
before a read.

### 15.2 Rules

- Lifecycle transitions apply by **prefix and age** to data files only (`business_date=` partitions); the `_delta_log`
  folders, the register and the products' newest year stay in Standard.
- **No S3 expiry on any table's files.** Removing files under a live table corrupts it
  ([ICEBERG_CONNECTOR.md section 9](../connectors/ICEBERG_CONNECTOR.md#9-retention-and-maintenance) explains why).
  Retention past seven years drops the whole year's table (the year's folder), by the retention job, after the
  register marks the year expired.
- The landing folder expires its dumps after 30 days (they are not a table).
- **Vacuum** of open tables: weekly, keeping 7 days of removed files (so a reload's old files outlive any reader).

### 15.3 Maintenance jobs

| Job | When | What |
|---|---|---|
| Compact small files | nightly, for days whose slices were reloaded | rewrite a day's files that fell below 50,000 rows |
| Checkpoint | every 10 commits (by the writer) | |
| Vacuum | weekly | open tables, 7-day retention |
| Close a year | first business day of January | compact, final checkpoint, vacuum; read-only |
| Retention | monthly | drop years past retention; the register marks them |

## 16. Consoles

At this scale the consoles are an **ECS service of their own** (the open question of AWS_DEPLOYMENT.md section 21,
answered here), scaled by users:

```
 load balancer ──► console service (4 tasks, auto-scaled on CPU and requests)
                     each: DRISHTI_BACKEND_URLS = two or three servers' Cloud Map names;
                           discovers the rest; keeps a session on one server while it answers
                     sessions: signed cookies; any console takes any user
                     state: its own EFS folders (data/console per task; nothing shared)
```

About 40 pages a second per console process (*measured*), 4 processes per task: four tasks serve about 600 pages a
second, above the target's peaks.

## 17. Failures

| Failure | Effect | Recovery |
|---|---|---|
| A server dies | its hot slices served by their second owners (warm); its older slices by the next server (cold for a moment); its users' consoles fall over | ECS restarts it (AWS_DEPLOYMENT.md); it takes its share back |
| A server is slow | forwards time out to the second owner; scattered searches name it in `failed` past `scatter-budget` | Admin → Net shows it; ECS health checks restart a stuck server |
| S3 throttles (`503 Slow Down`) | retried with back-off; reads slower | counted in Health; spread prefixes (section 6.4) |
| S3 Region trouble | reads fail | disaster recovery (AWS_DEPLOYMENT.md section 16; the lake bucket replicated) |
| A loader task dies | its slice stays `LOADING`; the day is not published | Step Functions retries the task (idempotent: `replaceWhere` on the slice) |
| A slice keeps failing | the day stays unpublished; the previous day is served; an alarm fires | fix the dump or the code; re-run the slice; or publish the day without it, marked partial, by an administrator's decision |
| A commit conflict storm | slices retry their commits | bounded retries; then the slice fails and is re-run |
| The register is unreadable | servers keep their last known register | alarm; a read-only copy of the register is in every server's data folder |
| A bad day published (wrong data) | users see it | republish the day from corrected dumps; or roll the day back: the register's previous fingerprint and Delta's time travel to the previous version of the day's slices |

## 18. Security at scale

- **Masks and roles twice**: every forwarded read and every scattered search is masked on the owner for the token's
  user and again on the asking server (DRISHTI_NET.md section 9), as today.
- **Row-level access** (a desk head sees only their desk) applies on each owner, before partial aggregates leave it,
  so a merged total never contains rows the user may not see.
- **Links and products obey the same rules**: a link index row is a row of its kind, filtered by the user's row-level
  access; group totals are computed per row-level partition where the model declares one (or computed on the fly from
  the link index for users with restricted rows).
- **S3**: SSE-KMS, bucket policies limited to the VPC endpoint and the Drishti roles; loader roles write, server roles
  read; access logs kept.
- **Lineage**: the register records which run published which day, from which dumps: governance shows it per day.

## 19. Sizing and cost

### 19.1 The deployment

| Part | Size | Count |
|---|---|---|
| Servers | 16 vCPU, 64 GB (8 GB heap) | 6 (each owns about 1/6; hot days on two) |
| Consoles | 2 vCPU, 4 GB | 4, auto-scaled |
| Loader tasks | 4 vCPU, 16 GB | up to 32, during the batch window only |
| Planner, publisher, maintenance | 2 vCPU, 8 GB | on schedule |
| S3 | about 120 TB after seven years (documents and products) | growing about 70 GB a business day |
| EFS | servers' caches and state: about 200–300 GB a server; loader spill: up to 1 TB during the window | |

Throughput: about 130 single reads a second on 24 cores (*measured*, SCALE_STRATEGY.md section 4.4), so about 90 on a
16-vCPU server; six servers about 500 a second: the target's peak (section 1). More users: more servers (each added
server takes 1/N of the slices).

### 19.2 Monthly cost, list prices (estimates, one Region, on demand)

| Item | After 1 year | After 7 years |
|---|---|---|
| S3 storage (tiered as section 15) | about USD 250 | about USD 900 |
| S3 requests (reads, loads, lists) | about USD 100–200 | about USD 100–200 |
| S3 retrievals from IA and Glacier IR (history and month-end reads) | tens of USD | about USD 100 |
| Servers (6 × 16 vCPU, 64 GB, always on) | about USD 3,500–4,500 | the same |
| Consoles (4 × 2 vCPU, 4 GB) | about USD 300 | the same |
| Loader fleet (32 tasks × about 1 hour × 21 days) | about USD 150 | the same |
| EFS (about 2 TB, Elastic throughput) | about USD 600–900 | the same |
| Load balancer, endpoints, logs, backups | about USD 300 | the same |
| **Total** | **about USD 5,500–7,000** | **about USD 6,500–8,000** |

Compute dominates; storage stays small even at seven years thanks to tiering. Savings Plans or EC2 capacity lower the
compute part by a third or more.

## 20. Observability

| Signal | Where |
|---|---|
| Per day: plan, slices' states, rows, publication time and fingerprint | Admin → Lakes → Days (new), from the register |
| Per server: owned slices, warm share, forwarded in and out, cold reads | Admin → Net → Members (the donut, with shares) |
| Per slice: reads, cold reads, p50/p95 | Admin → Caches, by table and day |
| S3: GETs, bytes, `503`s, latency | Admin → Health; CloudWatch S3 request metrics |
| Loading: tasks, throughput, retries, commit conflicts | Step Functions and the planner's metrics; Admin → Lakes → Days |
| Alarms | day not published by its deadline; a slice failed twice; a server dead for 5 minutes; cold-read share above 50 % for 10 minutes; S3 `503`s above 1 % |

## 21. Settings (proposed)

| Setting | Default | Meaning |
|---|---|---|
| connector `root` with `{year}` | — | tables by year (section 6.3) |
| connector `years` | listed | the years that exist |
| connector `ownership` | `replica` | `sliced`: owned in serving slices on the ring (section 10) |
| `drishti.net.slices.groups` | 12 | serving slices per table and day |
| `drishti.net.slices.hot-days` | 5 | newest days with two owners |
| `drishti.net.slices.hot-owners` | 2 | owners of a hot slice |
| `drishti.net.slices.virtual-nodes` | 64 | points per server on the ring |
| `drishti.net.slices.prefetch-hot` | true | warm owned hot slices when a day is published |
| `drishti.register.uri` | — | the day register's table |
| `drishti.register.poll` | 10s | how often the register is read |
| `drishti.sources.id-map` | false at this scale | the id map of SCALE_STRATEGY.md, for small tables |
| `drishti.cache.warm-max-gb` | 200 | warm tier per server |
| `drishti.bi.rollup-after-days` | 5 | BI ranges longer than this read rollups |
| `drishti.bi.max-split-days` | 31 | longest range answered by a split scan |
| loader `max-tasks` | 32 | loader tasks at once |
| loader `commit-retries` | 20 | commit retries per slice |
| loader `links.merge` | false | merge a day's link index files at publish |

## 22. Build plan

Each step is useful on its own and measured before the next.

| Step | Content | Done when |
|---|---|---|
| S1 | **Range maps and Bloom filters**: reads find their file from Delta's per-file id statistics; loaders write Bloom filters on `id`; the id map optional | one entity on a 50-million-row day with no id map, within target (correctness locally at 10,000 trades; speed in the environment of S8) |
| S2 | **Tables by year**: the `{year}` root pattern, closing a year | a date in any year read; a year's log stays small |
| S3 | **The loader fleet**: planner, slice loader, safe commits (conditional writes; DynamoDB), publisher, register, reloads | 500 GB loaded by 32 tasks within the window; a killed task re-run; no half day ever read |
| S4 | **Products**: link indexes, group totals, rollups, id lists; the `derived` connector and BI read them | the CTRD, counterparty and portfolio pages and BI over a year answer from products |
| S5 | **Slices and owners**: serving slices on the ring, forwarding to owners, failover, Admin → Net shares | three servers, each warming only its share; one stopped and the reads continue |
| S6 | **Split searches and aggregates** across owners, merged | a day's search over six servers equals the single-server answer, row for row |
| S7 | **Warm tier**: memory-mapped column files of owned days; the one memory budget | a server with 8 GB of heap serves its share of 1,764 days |
| S8 | **Benchmark in AWS**: a generator of synthetic documents of the real shapes, 1/10 scale (5 million a day for 30 days), then full scale for 10 days; every estimate here replaced by a measurement | the targets of section 1 met, or the gap stated with its fix |

The local machine stays at its limit of 10,000 trades; every step is proven for correctness locally and for scale in
the AWS environment of step S8.

## 23. Tests

| Test | Proves |
|---|---|
| Range map reads equal id map reads, every entity of a fixture lake | S1 correct |
| A Bloom filter miss reads no data (counted GETs) | S1 efficient |
| Concurrent slice commits on one table (MinIO with conditional writes; the DynamoDB lock against DynamoDB Local) | no lost commit; every slice's rows present once |
| A reader polling during a reload never sees two fingerprints in one answer | a day appears whole |
| Products equal the same numbers computed from the documents | products correct |
| Ownership: every slice has the expected owners for a given ring; one server removed moves only its slices | the ring |
| Split search and split aggregate equal the single-server answer | S6 |
| Masks and row-level access on split aggregates: a restricted user's totals equal the totals of the rows they may see | security |
| Failure drills (S8): a server killed mid-load of users; S3 throttling injected; a loader killed | section 17 |

## 24. Risks and open questions

- **Searches without a link index** over 50 million rows cost seconds even split six ways; the fields users search
  most should get link indexes (a pack setting): which fields, decided with the users.
- **History across years** reads one cold row group per day: fast for one entity, not for thousands at once (that is
  Athena's job, section 11.4).
- **Delta Kernel's support for v2 checkpoints** decides whether a year's snapshot stays cheap without tables by year;
  tables by year make it safe either way.
- **EFS for loader spill** (the AWS rule): about 1 TB written and read per day; Elastic throughput handles it, at a
  cost (section 19.2); whether the loaders' scratch may use task-local storage instead is the product owner's decision.
- **Who writes the products**: Drishti's loaders (this design), or the bank's own pipeline (Spark, Glue) writing
  Drishti's layout. The layout is documented either way.
- **Open for the product owner**: the release; the source systems' real document sizes and counts (to replace the
  assumed mix of section 2); the link-index fields; the console service versus one console per server.
