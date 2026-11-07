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
# Arrow Flight SQL from JDBC tools (DBeaver, DataGrip, any JDBC program)

The guide: [FLIGHT_GUIDE.md](../FLIGHT_GUIDE.md) (section 12, Flight SQL); every call: [FLIGHT_REFERENCE.md](../FLIGHT_REFERENCE.md) (section 6).
SQL here is read-only SQL over the BI models (DSQL, [API_GUIDE.md › Drishti SQL](../API_GUIDE.md#drishti-sql-apiv1bisql)), answered by the same path
as the BI SQL console.

## Install

The Apache Arrow Flight SQL JDBC driver: one jar, `org.apache.arrow:flight-sql-jdbc-driver`, version 19.0.0 or newer (the server's own
Arrow version). No installer, so it runs on machines where no program may be installed (the Windows QA machine included). On Java 17 or
later start the tool's JVM with `--add-opens=java.base/java.nio=ALL-UNNAMED` (DBeaver: `dbeaver.ini`, a line under `-vmargs`).

## Connect

```text
jdbc:arrow-flight-sql://drishti-1.bank.example:18815/?useEncryption=true&token=<API token with data:read>&model=trading-risk@1
```

| Property | Means |
|---|---|
| `useEncryption=true` | TLS (always on at Drishti's Flight port) |
| `token=` | a personal API token with `data:read` (or `data:read:bi`); the driver sends it on every call, the service keeps one session for it |
| `trustStore=`, `trustStorePassword=` | the bank's CA as a Java trust store, when the JVM's own does not hold it, with `useSystemTrustStore=false` (the driver reads `trustStore` only then; or `disableCertificateVerification=true` on a test machine only) |
| `model=` | the BI model statements read, unless a statement names its own with a first line `-- model: <model>` (the driver sends this property as the header `model`) |

In DBeaver: Database → New connection → **Arrow Flight SQL** (or a generic JDBC connection with the jar), the URL above. The navigator
shows one catalog, a schema per BI model you may read, and a table per alias of the model with its columns.

## Read

```sql
SELECT trades.DESK AS desk, MEASURE('Notional') AS notional, COUNT(*) AS n
FROM trades
GROUP BY trades.DESK
```

From Java:

```java
try (Connection c = DriverManager.getConnection("jdbc:arrow-flight-sql://drishti-1.bank.example:18815/?useEncryption=true&token="
        + System.getenv("DRISHTI_TOKEN") + "&model=trading-risk@1");
     ResultSet rs = c.createStatement().executeQuery("SELECT trades.DESK, MEASURE('Notional') FROM trades GROUP BY trades.DESK")) {
    while (rs.next()) {
        System.out.println(rs.getString(1) + " " + rs.getDouble(2));
    }
}
```

Run as written against a server with a test PKI (`&trustStore=ca.jks&trustStorePassword=…&useSystemTrustStore=false` added to the URL;
Java 21; the test data of 2026-09-30):

```text
DESK-COMM 8.850170019852502E11
DESK-CREDIT 3.4835024551415E11
DESK-EQD 4.3052971792252997E11
DESK-FI 4.0315015623297003E11
DESK-FX 3.7284221311835E11
DESK-INFL 1.299456220881E11
DESK-MM 1.30992079528E11
DESK-RATES 6.0533722239374E11
DESK-SFT 2.27897073212E11
DESK-STRUCT 1.06026316978E11
```

A prepared statement works the same way (read only, no parameters). Updates, DDL and transactions are refused: Flight SQL is read only here.

## Example

`JdbcFlightSql.java`, plain JDBC with the driver jar: without arguments it lists the models you may read and their tables; with a model
and a statement it runs the statement: [examples/clients/java](../../../examples/clients/java/README.md#jdbc-through-flight-sql).

## Errors

The driver shows the service's message, starting with its code: `DRS-5603` (no model named, a statement DSQL refuses, with the BI SQL
code in the words), `DRS-5602` (the token or the person may not read the model), `DRS-5604` (the rows limit of your role).
