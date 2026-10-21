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
# BI runs: every BI query as a bounded, visible job

Status: **being built** (the steps as built are in section 18.2b; the console contract as built in 14.5). Release **2.6.7** (decided by the product owner, 2026-10-20), between 2.6.6 (QA clean) and 2.7.0
(write-back). Builds on [BI_DESIGN.md](BI_DESIGN.md) (sections 4.1 the pipeline, 6.1 caches, 6.3 concurrency, 6.5 horizontal scale, 6.7
cost controls), [RUPAKA.md](RUPAKA.md) section 7 (the query engine), [SUPER_SCALE.md](SUPER_SCALE.md) (S4 products and rollups, S5 slices
and owners, S6 split queries), [DRISHTI_NET.md](DRISHTI_NET.md) (several servers), [DATABASE.md](DATABASE.md) (where relational state
lives) and the statement bursting of [STATEMENTS.md](../guides/STATEMENTS.md), whose per-recipient masking this reuses.

## Contents

1. [The decision](#1-the-decision) · 2. [What exists and what is new](#2-what-exists-and-what-is-new) ·
3. [The pipeline](#3-the-pipeline) · 4. [The planner and the estimate](#4-the-planner-and-the-estimate) · 5. [The tiers](#5-the-tiers) ·
6. [The inline wait](#6-the-inline-wait) · 7. [The Runs tab](#7-the-runs-tab) · 8. [The result store](#8-the-result-store) ·
9. [Masks and sharing](#9-masks-and-sharing) · 10. [Fairness and admission](#10-fairness-and-admission) ·
11. [Cancellation](#11-cancellation) · 12. [Restart and failure](#12-restart-and-failure) · 13. [The cluster](#13-the-cluster) ·
14. [API and CLI](#14-api-and-cli) · 15. [Settings](#15-settings-configrunsrunsyaml) · 16. [Error codes](#16-error-codes) ·
17. [Metrics and Admin](#17-metrics-and-admin) · 18. [Build steps](#18-build-steps-two-builders) · 19. [Tests](#19-tests) ·
20. [Docs to update](#20-docs-to-update-when-built) · 21. [Open questions](#21-open-questions)

## 1. The decision

The product owner, 2026-10-20:

1. **Super scale is the architecture at every data size.** There is one code path. On a laptop one node owns every slice; on a
   cluster many nodes own a share each. Heavy external engines (Amazon Athena, Trino, Spark) are optional plug-ins the planner *may*
   use and are never required.
2. **Every BI query goes through one asynchronous pipeline:** submit → plan (size estimate) → admission → run → store the result →
   notify. The console waits briefly (about 2 s, a setting). A run that finishes within that time is drawn inline exactly as today and
   no job is visible. A longer one becomes a visible job ("Running: about 4 min, we'll notify you") and the person keeps working.
   A dashboard's slicers, cross-filters and drills stay instant: products, rollups and cached layers answer them, and they never
   create a visible job. Live (ticking) views stay streams, not jobs.
3. **Three execution tiers, chosen by the planner from metadata only.** The planner reads rows per day per table from the Delta log
   or manifests, the days asked and which products exist. It never scans to estimate.
   * *interactive*: bounded work (one date, one entity, or a product covers the question);
   * *known heavy*: answered from products and rollups written at load (S4) or by scheduled refresh;
   * *ad-hoc heavy*: a background job, split over nodes (S6) or sent to an optional distributed engine. The person sees the
     estimate and confirms it above a threshold.
4. **A Runs tab in the BI Workspace**, with a count on the bell when a run finishes. For each run it shows the report or question,
   who ran it, its parameters and filters, the as-of date and data version, the status (queued, running with progress, done, failed,
   cancelled), the duration, the rows and the estimate. The actions are open the result, re-run (on the same data or newer data),
   cancel, export, schedule, and promote to a dataset snapshot or a report. The same actions are in the CLI
   (`drishti.py bi runs …`) and the API.
5. **Rules.** A result is keyed by query fingerprint plus data version and reused across users with the same rights. Masks hold:
   a result is computed as its runner, and a shared run is re-masked or recomputed for each recipient, as bursting does.
   Results are kept 30 days unless promoted, and Admin shows the storage they use. Fairness comes from per-user queue limits and
   per-server concurrency, with heavy runs in the background class so they never slow interactive users. Estimates are honest.
   Cancelling a run frees its resources at once. After a server restart, every queued or running job is resumed or marked failed with
   a clear reason, never lost without a word. In a cluster a job runs on the node or nodes that own its data, and any console or
   server shows its status.
6. **This fixes a class of problem.** QA round 3's P1 (QA3A-01: unbounded work in the request path ran a server out of memory)
   becomes a bounded queue.

Section 2 explains what in today's code these decisions meet, and what had to change because of it.

## 2. What exists and what is new

### 2.1 Today's query path

```
 console (bi-report.js, bi-report-board.js, Analyse, the SQL editor)
   │  B.enqueue (three at a time; a 429 retried 8 times)   →  /bi/api/... (routes/bi_routes.py proxy, waits up to 35 s)
   ▼
 drishti-server
   BiReportController  POST /api/v1/bi/reports/{id}/query     ┐
   MqController        POST /api/v1/bi/mq                     ├─► MqService / BiReportService / SqlService
   SqlController       POST /api/v1/bi/sql                    ┘        │ Planner → LogicalPlan (hash), SecuritySignature, ResultCache (L1)
                                                                       ▼
                                                              QueryService.query
                                                                 cache (Caffeine, cacheKey)
                                                                 Admission.enter(user, model, WorkClass, 2 s)   DRS-9025
                                                                 run(): Layers.columns (TableBuilder builds missing dates from the source)
                                                                        estimate(...)                           DRS-9029
                                                                        Layers.rollup / SQL on DuckDbEngine (watchdog, 30 s)  DRS-9024
                                                                 cache.put
```

The request thread does all of it, start to end, and the browser holds the connection open.

### 2.2 What each piece gives this design

| Piece (real class or file) | What it does today | How BI runs uses it |
|---|---|---|
| `BiReportController`, `MqController`, `SqlController`, `StatementController` | synchronous endpoints; the answer shapes of BI_MODEL_SPEC 5.2 | kept, shapes unchanged. They submit to the pipeline and wait for it (section 6) |
| `BiReportService`, `MqService`, `SqlService` | every front end ends in one `LogicalPlan` (BI_DESIGN 4.1) | the plan hash (`Canonicalizer.hash`) is the run's fingerprint |
| `QueryService` | cache, admission, layers, SQL, limits | becomes the run's *executor* for the local engine; its `estimate` moves before any layer build (2.3) |
| `Admission` | slots (`drishti.bi.query.max-concurrent`, 8), a person's share (`max-concurrent-per-user`, 3, interactive work only), a model's share (`drishti.bi.exec.per-model-share`, 0.5), work classes `INTERACTIVE` > `STANDING` > `BACKGROUND`, a wait (`admission`, 2 s) then `DRS-9025` | the one gate. Gains a cap per class so background never holds the slots interactive work needs (section 10) |
| `QueryContext` (`WorkClass`, `budgetRows`, `budgetMillis`) | the class and budget of the work on a thread | a run sets `BACKGROUND` and its budget; the planner reads it |
| `ExecProperties` (`drishti.bi.exec.*`) | `interactive` 30 s, `background` 300 s, `max-estimated-rows` 200 M, `max-fragments` 8 per connector, `node`, `nodes` | the time limits per class; `max-estimated-rows` stays the refusal for synchronous callers, while an asynchronous caller above it gets a run (section 6) |
| `Layers`, `TableBuilder` | Parquet partitions per dataset, table and date under `<data>/bi-store`, each with `meta.json` (rows, signature, data version) | the planner reads `meta.json` without building (new `Layers.peek`, section 4) |
| `DuckDbEngine` | one in-process DuckDB, `memory-limit` 768 MB, `threads` 2, a watchdog per statement | an interactive engine as today, plus a second instance for runs (section 10.3) |
| `ResultCache` (L1), `SecuritySignature` | answers keyed by plan hash + signature + generation, in memory | stays the hot tier; the result store (section 8) is the durable tier under it |
| `SourceGateway.dataVersion`, `SourcePlugin.dataVersion(kind, date)` | a cheap version of one date's data (Delta: the date's files stamp) | the data version in a result's key |
| `DatasetRefresher` | scheduled and after-load refresh of columns and rollups; state in memory | its rebuilds become `BACKGROUND` work under admission (2.3); the *known heavy* tier relies on them |
| Rollups (`Rollups`, `rollups/<dataset>/<name>/date=…`) | per-date aggregates the optimiser matches | the *known heavy* tier |
| `LiveTables`, `GET /reports/{id}/live` (SSE), `StandingQueries` | live tables and ticks; a tick carries no data and the client asks again | unchanged; live questions are never stored or made visible jobs |
| `StatementService`, `SubscriptionService` | a statement or report rendered per recipient, as that recipient | the pattern for sharing a run (section 9); scheduled sends become background runs |
| `SubscriptionSchedule` | `daily 07:00`, `business-days 07:30`, `monthly last 18:00 business`, `after-load TRD` | the schedule language of a scheduled run |
| `InboxService`, `InboxHub`, `BiNotifier.tell` | the inbox and the bell; rows are pointers, never data | a finished run tells its runner (type `bi-run`) |
| `BiExporter`, `ViewExporter`, `XlsxWriter`, `CsvWriter` | exports of views and reports | export of a run's result |
| `ShardDirectory` | rendezvous hashing of a model key over alive nodes | the node that runs a model's heavy runs (section 13) |
| identity schema files `drishti-identity/src/main/resources/db/schema-sqlite.sql`, `schema-postgres.sql` | every relational table Drishti owns; applied idempotently at start | two new tables, `drishti_bi_run` and `drishti_bi_run_schedule` (section 3.4) |
| `drishti-console/routes/bi_routes.py` (`_ALLOWED`, `QUERY_WAIT_SECONDS` 35 s), `web/static/js/bi-lib.js` (`B.api`, `B.enqueue`, `MAX_PARALLEL` 3) | the console's BI proxy and request queue | gains `runs`, the async request header and the run card (section 6) |
| `drishti-console/web/templates/bi/workspace.html` | the workspace page, sections *Items* and *Members* | becomes three tabs: *Items*, *Runs*, *Members* (section 7) |

### 2.3 What the real code forced

The decisions meet six facts in the code. Each one changes the design here, and the build fixes each one first.

| Fact in the code | Consequence for the design |
|---|---|
| `QueryService.run` builds the layers (`Layers.columns`, which reads the source for every missing date) **before** `estimate` checks the cost. The "cost budget before anything runs" runs after the most expensive part. | The estimate moves in front of every build and reads metadata only (`Layers.peek`, `SourcePlugin.rowEstimate`). This is step R3 and the core of "never a scan to estimate". |
| No `SourcePlugin` reports row counts. `dataVersion` exists; a row count does not. | New SPI `SourcePlugin.rowEstimate(kind, date)` (default empty). Delta answers from the add actions of the date (`numRecords` in their statistics, else file bytes ÷ a learned bytes-per-row), and Iceberg from manifest `record_count`. Other connectors answer from the last built partition's `meta.json` or from history (section 4.2). |
| `WorkClass.BACKGROUND` is declared and **never set** by any caller. `DatasetRefresher` calls `QueryService.refresh`, which never enters `Admission`. `Admission` sorts waiters by class but gives every class the same slots, so eight 300-second background queries would hold all eight slots and every interactive query would wait 2 s and get `DRS-9025`. | `Admission` gains a cap per class (`background-share`, `standing-share`). Refresh and runs enter as `BACKGROUND`. Interactive work keeps a reserve that background work cannot take (section 10). |
| `DuckDbEngine` is one DuckDB instance with one `memory_limit` (768 MB) and `threads` (2) shared by every query. A heavy aggregate that spills slows every interactive query on the node. | Runs execute on a **second DuckDB instance** with its own memory limit and threads (`engine.memory-limit`, `engine.threads`). Interactive work keeps its own. |
| `GenerationBus` generations are per process and start again at every start. | A durable result is keyed by the **data version vector** (`dataVersion` of every table and date read), not the generation. The L1 cache keeps using generations. |
| `ShardDirectory.cluster(...)` is called only in `TwoNodesTest`; production never wires model ownership to Drishti*net. S5 (slices) and S6 (split queries) of SUPER_SCALE are designs, not built. No external engine adapter exists. | 2.6.7 wires `ShardDirectory` to Drishti*net membership for runs (R7). An ad-hoc heavy run executes on the model's owning node through the local engine. *Split* (S6) and *external* engines are slots behind a `RunEngine` interface that 2.6.7 defines but does not fill (section 5.4). |

Four smaller facts also shaped the design:

* **"Dataset snapshot" does not exist as a stored result.** `snapshot` is a time mode of `DatasetDef.Time`. Promoting a run therefore
  adds a table source type, `run` (`DatasetDef.Source.RUN`), that reads a promoted result's Parquet (section 9.4).
* **The one Drishti database is 2.7.0 item 0**, not built yet. Run records cannot wait for it, and DATABASE.md forbids a feature
  opening its own SQLite file. They go into today's identity database through its two schema files, as new tables (which needs no
  migration); DATABASE.md's move carries them with everything else.
* **Synchronous callers exist**: `drishti.py bi mq`, the Python client, Elements, subscriptions and the console. They expect `200`
  and today's body. The asynchronous answer is opt-in with the standard `Prefer: respond-async` header (RFC 7240). Without it an
  endpoint waits as today.
* **QA3A-01 was a load-signal storm, not a BI query.** Sixty data-load signals each started a full `derived pages` pass. Virtual
  threads parked in `DeltaSourcePlugin.doc` on `slots.acquireUninterruptibly()` after their callers had timed out, and the backlog
  ran the heap out. Runs bound BI query work and add the rule that *work whose caller is gone is cancelled* (section 11), including
  interruptible waits for connector slots. The load-signal path needs the same coalescing. That is a 2.6.6 fix and is noted, not
  designed, here.

## 3. The pipeline

### 3.1 The flow

```
   submit ──► plan ──► admit ──► run ──► store ──► notify
     │          │         │        │        │         │
     │          │         │        │        │         └─ inbox row (type bi-run) + bell, only for a visible run
     │          │         │        │        └─ Parquet under <data>/bi-runs/results, keyed (section 8); never for live
     │          │         │        └─ the chosen RunEngine: local DuckDB (interactive engine or run engine), split, external
     │          │         └─ RunQueue (per user, per server) then Admission (class caps, slots, person and model shares)
     │          └─ RunPlanner: fingerprint, data version, reuse lookup, estimate, tier, engine, confirm?
     └─ RunService.submit(question, caller, options) → a Run handle (in memory) and a future
```

`RunService` (new, `com.ash.drishti.server.bi.runs`) owns the flow. Every BI question enters through it: report visuals, model
queries, SQL, statements and subscription sends. A question is the `LogicalPlan` with its resolved time, its caller and its options.

### 3.2 States

| State | Meaning | Visible in Runs | Stored row |
|---|---|---|---|
| `planned` | fingerprint, estimate and tier known; nothing has run | no | no |
| `awaiting-confirm` | ad-hoc heavy above `confirm.above-seconds` or `confirm.above-rows`; the person has been shown the estimate | yes (with *Confirm* and *Discard*) | yes |
| `queued` | waiting in `RunQueue` or in `Admission` | once visible | once visible |
| `running` | holds an admission ticket; `progress` moves | once visible | once visible |
| `storing` | the engine finished; the Parquet is being written and renamed | once visible | once visible |
| `done` | the result is in the store (or was served inline) | once visible | once visible |
| `reused` | the same key's result existed; nothing ran | once visible | once visible |
| `failed` | ended with a code and a reason | once visible | once visible |
| `cancelled` | stopped by its runner, an administrator, a newer question for the same visual, or a gone caller | once visible | once visible |
| `expired` | the result was removed by retention or the storage budget; the record is kept | yes | yes |

A run becomes **visible** when it crosses the inline wait (section 6), when it is submitted asynchronously (`Prefer: respond-async`
with `wait=0`, the CLI's `bi runs submit`, a schedule), or when it needs confirmation. A run that finishes inside the wait is never
visible: it writes no row and sends no notice. Its result still goes to the store when it is worth keeping (section 8.3).

### 3.3 Transitions

```
                    ┌──────────── reuse hit (same key, same or re-maskable rights) ───────────────► reused
                    │
 submit ─► planned ─┼── ad-hoc heavy, above confirm threshold ─► awaiting-confirm ─┬─ confirm ─► queued
                    │                                                            ├─ discard ─► cancelled
                    │                                                            └─ confirm-ttl (15 min) ─► cancelled
                    │
                    ├── interaction question that is ad-hoc heavy ─► failed (DRS-9089, with the estimate; nothing ran)
                    │
                    └── otherwise ─► queued ─► running ─► storing ─► done
                                       │         │          │
                                       │         │          └─ write failed / store full ─► failed (DRS-9088)
                                       │         ├─ engine error, time limit, row/byte limit ─► failed (DRS-9024/9027/9022/9023/9028)
                                       │         ├─ cancel ─► cancelled
                                       │         └─ node stopped ─► (after restart) queued (attempt + 1) | failed (DRS-9085)
                                       ├─ cancel ─► cancelled
                                       ├─ queue full at submit ─► failed (DRS-9081), never queued
                                       └─ node stopped ─► (after restart) queued | failed (DRS-9085)

 done ─► expired (retention or budget)          done | reused ─► (re-run) a new run, linked by rerunOf
```

Every transition is one method of `RunService`, under the run's lock. Each writes the row first, if the run is visible, and then
publishes the event (section 3.5). The allowed transitions are a table in `RunState`, and an illegal one throws, which the unit tests
cover (section 19).

### 3.4 The run record

The `drishti_bi_run` table goes in both identity schema files. A parity test keeps them identical.

```sql
CREATE TABLE IF NOT EXISTS drishti_bi_run (
    id            TEXT PRIMARY KEY,          -- r-<node>-<ulid>: the node that owns it is in the id (section 13)
    runner        TEXT NOT NULL,             -- the user it is computed as
    owner         TEXT NOT NULL,             -- who sees and manages it (the runner, or a schedule's owner)
    workspace     TEXT,                      -- the item's workspace, for the workspace's Runs tab
    source_type   TEXT NOT NULL,             -- report | board | mq | sql | statement | subscription | schedule
    source_id     TEXT,                      -- the item id (report, board, model), when there is one
    source_ref    TEXT,                      -- page/visual, or the item version
    question      TEXT NOT NULL,             -- the canonical MQ (JSON) that ran: replayable
    params        TEXT,                      -- slicers, filters, parameters, drill path (JSON, as the reader sent them)
    as_of         TEXT,                      -- the resolved dates (JSON list, or "from..to")
    data_version  TEXT,                      -- hash of the data version vector; the vector itself is in the result's answer.json
    fingerprint   TEXT NOT NULL,             -- the plan hash
    signature     TEXT NOT NULL,             -- SecuritySignature of the runner
    result_key    TEXT,                      -- section 8.2
    tier          TEXT NOT NULL,             -- interactive | known-heavy | ad-hoc-heavy
    engine        TEXT NOT NULL,             -- local | split | external:<name>
    state         TEXT NOT NULL,
    progress      REAL,                      -- 0..1, null when unknown
    phase         TEXT,                      -- building 12 of 60 dates | scanning | storing
    estimate      TEXT NOT NULL,             -- JSON: rows, bytes, seconds low/high, basis (section 4.4)
    rows_out      INTEGER,
    bytes_out     INTEGER,
    code          TEXT,                      -- DRS code of a failure or cancellation
    reason        TEXT,                      -- one plain sentence
    node          TEXT NOT NULL,             -- the node running it
    attempt       INTEGER NOT NULL DEFAULT 1,
    lease_until   TIMESTAMP,                 -- renewed by the running node (section 13.3)
    submitted_at  TIMESTAMP NOT NULL,
    started_at    TIMESTAMP,
    finished_at   TIMESTAMP,
    expires_at    TIMESTAMP,                 -- null while promoted or pinned
    rerun_of      TEXT,
    shared_from   TEXT,                      -- a recipient's recomputed run points at the original
    group_id      TEXT,                      -- the visuals of one page draw that became runs
    promoted_to   TEXT                       -- the dataset or report item a promotion made
);
CREATE INDEX IF NOT EXISTS drishti_bi_run_owner ON drishti_bi_run (owner, submitted_at);
CREATE INDEX IF NOT EXISTS drishti_bi_run_state ON drishti_bi_run (node, state);
CREATE INDEX IF NOT EXISTS drishti_bi_run_key ON drishti_bi_run (result_key);

CREATE TABLE IF NOT EXISTS drishti_bi_run_share (
    run_id TEXT NOT NULL, recipient TEXT NOT NULL, recipient_run TEXT, shared_by TEXT NOT NULL, shared_at TIMESTAMP NOT NULL,
    PRIMARY KEY (run_id, recipient));

CREATE TABLE IF NOT EXISTS drishti_bi_run_schedule (
    id TEXT PRIMARY KEY, owner TEXT NOT NULL, question TEXT NOT NULL, params TEXT, source_type TEXT NOT NULL, source_id TEXT,
    schedule TEXT NOT NULL,                   -- SubscriptionSchedule text: business-days 07:30, after-load TRD
    data TEXT NOT NULL DEFAULT 'latest',      -- latest | the same as-of rule as the run it was made from
    enabled INTEGER NOT NULL DEFAULT 1, last_run TEXT, next_at TIMESTAMP, created_at TIMESTAMP NOT NULL);
```

As built (R1), the tables also hold: `drishti_bi_run.title` (shown in Runs), `rows_read` (the actual rows read), `last_opened`
(retention counts from the last open, 8.4) and `reuse_of` (the run whose stored result a reused or joined run reads);
`drishti_bi_run_share.mode` (`same`, `re-masked`, `recomputed`); `drishti_bi_run_schedule.run_id` and `title` (the run it was made
from). The index names follow the file's `ix_` convention (`ix_bi_run_owner`, `ix_bi_run_state`, `ix_bi_run_key`,
`ix_bi_run_share_recipient`, `ix_bi_run_schedule_owner`). `SchemaParityTest` (drishti-it) keeps the two schema files identical in
tables, columns, nullability and indexes.

No data value is ever written to these tables. `question` and `params` hold names, operators and the values the reader typed into
filters, which the run's owner already knows. `params` is shown only to people who may see the run.

### 3.5 Events

`RunService` publishes `RunEvent(runId, state, progress, phase)` on an in-process bus. Subscribers:

* the console's waiter for the inline answer (section 6);
* `GET /api/v1/bi/runs/events` (SSE): the person's own runs. The Runs tab and the bell listen to it, with one stream per browser
  tab, under `drishti.bi.live.max-streams-per-user`;
* `RunNotifier`: on `done`, `failed` or `cancelled` (when the person did not cancel it) of a visible run, one inbox row through
  `BiNotifier.tell` (type `bi-run`, kind `bi`, entityId the run id, summary "Run finished: Desk P&L by month, 2026-01-02 to
  2026-09-30 (182 rows, 3 min 40 s)"). Names and counts only, never a value, as every inbox row.

## 4. The planner and the estimate

`RunPlanner` (new) runs after the front end has produced the `LogicalPlan` and before anything reads data. It is pure apart from
metadata lookups, and it takes milliseconds.

### 4.1 Inputs

| Input | Where it comes from | Cost |
|---|---|---|
| the plan: tables, measures, grain, filters, rollup match | `Planner`, `Optimiser` (the rollup the optimiser would choose) | in memory |
| the dates asked | the plan's resolved time (`as-of`, a window, `RelativeDates`), the business calendar | in memory |
| rows per table per date | in order: (1) `Layers.peek` reads the partition's `meta.json` when the partition is valid; (2) `SourcePlugin.rowEstimate(kind, date)`; (3) the table's learned rows per day (`RunHistory`, below); (4) unknown | one small file read, a log lookup, or memory |
| whether each partition is built | `Layers.peek`: valid (signature and data version match) or to build | as above |
| products | rollups declared on the model and built for the dates (`Layers.peek` on `rollups/…`); SUPER_SCALE S4 products (link indexes, group totals) once they exist, through the same `peek` | as above |
| data version vector | `SourceGateway.dataVersion(connector, kind, date)` per table and date | cheap by contract (SourcePlugin) |
| rates | `RunRates`: rows per second learned per connector and path (scan, record read) for builds, and per engine for scans; seeded from settings | memory |
| live tables | `LiveTables` | memory |

`SourcePlugin.rowEstimate(kind, date)` is new, with a default of `Optional.empty()`. Implementations:

| Connector | Answer |
|---|---|
| Delta (`DeltaSourcePlugin`) | from `DeltaTable.Layout.files` of the date: the sum of `numRecords` in each add action's statistics; for files without statistics, file bytes ÷ bytes per row (learned from files that have both) |
| Iceberg (`drishti-plugin-iceberg`, `drishti-iceberg` `ManifestReader`) | the sum of `record_count` of the date's data files in the current snapshot's manifests |
| DuckDB, PostgreSQL, JDBC | optional: the store's table statistics (`reltuples`, `duckdb_tables().estimated_size`) ÷ the dates the store holds, flagged `basis: store-stats` |
| others | empty: steps (3) and (4) apply |

`RunHistory` keeps, per (dataset table, connector), an exponentially weighted mean of rows per business day seen in built
partitions (`meta.json` rows) and finished runs. It lives in memory and is rebuilt at start from the `meta.json` files the BI store
already holds.

### 4.2 Formulas

For a plan `P` over tables `T` and dates `D`:

```
 rows(t, d)      = peek(t, d).rows                if the partition is valid
                 | rowEstimate(t.kind, d)         else, if the source answers
                 | history(t).rowsPerDay          else, if known
                 | unknown

 covered(P)      = the optimiser's rollup (or S4 product) for P exists and is valid for every d in D

 readRows(P)     = Σ_{d∈D} rollupRows(r, d)                               if covered(P)
                 | Σ_{t∈T} Σ_{d∈D} rows(t, d)                             otherwise

 buildRows(P)    = Σ_{t∈T} Σ_{d∈D, partition to build} rows(t, d)         (0 when covered)

 seconds(P)      = buildRows / rate.build(connector of t, path)           summed per connector, divided by its fragment share
                 + readRows  / rate.scan(engine)
                 + fixed.overhead (0.05 s)

 bytes(P)        = readRows × bytesPerRow(columns read)                   bytesPerRow from the partitions' Parquet footers, else type widths
 rowsOut(P)      = min(limit, Π grain cardinalities)                      cardinalities from the dimensions' layers, else readRows
```

Unknown rows are never treated as zero. If any `rows(t, d)` is unknown, the estimate is `basis: unknown` and the question is
treated as ad-hoc heavy until it has run once (section 4.4).

### 4.3 Decisions

| Decision | Rule (settings in section 15) |
|---|---|
| tier | section 5.1 |
| engine | section 5.4 |
| confirm | ad-hoc heavy **and** (`seconds.high` > `confirm.above-seconds` (60 s) **or** `readRows` > `confirm.above-rows` (500 M) **or** `basis: unknown`) |
| refuse | `readRows` > the largest engine's `max-rows` (local: `engine.max-read-rows`, 5 B) and no split or external engine is configured: `DRS-9086` with the estimate and the fix (narrow the dates, use a rollup grain, configure an engine) |
| reuse | a stored result with the same key (section 8.2) is valid: `reused`, nothing runs |

### 4.4 Honest estimates

The estimate is shown as a range with its basis, never as a single false-precise number.

```json
"estimate": {
  "readRows": 1840000000, "buildRows": 410000000, "bytes": 62000000000, "rowsOut": 4320,
  "seconds": {"low": 150, "high": 330}, "basis": "delta-stats",
  "dates": 182, "datesToBuild": 41, "covered": false,
  "why": "182 business dates of trade_endur (about 10.1 M rows a day; 41 dates not built yet); no rollup has grain desk × month"
}
```

* The range is `seconds × [low-factor, high-factor]`. The factors start at `[0.5, 2.0]` and narrow to the 10th and 90th percentiles of
  the measured actual ÷ estimate ratio once `RunRates` holds at least 20 runs for that engine.
* `basis` is `layers` (every partition built), `delta-stats`, `iceberg-manifests`, `store-stats`, `history` or `unknown`. With
  `unknown` the console says "We cannot estimate this yet: it reads tables whose size we do not know. It may take a long time."
* Every finished run records its actual rows, bytes and seconds next to its estimate. Admin shows how accurate estimates are (section 17).
* Progress is honest too. `progress` is measured, never guessed: built dates ÷ dates to build while building, then rows scanned ÷
  `readRows` (DuckDB's `query_progress()` on the run's connection), else `null`. The console then shows "running", not a bar.

## 5. The tiers

### 5.1 Choosing the tier

```
                        ┌───────────── live table in the plan? ── yes ──► INTERACTIVE, never stored, never visible (a stream)
 plan ──► RunPlanner ───┤
                        ├── covered(P) by a rollup or product, all dates valid ──► KNOWN HEAVY if readRows(raw) > interactive.max-rows
                        │                                                         else INTERACTIVE
                        └── not covered:
                              seconds.high ≤ interactive.max-seconds (10 s) and readRows ≤ interactive.max-rows (50 M)
                              and datesToBuild ≤ interactive.max-build-dates (2)  ──► INTERACTIVE
                              otherwise                                           ──► AD-HOC HEAVY
```

| Tier | Typical question | Engine and class | Visible job? | Stored? |
|---|---|---|---|---|
| **interactive** | one date; one entity; a page's visuals on the current business date; a slicer, cross-filter or drill on a covered question | the interactive DuckDB, `INTERACTIVE` class, `drishti.bi.exec.interactive` (30 s) | only if it outlives the inline wait (section 6), and never for interactions | if `store.keep-interactive` and the run took over `store.min-seconds` (1 s) |
| **known heavy** | a year of desk P&L by month where the rollup by desk × month exists; a counterparty page over a year from S4 group totals | the interactive DuckDB over rollup partitions, `INTERACTIVE` class (reads are small) | as interactive | as interactive |
| **ad-hoc heavy** | a year at a grain no rollup has; raw rows over many dates; a table never built | the run engine (section 10.3), `BACKGROUND` class, `drishti.bi.exec.background` (raised to `run.max-duration`, 4 h, for runs) | yes, unless it finishes inside the wait | always |

### 5.2 Known heavy depends on products being there

A question is *known heavy* only when its products exist for **every** date asked. When some dates lack the rollup (a new date not yet
refreshed, a rollup declared yesterday), the planner does not silently fall back to raw rows inline:

* if the missing dates' raw rows fit the interactive bounds, it reads the rollup for the built dates and the raw layer for the rest
  in one plan (the optimiser already mixes layers per date), still interactive;
* otherwise it is ad-hoc heavy, and the run's `why` names the missing rollup dates. `DatasetRefresher` is asked to build them
  (`BACKGROUND`), so the next asker gets *known heavy*.

Rollups are written by scheduled refresh (`DatasetRefresher`, `refresh: after-load`) today, and by the loaders (S4) once SUPER_SCALE
step S4 lands. The planner does not care which wrote them; it reads `peek`.

### 5.3 Interactions never become jobs

A request from a slicer, cross-filter or drill carries `interaction: true`. The console sets it in `stateFor` in `bi-report.js` when
the page's state changed by an interaction, not on the page's first draw. Such a question:

* runs only if the planner says *interactive* or *known heavy*;
* if the planner says *ad-hoc heavy*, it fails at once with `DRS-9089` and the estimate. Nothing ran. The visual shows "This
  selection needs a background run (about 3 to 6 min)" with **Run in background**, which submits the same question as a visible
  run;
* if it is still running at `drishti.bi.exec.interactive` (30 s), it is cancelled with `DRS-9024`, as today. It never crosses into a
  visible job;
* a newer interaction on the same visual (`supersedes: <page>/<visual>`) cancels the older one at once (section 11).

This keeps the decision ("dashboards stay instant") as a guarantee rather than a hope. An interaction is either fast because a
product covers it or refused at once with the way forward.

### 5.4 Engines

```java
public interface RunEngine {
    String name();                                         // local | split | external:athena | external:trino | external:spark
    boolean accepts(RunPlan p);                            // the tables are readable by it; the plan's operators are supported
    Limits limits();                                       // max read rows, max seconds, max result bytes
    Execution start(RunPlan p, ResultSink sink, CancelToken c);   // streams Arrow batches into the sink; honours cancel
}
```

| Engine | 2.6.7 | Chosen when |
|---|---|---|
| `local` | **built**: `QueryService` on the interactive DuckDB (interactive, known heavy) or on the run DuckDB (ad-hoc heavy) | always available; the only engine on a laptop |
| `split` | interface only; filled by SUPER_SCALE S6 (each slice owner aggregates its share, the coordinator merges partial sums, counts and sketches) | ad-hoc heavy, the model's tables are `ownership: sliced`, and more than one node is alive |
| `external:<name>` | interface only; adapters are 3.0 groundwork (BI_DESIGN 12.2 wave E item 3, "a second query engine behind the tier planner") | ad-hoc heavy, `local` and `split` would exceed their limits (or `prefer-external-above-seconds`), and an adapter is configured whose `accepts` holds |

Row rules and masks are applied **before** a plan leaves for any engine (`SecurityRewriter`, stage 4), so an external engine sees
only predicates and projections already restricted for the runner. An external engine never receives an unrestricted plan.

## 6. The inline wait

### 6.1 How a request waits

```
 browser ──POST /bi/api/reports/{id}/query  Prefer: respond-async, wait=2 ──► console proxy ──► server
                                                                                                  │
                                RunService.submit ─► future                                       │
                                        │                                                         │
           done within 2 s ─────────────┤──► 200 + today's body (the run stays invisible) ─────────┤
           not done at 2 s ─────────────┘──► 202 + {run} ; the run becomes visible, keeps going ───┘
           ad-hoc heavy, needs confirm ─────► 409 DRS-9082 + {estimate, confirmToken}
```

* The wait happens on the server (`wait` in `Prefer`, capped by `inline.max-wait`, 10 s), so no polling is needed for the common
  case. Its default is `inline.wait` (2 s).
* An ad-hoc heavy question that does not need confirmation is answered `202` **at once** without waiting. Its estimate already says
  it will not finish in 2 s.
* Without `Prefer: respond-async` an endpoint behaves as today: it waits up to its time limit (`drishti.bi.exec.interactive`) and
  answers `200` or a problem. The run is still a run (planned, admitted, bounded, stored when worth it) but never visible, whatever
  its tier, so a script that worked in 2.6.6 works unchanged. Its limits are today's too: past `drishti.bi.exec.max-estimated-rows`
  it gets `DRS-9029`, now with `run: {submit: "POST /api/v1/bi/runs"}` in the problem so a script knows how to ask for it as a run,
  and past the time limit `DRS-9024`. The difference is that the estimate now comes before any layer build (2.3), so the refusal
  costs nothing.
* The console proxy (`routes/bi_routes.py`) passes `Prefer` through. For asynchronous calls it waits `wait + 5 s` instead of
  `QUERY_WAIT_SECONDS` (35 s).

### 6.2 What the person sees

A visual whose query became a run shows a run card in place of its chart, and the rest of the page keeps working:

```
 ┌─ MTM by desk and month ─────────────────────────────────────────────┐
 │                                                                     │
 │   ◔  Running: about 3 to 6 min                                      │
 │      182 business days of trade_endur, 41 to build first            │
 │      Building 12 of 41 dates ▓▓▓▓▓░░░░░░░░░░  29 %                  │
 │                                                                     │
 │      We'll put it on the bell when it is ready. You can leave.      │
 │      [ Cancel ]  [ Open in Runs ]                                   │
 └─────────────────────────────────────────────────────────────────────┘
```

A question that needs confirmation shows the estimate first:

```
 ┌─ Confirm a background run ───────────────────────────────────────────┐
 │ MTM by desk and month, 2026-01-02 to 2026-09-30                      │
 │                                                                      │
 │ About 1.8 billion rows over 182 business days (Delta statistics).    │
 │ Expected 3 to 6 minutes on this server. No rollup has desk × month;  │
 │ adding one to the model makes this instant next time.                │
 │                                                                      │
 │ [ Run in background ]   [ Narrow the dates ]   [ Not now ]           │
 └──────────────────────────────────────────────────────────────────────┘
```

When the run finishes while the page is open, the card turns into the chart without a reload. The page's SSE (`/runs/events`)
delivers `done`, and the visual asks `GET /runs/{id}/result?format=arrow`. When the person has left, the bell shows the count and
the inbox row links to the report with `?run=<id>`, which draws the visual from the stored result.

A page draw that makes several runs (three heavy visuals) groups them under one `group_id`. The Runs tab shows one row for the page,
which expands to its visuals, and the bell rings once, when the last of them finishes.

### 6.3 What does not change

* Visuals whose queries finish in time look and behave exactly as today. No card, no row, no notice.
* `B.enqueue` and `MAX_PARALLEL` (3) stay. A `202` frees the browser's slot at once, so a heavy visual no longer holds one of the
  three for 30 s.
* Live views: the SSE `live` stream and its ticks are unchanged. A tick's re-ask is an interactive question and is never stored.

## 7. The Runs tab

### 7.1 Where

* `/bi/w/me` (My workspace) and `/bi/w/<workspace>` get a tab strip: **Items · Runs · Members**. The Runs tab of My workspace
  shows the person's own runs and runs shared with them. The Runs tab of a shared workspace shows the runs of that workspace's items
  that the person may see (section 9.1).
* The BI rail gets **Runs**, a link to `/bi/w/me?tab=runs`, with a small count of the person's running runs.
* `BI RUNS <GO>` in the top bar opens it.
* The bell's count includes finished-run notices, as it does every other notice (`inbox.js`, `drishti:unread`).

### 7.2 The list

```
 My workspace                                                                    [Items] [Runs] [Members]
 ───────────────────────────────────────────────────────────────────────────────────────────────────────────
  Runs   Status [All ▾]  Source [All ▾]  Who [Me ▾]  Since [30 days ▾]   Search [ desk pnl        ]  ⟳ live
 ┌──┬──────────────────────────────┬──────────┬────────────┬──────────────────┬──────────┬─────────┬────────┬─────┐
 │  │ Question                     │ Status   │ As of      │ Data             │ Duration │ Rows    │ Est.   │     │
 ├──┼──────────────────────────────┼──────────┼────────────┼──────────────────┼──────────┼─────────┼────────┼─────┤
 │ ◔│ MTM by desk and month        │ Running  │ 01-02…09-30│ delta v412       │ 1 m 52 s │ –       │ 3–6 m  │ ⋯   │
 │  │ Desk P&L · page Overview     │ 29 %     │            │                  │          │         │        │     │
 │ ✓│ Notional by counterparty     │ Done     │ 2026-09-30 │ delta v412       │ 41 s     │ 1,204   │ 30–90 s│ ⋯   │
 │  │ mq · trading                 │          │            │                  │          │         │        │     │
 │ ↺│ VaR by book (shared by anna) │ Done ⇄   │ 2026-09-30 │ delta v410       │ –        │ 88      │ –      │ ⋯   │
 │ ✕│ Raw trades 2025              │ Failed   │ 2025       │ –                │ 4 m 03 s │ –       │ 2–9 m  │ ⋯   │
 │  │ DRS-9028 the run ran out of its memory budget (4 GB): narrow the dates or add a rollup                  │
 │ ⊘│ Cash ladder 90 days          │ Cancelled│ 2026-09-30 │ –                │ 12 s     │ –       │ 1–2 m  │ ⋯   │
 │ ⌛│ Exposure by legal entity     │ Expired  │ 2026-08-29 │ delta v388       │ 2 m 10 s │ 52      │        │ ⋯   │
 └──┴──────────────────────────────┴──────────┴────────────┴──────────────────┴──────────┴─────────┴────────┴─────┘
  Storage: 1.4 GB of your results · kept 30 days unless promoted                                 1–6 of 48  [More]
```

* *Data* is the source's version in words (`delta v412`, a Delta table version; `iceberg snap 7…`; `layers 2026-10-20 07:31` when
  only built layers were read). Hovering it lists every table and date version (the vector).
* *Est.* is the range the person was shown. A finished run's tooltip shows "estimated 3 to 6 min, took 4 min 12 s".
* ⇄ marks a run shared with the person. Its row says by whom, and whether they see the original result (same rights), a re-masked
  one, or their own recomputation (section 9).
* Rows update live from `/runs/events`. There is no polling.

### 7.3 One run

```
 ┌─ MTM by desk and month ──────────────────────────────────────────────────────────────────────── ✕ ┐
 │ Done · 4 min 12 s · 4,320 rows · 1.1 MB                    [Open] [Re-run ▾] [Export ▾] [⋯]       │
 │                                                                                                   │
 │ Source       Report "Desk P&L", page Overview, visual "MTM by desk and month"   (open the report) │
 │ Run by       ajsinha, computed as ajsinha · 2026-10-20 09:14 on node java-2                       │
 │ Filters      DESK in (RATES-NY, RATES-LDN) · PRODUCT_TYPE ≠ FX_SPOT                               │
 │ Parameters   currency = USD · period = this year                                                  │
 │ As of        2026-01-02 to 2026-09-30 (182 business dates)                                        │
 │ Data         trade_endur delta v412 · desk_dim layers 2026-10-20 07:31            (all versions)  │
 │ Estimate     3 to 6 min, 1.8 B rows read (Delta statistics) · actual 4 min 12 s, 1.79 B rows      │
 │ Tier         ad-hoc heavy · local engine (run engine) · background                                │
 │ Kept until   2026-11-19 (30 days)                      [Keep longer] [Promote ▾] [Delete result]  │
 │ Shared with  bob (re-masked: NOTIONAL hidden for him), risk-team (3 recomputed)      [Share…]     │
 │ Schedule     —                                                                     [Schedule…]    │
 └───────────────────────────────────────────────────────────────────────────────────────────────────┘
```

| Action | What it does | Who |
|---|---|---|
| **Open** | the report with `?run=<id>` (the visual drawn from the stored result), or for MQ/SQL a result grid (`/bi/runs/<id>`) with the Analyse grid's sort, filter and pivot, all done client-side on the stored rows | anyone who may see the run |
| **Re-run ▾ → on the same data** | a new run with the same question and the same as-of dates. For sources that keep versions (Delta, Iceberg) it reads the recorded versions (`X-Drishti-Known-At` = the original's `submitted_at`). For others, when today's data version differs, it says so first: "2026-09-30 of trade_endur changed since (v410 → v412); the answer may differ" | the runner |
| **Re-run ▾ → on the newest data** | the same question with relative dates resolved again (`this year`, `latest`) | the runner |
| **Cancel** | section 11 | the runner, a bi-admin |
| **Export ▾** | CSV, Excel, Parquet, and PDF for a report visual, from the stored result through `BiExporter` (no recomputation). Masked cells stay masked | anyone who may see the result |
| **Schedule…** | a `drishti_bi_run_schedule` row with a `SubscriptionSchedule` expression. Each firing is a background run and a notice. Email is the existing subscription's job and is offered as a link to it | the runner |
| **Promote ▾ → dataset snapshot** | section 9.4 | a bi-author |
| **Promote ▾ → report** | a draft report in the person's workspace with this question as a table visual (MQ/SQL runs), or the run's report pinned to the run's as-of dates | a bi-author |
| **Share…** | section 9.3 | the runner |
| **Keep longer / Delete result** | sets `expires_at` (up to `retention.max-days`), or removes the result now (the row stays, `expired`) | the runner, a bi-admin |
| **Confirm / Discard** | for `awaiting-confirm` | the runner |

## 8. The result store

### 8.1 On disk

Everything is under the configurable data folder, with visible names only:

```
 <drishti.data.dir>/bi-runs/
   results/
     7f/7f3a…c1/                       ← result key (section 8.2), sharded by its first two hex digits
       result.parquet                   ← the rows: Parquet, zstd, row groups of 64 K rows, typed as the answer's columns
       answer.json                      ← the answer without rows: columns (ids, types, additivity, masks), totals, meta,
                                          the data version vector, the canonical question, the signature id, created, bytes
   snapshots/
     desk-pnl-2026/v3/result.parquet    ← promoted results (section 9.4): never expire, outside the budget
     desk-pnl-2026/v3/answer.json
   tmp/
     r-java-2-01JA…/                    ← a result being written; renamed into results/ when complete; swept at start
```

* The path is `runs.store.dir`, with default `${drishti.data.dir}/bi-runs`. On AWS (EFS with identical paths on every node,
  AWS_DEPLOYMENT.md) the folder is shared and any node can read any result. Otherwise results live on the node that ran them, and
  the others forward (section 13).
* **Parquet** is the stored format: compact, typed, and read back by DuckDB with no parsing. **Arrow IPC** is the wire format: a
  result is served by DuckDB reading `result.parquet` (`LIMIT/OFFSET` for a page) into `ArrowIpcWriter`, the way layers are
  served today. JSON is served for results up to `drishti.bi.query.json-max-rows` (5,000).
* The engine writes the result **directly**. The local engine uses DuckDB `COPY (…) TO 'tmp/<run>/result.parquet'` on the run's
  connection, so rows never pass through the Java heap. This is the lesson of QA3A-01.
* A file is written under `tmp/` and renamed into place (`FileSwap`, which handles Windows' held-open files), so a reader never
  sees half a result.

### 8.2 The key

```
 resultKey = sha256( planHash                      -- Canonicalizer.hash(LogicalPlan): question, model at its fingerprint
                   | resolvedTime                  -- the dates actually read ("latest" resolved)
                   | dataVersionVector             -- sorted (connector, kind, date) → SourcePlugin.dataVersion(kind, date)
                   | securitySignature             -- SecuritySignature.of(flat model, reader)
                   | engineSemantics )             -- "v1": bumped if an engine change alters answers (approximate sketches, rounding)
```

* The **data version vector** replaces the generation for durable keys (section 2.3). A restated date has a new version, so a new
  key, so the old result is never served for new data.
* Two readers with the **same signature** (the same rows, masks and denied tables) share a result. That is the decision's
  "reused across users with the same rights", and the same rule the L1 `ResultCache` already follows.
* A source that cannot give a data version (`dataVersion` empty) makes the result **not reusable**. It is stored for its runner
  (open, export, promote) but never served for another submit. The run detail says "this source has no data version: re-run to
  refresh".

### 8.3 Reuse

| Situation | What happens |
|---|---|
| submit, a `done` result with the same key exists | `reused` at once; the run row points at the existing result; `rows_out` copied; no admission, no engine |
| same plan and data, different signature | section 9.2 (re-mask or recompute) |
| an interactive question that took under `store.min-seconds` (1 s) | not stored (the L1 cache serves repeats); storing costs more than recomputing |
| an interactive question that took longer | stored when `store.keep-interactive` (true): the next reader's slow first draw becomes a reuse |
| live tables in the plan | never stored |

The L1 `ResultCache` stays in front, in memory: a hit there is microseconds. The store is the tier under it. An L1 miss with a store
hit loads `result.parquet` (milliseconds) and puts it in L1 when it is under L1's entry weight.

### 8.3a Fewer duplicate runs (product owner, 2026-10-20)

Five rules keep the same work from running twice. The first two are the key and the join (8.2, 8.3, 13.3); the other three were
added on the product owner's word.

| Rule | What it does | Where |
|---|---|---|
| **Same key, reuse** | the same question on the same data for the same rights opens the stored result | 8.2, 8.3 |
| **Join a running twin** | a submit whose key matches a running run follows it (single-flight) instead of starting another | 13.3 |
| **Normalise before hashing** | `Canonicalizer` sorts filter values and conjuncts, fills parameter and well defaults, resolves relative dates (`this year`, `last month`, `latest`) to the dates read, and drops aliases and visual-only properties (column order, titles, formats) that do not change the rows, so `region in (EU, US)` and `region in (US, EU)` hash alike. A test lists equivalent pairs that must hash the same and near pairs that must not | 8.2 (`planHash`) |
| **"This already ran"** | before an ad-hoc heavy run starts, the confirm step looks for a `done` run of the same plan and signature on **older** data. When one exists it offers *Open that result* (with its as-of date and age) or *Run on the newest data* (the estimate). Many people need only yesterday's answer | 6.2 confirm dialog, `GET /api/v1/bi/runs?plan=<hash>&latest=1` |
| **Repeated heavy questions become products** | the run log counts ad-hoc heavy runs per (model, grain, filters shape). Past `suggest.min-runs` (default 5 in 7 days) and `suggest.min-seconds` (default 60), the model's owner sees *"asked 37 times this week, about 4 min each: make it a rollup refreshed at load"* in the model designer and Admin → Runs, with the rollup YAML drafted for review (BI_DESIGN 6.1 *suggested aggregations*); once the rollup exists those questions are known heavy and answer instantly | Admin → Runs, model designer; in 2.6.7 |

Measured, not assumed: Admin → Runs shows the **reuse rate** (answered from a stored result), the **join rate** (attached to a
running twin), the **already-ran choice rate** and the top repeated heavy questions with their suggestion status (section 17).

### 8.4 Retention and limits

| Rule | Setting (default) |
|---|---|
| A result is kept for `retention.days` (30) after it was last opened, unless promoted or pinned | `retention.days`, `retention.max-days` (365, the most *Keep longer* allows) |
| Total size of unpromoted results | `store.max-bytes` (20 GB). Over it, the sweeper removes the least recently opened unpromoted results first; their rows become `expired` |
| One result | `store.max-result-rows` (50,000,000) and `store.max-result-bytes` (2 GB). Past either the run fails `DRS-9086` with the fix (aggregate, add a limit). Detected while writing, so the run stops at once |
| Per person | `store.max-bytes-per-user` (2 GB). Over it, that person's oldest results go first; nobody else's |
| Promoted snapshots | outside the budget; listed separately in Admin |
| Sweeper | every `retention.sweep` (1 h) and at start; also removes `tmp/` folders whose run is no longer running |

When an expired run is opened, its card says "This result was removed on 2026-11-19 (kept 30 days). Re-run on the same data or
the newest data", with both buttons.

## 9. Masks and sharing

### 9.1 Who sees a run

| Who | Sees the run row | Sees the result |
|---|---|---|
| the runner (owner) | yes | yes (it was computed as them) |
| a recipient of a share | yes (marked ⇄) | their own version (9.3) |
| a member of the workspace of the run's item | only with `runs.workspace-visibility: members` (default `owner-and-shared`); question and status, never the result | no, unless shared |
| a bi-admin | every row, for operations (Admin → BI → Runs) | no; the admin sees state, size and node, never values |

### 9.2 Computed as the runner

A run is executed with the runner's `QueryService.Caller`: their roles, attributes, row rules, masks and denied tables. Nothing is
computed "as the system". A schedule runs as its owner, checked again at every firing: a person who lost access gets a failed run
with `DRS-9021`, not someone else's rights. A subscription or statement send runs as each recipient, as `SubscriptionService`
and `StatementService` do today. The run's `runner` is the recipient, and its `owner` is the subscription's owner.

### 9.3 Sharing a run

Sharing never hands over the runner's result as such. For each recipient, `RunSharing` decides:

```
 recipient's SecuritySignature on the model ── equal to the runner's? ── yes ─► SAME: the recipient reads the stored result
          │
          no
          ▼
 the row part equal? (SecuritySignature.Alias rules, denied, hidden: the same for every alias the plan reads)
 and the recipient's masked columns ⊇ the runner's?
 and the canonical question validates as the recipient (MqValidator: no DRS-9161 MQ_MASKED, no DRS-9163 MQ_NOT_FOR_ROLE)?
 and every measure is additive, with no limit, top filter or totals (when an extra masked column is a grouping field)?
          │
          ├── yes ─► RE-MASK: the stored result is served with the recipient's extra masked columns replaced by the mask
          │          (the value + masked flag); a masked grouping field also COLLAPSES its groups into one, the
          │          additive measures summed, exactly as the engine answers that recipient; no recomputation
          │
          └── no  ─► RECOMPUTE: a new run in the recipient's name (runner = recipient, shared_from = the original,
                     BACKGROUND class), with the same question, as-of dates and data versions; it is visible to the
                     recipient and notifies them when done; its estimate is the original's actuals
```

* **Re-mask** is sound only when the rows are identical (the same row rules) and the recipient masks *more* columns. It is not purely
  column-wise: the engine does not group by a masked column, so for that recipient the groups of a masked grouping field collapse into
  one. Re-masking therefore masks the value **and** collapses those groups, **summing the additive measures**; that is exact only when every
  measure is additive and the question has no limit, top filter or totals. **Anything else is recomputed** (a non-additive measure, a
  limit, a top filter, totals), as is a measure whose input column is masked for the recipient (refused by `MqValidator`), a SQL statement
  and a report visual (`same` or `recomputed` only). As built: 18.2b, R6.
* **Recompute** is the bursting pattern of `StatementService`: one computation per recipient as that recipient.
* Sharing to a role (`role:risk-team`) expands to its members when the share is made, like `SubscriptionService.expand`. Each member
  gets a share row. Members with equal signatures share one recomputation (the key is the same), so ten readers of one desk cost one
  run, not ten.
* A share row is a pointer. The recipient's inbox row says "anna shared a run with you: VaR by book, 2026-09-30", as every share does.
* Unsharing removes the share row and the recipient's access. A recomputed run the recipient owns stays theirs.

### 9.4 Promoting to a dataset snapshot

A promotion copies `result.parquet` and `answer.json` to `snapshots/<name>/v<N>/` and creates a **draft dataset** in the
promoter's workspace:

```yaml
rachana: 2
dataset: desk-pnl-2026
title: Desk P&L 2026 (snapshot of run r-java-2-01JA…, as of 2026-01-02 to 2026-09-30)
time: {mode: snapshot}
tables:
  - name: result
    source: {run: desk-pnl-2026, version: 3}       # new source type: DatasetDef.Source.RUN, read-only Parquet
    columns: …                                     # from answer.json: names, types, roles, additivity, format
provenance: {run: r-java-2-01JA…, computedAs: ajsinha, signature: 5c1e…, dataVersion: …, question: …}
```

* The rows are what the promoter could see. Columns masked for them are stored as masks and stay masked for every reader, flagged
  `masked: always` in the dataset.
* The snapshot dataset follows the normal lifecycle (draft → review → publish, `BiGovernance`). Its own `rules:` and `masks:` apply
  to its readers on top. A reviewer sees the provenance, including `computedAs`, so publishing data computed with broad rights to
  narrow readers is a visible, reviewed decision, never an accident.
* Promotion needs the `bi-author` power and `runs.promote.enabled` (true).
* A new version (re-promote after a re-run) writes `v<N+1>`. Earlier versions are kept for `runs.promote.versions-keep` (5).

**As built (2.6.7).** `POST /api/v1/bi/runs/{id}/promote {name?, title?, to: dataset|report}` (`RunPromotion`):

* Only the run's **runner** promotes it (it holds their view), with `bi-author` and `promote.enabled`; a share's recipient cannot. Only a
  model query or a SQL statement's table is promoted (a report visual or a statement is refused, `400`). The run object's `can.promote` says so.
* The snapshot is `snapshots/<name>/v<N>/answer.json` under `store.dir`: the stored answer **as the promoter reads it** (`RunService.result`
  with their signature), so a column masked for them holds the mask in every row, never a value. The rows are stored as JSON (as results
  are, 18.2b R5), not `result.parquet`.
* The draft dataset (`rachana: 2`, one table, the dataset's own alias) is created in the promoter's personal workspace through the BI
  items, so its review and publish are the normal ones:

  ```yaml
  rachana: 2
  dataset: desk-pnl-2026
  version: 1.0.0
  title: "Desk P&L (snapshot of run r-java-2-01JA…, as of 2026-01-02 to 2026-09-30)"
  source: { run: desk-pnl-2026, version: 3, token: 5c1e… }
  columns:
    desk: { type: string, path: "trades.desk", role: dimension }
    pnl:  { type: decimal, path: "PnL", role: amount, aggregate: sum }
    trader: { type: string, path: "trades.trader", role: dimension }
  grain: { keys: [desk, trader] }
  security:
    masked: [trader]          # masked for the promoter: masked always
  provenance: { run: r-java-2-01JA…, computedAs: ajsinha, signature: …, dataVersion: …, asOf: …, question: … }
  ```

  Column names are the answer's last part, lower-cased (`trades.desk` is `desk`), with `path:` naming the answer's column; fields are
  dimensions and the grain's keys, additive measures `amount`/`sum`, others `ratio`/`avg`.
* **`Layers` reading `snapshots/` is built as a staged table** (the mechanism of model and SQL-view sources): `DatasetSources` reads the
  snapshot through `ModelCatalog.Snapshots` and stages it like any inner answer; the model's relationships, measures, the dataset's own
  rules and masks then apply on top. The version is part of the staged fingerprint, so a new version is never served from an old cache.
* **Who may read a snapshot:** only the dataset it was promoted to. The snapshot keeps the dataset's name and a random **token**, written in
  that dataset's `source:`; a dataset of another name, or without the token, gets `DRS-9108` ("this dataset may not read it"), so another
  author cannot point a dataset of theirs at someone else's snapshot. The draft is the promoter's only until it is published through
  review, where `provenance.computedAs` is visible. A snapshot name belongs to its first promoter (`409 DRS-9004` for anyone else).
* Promoting again under the same name writes `v<N+1>`, saves a new draft of **the same dataset item** pinned to it, and keeps the newest
  `promote.versions-keep`. The run's `promotedTo` is `name@N`.
* `to: report` also makes a draft report on `model: dataset:<name>` with one table of every column.

Tests: `RunPromotionTest` (masked stored as the mask and declared masked; the YAML parses; only the promoted dataset reads it; versions
and the same item; a report run refused) and `ModelAcceptanceTest` order 18 (end to end: run, promote to a report, the report's table
equals the run's result, another reader cannot open the draft, promoted again is version 2 of the same item).

## 10. Fairness and admission

### 10.1 Three gates

```
 submit ─► RunQueue (per node)                ─► Admission (per node, the engine's gate)   ─► engine
           · per user: queue.per-user (5)          · slots: drishti.bi.query.max-concurrent (8)
             visible runs queued + running         · class caps (new): background-share 0.25 → 2 slots,
             → DRS-9081 at submit                    standing-share 0.25 → 2 slots
           · per node: queue.max (200)             · INTERACTIVE: per user max-concurrent-per-user (3)
             → DRS-9081 at submit                  · per model: per-model-share 0.5 → 4 slots
           · workers: runs.workers (2)             · wait: admission (2 s) for INTERACTIVE → DRS-9025;
             runs started at once                    BACKGROUND waits in RunQueue, never refused for busy
```

* **`RunQueue`** (new) holds visible and background runs, ordered by fair share: the next run started is the oldest one of the
  person with the fewest runs currently running. It hands at most `runs.workers` runs at a time to `Admission` as `BACKGROUND`.
  Interactive questions do not pass through `RunQueue`. They go straight to `Admission` as today, so a burst of heavy runs
  cannot even delay their queueing.
* **`Admission`** gains `classCap(WorkClass)`. `grant()` skips a waiter whose class is at its cap. With 8 slots, background
  work never holds more than 2 and standing maintenance never more than 2, so **at least 4 slots are always free for interactive
  work**. The test of section 19.5 proves it.
* `DatasetRefresher` sets `QueryContext.with(new Ctx(BACKGROUND, …))` around `QueryService.refresh` and enters `Admission` (today
  it does neither). Refresh and runs share the background cap.

### 10.2 The work classes, mapped

| Work | Class | Gate | Time limit | Counts against |
|---|---|---|---|---|
| a visual, MQ or SQL question (interactive or known heavy) | `INTERACTIVE` | `Admission`, 2 s wait | `drishti.bi.exec.interactive` (30 s) | the person's 3, the model's share |
| a dashboard interaction | `INTERACTIVE` | as above; ad-hoc heavy refused at once (DRS-9089) | 30 s | as above |
| a standing query's maintenance (`StandingQueryRegistry`) | `STANDING` | `Admission`, `standing-share` | unchanged | the model's share |
| an ad-hoc heavy run, a recomputation for a recipient, a scheduled run, a subscription or statement send | `BACKGROUND` | `RunQueue` then `Admission`, `background-share` | `run.max-duration` (4 h) | the person's `queue.per-user`, the model's share |
| a dataset refresh (`DatasetRefresher`) | `BACKGROUND` | `Admission`, `background-share` (no `RunQueue`: it is not a person's run) | `drishti.bi.exec.background` (300 s) per date | the model's share |

### 10.3 Resources beyond slots

| Resource | Control |
|---|---|
| DuckDB memory and threads | ad-hoc heavy runs execute on the **run engine**, a second `DuckDbEngine` instance with `engine.memory-limit` (2 GB) and `engine.threads` (2). Its spill folder is `<data>/tmp/server/duckdb-runs`. The interactive instance keeps `drishti.bi.query.memory-limit` and `threads` for itself. A spilling run slows only runs |
| connector fragments | runs' layer builds take at most `fragments-share` (0.5) of each connector's `max-fragments` (`FragmentPools`), so interactive builds of a single date always find a slot |
| JVM heap | results stream engine → Parquet (8.1). Layer builds already stream batches (`scan-batch-rows`). A run's Java-side state is its record and progress counters |
| disk | the result store's budgets (8.4); DuckDB's `max_temp_directory_size` on the run engine (`engine.max-spill`, 20 GB) |
| CPU | the run engine's `threads`; `runs.workers` caps concurrent runs |

The interactive engine is untouched by runs: its memory, threads, slots and connector share are reserved. That is how "heavy runs never
slow interactive users" is kept as a property rather than a hope. The CPU is the one shared resource left, and it is bounded by
`engine.threads` × `runs.workers`.

## 11. Cancellation

Cancelling a run frees everything it holds at once:

| Phase | How it is stopped | Freed |
|---|---|---|
| `awaiting-confirm`, `queued` in `RunQueue` | removed from the queue | the queue place |
| waiting in `Admission` | its waiter removed under the lock (`changed.signalAll()`); new `Admission.Waiter.cancel()` | the place in line |
| building layers (`TableBuilder` via `FragmentPools`) | the run's `CancelToken` interrupts its virtual threads; connector reads check the token between batches; a waiting fragment leaves `FragmentPools` (interruptible acquire, never `acquireUninterruptibly`); the half-written partition's temp file is deleted (`TableBuilder` writes under a temporary name already) | fragment slots, the connector's semaphore, memory of the batch |
| scanning (DuckDB) | `Statement.cancel()` on the run's connection, the call the `DuckDbEngine` watchdog already makes at the time limit | DuckDB threads, memory, spill files |
| storing | the `tmp/<run>` folder deleted | disk |
| `split` / `external` | `Execution.cancel()`: each slice owner's fragment cancelled with the run's id; an external engine's query cancelled with its own API | remote resources |

* The admission ticket is closed in a `finally` (it is `AutoCloseable` today), so a cancelled run's slot goes to the next waiter in
  the same instant.
* A cancel answers `202` with the run in `cancelled` as soon as the token is set. The run row says `cancelled` once every phase above
  has let go, which takes milliseconds for a scan and at most one batch for a build. Admin shows any run whose resources took longer
  than `cancel.grace` (5 s) to be released.
* **A gone caller cancels invisible work.** When an invisible run's HTTP request is closed (the browser navigated, the console's
  proxy timed out), the run is cancelled. A newer question with the same `supersedes` key cancels the older one. A visible run
  is not cancelled by its caller leaving: the person was told it would continue.
* Code `DRS-9083` is the answer when a cancelled run's result is asked for. The reason names who cancelled it ("cancelled by you",
  "cancelled by an administrator (ops1)", "replaced by a newer selection", "the page was closed").

## 12. Restart and failure

### 12.1 At start

Before the server accepts requests, `RunRecovery` reads this node's rows in `queued`, `running` or `storing`:

| Row state at start | Rule | Result |
|---|---|---|
| `queued` (or `awaiting-confirm`) | the question still plans: the model and items exist, the runner still has access, the model's fingerprint is unchanged or `restart.requeue-changed-model` | back in `RunQueue` in its old order; reason "requeued after the server restarted at 09:41" |
| `queued`, does not plan | | `failed`, `DRS-9085`, reason naming what changed ("the model trading was changed while this run waited") |
| `running` / `storing`, `attempt < restart.max-attempts` (2) and `restart.resume-running` (true) | runs are reads, so starting again from the beginning is always safe; any layer partitions already built are kept (they are the BI store's, validated as always) | `queued`, `attempt + 1`; reason "started again after the server restarted (attempt 2 of 2)"; the estimate is reduced by the partitions already built |
| `running`, attempts exhausted | | `failed`, `DRS-9085`: "the server stopped while this ran, twice; it may be too large for this server: narrow it or ask an administrator" |
| a `tmp/` folder without a running row | | deleted |

Every row it changes produces the usual event and, for a visible run, an inbox row: "Your run *MTM by desk and month* was
started again after a server restart", or the failure. Nothing is dropped without a word.

### 12.2 Failures while running

| Failure | Code | Run detail says |
|---|---|---|
| engine time limit (`run.max-duration`) | `DRS-9024` | the limit, and the estimate it was given |
| DuckDB out of memory on the run engine (spill budget exhausted) | `DRS-9028` | the run engine's budget (`engine.memory-limit`, `engine.max-spill`) and the fix |
| result past `store.max-result-rows` or `max-result-bytes` | `DRS-9086` | the limit and "aggregate it, or add a limit" |
| source failed or breaker open | `DRS-9026` | the connector, from `FragmentPools`' breaker |
| store disk full, nothing evictable | `DRS-9088` | the folder and its budget |
| the runner lost access mid-run (role removed) | `DRS-9021` | checked again before `done`; the result is deleted |

A failed run keeps its partial progress information (dates built) so a re-run's estimate is honest.

## 13. The cluster

### 13.1 Where a run executes

```
 console ─► any server X ─► RunService.submit
                              │
                              ├─ interactive / known heavy: X answers it (asked queries are stateless, BI_DESIGN 6.5)
                              │
                              └─ ad-hoc heavy: owner = ShardDirectory.owner(model key) over Drishti*net's alive members
                                     X = owner ─► X runs it
                                     X ≠ owner ─► forward the submit to the owner (Drishti*net view forwarding:
                                                  the same endpoint, X-Drishti-Net-Hop: 1, the on-behalf-of token);
                                                  the owner creates the run (id r-<owner>-<ulid>) and answers 202
```

* **The model's owner** runs its heavy runs because it already holds that model's warm layers and live tables (BI_DESIGN 6.5).
  Ownership is rendezvous hashing, so every node agrees on the owner without talking, and a node leaving moves only its own models.
  2.6.7 wires `ShardDirectory` to Drishti*net's member table (alive, not suspect) for this, the wiring production lacks today.
* **With S6 built** (`engine: split`), the owner is the coordinator. It sends each slice owner its fragment (SUPER_SCALE 10.3 and
  11.3: partial sums, counts and sketches) and merges them. Each slice owner admits its fragment as `BACKGROUND` under its own caps,
  so a cluster-wide burst is bounded on every node.
* **One node** (a laptop) owns every model. The same code runs every path with `owner = self`.

### 13.2 Status from any node

| Identity database | How any node shows a run |
|---|---|
| shared (PostgreSQL, DRISHTI_NET section 19 *Identity across nodes*) | every node reads `drishti_bi_run` directly; list, get and events need no forwarding. Events are relayed: each node subscribes to the owner's `/runs/events` for the runs its consoles watch, one upstream stream per node, as live entity streams are relayed today |
| per node (SQLite) | `get`, `result`, `cancel` and actions on `r-<node>-…` are forwarded to `<node>` (the id names it); the person's list is scattered to the alive nodes and merged by `submitted_at` (as a search is). A node that does not answer is listed in `nodes.failed`, and its runs show "node java-3 is not reachable: its runs' state is unknown until it returns" |

Results are read where they are. On a shared folder (EFS) a node reads `result.parquet` itself; otherwise `GET /runs/{id}/result` is
forwarded to the run's node and streamed back.

### 13.3 A node dying mid-run

* The running node renews `lease_until` every `lease.renew` (10 s), for `lease.ttl` (45 s).
* **Shared database:** when Drishti*net marks the node dead (`dead-after`, 10 s) *and* its lease has lapsed, the model's new
  owner adopts the run. A conditional update (`UPDATE … SET node = ?, attempt = attempt + 1, state = 'queued' WHERE id = ? AND
  node = ? AND lease_until < now`) means exactly one node wins. The run starts again under the rules of 12.1 (attempts, access,
  model), and the runner is told "started again on java-1 after java-3 stopped". Only one node can own a run at a time: a node that
  comes back with a lapsed lease finds the row is no longer its own and abandons its copy (its `tmp/` is swept).
* **Per-node database:** nobody else can see the row. The run's state is reported as unknown (13.2) until the node returns, and
  12.1 then applies. The person can re-run it at once from another node ("Re-run elsewhere" on an unreachable run: a new run on
  the current owner, with `rerun_of` set).
* A forwarded submit whose owner dies before answering is retried once on the new owner within the caller's wait. Runs are reads, so
  a duplicate is harmless and is removed by the key (the second finds the first's result or its running row with the same key and
  joins it).

**Joining identical work.** Two submits with the same result key while the first is still running do not run twice. The second joins
the first: a new row for its own runner, in state `running`, which follows the first and turns `reused` when it finishes. This is
single-flight, the rule `Layers` already applies to partition builds.

### 13.4 As built (2.6.7)

* **Names.** In a cluster a run is named after the Drishti*net member (`r-<node id>-<ulid>`, `NetService.plannedNodeId`, known before
  the node joins), so its id names its node. Outside a cluster, as before (`drishti.bi.exec.node`, else `local`).
* **Owner** (`RunCluster`): `ShardDirectory` over every member known, a member that is not `alive` owning nothing; both nodes agree
  without talking. A submit (`POST /runs`) that is ad-hoc heavy, or asks `wait: 0`, for a model another node owns is sent whole to the owner
  for the caller (signed, `X-Drishti-Net-Hop: 1`, an on-behalf-of token: computed there with the caller's roles, row rules and masks);
  its answer comes back as it is (`202 {run}` with the owner's id). An owner that cannot be reached, or answers `502`/`503`, leaves the run
  here (runs are reads: running it here is correct). Interactive and known heavy questions are answered where asked.
* **A run's own calls** (`/runs/{id}/result`, `cancel`, `export`, `share`, `promote`, every action) go to the node that holds the run
  (`RunForwardFilter`): the row's `node` when the row is readable here (a shared identity database), else the node the id names. `GET
  /runs/{id}` is answered here when the row is readable here. A node that does not answer gives `502 DRS-5052` ("its state is unknown
  until it returns; re-run it here"). A forwarded request is answered from its node only.
* **The list** (`GET /runs`) is scattered to the other members alive and merged by `submittedAt` (de-duplicated by id, so a shared
  database lists each run once); a node that does not answer is in `nodes.failed` with the reason. **Events:** `/runs/events` relays the
  `run` events of the caller's runs on every other member alive when the stream opens.
* **Leases and adoption** (13.3): a run becoming visible takes `lease_until = now + lease.ttl`; every `lease.renew` the node renews its
  runs (`RunRecords.renew`, a conditional update on `node`); a run whose row another node holds now is abandoned (its work interrupted,
  nothing saved). With a shared database, each node adopts the runs of members that are not alive whose lease lapsed and whose model it
  owns now, by one conditional update (`RunRecords.adopt`: `node`, `attempt + 1`, `queued`, `WHERE node = <dead> AND lease_until < now`),
  then starts them again under 12.1 (attempts, access as the runner now), the reason "started again on <node> after <dead> stopped".
  With a per-node database nobody else sees the row: its state is unknown (above) until the node returns.
* Not built: the event relay does not pick up a member that joins after the stream opened (the console reopens the stream); with S6 the
  owner as coordinator (13.1, `engine: split`).
* `RunClusterTest` (two servers in one JVM over one identity database, ports from `-Ddrishti.net.it.ports`): a heavy run submitted on the
  node that does not own the model runs on the owner and its status, result and list answer from either node; a masked reader's run keeps
  the mask across the forward; the owner stopped with an unfinished run of its: the other node adopts it once (attempt 2) and finishes it.

## 14. API and CLI

### 14.1 Endpoints

All are under `/api/v1/bi/runs`, as the token's user, with the `bi:write` scope for changes. Every answer has
`Cache-Control: no-store`.

| Method | Path | Body / query | Answer |
|---|---|---|---|
| `POST` | `/api/v1/bi/runs` | `{source, options}` (14.2) | `200` `{run, answer}` done within the wait; `202` `{run}` (with `Location`); `409` `DRS-9082` `{estimate, confirmToken}` |
| `POST` | `/api/v1/bi/runs/estimate` | `{source}` | `200` `{tier, engine, estimate, reuse: {key, runId} | null, confirm: bool}`; runs nothing |
| `GET` | `/api/v1/bi/runs` | `?mine=true&shared=true&workspace=&state=&source=&q=&since=&limit=50&after=` | `200` `{runs: [run], next, storage: {bytes, results}, nodes: {failed: []}}` |
| `GET` | `/api/v1/bi/runs/{id}` | | `200` `run` (14.3) |
| `GET` | `/api/v1/bi/runs/{id}/result` | `?format=json\|arrow&offset=0&limit=5000` | `200` the answer shape of BI_MODEL_SPEC 5.2 (JSON) or an Arrow stream with `X-Bi-Rows`, `X-Bi-Masked`, `X-Bi-Run`; `409` `DRS-9083` not done; `410` `DRS-9084` expired |
| `GET` | `/api/v1/bi/runs/events` | SSE | `hello` (the person's active runs), then `run` events `{id, state, progress, phase, rowsOut, code}`; `status` heartbeat every `drishti.bi.live.status` |
| `POST` | `/api/v1/bi/runs/{id}/confirm` | `{confirmToken}` | `202` `run` (queued) |
| `POST` | `/api/v1/bi/runs/{id}/cancel` | | `202` `run` |
| `POST` | `/api/v1/bi/runs/{id}/rerun` | `{data: "same"\|"latest", wait?}` | as `POST /runs` |
| `POST` | `/api/v1/bi/runs/{id}/export` | `{format: "csv"\|"xlsx"\|"parquet"\|"pdf"}` | `200` the file (`Content-Disposition`) |
| `POST` | `/api/v1/bi/runs/{id}/share` | `{to: ["bob", "role:risk-team"]}` | `200` `{shares: [{recipient, mode: "same"\|"re-masked"\|"recomputed", recipientRun}]}` |
| `DELETE` | `/api/v1/bi/runs/{id}/share/{recipient}` | | `204` |
| `POST` | `/api/v1/bi/runs/{id}/promote` | `{name?, title?, to: "dataset"\|"report"}` (as built, 9.4) | `201` `{snapshot, version, dataset: {name, item}, report: {item}\|null, yaml}` |
| `POST` | `/api/v1/bi/runs/{id}/keep` | `{until}` or `{days}` | `200` `run` |
| `DELETE` | `/api/v1/bi/runs/{id}/result` | | `204` (the row stays, `expired`) |
| `POST` | `/api/v1/bi/runs/{id}/schedule` | `{schedule: "business-days 07:30", data: "latest"}` | `201` `schedule` |
| `GET` | `/api/v1/bi/runs/schedules` | | `200` `{schedules: [schedule]}` |
| `PUT` / `DELETE` | `/api/v1/bi/runs/schedules/{id}` | `{schedule?, enabled?}` | `200` / `204` |
| `GET` | `/api/v1/bi/runs/settings` | | `200` `{inlineWait: "PT2S", maxWait: "PT10S", confirmAboveSeconds: 60, retentionDays: 30, eventsUrl}` (what the console needs) |
| `GET` | `/api/v1/bi/admin/runs` | bi-admin: `?node=&state=&user=` | `200` `{queue: {byClass, byUser}, running: [run], storage: {...}, accuracy: {...}, nodes: [...]}` |
| `POST` | `/api/v1/bi/admin/runs/{id}/cancel` | bi-admin | `202` `run` |

The existing endpoints (`POST /api/v1/bi/reports/{id}/query`, `POST /api/v1/bi/reports/query`, `POST /api/v1/bi/mq`,
`POST /api/v1/bi/models/{id}/query`, `POST /api/v1/bi/sql`, the statement endpoints) accept `Prefer: respond-async[, wait=<seconds>]`
and the body fields `interaction` (bool) and `supersedes` (string). With `Prefer` they answer as `POST /runs` does. Without it they
answer as today.

### 14.2 Submit body

```json
{
  "source": {
    "type": "report",                       // report | board | mq | sql | statement
    "id": "rpt:desk-pnl", "page": "overview", "visual": "mtm-by-desk-month",
    "state": { "slicers": {"DESK": ["RATES-NY", "RATES-LDN"]}, "params": {"currency": "USD"}, "drill": [] }
    // or  "type": "mq",  "mq": { "mq": 1, "model": "trading", "select": {...}, "time": {"window": {"from": "2026-01-02", "to": "2026-09-30"}} }
    // or  "type": "sql", "sql": "SELECT desk, sum(mtm) FROM trading.trades GROUP BY desk", "model": "trading"
  },
  "options": {
    "wait": "PT2S",                         // the inline wait; 0 for fire-and-forget; capped by inline.max-wait
    "confirm": false,                       // true: accept the confirmation in advance (the CLI's --yes)
    "confirmToken": null,
    "interaction": false,
    "supersedes": null,                     // "<report>/<page>/<visual>": cancels an older question with the same key
    "title": null,                          // shown in Runs; default: the visual's title, else the question in words
    "data": "latest",                       // latest | "known-at:<instant>" (a re-run on the same data)
    "format": "json"                        // json | arrow, for an answer returned inline
  }
}
```

### 14.3 The run object (the contract between the two builders)

```json
{
  "id": "r-java-2-01JAC4Q8Z6T3M4R9B0X1Y2Z3W4",
  "title": "MTM by desk and month",
  "source": {"type": "report", "id": "rpt:desk-pnl", "title": "Desk P&L", "page": "overview", "visual": "mtm-by-desk-month",
             "href": "/bi/r/rpt:desk-pnl/overview?run=r-java-2-01JAC4Q8Z6T3M4R9B0X1Y2Z3W4"},
  "runner": "ajsinha", "owner": "ajsinha", "workspace": "me",
  "params": {"slicers": {"DESK": ["RATES-NY", "RATES-LDN"]}, "params": {"currency": "USD"}, "filters": [{"field": "PRODUCT_TYPE", "op": "!=", "values": ["FX_SPOT"]}]},
  "asOf": {"from": "2026-01-02", "to": "2026-09-30", "dates": 182},
  "data": {"version": "a91c…", "words": "trade_endur delta v412 · desk_dim layers 2026-10-20 07:31",
           "tables": [{"table": "trade_endur", "connector": "trade-endur", "versions": "v412 (182 dates)"}]},
  "tier": "ad-hoc-heavy", "engine": "local", "class": "background",
  "state": "running", "progress": 0.29, "phase": "building 12 of 41 dates",
  "estimate": {"readRows": 1840000000, "buildRows": 410000000, "bytes": 62000000000, "rowsOut": 4320,
               "seconds": {"low": 150, "high": 330}, "basis": "delta-stats", "dates": 182, "datesToBuild": 41, "covered": false,
               "why": "182 business dates of trade_endur …; no rollup has grain desk × month"},
  "actual": {"rowsRead": null, "rowsOut": null, "bytesOut": null, "seconds": 112},
  "code": null, "reason": null,
  "node": "java-2", "attempt": 1,
  "submittedAt": "2026-10-20T09:14:03Z", "startedAt": "2026-10-20T09:14:04Z", "finishedAt": null, "expiresAt": null,
  "reuse": null,                            // {"of": "<run id>", "mode": "same"|"re-masked"} when reused
  "shared": {"from": null, "with": [{"recipient": "bob", "mode": "re-masked", "recipientRun": null}]},
  "group": null, "rerunOf": null, "promotedTo": null,
  "can": {"open": false, "cancel": true, "rerun": true, "export": false, "share": false, "promote": false, "schedule": true, "keep": false, "delete": false},
  "links": {"self": "/api/v1/bi/runs/r-java-2-…", "result": "/api/v1/bi/runs/r-java-2-…/result", "events": "/api/v1/bi/runs/events"}
}
```

`can` is computed by the server for the caller, so the console never decides rights. As built (R1), `can` also carries `confirm`
(true for the runner of an `awaiting-confirm` run), an added field. Fields are never removed. A new field may be
added, and the console ignores fields it does not know.

### 14.5 The console contract as built (server lane, 2.6.7)

The console lane (`bi-runs.js`, `bi-lib.js`, `runs_admin.html`) was built against 14.1 to 14.3. These points settle what the server answers:

1. **`409 DRS-9082`** carries `estimate`, `confirmToken`, `runId`, `run` (the run object, state `awaiting-confirm`, its `confirmToken` set) and
   `confirm` (the path). Either confirm it with `POST /api/v1/bi/runs/{runId}/confirm {confirmToken}`, or ask the question again with
   `confirm: true` and the `confirmToken` (in the body of `/bi/mq`, `/models/{id}/query`, `/bi/sql`, `/reports/{id}/query`, or in `options` of
   `POST /runs`): the waiting run is confirmed, no second run starts.
2. **`200` with `Prefer`** on the existing endpoints is **today's body**, with the run's id in the `Drishti-Run` header (and `X-Bi-Run`, kept).
   `POST /runs` answers `{run, answer}`.
3. **The run object** has `can.discard` (with `can.confirm`, the owner of an `awaiting-confirm` run) and `confirmToken`, set only for that owner
   while it awaits confirmation, else null.
4. **The list** takes `since=7d|30d|12h` or an ISO date or instant (anything else is `400`), and `state=` comma-separated (`state=done,reused`).
5. **`GET /api/v1/bi/admin/runs`** answers `now.slots.{interactive,standing,background}.{used,cap}`, `now.admissionWaitP95Ms`,
   `queue.{byClass,byUser,oldestSeconds,oldestUser,queued,workers}`, `running`, `storage.{resultsBytes,maxBytes,results,oldest,snapshotsBytes,
   snapshots,topUsers[{user,bytes}]}`, `accuracy.{medianRatio,p10,p90,samples,basis,unknownShare}`, `inline.{inlineShare,jobMedianSeconds}`,
   `settings.{file,inlineWait,confirmAboveSeconds,retentionDays}`, `nodes[{node,alive}]` and `suggestions`. `admissionWaitP95Ms` is the
   interactive admission wait over the last 5 minutes (null when nothing was admitted); `accuracy.basis` is `{<basis>: {medianRatio, samples}}`;
   `snapshots*` count `snapshots/` (empty until promotion is built); `suggestions` is 8.3a's list
   `[{id, model, grain, filters, runs, medianSeconds, status, rollup, yaml, words, decidedBy, item}]`, accepted or dismissed with
   `POST /api/v1/bi/admin/runs/suggestions/{id}/accept|dismiss`.
6. **The run object** has `schedule` (the owner's schedule of the run, else null) and `source.model` (the model of an mq or sql run, else null).
7. **The events stream**'s first event is `hello` with `{runs: [...]}` (the caller's unfinished runs).
8. **A re-ask after `DRS-9089`** with `interaction: false, confirm: true` is accepted: it is planned as a normal question, confirmed in advance.
9. **`/reports/{id}/query`** with `Prefer` takes `interaction` and `supersedes` beside `page`, `visual` and `state`; without `Prefer` it is
   unchanged (both checked in `ModelAcceptanceTest`). Two faults were found and fixed: a slicer's second re-ask on the same visual failed
   `DRS-9080` because the first, finished and invisible, was "cancelled" (the parameter-slicer timeout), and an MQ with `options.explain` went
   through the pipeline and lost its SQL (the explain panel); it now answers at once.

### 14.4 CLI

A new module, `tools/biruns.py`, is registered under `drishti.py bi` like `biitem.py`. It uses the same `Api`, `CliError` and
`say_json`.

```
drishti.py bi runs list [--state running|done|failed|…] [--source report|mq|sql] [--since 7d] [--all-nodes] [--json]
drishti.py bi runs get RUN [--json]
drishti.py bi runs submit (--mq FILE | --sql TEXT --model M | --report ID --page P --visual V [--state FILE])
                          [--wait 2s] [--yes] [--title T]            # prints the run id, or the answer when done in time
drishti.py bi runs estimate (same source options)                    # tier, engine, estimate; runs nothing
drishti.py bi runs wait RUN [--timeout 30m]                          # follows /runs/events; exit 0 done, 3 failed, 4 cancelled
drishti.py bi runs result RUN [--format csv|json|arrow|parquet] [--out FILE]
drishti.py bi runs cancel RUN
drishti.py bi runs rerun RUN [--data same|latest] [--wait 2s]
drishti.py bi runs export RUN --format xlsx --out FILE
drishti.py bi runs share RUN --to bob --to role:risk-team
drishti.py bi runs promote RUN --as snapshot|report --name NAME [--workspace W]
drishti.py bi runs keep RUN --days 90
drishti.py bi runs schedule RUN "business-days 07:30" [--data latest]
drishti.py bi runs schedules [list|disable ID|enable ID|delete ID]
drishti.py bi runs storage                                            # bi-admin: Admin's storage numbers
```

`drishti.py bi mq FILE` and `drishti.py bi sql` gain `--async [--wait 2s] [--yes]`, which sends `Prefer: respond-async` (and with `--yes` `confirm: true`) and prints the run id
when the answer is `202`.

## 15. Settings: `config/runs/runs.yaml`

There are more than a handful of settings, so they get their own file, following `config/connectors/`, `config/net/` and
`config/db/database.yaml` (DATABASE.md section 7). `application.yaml` keeps only the pointer:

```yaml
drishti:
  bi:
    runs:
      file: ${DRISHTI_BI_RUNS_FILE:./config/runs/runs.yaml}   # BI runs (docs/architecture/BI_RUNS.md); ready-made in config/runs.examples/
```

The two class caps belong to the engine's gate and sit beside its other settings in `application.yaml` (`drishti.bi.exec.*`):

```yaml
    exec:
      background-share: 0.25     # the most of the slots background work (runs, refresh) holds at once
      standing-share: 0.25       # the most standing-query maintenance holds at once
      fragments-share: 0.5       # the most of a connector's max-fragments runs' layer builds hold
```

The shipped `config/runs/runs.yaml` (every key has its default in `RunsProperties`, so a missing file means the defaults):

```yaml
enabled: true                      # false: every question runs as before 2.6.7 (synchronous), no Runs tab
inline:
  wait: 2s                         # how long a request waits before its run becomes a visible job
  max-wait: 10s                    # the most a caller may ask for (Prefer: wait=)
interactive:                       # the bounds of the interactive tier
  max-seconds: 10                  # the estimate's high end
  max-rows: 50000000               # rows read
  max-build-dates: 2               # partitions it may build from the source
confirm:
  above-seconds: 60                # ad-hoc heavy runs above this ask first
  above-rows: 500000000
  unknown: true                    # an estimate with basis unknown asks first
  ttl: 15m                         # an unconfirmed run is discarded after this
queue:
  per-user: 5                      # a person's visible runs queued + running (DRS-9081)
  max: 200                         # all queued runs on this node (DRS-9081)
workers: 2                         # runs executing at once on this node
run:
  max-duration: 4h                 # a run's time limit (DRS-9024)
engine:                            # the run engine: a second DuckDB instance for ad-hoc heavy runs
  memory-limit: 2GB
  threads: 2
  max-spill: 20GB
  max-read-rows: 5000000000        # past it, and no split or external engine: DRS-9086
  prefer-external-above-seconds: 0 # 0: never prefer an external engine while local fits
engines: {}                        # external engines (3.0 groundwork): athena: {…}, trino: {…}, spark: {…}
estimate:
  low-factor: 0.5                  # the range before 20 runs of history narrow it
  high-factor: 2.0
  rates:                           # seeds, replaced by measured rates
    scan-rows-per-second: 50000000      # DuckDB over Parquet layers
    build-rows-per-second: 300000       # a connector's column scan into a layer
    record-rows-per-second: 2000        # the record-by-record path
store:
  dir: ${drishti.data.dir:./data}/bi-runs
  max-bytes: 20GB                  # unpromoted results on this node
  max-bytes-per-user: 2GB
  max-result-rows: 50000000        # DRS-9086
  max-result-bytes: 2GB            # DRS-9086
  min-seconds: 1                   # an interactive answer faster than this is not stored
  keep-interactive: true
retention:
  days: 30                         # after the last open, unless promoted or kept
  max-days: 365                    # the most "Keep longer" allows
  sweep: 1h
restart:
  resume-running: true             # a run interrupted by a restart starts again
  max-attempts: 2                  # then it fails with DRS-9085
  requeue-changed-model: false     # a queued run whose model changed fails instead of running on the new model
lease:
  renew: 10s
  ttl: 45s
cancel:
  grace: 5s                        # Admin flags a cancelled run whose resources are not released by then
workspace-visibility: owner-and-shared   # owner-and-shared | members (members see others' run rows, never results)
promote:
  enabled: true
  versions-keep: 5
notify:
  on: [done, failed]               # also: cancelled-by-others (always on), started-again (always on)
```

The console needs no new setting. It reads `GET /api/v1/bi/runs/settings`.

## 16. Error codes

New codes in the BI engine's range `DRS-90xx`, in the free block `DRS-9080` to `DRS-9089`. `ErrorCode.java` adds them and API_GUIDE's
and TROUBLESHOOTING's tables follow (the docs tests require both).

| Code | Name | HTTP | Meaning | Fix shown |
|---|---|---|---|---|
| `DRS-9080` | `RUN_NOT_FOUND` | 404 | no such run, or not one the caller may see | check the id; a shared run needs its share |
| `DRS-9081` | `RUN_QUEUE_FULL` | 429 | the caller has `queue.per-user` runs queued or running, or the node's queue is at `queue.max` | wait for one to finish or cancel one; `Retry-After` |
| `DRS-9082` | `RUN_CONFIRM` | 409 | ad-hoc heavy above the confirm threshold; the body carries the estimate and a `confirmToken` | confirm, narrow the dates, or add a rollup |
| `DRS-9083` | `RUN_NOT_DONE` | 409 | the result of a run that is queued, running, failed or cancelled | wait, or read its reason |
| `DRS-9084` | `RUN_EXPIRED` | 410 | the result was removed by retention or the storage budget | re-run on the same or the newest data |
| `DRS-9085` | `RUN_INTERRUPTED` | 503 | the server or node stopped while the run waited or ran, and it could not start again (attempts, access, a changed model) | re-run; if it repeats, narrow it |
| `DRS-9086` | `RUN_TOO_LARGE` | 413 | the estimate exceeds every available engine's limits, or the result exceeds `store.max-result-rows`/`max-result-bytes` | aggregate, narrow, add a rollup, or configure an engine |
| `DRS-9087` | `RUN_NODE_UNREACHABLE` | 503 | the node that owns the run did not answer | try later, or re-run elsewhere |
| `DRS-9088` | `RUN_STORE_FULL` | 507 | the result store is at its budget and nothing can be removed | an administrator frees space or raises `store.max-bytes` |
| `DRS-9089` | `RUN_NEEDED` | 409 | a dashboard interaction would need a background run; nothing ran; the body carries the estimate | *Run in background*, or ask for a rollup at this grain |

Existing codes keep their meanings: `DRS-9025` (an interactive question found no slot in time), `DRS-9029` (a synchronous caller asked
an ad-hoc heavy question; now with a `run` hint), `DRS-9024` (a time limit), `DRS-9028` (memory), `DRS-9021` (masked or no access).

## 17. Metrics and Admin

### 17.1 Metrics (Micrometer, beside `BiMetrics`)

| Metric | Tags | What |
|---|---|---|
| `drishti.bi.runs.submitted` (counter) | `tier`, `source` | questions submitted |
| `drishti.bi.runs.finished` (counter) | `tier`, `outcome` (done, reused, failed, cancelled), `code` | outcomes |
| `drishti.bi.runs.inline` (counter) | `result` (inline, job) | how often the inline wait was enough |
| `drishti.bi.runs.seconds` (timer) | `tier`, `engine` | run time |
| `drishti.bi.runs.queue` (gauge) | `class` | queued runs |
| `drishti.bi.runs.running` (gauge) | `engine` | running runs |
| `drishti.bi.runs.estimate.ratio` (distribution) | `basis`, `engine` | actual ÷ estimated seconds |
| `drishti.bi.runs.reuse` (counter) | `mode` (same, re-masked, recomputed, joined) | reuse |
| `drishti.bi.runs.store.bytes` (gauge) | `kind` (results, snapshots) | storage |
| `drishti.bi.runs.cancel.release` (timer) | | time from cancel to every resource freed |
| `drishti.bi.admission.wait` (existing) | add `class` | the interactive wait under background load: the fairness signal |

### 17.2 Admin → BI → Runs (`/admin/bi/runs`, bi-admin)

```
 Admin · BI · Runs                                                               node [all ▾]   ⟳ live
 ────────────────────────────────────────────────────────────────────────────────────────────────────
  Now        interactive 3/8 slots · standing 1/2 · background 2/2 (cap) · queue 7 (4 people)
             interactive admission wait p95 (5 min) 12 ms                     ✓ background not starving it
  Running    r-java-2-01JA…  ajsinha  MTM by desk and month     java-2  building 12/41  1 m 52 s   [Cancel]
             r-java-1-01JA…  bob      Raw trades Q3             java-1  scanning 61 %   3 m 10 s   [Cancel]
  Queued     5 runs · oldest 2 m (carol) · per person: carol 3, dave 2
  Storage    results 11.2 GB of 20 GB (1,904 results, oldest 2026-09-21) · snapshots 3.4 GB (12)
             top users: ajsinha 1.9 GB · bob 1.7 GB · carol 1.2 GB                          [Sweep now]
  Estimates  last 7 days: median actual/estimate 1.08, 90 % within 0.6–1.9 (delta-stats); 14 % basis unknown
  Inline     81 % of questions answered inline; 19 % became jobs (median 3 m 40 s)
  Settings   config/runs/runs.yaml · inline 2 s · confirm above 60 s · retention 30 days     (read-only here)
```

The existing `GET /api/v1/bi/engine` (`QueryService.stats`) also reports `admission.byClass` and the run engine's memory.

## 18. Build steps (two builders)

The server lane is builder A (pipeline, planner, store). The console lane is builder B (Runs tab, inline wait, CLI). Builder A
publishes the contract (14.3 and the endpoint table, as stubs answering fixed shapes) on day one, so builder B builds against it.
Targeted tests only between steps, on the 10K-trade lake at most, one heavy process at a time. One drill at the end of the release.

### 18.1 Server lane (A)

| Step | Work | Done when |
|---|---|---|
| **R1** contract | `com.ash.drishti.server.bi.runs`: `Run`, `RunState` (the transition table), `RunJson` (14.3), `RunsProperties` + `config/runs/runs.yaml` + `config/runs.examples/`; `ErrorCode` `DRS-9080`–`9089`; `RunsController` with every endpoint of 14.1 answering stub shapes; the `drishti_bi_run`, `drishti_bi_run_share` and `drishti_bi_run_schedule` tables in both identity schema files (parity test) | the console builds against the stubs; `RunStateTest`; schema parity |
| **R2** fairness | `Admission` class caps (`background-share`, `standing-share`), `Waiter.cancel()`; `DatasetRefresher` → `BACKGROUND` through `Admission`; `FragmentPools` `fragments-share` and interruptible acquire; the run engine (second `DuckDbEngine`) | `AdmissionClassCapTest`: 8 background waiters never take more than 2 slots and an interactive waiter is admitted at once |
| **R3** planner | `RunPlanner`, `RunRates`, `RunHistory`; `Layers.peek`; `SourcePlugin.rowEstimate` (default empty) with Delta (`DeltaTable.Layout` add-action stats) and Iceberg (`ManifestReader` `record_count`); `QueryService.estimate` moved before every `Layers.columns` build | `RunPlannerTest` (tiers, confirm, refuse); an estimate reads no data (counted connector reads = 0) |
| **R4** pipeline | `RunService` (submit, futures, inline wait, visibility, `supersedes`, gone-caller cancel, single-flight join), `RunQueue`, `CancelToken` through `QueryService`, `TableBuilder`, `DuckDbEngine`; `Prefer: respond-async` on `BiReportController`, `MqController`, `SqlController`, `StatementController`; `interaction` → `DRS-9089` | `RunServiceTest`; the existing BI tests pass unchanged (synchronous behaviour kept) |
| **R5** store | `RunStore` (key, `COPY TO` Parquet, `answer.json`, `FileSwap`, paging to JSON and Arrow), reuse, `RunSweeper` (retention, budgets, `tmp/`) | `RunStoreTest`; a stored result reads back equal to the inline answer |
| **R6** sharing and promotion | `RunSharing` (same, re-mask, recompute; `SecuritySignature` row part and mask part exposed), role expansion; promote: `DatasetDef.Source.RUN`, its parser and `Layers` reading `snapshots/`; promote to report; `RunSchedules` on `SubscriptionSchedule`; export through `BiExporter` | `RunSharingTest` (section 19.3) |
| **R7** restart and cluster | `RunRecovery`; leases; `ShardDirectory` wired to Drishti*net members; forwarding of submit, get, result, cancel; scatter of list; event relay; adoption by conditional update | `RunRecoveryTest`, `RunClusterTest` (two nodes in one JVM, as `TwoNodesTest`) |
| **R8** notify and metrics | `RunNotifier` through `BiNotifier.tell`; metrics of 17.1; `GET /api/v1/bi/admin/runs`; `/engine` additions | notices carry no values (test) |

### 18.2 Console lane (B)

| Step | Work | Done when |
|---|---|---|
| **R1c** Runs tab | `workspace.html` tab strip (Items · Runs · Members, `?tab=`); `bi-runs.js`: the list, filters, live rows from `/runs/events`, the run drawer and every action, as the run's `can` allows; the rail link and count; `BI RUNS <GO>`; `routes/bi_routes.py` `_ALLOWED` gains `runs` | against the stubs; `test_bi_runs_page.py` |
| **R2c** inline wait | `bi-lib.js` `B.api` sends `Prefer: respond-async, wait=<inlineWait>` for visual, MQ and SQL queries and handles `202`, `409 DRS-9082` and `409 DRS-9089`; `bi-report.js` and `bi-report-board.js`: the run card, progress, cancel, drawing from the result on `done`, `?run=<id>`; `interaction` and `supersedes` from `stateFor`; the confirm dialog; Analyse and the SQL editor likewise; the proxy's wait for asynchronous calls | `test_bi_runs_inline.py` (the proxy and the JS contract) |
| **R3c** bell and inbox | `bi-run` notices render with their link (`inbox.js`, the inbox template); the group rings once | test |
| **R4c** CLI | `tools/biruns.py` (14.4), `bi mq` / `bi sql` `--async`, completion (`tools/completion.py`) | `tools/test_cli_biruns.py` |
| **R5c** Admin and help | `/admin/bi/runs` (17.2); help cards in `drishti-console/config/help.yaml` for the Runs tab, the run card and the confirm dialog | `test_help.py`, `test_help_links.py` |
| **R6c** browser test | section 19.6 | passes in the drill |

### 18.2a Built early (R1 types and the R3 maths, not wired)

`com.ash.drishti.server.bi.runs` holds `Run`, `RunState`, `Tier`, `RunId`, `DataVersionVector`, `ResultKey`, `PlannerSettings`,
`RunEstimate`, `RunEstimator` and `QuestionNormaliser`, with `RunStateTest`, `ResultKeyTest`, `RunEstimatorTest` and
`QuestionNormaliserTest`; `DRS-9080`..`9089` are in `ErrorCode`. Coding them corrected the design in these places:

* **Transitions the diagram of 3.3 lacks:** `planned → running` and `running → reused | failed` (a twin joining a running run, 13.3);
  `storing → cancelled` (section 11 cancels while storing); `storing → queued` (12.1 restarts `storing` rows); `reused → expired` (the
  shared result is swept); `queued → failed` and `awaiting-confirm → failed` (DRS-9085 at restart); `running → done` for an inline answer
  that is not stored. 12.1's "queued (or awaiting-confirm) → back in RunQueue" is wrong for an unconfirmed run: it stays
  `awaiting-confirm` (or fails), it is never queued without its confirmation.
* **The basis** of a partly built plan is the source behind the most *unbuilt* rows (built partitions are certain), which gives
  `delta-stats` in 4.4's example; *layers* only when nothing is to build.
* **"Divided by its fragment share"** in 4.2 is read as: build seconds per table ÷ the fragments a run may use at once
  (`max-fragments × fragments-share`, at least 1).
* **`rowsOut`** is also capped by `readRows` (the product of grain cardinalities can pass the rows read), and saturates.
* **Relative dates are RelativeDates' names** (`this-year`, `previous-month`, `last-5-business-days`): 8.3a's "last month" is
  `previous-month`. A `relative` filter normalises to `between [from, to]` exactly as `MqCompiler` compiles it; `all` to no filter.
* **Normalising sorts the grain and the measures,** so the stored result's column order (and its row order when the question has no
  `sort`) can differ from the asker's: the result reader must project the asker's own order. `between` bounds, `sort` and
  `totals.levels` keep their order; `mq` (the language version) is dropped.

### 18.2b As built (server lane)

| Step | Status | What was built |
|---|---|---|
| R1 | built | `RunsProperties` (reads `config/runs/runs.yaml`, every key defaulted; examples in `config/runs.examples/`: `laptop.yaml`, `cluster.yaml`, `off.yaml`), `RunsConfiguration`, `RunJson` (14.3), `RunProblem` (a problem with extra properties and `Retry-After`), `RunsController` (every endpoint of 14.1), the three tables in both schema files, `SchemaParityTest`; `Run` gained `title`, `reuseOf`, `rowsRead` and a `Builder` |
| R2 | built, except the run engine | `Admission` class caps (`classCap`, `background-share` and `standing-share` from `drishti.bi.exec`, `byClass` in its stats; an interrupted waiter leaves the line at once, which is how a cancelled run's waiter is removed); `ExecProperties` `backgroundShare`, `standingShare`, `fragmentsShare`; `QueryService.refreshInBackground` (the `BACKGROUND` class, through admission), used by `DatasetRefresher`; `FragmentPools` gives background work at most `fragments-share` of a connector's pool. **Not built:** the second DuckDB instance for runs (section 10.3): runs execute on the one engine, bounded by the class cap and `workers` |
| R3 | built | `RunPlanner` (footprint → `RunEstimator` estimate → decision), `RunRates` (scan and build rates and the actual ÷ estimate ratios per engine, learnt from finished runs; the range factors narrow after `minRatioSamples`), `RunHistory` (rows per day per table, an exponentially weighted mean of the built partitions the planner peeks at; in memory, learnt again after a start); `Layers.peek` and `peekRollup` (`meta.json` only, nothing built, no data file opened); `SourcePlugin.rowEstimate(kind, date)` (default empty) with `RowEstimate(rows, basis)` in drishti-api, Delta from the add actions of the current snapshot (`numRecords` of their statistics, files without statistics by bytes ÷ the bytes per row of those with), Iceberg from the manifests' `record_count` of the date's data files (an upper bound when there are delete files), `SourceGateway.rowEstimate` summing a union's connectors; `QueryService.footprint` (`Footprints`: tables, dates, rows with their basis, built or not, the rollup the optimiser would choose and its valid partitions, the data version vector), reached from `MqService.footprint` (a `top` filter, which needs data to compile, and staged tables give no footprint). **The synchronous `DRS-9029` now comes from the footprint before any layer build** (the check after the build stays, for dates nobody can size); it covers the question's own range, not the extra ranges a time-intelligence measure compiles. Tiers, `DRS-9082` (a visible `awaiting-confirm` row and a token; `POST /runs/{id}/confirm`, or the question asked again with `confirmToken`), `DRS-9086` and `DRS-9089` at plan time; an ad-hoc heavy run without confirmation answers `202` at once. An interaction whose size is unknown is not refused `DRS-9089`: it runs interactively, bounded by the interactive limit (refusing every first slicer on a connector that cannot count would break dashboards). **Report visuals are planned (BIR-06):** the visual's main question, as the report asks it of its model (`BiReportService.footprint`, `DatasetService.footprint`, `ModelGateway.footprint` and `footprintModel` in `ConsumerQueries`), is sized as an MQ is: ad-hoc heavy answers `202` at once, above the thresholds `409 DRS-9082` (confirmed by token or `POST /runs/{id}/confirm`), an interaction `409 DRS-9089`, past every engine `DRS-9086`. A visual of **unknown** size is neither asked to confirm nor refused (`RunSources.reportPlan`): it waits inline and becomes a run past the wait, as before, so a page on a connector that cannot count does not ask for every visual (the same rule as an interaction of unknown size). Totals and highlight queries are not sized. Without `Prefer` nothing changes (`ModelAcceptanceTest` order 19, `RunServiceTest`). `RunPlannerTest`; `rowEstimate` tests in `DeltaLayoutTest` and `IcebergSourcePluginTest` |
| R4 | built | `RunService` (submit, inline wait, visibility, `supersedes`, cancel by interrupt, single-flight join, `interaction`), `RunQueue` (fair share, `workers`, `queue.max`), `RunRecords` (JDBC over the identity database, or memory), `RunSources` (mq, sql and report questions computed as the caller), `Prefer: respond-async` on `MqController` (both endpoints), `SqlController`, `BiReportController` (JSON; Arrow, EXPLAIN and an MQ with `options.explain` stay synchronous). Without `Prefer` every endpoint is unchanged (`ModelAcceptanceTest` order 17 asks all four endpoints both ways and compares the answers, for a raw and a masked reader). **Gone caller:** when the request thread waiting for an invisible run is interrupted, the run is cancelled and its work thread interrupted (`cancel.release` metric); Spring MVC does not tell a blocked request that its connection closed, so a closed browser tab is noticed only when the container interrupts the request (an async timeout), not at once. A superseded twin that already finished no longer fails the newer question with `DRS-9080` (the fault behind the console's parameter-slicer test). `StatementController` (preview and file) takes `Prefer` too: a statement is a run with a personal signature; inside the wait the same answer (the file, with `Drishti-Run`), past it `202` and a result of `{format, filename, contentType, pages, bytes}` (`StatementApiTest`) |
| R5 | built, JSON results | `RunStore`: `results/<xx>/<key>/answer.json` (the answer with its rows, the signature it was computed for) and `owner.txt` (whose budget it counts against) under `store.dir`, written beside their names and moved with `FileSwap`; served only to the same signature; paging; `sweep` (retention by last open, **`store.max-bytes-per-user`: a person's least recently opened results go first, nobody else's**, `store.max-bytes`, stale temporary files) every `retention.sweep`; `usage()` gives bytes, count, the oldest open and the five largest users. The key is `sha256(question \| signature \| data version \| v1)`. **The data version of a planned MQ or SQL question is the hash of its data version vector** (every table and date read, `SourcePlugin.dataVersion(kind, date)`) with the model's hash, so a stored result is reused after a restart while the data is unchanged and never once a date is restated; a source without a version or a live table makes it not reusable. Questions without a footprint keep the in-process generation (a boot id is part of it). Report visuals have a personal signature (user and roles) and no data version: stored for their runner, never reused. Not built: Parquet via `COPY TO`, Arrow results |
| R6 | built | **Sharing** (`RunSharing`, `RunShares` over `drishti_bi_run_share`): `SecuritySignature.rowPart` (rules, denied layers, hidden columns, denied kinds; the reader for a personal model) and `maskPart` (`alias.column`) decide per recipient: `same` (equal signature), `re-masked` (equal row part, the recipient's masks ⊇ the runner's, the question validates as the recipient) or `recomputed` (a new run as the recipient, `shared_from` the original, the background class; equal signatures join one computation). **Corrected while building:** masking on output is *not* what the engine does for a grouping field: the engine does not group by a masked column, so its groups collapse into one. Re-masking therefore masks the value **and** collapses the groups, summing the measures, which is exact only when every measure is additive and there is no limit, top filter or totals; otherwise the share is recomputed. A SQL statement or a report visual is `same` or `recomputed`. **The decision is taken again at every read** as the recipient is now, so a recipient whose rights narrowed is refused `DRS-9021` ("re-run it"), never shown the wider answer. `role:x` expands to its members when shared; a person who may not read the model gets no share. The recipient sees the run in their list and `GET` (`asRecipient`), reads `/result` and exports their own version; the owner's run object lists `shared.with`. Notices name the sharer and the title only (`RunSharingTest`, 19.3: an admin sharing with a viewer is re-masked and equals the viewer's direct query; a viewer sharing with an admin is recomputed as the admin; other desks recomputed with no row of the runner's; a role of three, two alike, computes twice). **Export:** `POST /runs/{id}/export {format: csv|xlsx}` through `BiExporter.ofAnswer`, `CsvWriter`, `XlsxWriter` (`RunExportTest`). **Schedules** (`RunSchedules` over `drishti_bi_run_schedule`, `SubscriptionSchedule`'s grammar without `after-load`): each firing asks again as the owner now; an owner who lost access gets a failure and a notice; checked every 30 s; the run object's `schedule` (`RunSchedulesTest`). **Promotion** (9.4 as built): `DatasetDef.Source.RUN` (`source: {run, version, token}`) and its parser, the snapshot read as a staged table through `ModelCatalog.Snapshots`, `POST /runs/{id}/promote` to a draft dataset and optionally a draft report, the snapshot bound to its dataset by name and token, versions kept per `promote.versions-keep` (`RunPromotionTest`, `ModelAcceptanceTest` order 18) |
| R7 | built (13.4) | **Cluster:** `RunCluster` (`ShardDirectory` over Drishti*net members), forwarding of submit to the owner and of a run's calls to its node (`RunForwardFilter`), the list scattered and merged, events relayed, leases renewed and adoption by conditional update (`RunClusterTest`). **Restart:** `RunService.recover` at start: every unfinished row of this node is replayed as its runner (`Principals.of`, attempt + 1, up to `restart.max-attempts`) or failed `DRS-9085` with a reason; each change is published and told. **Statement runs replay too** (2.6.7 A4): the preview or file is made again as its runner from the request it was asked with (`RunSources.statements`, `StatementController.fileAsRun`), no longer `DRS-9085`; a replayed run's signature is the runner's now, so its result reads back (`StatementReplayTest`). **A run waiting for its confirmation keeps waiting** across a restart or an adoption (BIR-08): its question is bound again as its runner, with a new token (the owner's run object carries it), until `confirm.ttl` from when it was asked; past that, or when it cannot be asked again, it is cancelled quietly (`DRS-9083`, "not confirmed within … min" or "the confirmation lapsed when the server restarted; nothing ran") with no bell, since nothing ran (`RunServiceTest`) |
| R8 | mostly | `bi-run` notices through `BiNotifier.tell` on done, failed and cancelled-by-others (names and counts only, tested); metrics `drishti.bi.runs.submitted`, `finished`, `inline`, `reuse`, `seconds`, `estimate.ratio` (distribution, tags `basis`, `engine`), `cancel.release` (timer); `GET /api/v1/bi/admin/runs` in the shape of 14.5 (`RunAdmin`); the gauges `drishti.bi.runs.queue` (class), `running` (engine), `store.bytes` (kind `results`, `snapshots`); `GET /api/v1/bi/engine` adds `runs` (workers, running, queued, the engine runs execute on: `shared`, no second instance yet); `now.admissionWaitP95Ms` (`Admission.interactiveWaitP95Ms`), `accuracy.basis` and `storage.snapshots*` filled (`RunGaugesTest`). **8.3a suggestions built** (`RunSuggestions`): finished ad-hoc heavy model queries counted per model, grain and filters' shape (field and operator, never a value) in `suggest.window`; past `suggest.min-runs` with a median of `suggest.min-seconds` a suggestion with its rollup drafted; accept adds the rollup to the model's draft under `rollups:` (comments kept, reviewed as any change), dismiss; decisions in `<store.dir>/suggestions.json`; `built` once the model has a rollup over that table at that grain (`RunSuggestionsTest`). Not shown in the model designer yet (console lane) |

### 18.3 Together

**R9:** the load test (19.5), the docs of section 20, the tutorial, then the experience QA round and one drill.

## 19. Tests

### 19.1 Unit

* `RunStateTest`: every allowed transition, and every illegal one throws.
* `RunPlannerTest`: a fixture model and lake of 10K trades over 60 days. One date gives interactive; a year covered by a rollup gives
  known heavy; a year at a grain no rollup has gives ad-hoc heavy; a missing rollup date with small raw rows gives interactive
  (mixed); `rowEstimate` empty plus no history gives `basis: unknown` and confirm; above `engine.max-read-rows` gives `DRS-9086`.
  **The estimate opens no data file** (a counting `SourceGateway`).
* `RowEstimateTest` (Delta, Iceberg): numRecords summed per date; files without stats fall back to bytes per row; equal to the true
  count on the fixture.
* `AdmissionClassCapTest`: the class caps, `Waiter.cancel()`, and interactive preferred under every mix.
* `RunStoreTest`: the key changes with each component; a restated date makes a new key; a source without a data version is never
  reused; Parquet round trip equals the answer (types, masks, totals); `FileSwap` replace under a held file.
* `RunSweeperTest`: retention by last open, budgets per node and per user, promoted results kept, `tmp/` swept.

### 19.2 The inline-versus-job threshold

* A question that takes 1.5 s with `inline.wait: 2s` answers `200` with today's body, writes no row and sends no notice.
* One that takes 2.5 s answers `202` at 2 s. Its row appears, it finishes, the notice arrives, and the result equals the synchronous
  answer.
* An ad-hoc heavy question answers `202` at once (it does not wait 2 s).
* `inline.wait: 0` makes everything a job; `enabled: false` makes everything synchronous as in 2.6.6.
* An interaction that is ad-hoc heavy answers `DRS-9089` within 50 ms and nothing runs (counted engine calls = 0).
* An interaction still running at the interactive limit is cancelled and never becomes visible.
* Without `Prefer`, every existing BI test passes unchanged.
* The time is controlled by a test `RunEngine` that sleeps a set time, so the threshold is tested exactly without heavy data.

### 19.3 Masks on shared runs

On the fixture with the pack's desk rules and masks (`PRICE`, `TRADER_ID`, `NAME`, as the soak uses):

* runner and recipient with the same desks and masks: `same`, and the recipient reads the identical result;
* recipient with the same desks and more masks: `re-masked`; the recipient's extra columns are the mask on every row, never a value
  (checked cell by cell), and the runner's view is unchanged;
* recipient with other desks: `recomputed`; the recipient's rows equal a direct query as the recipient, row for row; no row of the
  runner's desks appears;
* a recipient whose masked column feeds a measure: validation refuses re-mask and the run is recomputed (or refused `DRS-9161` for
  the recipient, as a direct query would be);
* a share to a role with three members, two with equal signatures: two recomputations, not three;
* a notice body and a run row never contain a data value (scan for the fixture's values);
* a promoted snapshot keeps the promoter's masks for every reader and applies its own rules on top.

### 19.4 Restart and cancellation

* Restart with one run queued, one running and one storing (`RunRecoveryTest` stops the service between phases): queued are
  requeued in order; running starts again with `attempt 2` and finishes; a second interruption makes `DRS-9085`; `tmp/` is swept;
  each change is notified; nothing stays `running`.
* A queued run whose model changed fails with the model named.
* Cancel in each phase (queue, admission wait, building, scanning, storing): the admission slot is free in under 100 ms; the
  connector's fragment pool is back to full; DuckDB's connection is closed; no `tmp/` remains; `drishti.bi.runs.cancel.release`
  p99 is under `cancel.grace`.
* A gone caller: closing the HTTP connection of an invisible run cancels it (counted engine work stops).
* Cluster (`RunClusterTest`, two nodes in one JVM over a shared SQLite in tests and a per-node SQLite): the owner runs it; the other
  node shows its status and result; killing the owner mid-run makes the new owner adopt it (shared database) or shows *unknown* (per
  node); a returning old owner abandons its copy.

### 19.5 Load: heavy runs never starve interactive queries

Within the local safety rule (10K trades at most, one heavy process): heaviness comes from **many dates and cold layers**, plus a
`DelayingSourceGateway` that makes each layer build take 2 s and each run-engine scan 5 s. No large data is used.

```
 baseline   : 20 interactive probes (one date, one desk; layers warm) at 5/s for 60 s       → p50, p95, refusals
 burst      : 40 ad-hoc heavy runs from 10 users submitted in 5 s (each 60 cold dates)
              + the same interactive probes for 120 s
 assert     : probe p95 under burst ≤ baseline p95 × 1.5 + 50 ms
              zero DRS-9025 among probes
              background slots never above the cap (sampled every 100 ms)
              queue.per-user refusals (DRS-9081) for users past 5, none for others
              every run ends done or DRS-9081; heap after GC within 20 % of baseline; no virtual-thread growth
              (count sampled: the QA3A-01 signature)
```

It runs as `drishti-it` `BiRunsLoadIT` (excluded from the default build, run in the drill), with its report in
`docs/admin/PERFORMANCE.md`.

### 19.6 Browser test

`test_bi_runs_browser.py` on the demo server:

1. Open the Desk P&L report; the visuals on one date draw inline with no run card (as today).
2. Set the period to *this year* on a visual at a grain no rollup has: the confirm dialog shows a range and its basis; *Run in
   background* shows the run card with progress.
3. Move to another page; the bell's count goes up when the run finishes; the inbox row opens the report with `?run=`, drawn from the
   result.
4. A slicer on a covered visual answers instantly with no card; a slicer that would need a run shows *Run in background*.
5. The Runs tab lists the run with as-of, data version, estimate and actual; re-run on the same data reuses it (`reused`); cancel a
   second run and its row says *Cancelled by you*.
6. Share with a viewer; signed in as the viewer, the run shows masked columns as the mask.
7. Keyboard only: tabs, the list and the drawer are reachable; status changes are announced (`aria-live`).

## 20. Docs to update when built

| Doc | What |
|---|---|
| [BI_GUIDE.md](../guides/BI_GUIDE.md) | runs: the inline wait, the run card, the Runs tab, estimates and tiers, sharing, promotion, schedules |
| [USER_GUIDE.md](../guides/USER_GUIDE.md) | the bell's run notices; `BI RUNS <GO>` |
| [API_GUIDE.md](../guides/API_GUIDE.md) | the endpoints of 14.1, `Prefer: respond-async`, the run object, `DRS-9080`–`9089` in the error table |
| [CLI_GUIDE.md](../guides/CLI_GUIDE.md) | `drishti.py bi runs …`, `bi mq --async` |
| [CONFIGURATION.md](../admin/CONFIGURATION.md) | `config/runs/runs.yaml`, `drishti.bi.runs.file`, `drishti.bi.exec.background-share`, `standing-share`, `fragments-share` |
| [TROUBLESHOOTING.md](../guides/TROUBLESHOOTING.md) | every new code; "my run says unknown estimate"; "my run was started again"; "the result expired" |
| [PERFORMANCE.md](../admin/PERFORMANCE.md), [LOAD_AND_MEMORY.md](../admin/LOAD_AND_MEMORY.md) | the run engine's memory; the load test's numbers |
| [OPERATIONS.md](../admin/OPERATIONS.md), [NET.md](../admin/NET.md) | Admin → BI → Runs; runs in a cluster |
| [DATASETS.md](../guides/DATASETS.md) | `source: {run: …}` snapshot datasets |
| BI_DESIGN 6.3 | the class caps; correct its code column (busy is `DRS-9025`, not `DRS-9022`) |
| `drishti-console/config/help.yaml` | cards for the Runs tab, the run card, the confirm dialog, the snapshot dataset |
| a tutorial, `docs/tutorials/22-heavy-questions-as-runs.md` | a year of P&L at a new grain: estimate, confirm, keep working, the bell, open, share, promote, add the rollup and see it become instant; every step in UI, CLI and YAML |
| CHANGELOG, RELEASE_NOTES | 2.6.7 |

## 21. Open questions

1. **Per-node SQLite clusters.** Status from any node needs scatter and forwarding (13.2). Should 2.6.7 require a shared identity
   database for clusters that use runs, or accept the *unknown* state until 2.7.0's one Drishti database makes sharing the norm?
2. **Store interactive results by default?** `store.keep-interactive: true` makes slow first draws reusable for everyone with the
   same rights, at the cost of disk. It could stay true, or only for certified reports.
3. **Visibility in shared workspaces.** `owner-and-shared` (default) or `members` (run rows visible to workspace members, results
   never)?
4. **Which external engine first** (3.0 groundwork): Trino (on-premises, the Delta connector) or Athena (AWS)?
5. ~~**Rollup suggestions from runs.**~~ Decided (product owner, 2026-10-20): in 2.6.7, with normalisation and the already-ran prompt (section 8.3a).
