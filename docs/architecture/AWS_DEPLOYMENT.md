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
# Drishti on AWS: ECS, EFS and S3

Status: **scripts in `deploy/`.** The images and entry points of sections 5 and 6 are files in
[`deploy/docker/ecs/`](../../deploy/docker/ecs); the EFS access points, Cloud Map, task and service definitions of
sections 4, 9, 12 and 13 are scripts and templates in [`deploy/aws/ecs/`](../../deploy/aws/ecs); the lake in S3 (buckets,
roles, loading, maintenance, tiers, alarms) is [AWS_DELTA_LAKE.md](AWS_DELTA_LAKE.md) and
[`deploy/aws/delta-lake/`](../../deploy/aws/delta-lake). Every script has `--help` and `--dry-run` and is tested that way;
none has yet run against a real AWS account (step A3 below). The server's two small changes of section 5.3 are not built.

Related: [SUPER_SCALE.md](SUPER_SCALE.md) (500 GB a day for seven years on this deployment),
[DRISHTI_NET.md](DRISHTI_NET.md) and [NET.md](../admin/NET.md) (several servers), [OPERATIONS.md](../admin/OPERATIONS.md)
(running one server), [OPERATIONALISING.md section 13](../guides/OPERATIONALISING.md#13-docker-and-kubernetes) (Docker
and Kubernetes today, `deploy/docker/server.Dockerfile` and `deploy/docker/console.Dockerfile`),
[AWS_DELTA_LAKE.md](AWS_DELTA_LAKE.md) (the lake in S3, past 200 TB: layout, loading, maintenance, tiers, alarms).

## Contents

1. [The ask and the rule](#1-the-ask-and-the-rule)
2. [The picture](#2-the-picture)
3. [The path map](#3-the-path-map)
4. [EFS](#4-efs)
5. [The server container](#5-the-server-container)
6. [The console container](#6-the-console-container)
7. [Starting a node](#7-starting-a-node)
8. [S3](#8-s3)
9. [Drishti*net on ECS](#9-drishtinet-on-ecs)
10. [Network](#10-network)
11. [Identity, roles and secrets](#11-identity-roles-and-secrets)
12. [Task definition](#12-task-definition)
13. [Service definition](#13-service-definition)
14. [Operations](#14-operations)
15. [Observability](#15-observability)
16. [Disaster recovery](#16-disaster-recovery)
17. [Performance: what EFS costs](#17-performance-what-efs-costs)
18. [Cost](#18-cost)
19. [Tests and acceptance](#19-tests-and-acceptance)
20. [Build plan](#20-build-plan)
21. [Open questions](#21-open-questions)

## 1. The ask and the rule

The product owner (2026-10-20): Drishti runs in AWS on **ECS**, with **S3** and **EFS**; the server and the console are
**separate containers**. The rule:

> **Whatever path a container uses must have a mapped path on EFS, identical, so that everything is durable.**

The design turns the rule into something that cannot be broken by accident:

| Mechanism | What it guarantees |
|---|---|
| Every path a container writes is an **EFS access point mounted at the same path** (section 3) | a task that stops, restarts, moves to another Availability Zone or is replaced by a new image comes back to exactly the files it left |
| The container's **root file system is read-only** (`readonlyRootFilesystem: true`) | a write anywhere else fails at once, naming the path, instead of being silently lost when the task stops |
| `/tmp`, `HOME`, the JVM's temp folder, crash files and heap dumps are **pointed at EFS** (section 5) | the usual "hidden" writers write durably too |
| The **entry point refuses to start** if a writable path is not mounted, not writable, or not on NFS (section 7) | a task definition with a missing or wrong mount never runs |
| The **image** holds only what never changes while it runs: the server jar, the console's code, the packs Drishti ships, the documents for the help centre | an image is a version, kept in Amazon ECR; it is not data |
| **S3 holds the lakes**, read directly by the connectors | the business data needs no file system at all |

## 2. The picture

```
                                   users (browsers)
                                          │ HTTPS 443
                                          ▼
        ┌──────────────────── Application Load Balancer (public or internal subnets) ────────────────────┐
        │  ACM certificate · listener 443 → target group "consoles" (port 17480, health /healthz)         │
        └───────────────┬───────────────────────────────┬───────────────────────────────┬─────────────────┘
                        │                               │                               │
   ┌─ ECS service drishti-a (1 task) ─┐  ┌─ ECS service drishti-b ─────────┐  ┌─ ECS service drishti-c ─────────┐
   │ task, awsvpc, 10.0.1.23          │  │ task, 10.0.2.41                 │  │ task, 10.0.3.17                 │
   │ ┌──────────┐    ┌─────────────┐  │  │ ┌──────────┐   ┌─────────────┐  │  │ ┌──────────┐   ┌─────────────┐  │
   │ │ console  │───►│   server    │  │  │ │ console  │──►│   server    │  │  │ │ console  │──►│   server    │  │
   │ │  :17480  │    │   :18480    │◄─┼──┼─┼──────────┼──►│   :18480    │◄─┼──┼─┼──────────┼──►│   :18480    │  │
   │ └────┬─────┘    └──────┬──────┘  │  │ └────┬─────┘   └──────┬──────┘  │  │ └────┬─────┘   └──────┬──────┘  │
   │      └──── same EFS paths ───┘   │  │      └── same EFS paths ──┘     │  │      └── same EFS paths ──┘     │
   └──────────────┬───────────────────┘  └──────────────┬──────────────────┘  └──────────────┬──────────────────┘
                  │        Drishti*net gossip and forwarding between servers, task addresses, port 18480       │
                  ▼                                     ▼                                    ▼
   ┌──────────────────────────────── Amazon EFS (Regional, encrypted, Elastic throughput) ───────────────────────┐
   │ /drishti/prod/drishti-a/opt/drishti/{data,config,sutras}   /drishti/prod/drishti-b/…   /drishti/prod/drishti-c/… │
   └──────────────────────────────────────────────────────────────────────────────────────────────────────────────┘
                  │  task role, gateway VPC endpoint
                  ▼
   ┌── Amazon S3 ── lakes (Delta Lake, Iceberg, files) ──┐   ┌── Secrets Manager ── token, session, cluster, admin ──┐
   └──────────────────────────────────────────────────────┘   └───────────────────────────────────────────────────────┘
   ┌── AWS Cloud Map ── drishti-a.drishti.local … ──┐   ┌── CloudWatch ── logs, metrics, alarms ──┐   ┌── AWS Backup ── EFS ──┐
```

**One ECS service per Drishti node.** Each service runs exactly one task. The task has **two containers, the server and
the console**, sharing the task's network address and mounting the same EFS folders at the same paths. A node has a
fixed name (`drishti-a`), its own EFS folders and its own place on the Drishti*net ring, so it must be a service of its
own: an ECS service with several tasks gives them no stable identity.

**Why the console sits beside its server** (not in a service of its own): the console reads the same packs, lakes
settings and folders as its server, through the same paths, exactly as on one machine. At the scale of
[SUPER_SCALE.md](SUPER_SCALE.md) consoles become a service of their own (section 21).

## 3. The path map

### 3.1 The rule, made concrete

Every writable path is the **same in the container and on EFS**. On EFS each node's paths sit under the node's prefix
`/drishti/<environment>/<node>`: several environments and nodes share one file system, and each node must have its own
files (a RocksDB store or a DuckDB file is opened by one process at a time). Below the prefix the path is identical:

```
  container of node drishti-a (prod)                EFS file system fs-…
  ──────────────────────────────────                ───────────────────────────────────────────────
  /opt/drishti/data          ◄── access point ──►   /drishti/prod/drishti-a/opt/drishti/data
  /opt/drishti/config        ◄── access point ──►   /drishti/prod/drishti-a/opt/drishti/config
  /opt/drishti/sutras        ◄── access point ──►   /drishti/prod/drishti-a/opt/drishti/sutras

  /opt/drishti/app.jar, /opt/drishti/shipped/packs, /opt/drishti/console, /opt/drishti/docs, /opt/drishti/bin
                             ── image (ECR), read-only; never written
```

### 3.2 Every folder, and who writes it

| Container path | Written by | Content |
|---|---|---|
| `/opt/drishti/data/identity` | server | users, roles, API tokens, tombstones (replicated between nodes by IdentitySync) |
| `/opt/drishti/data/governance` | server | certification, lineage, approvals |
| `/opt/drishti/data/bi-store` | server | BI columns and rollups (Parquet, DuckDB) |
| `/opt/drishti/data/reports` | server | report files, subscriptions' deliveries |
| `/opt/drishti/data/collab` | server | collaboration store and exports |
| `/opt/drishti/data/loads` | server | load history per pack, expectation overrides |
| `/opt/drishti/data/packs/installed` | server | packs deployed to this node |
| `/opt/drishti/data/uninstalled` | server | uninstalled artifacts (moved, never deleted) |
| `/opt/drishti/data/derived` | server | grouped pages (RocksDB sorted stores) |
| `/opt/drishti/data/cache` | server | document cache (RocksDB), warm column files, type-ahead segments |
| `/opt/drishti/data/designs` | server | Sutra designs |
| `/opt/drishti/data/dev-sutras` | server | bound development Sutras, one folder per user |
| `/opt/drishti/data/net` | server | Drishti*net state |
| `/opt/drishti/data/lakes` | console | lake maintenance settings and history |
| `/opt/drishti/data/console` | console | what the console keeps between runs (`net/servers.json`) |
| `/opt/drishti/data/tmp/server`, `/opt/drishti/data/tmp/console` | both | temp folders (the JVM's `java.io.tmpdir`, native libraries unpacked by DuckDB and RocksDB, Python's `TMPDIR`) |
| `/opt/drishti/data/home/server`, `/opt/drishti/data/home/console` | both | `HOME` (DuckDB extensions, font caches and any library that writes under home) |
| `/opt/drishti/data/logs` | server | log files, `hs_err_pid*.log`, heap dumps |
| `/opt/drishti/config/connectors` | server (Admin → Connectors) | connector files |
| `/opt/drishti/config/net` | server, entry point | `node.yaml`, `gossip.yaml`, `routing.yaml`, `peers/` |
| `/opt/drishti/config/ai`, `/opt/drishti/config/mcp` | both | AI providers, MCP servers |
| `/opt/drishti/config/bi` | server | Rupaka files: datasets, reports, boards, notebooks |
| `/opt/drishti/sutras` | server (going live) | site Sutras |

A folder the code adds later is under one of the three roots by construction (every Drishti path setting defaults to
`${drishti.data.dir}` or `./config`), so the map does not need to grow; the read-only-root test (section 19) catches
any exception.

### 3.3 The variables that pin every path

Set in both images, so nothing depends on a working directory:

| Variable | Value | Image |
|---|---|---|
| `DRISHTI_DATA_DIR` | `/opt/drishti/data` | both |
| `DRISHTI_CONNECTORS_DIR` | `/opt/drishti/config/connectors` | server |
| `DRISHTI_NET_DIR` | `/opt/drishti/config/net` | server |
| `DRISHTI_AI_DIR`, `DRISHTI_MCP_DIR` | `/opt/drishti/config/ai`, `/opt/drishti/config/mcp` | both |
| `DRISHTI_BI_DIR` | `/opt/drishti/config/bi` | server |
| `DRISHTI_SUTRAS` | `/opt/drishti/sutras` | server |
| `DRISHTI_PACKS_DIR` | `/opt/drishti/shipped/packs` (read-only, from the image) | both |
| `DRISHTI_CONSOLE_DATA_DIR` | `/opt/drishti/data/console` | console |
| `DRISHTI_CONSOLE_TMP_DIR` | `/opt/drishti/data/tmp/console` | console |
| `TMPDIR` | `/opt/drishti/data/tmp/server` or `/opt/drishti/data/tmp/console` | both |
| `HOME` | `/opt/drishti/data/home/server` or `/opt/drishti/data/home/console` | both |

**Shipped packs move out of `config`.** Today's images copy the packs to `/opt/drishti/config/packs`; an EFS mount on
`/opt/drishti/config` would hide them. The ECS images copy them to `/opt/drishti/shipped/packs` and point
`DRISHTI_PACKS_DIR` there. Installed packs (Admin → Packs, `pack deploy`) go to `data/packs/installed` on EFS and are
searched first, as today.

## 4. EFS

### 4.1 The file system

| Setting | Value | Why |
|---|---|---|
| Type | **Regional** (Multi-AZ) | a task restarted in another Availability Zone finds its files |
| Performance mode | General Purpose | lowest latency per operation |
| Throughput mode | **Elastic** | throughput follows the load (start-up warm-ups, BI builds) with no provisioning |
| Encryption at rest | on, with a customer-managed KMS key `alias/drishti-efs` | bank policy |
| Encryption in transit | TLS on every mount (`transitEncryption: ENABLED`) | |
| Lifecycle (Infrequent Access) | **off** | caches are read rarely but must be fast when they are |
| Mount targets | one per private subnet (one per Availability Zone), security group `drishti-efs` | section 10 |
| File system policy | deny any access without TLS; allow mounts only through the access points, only to the task role | section 11 |
| Backups | AWS Backup plan `drishti-efs-daily` | section 14.5 |
| Replication | EFS replication to the recovery Region | section 16 |

### 4.2 Access points

`deploy/aws/ecs/setup-efs.sh` creates them (and one `data` access point each for the lake's `loader` and
`maintenance` tasks).

One access point per node per path (three per node). Each roots its path and enforces the user the containers run as:

| Access point (name tag) | Root directory | POSIX user | Root directory creation |
|---|---|---|---|
| `drishti-prod-drishti-a-data` | `/drishti/prod/drishti-a/opt/drishti/data` | uid 10001, gid 10001 | owner 10001:10001, permissions 0750 |
| `drishti-prod-drishti-a-config` | `/drishti/prod/drishti-a/opt/drishti/config` | uid 10001, gid 10001 | owner 10001:10001, permissions 0750 |
| `drishti-prod-drishti-a-sutras` | `/drishti/prod/drishti-a/opt/drishti/sutras` | uid 10001, gid 10001 | owner 10001:10001, permissions 0750 |
| … the same three for `drishti-b`, `drishti-c`, … | | | |

Both images run as uid 10001 (`drishti`), so the server and the console of a node read and write the same files, and
the access point enforces that identity whatever the image says.

### 4.3 The tree on EFS

```
/drishti/
├── prod/
│   ├── drishti-a/opt/drishti/
│   │   ├── data/      identity/  governance/  bi-store/  reports/  collab/  loads/  packs/installed/  derived/
│   │   │              cache/  designs/  dev-sutras/  net/  lakes/  console/  tmp/{server,console}/  home/  logs/
│   │   ├── config/    connectors/  net/{node.yaml,gossip.yaml,routing.yaml,peers/}  ai/  mcp/  bi/
│   │   └── sutras/
│   ├── drishti-b/opt/drishti/…
│   └── drishti-c/opt/drishti/…
└── uat/
    └── drishti-a/opt/drishti/…
```

### 4.4 One node, one writer

Two tasks on one node's folders would open the same RocksDB stores and DuckDB files. Three guards:

1. The service allows at most one running task (`maximumPercent: 100`, `minimumHealthyPercent: 0`, section 13): ECS
   stops the old task before it starts the new one.
2. Drishti's own lock checks: `SortedStore.inUse` takes the lock of a RocksDB store's `LOCK` file (EFS supports NFSv4.1
   byte-range locks), and a second process refuses a store in use.
3. The entry point writes `data/net/task.lock` with the task's ARN and refuses to start while another task's file is
   fresh (younger than two gossip `dead-after` periods): a hand-started duplicate stops at once.

## 5. The server container

### 5.1 Dockerfile and entry point

[`deploy/docker/ecs/server.Dockerfile`](../../deploy/docker/ecs/server.Dockerfile) and
[`deploy/docker/ecs/server-entrypoint.sh`](../../deploy/docker/ecs/server-entrypoint.sh). Build from the repository root
after `./mvnw -q package -DskipTests`:

```bash
docker build -f deploy/docker/ecs/server.Dockerfile -t drishti-server-ecs:2.7.0 .     # Java 25: --build-arg BASE_IMAGE=eclipse-temurin:25-jre
```

The image: the server jar, the shipped packs in `/opt/drishti/shipped/packs` (outside the EFS paths, so a mount never hides
them), the entry point, mount points owned by uid 10001, and the variables of section 3.3. Before Java starts, the entry
point:

1. checks that `/opt/drishti/data`, `/config` and `/sutras` are writable NFS mounts (exit 78 otherwise;
   `DRISHTI_REQUIRE_EFS=false` only for a local check of the image);
2. refuses to start while another task's `data/net/task.lock` is fresh (one task per node);
3. finds the task's real IPv4 address (never `127.*`, `0.0.0.0` or `localhost`);
4. on the node's first start only, writes `config/net/node.yaml` (`advertise-url: http://${DRISHTI_NODE_IP}:18480`,
   `secret: ${DRISHTI_NET_SECRET}`, both resolved by the server at every start) and a peer file per seed of
   `DRISHTI_NET_SEEDS`;
5. starts Java with ZGC, `-XX:-UsePerfData`, the temp folder, home, crash files and heap dumps on EFS
   (`-XX:+UseCompactObjectHeaders` on Java 25).

The task's ARN comes from the ECS task metadata endpoint (`ECS_CONTAINER_METADATA_URI_V4`, set by ECS in every
container); outside ECS the container's host name stands in.

### 5.3 What the server needs from the code (small changes when built)

| Change | Why |
|---|---|
| Touch `data/net/task.lock` every gossip interval | the entry point's duplicate check (section 4.4) |
| Accept a peer `url` whose host is a DNS name that resolves to a real address | seeds by Cloud Map names (section 9); refused today only if it resolves to loopback or the wildcard |
| Nothing else | every path is already a setting; the real-address rule of 2.6.0 is already in the server |

### 5.4 Sizing, health and logs

| | Value |
|---|---|
| CPU, memory | 4 vCPU, 12 GB (8 GB heap; RocksDB, DuckDB, Arrow and ZGC off the heap) |
| Health check | `/actuator/health/readiness` on the task's own address, every 15 s, 4 retries, 180 s start period (caches warm in the background; readiness says when the node answers) |
| Logs | standard output to CloudWatch Logs (`awslogs`, group `/drishti/<env>`, stream prefix the node id); files the server writes stay in `data/logs` on EFS |
| Stop | ECS sends SIGTERM; Spring Boot's graceful shutdown finishes requests in flight (`stopTimeout: 60`) |

## 6. The console container

### 6.1 Dockerfile and entry point

[`deploy/docker/ecs/console.Dockerfile`](../../deploy/docker/ecs/console.Dockerfile) and
[`deploy/docker/ecs/console-entrypoint.sh`](../../deploy/docker/ecs/console-entrypoint.sh):

```bash
docker build -f deploy/docker/ecs/console.Dockerfile -t drishti-console-ecs:2.7.0 .
```

Calc's Python runtime (Pyodide) and DuckDB-Wasm are fetched in a first stage (pinned, SHA-256 checked), so a running
console needs no internet; the Python code is compiled at build time, so the read-only root never needs `__pycache__`.
The entry point checks the three mounts as the server's does, finds the task's real address and, unless
`DRISHTI_BACKEND_URL` is set (a console service of its own names the seeds' Cloud Map names), reaches the server of its
own task at `http://<task IP>:18480`.

The console **listens** on every interface of its task (`0.0.0.0`, so the load balancer reaches it); it never
**advertises** an address: the real-address rule of Drishti*net concerns what members advertise.

### 6.3 Sizing, sessions and health

| | Value |
|---|---|
| CPU, memory | 2 vCPU, 4 GB; `DRISHTI_CONSOLE_WORKERS=4` (one process per core serves about 40 pages a second, *measured*) |
| Starts after | the server of its task is healthy (`dependsOn … HEALTHY`) |
| Other servers | discovered from its server (`DRISHTI_BACKEND_DISCOVER`, on by default); a session falls over to another server when its own does not answer |
| Sessions | signed cookies (`DRISHTI_SESSION_SECRET`, the same in every task), so any console accepts any session; load balancer stickiness stays on so a user's live pages keep to one task |
| Health check | `/healthz`, by the load balancer's target group and the task definition |
| Logs | standard output to CloudWatch Logs |

## 7. Starting a node

```
 ECS                         server container                                    console container
  │ start task                 │                                                   │
  ├─ mount EFS access points ──┤ (data, config, sutras at /opt/drishti/…)          │
  ├─ inject secrets ───────────┤                                                   │
  │                            ├─ check the three mounts: writable, NFS  ──fail──► exit 78, task stops, ECS retries
  │                            ├─ task.lock: another task alive?          ──yes──► exit 78
  │                            ├─ real IPv4 of the task (never 127.*, 0.0.0.0)
  │                            ├─ first start? write config/net/node.yaml + peers/<seed>.yaml
  │                            ├─ java -Xmx8g … -jar app.jar
  │                            │     ├─ gossip: join through the seeds, advertise http://<task IP>:18480
  │                            │     ├─ IdentitySync and StateSync catch up from peers
  │                            │     └─ caches warm in the background
  │                            ├─ readiness UP ─────────────────────────────────►  │ (dependsOn HEALTHY)
  │                            │                                                   ├─ same mount checks, real IPv4
  │                            │                                                   ├─ backend = http://<task IP>:18480
  │                            │                                                   ├─ python run_drishti_web.py
  ├─ register in Cloud Map (drishti-a.drishti.local → task IP)                    │
  └─ target group: console healthy (/healthz) ◄────────────────────────────────────┘ users reach it
```

## 8. S3

### 8.1 Buckets

| Bucket | Content | Settings |
|---|---|---|
| `<org>-drishti-<env>-lake` | the lakes (Delta Lake tables; Iceberg; files) | versioning **on** with noncurrent versions expired after 14 days (a safety net against a wrong delete; Delta keeps its own history; [AWS_DELTA_LAKE.md section 5.1](AWS_DELTA_LAKE.md#51-buckets)), SSE-KMS with `alias/drishti-lake`, Block Public Access, access logging to the logs bucket, lifecycle tiers by business date (`deploy/aws/delta-lake/setup-lifecycle.sh`; [SUPER_SCALE.md section 15](SUPER_SCALE.md#15-seven-years-storage-tiers-and-retention)) |
| `<org>-drishti-<env>-artifacts` | pack bundles, release artifacts | versioning on, SSE-KMS |
| `<org>-drishti-<env>-logs` | S3 and load balancer access logs | lifecycle: expire after the bank's log retention |

### 8.2 How Drishti reads S3

- The Delta Lake native engine, Iceberg and the S3 connector take `s3://` roots in their connector files
  (`/opt/drishti/config/connectors/*.yaml`, on EFS).
- Credentials come from the **task role** through the AWS default chain: no access keys in files, variables or EFS.
- A **gateway VPC endpoint for S3** keeps the traffic inside AWS (no NAT gateway charges); the lake bucket's policy
  allows only that endpoint and the Drishti roles.

```yaml
# /opt/drishti/config/connectors/trade-endur.yaml
plugin: delta
kinds: [trade]
settings:
  root: s3://acme-drishti-prod-lake/trades/endur
  engine: native
  s3.region: eu-west-2
```

### 8.3 Bucket policy (lake)

```json
{
  "Version": "2012-10-17",
  "Statement": [
    { "Sid": "OnlyThroughTheVpcEndpoint", "Effect": "Deny", "Principal": "*", "Action": "s3:*",
      "Resource": ["arn:aws:s3:::acme-drishti-prod-lake", "arn:aws:s3:::acme-drishti-prod-lake/*"],
      "Condition": { "StringNotEquals": { "aws:sourceVpce": "vpce-…" } } },
    { "Sid": "TlsOnly", "Effect": "Deny", "Principal": "*", "Action": "s3:*",
      "Resource": ["arn:aws:s3:::acme-drishti-prod-lake", "arn:aws:s3:::acme-drishti-prod-lake/*"],
      "Condition": { "Bool": { "aws:SecureTransport": "false" } } }
  ]
}
```

### 8.4 What is not in S3

Anything the server or the console writes while running: by the rule it is on EFS. Loading the lakes (writing to S3) is
done by loader tasks with their own role ([AWS_DELTA_LAKE.md section 8](AWS_DELTA_LAKE.md#8-loading-the-daily-load-backfill-and-restatement), built;
[SUPER_SCALE.md section 7](SUPER_SCALE.md#7-loading-500-gb-a-day), the planned fleet); the
servers' role reads only.

## 9. Drishti*net on ECS

`deploy/aws/ecs/setup-cloud-map.sh` creates the namespace and a service per node; `register.sh` sets each server's
`DRISHTI_NET_SEEDS` from `DRISHTI_NET_SEED_NODES`.

```
   Cloud Map namespace drishti.local (private DNS)
     drishti-a.drishti.local  A  10.0.1.23     ◄── registered by ECS when drishti-a's task is healthy
     drishti-b.drishti.local  A  10.0.2.41
     drishti-c.drishti.local  A  10.0.3.17

   drishti-c starts (first time):
     DRISHTI_NET_SEEDS = http://drishti-a.drishti.local:18480,http://drishti-b.drishti.local:18480
       └─► entry point writes config/net/peers/drishti-a.yaml and drishti-b.yaml (url: http://drishti-a.drishti.local:18480)
     server resolves drishti-a.drishti.local → 10.0.1.23 (a real address; loopback would be refused)
       └─► gossips with drishti-a, advertising http://10.0.3.17:18480 (its own task address)
     every member now lists drishti-c at 10.0.3.17; the console of any node discovers it
```

- **Real addresses only.** With `awsvpc` networking each task has its own address in the VPC. The server advertises
  `http://<that address>:18480`; `node.yaml` says `advertise-url: http://${DRISHTI_NODE_IP}:18480`, resolved at every
  start, so a task that comes back with a new address advertises the new one. Never loopback, never `0.0.0.0`.
- **Seeds by name.** Peer files name seeds by their Cloud Map names, which resolve to task addresses; what members
  advertise stays an IP.
- **The cluster secret** (`DRISHTI_NET_SECRET`) comes from Secrets Manager; `node.yaml` names it, so it never lands on
  EFS.
- **Users, roles and API tokens** replicate between nodes as today (IdentitySync); each node also keeps its copy on its
  own EFS folders, so a whole-cluster restart loses nothing.
- **A node's identity is its service**: node id `drishti-a`, its access points, its Cloud Map name. The ring position
  follows the node id, so a task that restarts keeps its share.

## 10. Network

```
 VPC 10.0.0.0/16 (three Availability Zones)
 ┌───────────────────────────────────────────────────────────────────────────────────────────────┐
 │ public or internal subnets:   ALB (sg drishti-alb)                                             │
 │ private subnets (one per AZ): ECS tasks (sg drishti-node) · EFS mount targets (sg drishti-efs) │
 │ endpoints: S3 (gateway) · Secrets Manager, ECR (api, dkr), CloudWatch Logs, STS (interface)    │
 │ no internet route from the private subnets (a NAT gateway only if a connector needs the internet,│
 │ such as news or market data; otherwise none)                                                    │
 └───────────────────────────────────────────────────────────────────────────────────────────────┘
```

| Security group | Inbound | Outbound |
|---|---|---|
| `drishti-alb` | 443 from the users' networks | 17480 to `drishti-node` |
| `drishti-node` | 17480 from `drishti-alb`; 18480 from `drishti-node` (gossip, forwarding, consoles of other nodes) | 18480 to `drishti-node`; 2049 to `drishti-efs`; 443 to the endpoints |
| `drishti-efs` | 2049 from `drishti-node` | none |

- Only the load balancer is reachable from users; servers are never exposed.
- **TLS** at the load balancer with an ACM certificate; between the load balancer and the consoles, and between
  servers, traffic stays in the VPC on private subnets. A bank that requires TLS inside the VPC as well runs the
  servers with their own certificate (Drishti's TLS settings) and `https://` advertise URLs.
- **Sign-in**: the console's own sign-in, or OIDC as today (the load balancer can also authenticate with OIDC before
  the console).

## 11. Identity, roles and secrets

### 11.1 Task role (what the containers may do)

```json
{
  "Version": "2012-10-17",
  "Statement": [
    { "Sid": "ReadTheLake", "Effect": "Allow", "Action": ["s3:GetObject", "s3:ListBucket"],
      "Resource": ["arn:aws:s3:::acme-drishti-prod-lake", "arn:aws:s3:::acme-drishti-prod-lake/*"] },
    { "Sid": "DecryptTheLake", "Effect": "Allow", "Action": ["kms:Decrypt"],
      "Resource": "arn:aws:kms:…:key/<drishti-lake key>" },
    { "Sid": "MountThisNodesFolders", "Effect": "Allow",
      "Action": ["elasticfilesystem:ClientMount", "elasticfilesystem:ClientWrite"],
      "Resource": "arn:aws:elasticfilesystem:…:file-system/fs-…",
      "Condition": { "StringEquals": { "elasticfilesystem:AccessPointArn": [
        "arn:aws:elasticfilesystem:…:access-point/fsap-…data",
        "arn:aws:elasticfilesystem:…:access-point/fsap-…config",
        "arn:aws:elasticfilesystem:…:access-point/fsap-…sutras" ] } } }
  ]
}
```

One task role per node (`drishti-prod-drishti-a-task`) limits each node to its own access points. The scripts create
one shared role, `drishti-<env>-server-task` (`deploy/aws/delta-lake/setup-iam.sh`), allowed any access point of the file
system; a role per node is a tightening left to the platform team's template.

### 11.2 Execution role (what ECS does for the task)

Pull the images from ECR, write the log streams, and read the node's secrets:
`ecr:GetAuthorizationToken`, `ecr:BatchGetImage`, `ecr:GetDownloadUrlForLayer`, `logs:CreateLogStream`,
`logs:PutLogEvents`, `secretsmanager:GetSecretValue` on `drishti/prod/*`, `kms:Decrypt` on the secrets' key.

### 11.3 Secrets

| Secret (Secrets Manager) | Variable | Container |
|---|---|---|
| `drishti/prod/token` | `DRISHTI_TOKEN_SECRET` (at least 32 characters; a master key, OPERATIONS.md section 9) | server, console |
| `drishti/prod/session` | `DRISHTI_SESSION_SECRET` (at least 32 characters) | console |
| `drishti/prod/net` | `DRISHTI_NET_SECRET` (the cluster key) | server |
| `drishti/prod/admin` (`username`, `password`) | `DRISHTI_SEED_USERNAME`, `DRISHTI_SEED_PASSWORD` (the first administrator, first start only) | server |

These are the variables `drishti-stack/secrets.env.example` lists for the local cluster. Nothing secret is in an image,
a task definition's plain environment, or EFS. Rotation: a new secret version and a rolling restart (section 14.1);
rotating the token secret signs everyone out, as on one machine.

## 12. Task definition

For node `drishti-a` (the other nodes differ only in the node id and the three access point ids). The template is
[`deploy/aws/ecs/templates/node-task.json`](../../deploy/aws/ecs/templates/node-task.json); `deploy/aws/ecs/register.sh`
fills it from `deploy/aws/env` and the access points of `setup-efs.sh`, and registers it. It also sets
`DRISHTI_DELTA_ROOT=s3://<lake bucket>/lake`, so the shipped Delta connectors read the lake in S3. As rendered:

```json
{
  "family": "drishti-prod-drishti-a",
  "networkMode": "awsvpc",
  "requiresCompatibilities": ["FARGATE", "EC2"],
  "cpu": "6144", "memory": "16384",
  "runtimePlatform": { "operatingSystemFamily": "LINUX", "cpuArchitecture": "X86_64" },
  "taskRoleArn": "arn:aws:iam::<account>:role/drishti-prod-drishti-a-task",
  "executionRoleArn": "arn:aws:iam::<account>:role/drishti-prod-execution",
  "volumes": [
    { "name": "data",   "efsVolumeConfiguration": { "fileSystemId": "fs-…", "transitEncryption": "ENABLED",
        "authorizationConfig": { "accessPointId": "fsap-…data",   "iam": "ENABLED" } } },
    { "name": "config", "efsVolumeConfiguration": { "fileSystemId": "fs-…", "transitEncryption": "ENABLED",
        "authorizationConfig": { "accessPointId": "fsap-…config", "iam": "ENABLED" } } },
    { "name": "sutras", "efsVolumeConfiguration": { "fileSystemId": "fs-…", "transitEncryption": "ENABLED",
        "authorizationConfig": { "accessPointId": "fsap-…sutras", "iam": "ENABLED" } } }
  ],
  "containerDefinitions": [
    { "name": "server", "image": "<account>.dkr.ecr.<region>.amazonaws.com/drishti-server-ecs:<version>",
      "cpu": 4096, "memory": 12288, "essential": true, "readonlyRootFilesystem": true, "stopTimeout": 60,
      "portMappings": [{ "containerPort": 18480, "protocol": "tcp" }],
      "mountPoints": [
        { "sourceVolume": "data",   "containerPath": "/opt/drishti/data" },
        { "sourceVolume": "config", "containerPath": "/opt/drishti/config" },
        { "sourceVolume": "sutras", "containerPath": "/opt/drishti/sutras" } ],
      "environment": [
        { "name": "DRISHTI_NODE_ID", "value": "drishti-a" },
        { "name": "DRISHTI_NET_SEEDS", "value": "http://drishti-a.drishti.local:18480,http://drishti-b.drishti.local:18480" },
        { "name": "DRISHTI_SECURITY_ENABLED", "value": "true" },
        { "name": "DRISHTI_SEED_ADMIN", "value": "true" },
        { "name": "DRISHTI_PACKS", "value": "core,market-data,trading,counterparty-risk,market-risk" } ],
      "secrets": [
        { "name": "DRISHTI_TOKEN_SECRET",  "valueFrom": "arn:aws:secretsmanager:…:secret:drishti/prod/token" },
        { "name": "DRISHTI_NET_SECRET",    "valueFrom": "arn:aws:secretsmanager:…:secret:drishti/prod/net" },
        { "name": "DRISHTI_SEED_USERNAME", "valueFrom": "arn:aws:secretsmanager:…:secret:drishti/prod/admin:username::" },
        { "name": "DRISHTI_SEED_PASSWORD", "valueFrom": "arn:aws:secretsmanager:…:secret:drishti/prod/admin:password::" } ],
      "healthCheck": { "command": ["CMD-SHELL", "curl -fsS --max-time 3 http://$(hostname -I | awk '{print $1}'):18480/actuator/health/readiness"],
                       "interval": 15, "timeout": 5, "retries": 4, "startPeriod": 180 },
      "logConfiguration": { "logDriver": "awslogs", "options": { "awslogs-group": "/drishti/prod", "awslogs-region": "<region>",
                                                                 "awslogs-stream-prefix": "drishti-a" } } },
    { "name": "console", "image": "<account>.dkr.ecr.<region>.amazonaws.com/drishti-console-ecs:<version>",
      "cpu": 2048, "memory": 4096, "essential": true, "readonlyRootFilesystem": true, "stopTimeout": 30,
      "dependsOn": [{ "containerName": "server", "condition": "HEALTHY" }],
      "portMappings": [{ "containerPort": 17480, "protocol": "tcp" }],
      "mountPoints": [
        { "sourceVolume": "data",   "containerPath": "/opt/drishti/data" },
        { "sourceVolume": "config", "containerPath": "/opt/drishti/config" },
        { "sourceVolume": "sutras", "containerPath": "/opt/drishti/sutras" } ],
      "environment": [
        { "name": "DRISHTI_AUTH_ENABLED", "value": "true" },
        { "name": "DRISHTI_CONSOLE__PACKS__ENABLED", "value": "core,market-data,trading,counterparty-risk,market-risk" } ],
      "secrets": [
        { "name": "DRISHTI_TOKEN_SECRET",   "valueFrom": "arn:aws:secretsmanager:…:secret:drishti/prod/token" },
        { "name": "DRISHTI_SESSION_SECRET", "valueFrom": "arn:aws:secretsmanager:…:secret:drishti/prod/session" } ],
      "healthCheck": { "command": ["CMD-SHELL", "python -c \"import urllib.request;urllib.request.urlopen('http://localhost:17480/healthz')\""],
                       "interval": 15, "timeout": 5, "retries": 4, "startPeriod": 60 },
      "logConfiguration": { "logDriver": "awslogs", "options": { "awslogs-group": "/drishti/prod", "awslogs-region": "<region>",
                                                                 "awslogs-stream-prefix": "drishti-a" } } }
  ]
}
```

The server's health check asks readiness on the task's own address, so the console (`dependsOn … HEALTHY`) starts
only when its server answers.

## 13. Service definition

Template [`deploy/aws/ecs/templates/node-service.json`](../../deploy/aws/ecs/templates/node-service.json), created or
updated by `register.sh`; a console service of its own is `console-task.json` and `console-service.json`
(`register.sh --consoles N`). As rendered for `drishti-a`:

```json
{
  "serviceName": "drishti-a",
  "cluster": "drishti-prod",
  "taskDefinition": "drishti-prod-drishti-a",
  "desiredCount": 1,
  "launchType": "FARGATE",
  "platformVersion": "1.4.0",
  "deploymentConfiguration": { "maximumPercent": 100, "minimumHealthyPercent": 0,
                               "deploymentCircuitBreaker": { "enable": true, "rollback": true } },
  "networkConfiguration": { "awsvpcConfiguration": { "subnets": ["subnet-a", "subnet-b", "subnet-c"],
                                                     "securityGroups": ["sg-drishti-node"], "assignPublicIp": "DISABLED" } },
  "loadBalancers": [{ "targetGroupArn": "arn:aws:elasticloadbalancing:…:targetgroup/drishti-consoles/…",
                      "containerName": "console", "containerPort": 17480 }],
  "serviceRegistries": [{ "registryArn": "arn:aws:servicediscovery:…:service/srv-drishti-a" }],
  "healthCheckGracePeriodSeconds": 240,
  "enableExecuteCommand": false,
  "propagateTags": "SERVICE"
}
```

- `maximumPercent: 100` and `minimumHealthyPercent: 0`: a new deployment stops the node's task before starting the
  new one: never two tasks on one node's folders.
- The deployment circuit breaker rolls back a version that cannot become healthy.
- `enableExecuteCommand: false`: no shell into production tasks; diagnosis is through logs, metrics and Admin.

## 14. Operations

### 14.1 A new version (rolling, one node at a time)

```
  for each node (drishti-a, then b, then c), waiting for each to be healthy before the next:
    1. register a task definition revision with the new image tags
    2. update the node's service → ECS stops the old task (SIGTERM, graceful) → starts the new task
    3. the new task mounts the same EFS folders, rejoins Drishti*net with its (possibly new) address
    4. healthy: the load balancer sends it users again
  the other nodes answer meanwhile (the console falls over to another server; reads forward to a peer)
```

Schema and cache migrations happen at start, as on one machine; a version that needs all nodes on it at once is
marked in RELEASE_NOTES and deployed by stopping every service first.

### 14.2 Everyday events

| Event | What happens |
|---|---|
| **Task or host failure** | ECS starts a new task, possibly in another zone, on the same folders; the node rejoins with its new address; the console of another node keeps its users |
| **Adding a node** | three access points, a task role, a task definition and a service for `drishti-d`; its first start writes its Drishti*net files and joins through the seeds; Admin → Net shows it |
| **Removing a node** | scale its service to 0, then delete it; Admin → Net → Members, "forget"; its EFS folders are kept until the backup retention passes, then deleted by hand |
| **A pack** | `drishti.py pack deploy … --to /opt/drishti/data/packs/installed` from an administration host that mounts the node's data access point, or Admin → Packs upload; the server loads it by itself; for every node, repeat per node (or deploy once and let Drishti*net's newest-version rule serve it, DRISHTI_NET.md section 9) |
| **Connector files, Sutras** | edited in the console (written to EFS) or copied onto EFS from an administration host |
| **Secrets rotation** | new secret version, then a rolling restart (14.1) |
| **Certificates** | ACM renews the load balancer's certificate by itself |

### 14.3 The administration host

A small EC2 instance (or an AWS Systems Manager session on one) in a private subnet that mounts the EFS file system
with an administrator's access point rooted at `/drishti`, for pack deployment, inspecting files and restores. It is
the only place where people touch EFS directly.

### 14.4 Upgrading Drishti*net membership settings

`config/net/*.yaml` are files on EFS: edited on the administration host or in Admin → Net; the server watches them
(`DRISHTI_NET_WATCH`), so no restart is needed.

### 14.5 Back-up and restore

| | |
|---|---|
| Back-up | AWS Backup plan `drishti-efs-daily`: daily at 01:00, kept 35 days (a site setting), monthly kept 13 months; vault locked against deletion |
| Restore one node | stop its service (desired count 0); restore `/drishti/prod/<node>` from the recovery point to the same path (item-level restore); start the service |
| Restore everything | stop every service; restore the file system's `/drishti/prod`; start the seeds first, then the rest |
| What is lost | changes since the recovery point; users, roles and tokens are also on the other nodes (IdentitySync) and come back from them |

## 15. Observability

| Signal | Where |
|---|---|
| Logs | CloudWatch Logs `/drishti/<env>`, one stream per node and container; log files on EFS in `data/logs` |
| Metrics | the server's Prometheus endpoint (`/actuator/prometheus`, `DRISHTI_METRICS_TOKEN`) scraped by Amazon Managed Service for Prometheus or the CloudWatch agent; the Grafana dashboard in `deploy/grafana` |
| ECS | CPU and memory per container (Container Insights) |
| EFS | `PercentIOLimit`, `ClientConnections`, `MeteredIOBytes`, latency (CloudWatch) |
| Drishti | Admin → Health, Admin → Net (members, the donut, forwarding), Admin → Caches, per node |

Alarms: a node's service with no running task for 5 minutes; a console target unhealthy; server heap above 90 % for 10
minutes; EFS `PercentIOLimit` above 80 %; Drishti*net member `dead` for longer than 5 minutes; the backup job failed.

## 16. Disaster recovery

| | Primary Region | Recovery Region |
|---|---|---|
| Lakes | S3 bucket | S3 Cross-Region Replication of the lake bucket |
| Node files | EFS | EFS replication (read-only replica, made writable on fail-over) |
| Images | ECR | ECR replication |
| Secrets | Secrets Manager | replicated secrets |
| Running | the services | the same task and service definitions, desired count 0 |

Fail-over: make the EFS replica writable, set the recovery services' desired count to 1, point the load balancer's DNS
(Route 53) at the recovery Region. Recovery point: the replication lag (minutes); recovery time: the time to start the
nodes and warm their caches (tens of minutes). Exercised twice a year.

## 17. Performance: what EFS costs

EFS is a network file system: an operation takes about a millisecond or more, where a local NVMe disk takes tens of
microseconds. For files read and written whole (users, settings, reports, designs) this does not matter. It does for
the **caches** that the rule also puts on EFS:

| Cache on EFS | Effect | What softens it |
|---|---|---|
| RocksDB derived pages and document cache | slower first reads of a page; slower builds (many small writes) | Drishti's memory caches in front of every disk cache; RocksDB's block cache |
| DuckDB files (BI store) | slower first queries after a start | DuckDB's buffer pool |
| Warm column files and type-ahead segments (memory-mapped) | the first touch of a page of the file reads it over NFS; then the operating system's page cache serves it | page cache: give the task memory beyond the heap |
| Temp folders, native libraries | a slower start (a few seconds) | none needed |

Step A4 measures, on EFS and on local disk: node start to ready, first trade view, a form search, a derived-page build
and a BI query; the numbers go here. If they show slow first reads that matter, the product owner decides whether
the caches (which Drishti can always rebuild from the lake) may use the task's local disk instead (section 21).

## 18. Cost

Order of magnitude for three nodes in one Region, list prices, on-demand (to be priced for the bank's Region and
discounts):

| Item | Size | Monthly |
|---|---|---|
| ECS on Fargate | 3 tasks × (6 vCPU, 16 GB), always on | about USD 650–750 |
| EFS | 300 GB Standard, Elastic throughput | about USD 100 plus throughput charges |
| Load balancer | one ALB | about USD 25 plus capacity units |
| S3 | the lake (depends on data; SUPER_SCALE.md section 19 for 500 GB a day) | |
| Other | Secrets Manager, Cloud Map, CloudWatch, AWS Backup, VPC endpoints | about USD 100–200 |

## 19. Tests and acceptance

| Test | Pass when |
|---|---|
| **Read-only root, every flow**: both images run with a read-only root and plain folders mounted at the three paths (`DRISHTI_REQUIRE_EFS=false`, the only use of that switch), and the browser suite's smoke set runs against them | no write outside the three paths (a write elsewhere fails the test, naming the path) |
| **Entry points**: a missing mount, a read-only mount, a mount that is not NFS, a loopback address, a second task | each refuses to start with its message and exit code 78 |
| **Durability** (development account): stop a node's task | users, tokens, connector files, Sutras, designs, reports, installed packs and cluster membership are as before |
| **Zone move**: force the task into another Availability Zone | the same |
| **Restore** from AWS Backup to a new path, then swapped in | the node comes back as of the recovery point |
| **Drishti*net**: three nodes find each other through Cloud Map names | each advertises its task address; a restart with a new address is seen by every member within one `dead-after` |
| **Rolling upgrade** | users of the node being upgraded fall over to another console without signing in again |
| **Performance** (section 17) | measured and recorded |

## 20. Build plan

| Step | Content | Done when |
|---|---|---|
| A1 | The images and entry points of sections 5–6 as files (**done**: `deploy/docker/ecs/`); `task.lock` touched by the server; DNS seed names accepted | the read-only-root and entry-point tests pass on a build machine with Docker |
| A2 | One template (CloudFormation or Terraform): VPC pieces, EFS and its access points, roles, secrets, task and service definitions, load balancer, Cloud Map, endpoints, backup plan, alarms (**scripted** with the AWS CLI in `deploy/aws/`: access points, Cloud Map, roles, task and service definitions, schedules, lake buckets, alarms; the VPC, EFS, load balancer, endpoints and secrets remain the platform team's) | a new environment comes up from the template alone |
| A3 | A development environment: the durability, zone, restore, Drishti*net and rolling-upgrade tests | all pass |
| A4 | EFS measurements (section 17) | recorded here |
| A5 | Documentation: OPERATIONS (an AWS chapter), OPERATIONALISING section 13, a tutorial "Drishti on AWS" (UI and CLI), help cards | reviewed |

## 21. Open questions

- **Fargate or EC2** capacity: Fargate is simpler; EC2 allows larger tasks and cheaper steady capacity. The design
  works on both.
- **CloudFormation or Terraform** for step A2: whichever the bank's platform team uses.
- **Caches on EFS**: the rule puts them there; step A4's numbers decide whether to keep it so.
- **Consoles**: one per node (this design), or a console service scaled on its own (recommended at the scale of
  SUPER_SCALE.md).
- **TLS inside the VPC**: required by the bank or not.
