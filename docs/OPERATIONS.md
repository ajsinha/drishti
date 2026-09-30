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

## Processes

| Process | Port | Health | Metrics |
|---|---|---|---|
| `drishti-server` (Spring Boot, OpenJDK 21) | 18480 | `/actuator/health/{liveness,readiness}` | `/actuator/prometheus` |
| `console` (FastAPI) | 17480 | `/healthz` | — |

Both are stateless. Run as many of each as needed behind a load balancer. For SSE, the balancer
must not buffer (`X-Accel-Buffering: no` is set), and its idle timeout must exceed the 15 s heartbeat.
The console's sessions are signed cookies, so any console instance can serve any user.

## Deploying

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
./mvnw -q package -DskipTests
docker build -f deploy/server.Dockerfile  -t drishti-server:1.9.0 .
docker build -f deploy/console.Dockerfile -t drishti-console:1.9.0 .
export DRISHTI_TOKEN_SECRET=$(openssl rand -hex 32) DRISHTI_SESSION_SECRET=$(openssl rand -hex 32)
# first start seeds drishti-dev-admin / drishti-dev-admin123; sign in, change it, create real admins
# (or DRISHTI_SEED_ADMIN=false and provision users through the admin API). See docs/USER_MANAGEMENT.md.
docker compose -f deploy/compose.yaml up -d
```

- Images run as a non-root user and have health checks.
- The server image uses ZGC with 75 % of the container memory.
- Sutras are mounted read-only; update them by redeploying the volume, and hot reload picks them up.

## Data stores and feeds

The banking packs read one store per data domain (`reference`, `market`, `trading`, `risk`, `credit`,
`collateral`). Delta Lake is the default; PostgreSQL and Aerospike are one profile away.

```bash
# Delta Lake (default): write data/delta/<domain>/<kind>/ with ten business days of history
uv run --with deltalake --with pyarrow python tools/packgen/banking/make_data.py --lake data/delta

# PostgreSQL and Aerospike in Docker
docker compose -f deploy/compose.data.yaml up -d
uv run --with "psycopg[binary]" python tools/packgen/banking/make_data.py --postgres postgresql://drishti:drishti@localhost:5432/drishti
tools/load-aerospike.sh localhost:3000 test

# Live trades from Kafka (broker in deploy/compose.data.yaml)
uv run --with kafka-python python tools/samplegen/stream.py --rate 5

# Start with the store you want, the feeds you want and the stream
SPRING_PROFILES_ACTIVE=postgres DRISHTI_PACKS=market-risk,counterparty-risk DRISHTI_FEED_NYFED_SOFR=true \
  DRISHTI_STREAM_TRADING=true java -jar drishti-server-*-exec.jar
```

Each connector's health is on `/api/v1/sources`: a database that is down, or a feed that failed, shows there and
in *About*, and the rest of Drishti keeps working. A feed keeps its last good data until the next refresh.

## Memory

Nothing grows with the day's data unbounded. Every cache has a size limit you can set:

| Where | Holds | Limit (setting) |
|---|---|---|
| Kafka connector | the index of where each entity's latest message is (tens of bytes each) and recently read documents | `cache-mb` (256); `mode: ticks` keeps nothing (a store serves entities, the stream only ticks); `search: false` drops the identifier index |
| Kafka disk cache (per connector) | every live message of the day, on local disk (RocksDB, no write-ahead log, LZ4, oldest files dropped first) | `disk-cache.max-gb` (10); cleared every night at `disk-cache.reset-at` (02:00 New York); its own directory `disk-cache.dir` (default `${DRISHTI_CACHE_ROOT:./data/cache}/<connector>`), so connectors never contend on one store and a busy stream can have its own disk |
| Delta Lake connector | table partitions read recently | `cache-mb` (512) |
| Aerospike connector | dates, identifiers and (optionally) references learned by scanning | `reverse-index: false` for large sets |
| PostgreSQL (JDBC table mode) | nothing: every read is a query | `pool-size` connections |
| Engine | layouts, shape fingerprints, compiled expressions | `drishti.engine.layout-cache-size` (10,000), `fingerprint-cache-size` (100,000), `drishti.rachana.expression-cache-size` (10,000) |
| Live views | the current document of each entity someone is watching | `drishti.live.max-streams` (20,000) |

An admin can purge any cache at any time from **Admin → Caches** (or `POST /api/v1/admin/caches/{name|all}/purge`);
purges are audited. Documents of entities nobody is viewing are not held: Kafka messages for them are indexed from their key without
being parsed, and a later view reads that one message back by its offset.

## Security checklist

- **Server:** `DRISHTI_SECURITY_ENABLED=true` and `DRISHTI_TOKEN_SECRET` (≥ 32 bytes), shared with the console.
- **Console:** `DRISHTI_AUTH_ENABLED=true` and `DRISHTI_SESSION_SECRET` (≥ 32).
- **Users:** managed in the server (`/admin/users`). Change the seeded `drishti-dev-admin` password,
  or set `DRISHTI_SEED_ADMIN=false`. Back up `data/identity/` (users and audit).
- **Roles:** set per desk in `drishti.security.roles`. Keep `redact` listing the fields traders must
  not see in raw JSON.
- **Studio saving:** `DRISHTI_STUDIO_SAVE=true` only in authoring environments. Save writes into the
  first Sutra directory.
- **TLS:** terminate at the balancer. Cookies are `Secure` by default (`DRISHTI_SECURE_COOKIE=false`
  only for local HTTP).
- **CSP:** strict and set by the console (`script-src 'self'`, no inline scripts or styles). Every
  asset is vendored, so the console works air-gapped.

## Monitoring

Import `deploy/grafana/drishti-dashboard.json`. It covers view build p99 (`drishti_view_seconds`),
views per second, live tick → frame p99 (`drishti_live_latency_p99_milliseconds`), live streams and
topics, frames per second, and JVM heap.

Suggested alerts:
- view p99 > 150 ms for 5 min;
- live p99 > 40 ms for 5 min;
- any source plugin not `UP` in `/api/v1/sources`;
- a non-empty `/api/v1/sutras/problems`.

## Capacity (measured on a developer workstation; see PERFORMANCE.md)

- A warm view builds in a few milliseconds (gate: p99 < 50 ms), and a cold one in about 14 ms.
- One topic fans out to 10,000 listeners.
- Streams are capped per server by `drishti.live.max-streams` (20,000).

## Runbooks

- [Source down or slow](runbooks/source-down.md)
- [A Sutra is broken](runbooks/sutra-broken.md)
- [Live latency high](runbooks/live-latency-high.md)
- [Users cannot sign in](runbooks/sign-in.md)
