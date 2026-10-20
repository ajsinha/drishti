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
# Super scale: 500 GB a day for seven years, on one shared Delta lake in S3

Status: **design, not built.** Release not decided (product owner, 2026-10-20). It builds on
[SCALE_STRATEGY.md](SCALE_STRATEGY.md) (one server, millions of trades a day, built and measured),
[DRISHTI_NET.md](DRISHTI_NET.md) (many servers, one Drishti, built in 2.5.0) and [AWS_DEPLOYMENT.md](AWS_DEPLOYMENT.md)
(ECS, EFS, S3). Every number here is an **estimate** unless marked *measured*; step S8 replaces them with measurements.

## Contents

1. [The ask](#1-the-ask) · 2. [The size of it](#2-the-size-of-it) · 3. [What breaks if nothing changes](#3-what-breaks-if-nothing-changes) ·
4. [The shape](#4-the-shape) · 5. [The lake](#5-the-lake-in-s3) · 6. [Loading 500 GB a day](#6-loading-500-gb-a-day) ·
7. [Computed when loading](#7-computed-when-loading-not-when-asked) · 8. [Reading one entity](#8-reading-one-entity-without-an-id-map) ·
9. [Many servers on one lake](#9-many-servers-on-one-lake-drishtinet) · 10. [Searches, lists and BI](#10-searches-lists-and-bi) ·
11. [Memory and caches](#11-memory-and-caches-per-server) · 12. [Seven years: tiers and history](#12-seven-years-tiers-and-history) ·
13. [Consoles](#13-consoles) · 14. [Sizing a deployment](#14-sizing-a-deployment) · 15. [Build plan](#15-build-plan) ·
16. [Risks and open questions](#16-risks-and-open-questions)

## 1. The ask

The product owner (2026-10-20): Drishti must serve **500 GB of new data a day, kept for seven years**. The store is
**Delta Lake**, one **common lake in S3**, read by **several servers and several consoles** joined by **Drishti*net**.
Every user, on any console, sees one Drishti; any server can answer for any data.

The goals stay those of SCALE_STRATEGY.md section 1, at a hundred times the volume:

| | Target |
|---|---|
| Open one entity, any of the 1,760 business days | p95 under 300 ms (today's day), under 1 s (a cold day years back) |
| Search or form over one day | p95 under 2 s |
| Counterparty, portfolio, netting-set pages | p95 under 1 s |
| A day's 500 GB loaded | within a 2-hour batch window, the day appearing whole or not at all |
| Heap per server | **8 GB** still (the principle of SCALE_STRATEGY.md: scale out, not up) |
| Truth | the Delta lake in S3; everything else is a cache or a product of the load, rebuildable from it |

## 2. The size of it

"500 GB a day" is taken as **500 GB of source JSON documents** (what the source systems dump). Trade documents measure
6.5–14 KB each (SCALE_STRATEGY.md section 4.3), about 10 KB on average, and compress about 8 times in Parquet with
ZSTD (ICEBERG_CONNECTOR.md section 3: 0.7 KB per 6.5 KB trade).

| | A day | A year (252 business days) | Seven years (1,764 days) |
|---|---|---|---|
| Source JSON | 500 GB | 126 TB | 882 TB |
| Documents (rows) | **50 million** | 12.6 billion | **88 billion** |
| Parquet in the lake (documents plus promoted columns) | about 65 GB | about 16 TB | **about 115 TB** |
| Load-time products (section 7: link indexes, grouped pages, rollups) | about 8 GB | about 2 TB | about 14 TB |
| Files (about 250,000 rows each) | about 200 | about 50,000 | about 350,000 |

If the 500 GB are **already Parquet**, every row above is about 8 times larger (400 million documents a day, about
900 TB in seven years). The design is the same; section 14 scales the servers with it.

S3 holds this comfortably: 115–130 TB is ordinary for S3, and costs about USD 1,000–3,000 a month at list prices
depending on how much of it sits in cheaper tiers (section 12).

## 3. What breaks if nothing changes

What SCALE_STRATEGY.md built is right for about 1–2 million documents a day per server. At 50 million a day:

| Today | At 50 million a day |
|---|---|
| An **id map** per table and day (every id → its file, about 60 B per id) | 3 GB per day: a server holds one or two days, not 1,764 |
| A day's **promoted columns** in the heap (about 230 MB per million rows for 19 fields) | 11.5 GB per day: more than the heap |
| **Grouped pages** built per server from one pass over the day (45 s for 1.16 million, *measured*) | about 30 minutes per server per day, repeated on every server |
| **One Delta table** per source and kind for all history | 350,000 files in one log: every snapshot of the table lists them all |
| **Loaders** one per system (300,000–500,000 trades in 47–161 s, *measured*) | about 3 hours for one loader: parallel loaders on one table need a safe commit |
| Several servers on one lake are **replicas**: each warms its own caches for everything | every server does the same work; adding servers adds no cache |

The design answers each line: sections 8, 10–11, 7, 5, 6 and 9 in that order.

## 4. The shape

```
                         users ──► load balancer ──► consoles (a service of its own, scaled by users)
                                                         │  each console uses any server; discovers the rest
                ┌────────────────────────────────────────┼────────────────────────────────────────┐
                ▼                                        ▼                                        ▼
         server A                                 server B                                 server C   … N
   owns (table, day) slices                 owns its slices                          owns its slices
   by the hash ring; caches only them       (Drishti*net forwards a read to the owner in one hop;
                                             a search is split across owners and merged)
                │                                        │                                        │
                └──────────────── reads ─────────────────┼────────────────────────────────────────┘
                                                         ▼
     S3: ONE Delta lake ── trades/<system>/<year>/ (business_date partitions, files sorted by id)
                        ── products/ (link indexes, grouped pages, rollups: written by the loaders)
                                                         ▲
                       loader fleet (ECS tasks), one per (system, product type) slice of the day,
                       commits made safe by S3 conditional writes or a DynamoDB lock
```

Three ideas carry the scale:

1. **Compute when loading, not when asked.** What every server would otherwise compute (grouped pages, link lists,
   totals) is computed once by the loaders and stored in the lake beside the data (section 7).
2. **Find by ranges, not by maps.** Files of a day hold sorted, disjoint id ranges; the Delta log already records
   each file's lowest and highest id, so a read finds its file from a few hundred ranges, not 50 million ids (section 8).
3. **Each server owns a share.** On one shared lake, the hash ring of Drishti*net gives each (table, day) slice an owner
   (and a second owner for recent days); reads go to the owner, so the servers' caches add up instead of repeating
   each other (section 9).

## 5. The lake in S3

- **One table per source system, kind and year**: `s3://<lake>/trades/endur/2026/`. A year's log holds about
  50,000 files, not 350,000; old years are closed (no more writes), compacted once, checkpointed and vacuumed. The
  connector maps a business date to its year's table (a `tables-by-year` pattern on the connector:
  `s3://<lake>/trades/endur/{year}/`), and `inventory` lists the years.
- **Partitions**: `business_date`, plus `PRODUCT_TYPE` for trades (as today).
- **Files**: sorted by id, **disjoint id ranges** within a partition, about 250,000 rows (about 350 MB) each, Parquet row
  groups of 1 MB (as today), ZSTD. Delta's per-file statistics hold the min and max of `id` and of each promoted column.
- **Checkpoints** every 10 commits; v2 checkpoints with sidecar files when the Delta Kernel version allows, so a
  snapshot reads only what changed.
- **Read by the native engine** (`drishti-deltalake`: the AWS SDK, ranged GETs, no Hadoop), as today.
- **Request rates**: S3 serves at least 5,500 GETs a second per prefix; partitions are prefixes, so the day being read
  hard is spread over its product-type prefixes.

## 6. Loading 500 GB a day

- **A fleet of loader tasks** (ECS tasks, or AWS Batch), one per **slice**: a source system × product type × business
  day, about 100–200 slices a day. Each reads its slice of the dump (S3), sorts it by id (external sort, spill on the
  task's disk), writes its files, and commits. Today one loader writes about 50 MB of JSON a second (*measured*: 3 GB
  in about 60 s); 32 tasks load 500 GB in about 5 minutes of writing, and the window is bounded by the dumps' arrival,
  not by Drishti.
- **Safe concurrent commits** to one table: delta-rs's S3 commit through **conditional writes** (S3 `If-None-Match`), or
  its **DynamoDB lock provider** where conditional writes are not available. Each slice is one partition, so commits
  never conflict on data, only on the log version, and retry.
- **A day appears whole.** The slices commit as they finish; the day is **published** when the last slice is in, by one
  small commit to a **day register** (`products/days/`: kind, day, slices, rows, a fingerprint). Servers read a day only
  once it is registered, so nobody sees half a day. A reload of a day writes its slices again and re-publishes.
- **LoadGuard** as today: dates checked, a drop of more than half a table refused without `--force-drop`.
- **Idempotent**: a slice re-run replaces its partition (`replaceWhere`), so a failed task is simply run again.

## 7. Computed when loading, not when asked

Each slice, while it has the day's rows sorted in hand, also writes **products** to their own tables (one pass, no
second read). Every server then reads them like any table; none computes them.

| Product | Table | Content | Replaces |
|---|---|---|---|
| **Link indexes** | `products/links/<kind>/<field>/<year>/` | for each promoted link field (counterparty, netting set, portfolio, legal entity, index): `(value, id, business_date)` sorted by value, files with disjoint value ranges | reverse lookups and "trades of counterparty X" reading a day's columns in memory |
| **Grouped pages** | `products/groups/<grouped kind>/<year>/` | per group and day: totals (count, sums, extremes, distinct values, payments ladder sums) and the member count | the `derived` connector's per-server pass (section 3) |
| **Rollups** | `products/rollups/<model>/<year>/` | per day and the model's common grains (desk, portfolio, counterparty, product type, currency): sums, counts, distinct counts (HyperLogLog sketches) | BI scanning raw rows for ranges longer than a few days |

Slices produce partial products; the day's publishing step (section 6) merges the per-slice parts of grouped pages and
rollups (small: thousands of groups) into the day's final rows. A product is versioned with the day's fingerprint, so a
reload replaces it with the data.

The grouped page of a counterparty then reads its totals (one row) and its members from the link index (a range of
sorted rows: `value = CP-…`), a page at a time; no server holds members in memory or on disk.

## 8. Reading one entity without an id map

1. **The day and the table**: the date picks the year's table; the day register says the day is published.
2. **The file**: the table's file list for that partition, with each file's `id` min and max from the Delta log, kept
   per (table, day) as a **range map**: about 200 entries a day, a few KB, so all 1,764 days of a table fit in a few
   MB. A binary search gives the one file whose range holds the id.
3. **The row group**: the file's footer (cached per file, as since 2026-10-18, *measured*: the fix that took single
   reads from 66 to 133 requests a second) gives the row group by its id statistics.
4. **One ranged GET** of that row group (about 1 MB) from S3, decoded, the document cut out. With a **Bloom filter on
   `id`** (Parquet's split-block filter, written by the loaders), a miss on a day the entity does not have costs no
   data read at all.

Cost: one S3 GET of about 1 MB for a cold read, about 30–80 ms in the same region; then the document cache. The id map
of SCALE_STRATEGY.md stays only as an option for small tables, where it is faster on local disk.

## 9. Many servers on one lake (Drishti*net)

Today servers that read the same lake are **replicas**: any one answers, and each caches whatever it is asked. At this
scale that wastes the cluster's memory. The design adds **ownership on a shared lake**:

- **Slices and owners.** A slice is a (table, business day). Its owners are the next nodes on the hash ring (the ring
  of Drishti*net's Members donut) from `hash(table, day)`: **two owners** for the newest `hot-days` (5) days,
  **one** for older days. A node's caches (range maps, footers, columns, documents) hold only the slices it owns.
- **Reads go to an owner.** A node asked for an entity on a slice it does not own forwards the read to an owner in
  one hop (data forwarding, built in N4: masked twice, the answering node shown to administrators only); if the owner
  does not answer within `forward-timeout`, the second owner, then itself (any node *can* read any slice from S3).
- **Searches are split.** A search over a day goes to the day's owners, each scanning its own files of the day, and is
  merged as N5's scatter-gather merges today (rows by kind and id, sums added, `partial` and `failed` named). New: a
  shared table is split **by files**, where today a replica is asked whole.
- **Joining and leaving.** A node joining takes over its share of slices (about 1/N); it warms them from S3 on first
  use. A node leaving: its slices' second owner (hot days) or next node (older days) takes over at once, with cold
  caches for a moment, never an error.
- **Admin → Net → Members** shows each node's share of slices on the donut and the forwarding per peer, as today.

With N servers the cluster's cache is N times one server's, and a day's work is split N ways.

## 10. Searches, lists and BI

| Question | Answered from | Expected cost |
|---|---|---|
| Trades of counterparty X on a day (the CTRD page, a reverse link) | the link index: a range of sorted rows, one file or two | under 1 s, paged |
| A form search on promoted fields over one day (50 million rows) | split across the day's owners; each filters its files' columns, skipping files by min/max statistics, and the matching rows merge | 1–2 s with 6 owners; less when the filter has a link index |
| Counterparty, portfolio, legal-entity pages | grouped-page product (one row) plus the link index for members | under 1 s |
| BI over one day or a few days | the day's owners each aggregate their files (DuckDB over the Parquet columns), partial aggregates merged | seconds |
| BI over months or years | rollups (section 7) | seconds |
| One entity's history over seven years | 1,764 range-map lookups and ranged GETs, 64 at a time, or the entity's link through `entity_days` when the kind is `effective` | 1–3 s |
| Ad-hoc questions over all 88 billion rows | not interactive in Drishti: the same Delta tables are queryable by Amazon Athena, Trino or Spark, and Drishti's SQL target can hand such a question to one of them | minutes, outside the console's budget |

## 11. Memory and caches per server

The heap stays at **8 GB**; what grows goes to memory-mapped files and the page cache:

| Tier | Holds (the owned slices only) | Where | Bound |
|---|---|---|---|
| Hot | range maps (all days owned: a few MB); columns of owned hot days | heap | the one memory budget (SCALE_STRATEGY.md section 5) |
| Warm | columns of owned days as sorted, memory-mapped files; type-ahead ids (`index.storage: mmap`) | node's data folder (EFS under AWS_DEPLOYMENT.md's rule) and the OS page cache | `max-disk` per connector |
| Documents | documents already read | RocksDB document cache (SCALE_STRATEGY.md section 8.1) | size cap, TTL, version purge |
| Footers | Parquet footers of owned files | heap, bounded (`ParquetBatches`) | row groups' worth |

A server of this tier wants **8 GB of heap and 32–64 GB more for the page cache**: a 64 GB task, not a 12 GB one.

## 12. Seven years: tiers and history

| Age | S3 storage class | Read latency | Why |
|---|---|---|---|
| Last 90 days | S3 Standard | milliseconds | read all day long |
| 90 days to 2 years | S3 Intelligent-Tiering (or Standard-IA) | milliseconds | read for month-ends, audits, history |
| Older | S3 Glacier Instant Retrieval | milliseconds | read rarely, but must still answer in the console |

**Never** Glacier Flexible Retrieval or Deep Archive for tables Drishti reads: their objects cannot be read without a
restore of hours. Lifecycle rules apply to **closed years' tables only**, after their final compaction, and never
delete: retention past seven years is a table drop by the retention job, not an S3 expiry
([ICEBERG_CONNECTOR.md section 9](../connectors/ICEBERG_CONNECTOR.md#9-retention-and-maintenance) explains why expiring
files under a live table corrupts it). The Delta logs and checkpoints stay in S3 Standard.

## 13. Consoles

At this scale consoles are a **service of their own**, scaled by users (about 40 pages a second per console process,
*measured*), behind the load balancer. Each console starts with one or more servers' addresses, discovers the rest
(`DRISHTI_BACKEND_DISCOVER`), keeps each session on one server while it answers and falls over to the next. Any server
can take any request, because Drishti*net forwards it to an owner. Sessions are signed cookies, so any console takes
any user. (In AWS_DEPLOYMENT.md this is the open question "one console per node or a console service": at this scale,
a console service.)

## 14. Sizing a deployment

Estimates for 500 GB of JSON a day; to be confirmed by step S8.

| Part | Size |
|---|---|
| Servers | **6** tasks of 8 vCPU and 64 GB (8 GB heap), each owning about a sixth of the slices; hot days on two owners each |
| Consoles | 3–4 tasks of 2 vCPU and 4 GB, scaled by users |
| Loader fleet | up to 32 tasks of 4 vCPU and 16 GB during the batch window; none outside it |
| S3 | about 130 TB after seven years, growing about 70 GB a day |
| EFS | the servers' caches and state: a few hundred GB |
| Throughput | about 130 single reads a second per server (*measured* on one machine, SCALE_STRATEGY.md section 4.4), so about 800 a second for six |

If the 500 GB are already Parquet (400 million rows a day): about 24 servers and 128 loader tasks; everything else
scales with it.

## 15. Build plan

Each step is useful on its own and measured before the next.

| Step | Content | Done when |
|---|---|---|
| S1 | **Range maps and Bloom filters**: a read finds its file from Delta's per-file id statistics; loaders write Bloom filters on `id`; the id map becomes optional | one entity read on a 50-million-row day with no id map, within target |
| S2 | **Tables by year**: the connector's `tables-by-year` pattern, closing a year (compact, checkpoint, vacuum) | a date in any year read; the log of a year stays small |
| S3 | **The loader fleet**: slices, conditional-write or DynamoDB commits, the day register, re-runnable slices | 500 GB loaded by 32 tasks within the window; a killed task re-run; no half day ever read |
| S4 | **Products**: link indexes, grouped-page and rollup tables written by the slices and merged at publish; the `derived` connector and BI read them | the CTRD, counterparty and portfolio pages and BI over a year answer from products |
| S5 | **Ownership on a shared lake**: slices, owners, forwarding to the owner, failover, the donut showing shares | three servers, each warming only its share; a server stopped and the reads continue |
| S6 | **Split searches and aggregates**: a shared table split by files across owners, merged | a day's form search across six servers equals the single-server answer |
| S7 | **Warm tier**: columns of owned days as memory-mapped files; the one memory budget | a server with 8 GB of heap serves its share of 1,764 days |
| S8 | **Benchmark in AWS**: a synthetic generator at 1/10 scale (5 million rows a day for 30 days), then full scale for a week of days; every estimate here replaced by a measurement | the targets of section 1 met, or the gap stated |

Step S8 runs only in AWS: the local machine stays at its limit of 10,000 trades.

## 16. Risks and open questions

- **What "500 GB" means** (JSON or Parquet) changes the server count by about 8 times; to be confirmed with the source
  systems' dump sizes.
- **Delta on S3 with concurrent writers** depends on conditional writes or the DynamoDB lock; S3 itself now supports
  conditional writes, S3-compatible stores may not.
- **Searches without a link index** over 50 million rows cost seconds even split six ways; the promoted fields that
  users search most should get link indexes (a pack setting).
- **History across years** reads one cold row group per day; fast enough for one entity, not for thousands at once.
- **Cost**: storage is modest; the larger costs are the servers' memory and the loader fleet's hours.
- **Open for the product owner**: the release; whether the products (section 7) are written by Drishti's loaders or
  by the bank's own pipeline (Spark, Glue) in Drishti's layout; which promoted fields get link indexes by default.
