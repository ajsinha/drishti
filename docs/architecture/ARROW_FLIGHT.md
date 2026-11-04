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
# Arrow Flight: Drishti's data to any client (design questions)

*Release 2.9.11.c (product owner, 2026-11-03), picked up after 2.9.11.b is released. Status: **built on branch wip/flight, not yet released** (section 5, As built): every question of section 2
decided as recommended by the product owner (2026-11-03: "I am aligned with all of them, but good documentation is the key"); Flight SQL (Q10)
built as its second step: read-only DSQL over the BI models.*

**Read with:** [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md) (2.9.11.c: the lake configuration, Flight and the client examples), [ONE_GRAMMAR.md](ONE_GRAMMAR.md) (requests are
written in Rachana), [IN_MEMORY_LAKE.md](IN_MEMORY_LAKE.md) (section 10, the console's Python), [HIGH_PERFORMANCE_NATIVE.md](HIGH_PERFORMANCE_NATIVE.md).

## 1. Decided

1. **One service, a transport only.** A module `drishti-flight` answers Arrow Flight over the existing query layer (kinds, model queries,
   the pivot engine, VaR and the other measures, the in-memory lake) with the same entitlements, masks, busy protection and audit. No
   query logic is written twice.
2. **Any client:** Python (`pyarrow.flight`), Java, C#, R, Go; JDBC and ODBC tools through Flight SQL. Browsers keep Arrow over HTTP and
   live views over Server-Sent Events.
3. **Read only for clients** (the client examples never write to Drishti).
4. **The first use:** VaR scenario P&L: a scope's vector computed when asked and never stored, per-position P&L in batches, live intraday
   updates, exactly the engine's figures.
5. **No dual machinery:** the official Arrow Java library replaces the hand-written Arrow writers (compute `ArrowIpc`, `ArrowTable`;
   server `ArrowIpcWriter`, `ArrowAnswers`), and the console's own JSON-to-Arrow conversion goes; no HTTP Python reader is built.
6. **The client examples** (in 2.9.11.c) use Flight by default; examples that read the lake directly are labelled for people who already hold
   lake access.

## 2. Decisions (product owner, 2026-11-03: all as recommended)

| # | Question | Decided |
|---|---|---|
| Q1 | **What a request names.** Flight's tickets carry a request. Is it Rachana (a kind and paths, a pivot view, `measure(...)`), SQL through Flight SQL, or both? | **Both, one layer under them:** a ticket is a Rachana request (the same JSON as `POST /api/v1/pivot/query`, a kind with paths, or a measure call); Flight SQL accepts read-only SQL over models (DSQL), compiled to the same plans |
| Q2 | **What is exposed in the first build.** Kinds, BI models, pivot views, VaR vectors, exposure paths, the in-memory lake? | **Kinds, BI models, pivot views, VaR vectors and the in-memory lake** first; exposure paths, FRTB-SA and SIMM detail second |
| Q3 | **Who may call.** API tokens with scopes, or also a person's browser session? | **API tokens only** (the existing tokens and scopes: a new read scope `data:read` per domain), on behalf of a person for an application (the existing on-behalf-of tokens); never a browser cookie |
| Q4 | **Port and TLS.** | **Its own port** (default 18815), **TLS always** (the server's certificate), mutual TLS optional; off by default, an engine file `config/engines/flight.yaml` (planned) and Admin → Engines |
| Q5 | **The cluster.** Does a client read each part from the node that holds it (shards, the in-memory lake's owner)? | **Yes:** `GetFlightInfo` lists the endpoints (each node's real address) and the client reads them in parallel; a node that is down is retried on a peer that holds the part |
| Q6 | **VaR position rows: float32 or float64?** The hot file keeps position rows in float32; the engine sums in float64 | **As the engine computes them:** scope and node vectors in float64; position rows in float32 as stored, with the schema saying so; a test proves a client's sum of the rows equals the engine's vector within the parity rule |
| Q7 | **Live streams.** Long `DoGet` that keeps sending batches, or `DoExchange`? | **`DoGet` with a stream flag** for subscriptions (intraday batches, standing queries, in-memory lake appends), resumable by a sequence number |
| Q8 | **Limits.** | Per request: rows, bytes and time limits from the token's role, counted by busy protection; Arrow's off-heap memory capped per request and in total |
| Q9 | **Audit.** | Every request: who, on behalf of whom, what (the request text), rows and bytes sent, from which node; Admin → Data out shows live streams and the last day's readers |
| Q10 | **Flight SQL in the first build, or second?** | **Second step in the same release** if time allows; the drivers are third-party: the JDBC driver is a jar (fine on Windows QA), the ODBC driver is an installer (not on the Windows QA machine) |
| Q11 | **Mock-ups** for Admin → Data out (tokens, live streams, who reads what) | **Two to three mock-ups** before building, as for every new screen |
| Q12 | **The Arrow library's needs.** `arrow-memory` needs `--add-opens=java.base/java.nio=ALL-UNNAMED` on Java 21 | **Accept**, set in the start scripts and documented; checked by a start-up test |

## 3. Documentation: the key part of the release

*Product owner, 2026-11-03: "good documentation is the key".* The release is not done until each of these exists, with every example
run against the demo lake and its real output shown (never invented):

| Document | Holds |
|---|---|
| **A Flight guide** (`docs/guides/`, Help → Data out) | what Flight is and when to use it rather than the REST API or Arrow over HTTP; turning it on (the engine file, the port, TLS, the start-up option); tokens and scopes step by step; what can be read (kinds, models, pivot views, measures, the in-memory lake) and how each is named in Rachana; the cluster (endpoints, parallel reads, a node down); live streams and resuming; limits and what a refusal says; audit and Admin → Data out; troubleshooting (the ten most likely errors with their codes and fixes) |
| **The reference** | every ticket form and descriptor, its JSON, its schema in Arrow, its errors (codes in API_GUIDE), the scopes it needs, its limits; Flight SQL's supported statements |
| **A tutorial** | from a token to a pandas DataFrame of a desk's VaR scenario P&L in Python, then the same in Java and through JDBC (DBeaver), then a live intraday stream; UI, command line and code at every step, with pictures |
| **Client pages** | one per language (Python, Java now; C#/.NET, R and Go later, product owner 2026-11-03) and per tool (JDBC, ODBC): install, connect, read, stream, errors; the 2.9.11.d examples linked from each |
| **Operations** | sizing (memory for Arrow buffers per request and in total), the port in firewalls and load balancers, certificates and their renewal, monitoring (the metrics and the Admin page) |
| **Diagrams** | the request path (client, token, entitlements, the query layer, the engines, the nodes) and the cluster read |

**Mock-ups** of Admin → Data out (Q11; a planned screen, drawn with the decisions of Q3–Q9), each with a quiet
morning and a busy afternoon (four streams, one request refused for the rows limit, a node down with its VaR shards served by a peer,
Arrow memory near its cap): [A](mockups/data-out/A.html), one page read top to bottom: the service (switch, port, TLS, Arrow memory),
the nodes with their real addresses and what each serves, live streams with Stop, the last day's readers with refusals and the limit
hit, tokens with Flight scopes (Revoke) and limits per role (**recommended**: everything an administrator acts on is on one page, in
order of urgency); [B](mockups/data-out/B.html), a map of clients and nodes with a line per stream and the selected stream's detail;
[C](mockups/data-out/C.html), table-first: counts, then one requests table (live, last day, refused), nodes, limits and tokens.

**Chosen** (product owner, 2026-11-03): **A and B, both built**, as two views of one screen over one API: A, the page (the default), and B, the map ("B has cool graphics"), switched by a toggle; C is not built.

## 4. Size
One large item: the Arrow library replacing the hand-written writers, the Flight service with tickets, endpoints, streams, limits and
audit, the Python client and its tests. Flight SQL is a second, medium item if it joins the same release.

## 5. As built

| Part | Where |
|---|---|
| One Arrow library (decided 5) | Apache Arrow Java 19.0.0 for every Arrow artifact; `ArrowIpc` (compute) and `ArrowIpcWriter` (server) written with `arrow-vector`, same wire format; the hand-written flatbuffers code deleted; the console passes the server's bytes through and writes no Arrow (`core/arrow_answer.py` keeps `wanted`, `decode`, `pass_on`) |
| The service (decided 1, Q4) | module `drishti-flight`: `FlightService` (TLS always, its own port, the engine `flight` off as shipped), `DrishtiFlightProducer`, `FlightBackend` (all the server provides) |
| Tickets (Q1) | `FlightRequest`: a form (`kind`, `model`, `pivot`, `measure`, `lake`) and a Rachana request; the measure call is read by the VaR form until Rachana's own `measure(...)` parser can be called in the server |
| The forms (Q2, Q6) | `server.flight`: `KindFlightForm` (the Data view's reader, a download's checks), `AnswerFlightForms` (MQ, the pivot engine), `VarFlightForm` (`var.scenarios` float64, `var.parts` float32 for positions and trades), `LakeFlightForm` (the lake files, LAKE_CONFIG.md) |
| Tokens (Q3) | `TokenAuthenticator` (the handshake, a session after it) over `ServerFlightBackend.authenticate` (API tokens with `data:read[:domain]`, an application's token on behalf of a person, embed tokens) |
| The cluster (Q5) | `FlightNodes`: this node and the live Drishti\*net members by their real addresses; the Python client reads parts in parallel and tries the next address of a part |
| Streams (Q7) | `DoGet` with `stream: true`; the sequence in each batch's app metadata; resume is stateless (VaR: the date's intraday stamp; lake: a cursor column) |
| Limits (Q8) | `FlightLimits` per role, Arrow memory per read and per node (`ArrowMemory`, Health's `arrow-flight`), busy protection `Work.FLIGHT` |
| Audit (Q9, Q11) | `data.flight` per read; `FlightActivity`; Admin → Data out, mock-ups A (the page) and B (the map) as two views of one screen over `GET /api/v1/admin/data-out` |
| Q12 | `Add-Opens: java.base/java.nio` in the server jar's manifest; the test forks pass the option |
| Flight SQL (Q10) | `DrishtiSqlProducer` on the same port: each statement a `form: model` request with its SQL, answered by `SqlService` (DSQL) and read through the same producer; one catalog, the models as schemas, their aliases as tables (`ServerFlightSqlCatalog`); the model by a first-line comment or the header `model` |
