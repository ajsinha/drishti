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
# Java client examples

Two single-file programs, run with Java's source launcher (no project, no build): read only, like every client example
([the index](../README.md)). The Java client page is [docs/guides/flight/JAVA.md](../../../docs/guides/flight/JAVA.md).

| File | Shows |
|---|---|
| `ReadKindAndVarPnl.java` | connect with a token, read the `trade` kind (five paths of the latest date), then a desk's VaR scenario P&L (`var.scenarios`) with the engine's VaR and ES from the schema's metadata; each part read from the first of its nodes that answers |
| `JdbcFlightSql.java` | SQL over a BI model through the Flight SQL JDBC driver: without arguments the models the token may read and their tables, with a model and a statement its rows |

## The jars

Java 17 or newer (21 recommended). The Arrow Flight jars, version 19.0.0 (the server's own), in a `lib/` folder:

- **With Maven:** `mvn -f examples/clients/java/pom.xml dependency:copy-dependencies -DoutputDirectory=lib` (the `pom.xml` here only
  fetches the jars; it is not part of the repository's build).
- **Without Maven:** download `flight-core`, `arrow-memory-netty` and their dependencies from Maven Central (or your bank's mirror) into
  `lib/`.

## Run

```bash
cd examples/clients/java
export DRISHTI_FLIGHT_URL=grpc+tls://<a node's real address>:18815
export DRISHTI_TOKEN_FILE=~/.config/drishti/token      # a file holding an API token with data:read (or DRISHTI_TOKEN)
export DRISHTI_FLIGHT_CA=/path/to/ca.pem
java --add-opens=java.base/java.nio=ALL-UNNAMED -cp "lib/*" ReadKindAndVarPnl.java DESK:RATES
```

On Windows (PowerShell) the same with `$env:DRISHTI_FLIGHT_URL = "grpc+tls://…:18815"` and `-cp "lib\*"`. Nothing is installed: Java and
jars only.

`--add-opens=java.base/java.nio=ALL-UNNAMED` is Arrow's memory module's need on Java 17 and later.

(output from the demo lake: to be filled in the heavy run)

## JDBC through Flight SQL

```bash
export DRISHTI_FLIGHT_TRUSTSTORE=ca.jks DRISHTI_FLIGHT_TRUSTSTORE_PASSWORD=…
java --add-opens=java.base/java.nio=ALL-UNNAMED -cp "lib/*" JdbcFlightSql.java                 # the models and their tables
java --add-opens=java.base/java.nio=ALL-UNNAMED -cp "lib/*" JdbcFlightSql.java trading-risk@1 \
     "SELECT trades.DESK, COUNT(*) AS n FROM trades GROUP BY trades.DESK"                      # a statement in DSQL over that model
```

The model goes in the connection's `model` property (sent as the header `model`); a statement may name its own on a first line
`-- model: <model>` instead.

A JDBC tool (DBeaver, DataGrip) takes the same driver jar and URL: [docs/guides/flight/JDBC.md](../../../docs/guides/flight/JDBC.md).
