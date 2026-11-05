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
# Arrow Flight SQL from ODBC tools (Excel, Power BI, Tableau)

The guide: [FLIGHT_GUIDE.md](../FLIGHT_GUIDE.md) (section 12, Flight SQL); every call: [FLIGHT_REFERENCE.md](../FLIGHT_REFERENCE.md) (section 6).
SQL here is read-only SQL over the BI models (DSQL).

## Install

An Arrow Flight SQL ODBC driver (third-party). It is an **installer**, so it cannot go on machines where no program may be installed (the
Windows QA machine among them): there, use the [JDBC](JDBC.md) jar or the [Python](PYTHON.md) client instead.

## Connect

A DSN with: host `drishti-1.bank.example`, port `18815`, encryption (TLS) on, the bank's CA, and an API token with `data:read` as the
token (or as the password, with any user name). Name the BI model with the driver's extra connection property `model=<model>` when it
passes such properties as headers; otherwise begin each statement with a line `-- model: <model>`.

## Read

In Excel: Data → Get Data → From ODBC → the DSN → a schema (a BI model) → a table (one of its aliases), or Advanced options with a
statement such as `-- model: trading-risk@1` then `SELECT trades.DESK, MEASURE('Notional') FROM trades GROUP BY trades.DESK`. In
Power BI the same through its ODBC connector.

The ODBC driver is a third-party installer and is not run in Drishti's own test runs. The same statement through the Flight SQL JDBC
driver, on the test data of 2026-09-30, answers one row per desk (the figures an ODBC client receives are the same, from the same service):

```text
trades.DESK	Notional
DESK-COMM	8.850170019852502E11
DESK-CREDIT	3.4835024551415E11
...
DESK-RATES	6.0533722239374E11
DESK-SFT	2.27897073212E11
DESK-STRUCT	1.06026316978E11
```

## Without an installer

Where the ODBC driver may not be installed, Excel gets a workbook from a Flight read through Python instead (no add-in):
[examples/clients/excel](../../../examples/clients/excel/README.md).

## Errors

The tool shows the service's message, starting with its code (`DRS-5603` no model named or a statement refused, `DRS-5602` not allowed,
`DRS-5604` the rows limit).
