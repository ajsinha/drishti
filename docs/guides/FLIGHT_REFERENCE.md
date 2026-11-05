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
cluster and operations: [FLIGHT_GUIDE.md](FLIGHT_GUIDE.md). Error codes with their HTTP equivalents: [API_GUIDE.md](API_GUIDE.md#error-code-table).

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
| `kind` | the kind, as the Data view names it |
| `paths` (or `columns`) | the columns; none: every column the person may see. A masked column is left out; naming one in `where` is `DRS-5602` |
| `where` | lines as the Data view takes them: `FIELD = value`, `!=`, `in`, `contains`, `starts`, `>`, `<`, `between`, `is empty`, `is not empty` |
| `date` | the business date (a date the kind holds); none: the latest |

Schema: one field per path, typed by the kind's data contract: `integer` int64; `amount`, `rate`, `number`, `decimal` float64; `boolean`
bool; anything else utf8 (a nested value as its JSON). Scope: `data:read` or `data:read:<the kind's pack>`. Audited twice: the Flight
line and the Data view's `data.flight` on the kind. One part; any member may answer it.

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

The table is found in the lake configuration ([LAKE_CONFIG.md](../architecture/LAKE_CONFIG.md)) by lake and kind, never through a
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

Read-only SQL over the BI models (DSQL, [BI_LANGUAGE_REFERENCE.md](BI_LANGUAGE_REFERENCE.md)) on the same port; how to use it:
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
