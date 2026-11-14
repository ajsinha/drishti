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
template: other
category: template
summary: Single connector files that show one setup each - TLS and mutual TLS, certificate log-ins, cloud services (Confluent Cloud, DataStax Astra, Iceberg REST catalogs, S3), an outbound proxy, and the on-demand news and yfinance connectors.
connectors:
- activemq
- aerospike
- cassandra
- delta
- iceberg
- iceberg-external
- jdbc
- kafka
- mongodb
- news
- rabbitmq
- redis
- rest
- s3
- yfinance
files:
- activemq-ssl.yaml
- aerospike-tls.yaml
- cassandra-astra.yaml
- cassandra-tls.yaml
- customer-lake.yaml
- delta-private-ca.yaml
- iceberg-external-risk-mart.yaml
- iceberg-rest-private-ca.yaml
- kafka-confluent-cloud.yaml
- kafka-mtls-pem.yaml
- mongodb-x509.yaml
- news.yaml
- ops-events.yaml
- orders-queue.yaml
- positions-mongo.yaml
- postgres-tls-client-cert.yaml
- postgres-tls.yaml
- pricing-api.yaml
- rabbitmq-external.yaml
- redis-tls.yaml
- rest-mtls.yaml
- rest-via-proxy.yaml
- s3-private-ca.yaml
- s3-via-proxy.yaml
- trade-blotter-kafka.yaml
- trading-stream.yaml
- yfinance.yaml
doc: docs/guides/DATA_GUIDE.md
---
# The other connector templates

One file per setup, across the plugins: how to reach a store over TLS, log in with a certificate, use a cloud service or a corporate
proxy. Each names example hosts (`*.example.com`) and paths (`/etc/drishti/tls/…`): copy it, change those to yours, and keep every
secret an `${ENV}` or `file:` reference.

## What it reads

Whatever its plugin reads: brokers (`kafka`, `activemq`, `rabbitmq`), databases and stores (`jdbc`, `mongodb`, `redis`, `aerospike`,
`cassandra`), lakes (`delta`, `iceberg`, `iceberg-external`), object storage (`s3`), HTTP services (`rest`) and the two connectors that
compute documents on demand (`news`, `yfinance`, both `enabled: false`). The files table below names each file's plugin and kinds; the
plugin's own README and document say what it reads.

## Settings

The settings and variables the files set, by theme (paths and hosts are examples):

| Setting or variable | Default in the files | Meaning |
|---|---|---|
| **TLS** `tls.enabled` | `true` where set | Turn TLS on (a `rediss://`, `amqps://`, `ssl://` or `https://` address does so by itself) |
| `tls.ca-file` | `/etc/drishti/tls/ca.pem` (`ca-bundle.pem` for Kafka) | The private CA to trust, PEM |
| `tls.cert-file`, `tls.key-file` | `/etc/drishti/tls/client.pem`, `client.key` | The client certificate and key for mutual TLS or a certificate log-in |
| `tls.key-password-file` | `/run/secrets/kafka-client-key-password`, `/run/secrets/pg-client-key-password` | The password of an encrypted key, from a file |
| `tls.truststore` ← `*_TRUSTSTORE_PASSWORD` | `/etc/drishti/tls/ca.p12` | The CA as PKCS12; its password from `KAFKA_`, `ACTIVEMQ_`, `RABBIT_`, `MONGO_` or `PRICING_TRUSTSTORE_PASSWORD` |
| `tls.keystore` ← `KAFKA_KEYSTORE_PASSWORD` | `/etc/drishti/tls/client.p12` | The client certificate as PKCS12 (`trading-stream.yaml`) |
| **Certificate log-ins** `auth-mode: pki`, `auth-mechanism: external` / `x509`, `user` with no password | per file | Aerospike PKI, RabbitMQ EXTERNAL, MongoDB X.509, PostgreSQL `cert` log-in |
| **Passwords** `DRISHTI_DB_PASSWORD`, `DRISHTI_REDIS_PASSWORD`, `DRISHTI_CASSANDRA_PASSWORD`, `ACTIVEMQ_USER`, `ACTIVEMQ_PASSWORD` | none | Store log-ins, from the environment |
| **Cloud** `flavour: confluent` ← `CONFLUENT_API_KEY`, `CONFLUENT_API_SECRET` | none | Confluent Cloud: SASL_SSL with PLAIN |
| `schema-registry.url`, `schema-registry.basic-auth` ← `CONFLUENT_SR_KEY`, `CONFLUENT_SR_SECRET` | none | Schema Registry for Avro payloads |
| `secure-connect-bundle`, `token` ← `DRISHTI_ASTRA_TOKEN` | `/etc/drishti/astra/secure-connect-drishti.zip` | DataStax Astra |
| `catalog: rest`, `uri` ← `RISK_MART_CATALOG_URI`, `credential` ← `RISK_MART_CREDENTIAL` | `https://polaris.example.com/api/catalog` | An Iceberg REST catalog (Polaris, Snowflake Open Catalog, Glue) |
| `s3.*` / `access-key`, `secret-key` ← `LAKE_ACCESS_KEY`, `LAKE_SECRET_KEY`, `MINIO_*`, `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY` | none (one file reads `file:/run/secrets/lake-secret-key`) | S3 or S3-compatible storage |
| **HTTP** `header.Authorization` ← `PRICING_TOKEN`, `VENDOR_TOKEN` | `Bearer ${…}` | A bearer token on every request (`rest`) |
| **Proxy** `proxy.url`, `proxy.username`, `proxy.password` ← `PROXY_PASSWORD`, `proxy.no-proxy` | `http://proxy.corp.example:3128`, `svc-drishti` | The corporate proxy (`rest-via-proxy.yaml`, `s3-via-proxy.yaml`); left out: the site default `drishti.http.proxy.*` |
| **On demand** `enabled` | `false` | `news.yaml` and `yfinance.yaml` stay off until the site decides |

## Data structure

Each file needs what its plugin expects; the detail is in the plugin's README (`plugins/drishti-plugin-<x>/README.md`) and document:

- **Kafka** (`trading-stream.yaml`, `kafka-mtls-pem.yaml`, `kafka-confluent-cloud.yaml`): a compacted topic `trades`, keyed by trade id,
  the value the document (`id-field: TRADE_ID`); on Confluent Cloud, Avro through the Schema Registry.
- **ActiveMQ, RabbitMQ** (`activemq-ssl.yaml`, `ops-events.yaml`, `orders-queue.yaml`, `rabbitmq-external.yaml`): queues `orders` or
  `events` whose JSON bodies are envelopes `{kind, id, doc}` (no `kind.<queue>` is set).
- **PostgreSQL** (`postgres-tls*.yaml`): table mode, `entities (kind, id, business_date, doc, <promoted columns>)`.
- **MongoDB** (`positions-mongo.yaml`, `mongodb-x509.yaml`): the `positions` collection of database `drishti`, as its loader writes it.
- **Redis, Aerospike, Cassandra** (`redis-tls.yaml`, `aerospike-tls.yaml`, `cassandra-tls.yaml`, `cassandra-astra.yaml`): the domain
  `trading` in each store's layout (Cassandra: keyspace `drishti_trading` on Astra, promoted columns from `layout.trade.columns`).
- **Delta, Iceberg** (`customer-lake.yaml`, `delta-private-ca.yaml`, `iceberg-rest-private-ca.yaml`): a table per kind under the root,
  partitioned by business date. `iceberg-external-risk-mart.yaml` maps tables a Spark pipeline writes: `tables.<kind>` names the table,
  its `id` column(s), its `date` column, renames and links.
- **S3** (`s3-private-ca.yaml`, `s3-via-proxy.yaml`): `<prefix>/<kind>/<id>.json`, and dated `<prefix>/<yyyy-MM-dd>/<kind>/<id>.json`.
- **REST** (`pricing-api.yaml`, `rest-mtls.yaml`, `rest-via-proxy.yaml`): `GET <base-url>/{kind}/{id}` answering the JSON document, `404`
  for unknown ids.
- **News, yfinance**: nothing to hold; ids `NEWS-<name>` and `QUOTE-<symbol>` (internal use only for yfinance).

## Examples

```bash
cp config/connectors.examples/other/trading-stream.yaml config/connectors/trading-stream.yaml
# edit the hosts and paths, then give the server its secrets
export KAFKA_TRUSTSTORE_PASSWORD=… KAFKA_KEYSTORE_PASSWORD=…
python3 tools/drishti.py connector test trading-stream      # or Admin → Connectors → Test connection
```

The file name is the connector's name: name it after the connector a pack routes to (`trading-stream`, `trade-endur`) to replace that
one, or anything new and route a kind to it. The server applies a file within seconds; Admin → Connectors and `drishti.py connector
apply` refuse a credential written in clear. There is no loader for these files: each store is filled as its document says.

<!-- files:begin (generated by tools/connector_readmes.py from the files in this folder) -->
| File | Plugin | Kinds | What it is |
|---|---|---|---|
| `activemq-ssl.yaml` | `activemq` | the plugin's own | Orders from ActiveMQ over SSL |
| `aerospike-tls.yaml` | `aerospike` | the plugin's own | Trades in Aerospike over mutual TLS |
| `cassandra-astra.yaml` | `cassandra` | the plugin's own | Trades in DataStax Astra |
| `cassandra-tls.yaml` | `cassandra` | the plugin's own | Trades in Cassandra over mutual TLS |
| `customer-lake.yaml` | `delta` | the plugin's own | Customer tables in the data lake |
| `delta-private-ca.yaml` | `delta` | the plugin's own | Customer tables in the data lake over TLS |
| `iceberg-external-risk-mart.yaml` | `iceberg-external` | the plugin's own | risk-mart tables written by Spark, read through drishti-iceberg (no Hadoop) |
| `iceberg-rest-private-ca.yaml` | `iceberg` | the plugin's own | Iceberg tables behind a REST catalog, object store over TLS |
| `kafka-confluent-cloud.yaml` | `kafka` | the plugin's own | Trades from Confluent Cloud, Avro through Schema Registry |
| `kafka-mtls-pem.yaml` | `kafka` | the plugin's own | Trades over mutual TLS, PEM certificates |
| `mongodb-x509.yaml` | `mongodb` | the plugin's own | Positions in MongoDB, certificate log-in |
| `news.yaml` | `news` | the plugin's own |  |
| `ops-events.yaml` | `activemq` | the plugin's own |  |
| `orders-queue.yaml` | `rabbitmq` | the plugin's own | Orders from the order-management broker |
| `positions-mongo.yaml` | `mongodb` | the plugin's own |  |
| `postgres-tls-client-cert.yaml` | `jdbc` | the plugin's own | Trades in PostgreSQL, certificate log-in |
| `postgres-tls.yaml` | `jdbc` | the plugin's own | Trades in PostgreSQL, verified TLS |
| `pricing-api.yaml` | `rest` | the plugin's own |  |
| `rabbitmq-external.yaml` | `rabbitmq` | the plugin's own | Orders from RabbitMQ, logged in by certificate |
| `redis-tls.yaml` | `redis` | the plugin's own | Trades in Redis over mutual TLS |
| `rest-mtls.yaml` | `rest` | the plugin's own | Prices from the pricing API over mutual TLS |
| `rest-via-proxy.yaml` | `rest` | the plugin's own | Prices from a vendor API through the corporate proxy |
| `s3-private-ca.yaml` | `s3` | the plugin's own | Documents in MinIO over TLS |
| `s3-via-proxy.yaml` | `s3` | the plugin's own | Trade documents in S3, through the corporate proxy |
| `trading-stream.yaml` | `kafka` | the plugin's own | Live trades from the trading Kafka cluster |
| `yfinance.yaml` | `yfinance` | the plugin's own |  |
| `activemq-ssl.yaml` | `activemq` | `order` | Orders from ActiveMQ over SSL |
| `aerospike-tls.yaml` | `aerospike` | `trade` | Trades in Aerospike over mutual TLS |
| `cassandra-astra.yaml` | `cassandra` | `trade` | Trades in DataStax Astra |
| `cassandra-tls.yaml` | `cassandra` | `trade` | Trades in Cassandra over mutual TLS |
| `customer-lake.yaml` | `delta` | `customer` | Customer tables in the data lake |
| `delta-private-ca.yaml` | `delta` | `customer` | Customer tables in the data lake over TLS |
| `iceberg-external-risk-mart.yaml` | `iceberg-external` | `trade`, `counterparty`, `market-quote` | risk-mart tables written by Spark, read through drishti-iceberg (no Hadoop) |
| `iceberg-rest-private-ca.yaml` | `iceberg` | `trade` | Iceberg tables behind a REST catalog, object store over TLS |
| `kafka-confluent-cloud.yaml` | `kafka` | `trade` | Trades from Confluent Cloud, Avro through Schema Registry |
| `kafka-mtls-pem.yaml` | `kafka` | `trade` | Trades over mutual TLS, PEM certificates |
| `mongodb-x509.yaml` | `mongodb` | `position` | Positions in MongoDB, certificate log-in |
| `news.yaml` | `news` | `news` |  |
| `ops-events.yaml` | `activemq` | `event` |  |
| `orders-queue.yaml` | `rabbitmq` | `order` | Orders from the order-management broker |
| `positions-mongo.yaml` | `mongodb` | `position` |  |
| `postgres-tls-client-cert.yaml` | `jdbc` | `trade` | Trades in PostgreSQL, certificate log-in |
| `postgres-tls.yaml` | `jdbc` | `trade` | Trades in PostgreSQL, verified TLS |
| `pricing-api.yaml` | `rest` | `price` |  |
| `rabbitmq-external.yaml` | `rabbitmq` | `order` | Orders from RabbitMQ, logged in by certificate |
| `redis-tls.yaml` | `redis` | `trade` | Trades in Redis over mutual TLS |
| `rest-mtls.yaml` | `rest` | `price` | Prices from the pricing API over mutual TLS |
| `rest-via-proxy.yaml` | `rest` | `price` | Prices from a vendor API through the corporate proxy |
| `s3-private-ca.yaml` | `s3` | `trade` | Documents in MinIO over TLS |
| `s3-via-proxy.yaml` | `s3` | `trade` | Trade documents in S3, through the corporate proxy |
| `trade-blotter-kafka.yaml` | `kafka` | `trade` | The demo's live trades, and the trade blotter's amendments written back to them |
| `trading-stream.yaml` | `kafka` | `trade` | Live trades from the trading Kafka cluster |
| `yfinance.yaml` | `yfinance` | `quote` |  |
<!-- files:end -->

## Troubleshooting

- `PKIX path building failed`: the server's certificate is not trusted; set `tls.ca-file` or `tls.truststore`.
- `No subject alternative DNS name matching …`: connect by a name the certificate carries (or re-issue it).
- The handshake is closed with no other cause: the server wants a client certificate; set `tls.cert-file` and `tls.key-file`.
- A start-up message naming a file or a password: a path is wrong or a store password does not open it (TLS document, section 8).
- `the proxy … refused the credentials (HTTP 407 …)`: check `proxy.username` and `PROXY_PASSWORD`.
- The connector does not start: an `${ENV}` reference has no value in the server's environment.

## See also

- [Connector files](../../../docs/guides/DATA_GUIDE.md#9-connector-files-and-admin--connectors): the folder, the format, live reload, copying these examples.
- [TLS and the outbound proxy](../../../docs/guides/DATA_GUIDE.md#10-tls-and-the-outbound-proxy): every `tls.*` and `proxy.*` setting and the start-up checks.
- The plugins' documents: [Kafka](../../../plugins/drishti-plugin-kafka/README.md), [ActiveMQ](../../../plugins/drishti-plugin-activemq/README.md),
  [RabbitMQ](../../../plugins/drishti-plugin-rabbitmq/README.md), [REST](../../../docs/connectors/types/rest/README.md),
  [PostgreSQL](../../../plugins/drishti-plugin-jdbc/README.md), [MongoDB](../../../plugins/drishti-plugin-mongodb/README.md),
  [Redis](../../../plugins/drishti-plugin-redis/README.md), [Aerospike](../../../plugins/drishti-plugin-aerospike/README.md),
  [Cassandra](../../../plugins/drishti-plugin-cassandra/README.md), [Delta Lake](../../../plugins/drishti-plugin-delta/README.md),
  [Iceberg](../../../plugins/drishti-plugin-iceberg/README.md), [external Iceberg](../../../plugins/drishti-plugin-iceberg-external/README.md),
  [S3](../../../plugins/drishti-plugin-s3/README.md), [news](../../../docs/connectors/types/news/README.md), [yfinance](../../../docs/connectors/types/yfinance/README.md).

The Delta and Iceberg files here name a lake (`lake:`, `lake-tables:`); the lake itself, its root or catalog, storage and tables, is the matching
file in [config/lakes.examples/other/](../../lakes.examples/other/): copy both (docs/guides/DATA_LAKE_GUIDE.md).
