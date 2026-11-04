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
# Tutorial 35 · Data out: from a token to a pandas DataFrame of a desk's VaR scenario P&L

In this tutorial you turn Arrow Flight on, make a token that may read data, read a desk's VaR scenario P&L into pandas, read the same
from Java and from a JDBC tool, follow a live intraday stream, and see your reads on Admin → Data out. Each step shows the UI, the command
line and the code. The guide is [FLIGHT_GUIDE.md](../guides/FLIGHT_GUIDE.md); every request is in [FLIGHT_REFERENCE.md](../guides/FLIGHT_REFERENCE.md).

*Status: the steps are written; each output is filled in from the demo lake in the release's heavy run, marked "(output from the demo
lake: to be filled in the heavy run)" until then.*

## Time to complete

About 20 minutes.

## Before you start

- The server and console running on the demo lake ([tutorial 1](01-first-15-minutes.md)), VaR on (the default).
- Python 3.10 or newer with `pyarrow` and `pandas`; for step 5 a JDK 17 or newer; for step 6 DBeaver.
- The server's certificate and its CA as PEM files (a test PKI is fine on your own machine).

## Step 1 · Turn Flight on

**UI.** Admin → Engines → Arrow Flight → On, reason "tutorial 35"; restart the server.
**File.** `config/engines/flight.yaml`: `enabled: true`, `tls.certificate` and `tls.private-key` naming your PEM files.
**Check.** Admin → Data out shows *Running, port 18815, TLS*.

(output from the demo lake: to be filled in the heavy run)

## Step 2 · Make a token with data:read

**UI.** My account → API tokens → name "risk notebook", 30 days, scope **data:read** → Create; copy the `drk_…` token.
**Command line.**

```bash
curl -s -X POST -H "Authorization: Bearer $DRISHTI_TOKEN" -H "Content-Type: application/json" \
  -d '{"name": "risk notebook", "days": 30, "scopes": ["data:read"]}' https://localhost:18480/api/v1/me/tokens
```

(output from the demo lake: to be filled in the heavy run)

## Step 3 · Who am I

```python
from drishti_flight import Flight
f = Flight("grpc+tls://<your real IP>:18815", token="drk_…", ca="ca.pem")
f.whoami()
f.catalogue()[:5]
```

(output from the demo lake: to be filled in the heavy run)

## Step 4 · A desk's VaR scenario P&L in pandas

```python
pnl = f.measure("var.scenarios", "DESK:<a desk of the demo book>").to_pandas()
pnl.sort_values("PNL").head(5)          # the worst scenarios
pnl.attrs                                # (the schema's metadata: scope, date, VaR, ES)
```

Check by hand: the 5th worst of about 500 scenarios is the 99% VaR shown on the VaR screen for the same desk and date.

(output from the demo lake: to be filled in the heavy run)

Then the positions' vectors (float32, as stored) and their sum against the desk's vector:

```python
parts = f.measure("var.parts", "DESK:<the same desk>", by="position")
```

(output from the demo lake: to be filled in the heavy run)

## Step 5 · The same in Java

The program of the [Java page](../guides/flight/JAVA.md) with the request
`{"form": "measure", "request": {"measure": "var.scenarios", "scope": "DESK:<the same desk>"}}`.

(output from the demo lake: to be filled in the heavy run)

## Step 6 · Through JDBC (DBeaver)

In DBeaver: Database → New connection → Arrow Flight SQL (or a generic JDBC connection with the Flight SQL JDBC jar), the URL of the
[JDBC page](../guides/flight/JDBC.md) with your token and `model=` a BI model you may read; browse its tables (the model's aliases) and
run `SELECT trades.DESK, MEASURE('Notional') FROM trades GROUP BY trades.DESK`.

(output from the demo lake: to be filled in the heavy run)

## Step 7 · A live intraday stream

```python
req = f.request("measure", "measure('var.scenarios', 'DESK:<the same desk>')")
for seq, batch in f.stream(req):
    print(seq, batch.num_rows)
```

In another window apply an intraday batch (VaR → Intraday → Apply, or `POST /api/v1/var/intraday/streams/{name}/apply`): a new batch
arrives with a higher sequence. Stop the loop, then `f.stream(req, resume=seq)`.

(output from the demo lake: to be filled in the heavy run)

## Step 8 · Admin → Data out

Open Admin → Data out: your live stream with its rows and bytes; switch to the map and select its line; press **Stop**: the loop ends
with `DRS-5610`. The last day's readers list your earlier reads.

(output from the demo lake: to be filled in the heavy run)

## What you learned

Flight reads Drishti's data as the person a token belongs to, in Arrow, from the node that holds it; VaR's vectors are the engine's own
numbers, computed when asked; a stream resumes where it stopped; every read is limited, audited and visible to administrators.

Next: the [client examples](../../examples/clients/README.md), notebooks and programs to start your own from (a pivot over trade
legs, the VaR scenario and ES tail plotted, a join in DuckDB, the in-memory lake, Excel through Python).
