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
# Data out: Drishti's data to any client with Arrow Flight

**Arrow Flight** is a protocol for moving columnar data (Apache Arrow record batches) between programs over gRPC. Drishti answers it on
its own port so that any Arrow Flight client (a Python notebook, a Java service, a JDBC tool, or a program in any language with a
Flight library) reads Drishti's data, as the person it
belongs to, without a JSON step and without copying the lake: the kinds of every pack, BI model queries, pivot views, VaR's scenario P&L
vectors and the in-memory lake, each with the same entitlements, masks, busy protection and audit as the screens.

The exact form of every request, its schema and its errors are in the [reference](FLIGHT_REFERENCE.md); a hands-on walk from a token to a
pandas DataFrame is [tutorial 35](../tutorials/35-data-out-with-arrow-flight.md); the clients have their own sections
in the reference ([Python](FLIGHT_REFERENCE.md#71-python), [Java](FLIGHT_REFERENCE.md#72-java), [JDBC](FLIGHT_REFERENCE.md#73-jdbc-dbeaver-datagrip-any-jdbc-program), [ODBC](FLIGHT_REFERENCE.md#74-odbc-excel-power-bi-tableau)).
Working examples to start from, every one read only (Python notebooks from a token to VaR scenario P&L, a live stream, DuckDB and the
in-memory lake; Java; Excel through Python) are in [examples/clients](../../examples/clients/README.md). Later: C#/.NET, R and Go client sections (the service already answers any Flight client). How the service is built and why: [DATA_ARCHITECTURE.md section 11](../architecture/DATA_ARCHITECTURE.md#11-arrow-flight-drishtis-data-to-any-client).

## Contents

1. [When to use Flight](#1-when-to-use-flight)
2. [Turning it on](#2-turning-it-on)
3. [Tokens and scopes, step by step](#3-tokens-and-scopes-step-by-step)
4. [What can be read, and how each is named](#4-what-can-be-read-and-how-each-is-named)
5. [The request path](#5-the-request-path)
6. [The cluster: endpoints, parallel reads, a node down](#6-the-cluster-endpoints-parallel-reads-a-node-down)
7. [Live streams and resuming](#7-live-streams-and-resuming)
8. [Limits, and what a refusal says](#8-limits-and-what-a-refusal-says)
9. [Audit and Admin → Data out](#9-audit-and-admin--data-out)
10. [Operations](#10-operations)
11. [Troubleshooting: the ten most likely errors](#11-troubleshooting-the-ten-most-likely-errors)
12. [Flight SQL: SQL over the BI models from JDBC and ODBC tools](#12-flight-sql-sql-over-the-bi-models-from-jdbc-and-odbc-tools)
13. [Testing](#13-testing)

## 1. When to use Flight

| You want | Use |
|---|---|
| a figure, a document, a search, from a script, a few rows at a time | the REST API ([API_REFERENCE.md](API_REFERENCE.md)) with the same token |
| a table in a browser page (Drishti's own screens, an embedded view) | Arrow over HTTP: the page asks with `Accept: application/vnd.apache.arrow.stream` ([DATA_GUIDE.md](DATA_GUIDE.md)) |
| a whole table, a model's answer, a scope's P&L vectors into pandas, polars, Spark, a Java or .NET service, a JDBC tool | **Flight** |
| live updates in a program (each intraday VaR batch, each append to an in-memory table) | **Flight**, a stream ([section 7](#7-live-streams-and-resuming)) |
| the lake's files themselves (you already hold lake access) | the lake directly; Flight is the governed way for everyone else |

Flight is **read only**: nothing a client sends changes Drishti's data.

## 2. Turning it on

Flight is an engine of its own, off as shipped. It needs a restart to turn on, because its port opens at start.

**In the UI.** Admin → Engines → **Arrow Flight** → On, with a reason. The page says *restart needed*; restart the server.

**In the file.** `config/engines/flight.yaml` (every key is in [CONFIGURATION.md](../admin/CONFIGURATION.md#drishtienginesflight)):

```yaml
drishti:
  engines:
    flight:
      enabled: true                              # or DRISHTI_FLIGHT_ENABLED=true
      port: 18815                                # its own port, gRPC over HTTP/2
      tls:                                       # always TLS: the server's certificate
        certificate: /etc/drishti/tls/server.pem
        private-key: /etc/drishti/tls/server.key
        client-ca: ""                            # a CA bundle here asks every client for a certificate (mutual TLS)
```

**The start-up option.** Arrow's memory module needs one JVM option on Java 21: `--add-opens=java.base/java.nio=ALL-UNNAMED`. The server
jar's manifest carries it (`Add-Opens: java.base/java.nio`), so `java -jar` needs nothing more; a launcher that starts the server from
its classes adds the option itself.

When the server is up, its log says `Arrow Flight on 0.0.0.0:18815 (TLS), Arrow memory cap … bytes`, and Admin → Data out shows the
service running. Without a readable certificate and key the port stays closed and the page says why; the rest of the server runs.

## 3. Tokens and scopes, step by step

Flight takes **API tokens only** (never a browser session). A token reads as its owner, never more.

1. Open **My account → API tokens** and make a token with the scope **`data:read`** (every domain you may read) or
   `data:read:<domain>` (one domain: a pack such as `trading`, `bi` for BI models, `var` for VaR's vectors, a lake's name). Like every
   scope beyond `read`, it must expire within 90 days.
2. Copy the token (`drk_…`); it is shown once.
3. Give it to the client: in Python `Flight(url, token=…)`; every other client sends it at the handshake as
   `authorization: Bearer <token>` (or Basic with the token as the password).
4. The handshake answers with a **session** (`authorization: Bearer dfs_…`, an hour by default) that the client sends from then on; the
   token itself is checked again when the session ends.

**An application acting for a person.** A service account (a user with the `service` role) makes a token with `data:read`; the
application names the person in the header `x-drishti-on-behalf-of` at the handshake. The person's roles, masks and row rules then
apply, and the audit records both names. An embed token (Drishti Elements, an application on behalf of a signed-in person) is accepted
the same way.

Command line: the REST call that makes a token takes the scope too (`POST /api/v1/me/tokens` with `{"name": "risk notebook", "days": 30,
"scopes": ["data:read"]}`, [API_REFERENCE.md](API_REFERENCE.md#28-personal-api-tokens)).

## 4. What can be read, and how each is named

A request is JSON: a **form** and the **request** in the words the rest of Drishti uses (Rachana: kinds and paths, a pivot view, a
measure call). Full detail per form: [FLIGHT_REFERENCE.md](FLIGHT_REFERENCE.md).

| Form | Request | Answered by | Rows |
|---|---|---|---|
| `kind` | `{"kind": "trade", "paths": ["TRADE_ID", "PRODUCT_TYPE"], "where": ["PRODUCT_TYPE = IRS"], "date": "2026-09-30"}` | Export data's reader (as a download) | the kind's rows on the day, the chosen paths |
| `model` | `{"model": "trades-basic", "query": {"select": {"fields": [...], "measures": [...]}, "where": [...], "sort": [...], "limit": 50}}` | MQ, BI's model queries | the query's answer |
| `pivot` | the view, as `POST /api/v1/pivot/query` takes it | the pivot engine | one per cell and measure |
| `measure` | `measure('var.scenarios', 'DESK:RATES', date: 2026-09-30)` or `{"measure": "var.parts", "scope": "DESK:RATES", "by": "position"}` | the VaR engine, computed when asked | one per scenario; one per part with its vector |
| `lake` | `{"lake": "feeds", "kind": "news.headlines", "cursor": "SEQ"}` | the lake configuration ([DATA_LAKE_GUIDE.md section 2](DATA_LAKE_GUIDE.md#2-the-lake-file-every-key)), then the kind's reader | the table's rows; appends live |

**VaR's numbers are the engine's.** A scope's vector is float64, as the engine sums; a position's or trade's row is float32, as the hot
file keeps it ([VAR_GUIDE.md](VAR_GUIDE.md), section 26), and the schema's metadata says which (`drishti.pnl.precision`). The vectors are
computed when asked and never stored ([VAR_GUIDE.md](VAR_GUIDE.md), section 2). A client that sums the position rows gets the scope's
vector within the parity rule; it is never a substitute for the scope's own VaR, which is a quantile, not a sum.

Python, the shortest way:

```python
from drishti_flight import Flight
f = Flight("grpc+tls://drishti-1.bank.example:18815", token="drk_…", ca="bank-ca.pem")
pnl = f.measure("var.scenarios", "DESK:RATES").to_pandas()
```

```text
     SCENARIO SCENARIO_DATE          PNL
0           0    2024-09-17  11278589.52
1           1    2024-09-18  17875177.30
2           2    2024-09-19  21633915.20
3           3    2024-09-20 -61160944.54
4           4    2024-09-23  17550177.08
..        ...           ...          ...
504       504    2026-09-23  17561028.82
505       505    2026-09-24  42481517.80
506       506    2026-09-25 -12337742.74
507       507    2026-09-28 -35955103.30
508       508    2026-09-29  -2062964.68

[509 rows x 3 columns]
```

`ListFlights` lists what a token may read (names only); the action `whoami` says who the service thinks you are, your scopes and limits.

## 5. The request path

```text
 client (pyarrow, Java, JDBC, any Flight library)
    │  TLS, port 18815
    ▼
 ┌──────────────────────────── a Drishti node ─────────────────────────────┐
 │  handshake: the token (API token or embed token) → the token services   │
 │             scope data:read[:domain] · on behalf of a person            │
 │  GetFlightInfo: the request → its plan → endpoints (real addresses)     │
 │  DoGet:  busy protection admits ─▶ the form reads as the person ─▶      │
 │            kind ─▶ Export data reader      (entitlements, masks)          │
 │            model ─▶ MQ                    (row rules, masks)            │
 │            pivot ─▶ the pivot engine      (viewer, edition)             │
 │            measure ─▶ the VaR engine      (the person's view)           │
 │            lake ─▶ lake configuration ─▶ the kind's reader              │
 │          ─▶ Arrow record batches (official Arrow Java), limits counted  │
 │  audit: who, for whom, what, rows, bytes, node · Admin → Data out       │
 └──────────────────────────────────────────────────────────────────────────┘
```

Nothing in the Flight service computes or filters data: every check and every figure is the one the screens use.

## 6. The cluster: endpoints, parallel reads, a node down

`GetFlightInfo` answers a plan: one **endpoint** per part, each with the **real addresses** of the nodes that can answer it (never
`localhost` or `0.0.0.0`; the engine file's `advertise` or the node's Drishti\*net address), the preferred one first.

```text
                 GetFlightInfo(request)
 client ─────────────────────────────▶ node A
        ◀──────── endpoints ──────────
                   part 0: A, B, C       (a kind, a model: any member reads through the cluster)
                   part 1: C, B          (an in-memory table: C owns the store)
 client ══ DoGet(part 0) ══▶ A
 client ══ DoGet(part 1) ══▶ C   ✗ down
        ══ DoGet(part 1) ══▶ B   ✓      (the next address of the part)
```

The Python client reads the parts in parallel (`parallel=4`) and tries the next address of a part when a node does not answer; a node
that answers with a refusal is not retried elsewhere. Every node answers Flight on the same port.

## 7. Live streams and resuming

A request with `"stream": true` keeps sending batches as they come: VaR's scenario vector after each intraday batch is applied
(`measure('var.scenarios', …)`), or the rows appended to an in-memory table (`form: lake` with a `cursor`, a whole-number column that grows
with each append). Each batch carries its **sequence number** as app metadata (8 bytes, little-endian): the date's intraday stamp, or the
largest cursor value of the batch. Keep the last one; after a break, ask again with `"resume": <sequence>` (to this node or another) and
the stream continues after it, nothing missed, nothing twice.

```python
req = f.request("measure", "measure('var.scenarios', 'DESK:RATES')")
for seq, batch in f.stream(req):
    last = seq
# … later, after a break
for seq, batch in f.stream(req, resume=last):
    ...
```

With `print(seq, batch.num_rows)` in the first loop, across one intraday batch of the stand-in pricing system (its P&L vectors, then its
scenario PVs), stopped from Admin → Data out:

```text
18 509
26 509
27 509
DRS-5610: stopped by drishti-dev-admin on Admin → Data out
```

The first batch is the scope's vector as it stands, with the stream's current sequence.

## 8. Limits, and what a refusal says

Each read is held to its person's **role limits** (the most generous of their roles; `limits` in the engine file): rows, bytes, time, and
the **Arrow memory** it may hold at once. All Flight reads of a node together hold at most `memory.total-mb`; busy protection counts that
memory with the rest of the server (Health's busy card, `arrow-flight`) and may ask a new read to wait (`DRS-9506`).

A refusal is a Flight status whose message starts with Drishti's code, and whose metadata carries `drishti-code`:

```text
pyarrow.flight.FlightServerError: DRS-5604: the read passed 50000000 rows, the limit of your role; 49938432 rows were sent: narrow the
request or read it in parts
```

The rows limit stops a read after its last whole batch; a live stream has no time limit on its whole life, only on each batch.

## 9. Audit and Admin → Data out

Every read is audited when it ends: who, on behalf of whom, the token's id (never its secret), the form and the request's text, the
part, rows and bytes, milliseconds, the node, and the refusal if any (`data.flight`, `data.flight-refused`). A kind's read also writes the
Export data's own line (`data.flight` on the kind).

**Admin → Data out** shows, in two views of one screen (switch with the toggle at its top):

- **The page** (the default): the service (on, port, TLS, Arrow memory against its cap), the nodes with their real addresses and the
  forms each serves, the live reads with **Stop**, the last day's readers with their refusals and the limit hit, the tokens with a
  Flight scope (revoked on Admin → Users), and the limits per role.
- **The map**: clients on the left, nodes on the right, a line per live stream (its thickness the bytes, red when refused), the selected
  stream's detail in a panel.

Both read `GET /api/v1/admin/data-out` ([API_REFERENCE.md](API_REFERENCE.md)); Stop is `POST /api/v1/admin/data-out/streams/{id}/stop` (the client
gets `DRS-5610`).

## 10. Operations

**Sizing.** A read holds about two batches of Arrow memory at once (`batch-rows` × the row's width; 65,536 rows of 20 numeric columns
≈ 10 MB a batch). `memory.request-mb` caps one read, `memory.total-mb` (`DRISHTI_FLIGHT_MEMORY_MB`) the node. Arrow's buffers are direct
memory, so the shipped launchers raise the JVM's `-XX:MaxDirectMemorySize` for them at start, from `tools/lake/memory_caps.py`: 256 MB,
plus the in-memory lake's stores, plus Arrow's budget (512 MB for the compute exchange and BI answers, `DRISHTI_ARROW_EXCHANGE_MB`, plus
`memory.total-mb` when Flight is on), and set Arrow's own cap (`-Ddrishti.arrow.max-bytes`) to that budget. With Flight on and the
defaults: 256 + 512 + 4,096 = 4,864 MB. Check it with `python3 tools/lake/memory_caps.py --arrow-only`; on ECS set `DRISHTI_ARROW_MB` to
what it prints ([OPERATIONS.md](../admin/OPERATIONS.md#121a-direct-memory-and-the-diagnostics-of-a-long-run), section 12.1a); the host must
have that memory beside the heap. Changing `memory.total-mb` needs a restart (the limit is set at start).

Measured on one node (the test data of 2026-09-30, the defaults): `memory_caps.py` prints 4864 with Flight on (`--arrow-only` 4608);
the server's log says `Arrow memory cap 4294967296 bytes`. A desk's `var.scenarios` (509 rows) holds about 52 kB of Arrow memory
while it streams. The bank's `var.parts` by position (5,000 rows, the `drishti.var.max-parts` limit, of 509 float32 each: 10.7 MB in
the client) peaked at 17.8 MB of Arrow memory on the node (`health()`'s `arrowBytes`, sampled during the read) and took 0.1 s;
`arrowBytes` is back at 0 when the read ends.

**The port.** Open TCP 18815 between clients and every node (each node is read directly). A load balancer passes it through as TCP (TLS
ends on the node, HTTP/2 inside); it must not terminate TLS, since the endpoints name the nodes themselves.

**Certificates.** The port presents the server's certificate (PEM chain and PKCS#8 key); renew it as the HTTPS one, then restart the
node (the port reads its files at start). With `client-ca` set, every client presents a certificate signed by that CA.

**Monitoring.** Admin → Data out (live reads, refusals, memory); Health's busy card (`arrow-flight`); the audit log (`data.flight`); the
action `health` answers a node's state to a probe holding a token.

## 11. Troubleshooting: the ten most likely errors

| You see | Cause | Fix |
|---|---|---|
| `DRS-5601` no token | the client sent none, or a browser cookie | send the API token at the handshake (`authenticate_basic_token`, `authorization: Bearer`) |
| `DRS-5601` unknown, revoked or expired | the token ended | make a new one on My account → API tokens |
| `DRS-5602` lacks the scope data:read | the token was made without it | make a token with `data:read` |
| `DRS-5602` not data:read:trading | a domain scope that does not open this domain | a token with that domain, or `data:read` |
| `DRS-5603` form is kind, model … | the ticket is not Drishti's JSON | write it as the reference shows |
| `DRS-5604` rows limit | the read is bigger than your role allows | filter it, read fewer paths, or ask for a role with a higher limit |
| `DRS-5607` memory | a batch passed the read's Arrow memory cap | fewer paths, a narrower scope; an administrator may raise `memory.request-mb` |
| `DRS-9506` busy | the node is short of memory or busy | wait the seconds it says |
| `DRS-5608` no node answered | every address of a part is down or unreachable | check the port in the firewall; ask GetFlightInfo again |
| a TLS error (`certificate verify failed`) | the client does not trust the server's CA | give the CA file (`ca=` in Python, `trustedCertificates` in Java) |

## 12. Flight SQL: SQL over the BI models from JDBC and ODBC tools

**Flight SQL** is the SQL dialect of Arrow Flight that the JDBC and ODBC drivers speak. Drishti answers it on the same port, read only, as
SQL over the **BI models**: a statement is written in the server's own SQL (DSQL, [API_REFERENCE.md › Drishti SQL](API_REFERENCE.md#8-drishti-sql-apiv1bisql))
and answered by the same path as `POST /api/v1/bi/sql`, never by a second SQL engine. Each statement becomes a `form: model` read
(`{"model": …, "sql": …}`), so the token, its `data:read` scope (or `data:read:bi`), the BI power, the model's row rules and masks, busy
protection, the limits, Admin → Data out and the audit apply exactly as for any other read.

**Naming the model.** A DSQL statement reads one model, its `FROM` naming the model's aliases (`FROM trades`). Say which model in one of
two ways:

- a first line of the statement: `-- model: trades-basic@1` (or `/* model: trades-basic@1 */`), which works in any tool;
- the connection: the JDBC URL's `model=trades-basic@1` (the driver sends a property it does not know as the header `model`).

```sql
-- model: trading-risk@1
SELECT trades.DESK AS desk, MEASURE('Notional') AS notional, COUNT(*) AS n
FROM trades
GROUP BY trades.DESK
```

```text
desk	notional	n
DESK-COMM	8.850170019852502E11	5990
DESK-CREDIT	3.4835024551415E11	1172
DESK-EQD	4.3052971792252997E11	1452
DESK-FI	4.0315015623297003E11	1248
DESK-FX	3.7284221311835E11	1428
DESK-INFL	1.299456220881E11	396
DESK-MM	1.30992079528E11	480
DESK-RATES	6.0533722239374E11	1832
DESK-SFT	2.27897073212E11	672
DESK-STRUCT	1.06026316978E11	330
```

**What a tool browses.** One catalog (the product's name), a **schema per model** the person may read (`trading-risk@1`), a **table per
alias** of the model (`trades`, `counterparties`) with the columns the model describes for that person (hidden fields left out). Table
type: `TABLE`. A tool's browse never fails on a model the person may not read: it is not listed.

**What it refuses.** Anything but a query: updates, inserts, DDL, bulk ingestion, transactions, Substrait plans and statement parameters
(`UNIMPLEMENTED`, read only); `EXPLAIN` (the BI SQL console answers it); a statement that names no model (`DRS-5603`, with how to name
one). Prepared statements are read only and stateless: preparing returns the result's schema, executing reads it.

Clients: [JDBC](FLIGHT_REFERENCE.md#73-jdbc-dbeaver-datagrip-any-jdbc-program) (DBeaver, DataGrip, any JDBC program; the driver is one jar) and [ODBC](FLIGHT_REFERENCE.md#74-odbc-excel-power-bi-tableau)
(an installer, so not on machines where nothing may be installed). The statements and the catalog calls are in the
[reference](FLIGHT_REFERENCE.md#6-flight-sql), section 6.

## 13. Testing

`drishti-flight`'s `FlightServiceTest` runs the service over TLS with a stand-in backend (the handshake, a plan, batches, the limits, a
stream with resume, Stop); the server's `FlightServerTest` runs it inside the server with real tokens, entitlements and the audit; the
`FlightSqlServiceTest` runs Flight SQL in process (statements, the model by comment and header, the catalog, prepared statements,
an update refused); the console's `test_flight_client.py` runs the Python client against a stand-in service over TLS. On one node, every read form, the
stream across an intraday batch, Stop and resume are run against a real server ([tutorial 35](../tutorials/35-data-out-with-arrow-flight.md)).
The end to end on a three-node cluster (endpoints on every node, a node stopped mid-read, a VaR stream through an intraday batch) is
**not measured yet**; it is listed in [UNDER_CONSTRUCTION.md](../admin/UNDER_CONSTRUCTION.md).
