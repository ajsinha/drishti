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

The guide: [FLIGHT_GUIDE.md](../FLIGHT_GUIDE.md); every request: [FLIGHT_REFERENCE.md](../FLIGHT_REFERENCE.md).

**Status:** Flight SQL is the second step of this release and is not served yet; this page says what the tools will need.

## Install

An Arrow Flight SQL ODBC driver (third-party; an installer). Where no program may be installed, use the [JDBC](JDBC.md) jar or the
[Python](PYTHON.md) client instead.

## Connect

A DSN with: host `drishti-1.bank.example`, port `18815`, encryption on, the bank's CA, and an API token with `data:read` as the token (or
as the password with any user name).

## Read

In Excel: Data → Get Data → From ODBC → the DSN → a model; in Power BI the same through its ODBC connector.

(output from the demo lake: to be filled in the heavy run)

## Without an installer

Where the ODBC driver may not be installed, Excel gets a workbook from a Flight read through Python instead (no add-in):
[examples/clients/excel](../../../examples/clients/excel/README.md).

## Errors

The tool shows the service's message, starting with the code.
