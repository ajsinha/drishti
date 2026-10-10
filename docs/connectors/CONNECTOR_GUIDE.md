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
# Connecting your data

Connecting Drishti to your data means choosing a **connector** (one configured instance of a source plugin) and writing it down as a **connector file**. This page
is the short version; the full guide is [CONNECTOR_DEVELOPER_GUIDE.md](CONNECTOR_DEVELOPER_GUIDE.md): concepts, choosing a connector,
combining, operating and troubleshooting. The step-by-step walk-through of each connector is at the end of that
connector's own document (`<NAME>_CONNECTOR.md`, "Appendix: walk-through and worked examples").

## Configuration: one file per connector

A connector is a **site** resource: one YAML file in `config/connectors/`, and **the file name is the connector's name** (`config/connectors/trading-lake.yaml` is the
connector `trading-lake`). A pack does not carry connection settings; it **names** the connectors it reads through (`connectors: [trading-lake]` and `routes:` in
its `pack.yaml`) and may **suggest** a template for each, which the server writes to the file once, at the first start, and never overwrites.

```yaml
# config/connectors/trading-lake.yaml
plugin: delta                     # required: which source plugin (delta, jdbc, file, kafka, redis, …)
enabled: true                     # optional, default true
kinds: [trade]                    # optional: limit the kinds it serves
description: Trading lake         # optional
settings:                         # the plugin's settings; nesting is flattened to dotted keys
  root: ${DRISHTI_DELTA_ROOT:${drishti.data.dir:./data}/delta}
  domain: trading
```

- Edit the file in your editor, in **Admin → Connectors** (a form generated from the plugin's settings, a YAML tab, **Test connection**, history, restore), or with
  `drishti.py connector list|get|apply|delete|test|plugins|enable|disable|reset|history`. A change is applied to that connector without a restart.
- A credential (`password`, `secret…`, `token`, `api-key`, `access-key`, `private-key`, `credential`) is only ever an `${ENV_VAR}` or a `file:/path` reference.
- Ready-made files for every store are in [`config/connectors.examples/`](../../config/connectors.examples) (`postgres/`, `duckdb/`, `aerospike/`, `mongodb/`, `iceberg/`,
  `redis/`, `files/`, `other/`). Every connector document ends its settings with an "As a connector file" section.
- `drishti.sources.connectors` in `application.yaml` and the per-store profiles (`SPRING_PROFILES_ACTIVE=postgres`, …) still work but are **deprecated**.

### Precedence

Highest first (the full rules are in [CONNECTOR_FILES.md](CONNECTOR_FILES.md#precedence)):

| Source | Role |
|---|---|
| environment variables and `drishti.sources.connectors.*` in the server's own configuration (**deprecated**) | override a file setting by setting until you move them to the file |
| `config/connectors/<name>.yaml` | the site's definition; it replaces the pack's template for that name wholesale |
| the pack's template (`connectors:` mapping or `connector-templates:` in `pack.yaml`) | the pack's suggestion, written to the file once at the first start |

## Which connector?

| Your data is… | Use | Configure it with |
|---|---|---|
| JSON or CSV files in a folder | `file` | [FILE_CONNECTOR.md](FILE_CONNECTOR.md) |
| behind an in-house HTTP/JSON API | `rest` | [REST_CONNECTOR.md](REST_CONNECTOR.md) |
| in an existing database schema (your own SQL) | `jdbc` query mode | [JDBC_QUERIES.md](JDBC_QUERIES.md) |
| in PostgreSQL loaded for Drishti | `jdbc` table mode | [POSTGRES_CONNECTOR.md](POSTGRES_CONNECTOR.md) |
| in a Delta Lake or an Iceberg catalog | `delta`, `iceberg` | [DELTA_CONNECTOR.md](DELTA_CONNECTOR.md), [ICEBERG_CONNECTOR.md](ICEBERG_CONNECTOR.md) |
| in DuckDB, MongoDB, Redis or Aerospike | `duckdb`, `mongodb`, `redis`, `aerospike` | [DUCKDB_CONNECTOR.md](DUCKDB_CONNECTOR.md), [MONGODB_CONNECTOR.md](MONGODB_CONNECTOR.md), [REDIS_CONNECTOR.md](REDIS_CONNECTOR.md), [AEROSPIKE_CONNECTOR.md](AEROSPIKE_CONNECTOR.md) |
| on Kafka, ActiveMQ or RabbitMQ (live) | `kafka`, `activemq`, `rabbitmq` | [KAFKA_CONNECTOR.md](KAFKA_CONNECTOR.md), [ACTIVEMQ_CONNECTOR.md](ACTIVEMQ_CONNECTOR.md), [RABBITMQ_CONNECTOR.md](RABBITMQ_CONNECTOR.md) |
| JSON objects in a bucket (S3, MinIO, Ceph) | `s3` | [S3_CONNECTOR.md](S3_CONNECTOR.md) |
| public rates and FX | `feed` | [FEEDS_CONNECTOR.md](FEEDS_CONNECTOR.md) |
| the packs' sample entities | `demo` (runs as itself, no file) | [DEMO_CONNECTOR.md](DEMO_CONNECTOR.md) |

The comparison of what each can do (history, live ticks, search, reverse lookups) and the rules of thumb are in
[Which connector should I use?](CONNECTOR_DEVELOPER_GUIDE.md#which-connector-should-i-use). To write a plugin of your own, see
[The SourcePlugin SPI](CONNECTOR_DEVELOPER_GUIDE.md#the-sourceplugin-spi). All about the files, live reload, the Admin page, the API and the CLI:
[CONNECTOR_FILES.md](CONNECTOR_FILES.md).
