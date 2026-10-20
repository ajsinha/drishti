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

Status: **design; the images are written (`deploy/aws/`), not yet built or run.** Release not decided (product owner,
2026-10-20). Related: [DRISHTI_NET.md](DRISHTI_NET.md) and [NET.md](../admin/NET.md) (several servers),
[OPERATIONS.md](../admin/OPERATIONS.md) (running one server), [OPERATIONALISING.md section 13](../guides/OPERATIONALISING.md#13-docker-and-kubernetes)
(Docker and Kubernetes today).

## Contents

1. [The ask and the rule](#1-the-ask-and-the-rule) · 2. [The picture](#2-the-picture) · 3. [The path map](#3-the-path-map) ·
4. [EFS](#4-efs) · 5. [The server](#5-the-server) · 6. [The console](#6-the-console) · 7. [S3](#7-s3) ·
8. [Drishti*net on ECS](#8-drishtinet-on-ecs) · 9. [Network and security](#9-network-and-security) ·
10. [Task definition](#10-task-definition) · 11. [Operations](#11-operations-deploy-restart-back-up-restore) ·
12. [Performance](#12-performance-what-efs-costs) · 13. [Tests](#13-tests) · 14. [Build plan](#14-build-plan) ·
15. [Open questions](#15-open-questions)

## 1. The ask and the rule

The product owner (2026-10-20): Drishti runs in AWS on **ECS**, with **S3** and **EFS**; the server and the console are
**separate containers**. The rule:

> **Whatever path a container uses must have a mapped path on EFS, identical, so that everything is durable.**

This design turns the rule into something that cannot be broken by accident:

- **Everything a container writes is on EFS, at the same path as in the container.** A task that stops, restarts, moves
  to another Availability Zone or is replaced by a new image comes back to exactly the files it left.
- **The container's root file system is read-only** (`readonlyRootFilesystem: true`). A write anywhere else fails at
  once instead of being silently lost when the task stops. `/tmp`, `HOME`, the JVM's temp folder, crash files and heap
  dumps are all pointed at EFS.
- **The entry point refuses to start** if a writable path is not mounted, not writable, or not on EFS (NFS).
- **The image holds only what never changes while it runs:** the server jar, the console's code, the packs Drishti
  ships, the documents for the help centre. These are versioned with the image in ECR, not data.
- **S3 holds the lakes,** read directly by the connectors (`s3://`), as today.

## 2. The picture

```
                       users ──HTTPS──► Application Load Balancer (ACM certificate; optional OIDC)
                                                │ 17480
              ┌─────────────────────────────────┼─────────────────────────────────┐
              ▼                                 ▼                                 ▼
   ECS service drishti-a              ECS service drishti-b              ECS service drishti-c        (one task each)
   ┌──── task (awsvpc, 10.0.1.23) ──┐ ┌──── task (10.0.2.41) ───────┐  ┌──── task (10.0.3.17) ───────┐
   │ console :17480 ─► server :18480│ │ console ─► server           │  │ console ─► server           │
   │   same EFS paths, uid 10001    │ │                             │  │                             │
   └──────────────┬─────────────────┘ └─────────────┬───────────────┘  └─────────────┬───────────────┘
                  │   ◄──── Drishti*net gossip, on the tasks' real addresses, port 18480 ────►       │
                  ▼                                  ▼                                ▼
   EFS (Regional, encrypted): /drishti/prod/drishti-a/…   /drishti/prod/drishti-b/…   /drishti/prod/drishti-c/…
                  │
                  ▼  (task role, VPC endpoint)
   S3: lakes (Delta Lake, Iceberg, files)            Secrets Manager: token, session and cluster secrets
```

**One ECS service per Drishti node**, each running exactly one task with **two containers, the server and the console**.
A node has a fixed name (`drishti-a`), its own EFS folders and its own place in the Drishti*net ring. The two containers
of a task share its network address and mount the same EFS folders at the same paths, so the console sees the packs,
lakes and folders exactly as on a single machine.

## 3. The path map

Every writable path, the same in the container and on EFS. On EFS each path sits under the node's prefix
`/drishti/<environment>/<node>`, because several environments and nodes share one file system and each node must have
its own files (RocksDB stores and DuckDB files are opened by one process at a time). Below the prefix the path is
**identical** to the container path:

| Container path | EFS path | Container(s) | What lives there |
|---|---|---|---|
| `/opt/drishti/data` | `/drishti/prod/drishti-a/opt/drishti/data` | server, console | identity (users, roles, API tokens), governance, BI store, reports, collaboration, loads history, installed packs, derived pages (RocksDB), the disk cache, DuckDB files, lake maintenance settings, the console's data (`data/console`), temp folders (`data/tmp/server`, `data/tmp/console`), homes (`data/home/…`), logs, crash files and heap dumps (`data/logs`) |
| `/opt/drishti/config` | `/drishti/prod/drishti-a/opt/drishti/config` | server, console | connector files (`config/connectors`), Drishti*net (`config/net`), AI providers (`config/ai`), MCP servers (`config/mcp`), BI files (`config/bi`) |
| `/opt/drishti/sutras` | `/drishti/prod/drishti-a/opt/drishti/sutras` | server, console | site Sutras |

What is **not** on EFS, because it is not written: `/opt/drishti/app.jar`, `/opt/drishti/shipped/packs`,
`/opt/drishti/console` (code), `/opt/drishti/docs`, `/opt/drishti/bin`. The images move the shipped packs from
`config/packs` to `shipped/packs` so the `config` mount never hides them (`DRISHTI_PACKS_DIR=/opt/drishti/shipped/packs`
in both images).

The variables that pin every path (set in both Dockerfiles, so nothing depends on a working directory):

| Variable | Value |
|---|---|
| `DRISHTI_DATA_DIR` | `/opt/drishti/data` |
| `DRISHTI_CONNECTORS_DIR`, `DRISHTI_NET_DIR`, `DRISHTI_AI_DIR`, `DRISHTI_MCP_DIR`, `DRISHTI_BI_DIR` | `/opt/drishti/config/{connectors,net,ai,mcp,bi}` |
| `DRISHTI_SUTRAS` | `/opt/drishti/sutras` |
| `DRISHTI_PACKS_DIR` | `/opt/drishti/shipped/packs` (read-only, from the image) |
| `TMPDIR`, `HOME` | `/opt/drishti/data/tmp/<server or console>`, `/opt/drishti/data/home/<server or console>` |
| `DRISHTI_CONSOLE_DATA_DIR`, `DRISHTI_CONSOLE_TMP_DIR` | `/opt/drishti/data/console`, `/opt/drishti/data/tmp/console` |

## 4. EFS

- **One Regional file system** (data in several Availability Zones; a task restarted in another zone finds its files),
  encrypted at rest (KMS) and in transit (TLS mount), `General Purpose` performance mode, **Elastic throughput**.
- **Access points, one per node per path** (three per node). Each roots its path and enforces the user:

| Access point | Root directory | POSIX user | Creation (first use) |
|---|---|---|---|
| `drishti-prod-drishti-a-data` | `/drishti/prod/drishti-a/opt/drishti/data` | uid 10001, gid 10001 | owner 10001:10001, mode 0750 |
| `drishti-prod-drishti-a-config` | `/drishti/prod/drishti-a/opt/drishti/config` | uid 10001, gid 10001 | owner 10001:10001, mode 0750 |
| `drishti-prod-drishti-a-sutras` | `/drishti/prod/drishti-a/opt/drishti/sutras` | uid 10001, gid 10001 | owner 10001:10001, mode 0750 |

  Both images run as uid 10001 (`drishti`), so the server and the console of a node read and write the same files.
- **IAM authorisation** on the mounts (`elasticfilesystem:ClientMount`, `ClientWrite`), allowed only to the node's task
  role and only through its access points.
- **No lifecycle move to Infrequent Access** by default: caches are read rarely but must be fast when they are.
- **Backups**: AWS Backup, daily, kept 35 days (a site setting), restorable per node folder.
- **Never two tasks on one node's folders**: the service's deployment allows at most one running task
  (`maximumPercent: 100`, `minimumHealthyPercent: 0`), and Drishti's own lock checks (`SortedStore.inUse`, which takes
  the RocksDB `LOCK` file's lock; EFS supports NFSv4 locks) refuse a second process on the same store.

## 5. The server

Image `deploy/aws/server.Dockerfile`, entry point `deploy/aws/server-entrypoint.sh`.

**At start** the entry point:

1. checks that `/opt/drishti/data`, `/opt/drishti/config` and `/opt/drishti/sutras` are writable and on NFS (EFS), and
   stops with exit code 78 and a message naming the path otherwise;
2. finds the task's **real IPv4 address** (the task's network interface; never loopback or `0.0.0.0`) into
   `DRISHTI_NODE_IP`;
3. on the node's **first start only** (no `config/net/node.yaml` yet, and `DRISHTI_NODE_ID` set) writes the node's
   Drishti*net files ([section 8](#8-drishtinet-on-ecs)); later starts never overwrite them;
4. starts Java 21 (or 25) with `-Xmx` from `DRISHTI_HEAP` (8 GB by default), ZGC, `java.io.tmpdir` and `user.home` on
   EFS, no `hsperfdata` file (`-XX:-UsePerfData`), crash files and heap dumps in `data/logs`.

**Sizing**: 4 vCPU and 12 GB per task for the 8 GB heap (off-heap: RocksDB, DuckDB, Arrow, ZGC).

**Health**: the task definition checks `/actuator/health/readiness` on the task's own address; the load balancer
checks the console ([section 6](#6-the-console)).

**Logs**: standard output to CloudWatch Logs (`awslogs`); files the server writes stay in `data/logs` on EFS.

## 6. The console

Image `deploy/aws/console.Dockerfile` (a separate container; Calc's Pyodide and DuckDB-Wasm fetched at build, so it needs
no internet), entry point `deploy/aws/console-entrypoint.sh`.

**At start** it makes the same mount checks, finds the task's real address and sets
`DRISHTI_BACKEND_URL=http://<task address>:18480`: the server of the same task. The console learns the cluster's
other members from that server (`DRISHTI_BACKEND_DISCOVER`, on by default) and falls over to them when its own
server does not answer.

- The code is compiled into the image (`compileall`) and `PYTHONDONTWRITEBYTECODE=1`: nothing is written beside the
  code.
- What the console keeps between runs (`data/console`, such as `net/servers.json`), its temp folders
  (`data/tmp/console`), lake maintenance settings (`data/lakes`) and its AI and MCP files (`config/ai`, `config/mcp`)
  are on EFS.
- **Sizing**: 2 vCPU and 4 GB (`DRISHTI_CONSOLE_WORKERS`, 4 processes).
- **Behind the load balancer** on port 17480, health check `/healthz`. Sessions are signed cookies
  (`DRISHTI_SESSION_SECRET`, the same in every task), so any console accepts any session; stickiness is still on, so a
  user's live pages keep to one task.

## 7. S3

- **Lakes stay in S3** and are read directly: the Delta Lake native engine, Iceberg and the S3 connector take
  `s3://` roots in their connector files (`/opt/drishti/config/connectors/*.yaml` on EFS). Credentials come from the
  **task role** through the AWS default chain: no keys in files or variables.
- **A gateway VPC endpoint for S3**: traffic stays inside AWS (no NAT costs); the bucket policy allows only that
  endpoint and the task roles.
- **Loading the lakes** in AWS (later): the loaders (`tools/load-delta.sh`, `tools/load-iceberg.sh`) as scheduled ECS
  tasks writing to S3, with their own role.
- **Not in S3**: anything the server or the console writes while running. That is EFS, by the rule.

## 8. Drishti*net on ECS

- **Real addresses only.** With `awsvpc` networking each task has its own address in the VPC; the server advertises
  `http://<that address>:18480` (`advertise-url: http://${DRISHTI_NODE_IP}:18480` in `node.yaml`, resolved at every start,
  so a task that comes back with a new address advertises the new one). Never loopback or `0.0.0.0`.
- **Finding the seeds.** Each node's service registers in AWS Cloud Map (`drishti-a.drishti.local`, an A record of the
  task's address). `DRISHTI_NET_SEEDS` lists the seeds' URLs by those names; on the node's first start the entry point
  writes one peer file per seed (`config/net/peers/drishti-a.yaml`), skipping the node itself. The name resolves to
  the seed task's real address; the advertised addresses stay IPs.
- **The cluster secret** (`DRISHTI_NET_SECRET`) comes from Secrets Manager; `node.yaml` names it as `${DRISHTI_NET_SECRET}`,
  so it never lands on EFS.
- **Users, roles and API tokens** replicate between nodes as today (IdentitySync); each node also keeps its own copy on
  its EFS folders, so a whole-cluster restart loses nothing.

## 9. Network and security

| Security group | Allows in |
|---|---|
| load balancer | 443 from the users' networks |
| Drishti tasks | 17480 from the load balancer; 18480 from the Drishti tasks (gossip, scatter-gather, consoles of other nodes) |
| EFS mount targets | 2049 from the Drishti tasks |

- Private subnets for the tasks and EFS; only the load balancer is reachable from users.
- `DRISHTI_SECURITY_ENABLED=true`; the first administrator from Secrets Manager (`DRISHTI_SEED_USERNAME`,
  `DRISHTI_SEED_PASSWORD`), as in `deploy/compose.yaml`; the development administrator is off.
- Secrets Manager into the task's environment through the task definition's `secrets`: `DRISHTI_TOKEN_SECRET`,
  `DRISHTI_SESSION_SECRET`, `DRISHTI_NET_SECRET` (the variables `drishti-stack/secrets.env.example` lists). The execution role
  reads them; nothing is in the image, the task definition's plain environment, or EFS.
- TLS at the load balancer (ACM certificate); optional OIDC sign-in at the console as today.

## 10. Task definition

For node `drishti-a` (the other nodes differ only in the node id and the three access point ids):

```json
{
  "family": "drishti-prod-drishti-a",
  "networkMode": "awsvpc",
  "requiresCompatibilities": ["FARGATE", "EC2"],
  "cpu": "6144", "memory": "16384",
  "taskRoleArn": "arn:aws:iam::<account>:role/drishti-prod-task",
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
    { "name": "server", "image": "<account>.dkr.ecr.<region>.amazonaws.com/drishti-server-ecs:2.5.0",
      "cpu": 4096, "memory": 12288, "essential": true, "readonlyRootFilesystem": true,
      "portMappings": [{ "containerPort": 18480 }],
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
        { "name": "DRISHTI_TOKEN_SECRET", "valueFrom": "arn:aws:secretsmanager:…:drishti/prod/token" },
        { "name": "DRISHTI_NET_SECRET",   "valueFrom": "arn:aws:secretsmanager:…:drishti/prod/net" },
        { "name": "DRISHTI_SEED_USERNAME", "valueFrom": "arn:aws:secretsmanager:…:drishti/prod/admin:username::" },
        { "name": "DRISHTI_SEED_PASSWORD", "valueFrom": "arn:aws:secretsmanager:…:drishti/prod/admin:password::" } ],
      "healthCheck": { "command": ["CMD-SHELL", "wget -qO- http://$(hostname -I | awk '{print $1}'):18480/actuator/health/readiness || exit 1"],
                       "interval": 15, "timeout": 5, "retries": 4, "startPeriod": 180 },
      "logConfiguration": { "logDriver": "awslogs", "options": { "awslogs-group": "/drishti/prod", "awslogs-region": "<region>",
                                                                 "awslogs-stream-prefix": "drishti-a" } } },
    { "name": "console", "image": "<account>.dkr.ecr.<region>.amazonaws.com/drishti-console-ecs:2.5.0",
      "cpu": 2048, "memory": 4096, "essential": true, "readonlyRootFilesystem": true,
      "dependsOn": [{ "containerName": "server", "condition": "HEALTHY" }],
      "portMappings": [{ "containerPort": 17480 }],
      "mountPoints": [
        { "sourceVolume": "data",   "containerPath": "/opt/drishti/data" },
        { "sourceVolume": "config", "containerPath": "/opt/drishti/config" },
        { "sourceVolume": "sutras", "containerPath": "/opt/drishti/sutras" } ],
      "environment": [
        { "name": "DRISHTI_AUTH_ENABLED", "value": "true" },
        { "name": "DRISHTI_CONSOLE__PACKS__ENABLED", "value": "core,market-data,trading,counterparty-risk,market-risk" } ],
      "secrets": [
        { "name": "DRISHTI_TOKEN_SECRET",   "valueFrom": "arn:aws:secretsmanager:…:drishti/prod/token" },
        { "name": "DRISHTI_SESSION_SECRET", "valueFrom": "arn:aws:secretsmanager:…:drishti/prod/session" } ],
      "logConfiguration": { "logDriver": "awslogs", "options": { "awslogs-group": "/drishti/prod", "awslogs-region": "<region>",
                                                                 "awslogs-stream-prefix": "drishti-a" } } }
  ]
}
```

The service: `desiredCount: 1`, `deploymentConfiguration: { maximumPercent: 100, minimumHealthyPercent: 0 }`, Cloud Map
registration `drishti-a.drishti.local`, the console's port in the load balancer's target group. Step A3 of the build
plan writes all of this (EFS, access points, roles, task definitions, services, load balancer, Cloud Map, secrets) as
one template.

## 11. Operations: deploy, restart, back up, restore

| Event | What happens |
|---|---|
| **New version** | a new task definition revision with the new image tags; each node's service stops its task, then starts the new one on the same EFS folders (one node at a time, so the cluster keeps answering) |
| **Task or host failure** | ECS starts a new task, possibly in another zone, on the same folders; the node rejoins Drishti*net with its new address |
| **Adding a node** | three access points, a task definition and a service for `drishti-d`; its first start writes its Drishti*net files and joins through the seeds |
| **Back-up** | AWS Backup of the file system: every node's every file, daily |
| **Restore one node** | stop its service, restore `/drishti/prod/<node>` from the back-up, start the service |
| **A pack** | `drishti.py pack deploy … --to /opt/drishti/data/packs/installed` from an administration host that mounts the node's data access point, or **Admin → Packs** upload; the server picks it up by itself |
| **Connector files, Sutras** | edited in the console (written to EFS) or copied onto EFS from an administration host |

## 12. Performance: what EFS costs

EFS is a network file system: each operation takes about a millisecond or more, where a local NVMe disk takes tens of
microseconds. For files read and written whole (users, settings, reports, designs) this does not matter. It does for
the **caches** that the rule also puts on EFS: RocksDB derived pages, the disk cache, DuckDB files and the temp folders.
Expected effects (to be measured in step A4):

- a **slower first start** of a node while caches are opened and warmed;
- **slower first reads** of pages served from the disk cache or derived pages; reads already in memory are unchanged;
- **slower builds** of derived pages and DuckDB work that write many small pieces.

What helps, within the rule: Elastic throughput; the memory caches Drishti already keeps in front of every disk
cache; EFS's mount options from the EFS mount helper (`nfsvers=4.1`, large `rsize` and `wsize`). Step A4 measures
start time, first trade view, a search and a derived-page build on EFS against local disk and records them here.

## 13. Tests

- **Read-only root, every flow**: both images run with a read-only root file system and plain folders mounted at the
  three paths (`DRISHTI_REQUIRE_EFS=false`, the only use of that switch), and the browser test suite's smoke set runs
  against them. Any write outside the three paths fails the test, naming the path.
- **The entry points**: a missing mount, a read-only mount, a mount that is not NFS, a loopback address: each refuses
  to start with its message.
- **Durability**: in a development AWS account, a node's task is stopped and comes back: users, tokens, connector
  files, Sutras, designs, reports, installed packs and the cluster membership are as before; then the same across
  zones, and a restore from AWS Backup.
- **Two tasks, one node**: a second task started by hand on a node's folders is refused (store locks).
- **Drishti*net**: three nodes find each other through Cloud Map names, advertise their task addresses, and the
  cluster survives a node's restart with a new address.

## 14. Build plan

| Step | Content | State |
|---|---|---|
| A1 | ECS images and entry points (`deploy/aws/`) | written; not built or run yet |
| A2 | Read-only-root test of both images and the entry-point tests (needs Docker on the build machine) | |
| A3 | One template (CloudFormation or Terraform, [section 15](#15-open-questions)): EFS, access points, roles, secrets, task definitions, services, load balancer, Cloud Map, security groups, AWS Backup plan | |
| A4 | A development environment: the durability tests, the EFS measurements of section 12 | |
| A5 | Docs: OPERATIONS (an AWS chapter), OPERATIONALISING section 13, a tutorial "Drishti on AWS", help cards | |

## 15. Open questions

- **Fargate or EC2** capacity: Fargate is simpler; EC2 allows larger tasks and cheaper steady capacity. The design
  works on both.
- **CloudFormation or Terraform** for step A3: whichever the bank's platform team uses.
- **Caches on EFS**: the rule puts them there; if the measurements of step A4 show slow first reads that matter,
  the product owner decides whether caches (which Drishti can rebuild) may move to the task's local disk.
- **Consoles**: one per node (this design), or a separate console service scaled on its own.
