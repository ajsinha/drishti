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
# A high-performance Delta lake for Drishti on AWS, past 200 TB

Status: **scripts in [`deploy/aws/`](../../deploy/aws), images in [`deploy/docker/ecs/`](../../deploy/docker/ecs).** Every
script has `--help` and `--dry-run` and is tested that way (`tools/test_deploy_aws.py`); none has yet been run against a real
AWS account, so the first run in a development account is part of the work (section 15). Where this guide relies on something
that is designed but not built, it says **planned** and names the step of [SUPER_SCALE.md](SUPER_SCALE.md) (S1 to S8) that
builds it.

Related: [AWS_DEPLOYMENT.md](AWS_DEPLOYMENT.md) (ECS, EFS and the identical-path rule), [SUPER_SCALE.md](SUPER_SCALE.md)
(500 GB a day for seven years), [DELTA_CONNECTOR.md](../connectors/DELTA_CONNECTOR.md) (how Drishti reads a Delta table),
[LAKE_MAINTENANCE.md](../admin/LAKE_MAINTENANCE.md) (`lake maintain`), [TOOLS.md](../guides/TOOLS.md) (`lake build`,
`lake publish`), [DRISHTI_NET.md](DRISHTI_NET.md) (several servers).

> **The lake lives in Amazon S3: every table's Parquet data files AND its `_delta_log`.** Nothing of the lake is on EFS.
> EFS holds only what a node must find again after a restart: its configuration, users and tokens, Drishti\*net state, and
> the caches that should be warm after a restart (or local NVMe for those caches, section 10). The loader's scratch folder on
> EFS holds one day of one table for the minutes of a load, and is removed after it.

## Contents

1. [The picture](#1-the-picture)
2. [Why S3 and not EFS for the lake](#2-why-s3-and-not-efs-for-the-lake)
3. [Built and planned](#3-built-and-planned)
4. [First-time setup, step by step](#4-first-time-setup-step-by-step)
5. [The layout: buckets, prefixes, tables and partitions](#5-the-layout-buckets-prefixes-tables-and-partitions)
6. [Keys: the id, promoted columns and statistics](#6-keys-the-id-promoted-columns-and-statistics)
7. [Files: sizes, row groups and sorting](#7-files-sizes-row-groups-and-sorting)
8. [Loading: the daily load, backfill and restatement](#8-loading-the-daily-load-backfill-and-restatement)
9. [Maintenance: checkpoints, retention, vacuum and storage tiers](#9-maintenance-checkpoints-retention-vacuum-and-storage-tiers)
10. [Caches: EFS or local NVMe](#10-caches-efs-or-local-nvme)
11. [Staying fast past 200 TB](#11-staying-fast-past-200-tb)
12. [Sizing and cost](#12-sizing-and-cost)
13. [Runbooks](#13-runbooks)
14. [The scripts](#14-the-scripts)
15. [Limits and what comes next](#15-limits-and-what-comes-next)

## 1. The picture

```
  SOURCES (ENDUR, CALYPSO, MUREX, reference)        dumps: JSON / JSON Lines / .gz
          │
          ▼
  ┌── S3: <org>-drishti-<env>-landing ──────────────┐     kept LANDING_KEEP_DAYS (30), then expired
  │  2026-10-20/endur/*.jsonl.gz   …/calypso/  …/murex/ │
  └──────────────┬──────────────────────────────────┘
                 │ EventBridge Scheduler, 22:30 New York, Monday to Friday
                 ▼
  ┌── ECS task "loader" (image drishti-loader-ecs) ─────────────────────────────────────────────────────┐
  │ load-day.sh, per table, under the table's lock (EFS):                                                │
  │  1 aws s3 sync   landing → /opt/drishti/data/lake-build/<run>/in/<table>/      (EFS scratch)         │
  │  2 lake build    → a scratch Delta table: promoted columns, sorted by id, Parquet row groups 1 MB    │
  │  3 aws s3 sync   scratch data files → s3://…-lake/lake/<domain>/<table>/business_date=…/             │
  │  4 lake publish  ONE Delta commit on S3 (conditional PUT): the day's old files out, new ones in      │
  │  5 metrics       Drishti/Lake: LoadSucceeded, LoadSeconds, PublishedBusinessDate                     │
  └──────────────┬──────────────────────────────────────────────────────────────────────────────────────┘
                 ▼
  ┌── S3: <org>-drishti-<env>-lake (THE LAKE) ──────────────────────────────────────────────────────────┐
  │  lake/trading/trade_endur/_delta_log/   00000000000000000412.json … checkpoints, _last_checkpoint    │
  │  lake/trading/trade_endur/business_date=2026-10-20/PRODUCT_TYPE=IRS_FIXFLOAT/part-…​.snappy.parquet │
  │  versioning · SSE-KMS · only through the VPC endpoint · tiers by business date:                      │
  │      Standard (0–90 days) → Standard-IA (to 2 years) → Glacier Instant Retrieval (beyond)            │
  └──────────────┬──────────────────────────────────────────────────────▲───────────────────────────────┘
                 │ ranged GETs (task role, gateway endpoint)             │ checkpoint, retention, vacuum
                 ▼                                                       │ (ECS task "maintenance", nightly)
  ┌── ECS services drishti-a, drishti-b, drishti-c (one task each: server + console) ──────────────────┐
  │ server: Delta connector, native engine, root s3://…-lake/lake (DRISHTI_DELTA_ROOT)                  │
  │   heap: file lists, id maps, column sets, documents, Parquet footers                                │
  │   disk: id maps and documents (RocksDB) under /opt/drishti/data/cache (EFS) or /opt/drishti/nvme    │
  │ Drishti*net: gossip, forwarding and scatter-gather between task IPs; seeds by Cloud Map names       │
  └──────────────┬─────────────────────────────────────────────────────────────────────────────────────┘
                 ▼
  ┌── EFS (Regional, encrypted, Elastic throughput) ───────────────────────────────────────────────────┐
  │ /drishti/<env>/<node>/opt/drishti/{data,config,sutras}   one access point per node per path          │
  │ /drishti/<env>/loader/opt/drishti/data                   scratch lakes (minutes), table locks         │
  │ NO lake data, NO _delta_log                                                                          │
  └─────────────────────────────────────────────────────────────────────────────────────────────────────┘
```

## 2. Why S3 and not EFS for the lake

| | Amazon S3 | Amazon EFS |
|---|---|---|
| **Cost of 200 TB** (list prices, one Region) | about USD 1,400 a month tiered by business date (section 12); USD 4,600 all in Standard | USD 60,000 a month in EFS Standard; USD 3,200 in Infrequent Access plus a charge on every byte read, with tens of milliseconds added to each read |
| **Request rate** | at least 3,500 writes and 5,500 reads a second **per prefix**, and every `business_date=…/PRODUCT_TYPE=…/` folder is a prefix: the rate grows with the folders | one file system's limits (Elastic throughput: GB/s across all clients, IOPS per file system); every node, loader and maintenance job shares them |
| **Latency of a single read** | 10–30 ms to the first byte, in-Region; Drishti reads one row group (section 11.1) | about 1 ms for hot data in Standard; tens of ms in Infrequent Access |
| **Durability** | eleven nines, three or more Availability Zones, versioning (section 5.1), Cross-Region Replication | Regional: three zones; backup by AWS Backup |
| **Tiers** | Standard → Standard-IA → Glacier Instant Retrieval, still read in milliseconds; set per prefix and date by lifecycle rules (section 9.4) | Standard → Infrequent Access → Archive by last access only |
| **Other readers** | Athena, Trino, Spark, EMR and Glue read the same Delta tables in S3 (questions over all of history: [SUPER_SCALE.md section 11.4](SUPER_SCALE.md#114-questions-over-all-88-billion-rows)) | only something that mounts the file system |
| **Writers** | conditional writes make concurrent commits safe (section 8.2) | a hard link on a POSIX file system |

At 200 TB and beyond, EFS costs ten to forty times more and shares one throughput budget between every reader and writer.
S3 scales its request rate by prefix and its cost by tier. Drishti's reads are built for object storage: they read one day's
files and one row group of one file at a time, by byte ranges (section 11.1). So the lake is in S3, and EFS keeps the small,
hot, per-node state.

## 3. Built and planned

| | Status | Where |
|---|---|---|
| Reading Delta tables in S3 (native engine, no Hadoop): file lists from the last checkpoint, id maps, one row group per read, footers cached, documents cached on disk | **built** | `drishti-deltalake`, `drishti-lakeio`, `plugins/drishti-plugin-delta`; [DELTA_CONNECTOR.md](../connectors/DELTA_CONNECTOR.md) |
| `lake build`: a JSON export into a Delta table on local disk or EFS, the pack's promoted columns, partitions, sorted by id per partition that fits a batch | **built** | `tools/lake/build.py`, Drishti's Java writer in the server jar |
| `lake publish`: a table `lake build` wrote, committed into a table in S3 as one commit (S3 conditional writes) | **built** (this release) | `tools/lake/publish.py`, `tools/lake/test_publish.py` |
| `lake maintain` on an `s3://` root: retention, checkpoint and log cleanup, vacuum | **built** | `tools/lake/maintain.py` (with `--force-foreign`, section 9) |
| Buckets, IAM, conditional-write check, lifecycle tiers, alarms, report, load, backfill, maintenance, EFS access points, Cloud Map, task and service definitions, schedules | **scripts** (`--dry-run` tested; first real run pending) | [`deploy/aws/`](../../deploy/aws) |
| The ECS images (server, console, loader) and their entry points | **files** (not yet built in CI) | [`deploy/docker/ecs/`](../../deploy/docker/ecs) |
| Drishti\*net shards by table: nodes that read different tables are asked together; nodes that read the same table are replicas, and an entity goes to the same replica by a hash of its id | **built** | [DRISHTI_NET.md sections 7 and 10](DRISHTI_NET.md#10-shared-and-separate-lakes) |
| Range maps from the log's per-file id statistics, Parquet Bloom filters on `id` | **planned (SUPER_SCALE step S1)** | |
| Tables by year (`root: …/{year}`), closing a year | **planned (S2)** | |
| A loader fleet writing slices of one table at once, a day register, publication by fingerprint | **planned (S3)** | |
| Products (link indexes, group totals, rollups, id lists) written with the data | **planned (S4)** | |
| Each server owning a share of (table, day) slices on the ring; split searches and aggregates | **planned (S5, S6)** | |
| Warm tier of memory-mapped column files of owned days | **planned (S7)** | |
| Benchmark at scale in AWS | **planned (S8)** | |

## 4. First-time setup, step by step

Run the steps from a workstation or an administration host with the AWS CLI v2, the repository, Java 21 and the console's
virtual environment (`drishti-console/.venv`). Every script takes its settings from `deploy/aws/env`; every script prints its
AWS calls first with `--dry-run`. Run each with `--dry-run`, read what it would do, then run it without.

### Step 0: prerequisites

Before the scripts: a VPC with private subnets in three Availability Zones, a **gateway VPC endpoint for S3** on their route
tables, interface endpoints for ECR, CloudWatch Logs, Secrets Manager and STS, an EFS file system (Regional, encrypted,
Elastic throughput, a mount target per subnet), a KMS key for the lake (its key policy allows the roles of step 3), an ECS
cluster, an Application Load Balancer with a target group for the consoles on port 17480, an SNS topic for alarms, and the four
secrets of [AWS_DEPLOYMENT.md section 11.3](AWS_DEPLOYMENT.md#113-secrets). These are the bank's platform team's templates
(CloudFormation or Terraform); the scripts take their ids.

### Step 1: the settings

```bash
cp deploy/aws/env.example deploy/aws/env        # git-ignored; holds account ids and names, never a secret
$EDITOR deploy/aws/env                          # replace every <placeholder>
cp deploy/aws/delta-lake/tables.example deploy/aws/delta-lake/tables.conf   # optional: your tables; set LAKE_TABLES_FILE
```

**You should see:** nothing yet. A script run with a placeholder left says which one:
`setup-buckets.sh: LAKE_KMS_KEY_ARN is still a placeholder (<arn:aws:kms:…>) in deploy/aws/env`.

The tables file has one line per table: its folder, kind, pack, the pack's connector, the landing prefix (`{date}` is the
business date), the business-date field (`-` for the day being loaded) and the partition column:

```
trading/trade_endur      trade   config/packs/trading   trade-endur     {date}/endur/           -      PRODUCT_TYPE
```

### Step 2: the buckets

```bash
deploy/aws/delta-lake/setup-buckets.sh --dry-run
deploy/aws/delta-lake/setup-buckets.sh
```

**You should see** each call as it runs, ending in
`setup-buckets.sh: done: acme-drishti-prod-lake, acme-drishti-prod-landing, acme-drishti-prod-logs`:

```
+ aws s3api create-bucket --bucket acme-drishti-prod-lake --object-ownership BucketOwnerEnforced --create-bucket-configuration LocationConstraint=eu-west-2
+ aws s3api put-public-access-block --bucket acme-drishti-prod-lake --public-access-block-configuration BlockPublicAcls=true,…
+ aws s3api put-bucket-versioning --bucket acme-drishti-prod-lake --versioning-configuration Status=Enabled
+ aws s3api put-bucket-encryption --bucket acme-drishti-prod-lake --server-side-encryption-configuration file://…/enc-….json
+ aws s3api put-bucket-policy --bucket acme-drishti-prod-lake --policy file://…/policy-….json
…
```

Run again, it says `acme-drishti-prod-lake exists: kept, settings applied again` and puts the same settings.

### Step 3: who may do what

```bash
deploy/aws/delta-lake/setup-iam.sh --dry-run
deploy/aws/delta-lake/setup-iam.sh
```

| Role | Policy (`deploy/aws/delta-lake/iam/`) | May |
|---|---|---|
| `drishti-<env>-server-task` | `server-policy.json` | read the lake prefix (GET, LIST), decrypt; mount EFS through access points |
| `drishti-<env>-loader-task` | `loader-policy.json` | read the landing bucket; write data files and commits; **never delete** (an explicit deny) |
| `drishti-<env>-maintenance-task` | `maintenance-policy.json` | checkpoints, retention and vacuum: the only role that deletes; the lifecycle rules |
| `drishti-<env>-execution` | AWS's `AmazonECSTaskExecutionRolePolicy` and `drishti/<env>/*` secrets | ECS pulls images, writes logs, injects secrets |
| `drishti-<env>-scheduler` | inline | EventBridge Scheduler runs the lake tasks |

**You should see** `setup-iam.sh: done: three policies, five roles (…)`. A policy that exists gets a new default version.

### Step 4: prove that commits are safe

```bash
deploy/aws/delta-lake/check-conditional-writes.sh
```

**You should see** `check-conditional-writes.sh: ok: the second create was refused (412 PreconditionFailed): concurrent commits are safe`.
Section 8.2 explains why this matters. Any other answer: stop and read section 15.

### Step 5: storage tiers by business date

```bash
deploy/aws/delta-lake/setup-lifecycle.sh --print | head -20     # the rules, no AWS call
deploy/aws/delta-lake/setup-lifecycle.sh
```

**You should see** `setup-lifecycle.sh: 129 rules for acme-drishti-prod-lake` (three tables from 2019: one rule per table per
month still moving, one per table per year already in Glacier IR, three base rules). Section 9.4 explains the rules.

### Step 6: EFS access points, one per node per path

```bash
deploy/aws/ecs/setup-efs.sh --dry-run
deploy/aws/ecs/setup-efs.sh
```

**You should see** one access point id per line (`fsap-0a1b…`) and `setup-efs.sh: 14 access points on fs-…` for three nodes:
`data`, `config` and `sutras` for each node and for `consoles`, and `data` for `loader` and `maintenance`. Each roots the
node's identical path, `/drishti/<env>/<node>/opt/drishti/<path>`, and runs as uid/gid 10001. Running it again returns the same
ids (the access point's name is its client token).

### Step 7: Cloud Map for Drishti\*net seeds

```bash
deploy/aws/ecs/setup-cloud-map.sh        # once for the namespace (it takes a minute) …
deploy/aws/ecs/setup-cloud-map.sh        # … and again for one service per node
```

**You should see** `namespace requested: it takes a minute; run this script again to create the services`, then one
`create-service` per node. ECS registers each node's task under `drishti-a.drishti.local` with the task's own address; the
servers advertise `http://<task IP>:18480`, never loopback.

### Step 8: the images

```bash
./mvnw -q package -DskipTests
docker build -f deploy/docker/ecs/server.Dockerfile  -t "$ECR_REGISTRY/drishti-server-ecs:2.9.0" .
docker build -f deploy/docker/ecs/console.Dockerfile -t "$ECR_REGISTRY/drishti-console-ecs:2.9.0" .
docker build -f deploy/docker/ecs/loader.Dockerfile  -t "$ECR_REGISTRY/drishti-loader-ecs:2.9.0" .   # carries deploy/aws/env
docker push …                                         # each of the three, after aws ecr get-login-password
```

The loader image copies `deploy/aws/` with the `env` you filled in (it holds no secret), so its tasks read the same settings
(`DRISHTI_AWS_ENV=/opt/drishti/app/deploy/aws/env`). Rebuild it when the settings change.

### Step 9: task definitions, services and schedules

```bash
deploy/aws/ecs/register.sh --dry-run
deploy/aws/ecs/register.sh                          # --nodes --lake-jobs
deploy/aws/ecs/register.sh --consoles 4             # optional: a console service of its own (SUPER_SCALE.md section 16)
```

**You should see** a task definition per node (`drishti-prod-drishti-a`, …), a service per node, the `loader`, `maintenance`
and `report` task definitions and three schedules (`drishti-prod-load-day`, `-maintain`, `-report`), then
`register.sh: done`. The node task sets `DRISHTI_DELTA_ROOT=s3://<lake bucket>/lake`, so every shipped Delta connector
(`root: ${DRISHTI_DELTA_ROOT:…}`) reads the lake in S3 with no edit.

### Step 10: the first load

```bash
deploy/aws/delta-lake/load-day.sh --dry-run --date 2026-10-20 --tables trading/trade_endur
deploy/aws/delta-lake/load-day.sh --date 2026-10-20 --tables trading/trade_endur
```

**You should see** the five steps of section 8.1, then
`lake publish: ok exit=0 published <n> file(s), <bytes> bytes, dates 2026-10-20 to s3://…/lake/trading/trade_endur version=0 (new table)`
and `load-day.sh: business date 2026-10-20: exit 0`. On ECS the same runs as the `loader` task
(`aws ecs run-task --task-definition drishti-prod-loader --overrides '{"containerOverrides":[{"name":"loader","command":["deploy/aws/delta-lake/load-day.sh","--date","2026-10-20"]}]}' …`).

In the console: `TRD <id>` on 2026-10-20 opens the trade; **Admin → Health** shows `trade-endur` `UP (engine: native)`.

### Step 11: alarms and the report

```bash
deploy/aws/delta-lake/setup-alarms.sh
deploy/aws/delta-lake/report.sh
```

**You should see** `setup-alarms.sh: 17 alarms put` (four per table, two for the bucket and EFS, one per node), and the
report of section 11.3.

## 5. The layout: buckets, prefixes, tables and partitions

### 5.1 Buckets

| Bucket | Holds | Settings (setup-buckets.sh) |
|---|---|---|
| `<org>-drishti-<env>-lake` | every Delta table: data files and `_delta_log` | versioning **on**; SSE-KMS with S3 Bucket Keys (one KMS call per object set, not per object); Block Public Access; objects owned by the bucket owner; TLS only; data reachable only through the VPC endpoint (and the account's `drishti-<env>-admin*` roles); access logs; request metrics on `lake/`; noncurrent versions expire after 14 days; incomplete multipart uploads abort after 2 days |
| `<org>-drishti-<env>-landing` | the sources' dumps | the same protections; dumps expire after 30 days |
| `<org>-drishti-<env>-logs` | S3 access logs | SSE-S3; expire after 400 days |

**Versioning on, with a short noncurrent window.** Delta keeps its own history (time travel back to the vacuum window), so
versions are not for history. They are a safety net: a file deleted by mistake (a wrong vacuum, a script run against the
wrong prefix) stays recoverable for `NONCURRENT_KEEP_DAYS` (14). The lifecycle expires noncurrent versions after that, so
storage does not double. ([AWS_DEPLOYMENT.md section 8.1](AWS_DEPLOYMENT.md#81-buckets) is updated to match.)

### 5.2 Prefixes and tables

```
s3://acme-drishti-prod-lake/
└── lake/                                         LAKE_PREFIX: DRISHTI_DELTA_ROOT = s3://acme-drishti-prod-lake/lake
    ├── trading/                                  the domain (the connector's domain)
    │   ├── trade_endur/                          one table per source system and kind (table.trade of connector trade-endur)
    │   │   ├── _delta_log/                       commits, a checkpoint every 10 commits, _last_checkpoint   (S3 Standard, always)
    │   │   ├── business_date=2026-10-19/
    │   │   └── business_date=2026-10-20/
    │   │       ├── PRODUCT_TYPE=FX_FORWARD/part-00000-<uuid>.c000.snappy.parquet
    │   │       └── PRODUCT_TYPE=IRS_FIXFLOAT/part-00000-<uuid>.c000.snappy.parquet …
    │   ├── trade_calypso/ …
    │   └── trade_murex/ …
    ├── counterparty/ …                           reference data: small tables, often mode: effective
    └── market/ …
```

| Choice | Why |
|---|---|
| **One table per source system and kind** (`trade_endur`, `trade_calypso`, `trade_murex` as a union, `union: true`) | each source loads, restates and fails on its own; each table's log stays a third the size; the three connectors of the trading pack already read them so |
| **The domain as the first folder** | matches the connector's `domain`; IAM and lifecycle rules can name a domain |
| **Tables by year** (`trade_endur/2026/`) | **planned (SUPER_SCALE step S2)**: until then one table holds every year, and its checkpoint grows with its live files (section 11.4) |

### 5.3 Partition keys

| Key | Why |
|---|---|
| `business_date` (always first) | every interactive read names a date, so a read opens one day's folder only; a load and a restatement replace one day; retention removes whole days; the lifecycle tiers by the folder's date |
| `PRODUCT_TYPE` for trades (a second key, `--partition-by`) | spreads a day over several S3 prefixes (request rate), keeps each partition small enough for `lake build` to sort it in one batch (section 7.3), and lets a source restate one product type (`--mode overwrite-partitions`) |
| nothing finer | each partition column multiplies the folders and small files of a day; `lake build` allows four, two is the right number for trades and one for everything else |

**Request-rate-friendly prefixes.** S3 scales each prefix to at least 3,500 writes and 5,500 reads a second and splits busy
prefixes by itself. A day's reads are spread over its product-type folders, and over three tables for trades. Drishti's
writers name the folders (Delta paths cannot be hashed), so the spread comes from the partition keys. A `503 Slow Down` is
retried by the native engine's AWS client with back-off and counted in Health; the alarm `drishti-<env>-lake-s3-5xx` fires on
a sustained rate.

## 6. Keys: the id, promoted columns and statistics

Every row is one document ([DELTA_CONNECTOR.md section 3](../connectors/DELTA_CONNECTOR.md#3-what-is-on-disk)):

| Column | Type | Content |
|---|---|---|
| `id` | string | the entity's id (`--key`, or the pack's `ingest.<kind>.key`: `TRADE_ID`) |
| `doc` | string | the whole JSON document as the source sent it |
| `business_date` | date | partition |
| partition columns | string | `PRODUCT_TYPE` |
| promoted columns | string or double | copies of the fields searches, lists, links and BI read: the pack's `layout.<kind>.columns`, plus `ingest.<kind>.flatten` (`NOTIONAL`, `INDEX_NAME`) |

| Need | What it reads | So the table needs |
|---|---|---|
| **One entity** (`TRD MX-20000017`) | the day's id map (the `id` column of that day's files), then one row group of one file | rows sorted by `id` per file; small row groups (section 7) |
| **History** (`TRD MX-20000017 30`) | the `business_date=` partitions of the snapshot, then one single read per day, eight at once ([HISTORY_QUERIES.md](HISTORY_QUERIES.md)) | `business_date` as the partition; nothing else |
| **Type-ahead** | the newest day's ids (and `index.days` past days), from the id map, into the in-memory index | `id`; nothing else |
| **Search, pick lists, derived kinds, impact, links** | the day's column set: `id` and every promoted column, never `doc` | every field a search filters, sorts or shows **promoted**; a search on a field that is not promoted reads documents up to `drishti.search.max-scan` and says `partial` |
| **File skipping** | per-file min and max in the log, per-row-group min and max in the footer | `delta.dataSkippingStatsColumns` = `id`, `business_date` and the promoted columns, never `doc` (it would put whole documents in the log). `lake build` sets it; `lake publish` carries it to the S3 table |

Choosing what to promote is a pack decision ([DELTA_CONNECTOR.md section 4](../connectors/DELTA_CONNECTOR.md#4-declaring-the-layout-in-a-pack)).
Adding a promoted column later changes the table's columns: `lake publish` refuses a day whose columns differ from the
table's (`the columns differ from the target's (new [...], missing [...], other type [...])`); see runbook 13.4.

## 7. Files: sizes, row groups and sorting

### 7.1 The numbers

| Setting | Value | Set by | Why |
|---|---|---|---|
| Rows per file | `layout.<kind>.file-rows` (250,000) | the pack | few files per day, each large enough for S3 (hundreds of MB) |
| Row group | **1 MB** (`LAKE_ROW_GROUP_KB=1024` → `DRISHTI_LAKE_ROW_GROUP_KB`; the writer's default is 256 KB) | `deploy/aws/env` | a single read fetches and decodes one row group: on S3 each GET costs 10–30 ms to the first byte, so 1 MB balances bytes moved against GETs; the page index narrows it further (`DRISHTI_LAKE_PAGE_KB`, 64) |
| Compression | Snappy (Drishti's Java writer and delta-rs) | the writer | the native engine also reads ZSTD, GZIP and LZ4_RAW |
| Statistics | `id`, `business_date`, promoted columns | `lake build` | file and row-group skipping without documents in the log |
| Checkpoint | every 10 commits (`delta.checkpointInterval`, set by `lake publish` on a new table) and after every maintenance run | `lake publish`, `lake maintain` | a reader replays the last checkpoint plus at most a few commits |
| Log retention | 30 days (`delta.logRetentionDuration`) | `lake publish` | commit files older than that (and before a checkpoint) are removed by `lake maintain` |
| Removed files kept | 7 days (`delta.deletedFileRetentionDuration`; `LAKE_VACUUM_HOURS` 168) | `lake publish`, `maintain.sh` | a reader in flight and *known at* reach back a week |

### 7.2 Sorted by id

A file holds a contiguous id range and each row group a smaller one, so a read of one id skips every other row group by its
statistics. `lake build` sorts **each partition (a date and product type) that fits in one batch** (`--batch-rows`,
`LAKE_BATCH_ROWS`, 250,000); the Java writer writes a larger partition in the dumps' order. `load-day.sh` prints a warning
for each such partition. Three remedies, in order of preference:

1. **Ask the source to export sorted by id.** The dump's order is then the file's order: sorted at any size, no memory cost.
2. **Partition finer** (`PRODUCT_TYPE`, or a book or desk column) so each partition fits a batch.
3. **Raise `LAKE_BATCH_ROWS`** with the loader's memory (about rows × average document size × 3: 250,000 trades of 10 KB need
   about 8 GB; the loader task has 30 GB).

A Delta table written elsewhere (Spark, Databricks) sorts with `repartitionByRange(…, "id").sortWithinPartitions("id")`
([DELTA_CONNECTOR.md section 5](../connectors/DELTA_CONNECTOR.md#5-writing-the-lake)). Range maps that use the log's per-file
id ranges instead of id maps, and Bloom filters on `id`, are **planned (SUPER_SCALE step S1)**.

### 7.3 One commit per day per table

`lake build` writes the day into a **fresh scratch table** on EFS, so every file there is live. `aws s3 sync` copies the data
files (new UUID names: nothing in S3 is overwritten), and `lake publish` commits them in **one** commit that removes the
day's previous files. A reader sees the old day or the new day, never a mixture, and the previous version stays readable
(time travel, *known at*) until vacuum.

## 8. Loading: the daily load, backfill and restatement

### 8.1 The daily load (`load-day.sh`)

```
 EventBridge Scheduler (LOAD_SCHEDULE, LOAD_SCHEDULE_ZONE)
   └─► ECS RunTask drishti-<env>-loader ─► loader-entrypoint.sh (EFS mounted? writable?) ─► load-day.sh
          for each table of LAKE_TABLES_FILE:
            flock  /opt/drishti/data/lake-locks/<domain>--<table>.lock     (another load, backfill or maintenance: exit 3)
            aws s3 sync   s3://landing/<date>/<source>/  →  scratch/in/<table>/
            drishti.py lake build   --from scratch/in/<table> -r --kind trade --pack config/packs/trading --connector trade-endur
                                    --lake scratch/lake --domain trading --table trade_endur --mode overwrite-dates
                                    --batch-rows 250000 --as-of <date> --partition-by PRODUCT_TYPE --no-stamp --summary …
            aws s3 sync   scratch/lake/trading/trade_endur/  →  s3://lake/lake/trading/trade_endur/   --exclude "_delta_log/*"
            drishti.py lake publish --from scratch/lake/trading/trade_endur --to s3://…/lake/trading/trade_endur
                                    --dates <date> --mode overwrite-dates --storage-option AWS_REGION=<region> [--signal …]
            CloudWatch Drishti/Lake: LoadSucceeded, PublishedBusinessDate, LoadSeconds
          scratch removed (always)
```

The date defaults to today in `LOAD_SCHEDULE_ZONE`, the zone the schedule runs in (an evening load in New York is already the
next day in UTC). Exit codes: 0 every table published, 2 published with rejected documents (the summary says which), 1 a
table failed (its S3 table is unchanged), 3 a table was locked.

**The data-load signal.** With `DRISHTI_SIGNAL_URL` (a node's Cloud Map name) and `DRISHTI_TOKEN_SECRET_ID` (an API token
with `loads:write` in Secrets Manager, read into a 0600 file for the run), `lake publish` sends
`POST /api/v1/packs/{pack}/loads` per date: the server refreshes, verifies and alerts as for any load
([DATA_LOADS.md](../guides/DATA_LOADS.md)). Without it, servers see the new table version at their next refresh
(`refresh-seconds`, 10).

### 8.2 Why concurrent commits are safe

A Delta commit is the creation of `_delta_log/<version>.json`; two writers must never both create the same version. What the
code does, read from it:

| Writer | How it commits on S3 |
|---|---|
| `lake publish` and `lake maintain` (the Python `deltalake` package, 1.6.6 in the venv and the loader image) | a **conditional PUT** of the commit file (put-if-absent, `If-None-Match: *`): S3 refuses the second writer with 412, which re-reads the log and retries (`--commit-retries`, 20). deltalake 1.x no longer has the DynamoDB lock (its message: *delta-rs no longer supports S3DynamoDBLogStores*), so there is **no lock table**. `AWS_S3_ALLOW_UNSAFE_RENAME=true` is refused by `lake publish` |
| Drishti's Java writer (`lake build`) | local disk and EFS only (a hard link of a finished commit file); never S3 |
| Drishti's native Iceberg writer (`drishti-lakeio`) | the same conditional PUT, with a start-up probe |

`check-conditional-writes.sh` is that probe for the lake bucket. On top of it, the scripts take a lock per table on EFS
(`flock`, NFSv4 byte-range locks, released if a task dies), so a load, a backfill and maintenance of one table never overlap
in the first place. Loads of different tables run side by side.

### 8.3 Backfill (`backfill.sh`)

```bash
deploy/aws/delta-lake/backfill.sh --from 2026-01-02 --to 2026-10-16 --skip-missing           # weekdays; dates without dumps skipped
deploy/aws/delta-lake/backfill.sh --from 2025-01-02 --to 2025-12-31 --from-dir /opt/drishti/data/history   # an export on EFS
```

Oldest date first, one `load-day.sh` per date. It stops at the first date that fails and prints the command that resumes from
there; re-running a date replaces it, so a backfill can always be run again. For a large backfill, run several loader tasks
side by side **on different tables** (the per-table lock keeps two on one table apart), each with `--tables`.

### 8.4 Restatement

| A source re-sent | Run |
|---|---|
| some whole days | `backfill.sh --dates 2026-10-14,2026-10-15` |
| one product type of a day | `load-day.sh --date 2026-10-15 --tables trading/trade_endur --mode overwrite-partitions` (only the partitions in the dumps are replaced) |

Readers switch to the restated day at the commit; *known at* before the commit still shows the day as it was, for
`LAKE_VACUUM_HOURS`. Data files of an old day that is restated move to its storage tier at the next lifecycle run (the rules
are by date, not by object age), so a restatement of 2024 lands in Glacier IR, not in Standard.

## 9. Maintenance: checkpoints, retention, vacuum and storage tiers

### 9.1 What `maintain.sh` runs

Nightly (`MAINTAIN_SCHEDULE`, Tuesday to Saturday 02:30), as the `maintenance` task, per table under the table's lock:

```bash
drishti.py lake maintain --config <lake-maintenance.s3.yaml rendered> --once --force-foreign --tables trading/trade_endur
```

then `setup-lifecycle.sh` (the tier rules of the coming months). The settings ([`deploy/aws/delta-lake/lake-maintenance.s3.yaml`](../../deploy/aws/delta-lake/lake-maintenance.s3.yaml)):

| Key | Value | What `lake maintain` does ([LAKE_MAINTENANCE.md](../admin/LAKE_MAINTENANCE.md)) |
|---|---|---|
| `keep-business-days` | `LAKE_KEEP_BUSINESS_DAYS` (1800, about seven years) | removes the business dates older than that, counted back from today: a delete by partition, one commit; refuses to remove more than `max-drop-share` (0.5) of a table |
| `checkpoint` | true | writes a checkpoint and removes commit files past `delta.logRetentionDuration` |
| `vacuum-hours` | `LAKE_VACUUM_HOURS` (168) | deletes files no version uses, older than a week; S3 keeps them 14 more days as noncurrent versions |
| `compact` | **false** | see 9.3 |

`--force-foreign`: the ownership rule of `lake maintain` maintains only tables `lake build` built in that location, and treats
every `s3://` table as foreign. The tables in S3 are Drishti's (`lake publish` wrote them from `lake build`'s output), so the
script overrides the rule; `lake maintain` prints its warning and its history marks the runs *forced*.

### 9.2 Retention and the tiers together

```
 business date age:   0 ───────── 90 days ─────────────── 2 years ────────────────── LAKE_KEEP_BUSINESS_DAYS ──► removed
 storage class:       Standard    │ Standard-IA             │ Glacier Instant Retrieval │ by lake maintain (a Delta delete),
 (setup-lifecycle.sh)             │ (TIER_IA_AFTER_DAYS)    │ (TIER_GLACIER_IR_AFTER_DAYS)│ then vacuum, then noncurrent expiry
 read latency:        ms          │ ms                      │ ms                         │
```

Never Glacier Flexible Retrieval or Deep Archive: their objects need a restore of hours before a read, and Drishti reads any
day in milliseconds. Never an S3 expiry of current lake objects: removing a file a table version still names breaks the table
([DELTA_CONNECTOR.md section 10](../connectors/DELTA_CONNECTOR.md#10-keeping-the-lake-in-shape)); only `lake maintain`
removes table files.

### 9.3 Compaction

Each day arrives laid out (files of `file-rows`, sorted, one commit), so the S3 tables need no compaction. A plain Delta
compaction would also merge files out of id order: `lake maintain` re-sorts (`relayout`) only tables whose folder is the
kind's name (`trading/trade`); for the trading pack's per-source tables (`trade_endur`) it finds no layout and would run a
plain compaction. So `compact` is off for S3. Restate a day that needs re-laying (runbook 13.4) instead.

### 9.4 The lifecycle rules (`setup-lifecycle.sh`)

S3 lifecycle rules match prefixes and move objects on a **date** or at an **age**. Age is the time since the object was
written, which is wrong for a restated or backfilled day. So the script writes, per table, one rule per business month with
**dates**:

```json
{"ID": "tier-trading-trade_endur-2026-03", "Status": "Enabled",
 "Filter": {"And": {"Prefix": "lake/trading/trade_endur/business_date=2026-03", "ObjectSizeGreaterThan": 131072}},
 "Transitions": [{"Date": "2026-06-29T00:00:00Z", "StorageClass": "STANDARD_IA"},
                 {"Date": "2028-03-30T00:00:00Z", "StorageClass": "GLACIER_IR"}]}
```

A month's files move when the month's last day is 90 days old, and to Glacier IR at two years; a whole year already past two
years is one rule. Rules exist three months ahead, and `maintain.sh` rewrites them nightly. `_delta_log/` never matches, so
commits and checkpoints stay in Standard. Objects under 128 KB stay where they are (moving them costs more than it saves).
S3 allows 1,000 rules; the script refuses more (about 30 tables over seven years).

Costs to know: Standard-IA keeps an object at least 30 days, Glacier IR 90 days; a restatement of a tiered day pays the rest
of that minimum on the files it replaces. Reads from Standard-IA cost about USD 0.01 a GB and from Glacier IR about USD 0.03.

## 10. Caches: EFS or local NVMe

Drishti keeps small, bounded structures per table and day ([DELTA_CONNECTOR.md section 9](../connectors/DELTA_CONNECTOR.md#9-memory)):

| Cache | Where | Bounded by | After a restart |
|---|---|---|---|
| file list (snapshot) of each table | heap | the table's live files | re-read from the checkpoint |
| Parquet footers | heap (`-Ddrishti.parquet.footer-cache-groups`, 50,000 row groups) | row groups | re-read on first use (one ranged GET) |
| id maps of days | heap (`id-map-mb`, 20 % of heap, shared) and disk (`id-map-disk.dir`) | memory; one file per table version and date | **memory-mapped from disk: no scan** |
| column sets of days | heap (`columns-cache-mb`) | memory | re-read (the newest day in the background) |
| documents | heap (`doc-cache-mb`) and RocksDB on disk (`doc-disk-cache.dir`, `max-gb` 1, TTL 12 h, cleared daily 02:00) | size, age | **warm from disk** |
| type-ahead | heap (newest day; `index.days` more, `index.storage: mmap`) | days | rebuilt from id maps |

The disk caches default to `<data dir>/cache/<connector>/…`, which on ECS is `/opt/drishti/data/cache/…` on **EFS**.

| | EFS (default) | Local NVMe (instance store) |
|---|---|---|
| Latency | about 1 ms an operation (NFS); the page cache serves repeated reads of memory-mapped id maps | tens of microseconds |
| Throughput mode | **Elastic**: pays per GB read and written, follows the load; Provisioned only for a steady high rate | the disk's own |
| After a task moves host or zone | **warm**: same folders | **empty**: rebuilt as users read (correct, slower first reads) |
| Launch type | Fargate or EC2 | **EC2 only** (instances with instance store: `m6id`, `r6id`, `i4i`) |
| Cost | EFS Standard about USD 0.30 a GB-month plus Elastic throughput charges | included in the instance |
| The identical-path rule | satisfied | not: the cache path is on the host. The caches are rebuildable from the lake, so they may break the rule only with the product owner's decision ([AWS_DEPLOYMENT.md section 21](AWS_DEPLOYMENT.md#21-open-questions)) |

To use NVMe: `register.sh --nodes --cache nvme` (EC2 launch type; the host's `/mnt/nvme/drishti/<node>` mounted at
`/opt/drishti/nvme`), then point the connectors' disk caches there:

```yaml
# /opt/drishti/config/connectors/trade-endur.yaml (on EFS), settings:
  id-map-disk.dir: /opt/drishti/nvme/cache/trade-endur/idmaps
  doc-disk-cache.dir: /opt/drishti/nvme/cache/trade-endur/docs
  doc-disk-cache.max-gb: "50"
```

Recommendation: start on EFS (warm after any restart, no host affinity); move the document cache to NVMe if Admin → Caches
shows cold reads dominate and EFS latency shows in `p99Ms` (step A4 of AWS_DEPLOYMENT.md measures it).

## 11. Staying fast past 200 TB

### 11.1 What never scans the whole lake

| Request | Reads | Grows with history? |
|---|---|---|
| Open one entity on a date | the table's snapshot (in memory, refreshed from the last checkpoint every 10 s), the day's id map (memory or disk), **one row group of one file** (one ranged GET, about 1 MB) | no |
| History of one entity over N days | the snapshot's `business_date=` partitions, then one single read per day held, eight at once | with N, not with the lake |
| Type-ahead | the newest day's ids in memory | no |
| Search, pick list, derived kind, impact on a date | that day's column set (`id` and promoted columns, never documents), files and row groups skipped by statistics | no |
| Load, restatement | one day's files and one commit | no |
| Retention | a delete by partition (the log only), one commit | no |
| Vacuum (weekly is enough) | a listing of the table's prefix | with the table's file count: about 1,000 objects per LIST call |
| Questions over all of history | not interactive: Athena, Trino or Spark on the same tables | — |

### 11.2 Spreading the work over Drishti\*net

What is **built** today ([DRISHTI_NET.md section 10](DRISHTI_NET.md#10-shared-and-separate-lakes)):

- Nodes whose connectors read **different tables** of a kind are **shards**: a search asks every one, a read goes to the
  node that holds the table. Give each node (or each pair) its own tables: `drishti-a` and `drishti-b` read `trade_endur`,
  `drishti-c` and `drishti-d` read `trade_calypso` and `trade_murex`. Each node then caches only its tables.
- Nodes that read **the same table** are **replicas**: a search asks one; among replicas a forwarded read goes to the same
  node for the same entity (a stable hash of its id), so that node's caches stay warm for it.
- Every node can read every table from S3, so a failed node costs speed, never answers.

**Planned (SUPER_SCALE steps S5 and S6):** ownership of (table, day, group) slices on the ring, so N servers on one table hold
N times one server's working set, and searches split over the owners.

### 11.3 The numbers to watch

`report.sh` (scheduled daily, `REPORT_SCHEDULE`) prints them and sends them to CloudWatch. Its form, with illustrative
numbers for a lake of about 200 TB:

```
bucket acme-drishti-prod-lake (CloudWatch daily storage metrics):
  StandardStorage                         10412.3 GB
  StandardIAStorage                       46120.8 GB
  GlacierInstantRetrievalStorage         143998.1 GB
  objects                          672310

table                        version  checkpoint  behind   dates       newest   files GB(newest)    <32MB
trading/trade_endur             4512        4510       2    1764   2026-10-20     128      41.37        0
```

| Number | Healthy | If not |
|---|---|---|
| `behind` (commits since the checkpoint) | under 10 | a checkpoint failed: run `maintain.sh --tables …`; alarm `log-long` above 50 |
| files of the newest day, and `<32MB` | about rows ÷ `file-rows` per partition; small files 0 | many small files: the source sends many tiny dumps, or partitions are too fine (section 5.3) |
| live files per table | under about 100,000 | the checkpoint, and every refresh of the snapshot, grow with it: split the table (by source, or by year when step S2 lands) |
| S3 5xx | rare | `503 Slow Down`: reads concentrated on one prefix; spread with partitions; the alarm `lake-s3-5xx` |
| Admin → Health per connector: `p50Ms`, `p99Ms`, cold reads | a single read p95 under 300 ms on the newest days, under 1 s on any day | footers and id maps not warm (heap too small: Admin → Caches), or EFS latency (section 10) |
| Admin → Caches: the one memory budget | under its cap | raise the heap (8 GB per million entities a day), or shard tables over more nodes (11.2) |
| `LoadSeconds` | well inside the window | the dumps are large: more tables loaded in parallel (one loader task per table), or larger loader tasks |

### 11.4 The CloudWatch alarms (`setup-alarms.sh`)

| Alarm | Fires when |
|---|---|
| `drishti-<env>-<table>-load-failed` | a load of the table failed (nothing of it was published) |
| `drishti-<env>-<table>-not-loaded` | no successful load for three days running (a weekend is two) |
| `drishti-<env>-<table>-load-slow` | a load took longer than `ALARM_PUBLISH_DEADLINE_HOURS` |
| `drishti-<env>-<table>-log-long` | readers replay more than `ALARM_COMMITS_SINCE_CHECKPOINT` commits |
| `drishti-<env>-lake-s3-5xx` | more than `ALARM_S3_5XX_PER_5MIN` server errors in 5 minutes, twice in 15 |
| `drishti-<env>-efs-io-limit` | EFS `PercentIOLimit` above 80 % |
| `drishti-<env>-<node>-down` | a node's service has no running task for 5 minutes |

All notify `ALARM_SNS_TOPIC_ARN`; the AWS_DEPLOYMENT.md alarms (heap, Drishti\*net member dead, backup failed) come with
the platform's Prometheus or CloudWatch agent.

## 12. Sizing and cost

### 12.1 What 200 TB is

Estimates (to be replaced by the measurements of SUPER_SCALE step S8), for 200 TB of Parquet kept seven years:

| | Value |
|---|---|
| Per business day (252 a year) | about 113 GB of Parquet; about 100 million trade documents at about 1.1 KB each compressed (Snappy) |
| Files per day | about 350 (330 MB each, 250,000 rows) over all tables |
| Live files after seven years | about 620,000 over all tables; with three trade tables, about 200,000 each: above the 100,000 of 11.3, so tables by year (S2) or more tables matter before the seventh year |
| Commits | one per table per day, plus maintenance: about 1,500 a year per table |

### 12.2 The deployment

| Part | Size | Count |
|---|---|---|
| Server tasks | 4 vCPU and 12 GB for the server (8 GB heap) in a 6 vCPU, 16 GB task with its console; larger nodes (16 vCPU, 64 GB) at SUPER_SCALE volumes | 3 to 6, sharded by table (11.2) |
| Loader task | 4 vCPU, 30 GB, 21 GB ephemeral | one per table at load time, minutes to an hour a day |
| Maintenance and report tasks | the same image and size | nightly, minutes |
| EFS | each node's state and caches: 50–300 GB; the loader's scratch: one day of one table at a time (about the day's dumps plus its Parquet) | one file system |

### 12.3 Monthly cost (list prices, one Region, estimates)

| Item | Basis | About |
|---|---|---|
| S3 storage, 200 TB tiered | 10 TB Standard (USD 23/TB), 46 TB Standard-IA (12.5), 144 TB Glacier IR (4) | USD 1,400 |
| S3 storage if all were Standard | 200 TB × 23 | USD 4,600 |
| S3 requests | 250 reads a second, two GETs each, USD 0.40 per million; loads and lists | USD 500–700 |
| Retrievals from IA and Glacier IR | month-ends, audits, history | tens to a few hundred USD |
| Versioning overhead | 14 days of replaced and vacuumed files | a few percent of storage |
| Server nodes | 3 tasks of 6 vCPU, 16 GB on Fargate, always on | USD 650–750 |
| Loader, maintenance | about an hour a day | USD 50–150 |
| EFS | 1 TB Standard, Elastic throughput | USD 300 plus throughput charges |
| CloudWatch, Secrets Manager, Cloud Map, endpoints, load balancer | | USD 200–400 |

For comparison, the same 200 TB on EFS: USD 60,000 a month in Standard, about USD 3,200 in Infrequent Access plus USD 0.03
per GB read (section 2).

## 13. Runbooks

### 13.1 The daily load

| Check | How |
|---|---|
| It ran | CloudWatch Logs `/drishti/<env>`, stream prefix `loader`; `LoadSucceeded` 1 per table |
| It published | `report.sh --tables <table>`: `newest` is today; in the console, `TRD <id>` on today's date |
| Exit 2 (rejects) | the build summary in the log names the reasons; fix the source and restate the day (13.3) |
| Exit 1 | the log's line `lake build failed` or `lake publish failed` says why; the S3 table is unchanged; fix and run `load-day.sh --date <d> --tables <t>` |
| Exit 3 | maintenance or a backfill held the table's lock; the next run loads it, or run it by hand after |

### 13.2 A backfill

1. `backfill.sh --dry-run --from … --to … --tables <t>`: check the dates and commands.
2. Run it as a loader task per table (`aws ecs run-task … --overrides` with the command `deploy/aws/delta-lake/backfill.sh …`).
3. It stops at a failing date with the resume command; fix and resume.
4. `setup-lifecycle.sh` (or the next `maintain.sh`) moves the backfilled years to their tiers.

### 13.3 A restatement

1. The source re-sends the dumps under the landing prefix of the day.
2. `backfill.sh --dates <d>` (whole day) or `load-day.sh --date <d> --tables <t> --mode overwrite-partitions`.
3. Readers switch at the commit; the data-load signal (if set) refreshes servers at once.

### 13.4 A slow table

| Symptom | Cause | Fix |
|---|---|---|
| first read of a day slow, then fast | id map not yet on disk; footers cold | normal; `warm-dates` (3) warms the newest days at start |
| every read of a day slow | the day is not sorted by id (load warning *written in the dumps' order*), or row groups are large | restate the day after fixing the order (7.2) |
| searches slow or `partial` | a field the search uses is not promoted | promote it in the pack; the table's columns change, so publish refuses the next day: write the new layout into a new table folder (`trade_endur_v2`) with `backfill.sh` (its tables file line pointing there), then switch the connector's `table.trade` |
| the snapshot refresh slow (Health) | many live files or a long log | `report.sh`: `behind` and file counts; run `maintain.sh`; split the table |
| `503 Slow Down` | one prefix overloaded | partition by `PRODUCT_TYPE` (or finer for that table); S3 scales a prefix within minutes |

### 13.5 Disaster recovery

| | How |
|---|---|
| A table damaged (a wrong commit) | Delta time travel: the previous version is readable until vacuum; restate the affected days from the landing bucket (30 days) or from versions |
| Files deleted by mistake | S3 versioning: restore the noncurrent versions within `NONCURRENT_KEEP_DAYS` (`aws s3api list-object-versions`, `copy-object` of the version) |
| A Region lost | S3 Cross-Region Replication of the lake bucket, with Replication Time Control (most objects within 15 minutes), EFS replication and ECR replication ([AWS_DEPLOYMENT.md section 16](AWS_DEPLOYMENT.md#16-disaster-recovery)). Replication copies objects one by one, so a commit can arrive before a data file it names: a read of that day in the recovery Region fails (Health `DEGRADED … a truncated or missing Parquet file`) until the file arrives, and the other days are unaffected. Fail over after the replication backlog (`ReplicationLatency`, `OperationsPendingReplication`) is empty |
| Node state lost | AWS Backup of EFS; caches rebuild from the lake by themselves |

## 14. The scripts

```
deploy/
├── docker/                       compose.yaml, compose.data.yaml, server.Dockerfile, console.Dockerfile (local and Docker hosts)
│   └── ecs/                      the ECS images: server.Dockerfile, console.Dockerfile, loader.Dockerfile and their entry points
├── aws/
│   ├── env.example               every setting; copy to deploy/aws/env (git-ignored)
│   ├── lib/common.sh             --help, --dry-run, --env, the printed AWS calls, templates
│   ├── delta-lake/
│   │   ├── setup-buckets.sh      step 2        ├── load-day.sh        8.1
│   │   ├── setup-iam.sh          step 3        ├── backfill.sh        8.3, 8.4
│   │   ├── check-conditional-writes.sh  step 4 ├── maintain.sh        9
│   │   ├── setup-lifecycle.sh    step 5, 9.4   ├── report.sh          11.3
│   │   ├── setup-alarms.sh       step 11       ├── lake-maintenance.s3.yaml
│   │   ├── tables.example        the tables    └── iam/*.json         the policies and trusts
│   └── ecs/
│       ├── setup-efs.sh          step 6        ├── register.sh        step 9
│       ├── setup-cloud-map.sh    step 7        └── templates/*.json   node, console, lake tasks; services; schedule target
├── grafana/                      the Grafana dashboard
└── lake-maintenance.yaml         lake maintain's example for a local lake
```

Every script: `bash` with `set -euo pipefail`, the AWS CLI v2 and nothing else (Python only through `tools/drishti.py`), `--help`,
`--dry-run` (each changing call printed as `+ aws …`, each read as `? aws …`, none made), settings from one file, safe to run
again. `tools/test_deploy_aws.py` runs every script with `--help` and `--dry-run` against a fake settings file with an `aws`
that fails if called, renders every template to JSON, and checks the lifecycle rules.

## 15. Limits and what comes next

- **Not yet run in AWS.** The first real run, in a development account, follows sections 4 and 13 and records what you
  should see here. AWS_DEPLOYMENT.md steps A1 (images built and the read-only-root test) and A4 (EFS measurements) go with it.
- **A bucket without conditional writes** (`check-conditional-writes.sh` exit 1: an S3-compatible store, a proxy that drops
  the header): only one writer per table at a time. The per-table locks of the scripts already give that, as long as nothing
  else writes the tables.
- **`lake build` sorts a partition only when it fits a batch** (7.2).
- **One table holds every year** until tables by year (S2); watch live files per table (11.3).
- **Maintenance cannot re-sort per-source tables** (9.3): `compact` stays off; restate instead.
- **Ownership of slices, range maps, Bloom filters, products, the loader fleet** are planned (section 3).
