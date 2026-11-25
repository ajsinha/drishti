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

Examples of reading Drishti's data from outside it: Python notebooks, Java, JDBC and Excel. Each one **only reads** (the Flight service refuses every write), **through Arrow
Flight** as the person a token belongs to (entitlements, masks, limits, audit: [Flight guide](../../docs/guides/INTEGRATION_GUIDE.md)), **keeps the token out of the code**, and runs on the
**demo lake's synthetic data**, also as a test (`drishti-console/tests/test_client_examples.py`).

## Before you start
Flight on (Admin → Engines → Arrow Flight, [guide section 2.2](../../docs/guides/INTEGRATION_GUIDE.md#22-turning-it-on)) and a token with the scope `data:read` ([section 2.3](../../docs/guides/INTEGRATION_GUIDE.md#23-tokens-and-scopes-step-by-step)) in a file only you can read.
```bash
export DRISHTI_FLIGHT_URL=grpc+tls://<a node's real address>:18815    # Admin -> Data out shows it; never localhost on a cluster
export DRISHTI_TOKEN_FILE=~/.config/drishti/token                     # or DRISHTI_TOKEN
export DRISHTI_FLIGHT_CA=/path/to/ca.pem                              # the CA of the server's certificate
```

## Python notebooks (`python/`)

`python3 -m pip install -r examples/clients/python/requirements.txt jupyterlab`, then `cd examples/clients/python && jupyter lab`. They use the one-file client [`clients/python/drishti_flight.py`](../../clients/python/drishti_flight.py); outputs are not stored.

- [01 Connect and catalogue](python/01_connect_and_catalogue.ipynb): a token, `whoami`, the catalogue, a node's health, what a refusal looks like
- [02 A kind into pandas and polars](python/02_kind_to_pandas_and_polars.ipynb): the `trade` kind on the latest date; a filter applied on the server
- [03 A BI model query](python/03_bi_model_query.ipynb): `trades-basic`: Notional, MTM and trades by desk, then by desk and book
- [04 A pivot view](python/04_pivot_view.ipynb): Rachana paths over trade legs: by desk, a desk-by-year grid
- [05 VaR scenario P&L](python/05_var_scenario_pnl.ipynb): a desk's 509-day P&L vector with the VaR scenario and ES tail marked
- [06 A live intraday stream](python/06_live_intraday_stream.ipynb): `var.scenarios` as a stream, a break and the resume
- [07 Join with your own data in DuckDB](python/07_join_in_duckdb.ipynb): Drishti's trades and P&L joined in SQL with tables of your own
- [08 An in-memory lake table](python/08_in_memory_lake_table.ipynb): a memory-only table (the news store), a snapshot, an optional live follow
- [09 Direct lake read](python/09_direct_lake_read.ipynb): **for users who already hold lake access:** a table read with `deltalake` (or `pyiceberg`)

## Java (`java/`) and Excel (`excel/`)

Java 17+ source launcher, no project; the Flight jars (19.0.0, the server's own) go in a `lib/` folder beside the programs:
`mvn -f examples/clients/java/pom.xml dependency:copy-dependencies -DoutputDirectory=lib` (or `flight-core`, `arrow-memory-netty` and their dependencies from Maven Central).

```bash
cd examples/clients/java       # Windows: -cp "lib\*" and $env:DRISHTI_FLIGHT_URL = "grpc+tls://…:18815"; Arrow needs --add-opens on Java 17+
java --add-opens=java.base/java.nio=ALL-UNNAMED -cp "lib/*" ReadKindAndVarPnl.java DESK:RATES
java --add-opens=java.base/java.nio=ALL-UNNAMED -cp "lib/*" JdbcFlightSql.java trading-risk@1 "SELECT trades.DESK, COUNT(*) AS n FROM trades GROUP BY trades.DESK"
```

[ReadKindAndVarPnl.java](java/ReadKindAndVarPnl.java) reads the `trade` kind and a desk's VaR scenario P&L with the engine's VaR and ES; [JdbcFlightSql.java](java/JdbcFlightSql.java) runs SQL over a
BI model (set `DRISHTI_FLIGHT_TRUSTSTORE` and `_PASSWORD`; no arguments: the models and tables; the model goes in the `model` property or a first line `-- model: <model>`; [JDBC tools](../../docs/guides/INTEGRATION_GUIDE.md#21473-jdbc-dbeaver-datagrip-any-jdbc-program)).
[excel/flight_to_excel.py](excel/flight_to_excel.py) writes a workbook with `openpyxl` (nothing installed into Excel): `pip install pyarrow openpyxl pyyaml`, then
`python3 examples/clients/excel/flight_to_excel.py --out desk-rates.xlsx --scope DESK:RATES`; `xlwings` writes into an open workbook.
## Later
C#/.NET, R and Go examples; virtual-table and model notebooks.
