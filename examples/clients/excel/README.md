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
# Excel from a Flight read (no add-in)

`flight_to_excel.py` reads through Arrow Flight with the Python client, read only, and writes an Excel workbook on your machine with
`openpyxl`: nothing is installed into Excel, no add-in, no driver, so it runs where no program may be installed. The index of every
example: [../README.md](../README.md).

## Run

```bash
pip install pyarrow openpyxl pyyaml                    # in a virtual environment of your own
export DRISHTI_FLIGHT_URL=grpc+tls://<a node's real address>:18815
export DRISHTI_TOKEN_FILE=~/.config/drishti/token      # an API token with data:read (or DRISHTI_TOKEN)
export DRISHTI_FLIGHT_CA=/path/to/ca.pem
python3 examples/clients/excel/flight_to_excel.py --out desk-rates.xlsx --scope DESK:RATES
```

The workbook:

| Sheet | Holds |
|---|---|
| Summary | the scope, date and currency; the engine's VaR and ES as values; live formulas over the P&L sheet (scenarios, the worst P&L, how many losses pass the VaR and their average, to compare with the engine's ES) |
| By desk | the `trades-basic` model's Notional, MTM and Trades by desk |
| P&L | one row per historical scenario: `SCENARIO`, `SCENARIO_DATE`, `PNL` |
| About | as whom, when and what was read |

On the test data of 2026-09-30:

```text
wrote desk-rates.xlsx (10 desks, 509 scenarios)
```

The workbook, read back (the first rows of each sheet; Excel computes the formulas when it opens the file):

```text
Summary   Scope DESK:RATES · Business date 2026-09-30 · Currency USD · VaR (engine) 74633680 · ES (engine) 75377415
          Scenarios =COUNT('P&L'!C2:C510) · Worst P&L =MIN('P&L'!C2:C510)
          Scenarios worse than -VaR =COUNTIF('P&L'!C2:C510,"<"&-B5) · Average of those =IFERROR(AVERAGEIF('P&L'!C2:C510,"<"&-B5),"")
By desk   trade.DESK  Notional            MTM                 Trades
          DESK-COMM   885017001985.2504   -395383250.5300001  5990
          DESK-RATES  605337222393.74     4484481851.440001   1832
          ...                                                  (10 desks)
P&L       SCENARIO  SCENARIO_DATE  PNL
          0         2024-09-17     11293350
          1         2024-09-18     17864629
          ...                                                  (509 scenarios)
About     Read through Arrow Flight, read only, as ravi · Read at 2026-11-04T21:41:20
          Requests: model trades-basic by trade.DESK; measure('var.scenarios', 'DESK:RATES')
```

## Into an open workbook with xlwings

Where Excel is installed and Python may drive it, `xlwings` writes into the workbook you have open (no add-in needed for this use):

```python
import xlwings as xw
from flight_setup import connect                      # examples/clients/python on sys.path
with connect() as f:
    df = f.measure("var.scenarios", "DESK:RATES").to_pandas()
xw.books.active.sheets.add("P&L").range("A1").options(index=False).value = df
```

## Power Query through ODBC

Excel's Data → Get Data → From ODBC reads BI models through Flight SQL with an Arrow Flight SQL ODBC driver (a schema per model, a table
per alias). That driver is an **installer**, so it is not for machines where no program may be installed (the Windows QA machine among
them); the Python route above needs none. The DSN and the model: [docs/guides/FLIGHT_GUIDE.md](../../../docs/guides/FLIGHT_GUIDE.md#74-odbc-excel-power-bi-tableau).
