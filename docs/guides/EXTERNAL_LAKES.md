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
# Using Drishti with Databricks Delta Lake or Apache Iceberg

Your lake is already there: Databricks writes Delta tables, or Spark, Trino, Snowflake or Databricks write Iceberg tables. This page
says how Drishti reads them, which plugin to choose, and what to do when a table does not fit. The details are in the connector guides,
linked at each step: [DELTA_CONNECTOR.md](../connectors/DELTA_CONNECTOR.md), [ICEBERG_CONNECTOR.md](../connectors/ICEBERG_CONNECTOR.md)
and [CLUSTER_DEPLOYMENT.md](../admin/CLUSTER_DEPLOYMENT.md#2-drishti-on-aws-ecs-efs-and-s3).

## 1. No code, only configuration

What Drishti needs is the table's **shape**, not the product that wrote it. It reads Delta and Iceberg with its own readers (Delta
Kernel and drishti-iceberg, inside the server), so **no Databricks cluster, Spark or warehouse is needed to read**: the server reads the
table's files from storage. A table in the shape below is one connector file away.

**The shape**, for Delta and Iceberg alike: one row per entity per business day.

| Column | Content |
|---|---|
| `id` | the entity's id (`MX-20000001`), string |
| `doc` | the whole record as JSON text: what a view shows |
| `business_date` | the business day, a date; the partition |
| promoted columns | the fields people search by (`TRADE_ID`, `PRODUCT_TYPE`, `COUNTERPARTY` …), holding exactly the document's values; named in the connector's `layout.<kind>.columns` |

Each day sorted by `id` in files of disjoint id ranges, with small row groups, reads fastest
([DELTA_CONNECTOR.md section 3](../connectors/DELTA_CONNECTOR.md#3-what-is-on-disk),
[ICEBERG_CONNECTOR.md section 3](../connectors/ICEBERG_CONNECTOR.md#3-what-is-stored)).

## 2. Databricks Delta Lake: `plugin: delta`

- **Where.** `root` is the folder above the domain: `s3://bucket/prefix` (the default `native` engine), or `abfs://…` (Azure) and
  `gs://…` (Google Cloud) with `engine: hadoop` ([section 16](../connectors/DELTA_CONNECTOR.md#16-engines-native-and-hadoop)). The
  table is `<root>/<domain>/<kind>`, or `table.<kind>` names its folder.
- **Credentials.** S3: `s3.access-key`/`s3.secret-key` as `${ENV}` references, or the AWS default chain (an instance or task role).
  Azure and Google Cloud: Hadoop's own keys through `hadoop.<key>`. A private CA: `tls.*`
  ([Security](../connectors/DELTA_CONNECTOR.md#security-tls-and-credentials)).
- **Writing the shape from Databricks.** The Spark job and the table properties are in
  [section 5](../connectors/DELTA_CONNECTOR.md#5-writing-the-lake). On Databricks, `OPTIMIZE … ZORDER BY (id)` or liquid clustering on
  `id` keeps each day in id order.
- **Deletion vectors.** Rows Databricks deletes in place are read correctly, but a table carrying deletion vectors is searched through
  its documents (slower) until `OPTIMIZE` rewrites the files ([section 12](../connectors/DELTA_CONNECTOR.md#12-limits-and-trade-offs)).
- **Unity Catalog.** Drishti reads Delta **by storage path**, not through Unity Catalog: point `root` at the table's location and give
  the server read access to that storage. External tables are the simplest. See the next section.

### With or without Unity Catalog

| | Without Unity (today, the default) | With Unity (**2.9.6**, opt-in per connector; not built) |
|---|---|---|
| How the table is found | its storage path (`root`, `domain`, `table.<kind>`) | its name, `catalog.schema.table` |
| Credentials | storage credentials the server holds (keys, a role) | Unity Catalog's temporary credentials (credential vending) |
| Permissions | the storage's | Unity Catalog's grants |
| Use it when | there is no Databricks or Unity Catalog, or the tables are external tables whose storage Drishti may read | the tables are UC-managed, or the bank grants no direct storage access |

**Iceberg through Unity Catalog's REST catalog works today** (section 3). The table's shape is the same either way.

## 3. Apache Iceberg: `plugin: iceberg`

- **Path-based tables** (`catalog: hadoop`, the default): `root` is a folder or `s3://…`; the table is `<root>/<domain>/<kind>`.
- **REST catalogs** (`catalog: rest`, `uri`, `warehouse`, `credential` or `token`): Apache Polaris, Snowflake Open Catalog, AWS Glue's
  Iceberg REST endpoint, Databricks Unity Catalog, Nessie, Lakekeeper, Gravitino. A catalog that vends credentials supplies each table's
  own (`vended-credentials: true`, the default) ([section 4](../connectors/ICEBERG_CONNECTOR.md#4-catalogs-path-based-tables-and-rest-catalogs)).
- **AWS Glue and S3 Tables** through their REST endpoints with SigV4 signing: built in 2.9.5, **not yet run on AWS** (no account was
  available; the product owner runs `deploy/aws/iceberg/check-glue.sh`)
  ([section 19](../connectors/ICEBERG_CONNECTOR.md#19-aws-glue-s3-tables-and-the-iam-they-need-295),
  [CLUSTER_DEPLOYMENT.md › Drishti on AWS: ECS, EFS and S3, section 22](../admin/CLUSTER_DEPLOYMENT.md#22-iceberg-on-aws-295)).
- Tables written by Spark, Databricks, Trino or Snowflake are read the same way, in the same shape.
- **Format version 3** (deletion vectors, new types) is **not supported**: such a table is refused by name, never read wrongly. Write
  format version 2.

## 4. Choosing

Delta is the default everywhere (every pack's connector files read Delta). Iceberg is a configuration switch: the same connector
file with `plugin: iceberg`. On the same rows both give the same answers; VaR and exposure are equal to the cent
([VAR_GUIDE.md section 29.3](VAR_GUIDE.md#293-how-the-lake-tools-and-the-delta-plugin-carry-a-vector-as-built); the switch on the demo
data, step by step: [tutorial 29](../tutorials/29-var-on-iceberg.md)). Choose what your lake already is.

## 5. When a change is needed

- **A plain table without a `doc` column.** For now a small job writes a Drishti-shaped copy beside it, for example in Spark:

  ```python
  from pyspark.sql import functions as F
  src = spark.read.table("risk.trades_plain").where("business_date = '2026-09-30'")
  shaped = src.select(F.col("TRADE_ID").cast("string").alias("id"),
                      F.to_json(F.struct(*src.columns)).alias("doc"),
                      F.col("business_date").cast("date"),
                      "TRADE_ID", "PRODUCT_TYPE", "COUNTERPARTY", "PORTFOLIO")      # the promoted columns
  (shaped.repartitionByRange(4, "id").sortWithinPartitions("id")
         .write.format("delta").mode("overwrite").option("replaceWhere", "business_date = '2026-09-30'")
         .partitionBy("business_date").save("s3://risk-lake/delta/trading/trade"))
  ```

  **2.9.6** adds reading plain tables directly, driven by per-table configuration (planned).
- **Delta through Unity Catalog's credential vending:** 2.9.6, opt-in (above).
- **Iceberg format version 3:** later.

## 6. Connector files, one per case

Each is a file in `config/connectors/` (the file name is the connector's name; [CONNECTOR_FILES.md](../connectors/CONNECTOR_FILES.md)).

**Delta on S3** (`trade-endur.yaml`):

```yaml
plugin: delta
kinds: [trade]
settings:
  root: s3://risk-lake/delta            # the table: s3://risk-lake/delta/trading/trade_endur
  domain: trading
  table.trade: trade_endur
  s3.region: eu-west-2                  # no keys: the AWS default chain (instance or task role)
  layout.trade.columns: TRADE_ID,PRODUCT_TYPE,COUNTERPARTY,PORTFOLIO,TRADE_STATUS
```

**Delta on Azure** (ADLS Gen2):

```yaml
plugin: delta
kinds: [trade]
settings:
  root: abfs://lake@riskstorage.dfs.core.windows.net/delta
  engine: hadoop                        # abfs:// needs the hadoop engine
  domain: trading
  hadoop.fs.azure.account.key.riskstorage.dfs.core.windows.net: ${AZURE_STORAGE_KEY}
  layout.trade.columns: TRADE_ID,PRODUCT_TYPE,COUNTERPARTY,PORTFOLIO,TRADE_STATUS
```

**Iceberg through Unity Catalog's REST catalog:**

```yaml
plugin: iceberg
kinds: [trade]
settings:
  catalog: rest
  uri: https://<workspace>.cloud.databricks.com/api/2.1/unity-catalog/iceberg-rest   # your workspace's Iceberg REST path
  warehouse: risk_catalog               # the Unity catalog
  token: ${DATABRICKS_TOKEN}
  domain: trading                       # the schema (the REST namespace)
  layout.trade.columns: TRADE_ID,PRODUCT_TYPE,COUNTERPARTY,PORTFOLIO,TRADE_STATUS
```

**Iceberg on AWS Glue** (its REST endpoint; built, not yet run on AWS):

```yaml
plugin: iceberg
kinds: [pnl-vector]
settings:
  catalog: rest
  uri: https://glue.eu-west-2.amazonaws.com/iceberg
  warehouse: "123456789012"             # the account id: the Glue catalog
  domain: risk                          # the Glue database
  catalog.rest.sigv4-enabled: "true"
  catalog.rest.signing-name: glue
  catalog.rest.signing-region: eu-west-2
  s3.region: eu-west-2
```

Check a file before it serves anyone: `drishti.py connector test <file>` tries it on the real source and says what failed, in words.
Every setting: [DELTA_CONNECTOR.md section 14](../connectors/DELTA_CONNECTOR.md#14-settings),
[ICEBERG_CONNECTOR.md section 15](../connectors/ICEBERG_CONNECTOR.md#15-settings).

---

*Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. Unauthorised copying, use or distribution is prohibited.*
