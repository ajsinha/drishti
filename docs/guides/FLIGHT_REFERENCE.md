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
# Arrow Flight reference

Every request form, its JSON, its schema in Arrow, its errors, the scope it needs and its limits. How to turn Flight on, tokens, the
cluster and operations: [FLIGHT_GUIDE.md](FLIGHT_GUIDE.md). The clients, one section each: [section 7](#7-clients). Error codes with their HTTP equivalents: [API_GUIDE.md](API_GUIDE.md#error-code-table).

## 1. The calls

| Call | Takes | Answers |
|---|---|---|
| Handshake | the token in the headers: `authorization: Bearer drk_…` or Basic (any user, the token as the password); `x-drishti-on-behalf-of: <person>` for an application's token | `authorization: Bearer dfs_…`, the session the client sends from then on |
| `GetFlightInfo` | a descriptor whose **command** is a request (section 2) | the schema, one endpoint per part (its ticket and the nodes' `grpc+tls://host:port`), rows and bytes expected (-1: not known) |
| `GetSchema` | the same descriptor | the schema only |
| `DoGet` | a ticket: an endpoint's, or a request written by hand (no `part`: part 0, read here) | record batches; a stream's batches carry their sequence as app metadata |
| `ListFlights` | nothing (criteria ignored) | one descriptor per kind, model, measure and lake table the token may read; no schema, no data |
| `DoAction` `health` | nothing | `{"node", "arrowBytes", "arrowCap", "live"}` |
| `DoAction` `whoami` | nothing | `{"person", "onBehalfOf", "tokenId", "roles", "scopes", "limits": {"rows", "bytes", "seconds", "memory"}}` |
| `DoPut`, `DoExchange` | | refused (`UNIMPLEMENTED`): Flight is read only |

Path descriptors are refused (`DRS-5603`): a Drishti request is always a command.

## 2. The request

```json
{"form": "kind | model | pivot | measure | lake",
 "request": { … } or "Rachana text",
 "part": 0,            (in a ticket: which endpoint of the plan; left out: 0)
 "stream": true,       (a live stream; kinds, models and pivots are snapshots only)
 "resume": 41}         (a stream: the last sequence held)
```

At most 1 MB. A bad request is `DRS-5603` with the reason.

## 3. The forms

### 3.1 `kind`

```json
{"form": "kind", "request": {"kind": "trade", "paths": ["TRADE_ID", "PRODUCT_TYPE", "QUANTITY"], "where": ["PRODUCT_TYPE = IRS"], "date": "2026-09-30"}}
```

| Key | Means |
|---|---|
| `kind` | the kind, as Export data names it |
| `paths` (or `columns`) | the columns; none: every column the person may see. A masked column is left out; naming one in `where` is `DRS-5602` |
| `where` | lines as Export data takes them: `FIELD = value`, `!=`, `in`, `contains`, `starts`, `>`, `<`, `between`, `is empty`, `is not empty` |
| `date` | the business date (a date the kind holds); none: the latest |

Schema: one field per path, typed by the kind's data contract: `integer` int64; `amount`, `rate`, `number`, `decimal` float64; `boolean`
bool; anything else utf8 (a nested value as its JSON). Scope: `data:read` or `data:read:<the kind's pack>`. Audited twice: the Flight
line and Export data's `data.flight` on the kind. One part; any member may answer it.

### 3.2 `model`

```json
{"form": "model", "request": {"model": "trades-basic", "query": {"select": {"fields": ["trade.DESK"], "measures": ["MTM"]}, "sort": [{"by": "MTM", "desc": true}], "limit": 50}}}
```

`query` is an MQ query ([BI_GUIDE.md](BI_GUIDE.md)); the request may also be the MQ text or JSON with `model` in it, or
`{"model": …, "sql": "<a DSQL statement>"}` (what Flight SQL sends, section 6). Needs the BI power.
Schema: the answer's columns (text utf8, integers int64, decimals float64); metadata `drishti.model`, `drishti.masked`. Scope:
`data:read` or `data:read:bi`. Snapshot only.

### 3.3 `pivot`

The request is the pivot view, exactly as `POST /api/v1/pivot/query` takes it ([PIVOT_GUIDE.md](PIVOT_GUIDE.md)). Schema: `ROW`, `COLUMN`,
one utf8 field per dimension, `MEASURE`, `VALUE` (float64), `MEMBERS` (int64); metadata `drishti.provenance`, `drishti.source`. Scope:
`data:read` or `data:read:<the view's source>`. Audited as `pivot.query` too. Snapshot only.

### 3.4 `measure`

```json
{"form": "measure", "request": "measure('var.scenarios', 'DESK:RATES', date: 2026-09-30)"}
{"form": "measure", "request": {"measure": "var.parts", "scope": "DESK:RATES", "by": "position", "ccy": "USD"}}
```

| Measure | Rows | Schema |
|---|---|---|
| `var.scenarios` | one per scenario of the scope's vector | `SCENARIO` int64 (0-based), `SCENARIO_DATE` utf8, `PNL` float64 |
| `var.parts` | one per part (`by`: child, node, position, trade, desk, book) | `KEY`, `SCOPE`, `LABEL`, `KIND` utf8; `PNL` fixed-size list of one value per scenario: float32 for positions and trades (as stored), float64 for nodes |

Metadata: `drishti.measure`, `drishti.scope` (canonical), `drishti.date`, `drishti.currency`, `drishti.var`, `drishti.es`,
`drishti.intraday` (the date's intraday stamp); for parts also `drishti.pnl.precision` and `drishti.scenario.dates`. Scope: `data:read`
or `data:read:var`. A stream (`var.scenarios` only) sends the vector again after each intraday batch; the sequence is the intraday stamp.

### 3.5 `lake`

```json
{"form": "lake", "request": {"lake": "feeds", "kind": "news.headlines", "paths": ["SEQ", "HEADLINE"], "cursor": "SEQ"}, "stream": true, "resume": 1200}
```

The table is found in the lake configuration ([DATA_LAKE_GUIDE.md section 2](DATA_LAKE_GUIDE.md#2-the-lake-file-every-key)) by lake and kind, never through a
connector's settings; it is then read as its kind (section 3.1's keys and checks). A table of the in-memory lake is answered by the node
that owns its store. `cursor` (a stream): a whole-number column that grows with each append; the sequence of a batch is its largest
cursor value. Scope: `data:read` or `data:read:<lake>`.

## 4. Errors

| Code | Flight status | When |
|---|---|---|
| `DRS-5601` | UNAUTHENTICATED | no token, an unknown, expired or revoked token, a disabled account, a session that ended |
| `DRS-5602` | UNAUTHORIZED | no `data:read` scope, a domain scope that does not open the request, the person may not read it, an on-behalf-of from a token that is not an application's |
| `DRS-5603` | INVALID_ARGUMENT | not a request: bad JSON, an unknown form, a kind, model, measure or table that is not there, a bad date or line |
| `DRS-5604` | RESOURCE_EXHAUSTED | the rows limit of the role |
| `DRS-5605` | RESOURCE_EXHAUSTED | the bytes limit of the role |
| `DRS-5606` | TIMED_OUT | the time limit of the role; the client did not take a batch in time |
| `DRS-5607` | UNAVAILABLE | the read's Arrow memory cap, or the node's |
| `DRS-5608` | UNAVAILABLE | no node of a part answered (the Python client), or the part is not here |
| `DRS-5609` | NOT_FOUND | a resume point older than the node can send from |
| `DRS-5610` | CANCELLED | an administrator stopped the stream |
| `DRS-9506` | UNAVAILABLE | busy protection: the read did not start |

The message is `DRS-nnnn: <words>`; the status metadata carries `drishti-code`.

## 5. Limits

Per read, from the person's roles (`drishti.engines.flight.limits`): rows, bytes, time, Arrow memory; per node: `memory.total-mb`. Defaults:
50,000,000 rows, 8 GB, 10 minutes, 512 MB per read; 4 GB per node.

## 6. Flight SQL

Read-only SQL over the BI models (DSQL, [API_GUIDE.md › Drishti SQL](API_GUIDE.md#drishti-sql-apiv1bisql)) on the same port; how to use it:
[FLIGHT_GUIDE.md](FLIGHT_GUIDE.md#12-flight-sql-sql-over-the-bi-models-from-jdbc-and-odbc-tools), section 12.

| Flight SQL call | Answers |
|---|---|
| `CommandStatementQuery` (GetFlightInfo, GetSchema) | the statement as a `form: model` request `{"model": <model>, "sql": <statement>}`: its schema and one endpoint per part, the ticket a `TicketStatementQuery` whose handle is that request |
| `TicketStatementQuery` (DoGet) | the rows, read as any Flight read (limits, Data out, audit `data.flight`, form `model`) |
| `ActionCreatePreparedStatement` | the handle (the request itself: stateless), the result's schema, an empty parameter schema |
| `CommandPreparedStatementQuery` (GetFlightInfo, GetSchema, DoGet) | as a statement |
| `ActionClosePreparedStatement` | nothing to close |
| `CommandGetCatalogs` | one row: the product's name |
| `CommandGetDbSchemas` | `catalog_name`, `db_schema_name`: a row per BI model the person may read (its id, `trading-risk@1`); the filter is a LIKE pattern |
| `CommandGetTables` | `catalog_name`, `db_schema_name`, `table_name`, `table_type` (`TABLE`), and with `include_schema` the `table_schema`: a row per alias of each model, its columns as the model describes them for the person (integers int64, numbers float64, the rest utf8) |
| `CommandGetTableTypes` | `TABLE` |
| `CommandGetSqlInfo` | the server's name and version, read only, SQL yes, Substrait no, transactions none, the identifier quote `"`, the schema term `model` |
| updates, ingestion, Substrait, transactions, savepoints, statement parameters | refused: `UNIMPLEMENTED`, read only |

**The model of a statement**: its first line `-- model: <model>` or `/* model: <model> */`, else the call's header `model` (a JDBC URL's
`model=`); neither: `DRS-5603`. `EXPLAIN` is refused (`DRS-5603`; the BI SQL console answers it). The statement's own refusals are the
BI SQL codes (`DRS-9150` to `DRS-9178`, [API_GUIDE.md](API_GUIDE.md#error-code-table)), carried in a `DRS-5603` or `DRS-5602` message.
Scope: `data:read` or `data:read:bi`, and the BI power.

## 7. Clients

One section per client: install, connect, read, stream, errors. Working examples, every one read only, are in
[examples/clients](../../examples/clients/README.md). C#/.NET, R and Go are not written up yet; the service answers any Flight client.

### 7.1 Python

**Install.** `pip install pyarrow` (16 or newer; it brings `pyarrow.flight`), and pandas or polars if you want them. Copy
`clients/python/drishti_flight.py` next to your script or notebook: one file, no other dependency.

**Connect.**

```python
from drishti_flight import Flight
f = Flight("grpc+tls://drishti-1.bank.example:18815", token="drk_…", ca="bank-ca.pem")
f.whoami()          # {"person": …, "scopes": ["data:read"], "limits": {…}}
```

`url`, `token` and `ca` may come from `DRISHTI_FLIGHT_URL`, `DRISHTI_TOKEN` and `DRISHTI_FLIGHT_CA`. An application acting for a person
passes `on_behalf_of="alice"`; a service asking for client certificates takes `cert=` and `key=`.

**Read.**

```python
trades = f.kind("trade", paths=["TRADE_ID", "PRODUCT_TYPE", "QUANTITY"], where=["PRODUCT_TYPE = BASIS_SWAP"])
df = trades.to_pandas()                         # or polars.from_arrow(trades)
pnl = f.measure("var.scenarios", "DESK:RATES", date="2026-09-30")
parts = f.measure("var.parts", "DESK:RATES", by="position")
risk = f.model("trades-basic", {"select": {"fields": ["trade.DESK"], "measures": ["MTM"]}})
cells = f.pivot({"pivot": 1, "source": "trade", "rows": ["PRODUCT_TYPE"], "values": [{"measure": "count()"}]})
f.catalogue()                                   # what this token may read
```

Run on the test data of 2026-09-30:

```text
>>> df.head(3); len(df)
      TRADE_ID PRODUCT_TYPE    QUANTITY
0  MX-20000013   BASIS_SWAP   7000000.0
1  MX-20000014   BASIS_SWAP  64000000.0
2  MX-20000015   BASIS_SWAP  63000000.0
72
>>> pnl.to_pandas()
     SCENARIO SCENARIO_DATE         PNL
0           0    2024-09-17  11293350.0
1           1    2024-09-18  17864629.0
...
508       508    2026-09-29  -2066043.0
[509 rows x 3 columns]
>>> parts.num_rows
1358
>>> cells.num_rows, cells.schema.names
75 ['ROW', 'COLUMN', 'PRODUCT_TYPE', 'MEASURE', 'VALUE', 'MEMBERS']
>>> len(f.catalogue())
199
```

`read` asks for the plan and reads each part from its nodes in parallel, the next node of a part when one does not answer.

**Stream.**

```python
req = f.request("measure", "measure('var.scenarios', 'DESK:RATES')")
for seq, batch in f.stream(req):                # a pyarrow.RecordBatch after each intraday batch
    print(seq, batch.num_rows)
```

Keep the last `seq`; `f.stream(req, resume=seq)` continues after it.

**The command line.**

```bash
python3 clients/python/drishti_flight.py --url grpc+tls://drishti-1.bank.example:18815 --token "$DRISHTI_TOKEN" --ca bank-ca.pem \
    kind trade --paths TRADE_ID,PRODUCT_TYPE --where "PRODUCT_TYPE = IRS" > irs.csv
python3 clients/python/drishti_flight.py --out pnl.parquet measure var.scenarios DESK:RATES
```

**Examples.** Nine notebooks, each cell explained: connect and the catalogue, a kind into pandas and polars, a BI model, a pivot view over
trade legs, a desk's VaR scenario P&L with the VaR scenario and ES tail marked, a live stream with resume, a join in DuckDB, an
in-memory lake table, and a direct lake read for users who already hold lake access
([examples/clients/python](../../examples/clients/README.md#python-notebooks-python)). Excel from Python, no add-in:
[examples/clients/excel](../../examples/clients/excel/README.md).

**Errors.** A refusal raises `FlightRefused` with `.code` (`DRS-5604` …) and the words (section 4).

### 7.2 Java

**Install.** Maven: `org.apache.arrow:flight-core` and `org.apache.arrow:arrow-memory-netty`, version 19.0.0 or newer (the server's own).
Start the JVM (Java 17 to 21) with `--add-opens=java.base/java.nio=ALL-UNNAMED` (Arrow's memory module needs it; Arrow 19's memory
module does not start on Java 25).

**Connect.**

```java
BufferAllocator alloc = new RootAllocator();
FlightClient client = FlightClient.builder(alloc, Location.forGrpcTls("drishti-1.bank.example", 18815))
        .trustedCertificates(Files.newInputStream(Path.of("bank-ca.pem"))).build();
CallOption session = client.authenticateBasicToken("token", System.getenv("DRISHTI_TOKEN")).orElseThrow();
```

An application acting for a person adds the header at the handshake:
`client.authenticateBasicToken("token", token, new HeaderCallOption(headers))` with `x-drishti-on-behalf-of: alice` in `headers`.

**Read.**

```java
byte[] request = """
    {"form": "measure", "request": {"measure": "var.scenarios", "scope": "DESK:RATES"}}""".getBytes(StandardCharsets.UTF_8);
FlightInfo info = client.getInfo(FlightDescriptor.command(request), session);
for (FlightEndpoint e : info.getEndpoints()) {          // each part, from the first of its nodes (try the next when one is down)
    try (FlightStream s = client.getStream(e.getTicket(), session)) {
        while (s.next()) {
            VectorSchemaRoot root = s.getRoot();
            Float8Vector pnl = (Float8Vector) root.getVector("PNL");
            // …
        }
    }
}
```

Run (the elided body printing each batch's rows, its first P&L and the worst; Java 21; the test data of 2026-09-30):

```text
1 endpoint(s)
a batch of 509 rows from grpc+tls://192.168.2.77:18825; PNL[0] = 11,293,350.00; the worst = -119,535,289.00
metadata: {drishti.scope=DESK:RATES, drishti.var=7.463368E7, drishti.es=7.5377415E7, drishti.measure=var.scenarios, drishti.date=2026-09-30, drishti.currency=USD, drishti.intraday=0}
```

A part on another node needs a client for that node's location (`e.getLocations().get(0)`), authenticated the same way.

**Stream.** Add `"stream": true` to the request and read the ticket with `getStream`; each batch's sequence is
`s.getLatestMetadata().getLong(0)`. Ask again with `"resume": <sequence>` after a break.

**Example.** `ReadKindAndVarPnl.java`, one file run with the source launcher (a kind, then a desk's VaR scenario P&L with the engine's VaR
and ES, each part from the first node that answers): [examples/clients/java](../../examples/clients/java/README.md).

**Errors.** A refusal is a `FlightRuntimeException`; `e.getMessage()` starts with the code (`DRS-5604: …`) and
`e.status().metadata().get("drishti-code")` carries it alone.

### 7.3 JDBC (DBeaver, DataGrip, any JDBC program)

Flight SQL (section 6): read-only SQL over the BI models, answered by the same path as the BI SQL console.

**Install.** The Apache Arrow Flight SQL JDBC driver: one jar, `org.apache.arrow:flight-sql-jdbc-driver`, version 19.0.0 or newer. No
installer, so it runs on machines where no program may be installed. On Java 17 or later start the tool's JVM with
`--add-opens=java.base/java.nio=ALL-UNNAMED` (DBeaver: `dbeaver.ini`, a line under `-vmargs`).

**Connect.**

```text
jdbc:arrow-flight-sql://drishti-1.bank.example:18815/?useEncryption=true&token=<API token with data:read>&model=trading-risk@1
```

| Property | Means |
|---|---|
| `useEncryption=true` | TLS (always on at Drishti's Flight port) |
| `token=` | a personal API token with `data:read` (or `data:read:bi`); the driver sends it on every call, the service keeps one session for it |
| `trustStore=`, `trustStorePassword=` | the bank's CA as a Java trust store, when the JVM's own does not hold it, with `useSystemTrustStore=false` (the driver reads `trustStore` only then; `disableCertificateVerification=true` on a test machine only) |
| `model=` | the BI model statements read, unless a statement names its own with a first line `-- model: <model>` (the driver sends this property as the header `model`) |

In DBeaver: Database → New connection → **Arrow Flight SQL** (or a generic JDBC connection with the jar), the URL above. The navigator
shows one catalog, a schema per BI model you may read, and a table per alias of the model with its columns.

**Read.**

```java
try (Connection c = DriverManager.getConnection("jdbc:arrow-flight-sql://drishti-1.bank.example:18815/?useEncryption=true&token="
        + System.getenv("DRISHTI_TOKEN") + "&model=trading-risk@1");
     ResultSet rs = c.createStatement().executeQuery("SELECT trades.DESK, MEASURE('Notional') FROM trades GROUP BY trades.DESK")) {
    while (rs.next()) {
        System.out.println(rs.getString(1) + " " + rs.getDouble(2));
    }
}
```

Run against a server with a test PKI (`&trustStore=ca.jks&trustStorePassword=…&useSystemTrustStore=false` added to the URL; Java 21; the
test data of 2026-09-30):

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

A prepared statement works the same way (read only, no parameters).

**Example.** `JdbcFlightSql.java`, plain JDBC with the driver jar: without arguments it lists the models you may read and their tables;
with a model and a statement it runs the statement:
[examples/clients/java](../../examples/clients/java/README.md#jdbc-through-flight-sql).

**Errors.** The driver shows the service's message, starting with its code: `DRS-5603` (no model named, a statement DSQL refuses, with
the BI SQL code in the words), `DRS-5602` (the token or the person may not read the model), `DRS-5604` (the rows limit of your role).

### 7.4 ODBC (Excel, Power BI, Tableau)

**Install.** An Arrow Flight SQL ODBC driver (third-party). It is an **installer**, so it cannot go on machines where no program may be
installed: there, use the JDBC jar (7.3) or the Python client (7.1), or get an Excel workbook from a Flight read through Python
([examples/clients/excel](../../examples/clients/excel/README.md)).

**Connect.** A DSN with host `drishti-1.bank.example`, port `18815`, encryption (TLS) on, the bank's CA, and an API token with `data:read`
as the token (or as the password, with any user name). Name the BI model with the driver's extra connection property `model=<model>`
when it passes such properties as headers; otherwise begin each statement with a line `-- model: <model>`.

**Read.** In Excel: Data → Get Data → From ODBC → the DSN → a schema (a BI model) → a table (one of its aliases), or Advanced options
with a statement such as `-- model: trading-risk@1` then `SELECT trades.DESK, MEASURE('Notional') FROM trades GROUP BY trades.DESK`. In
Power BI the same through its ODBC connector. The answer is one row per desk, the same figures as the JDBC example above (the same
service answers both); the ODBC driver itself is not part of Drishti's test runs.

**Errors.** The tool shows the service's message, starting with its code (`DRS-5603` no model named or a statement refused, `DRS-5602`
not allowed, `DRS-5604` the rows limit).
