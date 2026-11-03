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
# Arrow Flight from Python

The guide: [FLIGHT_GUIDE.md](../FLIGHT_GUIDE.md); every request: [FLIGHT_REFERENCE.md](../FLIGHT_REFERENCE.md).

## Install

`pip install pyarrow` (16 or newer; it brings `pyarrow.flight`), and pandas or polars if you want them. Copy
`clients/python/drishti_flight.py` next to your script or notebook: one file, no other dependency.

## Connect

```python
from drishti_flight import Flight
f = Flight("grpc+tls://drishti-1.bank.example:18815", token="drk_…", ca="bank-ca.pem")
f.whoami()          # {"person": …, "scopes": ["data:read"], "limits": {…}}
```

`url`, `token` and `ca` may come from `DRISHTI_FLIGHT_URL`, `DRISHTI_TOKEN` and `DRISHTI_FLIGHT_CA`. An application acting for a person
passes `on_behalf_of="alice"`; a service asking for client certificates takes `cert=` and `key=`.

## Read

```python
trades = f.kind("trade", paths=["TRADE_ID", "PRODUCT_TYPE", "QUANTITY"], where=["PRODUCT_TYPE = IRS"])
df = trades.to_pandas()                         # or polars.from_arrow(trades)
pnl = f.measure("var.scenarios", "DESK:RATES", date="2026-09-30")
parts = f.measure("var.parts", "DESK:RATES", by="position")
risk = f.model("risk-summary", {"fields": ["DESK"], "measures": ["MTM"]})
cells = f.pivot({"pivot": 1, "source": "trade", "rows": ["PRODUCT_TYPE"], "values": [{"measure": "count"}]})
f.catalogue()                                   # what this token may read
```

(output from the demo lake: to be filled in the heavy run)

`read` asks for the plan and reads each part from its nodes in parallel, the next node of a part when one does not answer.

## Stream

```python
req = f.request("measure", "measure('var.scenarios', 'DESK:RATES')")
for seq, batch in f.stream(req):                # a pyarrow.RecordBatch after each intraday batch
    print(seq, batch.num_rows)
```

Keep the last `seq`; `f.stream(req, resume=seq)` continues after it.

## Errors

A refusal raises `FlightRefused` with `.code` (`DRS-5604` …) and the words; the codes are in the guide's troubleshooting table.

## The command line

```bash
python3 clients/python/drishti_flight.py --url grpc+tls://drishti-1.bank.example:18815 --token "$DRISHTI_TOKEN" --ca bank-ca.pem \
    kind trade --paths TRADE_ID,PRODUCT_TYPE --where "PRODUCT_TYPE = IRS" > irs.csv
python3 clients/python/drishti_flight.py --out pnl.parquet measure var.scenarios DESK:RATES
```
