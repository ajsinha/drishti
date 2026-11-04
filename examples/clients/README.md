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
# Client examples: Drishti's data in your own programs

Working examples of reading Drishti's data from outside it: Python notebooks first, then Java, JDBC and Excel. Each one:

- **only reads.** Nothing here writes to Drishti; the Flight service refuses every call that would (`DoPut`, `DoExchange`).
- **reads through Arrow Flight** by default, as the person a token belongs to: their entitlements, row rules and masks, busy protection,
  limits and audit, exactly as on the screens. How Flight works, turning it on and making a token: the
  [Flight guide](../../docs/guides/FLIGHT_GUIDE.md); every request form: the [reference](../../docs/guides/FLIGHT_REFERENCE.md).
- **keeps the token out of the code:** `DRISHTI_TOKEN`, or `DRISHTI_TOKEN_FILE` naming a file that holds it.
- runs on the **demo lake's synthetic data** (the defaults: the `trade` kind, the `trades-basic` model, the scope `DESK:RATES`), and as a
  test (`drishti-console/tests/test_client_examples.py`).

One notebook reads the lake's files directly and is labelled **for users who already hold lake access**: it gets no entitlements or
masks, no in-memory tables and no computed figures.

## Before you start

1. Flight on: Admin → Engines → Arrow Flight → On, then a restart ([guide section 2](../../docs/guides/FLIGHT_GUIDE.md#2-turning-it-on)).
2. A token with the scope `data:read`: My account → API tokens ([guide section 3](../../docs/guides/FLIGHT_GUIDE.md#3-tokens-and-scopes-step-by-step)).
   Keep it in a file only you can read, for instance `~/.config/drishti/token`.
3. The environment, the same for every example:

```bash
export DRISHTI_FLIGHT_URL=grpc+tls://<a node's real address>:18815    # Admin -> Data out shows it; never localhost on a cluster
export DRISHTI_TOKEN_FILE=~/.config/drishti/token                     # or DRISHTI_TOKEN
export DRISHTI_FLIGHT_CA=/path/to/ca.pem                              # the CA of the server's certificate
```

Optional:

```bash
export DRISHTI_EXAMPLE_SCOPE=DESK:RATES          # a VaR scope (the default)
export DRISHTI_EXAMPLE_DATE=2026-09-30           # a business date (default: the latest)
export DRISHTI_EXAMPLE_STREAM_WAIT=30            # seconds a stream example waits for a batch (the default)
export DRISHTI_EXAMPLE_STREAM_BATCHES=2          # batches a stream example takes (the default)
```

## Python notebooks (`python/`)

```bash
python3 -m venv ~/venvs/drishti-examples && . ~/venvs/drishti-examples/bin/activate
pip install -r examples/clients/python/requirements.txt jupyterlab
cd examples/clients/python && jupyter lab
```

They use the one-file client [`clients/python/drishti_flight.py`](../../clients/python/drishti_flight.py) through `flight_setup.py`
(beside them), which reads the environment above. Each cell has a note saying what it does; outputs are not stored.

| Notebook | Shows |
|---|---|
| [01 Connect and catalogue](python/01_connect_and_catalogue.ipynb) | connect with a token, `whoami` (person, scopes, limits), the catalogue of what you may read, a node's health, what a refusal looks like |
| [02 A kind into pandas and polars](python/02_kind_to_pandas_and_polars.ipynb) | the `trade` kind's chosen paths on the latest date into pandas and polars; a filter applied on the server |
| [03 A BI model query](python/03_bi_model_query.ipynb) | `trades-basic`: Notional, MTM and trades by desk, then by desk and book |
| [04 A pivot view](python/04_pivot_view.ipynb) | Rachana paths over trade legs: `sum($.LEGS[*].NOTIONAL)` by desk, a desk-by-year grid, trades with a JPY leg by product |
| [05 VaR scenario P&L](python/05_var_scenario_pnl.ipynb) | a desk's 509-day P&L vector plotted, the VaR scenario and the ES tail marked from the engine's own figures; one vector per position and their sum against the scope's |
| [06 A live intraday stream](python/06_live_intraday_stream.ipynb) | `var.scenarios` as a stream, each batch's sequence, a break and the resume after it |
| [07 Join with your own data in DuckDB](python/07_join_in_duckdb.ipynb) | Drishti's trades and P&L joined in SQL with tables of your own, on your machine |
| [08 An in-memory lake table](python/08_in_memory_lake_table.ipynb) | a memory-only table (the news store), the plan pointing at its owner node, a snapshot and an optional live follow; nothing written to disk |
| [09 Direct lake read](python/09_direct_lake_read.ipynb) | **for users who already hold lake access:** a table found through `config/lakes/demo.yaml` and read with `deltalake` (or `pyiceberg`), and what such a read is not |

## Java (`java/`)

Single-file programs run with Java's source launcher, no project: [java/README.md](java/README.md).

| File | Shows |
|---|---|
| [ReadKindAndVarPnl.java](java/ReadKindAndVarPnl.java) | a token at the handshake, the `trade` kind, a desk's VaR scenario P&L with the engine's VaR and ES; each part from the first node that answers |
| [JdbcFlightSql.java](java/JdbcFlightSql.java) | SQL over a BI model through the Flight SQL JDBC driver (one jar, no installer): the models and their tables, then a statement's rows |

## Excel (`excel/`)

[excel/flight_to_excel.py](excel/flight_to_excel.py) writes a workbook from a Flight read with `openpyxl` (no add-in, nothing installed
into Excel): the model by desk, the P&L vector, the engine's VaR and ES, and live formulas over the P&L. With `xlwings`, the same into an
open workbook. Power Query through ODBC waits for Flight SQL, and its driver is an installer: [excel/README.md](excel/README.md).

## As a test

`drishti-console/tests/test_client_examples.py` checks every notebook's form (valid JSON, the notice first, a note before every code
cell, no stored outputs, no token, no write call) on every run, and runs every notebook and the Excel script against a Flight server
when `DRISHTI_FLIGHT_URL` and a token are set and the server answers; otherwise those runs are skipped. The Java example runs too when
`DRISHTI_EXAMPLES_JAVA_CP` names its jars.

```bash
DRISHTI_FLIGHT_URL=grpc+tls://<real address>:18815 DRISHTI_TOKEN_FILE=… DRISHTI_FLIGHT_CA=… \
  DRISHTI_EXAMPLES_JAVA_CP="$PWD/examples/clients/java/lib/*" python -m pytest drishti-console/tests/test_client_examples.py -v
```

## Later

- C#/.NET, R and Go examples.
- Virtual-table and model notebooks (they come with 2.9.11.e).
