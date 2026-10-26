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
---
template: cassandra
category: template
summary: The six banking data domains read from Apache Cassandra (also ScyllaDB), one connector file per domain keyspace, loaded by tools/load-cassandra.sh.
connectors:
- cassandra
files:
- collateral-store.yaml
- credit-store.yaml
- market-store.yaml
- reference-store.yaml
- risk-store.yaml
- trade-endur.yaml
doc: docs/connectors/CASSANDRA_CONNECTOR.md
---
# The cassandra connector templates

Six ready-made connector files for the `cassandra` plugin: each reads one data domain of the banking packs from the keyspace of the
same name, which `tools/load-cassandra.sh` (or any writer keeping the same layout) fills. The trades file is already named
`trade-endur.yaml`, the connector the trading pack routes `trade` to.

## What it reads

Each file serves the kinds of one domain (`settings.domain`, which is also the keyspace): one table per kind, one row per entity per
business day, the JSON document in `doc` and the promoted fields as narrow columns. The kinds are learnt from the tables' comments
(`drishti kind: <kind>`), so the files list kinds only to name what they serve. `reference-store.yaml` reads its twelve kinds in
`effective` mode; the others are snapshots.

## Settings

The settings the files set (every other setting takes the plugin's default; the full list is in the plugin's settings table):

| Setting or variable | Default in the files | Meaning |
|---|---|---|
| `domain` | `trading`, `risk`, `market`, `reference`, `credit`, `collateral` (one per file) | The data domain, and the keyspace (no `keyspace` set) |
| `contact-points` ← `DRISHTI_CASSANDRA_CONTACT_POINTS` | `localhost:9042` | `host:port` list, comma separated |
| `local-datacenter` ← `DRISHTI_CASSANDRA_DATACENTER` | `datacenter1` | The data centre the driver prefers (`nodetool status` names it) |
| `username` ← `DRISHTI_CASSANDRA_USERNAME` | empty | The user; empty is a node with no sign-in (development) |
| `password` ← `DRISHTI_CASSANDRA_PASSWORD` | empty | The password, from the environment only |
| `mode.<kind>` | `effective` for the twelve reference kinds | The newest row on or before the date asked |

Mutual TLS and DataStax Astra are in `../other/cassandra-tls.yaml` and `../other/cassandra-astra.yaml`.

## Data structure

For each file, the keyspace must hold one table per kind plus the two catalogue tables the loader writes with `--create`:

```sql
CREATE TABLE "trading"."trade" ("business_date" date, "generation" int, "bucket" smallint, "id" text,
    "doc" text, "trade_id" text, "portfolio" text, …,                        -- the document and the promoted columns
    PRIMARY KEY (("business_date", "generation", "bucket"), "id")) WITH comment = 'drishti kind: trade';
CREATE TABLE "trading"."entity_days" (… PRIMARY KEY (("kind", "id"), "business_date"));
CREATE TABLE "trading"."kind_days"   (… "generation" int, "buckets" int, "rows" bigint, "loaded_at" timestamp, PRIMARY KEY ("kind", "business_date"));
```

| File | Keyspace | Kinds |
|---|---|---|
| `trade-endur.yaml` | `trading` | `trade` (every source system's trades in one table) |
| `risk-store.yaml` | `risk` | `var`, `stress-scenario`, `stress-result`, `frtb-sensitivity`, `pnl-explain` |
| `market-store.yaml` | `market` | twenty market-data kinds |
| `reference-store.yaml` | `reference` | `counterparty`, `book`, `desk`, `trader`, `csa`, `legal-entity` and six more, effective |
| `credit-store.yaml` | `credit` | `netting-set`, `credit-limit`, `cva`, `sa-ccr` |
| `collateral-store.yaml` | `collateral` | `collateral-balance`, `margin-call`, `simm` |

The id rule (bucket = Murmur3 of the id), generations and promoted columns are in
[the cassandra connector's README](../../../plugins/drishti-plugin-cassandra/README.md), section *Data structure*.

## Examples

```bash
cp config/connectors.examples/cassandra/*.yaml config/connectors/       # or only the domains you want
export DRISHTI_CASSANDRA_CONTACT_POINTS=cass-1.example.com:9042 DRISHTI_CASSANDRA_DATACENTER=dc-east
export DRISHTI_CASSANDRA_USERNAME=drishti_reader DRISHTI_CASSANDRA_PASSWORD=…
tools/load-cassandra.sh localhost:9042 datacenter1 --create --create-keyspace 1     # a development node: the samples x 10 days
```

On Windows the loader is `tools\windows\load-cassandra.ps1`. A copied file replaces the connector file of the same name (settings are
not merged) and is applied within seconds; with `trade-endur.yaml` on Cassandra, `trade-calypso` and `trade-murex` may be switched off.

<!-- files:begin (generated by tools/connector_readmes.py from the files in this folder) -->
| File | Plugin | Kinds | What it is |
|---|---|---|---|
| `collateral-store.yaml` | `cassandra` | `collateral-balance`, `margin-call`, `simm` | collateral-store read from Apache Cassandra (loaded by tools/load-cassandra.sh) |
| `credit-store.yaml` | `cassandra` | `netting-set`, `credit-limit`, `cva`, `sa-ccr` | credit-store read from Apache Cassandra (loaded by tools/load-cassandra.sh) |
| `market-store.yaml` | `cassandra` | `ir-curve`, `repo-curve`, `fx-spot`, `fx-forward-curve`, `fx-vol-surface`, `ir-vol-cube`, `cap-vol-surface`, `equity`, `equity-index`, `dividend-curve`, `equity-vol-surface`, `credit-curve`, `inflation-index`, `inflation-curve`, `commodity`, `commodity-curve`, `commodity-vol-surface`, `rate-fixing`, `bond`, `correlation-matrix` | market-store read from Apache Cassandra (loaded by tools/load-cassandra.sh) |
| `reference-store.yaml` | `cassandra` | `counterparty`, `counterparty-group`, `issuer`, `agreement`, `ccp`, `legal-entity`, `book`, `desk`, `trader`, `calendar`, `csa`, `clearing-account` | reference-store read from Apache Cassandra (loaded by tools/load-cassandra.sh) |
| `risk-store.yaml` | `cassandra` | `var`, `stress-scenario`, `stress-result`, `frtb-sensitivity`, `pnl-explain` | risk-store read from Apache Cassandra (loaded by tools/load-cassandra.sh) |
| `trade-endur.yaml` | `cassandra` | `trade` | trades read from Apache Cassandra (loaded by tools/load-cassandra.sh) |
<!-- files:end -->

## Troubleshooting

- `DOWN: … no connection to <host:port>`: wrong `contact-points`, a closed port or the node down (`nc -z <host> 9042`).
- `DOWN: keyspace … does not exist` / `has no kind_days table`: load with `--create` (and `--create-keyspace` on a development node).
- A log-in error: set `DRISHTI_CASSANDRA_USERNAME` and `DRISHTI_CASSANDRA_PASSWORD` in the server's environment.
- A new day does not appear: the catalogue is re-read every `refresh-seconds`; Admin → Caches → purge.

## See also

- [The Cassandra connector document](../../../docs/connectors/CASSANDRA_CONNECTOR.md): layout, loading, retention, sizing, security.
- [The cassandra connector's README](../../../plugins/drishti-plugin-cassandra/README.md) and [connector files](../../../docs/connectors/CONNECTOR_FILES.md).
- [TLS](../../../docs/connectors/TLS.md) and [the demo data](../../../docs/connectors/DEMO_DATA.md).
