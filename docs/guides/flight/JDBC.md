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
# Arrow Flight SQL from JDBC tools (DBeaver, DataGrip, Excel through a bridge)

The guide: [FLIGHT_GUIDE.md](../FLIGHT_GUIDE.md); every request: [FLIGHT_REFERENCE.md](../FLIGHT_REFERENCE.md).

**Status:** Flight SQL (read-only SQL over BI models) is the second step of this release and is not served yet. This page says what
the tool will need; its examples are filled in when the service answers Flight SQL.

## Install

The Apache Arrow Flight SQL JDBC driver: one jar (`org.apache.arrow:flight-sql-jdbc-driver`, version 19.0.0 or newer), no installer, so
it also runs where no program may be installed.

## Connect

```text
jdbc:arrow-flight-sql://drishti-1.bank.example:18815/?useEncryption=true&token=<API token with data:read>&trustStore=bank-ca.jks&trustStorePassword=…
```

In DBeaver: Database → New connection → *Arrow Flight SQL* (or a generic JDBC connection with the jar), the URL above.

## Read

`SELECT DESK, SUM(MTM) FROM "risk-summary" GROUP BY DESK`: SQL over a model, read only, compiled to the same plan as an MQ query.

(output from the demo lake: to be filled in the heavy run)

## Errors

The driver shows the service's message, starting with the code (`DRS-5602: …`).
