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
# Operations

This is the operator's handbook: how to install Drishti, start it, put it behind TLS, keep it safe, back it up,
watch it and upgrade it. Paths, settings and defaults here are taken from the code and the shipped configuration. Where a command prints something, the
text after **You should see** is what a healthy system prints.

If you only want to try Drishti on your own machine, the README's *Try it* section is enough. Read this document
when other people will use the installation.

## Contents

1. [What you are running](#1-what-you-are-running)
2. [Prerequisites](#2-prerequisites)
3. [Install from a build (one machine)](#3-install-from-a-build-one-machine)
4. [Install with Docker Compose](#4-install-with-docker-compose)
5. [Data stores, sample data and feeds](#5-data-stores-sample-data-and-feeds)
6. [Environment variables](#6-environment-variables)
7. [Running as services (systemd)](#7-running-as-services-systemd)
8. [TLS and the reverse proxy](#8-tls-and-the-reverse-proxy)
9. [Production checklist](#9-production-checklist) · [The token secret is a master key](#9a-1-the-token-secret-is-a-master-key) · [Scheduled reports](#9a-scheduled-reports) · [Collaboration email](#9a-2-collaboration-email-and-the-outbox) · [Retention, holds and export](#9a-3-collaboration-retention-legal-holds-and-the-compliance-export) · [Watermarked snapshots](#9a-3b-watermarked-snapshots-privacy-note) · [Chat bridges](#9a-4-chat-bridges-teams-slack-webhooks) · [The access log](#9b-the-access-log) · [Pack registry keys](#9c-pack-registry-keys)
10. [Backups and restore](#10-backups-and-restore)
11. [The lake: where it lives and keeping it bounded](#11-the-lake-where-it-lives-and-keeping-it-bounded)
12. [Memory and caches](#12-memory-and-caches)
13. [Monitoring](#13-monitoring)
14. [Logs](#14-logs)
15. [Upgrades and rollback](#15-upgrades-and-rollback)
16. [Capacity](#16-capacity)
17. [Runbooks](#17-runbooks)

---

## 1. What you are running

Drishti is two processes. Users only ever talk to the console; the console talks to the server.

```
 browser ──HTTPS──▶ reverse proxy ──HTTP──▶ console (FastAPI, :17480) ──HTTP──▶ server (Spring Boot, :18480) ──▶ your sources
                                             sessions, pages, live channel        views, live streams, users,        Delta Lake, PostgreSQL,
                                                                                  Sutras, packs, governance          Aerospike, Kafka, ActiveMQ,
                                                                                                                     RabbitMQ, S3, files, REST, feeds
```

| Process | Default port | Health check | Metrics | Started by |
|---|---|---|---|---|
| `drishti-server` (Spring Boot, Java 21 or newer, production runs 21) | 18480 | `/actuator/health/liveness`, `/actuator/health/readiness` | `/actuator/prometheus` | `java -jar drishti-server-<version>-exec.jar` |
| `console` (FastAPI on uvicorn, Python) | 17480 | `/healthz` | none | `python drishti-console/run_drishti_web.py` |

Things worth knowing before you plan an installation:

- **Both processes keep no session state in memory that matters.** The console's sign-in session is a signed
  cookie, so any console instance can serve any user. You can run several of each behind a load balancer, as long
  as the server instances share the same `data/` directories (users, governance) and the same Sutra directories.
- **The server's working directory matters.** Every relative path in its configuration (`./packs`, `./sutras`,
  `./data/...`, `./application.local.yaml`) is resolved from the directory you start it in. Start it from the
  installation directory, or set the paths to absolute values.
- **The console finds its files relative to itself.** It reads `drishti-console/config/application.yaml`, and its default
  `packs.dir` (`../packs`) and `help.docs_dir` (`../docs`) are relative to the `drishti-console/` directory, not to the
  directory you start it in.
- **The server's `/api/v1/...` endpoints trust the caller completely when security is off.** With
  `drishti.security.enabled: false` (the development default) anyone who can reach port 18480 is an administrator.
  In any shared installation, turn security on (section 9) and do not expose port 18480 to users at all.

## 2. Prerequisites

| Need | Version | Why |
|---|---|---|
| OpenJDK | 21 (the build enforces it) | runs the server and builds it |
| Python | 3.13 (the console image uses `python:3.13-slim`) | runs the console |
| `uv` (recommended) | any recent | creates the console's virtual environment and runs the data tools with their libraries |
| Docker and Docker Compose | any recent | only for the container installation and the sample databases |
| Disk | a few GB, plus the lake, the Kafka disk cache (up to `disk-cache.max-gb`, 10 GB per stream connector) and each ActiveMQ or RabbitMQ connector's state store (budget `state.max-gb`, 10 GB each; plan about twice that) | an SSD for the state stores: they sync every write by default |
| Memory | 2 GB for the server is plenty for the sample packs; see section 12 for what grows | |

Check Java:

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
$JAVA_HOME/bin/java -version
```

You should see a line starting `openjdk version "25.`.

## 3. Install from a build (one machine)

This installs both processes under `/opt/drishti` on a Linux host. Adjust the paths to taste.

### 3.1 Build

From a checkout of the repository:

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
./mvnw -q clean package -DskipTests
ls drishti-server/target/*-exec.jar
```

You should see exactly one file, for example `drishti-server/target/drishti-server-1.18.0-exec.jar`.

> Use `clean`. Without it, `target/` keeps the jars of earlier versions, and both `java -jar
> drishti-server-*-exec.jar` and the server Dockerfile's `COPY drishti-server/target/drishti-server-*-exec.jar`
> then match several files: Java runs the first one alphabetically (the oldest), and Docker refuses the copy.

### 3.2 Lay out the installation

```bash
sudo useradd --system --home /opt/drishti drishti
sudo mkdir -p /opt/drishti/data /opt/drishti/sutras
sudo cp drishti-server/target/drishti-server-1.18.0-exec.jar /opt/drishti/drishti-server.jar
sudo cp -r packs console docs /opt/drishti/
sudo cp LICENSE CHANGELOG.md RELEASE_NOTES.md THIRD-PARTY-NOTICES.md /opt/drishti/   # the help centre shows them
sudo chown -R drishti:drishti /opt/drishti
```

The result:

```
/opt/drishti/
├── drishti-server.jar           the server
├── application.local.yaml       your site settings (optional; create it, see CONFIGURATION.md)
├── packs/                       domain packs (the server reads ./packs, the console ../packs)
├── sutras/                      your own Sutras (drishti.rachana.dirs, default ./sutras)
├── data/                        everything the server writes: users, governance, caches (section 10)
├── docs/                        rendered by the console's help centre
└── drishti-console/
    ├── run_drishti_web.py
    ├── config/application.yaml  console settings; put overrides in config/application.local.yaml
    └── .venv/                   created in the next step
```

### 3.3 Install the console's Python libraries

```bash
cd /opt/drishti
sudo -u drishti uv venv drishti-console/.venv
sudo -u drishti uv pip install --python drishti-console/.venv/bin/python -r drishti-console/requirements.txt
```

Without `uv`: `python3.13 -m venv drishti-console/.venv && drishti-console/.venv/bin/pip install -r drishti-console/requirements.txt`.

### 3.4 First start (by hand)

Open two terminals. In the first:

```bash
cd /opt/drishti
sudo -u drishti env JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 DRISHTI_PACKS=finance \
  /usr/lib/jvm/java-21-openjdk-amd64/bin/java -jar drishti-server.jar   # any JDK 21 or newer; on Java 25 you may add -XX:+UseCompactObjectHeaders (Java-25-only flag, about 10% less heap; Java 21 refuses to start with it)
```

You should see, after a few seconds, a line ending `Started DrishtiApplication in … seconds`, and on the very first
start a warning:

```
Created development admin 'drishti-dev-admin' with the default password. Change it before any shared use.
```

In the second terminal:

```bash
cd /opt/drishti
sudo -u drishti drishti-console/.venv/bin/python drishti-console/run_drishti_web.py
```

You should see `Uvicorn running on http://127.0.0.1:17480`.

### 3.5 Check it works

```bash
curl -s localhost:18480/actuator/health
curl -s localhost:18480/actuator/info
curl -s localhost:17480/healthz
```

You should see:

```
{"status":"UP","groups":["liveness","readiness"]}
{"build":{"artifact":"drishti-server","name":"drishti-server","time":"2026-09-30T23:03:19.273Z","version":"1.18.0","group":"com.ash.drishti"}}
{"status":"UP"}
```

Then open `http://localhost:17480` in a browser, type `TRD IRS-48213` on the command line and press Enter (this
is one of the `finance` pack's examples; the landing page lists the others). An interest rate swap with its legs,
cashflows and SOFR curve should appear.

`/healthz` on the console only says the console process is answering (liveness). `/readyz` also asks the server:
`{"status":"UP","server":"reachable"}`, or `503` with `"server":"unreachable"` while it cannot reach it. Point a load
balancer's readiness check at `/readyz` and a restart policy at `/healthz`.

### 3.6 Choosing packs

`DRISHTI_PACKS` is a comma-separated list of pack names (directories under `packs/`). Enabling a pack also enables
the packs it `extends`. For example `DRISHTI_PACKS=market-risk` brings in `market-data`, `trading` and
`banking-core` as well. The installed packs are:

```bash
ls /opt/drishti/packs
```

```
banking-core  climate-risk  counterparty-risk  economics  finance  genomics  liquidity-risk  logistics
market-data  market-risk  operational-risk  politics-society  retail-banking  trading
```

What each pack contains, and how to write your own, is in [PACKS.md](../guides/PACKS.md). Most packs read a Delta Lake under
`./data/delta`; section 5 shows how to generate sample data for them.

## 4. Install with Docker Compose

`deploy/compose.yaml` runs the server and the console as two containers. The images run as a non-root user
(uid 10001) and have health checks. The server image runs ZGC (generational) with `-XX:MaxRAMPercentage=75`, so it
uses up to three quarters of the container's memory limit.

### 4.1 Build the images

From the repository root:

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
./mvnw -q clean package -DskipTests
docker build -f deploy/server.Dockerfile  -t drishti-server:1.18.0 .   # base image eclipse-temurin:21-jre; --build-arg BASE_IMAGE=eclipse-temurin:25-jre for Java 25
docker build -f deploy/console.Dockerfile -t drishti-console:1.18.0 .
```

The server image contains the jar and `packs/`. The console image contains the console, `docs/`, `packs/` and the
licence and release files, so the help centre works without network access.

### 4.2 Provide the secrets

The compose file refuses to start without four values: two secrets and the first administrator. It has no default
password anywhere and does not create the development admin. Put them in `deploy/.env` (never commit it):

```bash
cat > deploy/.env <<EOF
DRISHTI_TOKEN_SECRET=$(openssl rand -hex 32)
DRISHTI_SESSION_SECRET=$(openssl rand -hex 32)
DRISHTI_ADMIN_USER=your-name
DRISHTI_ADMIN_PASSWORD=$(openssl rand -base64 18)
DRISHTI_PACKS=finance
EOF
chmod 600 deploy/.env
```

`DRISHTI_ADMIN_USER` and `DRISHTI_ADMIN_PASSWORD` become the one administrator created on the first start with an empty
user store (at least 10 characters, with a letter and a digit); create the others in **Admin → Users**. The
token secret is a master key, see [section 9a-1](#9a-1-the-token-secret-is-a-master-key).

`openssl rand -hex 32` prints 64 characters, which satisfies both minimums (32 bytes for the token secret, 32
characters for the session secret).

### 4.3 Start

```bash
docker compose -f deploy/compose.yaml up -d
docker compose -f deploy/compose.yaml ps
```

You should see both services `running`, and after about 30 seconds `(healthy)`. If you forgot a secret you get
`required variable DRISHTI_TOKEN_SECRET is missing a value: set DRISHTI_TOKEN_SECRET` (likewise for the other three).

### 4.4 What the compose file sets, and what you should add

| Service | Setting | Value in `compose.yaml` | Meaning |
|---|---|---|---|
| server | `DRISHTI_SECURITY_ENABLED` | `true` | every `/api/v1` call needs a token from the console |
| server | `DRISHTI_TOKEN_SECRET` | from `.env` | shared with the console |
| server | `DRISHTI_SEED_ADMIN`, `DRISHTI_SEED_USERNAME`, `DRISHTI_SEED_PASSWORD` | `true`, `DRISHTI_ADMIN_USER`, `DRISHTI_ADMIN_PASSWORD` from `.env` | the first administrator you chose; the well-known development admin is not created |
| server | `DRISHTI_METRICS_TOKEN` | from `.env`, empty if unset | the bearer token a Prometheus scrape uses; empty means only an admin token reads `/actuator` |
| server | `ports` | `127.0.0.1:18480:18480` | the server is reachable from this host only; the console reaches it over the compose network |
| server | `DRISHTI_PACKS` | `${DRISHTI_PACKS:-finance}` | packs to enable |
| server | volume `../sutras` → `/opt/drishti/sutras` (read-only) | | your own Sutras |
| server | volume `../data/feeds` → `/opt/drishti/data/feeds` (read-only) | | the file connector's folder |
| server | named volume `drishti-identity` → `/opt/drishti/data/identity` | | users, roles, audit, preferences |
| console | `DRISHTI_BACKEND_URL` | `http://server:18480` | where the server is |
| console | `DRISHTI_CONSOLE__PACKS__ENABLED` | the same packs | used only if the server cannot be asked |
| console | `DRISHTI_AUTH_ENABLED` | `true` | users must sign in |
| console | `DRISHTI_TOKEN_SECRET`, `DRISHTI_SESSION_SECRET` | from `.env` | |

The compose file is a starting point. Before you rely on it, add:

1. **A volume for governance.** Sutra proposals and their history live in `/opt/drishti/data/governance`. Without a
   volume they are lost when the container is recreated:
   ```yaml
         - drishti-governance:/opt/drishti/data/governance
   ```
   (and `drishti-governance: {}` under `volumes:`).
2. **The lake.** The packs read `./data/delta` (`DRISHTI_DELTA_ROOT`). Mount your lake there, read-only, or set
   `DRISHTI_DELTA_ROOT` to an `s3a://` location (section 11):
   ```yaml
         - /srv/lake:/opt/drishti/data/delta:ro
   ```
   Without it, the lake connectors find no tables and their views say *No data available*.
3. **The Kafka disk cache**, if you turn on a stream connector: a volume on `/opt/drishti/data/cache`, sized above
   `DRISHTI_STREAM_CACHE_GB`.
4. **TLS.** The console sets `Secure` cookies by default, which browsers send only over HTTPS (and to
   `http://localhost`). Put a TLS proxy in front (section 8). For a quick test from another machine over plain
   HTTP, add `DRISHTI_SECURE_COOKIE: "false"` to the console, and remove it afterwards.
5. **Port 18480** is published on the loopback address only (`127.0.0.1:18480:18480`), for checks from the host. In
   production remove that line: only the console needs to reach the server, over the compose network.
6. **The demo source** is on in the image (`DRISHTI_DEMO_ENABLED`): add `DRISHTI_DEMO_ENABLED: "false"` under the
   server's `environment` once your packs read your own stores.

### 4.5 Sign in

Open `http://localhost:17480`. Sign in as the `DRISHTI_ADMIN_USER` and `DRISHTI_ADMIN_PASSWORD` of your `.env`
(a manual start without them has the development admin `drishti-dev-admin` / `drishti-dev-admin123`; the console shows
a warning until you change that password). Then create real users under
**Admin → Users**; see [USER_MANAGEMENT.md](USER_MANAGEMENT.md).

## 5. Data stores, sample data and feeds

The banking packs (`banking-core`, `market-data`, `trading`, `market-risk`, `counterparty-risk`, and the packs built
on them) read one store per data domain: `reference`, `market`, `trading`, `risk`, `credit`, `collateral`. Delta
Lake is the default; PostgreSQL and Aerospike are one Spring profile away. Each command below is run from the
repository root.

### 5.1 Delta Lake (default)

```bash
# the banking packs: data/delta/<domain>/<kind>/business_date=…/ with ten business days of history
uv run --with deltalake --with pyarrow --with pyyaml python tools/packgen/banking/make_data.py --lake data/delta

# the finance pack's own lake (domain "finance")
uv run --with deltalake --with pyarrow --with pyyaml python tools/samplegen/lake.py \
    --samples packs/finance/samples --root data/delta --domain finance --days 10
```

The other generated packs write their lakes the same way, for example
`uv run --with deltalake --with pyarrow --with pyyaml python tools/packgen/climate/make.py --lake data/delta`
(also `genomics`, `politics`, `liquidity`, `oprisk`, `economics`, `retail`). Note that these generators also rewrite
the pack's own files under `packs/`.

### 5.2 PostgreSQL, Aerospike and Kafka in Docker

`deploy/compose.data.yaml` starts PostgreSQL 18, a single-node Kafka 3.9 broker and Aerospike Community Edition:

```bash
docker compose -f deploy/compose.data.yaml up -d
tools/load-postgres.sh jdbc:postgresql://localhost:5432/drishti
tools/load-aerospike.sh localhost:3000 test
```

Then start the server with the profile for the store:

```bash
SPRING_PROFILES_ACTIVE=postgres  DRISHTI_PACKS=market-risk,counterparty-risk java -jar drishti-server.jar
SPRING_PROFILES_ACTIVE=aerospike DRISHTI_PACKS=market-risk,counterparty-risk java -jar drishti-server.jar
```

The `postgres` profile (`application-postgres.yaml`, inside the jar) points each `<domain>-store` connector at the
table `<domain>.entities`; the `aerospike` profile points them at a set per domain. The packs still decide the
kinds and routes; the profile changes only the store.

**Aerospike at scale, and retention.** Each domain is three sets: `<domain>` (a record per entity per business date,
the document and the pack's promoted fields as bins), `<domain>_ix` (a record per entity listing its dates) and
`<domain>_kinds` (the kinds' dates). Add a bulk book and a retention period to the load:

```bash
tools/load-aerospike.sh localhost:3000 test --trades 1000000 --days 3 --ttl-days 730
```

`--trades N --days D` streams `bulk_trades.py` straight into the loader (no file in between). `--ttl-days N` gives
every record written a time to live of N days, so Aerospike expires old business dates by itself: there is no
maintenance job, unlike the lake ([section 11](#11-the-lake-where-it-lives-and-keeping-it-bounded)). Without it,
records never expire. In the generated book a day record takes about 7 KB of namespace storage and an index record
about 150 bytes (`asinfo -v sets/test/trading` and `sets/test/trading_ix`: `data_used_bytes` over `objects`); size
the namespace for trades × days kept. Drishti's memory does not grow with the days kept: it holds one id per entity in the index set and the
days of promoted bins in use (section 12). See
[AEROSPIKE_CONNECTOR.md](../connectors/AEROSPIKE_CONNECTOR.md) for the design.

### 5.3 Live trades from Kafka

```bash
uv run --with kafka-python python tools/samplegen/stream.py --rate 5          # 5 trades a second to drishti.trading.trades
DRISHTI_STREAM_TRADING=true DRISHTI_PACKS=trading java -jar drishti-server.jar
```

`stream.py` also takes `--bootstrap` (default `localhost:9092`), `--topic`, `--samples` and `--seconds` (0 runs until
stopped).

### 5.4 Public feeds

The `market-data` pack has five public feeds, each off by default and refreshed every 60 minutes once on:

| Variable | Connector | Kind | Needs |
|---|---|---|---|
| `DRISHTI_FEED_NYFED_SOFR=true` | `nyfed-sofr-feed` | `rate-fixing` | internet access |
| `DRISHTI_FEED_ECB_ESTR=true` | `ecb-estr-feed` | `rate-fixing` | internet access |
| `DRISHTI_FEED_ECB_FX=true` | `ecb-fx-feed` | `fx-spot` | internet access |
| `DRISHTI_FEED_US_TREASURY=true` | `us-treasury-feed` | `ir-curve` | internet access |
| `DRISHTI_FEED_FRED=true` | `fred-feed` | `rate-fixing` | `FRED_API_KEY`; series from `DRISHTI_FRED_SERIES` (default `DGS10,DFF`) |

A feed that fails keeps its last good data and shows as down in **Admin → Health**; the rest of Drishti keeps
working.

## 6. Environment variables

Every variable below is a placeholder in a configuration file, written `${NAME:default}`. If you do not set it, the
default after the colon applies. Variables are read at start-up; change one and restart the process.

### 6.1 Server: core

Defined in `drishti-server/src/main/resources/application.yaml`.

| Variable | Default | Setting | What it does |
|---|---|---|---|
| `DRISHTI_PORT` | `18480` | `server.port` | HTTP port of the server |
| `DRISHTI_SECURITY_ENABLED` | `false` | `drishti.security.enabled` | require a bearer token on every `/api/v1` call; **must be `true` in production** |
| `DRISHTI_TOKEN_SECRET` | empty | `drishti.security.secret` | HS256 key the console signs tokens with; at least 32 bytes; the same value on the console. The server refuses to start with security on and a shorter secret |
| `DRISHTI_OIDC_ENABLED` | `false` | `drishti.security.oidc.enabled` | accept single sign-on ID tokens |
| `DRISHTI_OIDC_ISSUER` | empty | `drishti.security.oidc.issuer` | the provider's issuer URL (https) |
| `DRISHTI_OIDC_CLIENT_ID` | empty | `drishti.security.oidc.client-id` | the client id the ID token is issued to |
| `DRISHTI_CALENDAR` | `USNY` | `drishti.business-date.calendar` | holiday calendar for the default business date: `USNY`, `GBLO`, `EUTA`, `JPTO`, or joint such as `USNY+GBLO` |
| `DRISHTI_PACKS` | `finance` | `drishti.packs.enabled` | comma-separated packs to enable |
| `DRISHTI_PACKS_OVERLAY` | `./data/packs/added.yaml` | `drishti.packs.overlay` | packs loaded from Admin → Packs (written by the server, read at every start); back it up with `data/` |
| `DRISHTI_PACKS_DIR` | `./packs` | `drishti.packs.dir` | where the packs are. The console reads the same variable, but resolves a relative value from `drishti-console/`; use an absolute path if you set it |
| `DRISHTI_DEFAULT_PACKS` | empty (every installed pack) | `drishti.packs.default-for-users` | packs new users get until an admin changes them |
| `DRISHTI_SUTRAS` | `./sutras` | `drishti.rachana.dirs` | your own Sutra directories, scanned recursively, in addition to the packs' Sutras |
| `DRISHTI_STUDIO_SAVE` | `false` | `drishti.rachana.studio-save` | let Sutra Studio save files (into the first Sutra directory); only in authoring environments |
| `DRISHTI_SUTRA_REVIEW` | `true` | `drishti.governance.enabled` | a Studio save becomes a proposal that an approver must approve |
| `DRISHTI_SUTRA_FOUR_EYES` | `true` | `drishti.governance.four-eyes` | nobody approves their own proposal (with security on) |
| `DRISHTI_GOVERNANCE_DIR` | `./data/governance` | `drishti.governance.dir` | proposals and their history; back it up |
| `DRISHTI_PLUGIN_DIR` | empty | `drishti.sources.plugin-dir` | a directory of extra plugin jars, each loaded in its own class loader |

### 6.2 Server: users

| Variable | Default | What it does |
|---|---|---|
| `DRISHTI_SEED_ADMIN` | `true` | on first start with no users, create `drishti-dev-admin` / `drishti-dev-admin123`. Set `false` in production once you have a real admin |
| `DRISHTI_SEED_USERNAME`, `DRISHTI_SEED_PASSWORD` | `drishti-dev-admin`, `drishti-dev-admin123` | the administrator that first start creates: set both to choose your own instead of the development admin |
| `DRISHTI_FORCE_PW_CHANGE_ON_CREATE` | `false` | new users must change their password at first sign-in |
| `DRISHTI_FORCE_PW_CHANGE_ON_RESET` | `false` | users must change a password an admin reset |
| `DRISHTI_USERS_FILE`, `DRISHTI_AUDIT_FILE` | `./data/identity/users.json`, `./data/identity/audit.jsonl` | release 1.9 keeps users and the audit log in these files. From 1.10 they are only read once, to import them into the identity database |
| `DRISHTI_IDENTITY_DB_URL` | `jdbc:sqlite:./data/identity/drishti.db` | from 1.10: the identity database (users, roles, audit, saved per-user documents). SQLite by default, or `jdbc:postgresql://...` |
| `DRISHTI_IDENTITY_DB_USER`, `DRISHTI_IDENTITY_DB_PASSWORD` | empty | from 1.10: its credentials (PostgreSQL) |

Everything about users, roles and the identity database is in [USER_MANAGEMENT.md](USER_MANAGEMENT.md).

### 6.3 Server: built-in plugins

| Variable | Default | What it does |
|---|---|---|
| `DRISHTI_DEMO_ENABLED` | `true` | the demo source (synthetic ticking entities). Turn off in production |
| `DRISHTI_FEEDS` | `./data/feeds` | the file connector's root folder (rescanned every 30 s) |
| `DRISHTI_REST_ENABLED` / `DRISHTI_REST_URL` | `false` / `http://localhost:9000/api` | the REST connector and its base URL (it reads `<base>/{kind}/{id}`) |
| `DRISHTI_JDBC_ENABLED` | `false` | the single JDBC connector |
| `DRISHTI_JDBC_URL`, `DRISHTI_JDBC_USER`, `DRISHTI_JDBC_PASSWORD` | empty | its connection |
| `DRISHTI_ACTIVEMQ_ENABLED` | `false` | the ActiveMQ plugin (configure its settings in `application.local.yaml`; see [CONNECTOR_DEVELOPER_GUIDE.md](../connectors/CONNECTOR_DEVELOPER_GUIDE.md)) |
| `DRISHTI_RABBITMQ_ENABLED` | `false` | the RabbitMQ plugin (likewise) |
| `DRISHTI_S3_ENABLED` | `false` | the S3 document plugin (likewise) |

### 6.4 Server: pack connectors

These are read by the packs' `pack.yaml` files (`connectors:` sections).

| Variable | Default | Used by | What it does |
|---|---|---|---|
| `DRISHTI_LAKE_ENABLED` | `true` | every pack's Delta connector | `false` turns the lake connectors off (for example when a profile replaces them) |
| `DRISHTI_DELTA_ROOT` | `./data/delta` | every pack's Delta connector | the lake root: a local directory or `s3a://bucket/path` |
| `DRISHTI_STREAM_TRADING` | `false` | `trading` pack, `trading-stream` | read live trades from Kafka |
| `DRISHTI_KAFKA_BOOTSTRAP` | `localhost:9092` | `trading-stream` | Kafka bootstrap servers |
| `DRISHTI_TRADING_TOPIC` | `drishti.trading.trades` | `trading-stream` | the topic |
| `DRISHTI_STREAM_STALE_AFTER` | `15m` | `trading-stream` | with no new trade for this long the stream is shown as behind (`stale-after`) |
| `DRISHTI_STREAM_DISK_CACHE` | `true` | `trading-stream` | keep the day's messages in a local RocksDB disk cache |
| `DRISHTI_CACHE_ROOT` | `./data/cache` | `trading-stream` | parent folder of the disk caches (one sub-folder per connector) |
| `DRISHTI_STREAM_CACHE_GB` | `10` | `trading-stream` | disk cache size limit; oldest files are dropped beyond it |
| `DRISHTI_CACHE_RESET_AT` | `02:00` | `trading-stream` | the disk cache is cleared every night at this time (New York) |
| `DRISHTI_FEED_NYFED_SOFR`, `DRISHTI_FEED_ECB_ESTR`, `DRISHTI_FEED_ECB_FX`, `DRISHTI_FEED_US_TREASURY`, `DRISHTI_FEED_FRED` | `false` | `market-data` | the public feeds (section 5.4) |
| `DRISHTI_FRED_SERIES` | `DGS10,DFF` | `fred-feed` | FRED series to fetch |
| `FRED_API_KEY` | empty | `fred-feed` | your FRED API key |

### 6.5 Server: database profiles

With `SPRING_PROFILES_ACTIVE=postgres` or `aerospike` (section 5.2):

| Variable | Default | What it does |
|---|---|---|
| `DRISHTI_PG_URL` | `jdbc:postgresql://localhost:5432/drishti` | PostgreSQL holding the `<domain>.entities` tables |
| `DRISHTI_PG_USER` / `DRISHTI_PG_PASSWORD` | `drishti` / `drishti` | its credentials |
| `DRISHTI_AEROSPIKE_HOSTS` | `localhost:3000` | Aerospike seed hosts |
| `DRISHTI_AEROSPIKE_NAMESPACE` | `test` | the namespace |

### 6.6 Console

Defined in `drishti-console/config/application.yaml`.

| Variable | Default | Setting | What it does |
|---|---|---|---|
| `DRISHTI_CONSOLE_HOST` | `127.0.0.1` | `server.host` | interface the console listens on. Keep `127.0.0.1` behind a proxy on the same host; the container image sets `0.0.0.0` |
| `DRISHTI_CONSOLE_PORT` | `17480` | `server.port` | port |
| `DRISHTI_BACKEND_URL` | `http://127.0.0.1:18480` | `backend.url` | the server |
| `DRISHTI_AUTH_ENABLED` | `false` | `auth.enabled` | require sign-in; **`true` in production** |
| `DRISHTI_SESSION_SECRET` | empty | `auth.session_secret` | signs session cookies; at least 32 characters (the console refuses to start otherwise when auth is on) |
| `DRISHTI_TOKEN_SECRET` | empty | `auth.token_secret` | the same value as the server's |
| `DRISHTI_SECURE_COOKIE` | `true` | `auth.secure_cookie` | mark cookies `Secure`; set `false` only for plain HTTP tests |
| `DRISHTI_SESSION_RECHECK_SECONDS` | `10` | `auth.recheck_seconds` | how long the console reuses the server's answer to "does this session still stand, and who is the user now?": disabling, demoting or signing out takes effect within this time (at once on the console an administrator used); `0` asks on every request |
| (none) | `[]` | `auth.allowed_origins` | extra origins allowed to send state-changing requests, for a proxy that changes the `Host` header (section 8.4) |
| `DRISHTI_OIDC_ENABLED`, `DRISHTI_OIDC_ISSUER`, `DRISHTI_OIDC_CLIENT_ID` | `false`, empty, empty | `auth.oidc.*` | single sign-on; the same values as the server's |
| `DRISHTI_OIDC_CLIENT_SECRET` | empty | `auth.oidc.client_secret` | the client secret (empty for a public client using PKCE alone) |
| `DRISHTI_OIDC_REDIRECT_URI` | empty (`<console>/auth/oidc/callback`) | `auth.oidc.redirect_uri` | set it to the public HTTPS URL when the console is behind a proxy |
| `DRISHTI_PACKS_DIR` | `../packs` (relative to `drishti-console/`) | `packs.dir` | the packs (for guides and examples) |
| `DRISHTI_USER` | `ash` | `ui.user` | the acting user when sign-in is off (development only) |

### 6.7 Docker Compose only

| Variable | Default | File | What it does |
|---|---|---|---|
| `DRISHTI_PG_USER`, `DRISHTI_PG_PASSWORD`, `DRISHTI_PG_PORT` | `drishti`, `drishti`, `5432` | `compose.data.yaml` | the PostgreSQL container |
| `DRISHTI_KAFKA_PORT` | `9092` | `compose.data.yaml` | the Kafka port (also the advertised listener) |
| `DRISHTI_AEROSPIKE_PORT` | `3000` | `compose.data.yaml` | the Aerospike port |

### 6.8 Any other setting

Any server setting without its own variable can still be set from the environment with Spring's relaxed names:
upper case, dots and dashes become underscores, and dashes inside a word are dropped. For example:

```bash
DRISHTI_LIVE_MAXSTREAMS=40000                 # drishti.live.max-streams
DRISHTI_SOURCES_FETCHTIMEOUT=3s               # drishti.sources.fetch-timeout
SERVER_ADDRESS=127.0.0.1                      # bind the server to loopback only
LOGGING_LEVEL_COM_ASH_DRISHTI=DEBUG           # log level for Drishti's own classes
```

Or pass `--drishti.live.max-streams=40000` on the command line. Console settings follow
`DRISHTI_CONSOLE__<SECTION>__<KEY>`, for example `DRISHTI_CONSOLE__BACKEND__TIMEOUT_SECONDS=10`, or
`--backend.timeout_seconds=10`. The order of precedence and every setting are in
[CONFIGURATION.md](CONFIGURATION.md).

## 7. Running as services (systemd)

### 7.1 An environment file

Keep secrets out of unit files. Create `/etc/drishti/drishti.env`, owned by root, mode `0600`:

```bash
sudo mkdir -p /etc/drishti
sudo tee /etc/drishti/drishti.env >/dev/null <<EOF
# shared
DRISHTI_TOKEN_SECRET=$(openssl rand -hex 32)
# server
DRISHTI_SECURITY_ENABLED=true
DRISHTI_PACKS=market-risk,counterparty-risk
DRISHTI_DEMO_ENABLED=false
SERVER_ADDRESS=127.0.0.1
# console
DRISHTI_AUTH_ENABLED=true
DRISHTI_SESSION_SECRET=$(openssl rand -hex 32)
DRISHTI_BACKEND_URL=http://127.0.0.1:18480
EOF
sudo chmod 600 /etc/drishti/drishti.env
```

Both processes may read the same file: each ignores the other's variables.

### 7.2 The server

`/etc/systemd/system/drishti-server.service`:

```ini
[Unit]
Description=Drishti server
After=network-online.target
Wants=network-online.target

[Service]
User=drishti
WorkingDirectory=/opt/drishti
EnvironmentFile=/etc/drishti/drishti.env
Environment=JAVA_TOOL_OPTIONS=-Xmx4g -XX:+UseZGC -XX:+ZGenerational
ExecStart=/usr/lib/jvm/java-21-openjdk-amd64/bin/java -jar /opt/drishti/drishti-server.jar
SuccessExitStatus=143
TimeoutStopSec=45
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
```

- `WorkingDirectory` matters: `./packs`, `./data`, `./sutras` and `./application.local.yaml` are found from it.
- The server shuts down gracefully (`server.shutdown: graceful`): it stops taking requests and lets running ones
  finish, for up to 30 seconds; `TimeoutStopSec=45` gives it that time. Exit code 143 is a normal stop on SIGTERM.
- Without `-Xmx` the JVM takes a quarter of the machine's memory as its heap ceiling.

### 7.3 The console

`/etc/systemd/system/drishti-console.service`:

```ini
[Unit]
Description=Drishti console
After=network-online.target drishti-server.service
Wants=drishti-server.service

[Service]
User=drishti
WorkingDirectory=/opt/drishti/console
EnvironmentFile=/etc/drishti/drishti.env
ExecStart=/opt/drishti/drishti-console/.venv/bin/python /opt/drishti/drishti-console/run_drishti_web.py
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
```

### 7.4 Start and check

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now drishti-server drishti-console
systemctl status drishti-server --no-pager | head -3
curl -s localhost:18480/actuator/health/readiness
```

You should see `Active: active (running)` and `{"status":"UP"}`. Follow the logs with
`journalctl -u drishti-server -f` (section 14).

The lake maintenance job can run as a third unit; see section 11.3.

## 8. TLS and the reverse proxy

Terminate TLS at a reverse proxy in front of the **console**. Users never need the server directly.

### 8.1 What to expose

| Path | Expose to users? | Why |
|---|---|---|
| console `:17480`, everything | yes, through the proxy | the whole user interface, including its live channel |
| server `:18480/api/v1/...` | no | the console calls it; with security off it trusts anyone |
| server `:18480/actuator/...` | no; only to your monitoring network | with security on, only `/actuator/health` is open; the rest needs an admin token or `DRISHTI_METRICS_TOKEN` |
| server `:18480/api/docs`, `/api/docs/ui` | no (developers only) | the OpenAPI description and Swagger UI; with security on they need a token (any user), with security off they are open |

To serve the actuator on a separate port that only monitoring can reach, set Spring's standard
`MANAGEMENT_SERVER_PORT=18481`.

### 8.2 Live updates through a proxy

Each browser holds one long-lived Server-Sent Events connection to the console (`/api/channel`), shared by all its
tabs and workspace panes (from a SharedWorker, or from one tab elected by the others). For it to work through a
proxy:

- the proxy must **not buffer** responses (the console sends `X-Accel-Buffering: no`, which nginx honours);
- the proxy's **read timeout** must be well above the heartbeat (the console sends a comment line about every 15
  seconds when nothing changes);
- use HTTP/1.1 to the console.

### 8.3 nginx example

```nginx
server {
    listen 443 ssl http2;
    server_name drishti.bank.example;
    ssl_certificate     /etc/ssl/drishti/fullchain.pem;
    ssl_certificate_key /etc/ssl/drishti/privkey.pem;

    location / {
        proxy_pass         http://127.0.0.1:17480;
        proxy_http_version 1.1;
        proxy_set_header   Host              $host;
        proxy_set_header   X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header   X-Forwarded-Proto $scheme;
        proxy_set_header   Connection        "";
        proxy_buffering    off;          # live updates
        proxy_read_timeout 1h;           # long-lived event streams
    }
}

server {
    listen 80;
    server_name drishti.bank.example;
    return 301 https://$host$request_uri;
}
```

Check from outside:

```bash
curl -sI https://drishti.bank.example/healthz | head -1
```

You should see `HTTP/2 200`.

### 8.4 Cookies, CSP and single sign-on behind a proxy

- **Cookies** are `Secure` by default. Keep `DRISHTI_SECURE_COOKIE` unset (true) once TLS is in place. The session
  cookie is `HttpOnly` and `SameSite=Lax`, and holds only a signed reference to a session the server keeps: signing
  out (a `POST`), or disabling, deleting or resetting the password of the user, ends it on the server.
- **Cross-site requests.** Besides `SameSite=Lax` (which does not separate sibling subdomains), the console refuses a
  state-changing request (`POST`, `PUT`, `PATCH`, `DELETE`) whose `Origin` header (or, without one, `Referer`) is not
  the console itself (`403 DRS-5002`), and refuses JSON not sent as `application/json` (`415`). It compares the origin
  with the request's `Host` header, so keep `proxy_set_header Host $host;` (as in 8.3); if your proxy changes the host,
  list the public origin in `auth.allowed_origins` (for example `[https://drishti.bank.example]`) in
  `drishti-console/config/application.local.yaml`.
- **Content security policy** is strict and set by the console (`script-src 'self'`, no inline scripts or styles).
  Do not add or loosen a CSP at the proxy. Every asset is vendored, so the console works with no internet access.
- **Single sign-on**: set `DRISHTI_OIDC_REDIRECT_URI=https://drishti.bank.example/auth/oidc/callback` and register
  exactly that URL with the identity provider. Without it the console builds the URL from the request, which behind
  a proxy may come out as `http://127.0.0.1:17480/...`.

## 9. Production checklist

Work through this list for every shared installation. Each item says how to check it.

1. **Server security on.** `DRISHTI_SECURITY_ENABLED=true` and `DRISHTI_TOKEN_SECRET` at least 32 bytes.
   Check: `curl -s localhost:18480/api/v1/sources` answers
   `{"title":"unauthenticated","status":401,"code":"DRS-5010","detail":"missing bearer token"}`.
2. **Console sign-in on.** `DRISHTI_AUTH_ENABLED=true`, `DRISHTI_SESSION_SECRET` at least 32 characters, and the
   same `DRISHTI_TOKEN_SECRET` as the server. Check: a private browser window opening the console is sent to the
   sign-in page. Sessions follow the user: disabling or demoting someone takes effect within `auth.recheck_seconds`
   (10 s by default), and signing out ends the session on the server.
3. **The development admin is gone.** The compose file never creates it (it seeds the administrator of
   `DRISHTI_ADMIN_USER` / `DRISHTI_ADMIN_PASSWORD`). On any other install sign in as `drishti-dev-admin`, create your
   own admin, then either change the development admin's password or disable it, and set `DRISHTI_SEED_ADMIN=false`
   (or give `DRISHTI_SEED_USERNAME` and `DRISHTI_SEED_PASSWORD` for the first start). Check: the console no longer
   shows the default-password warning (the server reports `"defaultAdminPasswordInUse": false` at
   `/api/v1/admin/status`).
4. **Single sign-on (optional).** `DRISHTI_OIDC_ENABLED=true`, `DRISHTI_OIDC_ISSUER` (https) and
   `DRISHTI_OIDC_CLIENT_ID` on both processes, `DRISHTI_OIDC_CLIENT_SECRET` and `DRISHTI_OIDC_REDIRECT_URI` on the
   console, and the group-to-role map (`drishti.security.oidc.role-map`) on the server. See
   [USER_MANAGEMENT.md](USER_MANAGEMENT.md).
5. **Roles.** Review `drishti.security.roles` and what the packs add. Keep `drishti.security.redact` listing the
   fields that roles without raw access must not see: they read `•••` everywhere (raw JSON, search, views, Impact,
   type-ahead, alerts; [CONFIGURATION.md](CONFIGURATION.md#field-masks)). List every field that holds the value,
   since a mask hides only the field it names.
6. **Authoring.** `DRISHTI_STUDIO_SAVE=false` except in an authoring environment. Keep `DRISHTI_SUTRA_REVIEW=true`
   and `DRISHTI_SUTRA_FOUR_EYES=true` so every change is approved by a second person.
7. **Demo source off.** `DRISHTI_DEMO_ENABLED=false`.
8. **Network.** Only the proxy reaches the console; only the console reaches the server's `/api`; only monitoring
   reaches `/actuator`. `SERVER_ADDRESS=127.0.0.1` when they share a host.
9. **TLS** at the proxy, `DRISHTI_SECURE_COOKIE` left at `true`.
10. **Backups** of `data/identity`, `data/governance`, your Sutra directories and your configuration (section 10),
    and of `data/state/<connector>` if you run ActiveMQ or RabbitMQ connectors (their only copy of what the queues
    delivered; copied with the server stopped).
11. **Lake maintenance** scheduled (section 11).
12. **Monitoring**: Prometheus scraping `/actuator/prometheus` with `bearer_token: <DRISHTI_METRICS_TOKEN>` (with security on, every
    `/actuator` endpoint but health needs it, or an admin token; `/api/docs` needs any token), the Grafana dashboard imported, alerts set
    (section 13).
13. **Secrets** only in a `0600` environment file or your secret store; never in `application.yaml` or a repository.
    No password in a sample is a password to keep: `DRISHTI_PG_PASSWORD` (the development stack
    `deploy/compose.data.yaml` uses `drishti` and binds `127.0.0.1` only; a shared database needs its own),
    `DRISHTI_SEED_PASSWORD`, `DRISHTI_METRICS_TOKEN` (empty means only an admin token reads `/actuator`).
14. **The token secret** is protected and rotated as a master key (next section).
15. **Published ports.** `docker compose ps` shows no `0.0.0.0:18480`; the server's port is not reachable from outside.

## 9a-1. The token secret is a master key

`DRISHTI_TOKEN_SECRET` signs every token the console hands the server (HS256), and the server accepts **any token
signed with it**. That is by design, and it means the secret is not a password for one service: whoever holds it can
mint a token for any user name (even one that does not exist) with any role, `admin` included, and the server will
believe it. It does not need the console, a user account or a sign-in. Treat it like a database root password.

- **Protect it.** Generate it with `openssl rand -hex 32`; keep it in a `0600` environment file or a secret store, on
  the two hosts that need it (the server and the console), never in `application.yaml`, an image, a repository, a
  chat or a ticket. Do not reuse it across environments (a test secret must not open production). Keep the server's
  `/api` unreachable except from the console (section 8), so a leaked token cannot be used from elsewhere.
- **Rotate it** on a schedule you choose (every 90 days is common), whenever someone who knew it leaves, and at once
  after any suspicion of a leak: 1) generate a new value; 2) put it in the server's and the console's environment;
  3) restart both together (the server first or at the same time). Every token signed with the old secret stops
  working at the restart: users sign in again (their sessions end), and a client that minted its own tokens needs the
  new secret. Personal API tokens (`drk_...`) are not signed with it and keep working; revoke them separately in
  **Admin → Tokens** if the leak could have reached them. There is no overlap window for two secrets: plan the
  restart for a quiet moment.
- **Limit the damage.** Tokens carry an expiry (the console mints short ones), so a captured token ages out; a leaked
  secret does not. Review **Admin → Audit log** after a suspected leak: actions are recorded under the user name in the
  token, including one that was made up.
- **Alternatives that avoid sharing one secret.** Single sign-on (OIDC, section 9 item 4) moves who may sign in to
  your identity provider, though the console and server still share the signing secret between them. For a client that
  needs to read, use a personal API token (`drk_...`), which acts as one real user, only reads, and is revoked on its
  own; for machine access put a gateway in front that authenticates the caller and mints nothing. Splitting the
  secret per environment, and a vault that hands it to both processes at start, are the practical hardening steps
  today.

## 9a. Scheduled reports

People schedule searches to be delivered as CSV (User guide, *Scheduled reports*). What operators decide:

- **Where files go.** `DRISHTI_REPORTS_DIR` (default `./data/reports`), one folder per user and report. Nothing
  prunes it: rotate it with your usual tools (`find data/reports -mtime +30 -delete`).
- **Which webhooks are allowed.** `drishti.reports.webhooks` lists URL prefixes; empty (the default) means none.
- **Which server schedules.** With several servers on one identity database, set `DRISHTI_REPORTS_ENABLED=false` on
  all but one, or each report runs once per server.
- **What is audited.** `report.save`, `report.run` (with `ok` or `failed`) and `report.delete`. Admin → Health is
  not affected by a failing report; its owner sees the error on the Reports page.

## 9a-2. Collaboration email and the outbox

Shares can also be emailed ([COLLABORATION.md](../architecture/COLLABORATION.md)). It is off until you switch it on, and it never carries a
data value.

**Switch it on** (all three, or it stays off): `drishti.security.enabled: true` (the server refuses to start with email on and sign-in off),
`drishti.collab.console-url` (where links point) and an SMTP server:

```bash
export DRISHTI_CONSOLE_URL=https://drishti.example.com
export DRISHTI_MAIL_FROM=drishti@example.com
export SPRING_MAIL_HOST=smtp.example.com SPRING_MAIL_PORT=587 SPRING_MAIL_USERNAME=drishti SPRING_MAIL_PASSWORD=...
export SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_REQUIRED=true
export SPRING_MAIL_PROPERTIES_MAIL_SMTP_CONNECTIONTIMEOUT=10000 SPRING_MAIL_PROPERTIES_MAIL_SMTP_TIMEOUT=10000
export DRISHTI_COLLAB_EMAIL_ENABLED=true
```

Then send yourself a test: `POST /api/v1/admin/collab/mail-test` mails the signed-in administrator's own address and answers
`503 DRS-7012` with the SMTP reason when it cannot.

**What is sent.** For each person a share reaches who has an address (`User.email`, set by an administrator or the identity provider; people cannot
type addresses) and has not turned share mail off, one message: the sender's name, the kind and id, the panel and date, the note as that person
may read it (masked values `•••`, `{$.path}` quotes filled from the recipient's own view) and the link. Emails carry values by design (decision 2026-10-05): a pack that must not sends `link-only`. A pack whose identifiers are sensitive sets `drishti.collab.packs.<pack>.email.content: link-only`.
The message is built when it is sent, so a person whose role was removed since gets nothing (the row is *cancelled*). Several notices for the same person inside `email.coalesce-window` (default 10 seconds, a short first-send delay for a lone notice; `0` = off) go out as one digest email, each notice
still checked for opt-outs, rights and masking; so mail is sent up to that long after the event. People are not emailed about
their own shares, and no more than `limits.mails-per-recipient-per-hour` an hour.

**The outbox.** The row is written in the share's own transaction; a worker on each server (`outbox.enabled`) claims due rows under a lease, so
several servers never send one twice (after a crash between sending and recording, one repeat is possible; its `Message-ID` is fixed, so mail
clients fold it). A failure retries after `outbox.backoff`, doubling to `outbox.max-backoff`; a refusal by the server (SMTP 5xx, a malformed address)
or the `outbox.max-attempts`th failure makes a **dead letter**. Sent rows are deleted after `outbox.keep-sent-days`.

**Looking and fixing.**

```bash
curl -s -H "Authorization: Bearer $ADMIN" 'http://localhost:18480/api/v1/admin/collab/outbox?state=dead'        # counts and rows
curl -s -X POST -H "Authorization: Bearer $ADMIN" http://localhost:18480/api/v1/admin/collab/outbox/42/retry   # send a dead letter again
```

Rows show the recipient, template, share id, attempts, next attempt and the last error, never the message. `state` is `pending`, `sending`, `sent`,
`dead` or `cancelled`. Alert on `drishti.collab.outbox{state="dead"} > 0` and on a growing `state="pending"`. The usual causes of dead letters are in
[TROUBLESHOOTING.md](../guides/TROUBLESHOOTING.md#the-email-never-arrived). An outage of the mail server needs no action: rows stay pending and go out
when it returns.

## 9a-3. Collaboration retention, legal holds and the compliance export

Nothing in the collaboration record is destroyed by default (`retention.keep-days: 0`). Comments are never deleted by their authors or by
moderators: retract and hide change what readers see, and every revision stays. Only retention, or an administrator removing a whole thread,
destroys anything, and neither touches what a legal hold covers.

**Retention.** Set days in `application.yaml`, most specific first: `drishti.collab.retention.kinds.<kind>`, then
`drishti.collab.packs.<pack>.retention-days` for the pack that owns the kind, then `retention.keep-days`. A daily job
(`retention.interval`; it starts only when some retention above 0 is set) removes whole threads whose last activity, and shares whose creation,
is older than that, and writes one audit event each (`collab.purge.thread` with the thread's final hash, `collab.purge.share`). A thread goes
whole or not at all. Several servers on one database may each run it; it is idempotent. Look before you leap, and run it by hand:

```bash
curl -s -X POST -H "Authorization: Bearer $ADMIN" 'http://localhost:18480/api/v1/admin/collab/retention/run?dryRun=true'
# {"dryRun":true,"threadsPurged":12,"sharesPurged":30,"threadsHeld":2,"sharesHeld":1}   "held" = past retention but kept by a hold
```

Everything in this section can also be done in the console, on **Admin > Collaboration** (`/admin/collab`; the *Mail outbox* section lists pending mail with tries, next try and last error, dead letters with *Send again*, and sends a test mail; a share that asks for email while mail is failing says so): holds (place, list, release), the export
(start, wait, download once), verify and the retention dry run (the page only ever asks for the dry run; real runs stay the server's schedule). The
calls below are what the page makes.

**Legal holds** (the `compliance` role power; an administrator is not enough). A hold has a scope (`entity` = kind and id, `kind`, `user` =
everything the person wrote, sent or received, `thread`, or `all`), a reason, and an optional date range (a thread is covered when its activity
overlaps it, a share when it was sent inside it). Place and release are audited; a released hold stays in the list.

```bash
curl -s -X POST -H "Authorization: Bearer $COMPLIANCE" -H 'Content-Type: application/json' http://localhost:18480/api/v1/admin/collab/holds \
  -d '{"scope":"entity","kind":"trade","id":"MX-20000001","from":"2026-09-01","to":"2026-09-30","reason":"case 4411"}'
curl -s -H "Authorization: Bearer $COMPLIANCE" 'http://localhost:18480/api/v1/admin/collab/holds?active=true'
curl -s -X DELETE -H "Authorization: Bearer $COMPLIANCE" http://localhost:18480/api/v1/admin/collab/holds/7     # release
```

An administrator's permanent removal of one thread (`DELETE /admin/collab/threads/{id}`) answers `423 DRS-7010` while a hold covers it.

**Export (eDiscovery).** An export is a job: ask, poll, download once.

```bash
curl -s -X POST -H "Authorization: Bearer $COMPLIANCE" -H 'Content-Type: application/json' http://localhost:18480/api/v1/admin/collab/exports \
  -d '{"from":"2026-09-30","to":"2026-09-30","kind":"trade","id":"MX-20000001"}'          # 202 {"id":"…","state":"queued"}
curl -s -H "Authorization: Bearer $COMPLIANCE" http://localhost:18480/api/v1/admin/collab/exports/ID          # state: queued|running|done|failed
curl -s -H "Authorization: Bearer $COMPLIANCE" -o export.zip http://localhost:18480/api/v1/admin/collab/exports/ID/download
```

Filters (all optional): `from`/`to` (a day, or an instant), `kind`, `id`, `user` (a thread they wrote in; a share they sent or received),
`includeShares`, `includeThreads`. The zip holds `shares.ndjson`, `threads.ndjson` (every comment with every revision, **unscrubbed**: this is the
record), `chains.ndjson` (each thread's first and last hash and whether its chain verified), `holds.ndjson`, `inbox.ndjson` (the in-scope inbox notices: who, type, entity, actor, when, read) and `outbox.ndjson` (the in-scope email and bridge
delivery rows: channel, state, attempts, times, never a message body), `README.txt` (field meanings and
how to recompute a hash) and `manifest.json` (filters, who and when, counts, software version, and the SHA-256, size and line count of every
other file). It is written line by line, so memory stays bounded; one export runs at a time per server (a queue of 8). Only the person who asked
may download it, once; it is deleted then, or after `export-keep`. Starting, finishing and downloading are audited (`collab.export.*`); the file
lives in `<drishti.collab.dir>/exports` and a restart forgets a job that was not finished. Check a file against the manifest with
`sha256sum -c`-style comparison of each entry; **keep the manifest and `chains.ndjson` in the firm's archive**: a later export that disagrees on a
thread's hashes proves history was rewritten (the chain is evidence of tampering, not prevention: someone with the database could rewrite all of it).

**Verify.** `GET /admin/collab/verify?thread=th_…` recomputes one chain, checks the live comment rows against their revisions (text, author, state, revision number), and compares the thread's head (revision count and last hash) with the seal kept beside it, so a changed row or a removed newest revision is found (`{ok, steps, firstHash, lastHash, problem}`); with no `thread` (or with
`kind` and `id`) it checks every thread and share, in bounded memory, and reports the failures (`problems`, capped by `maxProblems`), and also checks the legal holds and the audit trail, each sealed in `drishti_collab_seal` (or `seals.log` with the file store); rows that cannot be read are reported, never a 500. Seals are evidence, not prevention. A failure
names the revision that was changed or no longer follows the one before it.

**Backups.** Every collaboration table (`drishti_share`, `drishti_share_recipient`, `drishti_inbox`, `drishti_outbox`, `drishti_thread`,
`drishti_comment`, `drishti_comment_revision`, `drishti_mention`, `drishti_follow`, `drishti_collab_hold`, `drishti_note_link`) is in the identity
database, so the backup of that database is the backup of the record, and it is the only copy of what the hash chains protect. With
`drishti.collab.store: file` (one server) the same records are the JSON-lines files under `drishti.collab.dir`; copy that folder, except
`exports/`, which holds only what is waiting to be downloaded. A restored database needs no repair: the chains verify against the manifest you kept.

## 9a-3b. Watermarked snapshots (privacy note)

`drishti.collab.snapshots.enabled` (default `false`) lets a sender tick **Include a picture** on a share: the server draws a PNG of the view (or the shared
panel) and attaches it to the share's page and, when `email.content` is `comment`, to its email. Read this before turning it on.

- **An image leaves the application's controls, and may show figures.** A masked field is `•••` in the picture, but an attachment can be forwarded, kept in a mailbox and read
  by anyone, with no rights check at the time it is read. That is why it is off by default, per pack switchable and an administrator's choice.
  A pack whose figures must never leave Drishti sets `drishti.collab.packs.<pack>.snapshots.enabled: false` (for instance a genomics pack); a share of
  such a kind asking for a picture is refused with `403 DRS-7015` and the dialog does not offer the option.
- **Least privilege.** One picture serves every recipient of a share, so it is drawn for the most restrictive of them: if any recipient lacks `raw`, the
  fields named in `drishti.security.redact` are `•••` for everyone; a panel any recipient may not open is not drawn at all. A recipient who may not open
  the kind never receives the share, so never the picture. Choose recipients with this in mind: one picture for a trader and a risk officer is the
  trader's.
- **Watermark.** Every picture carries the sender, the recipients (a count, or names with `snapshots.recipients: names`), the time, the data's business
  date or "live", its generation, and the link, in a band and tiled diagonally, so a leaked picture says whose it was.
- **Audit.** Each picture produced, including a preview in the dialog and each time one is served or attached, writes an access-log row with action
  `export` (kind, entity, and `snapshot sh_… for N recipients, masked|unmasked, bytes`).
- **Resources.** Drawing is headless (`java.awt.headless=true` is set by the renderer), uses the JDK's logical fonts (a server needs a JDK with its font
  libraries, as the standard images have; nothing is downloaded), is bounded by `snapshots.timeout`, `max-bytes`, `width` and `max-height`, runs two at a
  time, and is cached for `cache-ttl`. A picture is not stored: after the cache expires it is drawn again from the data as pinned, but its rights are frozen when the share is made (the roles of the recipients and the sender are kept in the share): a redraw uses that profile together with the rights of the recipients and of whoever asks *now*, so it can only get more restrictive; promoting a recipient to `raw` later never unmasks a picture, and a masked sender never sees more than they could see themselves.
- A picture that cannot be made (`503 DRS-7016`: timeout, too large, renderer failure) refuses the share; an email whose picture cannot be made later is
  sent without it. A digest email (several notices in one) carries no picture.

## 9a-4. Chat bridges (Teams, Slack, webhooks)

A bridge posts a share, a comment or a mention to a chat channel as **a sentence and a link**: "Ann Shah commented on a view", the kind and id, the
panel's title, the pinned date, the note, and the link. Posts carry values (decision 2026-10-05): the note is written as a person holding only the role `bridges.render-as`
(the bundled `viewer`, no `raw`) would read it, so a value copied from a masked field reads `•••`, and a `{$.mtm}` quote is filled from that role's view (`•••` if masked). Name a raw role in `render-as` only for a restricted channel. People who click
the link sign in and see what *they* may see. A pack whose ids are sensitive sets `packs.<pack>.email.content: link-only` and its posts carry no id, panel or
note either. Posts go through the same outbox as email (retries with backoff, dead letters, `outbox.max-attempts`), so a chat outage delays them and
never loses one.

**Set one up** (Teams: add a *Workflows* "post to a channel when a webhook request is received", or a classic incoming webhook; Slack: create an app with
an incoming webhook; generic: any HTTPS endpoint that checks the signature):

```bash
export BRIDGE_RISK_DESK_URL='https://...the webhook URL...'      # a credential: environment only, never in a file
export BRIDGE_AUDIT_URL=https://hooks.example/drishti BRIDGE_AUDIT_SECRET=...   # a json bridge also needs a signing secret
```

```yaml
drishti.collab:
  console-url: https://drishti.example.com
  bridges:
    enabled: true
    allow: [https://prod-00.westus.logic.azure.com/, https://hooks.example/]   # the URL must start with one of these; empty = nothing posts
    webhooks:
      - name: risk-desk
        format: teams                      # or slack
        url-env: BRIDGE_RISK_DESK_URL
        routes:
          - { packs: [market-risk], events: [share, comment] }
      - name: audit
        format: json
        url-env: BRIDGE_AUDIT_URL
        secret-env: BRIDGE_AUDIT_SECRET
        routes: [{ kinds: [trade], events: [mention] }]
```

Then `GET /api/v1/admin/collab/bridges` (each bridge: `usable`, or the `status` that says which variable is unset or that the URL is not under `allow`) and
`POST /api/v1/admin/collab/bridges/risk-desk/test` (posts a data-free test message; `503 DRS-7013` says why it failed). A share is private to its recipients:
route `share` to a channel only when everyone in it may know that a share happened and read its note (the note is scrubbed, but its words are the sender's).

**The signed webhook (`format: json`).** `POST` with `Content-Type: application/json`, a body of `{schema: "drishti.bridge/1", event, id, product,
headline, kind, entityId, panel, asOf, note, link, at}` and the headers `X-Drishti-Event`, `X-Drishti-Delivery` (the outbox number: use it to drop a repeat),
`X-Drishti-Timestamp` (epoch seconds) and `X-Drishti-Signature: sha256=<hex>`, the HMAC-SHA256, with the secret, of `<timestamp>.<raw body>`. The receiver
recomputes it over the exact bytes, compares in constant time and rejects a timestamp older than a few minutes.

**Limits and safety.** `bridges.per-minute` posts per bridge (the rest wait a minute; no attempt is lost), `bridges.timeout`, redirects are never followed
(a `3xx` is a dead letter: use the final URL), the answer's body is never read, and neither the URL nor the secret appears in a log line, an audit entry,
a dead letter or the admin listing (which shows the host only). Every post is in the audit log as `collab.bridge.post`, every dead letter as
`collab.bridge.dead`, every test as `collab.bridge.test`. A dead letter is sent again with `POST /api/v1/admin/collab/outbox/{seq}/retry` once the cause is fixed.
Changing a bridge's URL or secret needs a restart (the variables are read at start). Not supported: replies from chat into Drishti.

## 9b. The access log

Every answered read is a row in `drishti_access` (about 150 bytes). A desk of 200 people opening 300 views a day each
adds about 60,000 rows a day, 5.4 million over the default 90 days (roughly 1 to 2 GB on PostgreSQL with its indexes). For
heavier use put the identity database on PostgreSQL, or shorten `drishti.access-log.keep-days`. If writes fall
behind (the database is down), events queue in memory (`drishti.access-log.queue`, 100,000) and beyond that are
dropped and counted; Admin → Access shows the count. To keep it longer, export it on a schedule
(`GET /api/v1/admin/access?from=…&limit=5000`) before it is pruned.

### Ask about this page and your data

*Ask about this page* ([CONFIGURATION.md](CONFIGURATION.md)) is off by default. Turning it on sends text to the model endpoint
you configure, so decide what may leave the server:

- With `values: labels-only` (the default) the prompt holds the page's labels, the glossary, the pack's guide text and the user's
  question; no value of any entity. With `values: shown` it also holds the page's rendered sentence, with the figures the asking
  user may see. A field masked for that user is never in it, in either mode.
- The model gets no tools and cannot fetch anything. Document values and pack text are put in the prompt as marked, untrusted
  data; the fixed instructions tell the model not to follow them, which reduces but cannot remove the chance of a wrong answer.
- The question text is written to the access log (action `ask`) unless `log-questions: false`; answers are not stored unless
  `log-answers: true`. An endpoint that you do not control sees every question and the material around it.
- The key is read from `DRISHTI_ASK_KEY` only. If the endpoint is down the drawer says so (`DRS-4008`) and everything else in it works.

## 9c-0. Shipping packs, Sutras and data by copying

Packs, Sutras, about files and data are plain files: deploy them by copying a versioned, checksummed bundle, with an atomic swap and a backup, and no server API. Step by step, with CI, permissions, Docker/Kubernetes, rollback and troubleshooting: [OPERATIONALISING.md](../guides/OPERATIONALISING.md).

## 9c. Pack registry keys

The private key that signs packs (`tools/packreg/packreg.py keygen`) is the one secret of the registry: keep it
off servers, in your secrets store, with access for release managers only. Servers hold only public keys
(`drishti.packs.registry.trusted-keys`). To rotate: make a new key, add its public key to every server, republish
the packs with it, then remove the old key. To stop trusting a publisher at once, remove its key: installed packs
keep working, nothing new from it installs. Back up `data/packs/installed/` with the rest of `data/`.

## 10. Backups and restore

The server writes state in a few directories; the console writes nothing. All paths are relative to the server's
working directory unless you changed them.

| Path | Holds | Back up? | How |
|---|---|---|---|
| `data/identity/` | users, password hashes, the audit log, each user's settings, workspaces, monitors and pins. In 1.9: `users.json`, `audit.jsonl`, `preferences/`. From 1.10: the SQLite database `drishti.db` (`DRISHTI_IDENTITY_DB_URL`), which also holds roles defined in Admin → Roles | **yes, daily** | 1.9: copy the directory (files are written atomically). 1.10 with SQLite: `sqlite3 data/identity/drishti.db ".backup '/backup/drishti/identity.db'"`, safe while running. With PostgreSQL: `pg_dump` the database |
| `data/governance/` (`DRISHTI_GOVERNANCE_DIR`) | Sutra proposals, approvals and their history | **yes, daily** | copy the directory |
| `sutras/` (`DRISHTI_SUTRAS`) | your own Sutras, and what Studio saves | **yes**, preferably in version control | git, or copy |
| `application.local.yaml`, `drishti-console/config/application.local.yaml`, `/etc/drishti/drishti.env` | your configuration and secrets | **yes**, securely | copy |
| `data/state/<connector>/` | ActiveMQ and RabbitMQ connectors' latest document of each entity received (RocksDB, with its write-ahead log). A queue does not send a message twice, so this is the only copy Drishti has | yes, if you use those connectors | stop the server, copy the whole folder (the write-ahead log with the table files), start; or rely on the source system to re-send. A copy taken while the server runs is not consistent |
| `data/cache/<connector>/` | the Kafka connectors' disk cache | no | rebuilt from the topic; cleared every night anyway |
| `data/delta/` (`DRISHTI_DELTA_ROOT`) | the lake | by its owner | Drishti only reads it; back it up with your data platform's policy |
| `data/feeds/` (`DRISHTI_FEEDS`) | files for the file connector | by whoever writes them | |
| `packs/` | the packs you installed | no, if they come from the release | keep your own packs in version control |

### 10.1 A nightly backup

```bash
#!/usr/bin/env bash
# /opt/drishti/backup.sh: keep 14 nightly archives of Drishti's own state.
set -euo pipefail
cd /opt/drishti
stamp=$(date +%Y%m%d-%H%M)
if [ -f data/identity/drishti.db ]; then                      # 1.10+: a consistent copy of the live SQLite database
  sqlite3 data/identity/drishti.db ".backup 'data/identity/backup.db'"
fi
tar czf /backup/drishti/drishti-$stamp.tgz --exclude=data/identity/drishti.db* \
    data/identity data/governance sutras application.local.yaml
ls -1t /backup/drishti/drishti-*.tgz | tail -n +15 | xargs -r rm --
```

Run it from cron (`30 1 * * * /opt/drishti/backup.sh`) as the `drishti` user. Check the archive:

```bash
tar tzf /backup/drishti/drishti-20261001-0130.tgz | head
```

You should see `data/identity/...`, `data/governance/...` and your Sutras.

The script leaves out `data/state/`, the ActiveMQ and RabbitMQ state stores: RocksDB files copied while the server
writes them are not a consistent copy. Back those up with the server stopped (`systemctl stop drishti-server`, `tar
czf … data/state`, start), in a maintenance window; the messages that arrive meanwhile wait in the broker.

### 10.2 Restore

1. Stop the server: `sudo systemctl stop drishti-server`.
2. Move the damaged directories aside, for example `mv data/identity data/identity.broken`.
3. Unpack: `tar xzf /backup/drishti/drishti-20261001-0130.tgz -C /opt/drishti`. With 1.10 and SQLite, then
   `mv data/identity/backup.db data/identity/drishti.db`.
4. `sudo chown -R drishti:drishti /opt/drishti/data` and start the server.
5. Check: sign in as an admin, open **Admin → Users** and **Admin → Audit**; the users and recent events are there.

## 11. The lake: where it lives and keeping it bounded

### 11.1 Local disk or object storage

The Delta connector reads a lake through `LakeStore`: a `root` without a scheme is a local directory;
`s3a://bucket/lake` is Amazon S3 or any S3-compatible store (MinIO, Ceph, on-premises), read through Hadoop's S3 file
system with only the AWS SDK modules it needs. Dates, time travel, reverse lookups and search work the same on both.

The simplest switch is one variable for every pack's lake connectors:

```bash
DRISHTI_DELTA_ROOT=s3a://risk-lake/banking java -jar drishti-server.jar
```

Credentials then come from the AWS default chain (environment variables, profile, instance role). To set the
region, an endpoint or keys explicitly, override the connector in `application.local.yaml`:

```yaml
drishti:
  sources:
    connectors:
      trading-store:
        plugin: delta
        settings:
          root: s3a://risk-lake/banking
          domain: trading
          s3.region: us-east-1               # credentials from the AWS chain (environment, profile, instance role),
          # s3.endpoint: https://minio.bank.example   # or s3.access-key / s3.secret-key; an endpoint for S3-compatible stores
          # hadoop.fs.s3a.connection.maximum: "200"   # any Hadoop S3A setting, prefixed hadoop.
```

The layout is `<root>/<domain>/<kind>/business_date=YYYY-MM-DD/`. See [CONNECTOR_DEVELOPER_GUIDE.md](../connectors/CONNECTOR_DEVELOPER_GUIDE.md) for every
Delta setting.

**Sizing.** A laid-out trade table takes about 1.7 KB per trade per business day (Parquet, compressed): 1,000,000
trades a day is about 1.6 GB a day, 35 GB a month, 2.8 TB for seven years. The server's memory does not grow with
history: it keeps the days in use (their ids and columns, a few hundred MB a day for a million trades) and a document
cache, all bounded (`id-map-mb`, `columns-cache-mb`, `doc-cache-mb`). Give the server a heap of 8 GB or more for a
million trades a day.

### 11.2 Maintenance

Drishti's server only reads the lake. Writers leave small files every day, and Delta keeps old files for time
travel, so a lake grows until something tidies it. `tools/lake/maintain.py` does that, for local and S3 lakes,
configured by `deploy/lake-maintenance.yaml`:

| Step | Setting (per lake) | Default in the tool | What it does |
|---|---|---|---|
| retention | `keep-business-days` (the shipped file: 520, about two years; `null` keeps all) | `null` | deletes business dates older than the window, counted back from today in `schedule.zone` (or `--as-of`), never from the newest date in the table |
| retention guard | `max-drop-share` | `0.5` | a table whose retention would delete more than this share of its rows is left as it is and reported `failed`; `--force-drop` deletes anyway |
| compaction | `compact`, `target-file-mb` | `true`, `128` | merges each day's small files |
| checkpoint | `checkpoint` | `true` | writes a Delta checkpoint, so readers replay a short log |
| vacuum | `vacuum-hours` | `168` | removes files unreferenced for longer than this; *known at* (time travel) reaches back this far |

Other settings: `root` (a local path or `s3://bucket/path`), `domains` (default `["*"]`, every domain folder),
`storage-options` (for S3: `AWS_REGION`, `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_ENDPOINT_URL`,
`AWS_ALLOW_HTTP: "true"` for plain HTTP). `schedule.at` (`"02:30"`) and `schedule.zone` (`America/New_York`) set when
the daemon runs. Note the maintenance tool uses `s3://`, while the server uses `s3a://`, for the same bucket.

**Worked example: a dry run.** From the repository (or installation) root:

```bash
uv run --with deltalake --with pyarrow --with pyyaml python tools/lake/maintain.py \
    --config deploy/lake-maintenance.yaml --once --dry-run
```

You should see one JSON line per table, and nothing is changed:

```
{"at": "2026-09-30T20:59:08-04:00", "event": "maintained", "dry_run": true, "table": "data/delta/civic/bill", "before": {"files": 10, "mb": 0.03}, "retention": {"cutoff": "2024-10-02", "would_remove_files": 0}, "vacuum": {"files": 0, "dry_run": true}, "after": {"files": 10, "mb": 0.03}}
{"at": "2026-09-30T20:59:08-04:00", "event": "maintained", "dry_run": true, "table": "data/delta/civic/candidate", "before": {"files": 10, "mb": 0.03}, "retention": {"cutoff": "2024-10-02", "would_remove_files": 0}, "vacuum": {"files": 0, "dry_run": true}, "after": {"files": 10, "mb": 0.03}}
...
```

`cutoff` is the oldest business date kept; `would_remove_files` is what retention would delete. Run it again
without `--dry-run` to do the work; the lines then also show `compact` (`numFilesAdded`, `numFilesRemoved`) and
`checkpoint` (the table version). A table that fails prints `{"event": "failed", "table": ..., "error": ...}` and the
others carry on. With `--once` the exit code is `1` if any table failed, else `0`, which suits cron and alerting.

### 11.3 Scheduling it

Either from cron, once a night:

```cron
30 2 * * *  cd /opt/drishti && uv run -q --with deltalake --with pyarrow --with pyyaml python tools/lake/maintain.py --config /etc/drishti/lake-maintenance.yaml --once >> /var/log/drishti/lake.log 2>&1
```

Or as a service that sleeps until `schedule.at` every day (`--daemon`; it logs `{"event": "sleeping", "until": ...}`
between runs):

```ini
# /etc/systemd/system/drishti-lake.service
[Unit]
Description=Drishti lake maintenance
[Service]
User=drishti
WorkingDirectory=/opt/drishti
ExecStart=/usr/local/bin/uv run -q --with deltalake --with pyarrow --with pyyaml python tools/lake/maintain.py --config /etc/drishti/lake-maintenance.yaml --daemon
Restart=on-failure
[Install]
WantedBy=multi-user.target
```

Run it next to the writers. It is safe while Drishti reads the lake: a test proves Drishti's reader reads a lake the
job has compacted, checkpointed and vacuumed.

## 12. Memory and caches

Nothing grows with the day's data without bound. Every cache has a size limit you can set:

| Where | Holds | Limit (setting) |
|---|---|---|
| Kafka connector | the index of where each entity's latest message is, with each id for type-ahead (about 0.4–0.5 GB per million entities, estimated), and recently read documents | `cache-mb` (256); `mode: ticks` keeps nothing (a store serves entities, the stream only ticks); `search: false` drops the identifier index |
| Kafka disk cache (per connector) | every live message of the day, on local disk (RocksDB, no write-ahead log, LZ4, oldest files dropped first) | `disk-cache.max-gb` (10); cleared every night at `disk-cache.reset-at` (02:00 New York); its own directory `disk-cache.dir` (default `<disk-cache.root>/<connector>`, root `./data/cache`), so connectors never contend on one store and a busy stream can have its own disk |
| ActiveMQ / RabbitMQ connectors | recently read documents in memory; the latest document of every entity on disk (RocksDB, LZ4, level compaction: size follows the entities held, not the messages). Writes are kept per `state.durability`: `sync` (default; write-ahead log synced, no acknowledged message lost on a crash or power loss), `wal` or `none`; a message the store cannot keep is not acknowledged and comes again | `cache-mb` (128); `state.max-gb` (10) per connector under `state.dir` (default `./data/state/<connector>`); past it `state.when-full: evict-oldest` (default) removes the entities written longest ago, logged and counted in `evicted`, or `warn` only says so in health |
| Delta Lake connector | table partitions read recently | `cache-mb` (512) |
| Aerospike connector | the kinds' dates and the ids (from the index set), and days of promoted bins for searches, impact and reverse lookups; not the days kept | `columns-cache-mb` (1024), kept `columns-seconds` (300) |
| PostgreSQL (JDBC table mode) | nothing: every read is a query | `pool-size` connections |
| Engine | layouts, shape fingerprints, compiled expressions | `drishti.engine.layout-cache-size` (10,000), `fingerprint-cache-size` (100,000), `drishti.rachana.expression-cache-size` (10,000) |
| Live views | the current document of each entity someone is watching | `drishti.live.max-streams` (20,000) |

**Disk space in the disk cache.** A delete or an overwrite in RocksDB writes a small marker; the space comes back
when the file holding the old value is dropped. The disk cache uses FIFO compaction, which never rewrites files, so
space is reclaimed in three ways: the oldest files go once the store passes `disk-cache.max-gb`; the whole store is
deleted at the nightly `reset-at`; and an admin purge does the same at once. In between, deleted and overwritten
entries still take space, never beyond `max-gb`. Clearing and purging are safe under load: the new store takes
over at once and the old one is closed only after its last reader has finished.

**Disk space in a message-queue state store.** The ActiveMQ and RabbitMQ state stores use level compaction instead:
overwritten and deleted values are merged away, so a store holds about the compressed latest document of each entity,
plus values not yet compacted and a write-ahead log of at most 64 MB. Every `state.check-seconds` (60) each store is
compared with its own `state.max-gb`; with `state.when-full: evict-oldest` (the default) the entities written longest
ago are removed until it is under 90% of the budget and the store is compacted at once to give the space back. The
compaction writes new files before deleting old ones, so plan about twice `state.max-gb` of disk per connector. A
full disk does not lose messages: they are not acknowledged and come again once there is room, and health says
`DOWN: … (messages are not acknowledged and come again)` meanwhile.

**Seeing and purging caches.** **Admin → Caches** lists every cache with its statistics and a purge button for each
one or all. The same list from the server:

```bash
curl -s localhost:18480/api/v1/admin/caches
```

```
[{"name":"engine","type":"Layouts and shape fingerprints","stats":{"layouts":24,"fingerprints":8818,"layoutHitRate":0.999}},
 {"name":"market-store","type":"Connector","stats":{"tables":20,"partitions":24,"timeTravel":0}},
 {"name":"nyfed-sofr-feed","type":"Connector","stats":{"fetchedAt":"2026-10-01T00:03:36.547175473Z","observations":60,"series":1}}, ...]
```

A purge is `POST /api/v1/admin/caches/{name}/purge` (or `all`); it is audited as `cache-purged`. A
`layoutHitRate` well below 0.99 after warm-up means the data's shapes vary a lot; raise `layout-cache-size`.

Documents of entities nobody is viewing are not held: Kafka messages for them are indexed from their key without
being parsed, and a later view reads that one message back by its offset.

## 13. Monitoring

### 13.1 The endpoints

| URL | Auth | Answers | Use it for |
|---|---|---|---|
| `GET :18480/actuator/health/liveness` | none | `{"status":"UP"}` | restart the process if it fails |
| `GET :18480/actuator/health/readiness` | none | `{"status":"UP"}` | send traffic only when up (the image's health check) |
| `GET :18480/actuator/info` | none (security on: admin or metrics token) | build version and time | which version is running |
| `GET :18480/actuator/prometheus` | none (security on: `Authorization: Bearer $DRISHTI_METRICS_TOKEN`) | Prometheus metrics | scraping |
| `GET :18480/api/v1/health/live` | token (when security is on) | live streaming numbers | quick latency check |
| `GET :18480/api/v1/admin/health` | admin | everything in one answer | **Admin → Health** page, deep checks |
| `GET :18480/api/v1/sources` | token | each connector and its health | which source is down |
| `GET :18480/api/v1/sutras/problems` | token | Sutra problems, `{}` when none | broken Sutras |
| `GET :17480/healthz` | none | `{"status":"UP"}` | the console process is answering (liveness; it does not check the server) |
| `GET :17480/readyz` | none | `{"status":"UP","server":"reachable"}`, or `503` | the console can reach the server (readiness) |

The calls that need a token work with plain `curl` only while security is off (development). In production,
read them through the console's **Admin → Health** page, or from monitoring rely on the actuator.

### 13.2 Admin → Health

The **Admin → Health** page (console `/admin/health`, refreshed automatically) shows the overall status, each
connector's state, traffic, latency and last error, each pack's connectors and Sutra problems, and the live numbers.
The data behind it:

```bash
curl -s localhost:18480/api/v1/admin/health | python3 -m json.tool | head -40
```

```json
{
    "status": "OK",
    "summary": { "packsWithProblems": 0, "failedToStart": 0, "sourcesDown": 0, "sourcesDegraded": 0, "sources": 18, "packs": 12 },
    "server": { "version": "1.18.0", "uptimeSeconds": 6916, "java": "25.0.4.1", "heapUsedMb": 121,
                "heapMaxMb": 15640, "threads": 75, "cpus": 24 },
    "sources": [
        { "name": "credit-store", "version": "1.0", "status": "UP", "health": "UP",
          "kinds": ["credit-limit", "cva", "exposure-profile", "netting-set", "sa-ccr"],
          "live": false, "dated": true, "search": true,
          "lastUpdate": "2026-09-30T21:04:11Z", "staleAfter": null, "stale": false,
          "reads": { "reads": 4, "found": 4, "notHeld": 0, "errors": 0, "lastError": null, "lastErrorAt": null,
                     "lastOkAt": "2026-10-01T00:57:54.993176502Z", "p50Ms": 11.07, "p99Ms": 18.604 },
          "cache": { "tables": 5, "partitions": 7, "timeTravel": 0 } },
        ...
    ],
    "packs": [
        { "name": "market-data", "extends": ["banking-core"], "kinds": 20, "sutras": 20, "sutraProblems": [],
          "connectors": ["market-store", "nyfed-sofr-feed", "ecb-estr-feed", "ecb-fx-feed", "us-treasury-feed", "fred-feed"],
          "connectorsDown": [], "connectorsOff": ["ecb-estr-feed", "fred-feed"], "status": "OK", ... },
        ...
    ],
    "sutras": { "hotReload": "WATCHING", "problemFiles": 0 },
    "live": { "frames": 11660, "p50Ms": 0.809, "streams": 0, "p99Ms": 1.198, "topics": 0, "droppedFrames": 0 }
}
```

How to read it:

- **`status`**: `OK`; `DEGRADED` when any source is down, degraded or stale, a connector failed to start
  (`failedToStart`), a pack has a problem or the Sutra watcher has stopped; `DOWN` when no source is up at all.
- **`sutras`**: `hotReload` is `WATCHING` (edits to Sutra files are picked up), `OFF` (`drishti.rachana.hot-reload:
  false`) or `STOPPED: <reason>` (the watcher ended unexpectedly; the log has the error; restart to pick up edits
  again). `problemFiles` counts the files listed by `GET /api/v1/sutras/problems`, site and pack.
- **`lastUpdate`** is when the connector last received new data: the newest Kafka message, ActiveMQ or RabbitMQ
  message, Delta table commit, file in a file connector's folder, feed refresh that brought data, or demo tick. It is
  null for sources read on demand (PostgreSQL, Aerospike, S3), which cannot tell. **`stale`** is true when nothing new
  arrived for longer than the connector's `stale-after` setting (`staleAfter`, an ISO-8601 duration such as `PT15M`).
  The **Last update** column shows it, in amber when stale. Views of that source's entities show a "behind" banner.
- **A source** is `UP`, `DEGRADED` or `DOWN`; `health` carries the plugin's own text (the reason, when not up).
  `DEGRADED` means it serves, but some of its data cannot be read: a Delta connector names each table and date whose
  last read failed (a truncated Parquet file, pages in a codec its engine does not decompress, a table log missing a
  commit) and why, until a read of it succeeds or the table gets a new version. A Redis connector is `DOWN` as soon
  as its connection drops or a read fails, not at its next refresh; while it is down its reads fail at once instead of
  each waiting for the timeout, and it is `UP` again within a second of Redis answering (`fail-fast`, `recheck-ms`). `reads.errors`, `lastError` and `lastErrorAt`
  show the most recent failure; `p99Ms` its read latency.
- **A pack** is `DEGRADED` when one of its Sutras has problems or one of its connectors is down. `connectorsOff`
  lists connectors switched off by configuration (for example a feed you did not enable): not a fault.

### 13.3 The live numbers

```bash
curl -s localhost:18480/api/v1/health/live
```

```
{"streams":0,"topics":0,"frames":11660,"p50Ms":0.809,"p99Ms":1.198}
```

`streams` is the number of open live views, `topics` the distinct entities being watched, `frames` the frames sent
since start, and `p50Ms`/`p99Ms` the tick-to-frame latency over the last 30 seconds (`drishti.live.window`). The
same p99 is in the console's top bar. See [LIVE.md](../architecture/LIVE.md) and [PERFORMANCE.md](PERFORMANCE.md).

### 13.4 Prometheus and Grafana

Scrape the server:

```yaml
scrape_configs:
  - job_name: drishti
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ["drishti-host:18480"]
```

Drishti's own metrics:

```bash
curl -s localhost:18480/actuator/prometheus | grep '^drishti_'
```

```
drishti_live_frames_total 11660.0
drishti_live_latency_p99_milliseconds 1.198
drishti_live_streams 0.0
drishti_live_topics 0.0
drishti_view_seconds{quantile="0.5"} 0.0
drishti_view_seconds{quantile="0.99"} 0.0
drishti_view_seconds_count 112
drishti_view_seconds_sum 0.313422287
drishti_view_seconds_max 0.017122086
```

The usual Spring metrics (`jvm_*`, `http_server_requests_seconds_*`, `process_*`) are there too.

Import `deploy/grafana/drishti-dashboard.json` into Grafana. Its panels: view build p99 (`drishti_view_seconds`),
views per second, live tick → frame p99 (`drishti_live_latency_p99_milliseconds`), live streams and topics, live
frames per second, and JVM heap used.

### 13.5 Suggested alerts

| Alert | Condition | First look |
|---|---|---|
| Views slow | `drishti_view_seconds{quantile="0.99"} > 0.15` for 5 min | [source down](runbooks/source-down.md) |
| Live late | `drishti_live_latency_p99_milliseconds > 40` for 5 min | [live latency high](runbooks/live-latency-high.md) |
| Server down | `up{job="drishti"} == 0`, or readiness not `UP` | `journalctl -u drishti-server` |
| A source down | Admin → Health `status` not `OK`, or a source not `UP` in `/api/v1/sources` | [source down](runbooks/source-down.md) |
| Broken Sutra | `/api/v1/sutras/problems` not `{}` | [a Sutra is broken](runbooks/sutra-broken.md) |
| Heap high | `sum(jvm_memory_used_bytes{area="heap"}) / sum(jvm_memory_max_bytes{area="heap"}) > 0.9` for 10 min | section 12 |
| Lake maintenance failed | the job's exit code is 1, or a `"event": "failed"` line | the `error` in that line |
| Message state store cannot write | an ActiveMQ or RabbitMQ connector's health ends `(messages are not acknowledged and come again)` | free or grow the disk under `state.root` |
| Message state store full | a health `UP (state store over its budget: …)`, or `evicted` rising in Admin → Caches | raise `state.max-gb` and the disk |

## 14. Logs

| Process | Where | Notes |
|---|---|---|
| server | standard output (systemd journal, or `docker logs`) | Spring Boot's default format. Write to a file as well with `LOGGING_FILE_NAME=/var/log/drishti/server.log`; raise detail with `LOGGING_LEVEL_COM_ASH_DRISHTI=DEBUG` |
| console | standard output | uvicorn at level `info`; the access log is off |
| lake maintenance | standard output | one JSON line per table and step |

```bash
journalctl -u drishti-server --since "1 hour ago" --no-pager | grep -E "WARN|ERROR"
docker compose -f deploy/compose.yaml logs --tail 100 server
```

Lines worth knowing:

| Line contains | Meaning |
|---|---|
| `Started DrishtiApplication` | start-up finished |
| `Created development admin 'drishti-dev-admin' with the default password` | first start with no users; change that password |
| `drishti.security.secret must be at least 32 bytes when security is enabled` | the server refused to start: set `DRISHTI_TOKEN_SECRET` |
| `auth.session_secret must be at least 32 characters when auth is enabled` | the console refused to start: set `DRISHTI_SESSION_SECRET` |

User actions (sign-ins, user changes, cache purges, Sutra approvals) are not in the logs but in the audit log:
**Admin → Audit**, or `GET /api/v1/admin/audit?limit=50`.

## 15. Upgrades and rollback

1. **Read the release notes** (`RELEASE_NOTES.md`, `CHANGELOG.md`) for anything marked as a change in
   configuration or storage.
2. **Back up** (section 10.1), and keep the old jar: `cp drishti-server.jar drishti-server-$(date +%F).jar`.
3. **Build or fetch** the new release: `./mvnw -q clean package -DskipTests`.
4. **Stop** the console and the server: `sudo systemctl stop drishti-console drishti-server`.
5. **Replace** the jar, `packs/`, `drishti-console/` and `docs/` (keep `drishti-console/.venv` and your
   `drishti-console/config/application.local.yaml`). Re-run `uv pip install --python drishti-console/.venv/bin/python -r
   drishti-console/requirements.txt` in case the console's libraries changed.
6. **Start** the server, then the console.
7. **Verify**:
   ```bash
   curl -s localhost:18480/actuator/info
   curl -s localhost:18480/actuator/health/readiness
   ```
   You should see the new `version`, and `{"status":"UP"}`. Then check **Admin → Health** shows `OK` and open a view.

With Docker: build the new images with the new tag, change the `image:` tags in your compose file, and run
`docker compose -f deploy/compose.yaml up -d`; the named volumes are kept.

**Rollback**: stop, put the old jar (or image tag) back, restore the backup if the new version changed stored
data, start.

**Upgrading past 1.13 (server-side console sessions).** Sessions now live on the server (table `drishti_session`,
created at start). Cookies from before the upgrade carry no server session, so everyone signs in once more. Upgrade
the server and the console together: a new console cannot sign anyone in to an older server.

**Upgrading to 1.10 (users move into a database).** On its first start, 1.10 copies `users.json`, `audit.jsonl` and
the saved per-user documents into the identity database, once, and renames the old files `*.imported` (never
deletes them). To roll back to 1.9 after that, rename the files back. Details in
[USER_MANAGEMENT.md](USER_MANAGEMENT.md).

## 16. Capacity

Measured on a developer workstation; see [PERFORMANCE.md](PERFORMANCE.md) for how.

- A warm view builds in a few milliseconds (gate: p99 < 50 ms), and a cold one in about 14 ms.
- One topic fans out to 10,000 listeners.
- Live streams are capped per server by `drishti.live.max-streams` (20,000). Each browser tab uses one connection to
  the console, whatever it shows.
- An ActiveMQ or RabbitMQ connector applies one message at a time; with `state.durability: sync` (the default) each
  waits for a disk sync, so a connector runs at about one message per sync of its disk, typically thousands a second
  on an SSD (not measured here). Split a heavy feed over several connectors, or use `wal` where losing the last
  moments before a power loss is acceptable. Disk: about twice `state.max-gb` per connector.

## 17. Runbooks

- [Source down or slow](runbooks/source-down.md)
- [A Sutra is broken](runbooks/sutra-broken.md)
- [Live latency high](runbooks/live-latency-high.md)
- [Users cannot sign in](runbooks/sign-in.md)
- [TROUBLESHOOTING.md](../guides/TROUBLESHOOTING.md) for everything else.
