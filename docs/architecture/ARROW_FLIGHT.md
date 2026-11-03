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

*Release 2.9.11.c (product owner, 2026-11-03), picked up after 2.9.11.b is released. Status: **designed, not built**: every question of section 2 decided as
recommended by the product owner (2026-11-03: "I am aligned with all of them, but good documentation is the key").*

**Read with:** [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md) (2.9.11.c and 2.9.11.d), [ONE_GRAMMAR.md](ONE_GRAMMAR.md) (requests are
written in Rachana), [IN_MEMORY_LAKE.md](IN_MEMORY_LAKE.md) (section 10, the console's Python), [HIGH_PERFORMANCE_NATIVE.md](HIGH_PERFORMANCE_NATIVE.md).

## 1. Decided

1. **One service, a transport only.** A module `drishti-flight` answers Arrow Flight over the existing query layer (kinds, model queries,
   the pivot engine, VaR and the other measures, the in-memory lake) with the same entitlements, masks, busy protection and audit. No
   query logic is written twice.
2. **Any client:** Python (`pyarrow.flight`), Java, C#, R, Go; JDBC and ODBC tools through Flight SQL. Browsers keep Arrow over HTTP and
   live views over Server-Sent Events.
3. **Read only for clients** (2.9.11.d's examples never write to Drishti).
4. **The first use:** VaR scenario P&L: a scope's vector computed when asked and never stored, per-position P&L in batches, live intraday
   updates, exactly the engine's figures.
5. **No dual machinery:** the official Arrow Java library replaces the hand-written Arrow writers (compute `ArrowIpc`, `ArrowTable`;
   server `ArrowIpcWriter`, `ArrowAnswers`), and the console's own JSON-to-Arrow conversion goes; no HTTP Python reader is built.
6. **The client examples** (2.9.11.d) use Flight by default; examples that read the lake directly are labelled for people who already hold
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
| **Client pages** | one per language (Python, Java, C#, R, Go) and per tool (JDBC, ODBC): install, connect, read, stream, errors; the 2.9.11.d examples linked from each |
| **Operations** | sizing (memory for Arrow buffers per request and in total), the port in firewalls and load balancers, certificates and their renewal, monitoring (the metrics and the Admin page) |
| **Diagrams** | the request path (client, token, entitlements, the query layer, the engines, the nodes) and the cluster read |

## 4. Size

One large item: the Arrow library replacing the hand-written writers, the Flight service with tickets, endpoints, streams, limits and
audit, the Python client and its tests. Flight SQL is a second, medium item if it joins the same release.
