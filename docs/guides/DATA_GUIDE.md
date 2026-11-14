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
# Data guide: getting data into Drishti

Getting data into Drishti and keeping it current, for the people who connect it: **telling Drishti a batch has landed** (section 1:
one call from your ETL, the Data loads page, expectations and late data), **the data formats** (section 2: JSON Lines is the standard;
Arrow to the browser where it pays), **external data** (section 4: every public source of every pack, its licence and its refresh
script), **a pack from a schema** (section 5), taking data out, **Export data** (section 6), and connecting the stores themselves:
**connectors** (section 8: which plugin for which store, unions, combining history and live), **connector files and Admin → Connectors**
(section 9: the files, live reload, the page, the API, the CLI, health), **TLS and the outbound proxy** (section 10) and **the demo data**
(section 11: the test data, the demo lakes, every other store, your bank's names). **Lakes and loading** (the lake file,
the data inbox, the `.ready` file, `lake build`, the parallel loader, business dates, modes, undo, sizing, and reading a Databricks or
Iceberg lakehouse) have their own guide: [DATA_LAKE_GUIDE.md](DATA_LAKE_GUIDE.md); sections 2.7, 2.8, 3 and 7 below point there.

Each plugin's README is the one home for that plugin ([8.2](#82-which-connector)); the
shape of each command's data is its [contract](../contracts/packs/README.md); how the data layer is built (live updates, history
queries, the Iceberg engine, reactive connectors, the content store, the database) is [DATA_ARCHITECTURE.md](../architecture/DATA_ARCHITECTURE.md).
Each section keeps its own numbering (section 1's "section 4" is *4. The API reference* inside section 1).

## Contents

1. [Telling Drishti new data has landed](#1-telling-drishti-new-data-has-landed)
    - [1. Why: what the signal is for](#1-why-what-the-signal-is-for)
    - [2. Concepts](#2-concepts)
    - [3. Your first load in five minutes](#3-your-first-load-in-five-minutes)
    - [4. The API reference](#4-the-api-reference)
    - [5. The command line reference](#5-the-command-line-reference)
    - [6. Configuration reference](#6-configuration-reference)
    - [7. What happens, step by step](#7-what-happens-step-by-step)
    - [8. Expectations and late data](#8-expectations-and-late-data)
    - [9. The Data loads page](#9-the-data-loads-page)
    - [10. Permissions](#10-permissions)
    - [11. Recipes](#11-recipes)
    - [12. Troubleshooting](#12-troubleshooting)
    - [13. FAQ](#13-faq)
2. [Data formats: JSON Lines is the standard](#2-data-formats-json-lines-is-the-standard)
    - [1. The rule](#1-the-rule)
    - [2. The row forms](#2-the-row-forms)
    - [3. Reading: JSONL first, JSON still, CSV deprecated](#3-reading-jsonl-first-json-still-csv-deprecated)
    - [4. Going out: JSON Lines beside every export](#4-going-out-json-lines-beside-every-export)
    - [5. What stays as it is, and the guard](#5-what-stays-as-it-is-and-the-guard)
    - [6. Arrow to the browser, where it pays](#6-arrow-to-the-browser-where-it-pays)
    - [7. Archives: zip and tarballs](#7-archives-zip-and-tarballs)
    - [8. Feeds: the table and business date of a file](#8-feeds-the-table-and-business-date-of-a-file)
3. [Using Drishti with Databricks Delta Lake or Apache Iceberg](#3-using-drishti-with-databricks-delta-lake-or-apache-iceberg): in [DATA_LAKE_GUIDE.md](DATA_LAKE_GUIDE.md)
4. [External data: where it comes from and how to refresh it](#4-external-data-where-it-comes-from-and-how-to-refresh-it): its own guide, [EXTERNAL_DATA.md](EXTERNAL_DATA.md)
5. [Schema to pack: generate a pack from JSON Schema](#5-schema-to-pack-generate-a-pack-from-json-schema)
6. [Export data: download a dataset](#6-export-data-download-a-dataset)
    - [1. What a dataset is](#1-what-a-dataset-is)
    - [2. The selection](#2-the-selection)
    - [3. How the rows are read: pushdown and streaming](#3-how-the-rows-are-read-pushdown-and-streaming)
    - [4. Entitlements, masks, internal-only data and the audit](#4-entitlements-masks-internal-only-data-and-the-audit)
    - [5. The API](#5-the-api)
    - [6. The command line](#6-the-command-line)
    - [7. Settings](#7-settings)
7. [The data inbox: drop files and they load](#7-the-data-inbox-drop-files-and-they-load): in [DATA_LAKE_GUIDE.md](DATA_LAKE_GUIDE.md)
8. [Connectors](#8-connectors)
    - [8.1 One file per connector](#81-one-file-per-connector)
    - [8.2 Which connector?](#82-which-connector)
    - [8.3 Several connectors for one kind: a union](#83-several-connectors-for-one-kind-a-union)
    - [8.4 Combining connectors](#84-combining-connectors)
    - [8.5 Connectors built into the engine: derived, net and reactive](#85-connectors-built-into-the-engine-derived-net-and-reactive)
9. [Connector files and Admin → Connectors](#9-connector-files-and-admin--connectors)
    - [9.1 The model in one minute](#91-the-model-in-one-minute)
    - [9.2 The folder](#92-the-folder)
    - [9.3 The file](#93-the-file)
    - [9.4 Examples, one per store](#94-examples-one-per-store)
    - [9.5 Credentials and TLS](#95-credentials-and-tls)
    - [9.6 How packs and files fit together](#96-how-packs-and-files-fit-together)
    - [9.7 Live reload](#97-live-reload)
    - [9.8 Admin → Connectors](#98-admin--connectors)
    - [9.9 The REST API](#99-the-rest-api)
    - [9.10 The CLI](#910-the-cli)
    - [9.11 Moving off application.yaml and the old data-source override](#911-moving-off-applicationyaml-and-the-old-data-source-override)
    - [9.12 Health and problems](#912-health-and-problems)
    - [9.13 Decisions](#913-decisions)
    - [9.14 Connection pools](#914-connection-pools)
    - [Status words, warnings and what the page tells you](#status-words-warnings-and-what-the-page-tells-you)
10. [TLS and the outbound proxy](#10-tls-and-the-outbound-proxy)
    - [Connector matrix](#connector-matrix)
    - [10.1 The settings](#101-the-settings)
    - [10.2 What can be trusted](#102-what-can-be-trusted)
    - [10.3 A client identity: mutual TLS](#103-a-client-identity-mutual-tls)
    - [10.4 Passwords never go in a document](#104-passwords-never-go-in-a-document)
    - [10.5 Protocols, cipher suites, hostname verification](#105-protocols-cipher-suites-hostname-verification)
    - [10.6 Insecure trust-all, for development only](#106-insecure-trust-all-for-development-only)
    - [10.7 Making and converting certificates and keys](#107-making-and-converting-certificates-and-keys)
    - [10.8 Start-up checks and their messages](#108-start-up-checks-and-their-messages)
    - [10.9 Certificate expiry](#109-certificate-expiry)
    - [10.10 Rotation](#1010-rotation)
    - [10.11 Troubleshooting](#1011-troubleshooting)
    - [Outbound HTTP proxy for connectors, AI providers and MCP servers](#outbound-http-proxy-for-connectors-ai-providers-and-mcp-servers)
11. [The demo data](#11-the-demo-data)
    - [11.1 The test data and the demo lakes (the default)](#111-the-test-data-and-the-demo-lakes-the-default)
    - [11.2 Mixed Delta and Iceberg storage](#112-mixed-delta-and-iceberg-storage)
    - [11.3 The sizes](#113-the-sizes)
    - [11.4 Delta Lake](#114-delta-lake)
    - [11.5 PostgreSQL](#115-postgresql)
    - [11.6 Apache Iceberg](#116-apache-iceberg)
    - [11.7 DuckDB](#117-duckdb)
    - [11.8 MongoDB](#118-mongodb)
    - [11.9 Redis](#119-redis)
    - [11.10 Aerospike](#1110-aerospike)
    - [11.11 Apache Cassandra](#1111-apache-cassandra)
    - [11.12 JSON-lines files (no store at all)](#1112-json-lines-files-no-store-at-all)
    - [11.13 Your own JSONL](#1113-your-own-jsonl)
    - [11.14 Your bank's names](#1114-your-banks-names)
    - [11.15 The samples](#1115-the-samples)
    - [11.16 JSON lines: one format for every loader](#1116-json-lines-one-format-for-every-loader)
    - [11.17 The scripts](#1117-the-scripts)

## 1. Telling Drishti new data has landed

Drishti reads data **where it lives**. It does not ingest, transform or schedule anything: loading the lake, the files or the database
is the job of your own ETL (or of a pipeline engine such as DishtaYantra). What only Drishti can do is **act on the data once it is
there**. One small signal, "this batch has landed", makes Drishti refresh what it caches, check the data is really readable, evaluate the
alert rules on it, and tell the right people. Expectations then flag a batch that has *not* landed in time.

Related: [API_REFERENCE.md](API_REFERENCE.md) (every endpoint, the error codes), [CLI_REFERENCE.md](CLI_REFERENCE.md) (the `drishti.py` program),
[CONFIGURATION.md](../admin/CONFIGURATION.md) (every setting), [BUILD_GUIDE.md](BUILD_GUIDE.md#31-the-packs-that-ship-turning-them-on-who-sees-which) (what a pack is),
[OPERATIONS_GUIDE.md](OPERATIONS_GUIDE.md#8-deploy-promote-and-roll-back-by-copying) (shipping packs and data to servers).

### 1. Why: what the signal is for

Without the signal Drishti finds out about new data by accident: the next person to open a view after a connector's cache expires.
Everything that should happen **because a batch landed** (a risk limit alert, an email to the desk, the check that the lake has
yesterday's date) then happens late or not at all, and nobody notices a batch that never came.

With the signal, the same four things happen every time, in order, and each leaves a result you can read:

| You want | Drishti does |
|---|---|
| views and date lists to include the new data now | drops the caches of the connectors that serve the kind and of the derived connectors built from it (the same thing Admin → Caches does): for a Delta connector its memory and disk caches and id map files; grouped pages kept on disk are rebuilt from the new data at once, the old pages serving meanwhile. Without the signal a Delta table's new version is seen within `refresh-seconds` (10 s) and grouped pages rebuild within `version-check` (10 s); the caches are keyed by table version, so nothing stale is served either way |
| to know the data is really readable | counts the kind's entities on that business date and records `verified` or `not-found` |
| alerts on the new numbers | evaluates every alert rule on the kind for that date, as each rule's owner may see it |
| alerts on numbers, and subscriptions, that follow loads | an alert on a BI measure or a Sutra field whose trigger is `after-load` (and every "no data" alert), a board tile that notifies after loads, and a subscription with the schedule `after-load`, look `load-delay` (5 s) after a load that finished ready, once the BI layers have rebuilt ([REPORTS_GUIDE.md](REPORTS_GUIDE.md#192-when-it-fires-and-when-it-looks)) |
| the right people told | one inbox notice (and the bell) each, and an email through the outbox if the pack asks for it |
| to know when a batch did **not** come | an expectation per kind (`trade by 19:00 America/New_York`); a scheduler flags it late on Health and tells the same people |

**What this is not.** Drishti will not copy, parse, transform or schedule your data. `drishti.py data ingest`, `data ingest --watch` and
`pack make` exist to stage data for a demo, a small team or a proof of concept; they are not a production ETL (see
[OPERATIONS_GUIDE.md](OPERATIONS_GUIDE.md#8-deploy-promote-and-roll-back-by-copying) and [DATA_GUIDE.md section 11](../guides/DATA_GUIDE.md#11-the-demo-data)). In production your ETL loads the store and, as its
last step, makes the one call this guide describes.

### 2. Concepts

**A load** is one announcement: *this pack's kind landed (or failed) for this business date*. It carries optional facts (rows, rejected,
source, batch id, a note) and Drishti adds what it did about it. Loads are kept per pack (the newest `drishti.loads.keep`, 2,000 by default)
and are what the [Data loads page](#9-the-data-loads-page) lists.

**Status.** `ready` says the data is in place and Drishti should act. `failed` says the batch did not land: Drishti records it, tells the
people, and does nothing else. A later `ready` for the same kind and date replaces a `failed`, and a later `failed` after a `ready` is
recorded as such (the date is then *failed* until a new `ready` arrives).

**Idempotent.** The key of a load is **pack, kind, business date and batch id**. Announcing the same key with the same status, rows and
rejected again is a no-op: the same record is returned (`200`, `duplicate: true`) and nothing runs twice, so a retried job is safe. The same key
with a *different* outcome is a new record (`attempt` 2, 3, ...). A `ready` for a kind and date that already has a `ready` is marked a
**reload**.

**Verified.** After a `ready` load, Drishti counts the kind's entities on that business date, through the same connectors every view uses.
`verified` means at least one was found; `not-found` means none (a wrong root, a date folder that was not written, a cache that
could not be refreshed); `skipped` means the check did not apply (a failed load). With a *dated* connector (Delta, the File connector's date
folders, a dated SQL source) this proves the date is there. A connector that holds no dates can only show the kind has entities.

**Expectations.** Per pack, per kind: *by when* the load is due each business day, in which time zone and by which business-day calendar.
They come from the pack's own `loads:` section and the administrator's override file. (Where the pack reads its data is separate: it is set in connector files, `config/connectors/<name>.yaml`, see [DATA_GUIDE.md section 9](#9-connector-files-and-admin--connectors).)

**Late.** An expectation whose deadline has passed with no `ready` load is **late** (today) or **missing** (an earlier business day in the
look-back window). It is flagged once: a notice to the people the pack names, and a line on Admin → Health (`data late: my-bank/trade
2026-10-06`). It clears when the load lands.

```
  your ETL                       Drishti                                      people
  --------                       -------                                      ------
  copy / load data  ----------.
  (lake, files, SQL)           |
                               v
  POST /packs/{pack}/loads  -> record  (idempotent per pack+kind+date+batch)
        status=ready            |
                                +--> refresh   drop the caches of the kind's connectors
                                +--> verify    entities of the kind on that date?  verified | not-found
                                +--> alerts    evaluate the kind's alert rules on that date
                                +--> smoke     open N sample entities (optional)
                                +--> notices   inbox + bell (+ email) to the pack's roles/users ---> "trade for 2026-10-06
                                |                                                                    loaded: 48,213 rows,
        status=failed  ---------+--> record + notices only                                           12 rejected; 3 alerts fired"
                                |
  scheduler (every minute) -----+--> deadline passed, nothing ready?  ->  LATE  ->  Health + notice
                                +--> a ready load lands               ->  cleared
```

### 3. Your first load in five minutes

The walk-through, with real output and pictures, is [tutorial 12 · Load data daily](../tutorials/12-a-day-of-bank-data.md): say when you expect data (step 1), announce a load from the command line (step 2), a failed
batch (step 3), the same through the API (step 4), the history and what is due (step 5) and an alert for a missing day (step 6). In short: **announce** a batch when your ETL has written it,

```
$ drishti-console/.venv/bin/python tools/drishti.py data landed --pack market-risk --kind stress-scenario --date 2026-10-16 --rows 8 --batch eod-20261016-1
stress-scenario for 2026-10-16 loaded: 8 rows; 0 alerts fired
  load mvczs759-1  status ready  verified verified  attempt 1
  record  ok      recorded
  refresh ok      caches dropped: file, risk-store
  verify  ok      8 stress-scenario entities on 2026-10-16
  alerts  ok      0 alerts fired
  smoke   skipped not asked for (loads.smoke is 0)
  notices ok      1 person told
```

(On the quick-start server, the demo lakes' last business day; `DRISHTI_SERVER` and `DRISHTI_TOKEN` name the server and an
administrator's token. Run it again and the line under `load` ends `(already announced: nothing ran again)`.)

or `POST /api/v1/packs/{pack}/loads` ([section 4](#4-the-api-reference)); Drishti then records it, refreshes the caches, verifies that the rows are readable, checks alerts, runs a smoke test and tells people
([section 7](#7-what-happens-step-by-step)). Reading the history is `data loads` ([section 5](#5-the-command-line-reference)); the expectations that make late data visible are [section 8](#8-expectations-and-late-data).

**No ETL yet? Load a file from the browser.** For a pack whose kind is read by a File connector (as Build -> New pack makes it from data
files), **Admin -> Packs -> the pack -> Load data** takes one JSON array or JSON Lines file of any size, streams it to the server
(`POST /api/v1/admin/packs/{pack}/data?kind=K&date=YYYY-MM-DD`, token scope `packs:admin`, up to `drishti.loads.upload-max-mb`), writes it
under its business date (or the day you choose, for a snapshot kind: `ingest: {snapshot: true}`), checks every row against the pack's Sutras
(types with no Sutra, fields no Sutra shows), and then announces the load exactly as above, so the refresh, verify, alerts, smoke and notices
run and the pages built from that kind (a `derived` connector's pages per value) are rebuilt. The worked example is
[A deep trading JSON](BUILD_GUIDE.md#9-a-deep-trading-json-from-one-file-to-a-deployed-pack).

### 4. The API reference

All paths are under `/api/v1`. Every call needs the caller from the bearer token (or `X-Drishti-User` when sign-in is off).

#### 4.1 `POST /packs/{pack}/loads`: announce a load

Who: an administrator, or a personal API token with the `loads:write` scope whose user's roles open the kind. Success: `201` for a new
record, `200` with `"duplicate": true` for an announcement seen before. In a Drishti\*net cluster a new `ready` load is passed on once to
every other live member (PO-5): the answer then carries `forwardedTo` (the members' ids).

Request body (JSON):

| Field | Type | Required | Default | Rules |
|---|---|---|---|---|
| `kind` | string | yes | | a kind the pack owns (`DRS-5012` otherwise); at most 100 characters |
| `businessDate` | string `yyyy-MM-dd` | yes, or `asOf` | | the business date the batch is for; not in the future (in the server's business-date zone); weekends and holidays are accepted as given |
| `asOf` | string `yyyy-MM-dd` | alias | | used when `businessDate` is absent |
| `status` | `ready` or `failed` | no | `ready` | anything else is `DRS-5001` |
| `rows` | integer >= 0 | no | unknown | shown in the notice and the history |
| `rejected` | integer >= 0 | no | unknown | rows your ETL rejected |
| `source` | string | no | none | the job or system name; at most 200 characters |
| `batchId` | string | no | none | part of the idempotency key; letters, digits and `. _ : / # @ -` and spaces, at most 100 characters |
| `note` | string | no | none | for a failed load, why; at most 1,000 characters; shown in the notice |

Response body: the record, plus `summary` and `duplicate`.

| Field | Type | Meaning |
|---|---|---|
| `id` | string | the load's id (use it in `GET /packs/{pack}/loads/{id}`) |
| `pack`, `kind`, `businessDate`, `status`, `rows`, `rejected`, `source`, `batchId`, `note` | | what you sent (absent when you did not send it) |
| `receivedAt` | instant | when the server received it |
| `receivedBy` | string | the user the request was made as |
| `attempt` | integer | 1 for the first outcome announced for the key, 2 for the next different one, ... |
| `reload` | boolean | a `ready` load of this kind and date was already recorded |
| `verified` | string | `verified`, `not-found` or `skipped` |
| `entities` | integer | entities of the kind found on the date (capped at `drishti.loads.verify-limit`; a count at the cap means "or more") |
| `alerts` | integer | alerts fired by the evaluation |
| `notices` | integer | people told |
| `steps` | array | `{name, status, detail, ms}` per step; status is `ok`, `warn`, `failed` or `skipped` (section 7) |
| `durationMs` | integer | how long the steps took |
| `done` | boolean | false only while the steps are still running |
| `summary` | string | the one line used in the notice |
| `duplicate` | boolean | true when this announcement had been seen before and nothing ran again |

**Idempotency rules.** Key = pack + kind + `businessDate` + `batchId` (empty when absent). Same key and same `status`, `rows` and `rejected` as
the newest record of that key: duplicate. Anything else: a new record. Without a `batchId`, send the same body twice and it is a duplicate; send
different `rows` and it is a reload.

**Errors** (problem JSON: `type`, `title`, `status`, `detail` starting with the code, `instance`, `code`):

| Status | Code | When | Example body |
|---|---|---|---|
| 400 | `DRS-5001` | `businessDate`/`asOf` missing, `status` not ready/failed, negative counts, an over-long field, a bad `batchId`, invalid JSON | `{"type":"about:blank","title":"bad request","status":400,"detail":"DRS-5001 businessDate (or asOf) is required, as yyyy-MM-dd","instance":"/api/v1/packs/market-risk/loads","code":"DRS-5001"}` |
| 400 | `DRS-4003` | the date is not `yyyy-MM-dd` or is in the future | `{"type":"about:blank","title":"bad business date","status":400,"detail":"DRS-4003 cannot read business date '05/10/2026' (use yyyy-MM-dd)","instance":"/api/v1/packs/market-risk/loads","code":"DRS-4003"}` |
| 401 | `DRS-5010` | no or an unknown bearer token | `{"title":"unauthenticated","status":401,"code":"DRS-5010","detail":"DRS-5010 missing bearer token"}` |
| 403 | `DRS-5002` | not an administrator and no `loads:write` token; a token without the scope; the token's user may not open the kind | `{"title":"forbidden","status":403,"code":"DRS-5002","detail":"this token lacks the scope loads:write"}` |
| 404 | `DRS-5011` | no loaded pack with that name (also for a pack none of whose kinds the caller may open: the two look the same) | `{"type":"about:blank","title":"load pack not found","status":404,"detail":"DRS-5011 no loaded pack named 'nope'","instance":"/api/v1/packs/nope/loads","code":"DRS-5011"}` |
| 422 | `DRS-5012` | the pack has no such kind | `{"type":"about:blank","title":"load kind unknown","status":422,"detail":"DRS-5012 pack 'market-risk' has no kind 'nope'; its kinds are [stress-scenario, stress-result, frtb-sensitivity, pnl-explain, trade-valuation, position, portfolio, pnl-vector, scenario-set, var-limit]","instance":"/api/v1/packs/market-risk/loads","code":"DRS-5012"}` |

A `ready` load that was recorded but could not be verified is **not** an error: it is `201` with `"verified": "not-found"` and a `warn`
`verify` step, so your job can decide (the CLI exits 1). For example (the shape; a dated connector with no data for the date). On the
demo lakes this does not happen for a date before the first business day: the check counts the kind's ids from the connectors' listing,
which is the newest one, so `2026-10-05` (before the lakes begin) also reads `8 stress-scenario entities on 2026-10-05`, a known limit:

```json
{"status":"ready","verified":"not-found","entities":0,"alerts":0,"notices":1,
 "steps":[{"name":"record","status":"ok","detail":"recorded","ms":0},
          {"name":"refresh","status":"ok","detail":"caches dropped: risk-store","ms":2},
          {"name":"verify","status":"warn","detail":"no stress-scenario entities found on 2026-10-05; check the connector's root, dates and caches","ms":4},
          {"name":"alerts","status":"skipped","detail":"the data is not readable for 2026-10-05, so no rule was evaluated","ms":0},
          {"name":"smoke","status":"skipped","detail":"not asked for (loads.smoke is 0)","ms":0},
          {"name":"notices","status":"ok","detail":"1 person told","ms":0}],
 "summary":"var for 2026-10-05 loaded: 1,200 rows; NOT FOUND in the data"}
```

#### 4.2 Reading

| Method | Path | Who | Answer |
|---|---|---|---|
| `GET` | `/packs/{pack}/loads?kind=&date=&status=&limit=` | an administrator, or a user whose roles open a kind of the pack (only loads of kinds they may open are listed) | `{pack, kinds, loads: [record ...]}`, newest first; `limit` 1-1000, default 100 |
| `GET` | `/packs/{pack}/loads/{id}` | as above | one record; `404 DRS-5011` when there is none |
| `GET` | `/packs/{pack}/loads/expectations` | as above | `{pack, now, origin, config, expectations: [{pack, kind, businessDate, by, zone, deadline, state, loadId, landedAt}]}` |
| `GET` | `/admin/loads/{pack}`, `/admin/loads/{pack}/{id}`, `/admin/loads/{pack}/expectations` | an administrator | the same three, for the console |
| `GET` | `/admin/health` | an administrator | adds `dataLate: [{pack, kind, businessDate, state, by, line}]`; the status is `DEGRADED` while it is not empty |

A token with only the default `read` scope may call every `GET` above (as the user it stands for).

#### 4.3 The expectation settings (administrators; never open to a token)

| Method | Path | Does |
|---|---|---|
| `GET` | `/admin/loads/{pack}/config` | the settings in force: `{pack, origin, config: {notify: {roles, users, email}, smoke, expect: {kind: {by, zone, calendar}}}, kinds}`; `origin` is `pack`, `override` or `default` |
| `PUT` | `/admin/loads/{pack}/config` | saves the whole override (`DRS-5001` with every problem when it is invalid; `DRS-5012` for an unknown kind); audited as `data-load-config-changed` |
| `DELETE` | `/admin/loads/{pack}/config` | removes the override; the pack's own `loads:` section applies again; audited as `data-load-config-reset` |

### 5. The command line reference

`tools/drishti.py` (see [CLI_REFERENCE.md](CLI_REFERENCE.md) for installing it and the connection options `--server`, `--token-file`, `--user`,
`--timeout`, and the `DRISHTI_SERVER` / `DRISHTI_TOKEN` / `DRISHTI_USER` environment). The token is never printed.

#### `data landed`

```
drishti.py data landed --pack P --kind K --date D [--status ready|failed] [--rows N] [--rejected N]
                       [--batch ID] [--source S] [--note TEXT] [--json]
```

| Option | Meaning |
|---|---|
| `--pack P` | the pack that owns the kind (required) |
| `--kind K` | the kind that landed (required) |
| `--date D` | the business date, `yyyy-MM-dd`, or `today` / `yesterday` (this machine's calendar day; use the date itself when your batch date differs) (required) |
| `--status` | `ready` (default) or `failed` |
| `--rows N`, `--rejected N` | counts |
| `--batch ID` | the batch id; makes retries idempotent |
| `--source S` | job or system name |
| `--note TEXT` | a note; for a failed load, why |
| `--json` | print the server's answer as JSON |

Exit codes: `0` recorded (and, for `ready`, verified); `1` a `ready` load is **not verified**, or the server refused it (an `ApiError` line
with the code and a hint); `2` usage (a bad `--date`, a missing option).

#### `data loads`

```
drishti.py data loads --pack P [--kind K] [--date D] [--status ready|failed] [--limit N] [--expectations] [--json]
```

Lists the history (default 30), optionally filtered; `--expectations` also prints what is due and its state; `--json` prints
`{pack, kinds, loads, expectations?}`. Exit `0` unless the call fails.

### 6. Configuration reference

#### 6.1 In the pack (`pack.yaml`)

```yaml
loads:
  notify:
    roles: [risk-ops, desk-head]     # everyone with one of these roles who may open the kind is told
    users: [ada]                     # and these users
    email: true                      # also queue an email (needs the collaboration email channel)
  smoke: 3                           # open this many sample entities after each ready load; 0 = off
  expect:                            # per kind, when its load is due each business day
    trade:  { by: "19:00", zone: America/New_York, calendar: USNY }
    curve:  { by: "07:30", zone: Europe/London,    calendar: GBLO }
    var:    { by: "23:59" }          # zone and calendar default to the server's business-date zone and calendar
```

| Key | Type | Default | Meaning |
|---|---|---|---|
| `notify.roles`, `notify.users` | lists of strings | the roles in `drishti.loads.default-notify-roles` (`admin`) when both are empty | who is told. A person is told only while they are enabled and may open the kind |
| `notify.email` | boolean | `false` | also an email per person through the outbox, if they have an address, did not turn the `load` event off (Settings, Notifications) and the email channel is on |
| `smoke` | integer | `0` | sample entities opened after a ready load (at most `drishti.loads.smoke-max`, 20) |
| `expect.<kind>.by` | `HH:mm` | | the local time of day the load is due |
| `expect.<kind>.zone` | time zone id | the server's business-date zone | the zone of `by` |
| `expect.<kind>.calendar` | calendar spec | the server's calendar | the business-day calendar (`USNY`, `GBLO`, `USNY+GBLO`, ...): no expectation is made for a day that is not a business day |

#### 6.2 The administrator's override

Admin → Packs → *Data loads* → **Expectations settings** (or `PUT /admin/loads/{pack}/config`) writes `<drishti.loads.dir>/config/<pack>.yaml`
(by default `./data/loads/config/<pack>.yaml`). The pack's own files are never touched, so redeploying the pack keeps the override. The
override replaces the pack's sections **whole**: a saved `expect` replaces the pack's `expect`, a saved `notify` replaces its `notify`. A section
the override leaves out stays as the pack has it. **Reset** deletes the file.

```yaml
# <loads dir>/config/my-bank.yaml
notify:
  roles: [risk-ops]
  users: []
  email: true
smoke: 2
expect:
  trade: {by: '19:00', zone: America/New_York, calendar: USNY}
  curve: {by: '07:30', zone: Europe/London, calendar: GBLO}
```

#### 6.3 Server settings (`drishti.loads.*`)

Also listed in [CONFIGURATION.md](../admin/CONFIGURATION.md#drishtiloads--data-loads-a-batch-landed).

Every setting of this part, with its type, default, environment variable, meaning and whether a change needs a restart, is in the one configuration document: [CONFIGURATION.md, `drishti.loads`](../admin/CONFIGURATION.md#drishtiloads) (also at Help → Configuration in the console).

### 7. What happens, step by step

#### On `ready`

| Step | What it does | Result and how it shows |
|---|---|---|
| `record` | writes the announcement to the history (once per key) | always `ok`; the detail says `recorded`, `recorded as attempt N` or `recorded as a reload of an earlier ready load` |
| `refresh` | calls `purgeCaches()` on every connector that serves the kind, and drops the engine's layout caches: the same machinery as Admin → Caches, scoped to the kind. A connector refreshed less than `drishti.loads.refresh-window` (5 s) ago, or being refreshed, is not purged again: the signal joins one more refresh at the end of the window, and the connector only looks for its newest table version now, so the steps below still see the new data. A backfill of 60 dates refreshes each connector once or twice, not 60 times | `ok` with the connectors named (`caches dropped: …`, and `merged with a refresh moments ago: … (their caches are dropped once more in N s)`); `failed` with the error if a purge threw |
| `verify` | lists the kind's entities on the business date through the router and counts them | `ok` with the count; `warn` when none were found (`verified` becomes `not-found`) or when some sources could not answer (they are named); `failed` if the read threw |
| `alerts` | evaluates every enabled alert rule whose entity is of this kind against the entity **on that business date**, for every user, each as that user may see the data (field masks apply). A rule's remembered state is cleared first, so a condition that holds on the date fires once | `ok`: `N alerts fired`; `skipped` when the data is not readable for the date |
| `smoke` | opens the first N entities of the verify listing as views on that date (N = the pack's `smoke`) | `ok`: `opened 2 of 2 sample entities`; `failed` names the first failures; `skipped` when `smoke` is 0 |
| `late` | clears the late-data flag of this kind and date, if there was one | present only when a flag was cleared |
| `notices` | one inbox row and a bell each for the people the pack names, and an email when `notify.email` is on | `ok`: `N people told`; `skipped`: nobody with those roles may open the kind |

After the steps, the **BI layers** that read the kind are brought up to date for the business date. A table whose stored partition for
the date was built from the data its connector holds now (the connector can say its data version: Delta, Iceberg and the others with
`dataVersion`) is left alone, so a signal that repeats a load changes nothing. Otherwise the date's partition is invalidated (its metadata
removed; a build writes a new file and a page reading the old one finishes on it) and, when a page has read that date, rebuilt in the
background (a date nobody has read is built when a page asks for it, not by every signal of a backfill): one rebuild
running per table and at most one waiting per date, two tables' rebuilds at once in the whole server, and a waiting one runs only if the
data changed since the one before it. A partition is built by one build at a time (on demand by a page, in the background, or as a
table's latest), and an invalidation never deletes a build's files. A dataset that refreshes `after-load` gets one refresh running and one
waiting with every signalled date, each date once, and skips a date whose layers hold the data held now. So a burst of thousands of
signals for the same data (QA3B-07) reads each date at most once more and the server settles when the burst ends.

The notice text is the `summary`: `trade for 2026-10-06 loaded: 48,213 rows, 12 rejected; 3 alerts fired` (a reload says `reloaded`; a
not-found says `; NOT FOUND in the data`). A notice holds only counts and names, never a data value, and a reader sees it only while their
roles open the kind. Opening it goes to the Data loads page for administrators.

#### On `failed`

`record` (always), `late` (skipped: a failed load does not clear a late flag) and `notices` (`var load for 2026-10-05 FAILED: copy stopped: disk
full`). Nothing is refreshed, verified or evaluated: the data is not there. The date shows **failed** in the expectations until a `ready` arrives.

Every announcement is also written to the audit log (`data-load-ready` or `data-load-failed`, with the kind, date, batch, the verified state,
alerts and people told); a token write is audited as `token-write` as well.

### 8. Expectations and late data

Every `check-interval` the scheduler looks, for each expected kind, at **today** (when it is a business day of the kind's calendar, in the
kind's zone) and at the previous `lookback-days` business days:

| State | Meaning |
|---|---|
| `pending` | today, and the deadline has not passed |
| `on-time` | a `ready` load arrived by the deadline |
| `landed-late` | a `ready` load arrived, after the deadline |
| `late` | today, the deadline has passed, nothing `ready` yet |
| `missing` | an earlier business day in the window, nothing `ready` ever landed |
| `failed` | the newest announcement for the date says `failed` |

`late` and `missing` are flagged **once**: a `load-late` notice to the people the pack names (`trade for 2026-10-06 is LATE: expected by 19:00
America/New_York`) and a line on Admin → Health that makes the overall status `DEGRADED`. The flag is persisted, so a restart does not tell
again, and it clears when a `ready` load lands (or the date leaves the window). Holidays and weekends of the kind's calendar have no expectation.
The check uses an injected clock in tests; in production it is the server's clock in the kind's own zone.

![The expectations of a pack and their state](img/loads/02-expectations.jpg)

![Admin → Health lists the data that is late](img/loads/05-health-late.jpg)

### 9. The Data loads page

> **Where to find it.** Admin menu → **Data loads** (`/admin/loads`) shows every loaded pack at a glance: today's
> expectations (on time, late, missing) and its latest loads, with a banner when something needs attention. **Open** on
> a pack (or the **Data loads** button on its row in Admin → Packs) goes to that pack's page with the full history,
> each load's steps and the expectation settings.

Admin → Packs → **Data loads** on a loaded pack's row (`/admin/packs/{pack}/loads`; administrators only).

![The Data loads page: expectations with their state, and the history](img/loads/01-data-loads-history.jpg)

* **Expectations** lists each expected kind for today and the look-back days with its deadline, state (`on-time`, `landed-late`, `late`,
  `missing`, `pending`, `failed`; every state has its word, colour is only a hint) and when it landed.
* **History** lists the loads, newest first: received (and by whom), kind and date, status (`reload`, `attempt N` badges), rows and rejected,
  source and batch, verified (and the count), alerts fired, people told, and the steps. Filters (kind, status, date) are a plain form that works
  without scripts; the tables also sort, filter and page.
* **Steps** opens a load's results, each with its own status and time.

![One load's step results](img/loads/03-load-steps.jpg)

* **Expectations settings** edits the override (roles, users, email, smoke, one row per expected kind) and can reset it to the pack's own.
* **Announce a load** tells Drishti a batch landed when no ETL can (a load re-run by hand, a test): kind (the pack's), business
  date, status, and optionally rows, batch id and note. It makes the same `POST /api/v1/packs/{pack}/loads` an ETL makes, as you, with
  `source: console`, and is audited as `load-announced` besides the load's own record. From a terminal the same is
  `drishti.py data landed --pack P --kind K --date D`.

![The expectations settings](img/loads/04-settings.jpg)

Keyboard: `/` focuses the kind filter, `e` opens the settings, `Esc` closes the dialog; every control is reachable by Tab and has a label.
Phone width: the page fits without sideways scrolling (the tables scroll inside their own frame) and controls are touch sized. It follows the
light, dark and high-contrast themes.

![The page at phone width](img/loads/06-phone.jpg)

### 10. Permissions

| Caller | May announce a load | May read loads | May change the expectation settings |
|---|---|---|---|
| administrator (session or token) | yes (a token needs `loads:write`) | yes | yes (never with a token) |
| personal API token with `loads:write` | yes, **only for packs whose kinds the token's user's roles open**, and only for kinds they may open | yes | no |
| personal API token with the default `read` scope | no (`403`: *this token lacks the scope loads:write*) | the loads of kinds its user may open | no |
| any other user | no | the loads of kinds they may open | no |

A scope is a door, not a right: the call is checked against the roles the token's user holds **now**. Every announcement by a token is audited
(`token-write`, with the token's id, never its secret) and so is each refusal (`token-denied`).

**An ETL job's least-privilege token.** Make a role that opens only what the job loads, a user that holds it, and a token with only
`loads:write`, which must expire (`drishti.security.token-write-max-days`, 90 by default):

```
# 1. a role that opens only the kinds the job loads
curl -X PUT $S/api/v1/admin/role-definitions/risk-etl -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
     -d '{"description":"risk ETL: announces loads","kinds":["stress-scenario","stress-result"]}'
# 2. a user that holds it (a service account; no human uses its password)
curl -X POST $S/api/v1/admin/users -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
     -d '{"username":"risk-etl","roles":["risk-etl"],"password":"a-long-random-password"}'
# 3. sign in as that user (or use My account -> API tokens) and create the token with only the load scope
curl -X POST $S/api/v1/me/tokens -H "Authorization: Bearer $RISK_ETL_SESSION" -H 'Content-Type: application/json' \
     -d '{"name":"nightly-risk-etl","days":90,"scopes":["loads:write"]}'
# -> {"secret":"drk_...", ...}   shown once: put it in your scheduler's secret store, as DRISHTI_TOKEN
```

The same token announces [`stress-scenario`](../contracts/samples/market-risk.md#stress-scenario) ([contract](../contracts/packs/market-risk.md#scn-stress-scenario-stress-scenario)) and [`stress-result`](../contracts/samples/market-risk.md#stress-result) ([contract](../contracts/packs/market-risk.md#str-stress-result-stress-result)) and nothing else: a call for another kind is `403`, for a pack it cannot reach `404 DRS-5011`,
and it cannot change expectations, users, tokens or packs.

### 11. Recipes

All of them end the same way: **after the data is committed**, one call. Put it in the job's success path, never before the copy finishes.

#### 11.1 curl

```bash
curl -fsS -X POST "$DRISHTI/api/v1/packs/my-bank/loads" \
  -H "Authorization: Bearer $DRISHTI_TOKEN" -H 'Content-Type: application/json' \
  -d "{\"kind\":\"trade\",\"businessDate\":\"$BIZ_DATE\",\"rows\":$ROWS,\"rejected\":$REJECTED,\"source\":\"eod-copy\",\"batchId\":\"$RUN_ID\"}"
```

`-f` makes a refusal a non-zero exit; a retry with the same `RUN_ID` is a no-op.

#### 11.2 Python (`requests`)

```python
import os, requests

def announce(pack, kind, date, rows=None, rejected=None, batch=None, status="ready", note=None):
    body = {"kind": kind, "businessDate": date, "status": status}
    body.update({k: v for k, v in (("rows", rows), ("rejected", rejected), ("batchId", batch), ("note", note)) if v is not None})
    r = requests.post(f"{os.environ['DRISHTI']}/api/v1/packs/{pack}/loads", json=body, timeout=60,
                      headers={"Authorization": f"Bearer {os.environ['DRISHTI_TOKEN']}"})
    r.raise_for_status()                       # 4xx: the body has the DRS code and what to fix
    load = r.json()
    if status == "ready" and load["verified"] != "verified":
        raise RuntimeError(f"landed but not readable: {load['summary']}")
    return load
```

#### 11.3 Airflow

The announcement is the last task of the DAG; `trigger_rule` makes the failure announcement run when the load fails.

```python
from airflow.decorators import dag, task
from airflow.utils.trigger_rule import TriggerRule
import pendulum

@dag(schedule="0 18 * * 1-5", start_date=pendulum.datetime(2026, 1, 1, tz="America/New_York"), catchup=False)
def eod_trades():
    @task
    def load_trades(ds=None):
        ...                                     # your copy / COPY INTO / spark job; return the counts
        return {"rows": 48213, "rejected": 12}

    @task
    def announce_landed(counts, ds=None, run_id=None):
        from drishti_loads import announce        # the helper of 11.2
        announce("my-bank", "trade", ds, counts["rows"], counts["rejected"], batch=run_id, source="airflow:eod_trades")

    @task(trigger_rule=TriggerRule.ONE_FAILED)
    def announce_failed(ds=None, run_id=None):
        from drishti_loads import announce
        announce("my-bank", "trade", ds, batch=run_id, status="failed", note="eod_trades failed; see Airflow", source="airflow:eod_trades")

    c = load_trades()
    announce_landed(c)
    c >> announce_failed()

eod_trades()
```

Or, with the CLI on the worker: `BashOperator(bash_command="python /opt/drishti/tools/drishti.py data landed --pack my-bank --kind trade --date {{ ds }} --batch {{ run_id }}")`
(`DRISHTI_SERVER` and `DRISHTI_TOKEN` come from the worker's environment or an Airflow connection; the task fails when the data is not verified).

#### 11.4 cron or a shell script after a copy

```bash
#!/bin/bash
set -euo pipefail
DATE=$(date +%F)
rsync -a --delete "/exports/trades/$DATE/" "/mnt/lake/trade/date=$DATE/"        # your copy
ROWS=$(cat "/exports/trades/$DATE/_rows")
if python /opt/drishti/tools/drishti.py data landed --pack my-bank --kind trade --date "$DATE" --rows "$ROWS" \
      --batch "eod-$DATE" --source "cron:$(hostname)"; then
  :                                                                              # landed, verified, people told
else
  echo "landed but not verified (exit $?): check the connector's root and dates" >&2
fi
# in a trap on error:  drishti.py data landed ... --status failed --note "copy failed on $(hostname)"
```

```
15 18 * * 1-5  DRISHTI_SERVER=https://drishti.example.com DRISHTI_TOKEN=$(cat /etc/drishti/etl.token) /opt/etl/eod_trades.sh
```

#### 11.5 DishtaYantra

DishtaYantra (the author's pipeline engine, a separate project) is the natural ETL for an EOD batch (its
`docs/BATCH_FILE_PROCESSING.md` and `docs/HEADLESS_AND_ORCHESTRATION.md`). The extension point is **the end of the batch**, after its sinks
have committed; two ways to use it, neither changing DishtaYantra:

**(a) A headless batch job plus a final step (recommended).** `python -m core.dag.headless_runner --job eod_job.json --summary out/summary.json`
runs the DAGs to completion, drains every publisher and the write-ahead log, writes the summary (per DAG: `completed_by`, `published`,
`drained`, ...) and exits `0` on success and `1` when incomplete or on error. The scheduler (or the job's wrapper) then makes the one call:

```bash
python -m core.dag.headless_runner --job eod_job.json --summary out/summary.json
status=$?
ROWS=$(python - <<'EOF'
import json; s = json.load(open("out/summary.json"))
dags = s.get("dags", s)                       # check the shape of your summary file once
print(sum(int(d.get("published", 0)) for d in (dags.values() if isinstance(dags, dict) else dags)))
EOF
)
if [ $status -eq 0 ]; then
  python /opt/drishti/tools/drishti.py data landed --pack my-bank --kind trade --date "$BIZ_DATE" --rows "$ROWS" --batch "$JOB_ID" --source dishtayantra
else
  python /opt/drishti/tools/drishti.py data landed --pack my-bank --kind trade --date "$BIZ_DATE" --status failed --batch "$JOB_ID" --note "headless job exited $status"
fi
```

**(b) A final `delegate://` sink as a hook.** A DishtaYantra publisher can be your own class
(`delegate_module`, `delegate_class`, `delegate_config`; the contract is a constructor taking `name` and `config`, and `publish(data)`; see its
`docs/TUTORIAL_delegate_connectors.md`). Give the DAG a last node that emits one *batch complete* marker record **after** the real sink has
committed, and route only that marker to this class:

```python
# drishti_landed_sink.py  (ordinary Python; imports nothing from DishtaYantra)
import json, urllib.request

class DrishtiLandedSink:
    def __init__(self, name, config):
        self.url = config["drishti"].rstrip("/") + f"/api/v1/packs/{config['pack']}/loads"
        self.token = config["token"]
        self.kind = config["kind"]

    def publish(self, data):                         # one marker record: {"businessDate": "...", "rows": N, "rejected": N, "batchId": "..."}
        body = {"kind": self.kind, "businessDate": data["businessDate"], "rows": data.get("rows"), "rejected": data.get("rejected"),
                "batchId": data.get("batchId"), "source": "dishtayantra"}
        req = urllib.request.Request(self.url, data=json.dumps({k: v for k, v in body.items() if v is not None}).encode(), method="POST",
                                     headers={"Content-Type": "application/json", "Authorization": "Bearer " + self.token})
        with urllib.request.urlopen(req, timeout=60) as r:       # raise, do not swallow: a refused announcement must be seen
            r.read()
```

Because the call is idempotent per batch id, DishtaYantra's replays and retries cannot announce a batch twice.

### 12. Troubleshooting

| You see | Cause | What to do |
|---|---|---|
| `403 ... this token lacks the scope loads:write` | the token was made with the default `read` scope | make a token with `scopes: ["loads:write"]` (My account → API tokens, tick *loads:write*) |
| `403 ... may not open trade data` | the token's user has no role that opens the kind | give the service user a role that opens it (section 10) |
| `403 ... only an administrator, or a personal API token with the loads:write scope` | a session of a non-administrator | use a token, or an administrator |
| `404 DRS-5011 no loaded pack named ...` | wrong pack name, the pack is not loaded or switched off, or the caller can open none of its kinds | `GET /api/v1/packs` for the names; Admin → Packs |
| `422 DRS-5012 pack ... has no kind ...` | a typo, or the kind belongs to another pack | the message lists the pack's kinds |
| `400 DRS-4003 ... is in the future` | the business date is after today in the server's zone | send the date the data is *for* |
| `"verified": "not-found"` / CLI exit 1 | the connector cannot read that date: wrong root, the date folder or partition is not written yet, a stale cache on another server | check Admin → Connectors → the connector → Test connection (or `drishti.py connector test NAME`); on a multi-server setup announce on every server (section 13) |
| `refresh ... no connector serves trade` | the kind is served by no connector of this server | the connector that serves the kind is not configured on this server: Admin → Packs → Data source marks it *not configured* (the kind answers `DRS-1011`); create its file in Admin → Connectors |
| `alerts ... skipped` | the data is not readable for the date (see `verify`) | fix verify first |
| `notices ... no one to tell` | nobody with the named roles may open the kind, or the roles/users are misspelt | Expectations settings; the roles must exist and open the kind |
| a load is `late` although it landed | the load was announced for another business date, another kind, or `failed` was announced last | History: the dates and the newest status per kind and date |
| `data late` stays on Health | nothing `ready` landed for that date, or the date is still inside the look-back window | announce the load; or let it leave the window (`lookback-days`) |
| no email arrives | `notify.email` is off, the email channel is off, the person has no address, turned the `load` event off, or hit the hourly cap | Admin → Collaboration (outbox, dead letters); the person's Settings → Notifications |
| the settings page says *settings from the defaults* | no `loads:` section in the pack and no override | add one in the pack, or save the settings |

### 13. FAQ

**Why does Drishti not ingest the data itself?** Because it is the other half of a system you already have. Your ETL knows when a batch is
complete and correct; Drishti knows who needs to hear about it and what the data means. Keeping them separate means a Drishti restart can
never lose a load, and your ETL can change engine without touching Drishti. `data ingest`, `--watch` and `pack make` are for staging, small
setups and proofs of concept.

**Can I announce the same batch again?** Yes: the same pack, kind, date and batch id with the same outcome is a no-op (`duplicate: true`).
Use that for retries. To *replace* a batch, announce it again with a new batch id (or different counts): it is recorded as a reload and the
steps run again.

**How do I backfill many dates?** One call per kind and date, oldest first, from a loop. A backfill is a load like any other, so each date
refreshes caches, verifies and evaluates alerts, and tells the people the pack names: expect one notice per date (pass `--note backfill`
so the history says why). To keep a large backfill quiet, set `notify.users` to a service user for its duration in the Expectations settings, and
reset it afterwards.

**What about holidays and weekends?** An expectation is only made for a business day of the kind's calendar (`calendar`, e.g. `USNY` or
`GBLO`), so a bank holiday is never "late". A load you announce for a weekend or holiday date is accepted as given.

**Several kinds land together.** Announce each kind: the history, expectations and notices are per kind.

**Several Drishti servers read the same lake.** Each server caches on its own. In a Drishti\*net cluster, announce on any one
member: a `ready` load is passed on once to every other live member (signed, as you; the answer lists them in `forwardedTo`), each runs
its own refresh, and a member that received it from a peer never passes it on again (decision PO-5). A member that does not answer is
named in the server's log: announce on it by hand. Without a cluster, announce on each server (the call is cheap and idempotent).

**Does the signal change any data?** No. It drops caches, reads, evaluates and notifies. The alert evaluation does store the alerts it fires,
in the owners' alert history, as it would when the data ticked.

**What if the announcement call itself fails?** Your job should retry with the same batch id and, if it still fails, alert you: the data is
there, Drishti simply has not been told. Until it is, expectations will (rightly) flag the load as late.

## 2. Data formats: JSON Lines is the standard

**JSON Lines (JSONL) is Drishti's data format**: one JSON object per line, UTF-8, in a `.jsonl` file (`.jsonl.gz` where
the repository gzips large files). Every generator writes it, every loader reads it, and every export and download offers it. This
page says what that means for data coming in, data going out, and the files Drishti keeps.

### 1. The rule

- **One JSON object per line.** No array around the lines, no commas between them, no blank lines needed (a blank line is skipped).
  A document may be as deep as it likes, as long as it stays on its line.
- **UTF-8**, with or without gzip (`.jsonl.gz`).
- **Generating and loading are separate steps.** A generator writes JSONL; a loader puts it into a store. `tools/packgen/trades/make_trades.py`
  is the model: it writes `trade_<system>.<date>.jsonl.gz` (the load rules' name), then `lake build` loads them, each under its name's date. Running a loader never rewrites a pack's samples,
  its Sutra tests or the sample feed.

### 2. The row forms

| Form | One line is | Written by | Read by |
|---|---|---|---|
| **Document** | one entity, as its source holds it: `{"TRADE_ID": "1000015", "LEGS": [...], ...}` | `make_trades.py`, `bulk_trades.py`, `comswap_synth.py`, `note_synth.py`, every pack's `samples/<kind>.jsonl` | `lake build`, `build_demo_lakes.py`, `ingest_jsonl.py`, the file connector, the demo connector |
| **Loader row** | `{"domain", "kind", "id", "date", "doc", "columns"}`: a document (`doc`, as JSON text) of a kind on a business date, with the fields its pack promotes; kept as it is, but stored under the business date of its file by the load rules ([DATA_LAKE_GUIDE.md](DATA_LAKE_GUIDE.md#5-routing-business-dates-modes-repeats-and-undo)), never its own `date` (a row whose `date` differs is counted) | `make_data.py --jsonl`, `bulk_trades.py --jsonl`: tars of `<kind>/<YYYY-MM-DD>/rows.jsonl` folders | the Java loaders (PostgreSQL, Cassandra, Iceberg, DuckDB, Redis, MongoDB, Aerospike, the file connector's `JsonlLoader`) |
| **Lake row** | `{"domain", "kind", "table", "id", "date", "doc"}`: the same, naming the Delta table (`trade_endur`); kept as it is, but stored under the business date of its file by the load rules ([DATA_LAKE_GUIDE.md](DATA_LAKE_GUIDE.md#5-routing-business-dates-modes-repeats-and-undo)), never its own `date` (a row whose `date` differs is counted) | `make_data.py --lake`, `samplegen/lake.py`, the pack generators' `--lake`: tars of `<table>/<YYYY-MM-DD>/rows.jsonl` | `tools/samplegen/load_rows.py` |
| **Sutra test case** | `{"case": "<name>", "document": {...}}` | every generator of Sutra tests (`tests/<sutra>/cases.jsonl`, beside `expect.yaml`) | `sutra test`; the older one-`.json`-a-case folders still read, with a warning |
| **Catalogue entry** | `{"kind", "id", "title", "subtitle"}` | every pack generator (`samples/catalog.jsonl`; the `catalog.json` array of earlier releases is still read) | the demo connector, the pack tools |
| **Staged lake row** | `{"id", "doc", "columns"}` | `lake build`'s staging (`<lake>/_drishti-lake/staging`), the official results' publishers | Drishti's Java lake writer (`LakeWriter`; the earlier `[id, doc, columns]` array a line still read) |
| **Seal** | `{"key", "count", "hash"}` | the collaboration file store (`<collab dir>/seals.jsonl`) | the same store (the tab-separated `seals.log` of earlier releases still read, never rewritten) |

A **restatement** is a second file of lake rows (`<name>-restated.tar`): its rows replace the rows of the same id on their date, as a
second commit, so *known at* shows the correction.

Where the generators put their output:

| Generator | Output |
|---|---|
| `tools/packgen/banking/make_data.py` | `config/packs/<domain>/<pack>/samples/<kind>.jsonl`; `--lake ROOT`: `<ROOT>/../build/lake-rows/banking-history.tar` and `banking-restated.tar`, then loaded (`--no-load` stops there); `--jsonl FILE`: loader rows |
| `tools/samplegen/bulk_trades.py` | `<data>/build/bulk/<system>/trade_<system>.<date>.jsonl.gz`, then `lake build` (`--no-lake` stops there) |
| `tools/packgen/trades/make_trades.py` | `<data>/build/trades/<system>/trade_<system>.<date>.jsonl.gz`, then `lake build` with `--lake` |
| `tools/realdata/normalise.py` | `tools/realdata/dataset/*.jsonl` (a country, group, series or Factbook profile a line) and the `meta.json` manifest |
| the sample feed | `<data>/feeds/fixing.jsonl` (one fixing series a line: `FIX SOFR-HISTORY`) |

### 3. Reading: JSONL first, JSON still, CSV deprecated

The readers keep what they accept: the file connector reads `<kind>.jsonl` (dated `<yyyy-MM-dd>/<kind>.jsonl`) and one file per
entity (`<kind>/<id>.json`); `lake build`, `build_demo_lakes.py`, `data ingest` and the Java loaders read JSON Lines and JSON arrays
(gzipped or not), and `lake build`, `build_demo_lakes.py`, `load_rows.py`, `convert` and the parallel lake loader read them inside a
`.zip`, `.tar.gz`, `.tgz` or `.tar` archive too ([section 7](#7-archives-zip-and-tarballs)); the demo connector reads
`samples/<kind>.jsonl` and the older `samples/<kind>/<id>.json`.

**CSV input is deprecated** and will be removed in a later release. Today only the file connector reads CSV (a feed file
`<kind>/<id>.csv`, served as `{"rows": [...]}`): each such file logs one warning and is named in **Admin → Health**
(`UP (CSV input is deprecated: fixing/X.csv (convert to JSON Lines: drishti.py convert))`) until it is gone.

**`drishti.py convert FILE [--out X.jsonl]`** turns CSV, a JSON array (or one object) or a folder of per-entity `.json` files into
JSON Lines ([CLI_REFERENCE.md](CLI_REFERENCE.md#convert-csv-a-json-array-or-a-folder-of-json-files-into-json-lines)):

```bash
drishti-console/.venv/bin/python tools/drishti.py convert trades.csv                    # trades.jsonl, a line per row
drishti-console/.venv/bin/python tools/drishti.py convert data/feeds/fixing/SOFR-HISTORY.csv --rows-document --out data/feeds/fixing.jsonl
drishti-console/.venv/bin/python tools/drishti.py convert data/feeds/settlement          # settlement.jsonl, the file name as "id"
```

### 4. Going out: JSON Lines beside every export

Every export and download offers JSON Lines beside the formats it already has: **CSV, Excel, PDF and PNG all stay**, and CSV downloads
are a normal option (the deprecation is for data coming in). A JSONL download is **one row a line, keyed by the same column names
as the CSV's header**, values typed (numbers stay numbers, an empty cell is `null`), with no byte-order mark and no spreadsheet escaping.

| Where | JSON Lines |
|---|---|
| Server API | `GET /api/v1/search/jsonl`; `POST /api/v1/bi/sql` `format: jsonl`; `GET /bi/runs/{id}/result?format=jsonl`, `POST /bi/runs/{id}/export {format: jsonl}`; `POST /api/v1/export/jsonl` (reports, visuals, views); statements `format: jsonl`; subscriptions `format: jsonl` (attached); scheduled reports `format: jsonl` (a webhook gets `application/jsonl`, a folder a `.jsonl` file); `exposure/scope/profile?format=jsonl`, `exposure/captures/{id}/jsonl`, `var/captures/{id}/jsonl`. The media type is `application/jsonl`; a cluster forwards `/search/jsonl` as it does `/search/csv` |
| Console | the **E** export of a table's chart, Pivot, BI runs, a BI report's Export bar, the statement editor, a view's Export menu, a panel's **{}** icon, the history page's **E** menu, search and form results, the exposure explorer (profile and captures), the VaR explorer (the path and captures), the Subscribe form's *Send as* and the scheduled report's *Format*; routes `/export/search.jsonl`, `/export/grid.jsonl`, `/export/compare/...jsonl`, `/export/<kind>/<id>/<panel>.jsonl`, `/export/history/...jsonl`, `/dist/bi/<id>.jsonl`, `/dist/view/<kind>/<id>.jsonl` |
| Command line | `drishti.py history ... --jsonl`, `drishti.py exposure profile ... --jsonl`, `bi runs result --format jsonl`, `bi runs export --format jsonl`, `bi sql --format jsonl`, `bi statement file --format jsonl` |

The compliance export's files are `.jsonl` (they were JSON Lines named `.ndjson`).

**Official results.** VaR and exposure keep publishing their official end-of-day results to Delta (`risk-results/var-official`,
`exposure-official`, `exposure-official-cpty`), and each published date is also written as JSON Lines beside them:
`<root>/risk-results/jsonl/<table>/<date>/<table>-v<table version>.jsonl`, one row a line, one file per commit (a restatement is a new
file). The setting is `drishti.var.official.jsonl` and `drishti.ccr.exposure.official.jsonl`, on by default
([CONFIGURATION.md](../admin/CONFIGURATION.md)). In the content store the row lists are JSON Lines too: `var/official/<date>-membership-v<n>.jsonl`
and `exposure/official/<date>-trades-v<n>.jsonl` (a header line, then a row a line). Records written as `.json` keep their `.json`
and are read as they are: the store is append-only, nothing is rewritten. A single record or capture stays one JSON document.

### 5. What stays as it is, and the guard

Not data, so not JSONL, and they stay as they are: manifests (`testdata.yaml`, `MANIFEST.json`, `meta.json`), application state files,
map GeoJSON, run reports, and storage internals (Delta and Iceberg Parquet, the BI store, the hot files, Arrow between the server
and Python). `tools/iceberg/fixtures.py` writes real Iceberg tables on purpose: it tests the Iceberg reader itself. Human-facing
files stay too: the pack translation sheet (`pack i18n export`, CSV), and every CSV, Excel, PDF and PNG download.

The drill guards the rule: `tools/test_generators_write_jsonl.py` runs the cheap generators into a temporary folder and checks every
data file they write is JSON Lines, and reads the source of every generator for a Delta, Parquet or CSV writer or a `.json`, `.csv`,
`.tsv`, `.parquet` or `.ndjson` data file; its allow-list holds the non-data names above (`MANIFEST.json`, `meta.json`,
`cache-index.json`, `trackers.json`, `expect.yaml` …) and the loaders. `tools/test_exports_offer_jsonl.py` checks that every export
entry point (server, console route, UI menu, command line) offers JSON Lines.

### 6. Arrow to the browser, where it pays

JSON Lines is the data standard (above); **Apache Arrow** is the in-memory and wire format where it pays:
it reaches the browser too, for the answers it was measured to win on, and nowhere else.

**Where Arrow is used.**

| Path | Arrow | Since |
|---|---|---|
| Java to the Python compute workers | Arrow IPC files, memory-mapped | built ([NOTEBOOK_GUIDE.md](NOTEBOOK_GUIDE.md)) |
| BI answers to the console's Python, Explore and Perspective-era clients | `?format=arrow` on `POST /bi/reports/{id}/query` and `POST /bi/mq`: the raw rows | 2.1 |
| **Any JSON answer of the two endpoints below, to any client that asks** | `Accept: application/vnd.apache.arrow.stream`: the JSON answer itself, its table as the record batch, the rest in the schema's metadata | built |
| **The VaR explorer's contribution tables by position and by trade, in the browser** | read with Arrow JS (vendored) into typed columns | built |
| **Any program: Python, Java, JDBC tools, any Arrow Flight client** | Arrow Flight on its own port: kinds, models, pivot views, VaR vectors, the in-memory lake, streams ([FLIGHT_GUIDE.md](FLIGHT_GUIDE.md)) | built |

**Where it deliberately is not.** The **VaR hot files** stay Drishti's own layout (float32 position rows in hierarchy order, memory-mapped,
float64 node totals): summing vectors runs near the machine's memory bandwidth, which Arrow's framing would not improve. **The lake** stays
Parquet (Delta or Iceberg), Arrow's partner on disk. **Shard partials** between servers stay raw vectors of doubles. **Entity pages and
small answers** stay JSON. And the measurements below kept three browser payloads on JSON: **the BI grid**, **the VaR P&L path** and **the
search page**.

**How it works.** The server's `ArrowAnswers` writes the stream from a JSON answer with the official Apache Arrow Java library
(`ArrowIpcWriter`, the same library as the compute workers' exchange and Arrow Flight):

- the answer's table is the record batch: `rows` named by `columns` (`{"shape": "rows"}`), or a list of objects (`{"shape": "records"}`,
  with `absent` naming the keys some objects leave out, so the rebuilt object leaves them out too);
- everything else in the answer (`columns`, `meta`, `totals`, `var`, `sumComponent`, notes...) is JSON in the schema's metadata,
  `drishti.answer`; `drishti.table` says where the table goes back;
- cells are text, 64-bit integers, doubles or booleans, with nulls; text that repeats (at most half as many distinct values as cells,
  16 cells or more: desks, books, currencies, dates) is a **dictionary** column, each value once and 4-byte indices;
- an answer holding anything else (an object or a list in a cell, a column mixing text and numbers, a NaN, a key both null and left out)
  is sent as JSON, with `200`: the caller reads the `Content-Type`.

The console passes the server's Arrow through unchanged and writes no Arrow of its own: when the server answers JSON (an answer given in
time by a BI run, a table Arrow is not written with) the browser gets that JSON, which `Drishti.fetchTable` reads as well. A Python
program reads Drishti's data through Arrow Flight ([FLIGHT_GUIDE.md](FLIGHT_GUIDE.md)). In the browser, **`Drishti.fetchTable(url, opts)`** (`/static/js/drishti_table.js`) asks with
`Accept: application/vnd.apache.arrow.stream, application/json;q=0.9` when the page loaded Arrow JS and `application/json` otherwise,
and returns one column-oriented table whichever format came back: `names`, `length`, `column(name)` (a `Float64Array` for numbers, NaN
for a null; an array for text), `value(name, i)` (the cell as JSON has it), `answer()` (the whole JSON answer, equal cell for cell). Types:
a 64-bit integer becomes a Number when every value of its column is within 2^53 and text past it (JSON would round it); a decimal becomes
exact text; a date `YYYY-MM-DD`; a timestamp ISO text, with `Z` when the column has a time zone and without when it has none. `?tables=json`
in a page's address makes it ask JSON, to compare or to rule Arrow out of a problem. Arrow JS is **Apache Arrow JS 21.1.0**, vendored
(`/static/vendor/apache-arrow/21.1.0/`, 178,919 bytes, 47,797 gzip; [VENDOR.md](../../drishti-console/web/static/vendor/VENDOR.md)),
loaded only by the VaR explorer; nothing comes from a CDN.

**Measured** (2026-10-30; one machine, Chromium through Playwright, a scratch server and console on free ports; bytes as the console sends
them (it does not compress) and gzip -6 as a proxy in front of it would; parse is the median of 7 in the page: `JSON.parse` against reading
the stream and rebuilding the answer; click-to-painted the median of 5 after a warm one, from the click to every row in the page):

| Payload | Rows | JSON bytes (gzip) | Arrow bytes (gzip) | Parse JSON / Arrow | Click-to-painted JSON / Arrow | Kept |
|---|---:|---:|---:|---:|---:|---|
| VaR contributions of a desk **by trade** | 1,281 | 631,873 (96,318) | **244,304 (81,628)**, −61% (−15%) | 0.6 / 1.9 ms | 199 / **168 ms** | **Arrow** |
| VaR contributions of a desk **by position** | 1,173 | 692,324 (87,571) | **243,392 (69,496)**, −65% (−21%) | 0.9 / 3.1 ms | 182 / 227 ms (the run's noise: ±30 ms) | **Arrow** |
| VaR contributions by child (a desk's books) | 3 | 1,081 (463) | 2,304 (933) | 0 / 0.2 ms | 59 ms | JSON |
| VaR P&L path, the bank | 509 points | 21,607 (5,791) | 22,032 (7,312) | 0.1 / 0.4 ms | 132 ms | JSON |
| VaR P&L path, a desk | 509 points | 21,846 (5,733) | 22,360 (7,241) | 0 / 0.2 ms | 46 ms | JSON |
| VaR paths of a node's children (fetched ahead) | 2 scopes | 43,350 (11,427) | 44,608 (14,435) | 0 / 0.6 ms | (ahead of the click) | JSON |
| BI grid, a table visual, 5,000 trades × 9 columns | 5,000 | 536,160 (122,953) | 344,280 (112,656), −36% (−8%) | 1.0 / 2.1 ms | 874 / 1,324 ms | JSON |
| BI grid, 50,000 trades × 9 columns (json-max-rows raised for the test) | 50,000 | 5,355,611 (1,216,777) | 3,394,184 (1,048,434), −37% (−14%) | 9.5 / 12.7 ms | 5,981 / 5,559 ms | JSON |
| Search, `TRD limit 1000` (the most one search returns): the page as HTML, and its rows as JSON and as Arrow | 1,000 | HTML page 680,347 (66,836); rows as JSON 124,174 (21,152) | 73,752 (18,312) | | page 542 ms | HTML |

What the numbers say. **The contribution tables by position and by trade** are mostly numbers (component, incremental and marginal VaR,
quantities, PVs) with repeated text (books, currencies, products): Arrow carries them in 35 to 39% of the bytes, the fetch through the
console took 59 to 61 ms against 81 to 91 ms, and the page paints in the same time (the parse costs 1 to 2 ms more, the table's DOM
dominates). Over a bank's network, where 400 KB more is tens of milliseconds, that is the win, so they are Arrow. **The P&L path** is 509
numbers and their 509 dates with the answer around them (histogram, worst 25, a page of ranked rows) in JSON: Arrow is no smaller and its
gzip is larger; it stays JSON. **The BI grid** is wide text (trade ids, desks, books, products, currencies, dates) and V8's `JSON.parse`
is very fast: with dictionaries Arrow is 36% smaller raw but only 8 to 14% gzip, parses slower, and the page (a run's answer converted on
the console, Arrow JS loaded) painted no sooner, slower at 5,000 rows; the paint of tens of thousands of rows is the DOM's, whatever the
format. It stays JSON (the report page was wired to Arrow on trial for the measurement, and the wiring removed); BI's `Accept` answer stays
for API clients that read Arrow. **Search** draws its results on the server as HTML (at most 1,000 rows a search): no table reaches the
browser as data, so there is nothing for Arrow to carry. The measurement says where the weight is: the 1,000-row page is 680 KB of HTML,
while its rows would be 124 KB as JSON and 74 KB as Arrow (gzip 21 and 18 KB). The saving is in drawing the table in the browser, not in
the format; that belongs with the client-side renderer, where Arrow can carry the rows.

Re-measure, one file at a time, from `drishti-console` (each starts its own scratch stack; the results are printed and added to the
file named):

```bash
DRISHTI_ARROW_BENCH=1 DRISHTI_ARROW_BENCH_OUT=/tmp/arrow-bench.json pytest -s tests/test_arrow_bench_var_browser.py   # VaR and search
DRISHTI_ARROW_BENCH=1 DRISHTI_ARROW_BENCH_ROWS=50000 pytest -s tests/test_arrow_bench_bi_browser.py                 # the BI grids
```

Equality is tested in `drishti-server` `ArrowAnswersTest`, `tests/test_arrow_answer.py` (the console, negotiation and
fallback) and `tests/test_arrow_browser.py` (the explorer drawn the same from Arrow and from JSON, without Arrow JS, on a phone, no other
host asked).

### 7. Archives: zip and tarballs

Archives (`.zip`, `.tar`, `.tar.gz`, `.tgz`), what each loader makes of them and the guards: [DATA_LAKE_GUIDE.md](DATA_LAKE_GUIDE.md), [section 11](DATA_LAKE_GUIDE.md#11-archives-zip-and-tar).

### 8. Feeds: the table and business date of a file

The table, business date and mode of every file, for every loader: [DATA_LAKE_GUIDE.md](DATA_LAKE_GUIDE.md), [section 5](DATA_LAKE_GUIDE.md#5-routing-business-dates-modes-repeats-and-undo).

## 3. Using Drishti with Databricks Delta Lake or Apache Iceberg

Lakes (one file per lake, `config/lakes/<lake>.yaml`), Delta and Iceberg, and reading a lakehouse Databricks, Spark, Trino or Snowflake already writes (by storage path, or Iceberg through Unity Catalog's REST endpoint), with the Delta features read and what is planned: [DATA_LAKE_GUIDE.md](DATA_LAKE_GUIDE.md), [section 10](DATA_LAKE_GUIDE.md#10-connecting-to-an-existing-lakehouse-databricks-unity-catalog). The connector settings: [DELTA_CONNECTOR.md](../../plugins/drishti-plugin-delta/README.md), [ICEBERG_CONNECTOR.md](../../plugins/drishti-plugin-iceberg/README.md).

## 4. External data: where it comes from and how to refresh it

Every external source of every pack (World Bank, IMF, UNDP, CIA Factbook, Yahoo Finance, Google News, ECB, US Treasury, NY Fed, SEC
EDGAR, Wikidata, Nasdaq Trader, GLEIF), where it comes from, its licence, which pack and kinds it feeds, and the data feed that pulls it
(`.sh` and `.ps1`) with examples, is its own guide: **[EXTERNAL_DATA.md](EXTERNAL_DATA.md)** (Help → External data).

## 5. Schema to pack: generate a pack from JSON Schema

A pack made from JSON Schema files, a folder of JSON Lines, or both (Build → New pack, or `drishti.py pack make`) has its own guide:
[BUILD_GUIDE.md](BUILD_GUIDE.md#8-the-pack-designer-build--new-pack).

## 6. Export data: download a dataset

Taking data **out** of Drishti, a dataset and a day at a time: `EXPORT <GO>` (Views → Export data, `/data`; `DATA` works too), the API under `/api/v1/data` and
`drishti.py data get`. It is download only: rows are never shown in a grid. The screen itself, panel by panel, is in
[USER_GUIDE.md (Export data)](USER_GUIDE.md#export-data).

### 1. What a dataset is

A dataset is a **stored kind** of a loaded pack that a connector serves ([`trade-valuation`](../contracts/samples/market-risk.md#trade-valuation) ([contract](../contracts/packs/market-risk.md#tval-trade-valuation-trade-valuation)), `trade`, [`netting-set`](../contracts/samples/counterparty-risk.md#netting-set) ([contract](../contracts/packs/counterparty-risk.md#nset-netting-set-netting-set))); computed kinds (VaR, EE
and PFE, the what-ifs) hold no rows and are not listed. For each the view shows:

| Fact | Where it comes from |
|---|---|
| source | the connector that serves the kind now, and the table its data contract names (`storage.table`) |
| format | the connector's plugin: Delta, Iceberg, JSON Lines (the file connector), Database (JDBC), DuckDB ... |
| dates held | every business date the kind's sources hold (the Delta log, Iceberg snapshots, the file layout); *not dated* for a source of current rows only |
| columns | the data contract's fields; a kind without a contract: the source's columns, else the fields of a few of its entities |
| internal only | the kind is in `drishti.data-view.internal-only` (the IMF, Yahoo and news-headline kinds, [EXTERNAL_DATA.md](EXTERNAL_DATA.md)) |

### 2. The selection

A selection is the **columns** (all by default) and any number of **filter lines**, every line joined by AND. It is deliberately not SQL:
no joins, no grouping, no expressions. A line is written the same way on the screen's address, in the API and in the CLI:

| Line | Operator | Meaning |
|---|---|---|
| `DESK = DESK-COMM` | equals | the value is exactly this |
| `STATUS != DEAD` | not equals | anything else (a row without the value does not match) |
| `PRODUCT_TYPE in ComSwap,ComFut` | one of | any of the values, separated by commas (OR within one attribute) |
| `NAME contains bank` | contains | the text holds it, ignoring case |
| `PORTFOLIO starts BOOK-COMM` | starts with | the text begins with it, ignoring case |
| `MTM > 1000000` | greater than | numbers compare as numbers, everything else (dates included) as text |
| `PNL_1D < 0` | less than | as above |
| `DV01 between 1 and 5` | between | both ends included (`1,5` works too) |
| `NETTING_SET is empty` | is empty | no value, or an empty text |
| `COUNTERPARTY is not empty` | is not empty | any value |

The words work too (`equals`, `not equals`, `one of`, `starts with`, `greater than`, `less than`); a value may be quoted
(`DESK = "DESK COMM"`). A line that cannot be read is refused before any row is read (`400 DRS-5001`, saying how to write it), as is a
column the dataset does not have. At most 50 lines.

The selection is read back two ways, both for reference only: in plain words (*From trade-valuation on 2026-09-30: 9 columns, rows where DESK
is DESK-COMM, PRODUCT_TYPE is one of ComSwap or ComFut, and MTM is greater than 1000000.*) and as a SQL-like line:

```sql
SELECT TRADE_ID, PRODUCT_TYPE, PORTFOLIO, DESK, COUNTERPARTY, NOTIONAL, MTM, PNL_1D, VALUATION_DATE
FROM trade-valuation  -- 2026-09-30
WHERE DESK = 'DESK-COMM' AND PRODUCT_TYPE IN ('ComSwap', 'ComFut') AND MTM > 1000000
```

### 3. How the rows are read: pushdown and streaming

The rows are **streamed** from the connector to the file, never held whole in memory, on the server or in the console:

- **Pushed down.** When the kind has one source and that source keeps every column the selection reads as a column (Delta Lake and Iceberg
  tables with promoted columns, DuckDB, JSON Lines files with column reads), only those columns are read for the date, and the
  comparisons (`=`, `!=`, one of, `>`, `<`, between) go to the connector's column read, which drops the rows that fail them before they
  reach Drishti. A connector holds one date's chosen columns while it reads them; the rows go out in batches of
  `drishti.data-view.batch-rows`.
- **Filtered while streaming.** Otherwise (a union of sources, a field nested in a record, a source without column reads, or a caller with
  masks) the documents of the date are read in one pass and each is checked as it goes by. A source that cannot read a day in one pass is
  listed and read 500 entities at a time (at most `drishti.data-view.list-limit`).

Either way every row is checked against every line, so the answer is the same. The count before the download reads the day without the
pushdown, to give both numbers (*412 of 12,076 on the date*); it stops at `drishti.data-view.count-budget` (60 s) and then says *at least*.
The size is estimated by compressing the selected rows as the download will (the first 4 MB, the ratio applied to the rest).

### 4. Entitlements, masks, internal-only data and the audit

- **Entitlements.** Only datasets the person may open are listed or read: their role opens the kind, the pack is on for them, and the
  command is on (Admin → Functions); an edition or lens that hides the kind's command hides the dataset. The same rules as every other screen.
- **Masks.** A column masked for the person (`drishti.security.redact`, a role without `raw`) is left out of the column list and out of
  every download, even when a shared link names it; a filter line on it is refused (`403 DRS-5002`: filtering would tell its values). Masks
  deeper in a record apply too, so a masked person's rows are always read as documents with the masks applied.
- **Internal only.** A dataset whose source's terms restrict reuse is marked *internal only* in the list. It is refused for download
  (`403 DRS-5002`, the facts still shown) where the edition says `internal-only-data: off` (the four editions ship so) or the server says
  `drishti.data-view.internal-only-download: false`. The full Drishti, for internal use, downloads them.
- **Audit.** Every download writes one audit record when it ends: actor, action `data.download`, the dataset, and `date=...; columns=...;
  where=...; rows=N; complete` (or `incomplete` when the stream broke off). Admin → Audit lists them.

### 5. The API

All under `/api/v1`, for any signed-in person (a personal token too); the edition's `DATA` word and `drishti.data-view.enabled` switch it off
(`404 DRS-4023`, `403 DRS-5002`).

| Method | Path | Answer |
|---|---|---|
| `GET` | `/data` | `{internalOnlyRefused, datasets[{kind, pack, code, title, description, class, connector, format, table, dated, columnCount, internalOnly, refused, dates, first, last}]}` |
| `GET` | `/data/{kind}` | the facts as above with `grain`, `key`, `columns[{name, type, description}]` (less the masked), `masked` (how many left out) and `dates` (every date held) |
| `GET` | `/data/{kind}/count?date=&columns=&where=` | `{date, columns, lines, words, sql, pushdown, rows, total, complete, bytes, gzipBytes, read}` |
| `GET` | `/data/{kind}/rows?date=&columns=&where=` | the rows as `application/gzip`, `<kind>_<date>.jsonl.gz`; `X-Drishti-Selection` carries the selection in words |

`date` is `yyyy-MM-dd` and must be one the dataset holds (`400 DRS-4003` names the range); without it, the latest. `columns` is
comma-separated (or repeated); `where` is repeated, one line each. Example:

```bash
curl -H "Authorization: Bearer $DRISHTI_TOKEN" -o trade-valuation_2026-09-30.jsonl.gz \
  "http://localhost:8080/api/v1/data/trade-valuation/rows?date=2026-09-30&columns=TRADE_ID,DESK,MTM&where=DESK%20%3D%20DESK-COMM&where=MTM%20%3E%201000000"
zcat trade-valuation_2026-09-30.jsonl.gz | head -2
{"TRADE_ID":"1000417","DESK":"DESK-COMM","MTM":1250340}
{"TRADE_ID":"1000431","DESK":"DESK-COMM","MTM":2004112}
```

### 6. The command line

`drishti.py data get` does the same from a terminal ([CLI_REFERENCE.md](CLI_REFERENCE.md#data-get-a-dataset-as-jsonlgz-export-data)):

```bash
python3 tools/drishti.py data get                                     # the datasets you may download
python3 tools/drishti.py data get trade-valuation --date 2026-09-30 \
    --columns TRADE_ID,PRODUCT_TYPE,DESK,MTM --where "DESK = DESK-COMM" --where "MTM > 1000000" --count
python3 tools/drishti.py data get trade-valuation --date 2026-09-30 \
    --columns TRADE_ID,PRODUCT_TYPE,DESK,MTM --where "DESK = DESK-COMM" --where "MTM > 1000000" -o tval.jsonl.gz
```

### 7. Settings

| Setting | Default | What it does |
|---|---|---|
| `drishti.data-view.enabled` | `true` | the API on or off (the edition feature `data` presets it) |
| `drishti.data-view.internal-only` | the IMF, Yahoo and news-headline kinds | the datasets marked internal only |
| `drishti.data-view.internal-only-download` | `true` | `false` refuses them (the edition feature `internal-only-data: off` presets it) |
| `drishti.data-view.count-budget` | `PT60S` | the longest a row count reads before answering *at least* |
| `drishti.data-view.batch-rows` | `8192` | rows a column read hands over at a time |
| `drishti.data-view.list-limit` | `200000` | most entities read from a source that cannot read a day in one pass |
| `drishti.data-view.read-budget` | `PT30S` | one listing, or one chunk of 500 entities, from such a source |
| console `features.data` | `true` | the page, the menu item and the word in the console |
| console `data_view.*` | 30, 90, 300 s | how long the console waits for the list, a count, and between two parts of a download |

Every setting is also in [CONFIGURATION.md](../admin/CONFIGURATION.md).

## 7. The data inbox: drop files and they load

Drop files with a `.ready` marker in a folder and the server loads them into a lake by itself: [DATA_LAKE_GUIDE.md](DATA_LAKE_GUIDE.md), [section 3.1](DATA_LAKE_GUIDE.md#31-the-data-inbox) (the folders, the cycle, the settings, the Inbox screen), section 4 (the `.ready` file) and section 5 (routing rules, dates, modes, undo); the walk-through is [tutorial 16, Part C](../tutorials/16-load-your-own-data-into-a-new-lake.md#part-c--load-files-automatically-with-the-data-inbox).

## 8. Connectors

Connecting Drishti to your data means choosing a **connector** (one configured instance of a source plugin) and writing it down as a
**connector file**. Each plugin's README is the one home for that plugin (how it reads, every setting, scale and limits,
troubleshooting, a walk-through); this section says which one to choose and how connectors fit together; [section 9](#9-connector-files-and-admin--connectors)
is the files, live reload, Admin → Connectors, the API and the CLI; [section 10](#10-tls-and-the-outbound-proxy) is TLS and the proxy.
Doing it once with a worked example: [tutorial 15](../tutorials/15-connect-your-data.md). Writing a plugin of your own:
[DEVELOPER_GUIDE.md](DEVELOPER_GUIDE.md#10-connectors-choosing-combining-and-writing-one).

### 8.1 One file per connector

A connector is a **site** resource: one YAML file in `config/connectors/`, and **the file name is the connector's name**
(`config/connectors/trading-lake.yaml` is the connector `trading-lake`). A pack does not carry connection settings; it **names** the
connectors it reads through (`connectors: [trading-lake]` and `routes:` in its `pack.yaml`) and may **suggest** a template for each, which
the server writes to the file once, at the first start, and never overwrites.

```yaml
# config/connectors/trading-lake.yaml
plugin: delta                     # required: which source plugin (delta, jdbc, file, kafka, redis, …)
enabled: true                     # optional, default true
description: Trading lake         # optional
settings:                         # the plugin's settings; nesting is flattened to dotted keys
  root: ${DRISHTI_DELTA_ROOT:${drishti.data.dir:./data}/delta/demo}
  domain: trading
```

- Edit the file in your editor, in **Admin → Connectors** (a form generated from the plugin's settings, a YAML tab, **Test connection**,
  history, restore), or with `drishti.py connector list|get|apply|delete|test|plugins|enable|disable|reset|history`. A change is applied to
  that connector without a restart ([section 9](#9-connector-files-and-admin--connectors)).
- A credential (`password`, `secret…`, `token`, `api-key`, `access-key`, `private-key`, `credential`) is only ever an `${ENV_VAR}` or a
  `file:/path` reference ([8.4, Secrets](#secrets)).
- Ready-made files for every store are in [`config/connectors.examples/`](../../config/connectors.examples) (`postgres/`, `duckdb/`,
  `aerospike/`, `cassandra/`, `mongodb/`, `iceberg/`, `redis/`, `files/`, `other/`); every plugin README ends its settings with an "As a
  connector file" section.
- A connector over the network takes the shared `tls.*` settings for a private CA or a client certificate, and, when it speaks HTTP, the
  shared `proxy:` block ([section 10](#10-tls-and-the-outbound-proxy)).
- `drishti.sources.connectors` in `application.yaml` and the per-store profiles (`SPRING_PROFILES_ACTIVE=postgres`, …) still work but are
  **deprecated** ([9.11](#911-moving-off-applicationyaml-and-the-old-data-source-override)).

Which definition wins (environment and the deprecated `drishti.sources.connectors.*`, then the file, then the pack's template) is
[Precedence](#precedence); how `routes:` and a file's `kinds:` decide which connector answers a kind is
[9.6](#96-how-packs-and-files-fit-together).

### 8.2 Which connector?

| Your data is… | Plugin | Use it for, in one line | Its README |
|---|---|---|---|
| JSON lines (or JSON) files in a folder | `file` | a demo, a nightly export, reference data kept by hand, a first connector; no live updates, new files found every `rescan-seconds` | [file](../../docs/connectors/types/file/README.md) |
| in a Delta Lake (local disk, EFS, `s3://`) | `delta` | business dates at a million trades a day for years; a table per kind partitioned by business date | [delta](../../plugins/drishti-plugin-delta/README.md) |
| in Iceberg tables behind a catalog (REST, Glue, AWS S3 Tables) | `iceberg` | the same at scale on Iceberg | [iceberg](../../plugins/drishti-plugin-iceberg/README.md) |
| in Iceberg tables other engines write (Spark, Flink, Trino, Glue), read-only | `iceberg-external` | reading an existing lakehouse in its own layout | [iceberg-external](../../plugins/drishti-plugin-iceberg-external/README.md) |
| in an existing database schema, with your own SQL | `jdbc` query mode | one SQL statement per kind; `:asOf` makes it dated | [jdbc](../../plugins/drishti-plugin-jdbc/README.md#query-mode-the-queries) |
| in PostgreSQL loaded for Drishti | `jdbc` table mode | every kind of a domain in one table, partitioned by month | [jdbc](../../plugins/drishti-plugin-jdbc/README.md) |
| in DuckDB | `duckdb` | a book in one embedded file, one machine | [duckdb](../../plugins/drishti-plugin-duckdb/README.md) |
| in MongoDB | `mongodb` | a document per entity per day, read as columns | [mongodb](../../plugins/drishti-plugin-mongodb/README.md) |
| in Redis | `redis` | a million trades a day in memory, TTL retention | [redis](../../plugins/drishti-plugin-redis/README.md) |
| in Aerospike | `aerospike` | a million trades a day for years | [aerospike](../../plugins/drishti-plugin-aerospike/README.md) |
| in Apache Cassandra, ScyllaDB, DataStax Astra or Amazon Keyspaces | `cassandra` | a partition per business day; a day appears whole or not at all | [cassandra](../../plugins/drishti-plugin-cassandra/README.md) |
| JSON objects in a bucket (S3, MinIO, Ceph) | `s3` | documents another system writes, one per entity; no reverse lookups | [s3](../../plugins/drishti-plugin-s3/README.md) |
| behind an in-house HTTP/JSON API | `rest` | one GET per entity, the owner's current answer; no search, undated | [rest](../../docs/connectors/types/rest/README.md) |
| on Kafka (live) | `kafka` | entities whose latest state is published to a topic; `mode: ticks` as a change feed over a store | [kafka](../../plugins/drishti-plugin-kafka/README.md) |
| on ActiveMQ Classic or RabbitMQ (live) | `activemq`, `rabbitmq` | queues that deliver once: the latest document per entity kept in a local state store | [activemq](../../plugins/drishti-plugin-activemq/README.md), [rabbitmq](../../plugins/drishti-plugin-rabbitmq/README.md) |
| public rates and FX (SOFR, €STR, ECB FX, the Treasury curve, FRED) | `feed` | real reference rates beside a pack's own; needs outbound HTTPS or a mirror | [feeds](../../docs/connectors/types/feed/README.md) |
| headlines for a named entity | `news` | computed on demand (`NEWS-Federal_Reserve`); off as shipped | [news](../../docs/connectors/types/news/README.md) |
| a quote and price history for a ticker | `yfinance` | `QUOTE-AAPL`, computed on demand; samples only, not for production | [yfinance](../../docs/connectors/types/yfinance/README.md) |
| the packs' sample entities | `demo` (runs as itself, no file) | a first look, demos, writing a pack; switch it off in production | [demo](../../docs/connectors/types/demo/README.md) |

What each can do (history, live ticks, search, reverse lookups) and the rules of thumb are compared in
[Which connector should I use?](DEVELOPER_GUIDE.md#103-which-connector-should-i-use). Three connector types are built
into the engine rather than installed as plugins ([8.5](#85-connectors-built-into-the-engine-derived-net-and-reactive)).

### 8.3 Several connectors for one kind: a union

When one kind comes from several systems at once (trades from Endur, Calypso and Murex, each with its own extract), give each system
its own connector serving the kind and mark them `union: true`. Ids must be unique across them (the trading pack's are: Endur deal
numbers, `CLY-…`, `MX-…`), and each document says where it came from (the trading pack's `SOURCE_SYSTEM`).

```yaml
# config/connectors/trade-calypso.yaml (trade-endur.yaml and trade-murex.yaml differ only in the table and the name)
plugin: delta
settings:
  root: ${DRISHTI_DELTA_ROOT:./data/delta/demo}
  domain: trading
  union: true                   # one slice of the kind: the union of the three serves it
  table: { trade: trade_calypso }
  source-name: trade-calypso
```

| What | Without `union` | With `union: true` |
|---|---|---|
| open `TRD CLY-3000086` | the first connector that holds the date answers; one that holds it and does not list the id ends the read (*not found*) | every union connector is asked in turn; *not found* only when none lists it |
| search and type-ahead | the first connector holding the date lists the kind | every union connector lists it; their hits are taken in turn, so a short type-ahead shows each system |
| a structured search, a pick list, a pivot over columns | the columns of one connector | the columns of all of them, put together (a number in one system and text in another is kept as it is) |
| a BI table over the kind (no `connector:` named) | one connector's rows | every union connector's rows in one table; naming a connector reads that one only |

A connector behind the union (a stream of today's changes, the demo samples) is still not asked once the union holds the date. The
trading pack ships its three trade connectors this way ([the delta README](../../plugins/drishti-plugin-delta/README.md#declaring-the-layout-in-a-pack));
`drishti.py lake build --connector trade-endur` writes the table that connector reads ([CLI_REFERENCE.md](CLI_REFERENCE.md)).

### 8.4 Combining connectors

#### History from a lake, live from Kafka, for the same kind

This is what the trading pack names: `trade-endur` (Delta Lake, dated) and `trading-stream` (Kafka, live), both
serving `trade`, with `routes: { trade: trade-endur }`. With the stream on and the demo off, the candidates for
`trade` are the route (`trade-endur`), then every other connector serving `trade` (`trading-stream`, and `file`,
which serves every kind). Then:

| The user asks for | Order tried | Who answers |
|---|---|---|
| Live | `trading-stream` (live first) → `trade-endur` → `file` | Kafka; the view ticks from Kafka. A trade Kafka does not hold (matured, compacted away) falls through to the lake's newest date |
| a picked date | `trade-endur` (dated first) → `file` → `trading-stream` | the lake, for that date; a static snapshot |
| a date the lake does not hold | as above | `file` if it has a dated folder; else Kafka's current document, with the banner *No data held for <date>* (the current data of trading-stream) |

**`state` or `ticks`.** In `state` mode (the default) Kafka keeps an index of the topic and answers Live reads
itself, as above. With `mode: ticks` it keeps nothing (no index, no cache, no disk) and answers no reads: the lake
or database answers every read, and Kafka only pushes each new message to the views that are open. Use it when the
store already has the day's data and the topic is just the change feed, or the topic is too large to index:

```yaml
# config/connectors/trading-stream.yaml
plugin: kafka
settings: { mode: ticks, bootstrap-servers: "${DRISHTI_KAFKA_BOOTSTRAP}", topics: drishti.trading.trades,
            kind: trade, id-field: tradeId }
```

The view is live because a connector *pushes* the kind (the plugin answers `pushes(ref)` with true in ticks mode),
not because the document that answered is live: `TRD MX-20000001` reads from `trade-endur` (its provenance names the
lake), shows the green Live button, and each message for MX-20000001 on the topic repaints it within a frame. A picked
business date stays a static snapshot either way.

#### Several domains

Run one connector per data domain. A server with the banking packs runs, for example (real output of
`GET /api/v1/sources`, shortened):

```json
{"sources":[
  {"name":"demo","kinds":[],"live":true,"search":true,"reverseLookup":true,"health":"UP"},
  {"name":"file","kinds":[],"live":false,"search":true,"reverseLookup":false,"health":"UP"},
  {"name":"trade-endur","kinds":["trade"],"live":false,"search":true,"reverseLookup":true,"health":"UP"},
  {"name":"credit-store","kinds":["credit-limit","netting-set","sa-ccr","cva"],"live":false,"search":true,"reverseLookup":true,"health":"UP"},
  {"name":"reference-store","kinds":["book","agreement","counterparty", "…"],"live":false,"search":true,"reverseLookup":true,"health":"UP"},
  {"name":"nyfed-sofr-feed","kinds":["rate-fixing"],"live":false,"search":true,"reverseLookup":false,"health":"UP"}],
 "failures":{}}
```

Each connector lists its own `kinds`, has its own health, its own cache and its own read statistics. Keep connector
names stable: routes, health history, cache purges and provenance all use them.

#### Changing a pack's connector from the site

The connector files are the site's, so change a pack's connector by editing its file (in **Admin → Connectors**, or in your editor). A file **replaces** the
pack's template for that name **wholesale**: the settings are not merged, so what the file does not say is not inherited. To change only the root of
`trade-endur`, start from the generated file (`drishti.py connector get trade-endur`) and edit that line:

```yaml
# config/connectors/trade-endur.yaml (generated from the trading pack's template, then edited)
plugin: delta
settings:
  root: /srv/lake                  # the line the site changed
  domain: trading
  layout.trade.columns: TRADE_ID,SOURCE_SYSTEM,PRODUCT_TYPE,...
```

```yaml
# config/connectors/trading-stream.yaml: the stream on, whatever DRISHTI_STREAM_TRADING says
plugin: kafka
enabled: true
settings:
  bootstrap-servers: kafka1.bank.example:9092
  topics: trading.trades.v2
  kind: trade
  id-field: tradeId
```

Points to remember:

- **A file is the whole definition.** It names no kinds: which connectors serve a kind is `config/bindings.yaml` (Admin → Data bindings), and the connector serves what its plugin and lake hold. **Reset to pack default**
  (Admin → Connectors, or `drishti.py connector reset <name>`) puts the pack's template back and keeps your text in the history.
- **Switch one off with `enabled: false`** in its file (`drishti.py connector disable fred-feed`).
- **Keys the plugin does not read are ignored** (with a warning on the form and from the validator). Switching `trade-endur` to `jdbc` and leaving `root` in the file is
  harmless. (Aerospike *does* read `domain`, as its set, and `layout`, as its promoted bins.)
- **A file with no `plugin:`** fails to start. A file name that no pack names and that is not a typo of one defines a new site connector; a pack that names a
  connector nobody defined loads, and its kinds answer `DRS-1011 connector <name> is not configured` ([DATA_GUIDE.md section 9](#a-pack-names-a-connector-nobody-defined)).
- **Between packs**, a child pack that redefines a parent's connector template replaces it *as a whole*, and the override is
  listed in health (`overrides`); two unrelated packs that define the same connector differently stop the server at
  start, naming both. See [BUILD_GUIDE.md](BUILD_GUIDE.md#31-the-packs-that-ship-turning-them-on-who-sees-which).
- **The deprecated form still works.** `drishti.sources.connectors.<name>` in `application.yaml` overrides a file setting by setting, with a warning that names the file to write.

#### Switching connectors off

| To | Do |
|---|---|
| switch one connector off | `enabled: false` in its file (Admin → Connectors **Disable**, or `drishti.py connector disable <name>`) |
| switch every pack's lake off | `DRISHTI_LAKE_ENABLED=false` |
| switch the samples off | `DRISHTI_DEMO_ENABLED=false` |
| switch a plugin running as itself off | `drishti.sources.plugins.<plugin>.enabled: false` (`DRISHTI_REST_ENABLED`, `DRISHTI_JDBC_ENABLED`, `DRISHTI_S3_ENABLED`, `DRISHTI_ACTIVEMQ_ENABLED`, `DRISHTI_RABBITMQ_ENABLED`) |
| one value for one start | `java -jar … --drishti.sources.connectors.trading-stream.enabled=true` (the deprecated form; it overrides the file for that start) |

A connector that is off is listed under its pack's `connectorsOff` in health. Note that **Admin → Packs** (switching a
pack off for everyone) hides the pack's kinds from users; it does not stop its connectors. Use `enabled: false` for
that.

#### Secrets

A connector file never holds a secret. A setting whose name says it is a credential (`password`, `secret…`, `token`, `api-key`, `access-key`, `private-key`, `credential`)
must be an environment reference or a file reference; set the variable where the server runs (systemd `Environment=`, a Kubernetes secret, the shell), or mount the
secret as a file:

```yaml
settings:
  password: ${DESK_DB_PASSWORD}                    # an environment reference, no default
  # password: file:/run/secrets/desk-db-password   # or a file reference: the first line is the secret
  user: ${DESK_DB_USER:drishti}                    # not a credential: a default is fine
  header.Authorization: "Bearer ${CRM_TOKEN}"
  client.sasl.jaas.config: ${KAFKA_JAAS}
  tls.keystore-password: ${CLIENT_KEYSTORE_PASSWORD}   # tls.* settings: see DATA_GUIDE.md section 10; …-password-file also works
```

A literal value, a default inside the reference (`${X:fallback}`) and a password inside a URL (`jdbc:…//user:pw@host`, `?password=…`) are **refused** by Admin → Connectors,
the API and the CLI. A variable that is unset and has no default refuses that file, and the last good configuration keeps running. Health and `/api/v1/sources` do not
show settings. Prefer the AWS credential chain (instance roles) to `access-key` and `secret-key` for S3 and S3A. Details: [DATA_GUIDE.md section 9](#95-credentials-and-tls).

#### A pack with several connectors, worked through

A pack for an equities desk, `config/packs/equity-desk/pack.yaml` (illustrative; the structure is exactly that of the
shipped `trading` pack), keeps history in Delta Lake and takes live orders from Kafka. Its `connectors:` mapping is the pack's **suggested templates**: at the first start the server writes
`config/connectors/equity-store.yaml` and `config/connectors/equity-orders.yaml` from them, and those files are then the site's (a pack that only wants to name its connectors writes
`connectors: [equity-store, equity-orders]` and lets the site create the files):

```yaml
pack: equity-desk
version: 1.0.0
title: Equity desk
extends:
- core                 # counterparties, books, traders… and core's reference-store connector
- market-data                  # equities, curves… and market-store plus the five feeds
kinds:                         # a kind belongs to exactly one pack
- order
- position
mnemonics:                     # <MNEMONIC> <ID> <GO>
  ORD: { kind: order, label: Order }
  POS: { kind: position, label: Position }
graph:
  id-patterns:
  - { pattern: "^O-", kind: order }
  - { pattern: "^POS-", kind: position }
connectors:
  equity-store:                # history: one Delta domain folder for both kinds
    plugin: delta
    enabled: ${DRISHTI_LAKE_ENABLED:true}
    kinds: [order, position]
    settings:
      root: ${DRISHTI_DELTA_ROOT:${drishti.data.dir:./data}/delta/demo}
      domain: equity           # <root>/equity/order/, <root>/equity/position/
      mode.position: snapshot
      mode.order: effective    # an order row is written only when the order changes
  equity-orders:               # live: the order book as it moves
    plugin: kafka
    enabled: ${DRISHTI_STREAM_EQUITY:false}
    kinds: [order]
    settings:
      bootstrap-servers: ${DRISHTI_KAFKA_BOOTSTRAP:localhost:9092}
      topics: equity.orders
      kind: order
      id-field: orderId
routes:
  order: equity-store
  position: equity-store
```

**Routing, for `ORD O-55120 <GO>`.** The candidates are, in order: the route (`equity-store`), the
`default-route` (`demo`, which serves any kind), then every other source serving `order` (`equity-orders`, and any
undeclared-kinds plugin such as `file`). Then:

- **Live**, stream on: live sources first, a real stream before the default route's samples, so
  `equity-orders` → `demo` → `equity-store` → `file`. The view ticks from Kafka. An order that Kafka does not hold
  (filled last week, compacted away) falls through to the lake's newest row.
- **Live**, stream off: `demo` → `equity-store` → `file`. With no `samples/` for `order`, the demo source holds
  nothing and the lake answers.
- **A picked date**: dated sources first, so `equity-store` → `file` (dated folders) → `demo` → `equity-orders`;
  the lake answers for that date, and the view is a static snapshot.
- A source that holds nothing passes on; one that fails stops the read (`DRS-1003`), so a lake that is down shows as
  an error rather than silently serving other data.

**Inheritance.** Enabling `equity-desk` (`DRISHTI_PACKS=equity-desk`) loads `core` and `market-data` too
(`extends:` and `requires:` both pull parents in), and every connector the three name runs: `reference-store`,
`market-store`, the five feeds (off unless switched on), `equity-store` and `equity-orders`. Where two packs declare
the same connector name (or route, mnemonic, field, badge or role):

- identically: nothing happens, it runs once;
- differently, and one pack inherits from the other (or a loaded pack inherits from both): the more specific one
  wins *as a whole* (the child over its parents, the rightmost parent over those before it, by C3 linearisation, as
  `PackLineage` computes), and the override is listed in health (`overrides`, from `drishti.packs.overrides`);
- differently, from unrelated packs: the server refuses to start, naming both packs.

So a child pack that redefines `market-store` replaces its whole definition (plugin, kinds and every setting); it
does not merge with the parent's.

**Site overrides.** The site's connector files replace the pack's templates wholesale, so a site edits the file it was given. `config/connectors/equity-store.yaml`,
moved to PostgreSQL (same kinds, modes and route; a different store):

```yaml
plugin: jdbc
kinds: [order, position]
settings:
  url: jdbc:postgresql://pg.bank.example:5432/drishti
  user: ${PG_USER}
  password: ${PG_PASSWORD}
  table: equity.entities
  mode.position: snapshot
  mode.order: effective
```

`config/connectors/equity-orders.yaml`, the stream on whatever `DRISHTI_STREAM_EQUITY` says, and the parent pack's feed off for this site (`config/connectors/fred-feed.yaml` with
`enabled: false`, or `drishti.py connector disable fred-feed`):

```yaml
plugin: kafka
enabled: true
kinds: [order]
settings:
  bootstrap-servers: kafka1.bank.example:9092
  topics: equity.orders
  kind: order
  id-field: orderId
```

Remember that keys the new plugin does not read are
simply ignored (`root` and `domain` above would be; but `domain` *is* read by `aerospike`, as the set, and so is `layout`, as the promoted
bins). Routes stay the pack's (`routes:` in its `pack.yaml`).

### 8.5 Connectors built into the engine: derived, net and reactive

Three connector types need no plugin to install; each has its README beside the plugins' (Admin → Connectors shows it too):

| Type | What it does | Its README |
|---|---|---|
| `derived` | kinds no source holds, computed by grouping another kind and adding up (a counterparty's trades from every system, `CTRD`), once per business date and data version, shared by every reader; read through the normal routing, so a union counts as one kind | [derived](../connectors/types/derived/README.md) |
| `net` | when Drishti\*net is on, every peer that holds data is a source `net:<node id>`, asked only after the local sources and only for a read made for a person, one signed hop, masked for the caller on both nodes; no connector file | [net](../connectors/types/net/README.md) |
| `reactive` | a connector whose answer is a computation: the kinds it computes with their typed parameters, the inputs it reads as the person asking, the engine (Java or Python), the triggers, coalescing, cache and limits; a Sutra panel reads it with `source: { kind, id, params }` | [reactive](../connectors/types/reactive/README.md) |

How the reactive connectors are built is [DATA_ARCHITECTURE.md section 4](../architecture/DATA_ARCHITECTURE.md#4-reactive-connectors);
how a pack declares derived kinds is [BUILD_GUIDE.md](BUILD_GUIDE.md#53-derived-kinds-entities-computed-from-other-kinds).

## 9. Connector files and Admin → Connectors

The connector folder, the file format (with an example for every store), how a pack and a file fit together (including several
connectors serving one kind), live reload, the **Admin → Connectors** page, the REST API, the `drishti.py connector` commands, moving off
`application.yaml`, health and connection pools. The settings each plugin reads are in its README ([8.2](#82-which-connector)). Every
command output below was captured on a fresh clone with the demo lakes (`drishti.py lake demo`,
[OPERATIONS_GUIDE.md](OPERATIONS_GUIDE.md#24-build-the-demo-lakes)).

### 9.1 The model in one minute

```
  pack.yaml (trading)                    config/connectors/trade-endur.yaml          the system behind it
  ───────────────────                    ──────────────────────────────────          ────────────────────
  kinds: [trade, …]                      plugin: delta
  connectors:                       ──►  kinds: [trade]                         ──►  data/delta/demo/trading/trade_endur
    trade-endur:   {template}            settings: { root: …, domain: trading,       (a Delta table, partitioned by
    trade-calypso: {template}                        table.trade: trade_endur,         business date and PRODUCT_TYPE)
    trade-murex:   {template}                        union: true, … }
  routes: {trade: trade-endur, …}        config/connectors/trade-calypso.yaml   ──►  data/delta/demo/trading/trade_calypso
                                         config/connectors/trade-murex.yaml     ──►  data/delta/demo/trading/trade_murex
```

- The **pack** says *what* it needs: the kinds it owns and, in `routes:`, which connector answers each kind first.
- The **file** says *how* to reach it: plugin, settings, switch. It is edited by the site, in the editor of your choice or in **Admin →
  Connectors**, and applied to the running server **without a restart**.
- A pack may *suggest* a connector (a template). At the first start the server writes the suggestion to the file, once, and never
  overwrites it afterwards. So every pack shipped with Drishti works out of the box, and the file is then yours.
- **Several connectors may serve one kind together.** A connector with `union: true` holds one slice of its kinds (one source system
  of several, ids unique across them): reads, searches, type-ahead and BI tables ask every union connector of the kind and put the
  answers together. The trading pack's `trade` kind is served this way by `trade-endur`, `trade-calypso` and `trade-murex`.
- A pack that names a connector that does not exist still loads; its kinds say so ([9.12](#912-health-and-problems)).

### 9.2 The folder

Every setting of this part, with its type, default, environment variable, meaning and whether a change needs a restart, is in the one configuration document: [CONFIGURATION.md, `drishti.sources`](../admin/CONFIGURATION.md#drishtisources) (also at Help → Configuration in the console).

The folder holds the connector files and nothing else. A connector plugin that is not shipped (a vendor's, a JDBC driver for
`jdbc`) is a jar in the server's **lib folder** (`drishti.lib.path`, default `<data folder>/lib`), loaded at start; that is the only
place for one ([CONFIGURATION.md › Your own libraries](../admin/CONFIGURATION.md#your-own-libraries-the-servers-lib-folder-drishtilibpath-and-the-consoles-external_libpython)). What the server keeps *about* them lives in the **data folder**, never beside
them and never in a hidden folder:

```
config/connectors/                          the site's connector files: one per connector, the name is the file name
  trade-endur.yaml
  trade-calypso.yaml
  market-store.yaml
  …
data/connectors/                            <data dir>/connectors (DRISHTI_DATA_DIR moves the data folder)
  generated/trade-endur.properties          which files the server wrote from a pack's template, and from which version
  history/trade-endur.20261017T213854795.yaml   every earlier text of every file, written before each change
```

A `generated/<name>.properties` marker records the fingerprint of the file the server wrote and of the template it came from
(`file=1a3893dbc61d97ee`, `template=35e7b46805591a9c`, `kinds=trade`), so the server can tell an **unedited** generated file (it
may be regenerated when the pack's template changes) from one **you edited** (left alone, and flagged).

Names are lower case letters, digits and hyphens, starting with a letter or digit, at most 64 characters (`trade-endur`,
`risk-store-2`). `plugins` and `history` are reserved. A file whose name breaks the rule (`Upper.yaml`, `a b.yaml`, `.yml`) is not
loaded; the server lists it in the log and on the Connectors page.

**Keep this folder in version control** if you manage configuration as code; it holds no secret ([9.5](#95-credentials-and-tls)). Back it up with the rest
of the site configuration; `<data dir>/connectors/history/` is a convenience, not a backup.

### 9.3 The file

```yaml
# config/connectors/trade-endur.yaml
plugin: delta                     # required: which source plugin (delta, jdbc, file, kafka, redis, derived, …)
enabled: "${DRISHTI_LAKE_ENABLED:true}"   # optional, default true
description: ENDUR trades         # optional, free text
settings:                         # the plugin's settings; nesting is flattened to the dotted keys the plugin reads
  root: "${DRISHTI_DELTA_ROOT:${drishti.data.dir:./data}/delta/demo}"
  domain: trading
  table.trade: trade_endur        # the table under <root>/<domain>/ that holds the kind (default: the kind's name)
  union: "true"                   # one slice of 'trade' among several connectors
  tls:                            # tls: {ca-file: x} is the setting tls.ca-file
    ca-file: /etc/drishti/tls/ca.pem
```

- Only these top-level keys are allowed (`plugin`, `enabled`, `kinds`, `description`, `settings`, and an optional `name`); anything
  else is an error that names the key.
- `enabled` is `true` or `false`, quoted or not (`enabled: "false"` switches the connector off, as `${VAR:false}` resolves to the
  same text), or a `${ENV_VAR:default}` placeholder. Anything else (`maybe`, `"off"`) refuses the file with `'enabled' is true or false
  (or a "${VARIABLE:true}" placeholder), not 'maybe'`. A placeholder is held to the same rule once resolved, at start and on a hot
  reload: `${VAR:nope}` refuses the file with `… not 'nope' (${VAR:nope} resolves to it)` (the last good configuration keeps running)
  rather than switching the connector off. Admin → Connectors and `GET /api/v1/admin/connectors` list `enabled` as it resolves, so
  `"${DRISHTI_LAKE_ENABLED:true}"` with `DRISHTI_LAKE_ENABLED=false` reads `enabled: false` beside `DISABLED`.
- A `name:` inside the file must equal the file name. A file `a.yaml` that says `name: b` is an error: rename the file or remove the
  line. The file name always wins as the identity.
- `${ENV_VAR}` and `${ENV_VAR:default}` placeholders are resolved when the connector starts, exactly as everywhere else in Drishti's
  configuration. An unset variable without a default refuses that file (the last good configuration keeps running).
- Lists become comma lists (`topics: [a, b]` is `topics: a,b`). Values are strings to the plugin.
- Comments are kept when you edit the YAML in an editor or in the YAML tab. Saving from the **Form** tab rewrites the file from the
  fields (comments are not kept; the earlier text is in `<data dir>/connectors/history/`).

#### Compute requirements: `requires:`

A connector may name the libraries its computation needs: a locked Python environment (`python: {version, lock, index}`), Java
libraries by Maven coordinates and sha256 (`java:`), and connector plugins (`plugins:`). **A connector whose requirements are not all
approved by an administrator stays off**: Admin → Connectors shows it as *waiting for approval* (state `NOT_APPROVED`) with the request
to decide in Admin → Compute. The person who asked is `requires.requested-by`, else the administrator who last saved the file here.
Editing the connector in the form keeps its `requires:` block. The keys, the lock file and the approval are in
[OPERATIONS_GUIDE.md › Compute workers, section 6](OPERATIONS_GUIDE.md#154-requirements-environments-and-approval).

#### A computation: `plugin: reactive`

A connector of the built-in plugin `reactive` computes its kinds ([REACTIVE_CONNECTOR.md](../connectors/types/reactive/README.md)). Its file has, besides
the keys above, `mode: reactive`, `kinds` as a **mapping** of each kind to its typed `params:`, `compute:` and `result:` rules, and the
top-level keys `reads`, `engine`, `triggers`, `coalesce`, `cache`, `limits`, `history` and `incremental`. On any other plugin these keys
are refused (*'engine' is part of a computation: only a connector of plugin reactive has it*); `mode:` (`static`, `live`, `on-demand`,
`reactive`) may be written on any. The server hands the computation to the plugin as the one setting `reactive.spec`; the form, a pack's
template and a template update keep it as written. Templates in it use single braces (`{id}`, `{params.x}`): `${NAME}` is an environment
variable, as everywhere in the file.

#### Writes: saving to the source

A connector may declare `writes:`: named commands that change a record in its source, used by write-back's `source` target
([APPROVALS_GUIDE.md › The design and its reasons, section 5.4](APPROVALS_GUIDE.md#10-writing-to-a-source-writes); the administrator's guide
[APPROVALS_GUIDE.md section 13](APPROVALS_GUIDE.md#10-writing-to-a-source-writes)). Each command has the `kind` it amends, the extra `params:` a Sutra
may supply, its plugin's block (`jdbc:` with `sql`, the guard `expect-rows` and the idempotency clause `already-applied`), `confirm:`
(`reply` or `next-load`) and `timeout:`. Only the JDBC plugin writes ([JDBC_QUERIES.md section 16](../../plugins/drishti-plugin-jdbc/README.md#writes-commands-that-change-a-record));
the File, Delta and Iceberg connectors never do (a lake is not written per edit) and every other plugin is refused (*plugin … cannot
write*). A command without its guard or idempotency clause, or naming an unknown `:param`, is refused when the connector loads: the
problem shows as a warning on `writes.<command>` here and on Admin → Edits, and the connector stays read-only for it. Declaring `writes:`
writes nothing: an administrator must also switch the connector's writes on in Admin → Edits (audited). The form and template updates
keep the block as written; a reactive connector may not have one (it computes, it is never a write target). None of the shipped files
in `config/connectors.examples/` declares one.

### 9.4 Examples, one per store

**Delta Lake** (`config/connectors/trade-endur.yaml`, exactly as the server generated it from the trading pack's template at its first
start):

```yaml
# Generated at first start from the connector template of pack 'trading'. Edit it freely: an edited file is never overwritten; an unedited one follows the pack's template at an upgrade.
plugin: delta
enabled: "${DRISHTI_LAKE_ENABLED:true}"
kinds:
- trade
settings:
  root: "${DRISHTI_DELTA_ROOT:${drishti.data.dir:./data}/delta/demo}"
  domain: trading
  layout.trade.columns: "TRADE_ID,SOURCE_SYSTEM,PRODUCT_TYPE,PRODUCT_SUB_TYPE,PRODUCT_GROUP,TRADE_STATUS,TRADE_VERSION,BUY_SELL_CODE,CURRENCY,QUANTITY,TRADE_DATE,MATURITY_DATE,COUNTERPARTY,LEGAL_ENTITY,PORTFOLIO,TRADER_ID"
  layout.trade.sort-by: id
  layout.trade.file-rows: 250000
  layout.trade.row-group-rows: 1000
  union: "true"
  table.trade: trade_endur
  source-name: trade-endur
```

`trade-calypso.yaml` and `trade-murex.yaml` are the same with `table.trade: trade_calypso` / `trade_murex` and their own
`source-name`. The `layout.trade.columns` are the attributes promoted beside each document as Delta columns (UPPER_SNAKE, as in the
documents), so searches, type-ahead and BI read them without opening documents. `drishti.py lake demo` writes these three tables;
`drishti.py lake build` writes one from your own export ([CLI_REFERENCE.md](CLI_REFERENCE.md)).

**A derived connector** (`config/connectors/trade-pages.yaml`, generated from the trading pack): no store behind it; it computes the
counterparty, portfolio, legal-entity and index pages from the `trade` kind (whichever connectors serve it). The first of its four
kinds (the file has the same block for `portfolio-trades`, `legal-entity-trades` and `index-trades`):

```yaml
plugin: derived
kinds:
- counterparty-trades
- portfolio-trades
- legal-entity-trades
- index-trades
settings:
  refresh: 60s
  store: disk
  counterparty-trades.from: trade
  counterparty-trades.group-by: $.COUNTERPARTY
  counterparty-trades.id-field: COUNTERPARTY
  counterparty-trades.members: TRADE_IDS
  counterparty-trades.max-members: 1000
  counterparty-trades.rows-field: TRADES
  counterparty-trades.fields.TRADE_COUNT: count
  counterparty-trades.fields.NOTIONAL: sum first($.LEGS).NOTIONAL
  counterparty-trades.fields.SOURCE_SYSTEMS: distinct $.SOURCE_SYSTEM
  counterparty-trades.rows.TRADE_ID: $.TRADE_ID
  counterparty-trades.rows.NOTIONAL: first($.LEGS).NOTIONAL
  counterparty-trades.rows.MATURITY_DATE: $.MATURITY_DATE
  …
```

**Kafka** (`config/connectors/trading-stream.yaml`, generated from the trading pack, switched off until `DRISHTI_STREAM_TRADING=true`):

```yaml
plugin: kafka
enabled: "${DRISHTI_STREAM_TRADING:false}"
kinds:
- trade
settings:
  bootstrap-servers: "${DRISHTI_KAFKA_BOOTSTRAP:localhost:9092}"
  topics: "${DRISHTI_TRADING_TOPIC:drishti.trading.trades}"
  kind: trade
  id-field: TRADE_ID
  stale-after: "${DRISHTI_STREAM_STALE_AFTER:15m}"
  disk-cache.enabled: "${DRISHTI_STREAM_DISK_CACHE:true}"
  disk-cache.root: "${DRISHTI_CACHE_ROOT:${drishti.data.dir:./data}/cache}"
  …
```

Over TLS, add the shared `tls:` block (section 5) and `security.protocol: SSL`, as
`config/connectors.examples/other/trading-stream.yaml` and `kafka-mtls-pem.yaml` show.

**More ready-made files** are in [`config/connectors.examples/`](../../config/connectors.examples). Copy one to `config/connectors/`,
change what is yours, and the server starts it within seconds. The folders `postgres/`, `duckdb/`, `aerospike/`, `mongodb/`,
`iceberg/`, `redis/` and `files/` hold the six data domains (`trading-store`, `risk-store`, `market-store`, `reference-store`,
`credit-store`, `collateral-store`) read from that store, replacing what the former `application-<store>.yaml` profiles did; `other/`
holds Kafka, RabbitMQ, ActiveMQ, REST, MongoDB, S3-backed Delta, PostgreSQL and Redis connectors over TLS or a proxy. A test
(`ConnectorExamplesTest`) keeps every example valid. Two things to know when you copy them:

- The example `trading-store.yaml` files are a **single** trade connector. The trading pack routes `trade` to `trade-endur` and serves
  it as a union of three: to read trades from another store, save the example as `trade-endur.yaml` (and `trade-calypso.yaml`,
  `trade-murex.yaml`, each with `union: "true"`), or keep one file and switch the other two off.
- Some example files still name a camelCase id field (`id-field: tradeId`); the shipped packs' documents are UPPER_SNAKE, so set
  `id-field: TRADE_ID`.

**PostgreSQL / any JDBC database** (`config/connectors.examples/postgres/trading-store.yaml`):

```yaml
plugin: jdbc
kinds:
- trade
description: trading-store read from postgres (the former application-postgres.yaml profile)
settings:
  domain: trading
  url: ${DRISHTI_PG_URL:jdbc:postgresql://localhost:5432/drishti}
  user: ${DRISHTI_PG_USER:drishti}
  password: ${DRISHTI_PG_PASSWORD}
  table: trading.entities
  # pool-size: inherits drishti.db.pool.size (20); set it here to size this connector's pool alone
```

**DuckDB, Aerospike, MongoDB, Iceberg, Redis, files**: the same shape with that plugin's settings; see
`config/connectors.examples/<store>/`. For instance Redis:

```yaml
plugin: redis
kinds: [trade]
settings:
  domain: trading
  uri: ${DRISHTI_REDIS_URI:redis://localhost:6379}
  cluster: ${DRISHTI_REDIS_CLUSTER:false}
```

**A folder of JSON documents** (a site connector no pack suggests; this one was applied and deleted on the demo server to capture
the outputs in section 10):

```yaml
# config/connectors/docs-notes.yaml: desk notes from a drop folder
plugin: file
kinds: [docs-note]
description: Desk notes from a drop folder
settings:
  root: /srv/desk/notes
  lookback-days: 10
```

The settings of every plugin, with type, default, whether it is required and whether it is a credential, are served by
`GET /api/v1/admin/connectors/plugins` and printed by `drishti.py connector plugins <name>` (real output, shortened):

```
$ drishti.py connector plugins file
plugin file
  setting               type                    default             what it is
  --------------------  --------------------    ----------------    ------------------------------------------------------------
  layout.*              string                                      Per-kind promoted columns: layout.<kind>.columns: a, b, c
  root                  path                    <data dir>/feeds    Folder of documents; default: the feeds folder of the data directory
  domain                string                                      Domain
  id-field              string                                      Field holding the entity id
  rescan-seconds        int                                         Rescan interval
  lookback-days         int                                         Days of history to index
  business-zone         string                  America/New_York    Time zone whose today is the business date the health check compares the folders with (the 0-rows warning)
  …
  index.days            int                     1                   Business days of ids kept for type-ahead (1 = the newest day only)
  …
  source-name           string                                      The name documents carry as their provenance (default: the connector's name)
  mode.*                string                                      Per-kind read mode (mode.<kind>)
  stale-after           duration                                    Mark the source stale when it has received nothing for this long (15m, 2h, 1d)
  union                 boolean                 false               This connector holds one slice of its kinds (one source system of several, ids unique across them): reads, searches, type-ahead and BI tables ask every union connector of the kind together
```

A plugin can declare its own settings (the `settingSpecs()` method of the source SPI, see
[DEVELOPER_GUIDE.md](DEVELOPER_GUIDE.md#10-connectors-choosing-combining-and-writing-one)); the shipped plugins are described in `drishti/plugin-settings.yaml`
inside the engine. A setting a plugin does not declare is a **warning** (a likely typo), never a refusal, because plugins also read
free-form keys (`layout.*`, `mode.*`, `table.*`, `query.*`). Every key the shipped plugins read is declared in that file; a test
(`PluginSettingsCatalogueSyncTest`) fails when a plugin reads one it does not declare, or when a setting is marked `required` that
the plugin defaults.

### 9.5 Credentials and TLS

**A connector file never holds a secret.** A setting whose name says it is a credential (`password`, `secret…`, `token`, `api-key`,
`access-key`, `private-key`, `credential`) must be one of:

- an environment reference: `password: ${ORDERS_DB_PASSWORD}` (refused at start when the variable is not set), or
  `password: ${ORDERS_DB_PASSWORD:}` with an empty default, for a store that may have no sign-in (a development node), or
- a file reference: `password: file:/run/secrets/orders-db-password` (the first line of the file is the secret, read when the connector
  starts; this is how Docker and Kubernetes secrets are mounted).

A literal value, a value as the default inside the reference (`${X:fallback}`) and a password inside a URL (`jdbc:…//user:pw@host`, `?password=…`)
are **refused** by Admin → Connectors, the API and the CLI with the reason and the name to use (real output in
[section 10](#910-the-cli)). A hand-edited file that breaks this still loads (so a running site is not stopped by an edit), and the page
shows a note on the connector.

**TLS.** Plugins that talk to a server over the network read one shared set of `tls.*` settings, and the form shows them as a **TLS**
section. The form's list is not typed by hand: it is generated from the class that reads the keys (`TlsSettings`), and a test fails if
the two ever differ. The full reference, with every key, the formats and the start-up messages, is [DATA_GUIDE.md section 10](#10-tls-and-the-outbound-proxy); the keys are:

| Setting | Meaning |
|---|---|
| `tls.enabled` | Use TLS (a connector whose address already says so, such as `amqps://`, does not need it). |
| `tls.ca-file` | A PEM file of one or many CA certificates to trust (or the PEM text). |
| `tls.truststore`, `tls.truststore-password`, `tls.truststore-type` | A PKCS12 or JKS truststore instead of, or beside, the PEM file. |
| `tls.trust-jvm-default` | Also trust the public authorities the JVM knows. |
| `tls.cert-file`, `tls.key-file`, `tls.key-password` | The client certificate and key in PEM form, for mutual TLS. |
| `tls.keystore`, `tls.keystore-password`, `tls.keystore-type`, `tls.key-alias` | The client identity as a PKCS12 or JKS keystore. |
| `tls.protocols`, `tls.cipher-suites` | Protocol versions and cipher suites offered. |
| `tls.verify-hostname` | Check that the certificate names the host (default on). |
| `tls.insecure-trust-all` | Development only; refused unless `DRISHTI_ALLOW_INSECURE_TLS=true`. |

Every password has a `-file` twin (`tls.keystore-password-file: /run/secrets/ks`) that reads the first line of a file; the twin names
a path, so it is not itself a secret. A password written in a file is an environment reference or a `file:` reference, as above. The
Kafka connector also has `flavour`, `security.protocol`, `sasl.*` and `schema-registry.*` (with `schema-registry.tls.*` for the
registry's own certificate); the form lists them in a **Security** and a **Schema Registry** section.

**Outbound proxy.** Every connector that speaks HTTP also takes a `proxy:` block in `settings:` (shown as a **Proxy** section of the
form, generated from `ProxySettings`, the class that reads the keys): `proxy.url` (`http://host:port`), `proxy.username`,
`proxy.password` (an `${ENV}` reference, like every credential), `proxy.no-proxy` (names, suffixes, addresses, CIDR blocks) and
`proxy.enabled`; the bare `proxy: none` goes direct whatever the site default (`drishti.http.proxy.*`) says. The connector's page shows
the effective proxy, masked, and where it came from (connector, site default, environment), and **Test connection** goes through it and
says so. The reference is [DATA_GUIDE.md, the outbound proxy](#outbound-http-proxy-for-connectors-ai-providers-and-mcp-servers).

**Test connection** turns a TLS failure into words: an untrusted certificate points you at `tls.ca-file` or `tls.truststore`, a name
mismatch tells you to connect by a name the certificate carries, an expired certificate says so, and a wrong store password points at
the reference. Ready-made secure files are in [`config/connectors.examples/other/`](../../config/connectors.examples/other):
`kafka-mtls-pem.yaml` (Kafka, PEM client certificate), `kafka-confluent-cloud.yaml` (Confluent Cloud with Schema Registry),
`rabbitmq-external.yaml` (`amqps://`, the certificate is the log-in), `activemq-ssl.yaml` (`ssl://`), `postgres-tls.yaml`,
`postgres-tls-client-cert.yaml`, `redis-tls.yaml`, `rest-mtls.yaml`, `rest-via-proxy.yaml`, `s3-private-ca.yaml`, `s3-via-proxy.yaml`,
and the PKCS12 forms in `trading-stream.yaml`, `orders-queue.yaml`, `ops-events.yaml`, `positions-mongo.yaml` and `pricing-api.yaml`.

### 9.6 How packs and files fit together

A pack **names** its connectors. The trading pack's `pack.yaml` (shortened):

```yaml
connectors:                         # a mapping: each entry is a TEMPLATE the server writes to config/connectors/<name>.yaml once
  trade-endur:
    plugin: delta
    enabled: ${DRISHTI_LAKE_ENABLED:true}
    kinds: [trade]
    settings:
      root: ${DRISHTI_DELTA_ROOT:${drishti.data.dir:./data}/delta/demo}
      domain: trading
      layout:
        trade:
          columns: [TRADE_ID, SOURCE_SYSTEM, PRODUCT_TYPE, …]
          sort-by: id
      union: true
      table: {trade: trade_endur}
      source-name: trade-endur
  trade-calypso: { … table: {trade: trade_calypso}, union: true … }
  trade-murex:   { … table: {trade: trade_murex},   union: true … }
  trading-stream: { plugin: kafka, enabled: ${DRISHTI_STREAM_TRADING:false}, … }
  trade-pages:   { plugin: derived, … }
routes:                             # which connector answers each kind first
  trade: trade-endur
  counterparty-trades: trade-pages
  portfolio-trades: trade-pages
  legal-entity-trades: trade-pages
  index-trades: trade-pages
```

- `connectors:` is either a **list of names** (a pack that only names what it needs) or, as every shipped pack does, a **mapping of
  definitions**. A mapping is read as the pack's **templates**; both forms work, and `connector-templates:` carries templates beside a
  list.
- **Kinds** (this is the one statement of the rule; other documents link here): the pack's `routes:` decide the **primary**
  connector for a kind (the pack author knows where each kind lives). A read of a kind asks the routed connector first, then the
  default route, then every other connector that serves the kind, in configuration order. A connector's own `kinds:` (file, template
  or server configuration) is the **exact list** of kinds it serves, which works both ways:
  - it **adds**: a connector listing a kind is consulted after the routed one even with no route to it (for example a lake behind a
    recent store, for dates the recent store does not hold);
  - it **narrows**: a kind missing from the list is not served by that connector, **even when a route sends the kind to it**. The
    read falls through to the next connector that serves the kind, and a BI table that names the connector is refused with
    `connector '…' does not serve kind '…' … add the kind to the connector's file` (`DRS-9026`).

  With **no** `kinds:` the connector serves whatever its plugin serves (for most plugins, any kind), so the routes alone decide. The
  code is `ConnectorInstance` and `SourceRouter.candidates` in drishti-engine; the tests are
  `SourceRouterTest.connectorsServingOneKindAreAskedInConfigOrderAfterTheRoute` and
  `LatestHeldDateTest.aNamedConnectorThatDoesNotServeTheKindIsSaidNotReadAsEmpty`.
- **A union** (`union: true` on each connector of the kind): every union connector of a kind answers together, not one after another.
  A trade id is looked up in all three, a search or a type-ahead merges their hits (labelled by source), and a BI table over `trade`
  reads the three tables as one. Ids must be unique across the union (the trade ids are: `1000001` ENDUR, `CLY-3000157` CALYPSO,
  `MX-20000001` MUREX).
- **Several server processes or test contexts sharing one connector folder** all watch it; a file that appears there is started by
  every one of them and, for a name they define differently, replaces what they run. Give each its own folder
  (`drishti.sources.connectors-dir`).
- A name may be used by **several packs**; Admin → Connectors shows who uses each connector and for which kinds, and asks before you
  switch one off or delete it.

What the server reports for the trading pack (real output; only the routed connector lists the pack's kinds, the union members serve
`trade` beside it):

```
$ drishti.py server packs datasource get trading
connectors of trading   [files in …/config/connectors; change them with `drishti.py connector ...`]
  connector       state     plugin   kinds from this pack                                                   defined by  problems
  --------------  --------  -------  ---------------------------------------------------------------------  ----------  --------
  trade-endur     running   delta    trade                                                                  file
  trade-calypso   running   delta                                                                           file
  trade-murex     running   delta                                                                           file
  trading-stream  disabled  kafka                                                                           file
  trade-pages     running   derived  counterparty-trades,portfolio-trades,legal-entity-trades,index-trades  file
[exit 0]
```

#### Precedence

Highest first. Each is documented and tested:

| Source | Role |
|---|---|
| Environment variables and `drishti.sources.connectors.*` in the server's own configuration (**deprecated**) | Override a file **setting by setting** until you move them to the file; a warning names the file to write. |
| `config/connectors/<name>.yaml` | The site's definition. It **replaces** the pack's template for that name **wholesale**: the settings are not merged, so what the file does not say is not inherited. |
| The pack's template (`connectors:` mapping or `connector-templates:`) | The pack's suggestion. Used to write the file once, and in memory when the file cannot be written. |

A file with **no** template of that name defines a **new site connector**. When two related packs (one `extends` the other) suggest the
same name, the more specific pack's template is the one written; two unrelated packs suggesting different definitions of one name is
an error.

#### Generation at the first start

If a pack suggests a connector and no file of that name exists, the server writes it (logged, and audited as `connector-generated`),
records a marker in `<data dir>/connectors/generated/`, and never touches a file you edited. On a fresh clone with the seven packs:

```
$ ls config/connectors
civic-store.yaml       ecb-fx-feed.yaml     macro-store.yaml      nyfed-sofr-feed.yaml  trade-calypso.yaml  trading-stream.yaml
collateral-store.yaml  fred-feed.yaml       market-store.yaml     reference-store.yaml  trade-endur.yaml    us-treasury-feed.yaml
credit-store.yaml      legal-store.yaml     news-store.yaml       risk-store.yaml       trade-murex.yaml    yfinance.yaml
ecb-estr-feed.yaml     news.yaml            trade-pages.yaml
```

The log says, per file:

```
connectors: connector file trade-endur.yaml generated from the template of pack 'trading'
```

**Edited or not.** A generated file you never edited follows its pack: when the pack's template changes (a new release), the file is
written again from it (its text kept in the history). A file you edited is never overwritten: it gets what the template gained, every
edit kept ([Upgrades](#upgrades)); the Connectors page and Health flag that the pack now suggests something else, and **Reset to pack
default** takes the suggestion when you want it. If you delete a generated
file by hand while the pack still suggests it, it is written again at the next start. To stop generating files altogether, set
`drishti.sources.connectors-generate: false`; the templates are then used in memory. The `sutra` command line
(`drishti.py sutra ...`, `pack check`) always runs that way: it reads the connector files that exist and writes none.

**`lake demo` and connector files.** `drishti.py lake demo` checks the seven packs' Delta connector files against the lake it built:
with the default lake (`<data>/delta`) nothing changes; with `--lake DIR` each file that points elsewhere is rewritten to point there
(its earlier text kept in `<data dir>/connectors/history/`), and a missing one is written from the pack's template.

#### Template updates

A connector file generated once from an older pack template can lack kinds the pack serves now (seen in the field: `macro-store`
without [`region-economy`](../contracts/samples/world-economy.md#region-economy) ([contract](../contracts/packs/world-economy.md#rege-region-economy-region-economy)) and `country-group`, `civic-store` without [`office-holder`](../contracts/samples/world-economy.md#office-holder) ([contract](../contracts/packs/world-economy.md#offc-office-holder-office-holder)), `risk-store` without `trade-valuation`, and old
files still naming `central-bank-decision`, `trade-flow` and `price-basket`, which no pack uses any more). The server compares every
connector file with its pack's current template, at start and every
`drishti.connectors.template-check-ms` (60 s), and sorts the difference into three parts:

| Part | What | Applied automatically |
|---|---|---|
| **Missing** (additive) | kinds the template serves that the connector lacks; settings keys the template has and the connector does not (the layout entries for those kinds among them); columns a layout list of the template has and the connector's same list lacks (`layout.<kind>.columns`, `.vectors`: a pack that gained columns; the connector's own columns and order kept, the new ones appended, the file before kept beside it as `<name>.yaml.before-<yyyyMMdd-HHmmss>.bak`); parameters a reactive connector's template gained (`kinds.<kind>.params.<name>`, such as a reporting currency `ccy`; the file's own parameters kept, the file before kept beside it). Applied once at start, before the server answers, then every `template-check-ms` | with `additive` |
| **Changed** (conflicting) | settings both have with different values: an administrator's edit | never |
| **Obsolete** | kinds in the connector that no loaded pack serves | never |

`template-updates` in the connectors settings file, `config/connectors-settings.yaml` (named by `drishti.connectors.settings-file`;
`drishti.connectors.template-updates` overrides it), chooses what happens on its own:

```yaml
# config/connectors-settings.yaml
template-updates: additive    # additive (the default): add missing kinds and settings, keeping every edit | notify: only list | off
```

With `additive` the missing part is merged into the connector, every other value kept as it is, written to the file with the previous text in the
history; the server logs one line, records `connector-template-update` in the
audit trail and lists it under *Recent automatic updates*. Changed and obsolete parts are only ever changed by an administrator.

**Admin → Connectors → Template updates** (`/admin/connectors/template-updates`; the Connectors tab shows a count when anything
drifted, and the Connectors page links to it) lists every drifted connector with its three parts and its text beside the template's
(stacked on a phone), and the buttons: **Add missing kinds** (merge the missing part, keep edits), **Regenerate from template** (the
template's text replaces the connector's; asks first), **Remove obsolete kinds** (asks first) and **Fix all additive**. Every button
keeps the old text in the history (Restore on the connector's page puts it back) and sends the version read, so a change made
meanwhile is a `409 DRS-5032`. REST: `GET /api/v1/admin/connectors/template-updates`, `POST …/template-updates/<name>/add-missing`,
`…/regenerate`, `…/remove-obsolete`, `…/use-template-value`, `POST …/template-updates/fix-all`. What an upgrade does to unedited and
edited files, at any depth: [Upgrades](#upgrades).

#### Upgrades

A new release can change a pack's connector templates (a new parameter of a kind, for example; a file generated before it would refuse
the new request). An upgrade keeps every connector file in step, with two rules:

**1. A file nobody edited follows the pack.** Drishti stamps every file it writes from a template with one line after the header:

```
# Generated at first start from the connector template of pack 'market-risk'. Edit it freely: an edited file is never overwritten; an unedited one follows the pack's template at an upgrade.
# drishti-template: pack=market-risk template=6f1c0a9d2e7b4c3a text=1b8e4f0c9a2d7e65
```

`template` is the fingerprint of the template the file came from, `text` the hash of the text below the line as written. The stamp is
part of the text, so it travels with it: the file on disk, the history, a copy to another
server. At start (before the server answers) and at every template check, a file whose text below the stamp still has that hash was
never edited: when its template's fingerprint changed it is **replaced in full** by the new template, changed values included. The file
before is kept beside it as `<name>.yaml.before-upgrade-<yyyyMMdd-HHmmss>.bak` and in the history; the log has one line, the audit trail
`connector-template-upgrade`, and *Recent automatic updates* the notice. A CRLF save is not an edit. Files of an older generation without
the stamp are recognised by their marker in `<data dir>/connectors/generated/` in the same way, and stamped when rewritten. **Reset to
pack default** and **Regenerate from template** write a stamped file again, so a connector you put back follows the pack once more.

**2. An edited file gets every missing key, at any depth.** The additive merge reaches the whole definition: kinds, settings, the
layout lists (`layout.<kind>.columns`, `.vectors`), a missing `description`, and a reactive connector's computation: `kinds.<kind>.params`,
`kinds.<kind>.result`, `reads`, `engine`, `triggers`, `coalesce`, `cache`, `limits`, `history`. Any key the template has and the file lacks
is added. A value the file has is never overwritten. Lists are kept in the file's order and get the template's missing items appended, as
the layout lists do; a mapping in a list that names the same thing as one of the file's (the same `kind`, `name`, `id`, `topic` or
`connector`, such as a computed connector's read of `var-result`) is merged into it rather than added twice.

**Conflicts.** The same key with a different value (an administrator's edit, or a value the pack changed under an edited file) is listed
in **Admin → Connectors → Template updates** under *changed*, settings and definition paths alike (`refresh-seconds`, `limits.wait`,
`reads[0].id`), each with a **Use the pack's value** button: that one key takes the template's value, every other edit stays, the old
text goes to the history and the audit trail records `connector-template-update`. REST: `POST
/api/v1/admin/connectors/template-updates/<name>/use-template-value` with `{"where": "settings"|"definition", "key": "<key>"}` and the
connector's version as `If-Match`.

![Template updates: an edited trade-murex, its missing columns added at start, its own file-rows listed as changed with Use the pack's value](img/connector_files/15-template-updates.jpg)

A file written by `drishti.py lake demo` or `build_demo_lakes.py` (its first line *Written by …*) carries no stamp: it counts as
edited, keeps its values and gains missing keys. A change of `plugin` is not listed as a conflict (a `var-results.yaml` that `lake demo`
wrote stays on Delta when the official results move to Iceberg): put such a file back with **Reset to pack default**.

**Example.** A reactive connector generated by an older release and edited (`limits.wait: 45s`), after an upgrade: the parameter its
pack's template gained (`kinds.<kind>.params.ccy`) and the new triggers' kinds are added, `limits.wait` stays `45s`, and `reads[0].id`
(`{id}` in the file, `{id}~ccy={params.ccy}` in the template) is listed as a conflict until an administrator uses the pack's value.

**3. A connector the pack retired.** When a pack no longer has a connector's template (a pack that dropped `var-whatif`, for example), a file Drishti generated from that pack's template and nobody edited is
removed at start: its text is kept beside it as `<name>.yaml.retired-<yyyyMMdd-HHmmss>.bak` and in the history, and the log says so.
An edited file stays as it is; **Template updates** lists its kinds as *obsolete*, and **Remove obsolete kinds** or deleting the
connector takes it away. A file of a pack the server does not load, and a site's own file, are never touched.


#### A pack names a connector nobody defined

The pack loads. Its kinds say so, in three places:

- **Admin → Packs → Data source** lists the pack's connectors; a missing one is marked **not configured** with a **Create** link that
  opens New connector pre-filled from the pack's suggestion (if it has one).
- **Admin → Health** shows the pack as degraded with `connectorsNotConfigured`.
- Reading a view of such a kind fails with `DRS-1011`:

```
HTTP/1.1 404
{"title":"connector not configured","status":404,
 "detail":"DRS-1011 connector desk-archive is not configured: kind 'desk-quote-archive' is routed to it, but no connector file desk-archive.yaml exists (create it in Admin -> Connectors)",
 "instance":"/api/v1/views/desk-quote-archive/Q-1","code":"DRS-1011"}
```

![Admin → Packs → Data source: the pack's connectors, one not configured, with Edit and Create links](img/connector_files/07-pack-connectors.jpg)

#### Deploying a pack archive

A pack archive (`pack make`, `pack bundle`, Admin → Packs → Deploy archive) carries the connector **names and templates only**, never
a site's settings. The deploy preview lists the connectors the pack names that the site lacks, and
`POST /api/v1/admin/packs/deploy/{id}/connectors` (the **Create connectors** step) writes files from the templates before you deploy.
Loading the pack writes them at the next start anyway.

### 9.7 Live reload

The folder is watched (file events, with polling as a fallback and a safety net, the same pattern as Sutra hot reload). Per file:

| What happens to the file | What the server does |
|---|---|
| A **new** file | Starts that connector. |
| A file **changes** | Stops and restarts **only that connector**. |
| A file is **deleted** | Stops it, or, if a pack's template names it, applies the template again. |
| A file is **bad** (not YAML, unknown key, `name:` disagrees, unset `${VAR}`) | Keeps the **last good configuration running**, and shows the problem. |

```
INFO  connector docs-notes started (file)
INFO  connector docs-notes reconfigured from docs-notes.yaml: RUNNING
WARN  connector file docs-notes.yaml is not usable; the last good configuration keeps running: docs-notes.yaml: unknown key 'bogus' (allowed: plugin, enabled, kinds, description, settings, requires; a reactive connector also mode, reads, engine, triggers, coalesce, cache, limits, history)
INFO  connector file docs-notes.yaml was removed; the connector stops
```

A bad file shows in **Admin → Health** (`connectors.badFiles`, overall status degraded), on the Connectors page, and in
`drishti.py connector list` (in the *problems* column, with *the last good configuration is running*).

**Concurrency.** One reconfiguration at a time **per connector**; different connectors reconfigure in parallel. The old instance is
taken out of routing at once, stays open for `connectors-drain` (3 s) so reads in flight finish on it, then closes; the new one starts
after. Connectors you did not touch are not interrupted. A connector's place in the read order is kept across restarts.
A read of one of its kinds meanwhile (an entity, a view, a query) waits for the new instance, up to 30 s, instead of answering
"no source" or "not found"; a start that takes longer answers `503 DRS-1015 connector … is restarting`.

**The data changes, not the file.** A connector file does not change when its lake is rebuilt. The Delta connector checks each table's
latest version every `refresh-seconds` (10 s) and keys everything it caches by the table's version **and** commit time, so a table
written afresh (`lake demo` again, whose versions restart at 0) is read again; the BI store notices the same through each date's data
version ([DATA_GUIDE.md](#1-telling-drishti-new-data-has-landed)).

#### A connector that fails to start, or loses its connection, is retried

A file that is fine can still not start: the store is down, the network is not up yet, a credential is refused. The connector is then
`FAILED` and **tried again by itself**, without anyone touching the file: after `drishti.retry.connectors.initial` (5 s), then twice as
long each time up to `drishti.retry.connectors.max` (5 min), with 20 % jitter. When the store comes up the connector starts and serves;
open live views read through the router and pick the new instance up on their next refresh, no one reloads anything. The same goes for
a connector that was running and whose plugin now reports `DOWN` (its `health()`): it is listed as *connection lost*, looked at on the
schedule, and if it is still down after a few looks it is started afresh. Errors retrying cannot fix (the plugin is not installed, a
setting refers to an unset variable, credentials refused three times in a row) are classed **permanent** and retried only every `max`.
A connector that is `IDLE` (installed, not configured) is not retried: it needs a setting, and saving the file starts it.

```
WARN  connector 'site-quotes' failed (first time): connection refused; next try 2026-10-10T14:02:05Z
WARN  connector 'site-quotes' failed (attempt 2): connection refused; next try 2026-10-10T14:02:20Z
INFO  connector site-quotes started (file)
INFO  connector 'site-quotes' recovered after 3 failed attempt(s); it was: connection refused
```

The log writes failures 1, 2, 4, 8, ... and always the recovery, so an hour of outage is a handful of lines. Admin → Connectors and
Health show *failed — next retry in 20 s (attempt 3)* with the last error on the connector's row; **Retry now** (a button there and on
Health, `POST /api/v1/admin/retry/connectors[?name=site-quotes]`, admin role, audited as `connector-retry-now`, token scope
`packs:admin`) tries at once. Saving the file starts the count again. Failures and recoveries are audited (`connector-retry-failed`,
`connector-recovered`). All settings: [CONFIGURATION.md](../admin/CONFIGURATION.md#retry-settings); the overview:
[OPERATIONS_GUIDE.md](OPERATIONS_GUIDE.md#138-retries-what-failed-is-tried-again-by-itself).

What is **not** live: a connector defined in the server's own configuration (`application.yaml`, deprecated) and a change of
`connectors-dir` itself; both need a restart.

### 9.8 Admin → Connectors

**Admin → Connectors** (`/admin/connectors`, administrators only; the Admin menu and the Admin tabs link to it; `?` opens this page).
On the demo lakes it lists the seven packs' connectors: the three trade connectors, the derived `trade-pages`, the Delta domain stores
(`reference-store`, `market-store`, `legal-store`, `credit-store`, `collateral-store`, `risk-store`, `macro-store`, `civic-store`,
`news-store`), and the feeds, `news`, `yfinance` and `trading-stream` (off until you switch them on).

![Admin → Connectors: every connector with origin, plugin, kinds, status, who uses it and when it changed](img/connector_files/01-connectors-list.jpg)

**Uninstalling safely.** The page has one way out for a connector that has a file, **Uninstall…** (there is no separate Delete button;
`DELETE /{name}` remains for scripts and keeps the text in `<data dir>/connectors/history/`). It asks what depends on the connector
(the packs and kinds that read through it, BI datasets that scan it, whether a pack's template would write it back), refuses while
anything does, and otherwise **moves the file** to `<data dir>/uninstalled/` where **Admin → Artifacts → Uninstalled** restores it as a file. A
connector a pack's template names is only disabled or reset. See [OPERATIONS_GUIDE.md section 18](OPERATIONS_GUIDE.md#10-admin--artifacts-install-and-uninstall-safely).

The list shows each connector's **origin** (`file`, `pack` for a template with no file yet, `application` for the deprecated
configuration), plugin, kinds, **status** with the plugin's own health beside it, which packs use it (and for which kinds), and when it
last changed. Filter by text, status or origin. `n` creates a connector and `/` focuses the filter. A connector defined in
`application.yaml` is flagged with the file that would replace it; a bad file shows its problem on its row.

#### Connector files on a cluster

The file `config/connectors/<name>.yaml` is the connector: there is nothing else to look at. Every Admin save (create, edit, enable or disable, restore, reset, delete) writes that file, with the earlier text kept in the history and the action in the audit trail, and hot-reloads the connector. A connector's file is per node, like everything in that folder: a save on one node changes the file of that node. Admin → Health warns when a connector present on two nodes has different text on them; deploy the file to the others, or save it on each. The text is kept exactly as saved: `${VAR}` placeholders stay unresolved, masked credentials are never written, and a credential is only ever an environment or `file:` reference.

#### One connector: its own page

Every connector name in the list is a link (Tab to it, Enter opens it) to `/admin/connectors/<name>`; the packs in *Used by* link to
their pages.

![One connector: summary, TLS certificate expiry, packs that use it, settings, the file and its history](img/connector_files/11-connector-detail.jpg)

The page shows: the plugin, the origin (and the file), whether it is on, the **status** and the plugin's health, the last update and
when the file changed; when the connector failed to start or lost its connection, *failed — next retry in Ns (attempt k)* with the last
error and **Retry now**; **TLS**: on or off, the protocols, whether the host name is checked, and each configured certificate (client
certificate, CA bundle, key store) with its subject and **expiry date and days left** (a warning under 30 days, *expired* once past; a
setting that cannot be read is shown as the error). Then the **kinds it serves**, the **packs and kinds that use it** (links), the
**settings in effect** (credentials shown as `***`), the file as **read-only text**, and the **history** of kept versions.

Buttons: **Edit** opens the editor described below, **Test connection** opens it on the Test tab and runs the test on the saved file,
**Compare or restore** opens the History tab, **Disable / Enable**, **Reset to pack default** and **Uninstall…** behave as on the list
(a pack's connector is never uninstalled; the page asks before it disables one packs use). The editor also opens by address:
`/admin/connectors?name=<name>&tab=history` or `&tab=test`.

![The connector's page on a phone: one column, targets of at least 44 px](img/connector_files/13-connector-detail-phone.jpg)

The pack side is the same: a pack's page lists the connectors it names, each linked here, and flags a missing one with **Create**
([BUILD_GUIDE.md](BUILD_GUIDE.md#318-what-admin--packs-shows-about-a-pack)).

![A pack's page: its connectors, one running, one missing with Create](img/connector_files/12-pack-detail.jpg) ![A pack's page on a phone](img/connector_files/14-pack-detail-phone.jpg)

**New connector** and **Edit** open one editor with four tabs (arrow keys move between them):

![New connector: a form generated from the plugin's settings, with a TLS section](img/connector_files/02-new-connector-form.jpg)

- **Form.** Choose a plugin and the form is generated from its declared settings, grouped (connection, TLS, what it reads, tuning),
  with a required marker, the default as the placeholder, and the description under each field. A **TLS** section appears when the
  plugin supports it. Kinds are picked from the kinds the loaded packs own. Settings the plugin reads that have no field go in **Other
  settings**.
- **YAML.** The same draft as text, comments and all. Leaving the tab reads the text into the form (and tells you if it cannot be
  read); leaving the form renders it. Both tabs edit one draft.
- **Test.** **Test connection** starts a throwaway instance on the draft's settings, asks it what it holds for the kinds it serves
  (business dates and counts), and closes it. Nothing that runs is changed. A failure comes back in words, with a hint for TLS and
  network errors.
- **History** (existing files). Every earlier text with its time and size; **Show changes** shows a diff against the current file
  (added lines start with `+`, removed with `-`, so it reads without colour), **Restore** puts one back (and applies it).

![A literal credential is refused with the reference to use instead](img/connector_files/03-secret-refused.jpg)

Fields flagged as secrets accept only `${ENV_VAR}` or `file:` references. A literal value is refused in the form before anything is
sent, with the message above; the server refuses it too.

![Test connection: a failure explained in words](img/connector_files/04-test-connection.jpg)

![The YAML tab](img/connector_files/05-yaml-tab.jpg)

![History: the earlier versions, and what changed](img/connector_files/06-history-diff.jpg)

**Save** writes the file atomically (a timestamped copy of the old text goes to `<data dir>/connectors/history/`), applies it to that
connector and says what state it reached. **Two editors cannot overwrite each other**: the page sends the version it read (the file's
ETag) and the server refuses a stale one with *changed since you read it*, offering **Load the current version**.

Row actions: **Disable / Enable** (keeps the rest of the file), **Reset to pack default** (for a connector a pack suggests: the file is
replaced by the suggestion and your text is kept in the history), and **Uninstall…** (a file no pack suggests; it moves to the archive
and **Admin → Artifacts → Uninstalled** restores it). A connector a pack suggests is never uninstalled: reset it or disable it. Before
you disable or uninstall a connector that packs use, the page lists them and asks:

![Disabling a connector a pack uses asks first](img/connector_files/09-disable-asks.jpg)

A disabled connector stops at once: its background work (a lake's periodic re-listing of ids, warming) stops with it, so it reads and
logs nothing more. When a disabled connector is the one a kind is routed to and another connector still serves the kind (a Cassandra `trading-store`
beside a disabled `trade-endur`), the others are asked: an id none of them holds answers `DRS-1001 no source holds trade/… (asked
trading-store; trade-endur, which kind 'trade' is routed to, is disabled)`. `DRS-1002 connector … is disabled` is the answer only when
nothing else serves the kind.

**New connector from a pack's suggestion.** The Create link on a pack's Data source panel (or `/admin/connectors?new=<name>`) opens the
editor pre-filled from the suggestion:

![New connector pre-filled from a pack's suggestion](img/connector_files/08-create-from-suggestion.jpg)

The page is keyboard operable (`n`, `/`, arrow keys on the tabs, Esc closes a dialog and returns focus to what opened it), works in
both themes, and on a phone (390 px) fields stack and every target is at least 44 px:

![Phone width](img/connector_files/10-phone.jpg)

### 9.9 The REST API

All under `/api/v1/admin/connectors`; administrators only; a personal API token needs the **`packs:admin`** scope for the writes; every
change is **audited** (setting *names*, never values) as `connector-saved`, `-deleted`, `-reset`, `-enabled`, `-disabled`,
`-restored`, `-tested`, `-generated`, `-migrated`. From the console the same-origin and CSRF rules apply.

| Method and path | Does |
|---|---|
| `GET /` | Every connector: `origin`, `plugin`, `kinds`, `enabled`, `state`, `health`, `lastUpdate`, `updated`, `etag`, `problems`, `usedBy`; plus `directory`, `watch`, `misnamed`, `fileProblems`, `deprecated`, `knownKinds`. |
| `GET /plugins` | The plugins and their settings: `name`, `type`, `required`, `default`, `description`, `secret`, `group`; `tls` says whether the TLS section applies. |
| `GET /{name}` | The effective settings (credentials masked), the file `text`, `etag` (also the `ETag` header), `usedBy`, `packDefault`, `history`, `retry` (attempt, `nextRetryInSeconds`, `lastError`), and `tls`: `null` when no `tls.*` setting is given, else `enabled`, `verifyHostname`, `protocols`, `certificates` (`source`, `subject`, `notBefore`, `notAfter`, `daysLeft`, `expired`), `warnings`, or `error` when the material cannot be read. |
| `GET /{name}/suggestion` | What a loaded pack suggests for that name, `{"pack", "text"}`, to pre-fill New connector. `404` when no pack suggests it. |
| `PUT /{name}` | Create or change. Body `{"text": "<yaml>"}` or the form's fields. An existing file needs `If-Match: <etag>`; `?confirm=true` after switching off a connector packs use. Validates, writes atomically, applies. |
| `DELETE /{name}` | Delete a file no pack suggests (`If-Match`, `?confirm=true` when packs use it). |
| `POST /{name}/enabled` | `{"enabled": false}`: switch on or off, keeping the rest of the file. |
| `POST /{name}/reset` | Put the pack's suggestion back. |
| `POST /{name}/test` | Try a draft (the same bodies as `PUT`), or the saved file with no body. |
| `POST /{name}/validate`, `/render`, `/parse` | Findings for a draft; the YAML of the form's fields; the form's fields of a YAML. Nothing is saved. |
| `GET /{name}/history/{id}`, `POST /{name}/restore/{id}` | A kept version's text; put it back. |

Validation: the name rule; the plugin exists; required settings are present; types (`int`, `boolean`, enums); credentials are
references; unknown settings and unknown kinds are **warnings**. Errors are `DRS-5031` (422); a version conflict, an existing file
without `If-Match`, or an unconfirmed change to a connector in use is `DRS-5032` (409); an unknown connector is `DRS-5033` (404).

```
$ curl -i -X PUT .../api/v1/admin/connectors/site-quotes -H 'If-Match: "0000000000000000"' -d '{"text":"plugin: file\n…"}'
HTTP/1.1 409
{"title":"connector conflict","status":409,"code":"DRS-5032",
 "detail":"DRS-5032 connector 'site-quotes' changed since you read it (now 3e4b312691e95f5c, you have 0000000000000000); reload it and apply your edit again"}
```

### 9.10 The CLI

`drishti.py connector list|get|apply|delete|test|plugins|enable|disable|reset|history`, over a personal token with `packs:admin` for
the writes (`--token-file` or `DRISHTI_TOKEN`, `--server` or `DRISHTI_SERVER`), `--json` for machines, exit 0 done, 1 refused or a test
failed, 2 usage. Real output on the demo lakes (paths shortened to `…`):

```
$ drishti.py connector list
24 connector(s) in …/config/connectors   (watching: WATCHING)
  name              origin  plugin    kinds                                                        state     used by            problems
  ----------------  ------  --------  -----------------------------------------------------------  --------  -----------------  --------
  civic-store       file    delta     jurisdiction,party,office-holder +3                          running   world-economy
  collateral-store  file    delta     collateral-balance,margin-call,simm                          running   counterparty-risk
  credit-store      file    delta     netting-set,credit-limit,cva +1                            running   counterparty-risk
  ecb-estr-feed     file    feed      rate-fixing                                                  disabled  market-data
  ecb-fx-feed       file    feed      fx-spot                                                      disabled  market-data
  fred-feed         file    feed      rate-fixing                                                  disabled  market-data
  legal-store       file    delta     agreement,csa                                                running   counterparty-risk
  macro-store       file    delta     economy,macro-indicator,economic-forecast +4                 running   world-economy
  market-store      file    delta     ir-curve,repo-curve,fx-spot +17                              running   market-data
  news              file    news      news                                                         disabled  news-and-markets
  news-store        file    delta     news-topic,listed-company                                    running   news-and-markets
  nyfed-sofr-feed   file    feed      rate-fixing                                                  disabled  market-data
  reference-store   file    delta     counterparty,counterparty-group,issuer +7                    running   core
  risk-store        file    delta     var,stress-scenario,stress-result +3                         running   market-risk
  trade-calypso     file    delta     trade                                                        running   trading
  trade-endur       file    delta     trade                                                        running   trading
  trade-murex       file    delta     trade                                                        running   trading
  trade-pages       file    derived   counterparty-trades,portfolio-trades,legal-entity-trades +1  running   trading
  trading-stream    file    kafka     trade                                                        disabled  trading
  us-treasury-feed  file    feed      ir-curve                                                     disabled  market-data
  yfinance          file    yfinance  quote                                                        disabled  news-and-markets
  …
[exit 0]

$ drishti.py connector get trade-endur
connector trade-endur   origin file   plugin delta   running   version 1a3893dbc61d97ee
  file …/config/connectors/trade-endur.yaml
  kinds trade
  used by pack trading (trade)
  settings (credentials masked):
  setting                      value
  ---------------------------  ------------------------------------------------------------------
  root                         ${DRISHTI_DELTA_ROOT:${drishti.data.dir:./data}/delta/demo}
  domain                       trading
  layout.trade.columns         TRADE_ID,SOURCE_SYSTEM,PRODUCT_TYPE,PRODUCT_SUB_TYPE,PRODUCT_GROUP,TRADE_STATUS,…
  layout.trade.sort-by         id
  layout.trade.file-rows       250000
  layout.trade.row-group-rows  1000
  union                        true
  table.trade                  trade_endur
  source-name                  trade-endur
[exit 0]

$ drishti.py connector test trade-endur
trade-endur (delta): reachable, health UP (engine: native), 14 ms
  kind   business date  rows  note
  -----  -------------  ----  ----
  trade  2026-10-16     5000
  trade  2026-10-15     5000
  trade  2026-10-14     5000
[exit 0]

$ drishti.py connector apply docs-notes.yaml          # with a literal password: refused
drishti: the server said 422 DRS-5031: DRS-5031 the connector is not valid: settings.password: a credential is never written into a connector file; use an environment reference such as ${PASSWORD} or a file: reference such as file:/run/secrets/password
[exit 1]

$ drishti.py connector apply docs-notes.yaml --test-first
applied docs-notes: running; changed (new connector), root, lookback-days; version 0a2bb09fe66ee59c
  note kinds: kind 'docs-note' is not owned by a loaded pack (loaded: agreement, bond, book, calendar, cap-vol-surface, ccp, and 64 more)
[exit 0]

$ drishti.py connector apply docs-notes.yaml          # lookback-days 10 -> 5
applied docs-notes: running; changed lookback-days; version 1abf33e787d83abf
[exit 0]

$ drishti.py connector history docs-notes
  version             kept at                         bytes
  ------------------  ------------------------------  -----
  20261017T213854795  2026-10-17T21:38:54.794718099Z  276
[exit 0]

$ drishti.py connector disable trade-endur
drishti: the server said 409 DRS-5032: DRS-5032 disabling connector 'trade-endur' would stop serving pack trading (trade); repeat with confirm=true to go ahead
[exit 1]

$ drishti.py connector delete trade-endur
drishti: the server said 409 DRS-5032: DRS-5032 connector 'trade-endur' is named by pack trading's template, so it is not deleted: reset it to the pack's default, or disable it
[exit 1]

$ drishti.py connector delete docs-notes
deleted docs-notes (its text is kept in <data>/connectors/history/)
[exit 0]
```

`apply` takes the name from the file name (`--name` overrides), reads the file's current version and sends it as `If-Match`, so it is
safe in a GitOps job: a concurrent edit makes the job fail instead of overwriting it (`--if-match` pins the version you expect).
`disable … --confirm` goes ahead after the question above. `drishti.py server packs datasource get <pack>` shows a pack's connectors
(section 6) and exits 1 while one is not configured:

```
$ drishti.py server packs datasource get desk-demo
connectors of desk-demo   [files in …/config/connectors; change them with `drishti.py connector ...`]
  connector     state           plugin  kinds from this pack  defined by          problems
  ------------  --------------  ------  --------------------  ------------------  ----------------------------------------
  desk-quotes   running         file    desk-quote            file
  desk-archive  not configured          desk-quote-archive    nothing defines it  connector desk-archive is not configured
  desk-archive is not configured: create it with `drishti.py connector apply desk-archive.yaml` or in Admin -> Connectors
[exit 1]
```

### 9.11 Moving off application.yaml and the old data-source override

**`drishti.sources.connectors` in `application.yaml`** (and `application-<profile>.yaml`, and environment variables such as
`DRISHTI_SOURCES_CONNECTORS_RISK_STORE_SETTINGS_ROOT`) **keep working**, but are deprecated. At start the server logs, per connector:

```
WARN  deprecated: connector 'legacy-feed' is defined in the server's own configuration (drishti.sources.connectors.legacy-feed);
      move it to /srv/drishti/config/connectors/legacy-feed.yaml and remove it from application.yaml. Until then it overrides that
      file setting by setting.
```

The Connectors page flags them too, and opening one and pressing Save creates its file. To move one: write the file (the YAML has the
same keys: `plugin`, `enabled`, `kinds`, `settings`), then delete the block from `application.yaml`.

**The per-store profiles** (`application-postgres.yaml`, `-duckdb`, `-aerospike`, `-mongodb`, `-iceberg`, `-redis`, `-files`) are
**kept working, deprecated**, and every one is also provided as **example files** in `config/connectors.examples/<store>/`. They were
not converted in place because a profile *overlays* the pack's generated files setting by setting, while a file *replaces* the template;
a faithful automatic conversion would hide a behaviour change. Copy the example files you want into `config/connectors/` (mind the
trade connectors' names, section 4) and drop the profile.

**The Admin → Packs → Data source override** (`<data dir>/packs/settings/<pack>.yaml`, written by the earlier Data source panel) is
**unified** with connector files. At the first start after the upgrade the server **migrates** each override into the connector file
of the same name: the override's `enabled` and `settings` are applied on top of the file (or, when there is none, on top of the pack's
template), the original override file is moved to `<data dir>/packs/settings/migrated/<pack>.yaml.<timestamp>` as a backup, and the
file's earlier text, if there was one, goes to `<data dir>/connectors/history/`. An override the server cannot place (no template and
no file say which plugin to use) is **left in place and reported**; nothing an administrator saved is dropped. It is logged and audited
as `connector-migrated`:

```
connectors: data-source override of pack 'risk' for connector 'risk-store' moved into risk-store.yaml
connectors: original override file of pack 'risk' kept in data/packs/settings/migrated
```

The panel itself is now a **view** of the pack's connectors with links to Admin → Connectors; the `PUT`, `DELETE` and `…/test` calls of
`/admin/packs/{name}/datasource` and `drishti.py server packs datasource set|test|reset` are gone (use the connector API and
`drishti.py connector`).

### 9.12 Health and problems

- **Admin → Health** has a `connectors` block (count, failed, `badFiles` with the message, watch state, folder, deprecated names), and a
  pack row lists `connectors`, `connectorsDown` and `connectorsNotConfigured`. A bad file or a missing connector makes the overall
  status **degraded**.
- `GET /api/v1/admin/health` → `connectors.watch` is `WATCHING`, `POLLING`, `OFF` or `STOPPED: <reason>`; `sources[]` has each
  connector's health (`UP (engine: native)` for a Delta connector on the local disk).

| You see | Cause and fix |
|---|---|
| `unknown key 'x'` | Only `plugin`, `enabled`, `kinds`, `description`, `settings` (and `name`) at the top level; put plugin settings under `settings:`. |
| `'enabled' is true or false … not 'maybe'` | Write `true` or `false` (quotes are fine), or a `${VARIABLE:true}` placeholder. |
| `says name 'b' but the file name is the connector's name` | Rename the file, or delete the `name:` line. |
| `'password' refers to an environment variable that is not set` | Set the variable in the server's environment, or give a default for a non-secret value. The last good configuration keeps running. |
| `a credential is never written into a connector file` | Use `${ENV_VAR}` or `file:/path`. |
| `no plugin named 'x'` | The plugin is not installed; `drishti.py connector plugins` lists them. |
| A connector is `FAILED` | The plugin could not start; the message is the plugin's. **Test connection** shows it with a hint. It is retried by itself (*failed — next retry in Ns (attempt k)*, [above](#a-connector-that-fails-to-start-or-loses-its-connection-is-retried)); fix the cause and it starts, or press **Retry now**. |
| A Delta connector `DOWN: cannot reach …/data/delta/demo/trading` | No lake there: `drishti.py lake demo` (or point `root` / `DRISHTI_DELTA_ROOT` at your lake). |
| A Delta connector `DEGRADED: cannot read <kind> <date>: NoSuchFileException …` | A table was rewritten while the server read it (a `lake demo` or `lake build` run): it clears at the next reindex (about a minute); if not, *Admin → Caches* → purge that connector. |
| A file in the folder is ignored | Its name breaks the rule (listed on the page and in the log). |
| Edits are not picked up | `connectors-watch: off`, or `watch` is `STOPPED`; check Health and the log, or restart. |
| `DRS-1011` | A pack routes a kind to a connector with no file: create it ([9.6](#a-pack-names-a-connector-nobody-defined)). |

#### Health in one call

`GET /api/v1/admin/health` (Admin → Health) answers, in order:

| Field | Meaning |
|---|---|
| `status` | `OK`; `DEGRADED` (a connector is down or stale, one failed to start, or a pack has problems); `DOWN` (no connector is up) |
| `summary` | counts: `sources`, `sourcesDown`, `failedToStart`, `packs`, `packsWithProblems` |
| `sources[]` | one row per running connector: `status` (`UP` when `health` starts with `UP`, else `DOWN`), `health` text, `kinds`, `live`, `dated`, `search`, `reads` (`reads`, `found`, `notHeld`, `errors`, `lastError`, `lastErrorAt`, `lastOkAt`, `p50Ms`, `p99Ms`), `cache`, `lastUpdate` (when it last received new data; null for sources read on demand), `staleAfter` and `stale` (nothing new within the connector's `stale-after` setting, see [CONFIGURATION.md](../admin/CONFIGURATION.md#connector-settings-plugin-by-plugin)). Down connectors are listed first |
| `failedToStart` | connector name → error message |
| `packs[]` | each pack's `connectors`, `connectorsDown` (running but down), `connectorsOff` (switched off, or failed to start) |
| `overrides` | pack definitions overridden by a child pack |

```bash
curl -s http://localhost:18480/api/v1/admin/health | jq -r '.sources[] | select(.status=="DOWN") | "\(.name): \(.health)"'
curl -s http://localhost:18480/api/v1/admin/health | jq '.packs[] | {name, connectorsDown, connectorsOff}'
```

#### Every health text

| Connector | Health texts |
|---|---|
| `demo` | `UP` |
| `derived` | `UP (nothing computed yet)`, `UP`, `DOWN: <why the last computation failed>` |
| `file` | `UP`, `DOWN: no directory <root>` |
| `rest` | `UP` (a failed start is under `failedToStart`; `DOWN: not started` only before `start` has run) |
| `jdbc` | `UP`, `DOWN: not started`, `DOWN: <error> (reconnecting)` |
| `delta` | `UP`, `DOWN: cannot reach <root>/<domain>`, `DOWN: no Delta tables under <root>/<domain>` |
| `aerospike` | `UP`, `DOWN: not connected to Aerospike` |
| `kafka` | `DOWN: not started`, `UP (catching up)`, `UP`, `DOWN: no connection to the broker (reconnecting)`, `DOWN: <message> (retrying)`, `DOWN: <Exception>: <message> (reconnecting)` |
| `activemq` | `DOWN: not started`, `DOWN: connecting to <broker-url>`, `UP`, `DOWN: connection to the broker lost (reconnecting)`, `DOWN: <message> (reconnecting)`, `DOWN: <Exception>: <message> (reconnecting)`, `DOWN: <reason> (messages are not acknowledged and come again)`, `UP (state store over its budget: X of Y GB; the oldest entities are being evicted)`, `UP (state store over its budget: X of Y GB; nothing is dropped: raise state.max-gb or add disk)` |
| `rabbitmq` | `DOWN: not started`, `DOWN: <Exception>: <message> (retrying)`, `UP`, `DOWN: connection lost (recovering)`, `DOWN: consumer cancelled on <queue>`, and the same three state-store texts as `activemq` |
| `s3` | `UP`, `DOWN: <error> (retrying)` |
| `news`, `yfinance` | `UP` (nothing is fetched at start), `DOWN: <reason> (tried again on the next request)` after a failed call, `UP` after the next success |
| `feed` | `UP`, `DOWN: <Exception>: <message>`, `DOWN: the feed returned no data (serving the last data)`, `DOWN: the feed returned no data` (`DOWN: not fetched yet` only before `start` has run: the first fetch is inside it) |

Every connector recovers without restarting Drishti, and every one starts even when its store is down (see
[Reconnecting and failures](DEVELOPER_GUIDE.md#1010-reconnecting-and-failures)).

#### Installed but not configured

Every plugin on the class path runs as itself unless it is switched off, except one used only through named
connectors. A plugin that cannot run without a setting (`kafka` needs `topics`, `feed` needs `feed`, `jdbc` needs
`url`), as itself or as a named connector, then logs

```text
source plugin kafka is installed but not configured (kafka needs settings.topics); it stays idle
```

and is **not** counted as a failure (nor listed under `/sources`). Other plugins missing a required setting (`rest`
without `base-url`, `s3` without `bucket`) fail to start and appear under `failedToStart`; the server still runs.

#### Caches and purging

`GET /api/v1/admin/caches` (Admin → Caches) lists the engine's cache and every connector that has one (real output,
shortened):

```json
[
  {"name": "engine", "type": "Layouts and shape fingerprints", "stats": {"layoutHitRate": 0.774, "fingerprints": 139, "layouts": 103}},
  {"name": "trade-endur", "type": "Connector", "stats": {"timeTravel": 0, "partitions": 3, "tables": 1}},
  {"name": "ecb-fx-feed", "type": "Connector", "stats": {"series": 10, "observations": 640, "fetchedAt": "2026-10-01T01:35:09.770409225Z"}}
]
```

**Purge** (one connector, or **Purge all**) is safe at any time and is written to the audit log. What it does
depends on the connector:

| Connector | A purge |
|---|---|
| `delta` | drops cached partitions, latest versions and time-travel versions; the next reads go to storage |
| `kafka` | drops the memory cache and clears the disk cache; reads come back from Kafka by offset |
| `activemq`, `rabbitmq` | drops the memory cache only: the state store is the only copy and is never purged |
| `aerospike` | forgets the days of promoted bins and re-reads the kinds' dates and the ids now |
| `s3` | drops cached reads and misses |
| `feed` | clears the data, then refetches now (a failed refetch leaves nothing to serve until the next good refresh) |

Purge after you correct data in place (a restated lake date) when you do not want to wait for `refresh-seconds`.

#### Disk state

| Folder | Whose | Can it be deleted? |
|---|---|---|
| `./data/state/<connector>` (`state.root`, `state.dir`) | ActiveMQ and RabbitMQ state stores | only deliberately, server stopped: it is the only copy of what the queues delivered |
| `./data/cache/<connector>` (`DRISHTI_CACHE_ROOT`, `disk-cache.dir`) | Kafka disk cache | yes: it starts empty on every run anyway (the topic is replayed) |

Put both on local disk, not a network share, and size them; the two work differently:

| | State store (ActiveMQ, RabbitMQ) | Kafka disk cache |
|---|---|---|
| What it keeps | the latest value of each entity (level compaction): size follows the entities held | every message of the day (FIFO compaction): size follows the messages |
| Budget | `state.max-gb` (10), per connector; past it `state.when-full`: `evict-oldest` (default) removes the entities written longest ago until under 90%, logged and counted in `evicted`; `warn` keeps everything and says so in health | `disk-cache.max-gb` (10); past it the oldest files are dropped |
| After a crash | nothing acknowledged is lost with `state.durability: sync` (default); `wal` can lose the last moments on a power loss; `none` up to the 32 MB write buffer | refilled from the topic (no write-ahead log) |
| Disk to plan | about twice `state.max-gb` (a compaction after an eviction briefly needs room), plus the 64 MB write-ahead log | `disk-cache.max-gb` |

`state.reset-at: "06:00"` clears a state store's disk daily (the memory cache and type-ahead keep what they hold), for
state that should start empty each day.

#### Timeouts

`drishti.sources.fetch-timeout` (10 s) bounds the **whole** read, across every connector tried in turn, not each one.
If a slow store sits in front of others (a REST service, a remote database), either raise it or move the slow store
later in the order (a route to the fast one). Raising it past 15 s also needs the console's `backend.timeout_seconds`
raised above it:

```yaml
drishti:
  sources:
    fetch-timeout: 20s
```

A read that runs over ends with `DRS-1004 timed out reading <kind>/<id>`; the connector's own timeouts
(`timeout-ms` for `rest`, `timeout-seconds` for `feed`, `connect-timeout-ms` for `aerospike`) should be at or below
it.

#### Capacity tips

- **Memory per connector**: `delta` `cache-mb` (512) of partitions; `kafka` `cache-mb` (256) plus about 0.4–0.5 GB
  per million entities for its index and type-ahead (estimated); `activemq`/`rabbitmq` `cache-mb` (128) plus a few
  hundred bytes per entity held (estimated); `aerospike` `columns-cache-mb` (1024) of promoted
  bins plus one id per entity in the index set (not the days kept). Add them up across connectors when sizing the heap (`-Xmx`).
- **Database connections**: `pool-size` (20, the site default `drishti.db.pool.size`) per database connector, per server; the sum of the pools of all
  servers must fit the database's `max_connections` ([OPERATIONS_GUIDE.md](OPERATIONS_GUIDE.md#148-capacity-figures-database-pools-and-bi-scale)).
- **Writing a database connector**: do not open connections yourself. Build a `ResourcePool` from `com.ash.drishti.api.pool` with
  `PoolSettings.resolve(ctx.settings())` (it reads `pool-size`, the site default and the other `pool-*` keys), borrow in a try-with-resources
  (`try (var lease = pool.borrow()) { ... }`) for one unit of work, `lease.invalidate()` on a broken connection, and return `pool.stats()` from
  `SourcePlugin.poolStats()` so Admin and Prometheus show it. For a driver with its own pool (MongoDB, Aerospike) map the same settings onto it. `ConnectionDisciplineTest`
  fails the build on a connection opened outside a try-with-resources and on a field that holds one; the JDBC plugin's `JdbcPool` is the model.
- **Background work**: `refresh-seconds` (Delta 10, with a search re-index every 6 × that; Aerospike 60),
  `rescan-seconds` (`file` 30, `s3` 60), `refresh-minutes` (feeds 60). Shorter means fresher search and more load on
  the store.
- **Read latency** per connector is in `reads.p50Ms` and `reads.p99Ms`; a high `notHeld` count on a connector early in
  the order means it is being asked for kinds or ids it does not have: give it `kinds:`.
- **Several servers** each read every Kafka topic in full and each need their own `client-id` (ActiveMQ) and their own
  queues (both message brokers).
- **Message-queue throughput**: with `state.durability: sync` (the default) each message waits for a disk sync, so a
  connector applies about one message per sync, typically thousands a second on an SSD. Put `state.root` on an SSD,
  split a heavy feed over several connectors (each has its own store), or use `wal` where the last moments before a
  power loss may be lost.

### 9.13 Decisions

- **Files replace templates, they do not merge.** Merging would make a site's file depend on what a pack happens to contain in its next
  version. A file says everything the connector needs.
- **Kinds come from the pack's `routes:`**, optionally narrowed by the file's `kinds:`, rather than only from the file: the author of a
  pack knows which kind lives where, and the same connector can then serve several packs.
- **A union is declared by the connectors, not the pack.** `union: true` on each slice lets a site add a fourth source system by adding
  one file, with no change to the pack.
- **The file name is the identity**, so a rename is a different connector, and two files cannot disagree about a name.
- **A literal credential is refused at the authoring surfaces, but not when the file watcher meets one**, so a hand edit never takes a
  running connector down; it is flagged instead.
- **Unknown settings and kinds are warnings.** Plugins read free-form keys, and a connector may be created before its pack is loaded.
- **Reset, not delete, for a pack's connector**, so a deleted file does not silently reappear at the next start.
- **What the server keeps about the files is in the data folder**, visible (`generated/`, `history/`), so the connector folder holds
  only what the site wrote.
- **Restarts drain.** A restart waits `connectors-drain` before closing the old instance so reads in flight finish; during that window
  and the new instance's start the connector's kinds are not served: their reads wait for the new instance (up to 30 s, then
  `DRS-1015`).

### 9.14 Connection pools

Every database connection a connector opens comes from a **pool** of a size you set, **20 by default**. The rules, which an architecture
test (`ConnectionDisciplineTest`) enforces on the source:

- **One unit of work, then back.** A connection is borrowed in a `try`-with-resources block for a query, a batch or a day's publish and
  returned at once, whatever happens (an exception, a timeout, a cancelled request). It is never held across a stream to a client, a
  wait on the network, a live subscription or a person's think-time, and no field of any class holds a `Connection`, `Statement` or
  `ResultSet`. A returned connection with an open transaction is rolled back and its auto-commit restored; a broken one is closed and
  replaced.
- **No statement runs for ever.** `statement-timeout` (default 5 minutes) is set on every connection (PostgreSQL `statement_timeout`).
- **Leaks are visible.** A connection borrowed longer than `pool-leak-threshold` (default 60 seconds) is logged once with the stack of
  the code that borrowed it, counted, and shown on the connector's page and Admin → Health; it goes back when the borrower finishes.
- **Waiting is bounded.** A caller waits `pool-connection-timeout` (default 10 seconds) for a free connection, then fails with a message
  that says how busy the pool is.

The settings are the same for every database connector, under its `settings:`; a connector that sets none inherits the **site
default**, `drishti.db.pool.*` in `application.yaml` ([CONFIGURATION.md](../admin/CONFIGURATION.md#drishtidbpool--database-connection-pools)).
The example files in `config/connectors.examples/` set none, so they follow the site.

Every setting of this part, with its type, default, environment variable, meaning and whether a change needs a restart, is in the one configuration document: [CONFIGURATION.md, every plugin's settings (the `pool-*` settings)](../admin/CONFIGURATION.md#every-plugin) (also at Help → Configuration in the console).

| Store | What is pooled | Notes |
|---|---|---|
| PostgreSQL / any JDBC ([POSTGRES_CONNECTOR.md](../../plugins/drishti-plugin-jdbc/README.md#connection-pool)) | JDBC connections, opened on first use | all settings above; the loader takes `--pool-size` |
| MongoDB ([MONGODB_CONNECTOR.md](../../plugins/drishti-plugin-mongodb/README.md#connection-pool)) | the driver's pool (`maxPoolSize`, `minPoolSize`, `maxWaitTime`) | the statement timeout is the socket read timeout |
| Redis ([REDIS_CONNECTOR.md](../../plugins/drishti-plugin-redis/README.md#connection-pool)) | Lettuce connections, each multiplexed | another is opened only when the open ones are busy (`pool-pipeline-depth`); `pool-max-lifetime` and `pool-connection-timeout` do not apply |
| Aerospike ([AEROSPIKE_CONNECTOR.md](../../plugins/drishti-plugin-aerospike/README.md#connection-pool)) | the client's pool **per node** | `pool-size` is per node; `pool-connection-timeout` and `pool-max-lifetime` do not apply; borrows are not timed |
| The identity database on PostgreSQL | a Hikari pool (`drishti.identity.database-pool-size`, else the site default) | leak detection is Hikari's |
| **SQLite** (the default identity database) and **DuckDB** | **not pooled** | see below |

**Why SQLite and DuckDB are exempt.** Both are embedded files opened inside the server process: no network, no server-side connection
limit, no idle timeout to meet, nothing a pool protects. SQLite allows one writer at a time whatever the number of connections, so the
identity database keeps a small fixed pool (8) on SQLite; a DuckDB connector's `pool-size` (default 4) is a limit on reads at once.
Both still close everything they open (try-with-resources; `ConnectionDisciplineTest` covers them) and appear on Admin with their reads
running and waiting, marked *embedded, not pooled*.

**Seeing it.** The connector's page (Admin → Connectors → the connector) has a *Connection pool* line: *4 of 20 in use, 2 idle, 6 open,
0 waiting, longest current borrow 2.3 s*, with a note when a borrow passed the leak threshold or callers gave up waiting. Admin → Health
shows the same in a column, the identity database below the connectors, and a red line when every connection is in use with callers
waiting, or a borrow was held past the threshold. `GET /api/v1/admin/health` carries it as `sources[].pool` and `identityPool` (real
output of the default SQLite identity database; a pool that cannot time borrows reports `longestBorrowMillis: -1`):

```json
"identityPool": { "type": "sqlite", "exempt": true, "max": 8, "open": 2, "inUse": 0, "idle": 2, "waiting": 0,
                  "longestBorrowMillis": -1, "leaksDetected": 0, "timeouts": 0 },
"poolsAttention": []
```

A connector's pool looks the same with `"type": "jdbc"` and `"exempt": false`. Prometheus has one series per connector (`connector`
and `type` labels): `drishti_db_pool_max`, `_open`, `_in_use`, `_idle`, `_waiting`, `_longest_borrow_seconds`, `_leaks` and `_timeouts`
([OPERATIONS_GUIDE.md, Monitoring](OPERATIONS_GUIDE.md#134-prometheus-grafana-and-the-suggested-alerts)).

What the log says when a borrow is held (from the pool's test, `pool-leak-threshold: 150ms`; the frames after the first two are the
borrower's):

```
WARNING: connection leak suspected in the pool-test pool: borrowed by thread main for 150 ms (pool-leak-threshold 150 ms); borrowed at
java.lang.Throwable: borrowed by main
	at com.ash.drishti.api.pool.ResourcePool$Lease.<init>(ResourcePool.java:99)
	at com.ash.drishti.api.pool.ResourcePool.borrow(ResourcePool.java:178)
	at com.ash.drishti.plugin.jdbc.JdbcPoolTest.aConnectionHeldPastTheLeakThresholdIsCountedAndReleasedWhenClosed(JdbcPoolTest.java:204)
```

And when the pool is exhausted (`pool-size: 1`, `pool-connection-timeout: 250ms`):

```
no free connection in the pool-test pool within 250 ms (1 of 1 in use, 0 waiting, longest borrow 253 ms); raise pool-size or find what holds connections
```

Sizing the pools against the database's `max_connections` is in
[OPERATIONS_GUIDE.md](OPERATIONS_GUIDE.md#148-capacity-figures-database-pools-and-bi-scale); the symptoms and what to do are in
[OPERATIONS_GUIDE.md](OPERATIONS_GUIDE.md#212-things-that-failed-to-load-or-lost-their-connection).

### Status words, warnings and what the page tells you

The badge never says *running* for a connector that is not serving. **running** means up and healthy; **running, with a warning** means
up with something to read beside it (unreadable lines, or every dated folder is after today: *0 rows served for 2026-10-12: 'note'
holds 2099-01-05 to 2099-01-06, none on or before 2026-10-12*); **degraded** is a connector that serves but some of whose tables
cannot be read (its health starts `DEGRADED`, shown in full beside the badge: an Iceberg table moved after it was written, with the
`relocate` to copy); **down, retrying** is a connector that runs but reports DOWN or is
being retried; **failed**, **disabled** and **not started** keep their words. The list, the connector's page, a pack's *Data source*
panel and `drishti.py connector list|get` use the same words. The pages poll every 5 seconds: a connector's own page updates its status
as it starts, and the list and the Packs list offer a **Refresh** banner when something changed on disk, in the CLI or by another
administrator.

In the editor, a refused Save or Test shows its message in the bar beside **Save** (never below a long form), names the failing field,
focuses it and scrolls to it; **Save** is disabled and says *Saving…* while it works. Credential fields are masked while you type; a
literal credential is refused and **Test connection** says it did not try. **History** lists each kept version with the time in the
page's zone and hides Test and Save (restoring is its own button). A file connector made from a generated template says in its
description to set `root` (or the `DRISHTI_FILES_ROOT` environment variable); its page repeats it with the root now in effect.

## 10. TLS and the outbound proxy

Every connector that talks to a server over the network reads its TLS configuration from the same keys, under the
prefix `tls.`, and builds its secure connection with the same code (`com.ash.drishti.api.tls` in the plugin API). You
learn the settings once: a private CA, a PKCS12 or JKS store, a PEM file pair for mutual TLS, hostname verification and
the rest work the same for Kafka, ActiveMQ and RabbitMQ, and for each connector that has taken the module on since.

Behind a forward proxy, the same connectors also take the shared `proxy.*` settings ([DATA_GUIDE.md, the outbound proxy](#outbound-http-proxy-for-connectors-ai-providers-and-mcp-servers)); an `https://` target is tunnelled with `CONNECT` and keeps every `tls.*` setting described here.

This is the reference. The connector documents show complete configurations for their own protocols:
[Kafka](../../plugins/drishti-plugin-kafka/README.md#security-tls-sasl-and-confluent) (TLS, SASL, Confluent, Schema Registry),
[ActiveMQ](../../plugins/drishti-plugin-activemq/README.md#security) (`ssl://`), [RabbitMQ](../../plugins/drishti-plugin-rabbitmq/README.md#security) (`amqps://`,
client-certificate login), and for the rest see the [matrix](#connector-matrix) below, whose rows lead to each connector's Security
section. A plugin author who wants the module in a new connector reads
[the developer guide](DEVELOPER_GUIDE.md#108-tls-for-a-connector-the-shared-module).

### Connector matrix

Every connector with a network client reads these settings. This is what each does with them, and how that was checked.

| Connector | TLS is on when | Client certificate | `protocols` / `cipher-suites` | `verify-hostname: false` | Expiry in health | Checked against |
|---|---|---|---|---|---|---|
| [Kafka](../../plugins/drishti-plugin-kafka/README.md#security-tls-sasl-and-confluent) | `security.protocol: SSL` / `SASL_SSL` | yes | yes | yes | yes | a Kafka broker in Docker |
| [ActiveMQ](../../plugins/drishti-plugin-activemq/README.md#security) | `broker-url` has `ssl://` | yes | checked, not narrowed (JVM's) | yes | yes | ActiveMQ Classic in Docker |
| [RabbitMQ](../../plugins/drishti-plugin-rabbitmq/README.md#security) | `amqps://` | yes (also the log-in, `auth-mechanism: external`) | yes | yes | yes | RabbitMQ 4.1 in Docker |
| [Redis](../../plugins/drishti-plugin-redis/README.md#security-tls-and-credentials) | `rediss://` | yes | yes | yes (chain only) | yes | Redis 8.2 in Docker, `tls-auth-clients yes` |
| [REST](../../docs/connectors/types/rest/README.md#tls-for-the-rest-connector) | `https://` `base-url` | yes | yes | yes | yes | an HTTPS server in the test process |
| [Feeds](../../docs/connectors/types/feed/README.md#security) (a mirror) | `https://` `url` | yes | yes | yes | yes | an HTTPS server in the test process |
| [News](../../docs/connectors/types/news/README.md) (a mirror or proxy) | `https://` `base-url` | yes | yes | yes | no | no TLS-specific test: it applies the shared `TlsHttp` as REST and Feeds do |
| [yfinance](../../docs/connectors/types/yfinance/README.md) (a mirror or proxy) | `https://` `base-url` | yes | yes | yes | no | as News |
| [S3](../../plugins/drishti-plugin-s3/README.md#tls-for-the-s3-connector) | `https://` `endpoint` | yes | checked, not narrowed | **refused** (the AWS client always checks) | yes | an HTTPS stand-in S3 in the test process |
| [MongoDB](../../plugins/drishti-plugin-mongodb/README.md#security-tls-certificate-log-in-and-credentials) | `tls.enabled` or `tls=true` in the `uri` | yes (also the log-in, `auth-mechanism: x509`) | checked, not narrowed | yes | yes | MongoDB 7 in Docker, `requireTLS`, x.509 user |
| [PostgreSQL](../../plugins/drishti-plugin-jdbc/README.md#security-tls-and-credentials) (`jdbc:postgresql:`) | `tls.enabled` | yes (also the log-in, `cert` in `pg_hba.conf`) | yes | yes (`sslmode=require`) | yes | PostgreSQL 18 in Docker, `ssl=on` |
| [AI providers](AI_GUIDE.md#119-tls-the-same-block-as-the-connectors) (every `type:` in `config/ai`, the console's AI and MCP clients) | `https://` `base-url` | yes | yes (the console: `TLSv1.2` / `TLSv1.3`, OpenSSL cipher names; Bedrock: no) | yes (Bedrock: **refused**) | yes (Admin → AI) | fake HTTPS servers with a test CA, mutual TLS and a CONNECT proxy tunnel, in Java and Python; the console reads PEM and PKCS12, not JKS |
| other JDBC drivers | not mapped: the driver's own options in the URL | | | | | documented only |
| [Aerospike](../../plugins/drishti-plugin-aerospike/README.md#security) | `tls.enabled` | yes (also the log-in, `auth-mode: pki`) | yes | yes (no TLS name is given) | yes | unit tests of the wiring; the live check is a manual procedure (the server's TLS is Enterprise Edition) |
| [Cassandra](../../plugins/drishti-plugin-cassandra/README.md#security) (also ScyllaDB; Astra: its bundle) | `tls.enabled` | yes | yes | yes | yes | Cassandra 5 in Docker, `client_encryption_options` with `require_client_auth: true` |
| [Delta](../../plugins/drishti-plugin-delta/README.md#security-tls-and-credentials) (native engine) | `https://` `s3.endpoint` | yes | yes | yes | yes | an HTTPS stand-in S3 in the test process; refused with `engine: hadoop` |
| [Iceberg](../../plugins/drishti-plugin-iceberg/README.md#security-tls-and-credentials) (REST catalog's object store) | `https://` `s3.endpoint` | yes | checked, not narrowed | **refused** | yes | an HTTPS stand-in S3 in the test process; refused with `catalog: hadoop` |

The rule is the same everywhere: `tls.*` against an address that is not TLS is a start-up error, never a silent downgrade, and a
wrong file or password stops the connector at start naming the setting and the file. Where a library has no hook for a
private CA, the connector says so at start and names the JVM truststore as the way out; those places are the Hadoop S3A
engine and path catalogs (Delta, Iceberg), and the REST catalog's own connection (Iceberg).


### 10.1 The settings

All keys are connector settings (`settings:` of a pack connector or a site source), written with the prefix `tls.`.
Every one is optional.

| Key | Default | Meaning |
|---|---|---|
| `tls.enabled` | `false` | Use TLS. (A connector whose address already says so, such as `amqps://` or `ssl://`, or Kafka's `security.protocol: SSL`, uses TLS without it. Setting it to `true` against a plain address is a start-up error, not a silent downgrade.) |
| `tls.ca-file` | | Trust: a PEM file holding one or many CA certificates, or the PEM text itself (a value that contains `-----BEGIN` is taken as text, which suits a secret passed in an environment variable). |
| `tls.truststore` | | Trust: a PKCS12 or JKS truststore, by path. |
| `tls.truststore-password` / `tls.truststore-password-file` | none | Its password. |
| `tls.truststore-type` | detected | `PKCS12` or `JKS`; detected from the file when absent. |
| `tls.trust-jvm-default` | `true` when neither `ca-file` nor `truststore` is given, else `false` | Also trust the authorities the JVM trusts (the public CAs). `true` beside a `ca-file` or `truststore` merges them. |
| `tls.cert-file` | | Client identity: a PEM file with the certificate chain, leaf first. |
| `tls.key-file` | | Client identity: the PEM private key. PKCS#8, PKCS#1 RSA, SEC1 EC, or encrypted PKCS#8. |
| `tls.key-password` / `tls.key-password-file` | none | The password of an encrypted PEM key, or of the key inside a keystore (it defaults to the keystore password). |
| `tls.keystore` | | Client identity: a PKCS12 or JKS keystore, by path. Use this **or** `cert-file` with `key-file`, not both. |
| `tls.keystore-password` / `tls.keystore-password-file` | none | Its password. |
| `tls.keystore-type` | detected | `PKCS12` or `JKS`. |
| `tls.key-alias` | the only key | Which key of the keystore to present. |
| `tls.protocols` | `TLSv1.3,TLSv1.2` | Protocol versions offered, comma separated. A version the JVM does not support is refused at start. |
| `tls.cipher-suites` | the JVM's | Cipher suites, comma separated. |
| `tls.verify-hostname` | `true` | Check the server's name against its certificate. `false` logs a warning at every start. |
| `tls.insecure-trust-all` | `false` | Do not check the server certificate at all. Refused unless `DRISHTI_ALLOW_INSECURE_TLS=true` is in the environment. |

A connector with a second TLS endpoint of its own reads it under another prefix with the same keys (the Kafka
connector's Schema Registry uses `schema-registry.tls.*`).


### 10.2 What can be trusted

The connector trusts the **union** of whatever you give it.

| You give | The connector trusts |
|---|---|
| nothing | the JVM's authorities (the public CAs, plus anything in `javax.net.ssl.trustStore` / `-Djavax.net.ssl.trustStore`) |
| `tls.ca-file` | the certificates in that file only |
| `tls.truststore` | the certificates in that store only |
| `tls.ca-file` and `tls.truststore` | both, merged |
| any of the above and `tls.trust-jvm-default: true` | the above and the JVM's authorities |

Once you name your own authority, the public CAs are **not** trusted unless you add `trust-jvm-default: true`. That is
deliberate: a broker on a private CA should not be impersonated by any public one.

A CA bundle may hold one certificate or many, and intermediate authorities as well as roots:

```yaml
settings:
  tls.ca-file: /etc/drishti/tls/ca-bundle.pem      # several -----BEGIN CERTIFICATE----- blocks
```

The server certificate must chain to a certificate in the trusted set. Trusting the issuing (intermediate) CA is enough;
you do not need the root.


### 10.3 A client identity: mutual TLS

When the server asks the client for a certificate, give the connector an identity in one of two forms.

**PEM files** (the usual form with a private CA, cert-manager, Vault, Let's Encrypt):

```yaml
settings:
  tls.cert-file: /etc/drishti/tls/client.pem       # certificate, then any intermediates
  tls.key-file: /etc/drishti/tls/client.key        # the private key
  tls.key-password: "${CLIENT_KEY_PASSWORD}"       # only if the key is encrypted
```

The key may be written as any of these (the first line of the file says which):

| First line | Format | Made by |
|---|---|---|
| `-----BEGIN PRIVATE KEY-----` | PKCS#8, any algorithm | `openssl genpkey`, `openssl pkcs8 -topk8 -nocrypt`, cert-manager |
| `-----BEGIN ENCRYPTED PRIVATE KEY-----` | encrypted PKCS#8 (PBES2: PBKDF2 with AES-CBC, or the older PKCS#5/12 schemes the JVM knows) | `openssl pkcs8 -topk8 -passout …` |
| `-----BEGIN RSA PRIVATE KEY-----` | PKCS#1 RSA | `openssl rsa -traditional`, older tools |
| `-----BEGIN EC PRIVATE KEY-----` | SEC1 EC, with its curve | `openssl ecparam -genkey` |

An old OpenSSL-encrypted traditional key (it has a `Proc-Type: 4,ENCRYPTED` header) is not supported; the error shows the
command that converts it ([section 7](#107-making-and-converting-certificates-and-keys)).

**A keystore** (the usual form on a Java estate):

```yaml
settings:
  tls.keystore: /etc/drishti/tls/client.p12
  tls.keystore-password: "${CLIENT_KEYSTORE_PASSWORD}"
  tls.key-alias: drishti                           # only if the store holds more than one key
```

The connector checks that the key belongs to the certificate (it signs a challenge with the key and verifies it with the
certificate) before it connects, so a mismatched pair fails at start with both file names, not later with an opaque
handshake alert.


### 10.4 Passwords never go in a document

A password is read in one of two ways, and a connector document or a site YAML never contains the secret itself:

- **An environment placeholder:** `tls.keystore-password: "${CLIENT_KEYSTORE_PASSWORD}"`. A reference to a variable that is
  not set is a start-up error naming the variable (`${NAME:default}` gives a default).
- **A password file:** `tls.keystore-password-file: /run/secrets/keystore-password` reads the first line of the file, which
  is how Kubernetes and Docker secrets arrive. A missing file is an error naming the file.

Every `…-password` key has a `…-password-file` twin. The settings are not shown by the server's source list or by
Health.


### 10.5 Protocols, cipher suites, hostname verification

**Protocols.** `TLSv1.3,TLSv1.2` by default. Narrow it with `tls.protocols: TLSv1.3`. TLS 1.0 and 1.1 are disabled in
current JVMs and the connector does not re-enable them.

**Cipher suites.** The JVM's strong defaults are used unless you list suites in `tls.cipher-suites`. A suite the JVM does not
support is a start-up error, not silently ignored.

**Hostname verification** is on. The connector checks that the name you connect to (`kafka1.bank.example`) is in the
certificate's subject alternative names (SAN). A certificate with only a Common Name and no SAN is not accepted by a
current JVM. The usual fixes, in order of preference: re-issue the broker certificate with the right names; connect by a
name the certificate carries; and last, `tls.verify-hostname: false`.

`tls.verify-hostname: false` keeps the chain check but not the name check, so anyone holding a certificate from a trusted
authority can impersonate the server. Drishti logs a warning at **every start** while it is off:

```
WARNING: TLS: tls.verify-hostname is false: the server's name is NOT checked against its certificate.
Anyone holding a certificate from a trusted authority can impersonate the server.
```


### 10.6 Insecure trust-all, for development only

`tls.insecure-trust-all: true` accepts any certificate. It exists for a laptop and a self-signed throwaway broker, and it
is refused unless the process environment says so:

```
tls.insecure-trust-all is refused: it switches certificate checking off, so anyone on the network can impersonate the
server. For development only, start the server with DRISHTI_ALLOW_INSECURE_TLS=true
```

With `DRISHTI_ALLOW_INSECURE_TLS=true` set it works and logs `TLS: tls.insecure-trust-all is ON: the server certificate
is NOT checked. Development only.` at every start. Never set the variable in production. (The Kafka connector does not
offer it, because the Kafka client builds its own TLS: trust the broker's CA with `tls.ca-file` instead.)


### 10.7 Making and converting certificates and keys

These are the commands used to produce the files the connectors read; the output below was captured from them.

**A private CA and a client certificate**

```
$ openssl req -x509 -newkey rsa:2048 -nodes -keyout ca.key -out ca.pem -subj "/CN=Example Internal CA" -days 3650
$ openssl req -newkey rsa:2048 -nodes -keyout client-pkcs8.key -out client.csr -subj "/CN=drishti"
$ openssl x509 -req -in client.csr -CA ca.pem -CAkey ca.key -CAcreateserial -out client.pem -days 365
  Certificate request self-signature ok
  subject=CN=drishti
```

A **server** certificate needs its names as SAN, or hostname verification fails:

```
$ openssl req -newkey rsa:2048 -nodes -keyout broker.key -out broker.csr -subj "/CN=kafka1.bank.example" \
    -addext "subjectAltName=DNS:kafka1.bank.example,DNS:kafka2.bank.example,IP:10.0.0.11"
$ openssl x509 -req -in broker.csr -CA ca.pem -CAkey ca.key -CAcreateserial -out broker.pem -days 365 \
    -copy_extensions copy
```

**Between the key formats**

```
$ head -1 client-pkcs8.key
-----BEGIN PRIVATE KEY-----
$ openssl rsa -in client-pkcs8.key -traditional -out client-pkcs1.key        # PKCS#8 -> PKCS#1
writing RSA key          # first line: -----BEGIN RSA PRIVATE KEY-----
$ openssl pkcs8 -topk8 -in client-pkcs1.key -nocrypt -out client-pkcs8.key   # PKCS#1 -> PKCS#8
$ openssl pkcs8 -topk8 -in client-pkcs8.key -out client-enc.key -passout pass:s3cret   # encrypt
                         # first line: -----BEGIN ENCRYPTED PRIVATE KEY-----
$ openssl pkcs8 -topk8 -in legacy-encrypted.key -out client-pkcs8.key        # a Proc-Type: 4,ENCRYPTED key, converted
```

**PEM to PKCS12 and JKS**

```
$ openssl pkcs12 -export -in client.pem -inkey client-pkcs8.key -certfile ca.pem -name drishti -out client.p12 -passout pass:changeit
$ keytool -importkeystore -srckeystore client.p12 -srcstoretype PKCS12 -srcstorepass changeit \
    -destkeystore client.jks -deststoretype JKS -deststorepass changeit
$ keytool -list -keystore client.p12 -storepass changeit
Keystore type: PKCS12
Keystore provider: SUN

Your keystore contains 1 entry

drishti, Oct 10, 2026, PrivateKeyEntry,
Certificate fingerprint (SHA-256): 74:80:89:22:77:07:13:4B:6F:9A:72:7F:69:72:EC:08:73:74:72:C7:E9:45:7F:1A:91:C9:4E:B1:BA:C3:41:8F
```

A truststore from the CA certificate:

```
$ keytool -importcert -alias ca -file ca.pem -keystore truststore.p12 -storetype PKCS12 -storepass changeit -noprompt
Certificate was added to keystore
```

**PKCS12 back to PEM**

```
$ openssl pkcs12 -in client.p12 -passin pass:changeit -nokeys -out client-from-p12.pem    # the certificates
$ openssl pkcs12 -in client.p12 -passin pass:changeit -nocerts -nodes -out key-from-p12.pem   # the key (unencrypted)
```

(`-nodes` writes the key unencrypted; protect the file, `chmod 600`.) A JKS store becomes PKCS12 with `keytool
-importkeystore … -deststoretype PKCS12`, then the line above.

**Checking a pair and an expiry**

```
$ openssl x509 -in client.pem -noout -subject -enddate
subject=CN=drishti
notAfter=Oct 10 13:41:44 2027 GMT
$ openssl x509 -in client.pem -noout -pubkey | openssl md5        # the two digests must be equal
MD5(stdin)= dda642610a340a3c2c142705ec9765a7
$ openssl pkey -in client-pkcs8.key -pubout | openssl md5
MD5(stdin)= dda642610a340a3c2c142705ec9765a7
```


### 10.8 Start-up checks and their messages

The connector builds its TLS context when it starts, so a wrong file or password is a start-up error that names the
setting, the file and the reason, rather than an opaque failure at the first connection. Captured from a real run:

| Mistake | Message |
|---|---|
| a file that does not exist | `tls.ca-file '/etc/drishti/tls/nope.pem': file not found` |
| a PEM file with no certificate | `tls.ca-file '…': no certificate found (expected -----BEGIN CERTIFICATE-----)` |
| wrong keystore or truststore password | `tls.keystore '/etc/drishti/tls/client.p12': wrong password (tls.keystore-password), or the file is damaged` |
| a file that is not a key store | `tls.keystore '…': not a readable key store (tried PKCS12 and JKS): …` |
| an encrypted key without its password | `tls.key-file '…': the key is encrypted; set the key password (key-password or key-password-file)` |
| a wrong key password | `tls.key-file '…': cannot decrypt the key: wrong key password, or an unsupported encryption (…)` |
| a wrong key password inside a keystore | `tls.keystore '…': cannot recover the key 'drishti': wrong key password (tls.key-password; it defaults to the keystore password)` |
| a key for another certificate | `tls.key-file '…/ca.key' does not match the certificate in tls.cert-file '…/client.pem' (its first certificate, CN=drishti): the key is for another certificate. Compare: openssl x509 -noout -pubkey -in cert.pem \| openssl md5; openssl pkey -pubout -in key.pem \| openssl md5` |
| only one of the pair | `tls.cert-file is set but tls.key-file is not: a client identity needs both` |
| two identities | `keystore and tls.cert-file/tls.key-file both set: give the client identity one way` |
| an alias that is not there | `tls.key-alias 'zzz' is not in the keystore (aliases: [drishti])` |
| a truststore with no certificates | `tls.truststore '…': holds no certificates, so nothing would be trusted` |
| an unsupported protocol or cipher | `tls.protocols: 'SSLv9' is not supported by this JVM (supported: […])` |
| an unset environment variable | `tls.keystore-password: the environment variable CLIENT_KEYSTORE_PASSWORD is not set` |
| a missing password file | `tls.keystore-password-file '…': cannot read the password file (file not found)` |

These are not `DRS-` coded: they are connector start-up messages, shown in the log, in Admin → Packs → Data source →
Test connection and in the source's health text, like any other connector's configuration error.


### 10.9 Certificate expiry

At start the connector reads the expiry of every certificate it was given (the client chain and the CA bundle or
truststore; not the JVM's own authorities) and warns:

```
WARNING: TLS: certificate CN=drishti (tls.cert-file) EXPIRED on 2026-09-30; connections will fail
WARNING: TLS: certificate CN=drishti (tls.cert-file) expires in 12 days (2026-10-22)
WARNING: TLS: certificate CN=drishti (tls.cert-file) is not valid before 2026-12-01
```

The warning also reaches the operator without anyone reading the log: while the connector is up, the **health text** of the
source (Admin → Health and Admin → Packs → Data source) carries the soonest expiry once it is less than 30 days away:

```
UP (TLS certificate CN=drishti (tls.cert-file) expires in 12 days (2026-10-22))
```

It still starts with `UP`, so the source counts as up; the sentence is the nudge to rotate. An expired certificate is
warned about but not refused at start: the handshake will refuse it, with the server's reason.


### 10.10 Rotation

The files are read when the connector **starts**. To rotate a certificate, key, CA bundle or store:

1. Write the new files in place (or to new paths and change the setting). Keep the old CA in the bundle until every server
   has switched, so both the old and the new certificate are trusted during the changeover.
2. Restart the source: re-deploy the pack, or restart the server. The connector reconnects with the new material. The
   Kafka and message-queue connectors resume where they stopped (Kafka from its saved offsets; the queues from their own
   state store).
3. Check Health: `UP`, and no expiry sentence.

Do not delete the old files before the restart: a connector that reconnects after a network blip in the meantime reuses
what it loaded at start, but a start with a missing file is the error in [10.8](#108-start-up-checks-and-their-messages).


### 10.11 Troubleshooting

**Look at what the server sends** from the machine Drishti runs on:

```
$ openssl s_client -connect kafka1.bank.example:9093 -servername kafka1.bank.example -showcerts </dev/null
$ openssl s_client -connect kafka1.bank.example:9093 -CAfile ca.pem -verify_return_error -verify_hostname kafka1.bank.example </dev/null | grep -E "Verif|subject|issuer"
$ openssl s_client -connect kafka1.bank.example:9093 -CAfile ca.pem -cert client.pem -key client-pkcs8.key </dev/null   # mutual TLS
```

`Verification: OK` with `-verify_hostname` means the chain and the name both pass. `Verify return code: 20 (unable to get
local issuer certificate)` means the CA you gave is not the issuer (or the chain is missing an intermediate).

| What you see | What it means | Fix |
|---|---|---|
| `PKIX path building failed: … unable to find valid certification path to requested target` | the server's certificate does not chain to anything trusted | add the issuing CA to `tls.ca-file` or the truststore; remember that naming your own CA drops the public ones (`trust-jvm-default: true` keeps them) |
| `No subject alternative DNS name matching kafka1.bank.example found` (or `No subject alternative names present`) | the certificate does not carry the name you connect to | re-issue it with that name as a SAN, or connect by a name it carries; last resort `tls.verify-hostname: false` |
| `certificate_unknown` / `bad_certificate` from the server | the server does not trust the client certificate (mutual TLS) | the client certificate's CA must be in the server's truststore; check `tls.cert-file` includes the intermediates |
| `Received fatal alert: handshake_failure` | no protocol or cipher in common | check `tls.protocols` and `tls.cipher-suites` against the server's; a JVM older than the server's TLS 1.3-only setting |
| `Received fatal alert: certificate_required` / `peer not authenticated` | the server demands a client certificate and none was sent | set `tls.cert-file` and `tls.key-file`, or `tls.keystore` |
| `certificate has expired` / `NotAfter` | a certificate in the chain is past its date | rotate ([10.10](#1010-rotation)); the start-up warning named it |
| `Connection reset` right after connecting | the server expects TLS and the client sent plain text, or the reverse | check the address scheme and port (Kafka SSL listener, ActiveMQ `ssl://`, RabbitMQ 5671) |
| the start-up messages of [section 8](#108-start-up-checks-and-their-messages) | a file or a password | fix the named setting |

The JVM can log the whole handshake: start the server with `-Djavax.net.debug=ssl:handshake` for one run (it is verbose; do
not leave it on).


For plugin authors: the module is `com.ash.drishti.api.tls` (`TlsSettings`, `TlsContexts`, `TlsMaterial`, `Secrets`) in
`drishti-api`; no Spring, no dependencies beyond the JDK. See
[DEVELOPER_GUIDE.md](DEVELOPER_GUIDE.md#108-tls-for-a-connector-the-shared-module).

### Outbound HTTP proxy for connectors, AI providers and MCP servers

Some sites let a server reach the internet (or a vendor's API) only through a forward proxy. Drishti has **one mechanism** for that,
shared by every HTTP-based connector (REST, feeds, news, yfinance, S3, Delta and Iceberg over S3, the Kafka schema registry), by the AI
providers and by the MCP clients. It is configured the same way everywhere, under the prefix `proxy.`, next to the shared `tls.*`
settings ([DATA_GUIDE.md section 10](#10-tls-and-the-outbound-proxy)). The code is `com.ash.drishti.api.proxy` in the plugin API (Spring-free, like the TLS module); the console's
AI and MCP clients, which are Python, follow the same rules in `core/ai/proxy.py`.

Contents: [1](#proxy-1-the-settings) the settings · [2](#proxy-2-where-the-effective-proxy-comes-from) where the effective proxy comes from ·
[3](#proxy-3-the-site-default) the site default · [4](#proxy-4-the-no-proxy-list) the no-proxy list · [5](#proxy-5-https-targets-and-tls) https targets and TLS ·
[6](#proxy-6-credentials) credentials · [7](#proxy-7-what-each-connector-does) what each connector does · [8](#proxy-8-seeing-the-proxy-in-admin) seeing it in Admin ·
[9](#proxy-9-the-console-ai-and-mcp-clients) the console · [10](#proxy-10-what-is-not-covered) what is not covered · [11](#proxy-11-troubleshooting) troubleshooting ·
[12](#proxy-12-how-it-is-tested) how it is tested

#### Proxy 1. The settings

In a connector file the proxy is a `proxy:` block inside `settings:` (the file format flattens it to `proxy.url`, `proxy.username`, ...):

```yaml
# config/connectors/vendor-quotes.yaml
plugin: rest
kinds: [quote]
settings:
  base-url: https://api.vendor.example/v1
  proxy:
    url: http://proxy.corp.example:3128
    username: svc-drishti
    password: ${PROXY_PASSWORD}          # only an ${ENV} placeholder, or password-file: /run/secrets/proxy
    no-proxy: [intranet.example, .corp.example, 10.0.0.0/8]
```

| Setting | Default | Meaning |
|---|---|---|
| `proxy.url` | none | The proxy, `http://host:port` (a bare `host:port` means `http://`). Plain `http://` targets are forwarded through it; `https://` targets are tunnelled with `CONNECT`. Required when any other `proxy.*` key is given. |
| `proxy.username`, `proxy.password` | none | Basic proxy authentication. Give both or neither. The password is a secret: `${ENV}` placeholder only (a literal is refused, [Proxy 6](#proxy-6-credentials)); `proxy.password-file` reads the first line of a file. |
| `proxy.no-proxy` | none | Hosts that connect directly, bypassing the proxy ([Proxy 4](#proxy-4-the-no-proxy-list)). A YAML list or a comma list. |
| `proxy.enabled` | `true` | `false` switches the proxy off for this connector without deleting the block. |
| `proxy: none` | | The bare setting. This connector goes direct whatever the site default says. (`off`, `false` and `direct` mean the same.) |

Ready-made files: [`config/connectors.examples/other/rest-via-proxy.yaml`](../../config/connectors.examples/other/rest-via-proxy.yaml) and [`s3-via-proxy.yaml`](../../config/connectors.examples/other/s3-via-proxy.yaml) (a test keeps every example valid).

The same block works in the files of `config/ai/` and `config/mcp/` ([Proxy 9](#proxy-9-the-console-ai-and-mcp-clients)). The settings appear in the
connector form under **Proxy** for every plugin that speaks HTTP, and `drishti.py connector plugins rest` lists them.

The connection **to the proxy** is plain HTTP. A proxy that must itself be reached over TLS (`https://proxy`) is refused with a message that
says so; the JDK's HTTP client and the AWS clients Drishti uses cannot do it. This does not weaken anything for the target: an `https://` target is
tunnelled with `CONNECT`, so TLS is negotiated end to end with the target.

#### Proxy 2. Where the effective proxy comes from

For each connector, highest first:

1. the connector's own `proxy:` block, or `proxy: none` (direct);
2. the **site default**, `drishti.http.proxy.*` in `application.yaml` ([Proxy 3](#proxy-3-the-site-default));
3. the standard environment variables `HTTPS_PROXY`, `HTTP_PROXY` and `NO_PROXY`, only when the site asks with `drishti.http.proxy.from-environment: true`;
4. nothing: direct connections.

A connector's block replaces the site default **as a whole** (the settings are not merged); give the block its own `no-proxy`. The environment
variables are read by the target's scheme, as every tool does: `HTTPS_PROXY` for an `https://` address, `HTTP_PROXY` for `http://`. Credentials in
those variables (`http://user:pass@host:port`) are honoured and masked everywhere they could be shown. Admin shows which of the four applies
([section 8](#proxy-8-seeing-the-proxy-in-admin)); real values from the library:

```
connector:    {enabled=true, source=connector, sourceLabel=connector, url=http://proxy.corp.example:3128, username=svc-drishti, password=***, noProxy=[intranet.example, 10.0.0.0/8]}
site default: {enabled=true, source=site, sourceLabel=site default, url=http://proxy.corp.example:3128, username=null, password=null, noProxy=[]}
proxy: none:  {enabled=false, source=disabled, sourceLabel=disabled by the connector}
environment:  {enabled=true, source=environment, sourceLabel=environment (HTTPS_PROXY), url=http://envproxy.example:8080, username=bob, password=***, noProxy=[localhost, .local]}
```

#### Proxy 3. The site default

`application.yaml` (or the environment variables Spring maps to it) carries the site's proxy. It is **off** unless `url` is set or
`from-environment` is true:

```yaml
drishti:
  http:
    proxy:
      url: http://proxy.corp.example:3128       # DRISHTI_HTTP_PROXY_URL
      username: svc-drishti                     # DRISHTI_HTTP_PROXY_USERNAME
      password: ${DRISHTI_HTTP_PROXY_PASSWORD:}  # a placeholder (with an empty default); a literal in the file stops the server at start
      no-proxy: [localhost, .corp.example, 10.0.0.0/8]
      enabled: true                             # false keeps the settings but switches the proxy off
      from-environment: false                   # true: with no url, honour HTTPS_PROXY / HTTP_PROXY / NO_PROXY
```

Every setting of this part, with its type, default, environment variable, meaning and whether a change needs a restart, is in the one configuration document: [CONFIGURATION.md, `drishti.http`](../admin/CONFIGURATION.md#drishtihttp) (also at Help → Configuration in the console).

The site default is read once, when the server starts; a change needs a restart. A connector's own `proxy:` block is live like any other
connector setting (the file is applied within seconds). The server logs one line at start: `site HTTP proxy: ProxySettings[site default, proxy.corp.example:3128, user=svc-drishti, password=***]`
(or `off`). The console reads the same `DRISHTI_HTTP_PROXY_*` environment variables for its own AI and MCP clients.

#### Proxy 4. The no-proxy list

An entry is one of:

| Entry | Matches |
|---|---|
| `*` | every host (the proxy is never used) |
| `intranet.example` | that host and its sub-domains (`db.intranet.example`) |
| `.corp.example` or `*.corp.example` | the sub-domains, and the bare domain too (as `curl` does) |
| `10.1.2.3` | that address |
| `10.0.0.0/8`, `fd00::/8` | a block of addresses |
| `svc:8080` | the host `svc` (a port is ignored) |

Matching is case-insensitive. An address or block matches a target that is written as an address; **a host name is never resolved** to check it
against a block, because resolving would itself need the network. List the names too. The standard `NO_PROXY` variable uses the same entries, split on
commas.

#### Proxy 5. https targets and TLS

An `https://` target (the usual case for a vendor API, an S3 endpoint, an AI provider) is reached by sending `CONNECT host:443` to the proxy and then
speaking TLS **through the tunnel to the target**. So every `tls.*` setting of the connector keeps its meaning, unchanged: the private CA in
`tls.ca-file`, a client certificate for mutual TLS, the protocol versions, host-name verification. The proxy sees the target's name and port, never
the traffic. A target whose certificate is not trusted fails exactly as it does without a proxy (`PKIX path building failed`), which the tests check
through the tunnel. The proxy's own credentials go only to the proxy, in the `CONNECT` request, and are never sent to the target.

A proxy that inspects TLS (re-signs certificates) is a different matter: its signing CA must be in `tls.ca-file` or `tls.truststore`, like any
private CA.

#### Proxy 6. Credentials

* The proxy password is **never written in a file**. In a connector file it must be `${ENV}` (or `proxy.password-file`); saving a literal in Admin
  or through the API is refused: `settings.proxy.password: a credential is never written into a connector file`. A password inside the URL
  (`http://user:pw@proxy:3128`) is refused too, with a message that does not repeat it.
* Wherever the proxy is shown (the Admin connector page, the editor, the connection test, health, the API, the log) the username is shown and the
  password is `***`. Health strings and error messages carry the proxy's host and port, never a credential. The AI and MCP loaders refuse a literal
  `proxy.password` and say so without echoing it.
* Authentication is **Basic**. NTLM and Kerberos proxies are not supported ([Proxy 10](#proxy-10-what-is-not-covered)). The JDK refuses Basic credentials for a `CONNECT` tunnel by
  default (`jdk.http.auth.tunneling.disabledSchemes=Basic`); Drishti turns that restriction off in the server's JVM when a proxy with a login is
  configured, unless you set the property yourself. If you do set it to include `Basic`, https targets through a proxy that needs a login will fail with 407.

#### Proxy 7. What each connector does

| Connector | Through the proxy | How |
|---|---|---|
| [REST](../../docs/connectors/types/rest/README.md#outbound-proxy) | yes | the JDK HTTP client's proxy selector and authenticator |
| [Feeds](../../docs/connectors/types/feed/README.md#outbound-proxy) | yes, built-in feeds and a mirror `url` | same |
| [News](../../docs/connectors/types/news/README.md#outbound-proxy) | yes | same |
| [yfinance](../../docs/connectors/types/yfinance/README.md#outbound-proxy) | yes | same |
| [S3](../../plugins/drishti-plugin-s3/README.md#outbound-proxy) | yes, AWS and S3-compatible endpoints | the AWS SDK's proxy configuration; with a proxy the Apache HTTP client is used (the URL-connection client cannot answer a proxy's login when it tunnels https) |
| [Delta](../../plugins/drishti-plugin-delta/README.md#outbound-proxy) over S3 | yes, both engines | native engine: the SDK's proxy configuration; Hadoop engine: `fs.s3a.proxy.*` |
| [Iceberg](../../plugins/drishti-plugin-iceberg/README.md#outbound-proxy) over S3 / REST catalog | yes | the REST catalog's own client (`rest.client.proxy.*`), the S3 file IO (SDK proxy configuration), Hadoop `fs.s3a.proxy.*` for path tables |
| [Kafka](../../plugins/drishti-plugin-kafka/README.md#outbound-proxy) | the **schema registry** only (HTTP) | `HttpURLConnection` with the proxy and a per-connection authenticator. Brokers speak the Kafka protocol, not HTTP, and are not proxied |
| AI providers: OpenAI-compatible, Anthropic ([AI_GUIDE.md](AI_GUIDE.md#outbound-proxy)) | yes | the JDK HTTP client (server), `httpx` (console) |
| MCP Streamable HTTP clients ([AI_GUIDE.md](AI_GUIDE.md#outbound-proxy-1)) | yes | same |
| JDBC, Redis, MongoDB, Aerospike, Cassandra, ActiveMQ, RabbitMQ, files, DuckDB | no | not HTTP: they speak their own protocols (use a TCP-level route if the site needs one) |

When a connector's target is on the no-proxy list its health says so instead of claiming the proxy:
`UP (proxy proxy.corp.example:3128 not used for db.intranet.example: no-proxy)`. Otherwise it ends `... via proxy proxy.corp.example:3128 (connector)`
(or `(site default)`, `(environment)`).

#### Proxy 8. Seeing the proxy in Admin

**Admin → Connectors → a connector** has a **Proxy** row for every plugin that speaks HTTP: the effective proxy (`http://host:port`), the
username and `password ***`, the no-proxy list, and where it came from (*connector*, *site default*, *environment (HTTPS_PROXY)*, or *disabled by the
connector*). When the connector's target is on the no-proxy list it says the connector goes direct. A malformed block shows the problem in red.

The **editor** has a **Proxy** section in the Form tab with the same fields as the file, and a line above them with the effective proxy now, for example
*Effective proxy: http://proxy.corp.example:3128, from the site default; user svc-drishti, password \*\*\*; direct for .corp.example.* A literal
password is refused in the field before anything is saved, like any credential.

**Test connection** starts a throwaway instance with the proxy the connector would use, so the test goes through it, and says so: *The test went
through the connector proxy proxy.corp.example:3128.* (or *The test went direct: the proxy is configured but ... is on the no-proxy list*). A
failure of the proxy itself comes with a plain-words hint (407, refused CONNECT, unreachable). The API shape (`GET /api/v1/admin/connectors/{name}`
and `POST .../test`):

```json
"proxy": { "enabled": true, "source": "connector", "sourceLabel": "connector", "url": "http://proxy.corp.example:3128",
           "username": "svc-drishti", "password": "***", "noProxy": ["intranet.example", "10.0.0.0/8"],
           "target": "api.vendor.example", "viaProxy": true }
```

#### Proxy 9. The console, AI and MCP clients

`config/ai/*.yaml` (providers) and `config/mcp/*.yaml` (MCP servers) take the same block, read by both the server's Java clients and the console's
Python clients:

```yaml
# config/ai/openai.yaml
type: openai
base-url: https://api.openai.com
api-key: ${OPENAI_API_KEY}
proxy:
  url: http://proxy.corp.example:3128
  username: svc-drishti
  password: ${PROXY_PASSWORD}
  no-proxy: [localhost, .corp.example]
models:
  - name: fast
    id: gpt-4o-mini
```

`proxy: none` and the site default work as for connectors. In the server the site default is `drishti.http.proxy.*`; in the console it is the
environment variables `DRISHTI_HTTP_PROXY_URL`, `_USERNAME`, `_PASSWORD`, `_NO_PROXY`, `_ENABLED` and `_FROM_ENVIRONMENT` (the same ones Spring maps to
`drishti.http.proxy.*`, so one set configures both). The console's `httpx` clients do not pick up `HTTP_PROXY` / `HTTPS_PROXY`
from the environment by themselves, so that one setting controls it: set `DRISHTI_HTTP_PROXY_FROM_ENVIRONMENT=true` to take them from the
environment.

#### Proxy 10. What is not covered

* **A proxy reached over TLS** (`https://proxy`): refused with a message. The JDK client and the AWS SDK clients do not support it.
* **NTLM, Kerberos, Digest** proxy authentication: Basic only. (The Apache-based SDK clients could do NTLM; it is not wired.)
* **SOCKS** proxies: `http://` proxies only.
* **PAC files / automatic proxy discovery**.
* **Kafka brokers, JDBC, Redis, MongoDB, Aerospike, Cassandra, ActiveMQ, RabbitMQ**: not HTTP.
* **The server's own other HTTP clients** (the pack registry client, report e-mail, OIDC discovery and JWKS fetch, the collaboration bridge): they are
  not connectors; they use the JDK default (direct) and can be sent through a proxy only with the JVM's own `-Dhttps.proxyHost` family of options
  for those that honour them. Say if a site needs them on the shared mechanism.
* The Admin → AI and MCP pages do not yet display the effective proxy (the connector pages do).
* Hadoop's S3A has no no-proxy list: the S3A proxy is set only when the endpoint is not on the connector's `no-proxy` list, and S3A reads `fs.s3a.proxy.*` once.
* Iceberg's REST catalog client takes a host and port from `rest.client.proxy.*` and has no no-proxy list; Drishti applies the proxy to the catalog only
  when its address is not on the connector's `no-proxy` list.

#### Proxy 11. Troubleshooting

| You see | It means | Fix |
|---|---|---|
| `the proxy proxy.corp.example:3128 refused the credentials (HTTP 407 Proxy Authentication Required): check proxy.username and proxy.password` | The proxy asked for a login and the one given was wrong (or none was given). JDK clients say `too many authentication attempts` after three tries; tunnelled https says `Unable to tunnel through proxy. Proxy returns "HTTP/1.1 407 Proxy Authentication Required"` | Check the username, and the environment variable or file behind `proxy.password`. Test it with `curl -x http://proxy:3128 --proxy-user svc:... https://target`. |
| `the proxy ... refused to open a tunnel (CONNECT) to the target: Unable to tunnel through proxy. Proxy returns "HTTP/1.1 403 Forbidden"` | The proxy does not allow `CONNECT` to that host or port | Ask the proxy's owner to allow the target, usually port 443; check for a custom port in the connector's address. |
| `cannot connect to the proxy 10.1.2.3:3128 (Connection refused)` | Nothing listens there, or a firewall drops it | Check `proxy.url` (host, port, `http://`), that the proxy is up and reachable from the Drishti server. |
| `cannot resolve the name proxy.corp.example (DNS)` | The server cannot resolve the proxy's name | Use the proxy's address, or fix the server's DNS. If the target's name is the one the proxy cannot resolve, the proxy returns 502 and the message names the target. |
| `PKIX path building failed` through a proxy | The target's certificate (or the proxy's re-signing certificate) is not trusted | `tls.ca-file` with the issuer of what the server presents; [DATA_GUIDE.md section 10](#10-tls-and-the-outbound-proxy). |
| `proxy.url: a proxy reached over TLS (https://) is not supported ...` | `proxy.url` starts with `https://` | Use `http://host:port`; https targets are still tunnelled. |
| `proxy.password must be an ${ENV_NAME} placeholder` / `a credential is never written into a connector file` | A literal password | Put it in an environment variable and write `${NAME}`. |
| A connector still connects directly | It is on a `no-proxy` list, says `proxy: none`, or its plugin does not speak HTTP | Admin → Connectors → the connector → **Proxy** row says which. |

Commands to try from the server's host: `curl -v -x http://proxy.corp.example:3128 https://api.vendor.example/` (a `407` here is the same 407),
`curl -v --proxytunnel -x http://proxy.corp.example:3128 https://api.vendor.example/` (the `CONNECT`).

#### Proxy 12. How it is tested

No test uses the internet. `FakeProxy` (`drishti-testkit`) is a small forward proxy on a local port: it forwards plain HTTP, tunnels `CONNECT`,
counts both, can demand Basic `Proxy-Authorization` (answering 407 otherwise) and can refuse `CONNECT`. Tests for REST, feeds, news, yfinance, S3, Delta
(native S3 reader), Iceberg (S3 file IO and the S3A properties), the Kafka schema registry and the AI and MCP clients (Java and Python) check that
requests go through it with the right credentials, that an https target keeps its own TLS through the tunnel (and an untrusted one still fails), that
the no-proxy list bypasses it, that the site default is inherited and `proxy: none` overrides it, and that a literal password is refused. The server
tests check the masked Admin summary and the connection test; a Chromium test checks the connector page and the editor.

## 11. The demo data

> **For staging, small setups and proofs of concept.** The loaders here, `drishti.py lake demo`, `lake build`, `data ingest` (and
> `--watch`) and `pack make` are not a production ETL; Drishti does not ingest your data. Production data is loaded by your own ETL,
> which ends by telling Drishti the batch landed: [DATA_GUIDE.md](#1-telling-drishti-new-data-has-landed).

Drishti's demo data comes in three forms, all production-shaped (UPPER_SNAKE attributes, as the source systems name them), all
fictitious, all deterministic:

| Form | Where | What it is | Built by |
|---|---|---|---|
| **The test data** | `data/testdata/` (committed, about 138 MB of `.jsonl.gz`, 2,863 files, measured 2026-10-28) | sixty business days of every dataset the seven packs read: up to 5,000 trades a day per source system (ENDUR, CALYPSO, MUREX), counterparties, market data, agreements, netting sets, risk results; a first-day snapshot and each later day's changes | `tools/testdata/make_testdata.py` (`--check` says whether the files are current) |
| **The demo lakes** | `data/delta/demo/` (generated, never committed) | the test data as Delta tables, one per dataset, sixty daily snapshots each: what the packs' connectors read | `drishti.py lake demo` (one command, about 15 minutes) |
| **The samples** | `config/packs/<domain>/<pack>/samples/<kind>.jsonl` (committed) | a few hundred documents a pack, the same shape, that the `demo` source serves live and ticking, with no store at all | `tools/packgen/banking/make_data.py` and each pack's generator |

The other stores (PostgreSQL, DuckDB, Iceberg, MongoDB, Redis, Aerospike, Cassandra, JSON-lines files) are loaded with the samples (ten business
days) and, for scale tests, a generated trade book: the scripts in [the other stores](#115-postgresql).

### 11.1 The test data and the demo lakes (the default)

```bash
drishti-console/.venv/bin/python tools/drishti.py lake demo
```

(the IDE run configuration **Build demo lakes**). Measured for sixty days: **943 s (about 15 minutes), 86 tables, about 3.4 GB**, sixty business days
ending on today's business date (the test data's 2026-07-08 to 2026-09-30, moved; `--as-of data` keeps them). The trades go to one
table per source system, `data/delta/demo/trading/trade_endur`, `trade_calypso` and `trade_murex`, partitioned by business date and
`PRODUCT_TYPE`, each read by its own connector (`trade-endur`, `trade-calypso`, `trade-murex`) and served together as one kind,
`trade`; every other dataset goes to `data/delta/demo/<domain>/<kind>/`, partitioned by business date. Datasets of static reference data
(world-economy, news-and-markets) are built from those packs' samples, the same on every day. The real output, the options
(`--lake`, `--only`, `--with-real`, `--server`, `--no-signal`) and what a running server is told are in
[OPERATIONS_GUIDE.md, Step 5](OPERATIONS_GUIDE.md#24-build-the-demo-lakes); the datasets, one row each (key, connector,
table, partitions, documents on day 1, changes a day, size), are in [data/testdata/README.md](../../data/testdata/README.md). One record of
every data type, every field explained for the query that must produce it, is in the **[data samples](../contracts/samples/README.md)**.

| What | Value |
|---|---|
| Business days | 60 (USNY calendar; the test data's 2026-07-08 to 2026-09-30) |
| Trades | 5,000 per source system on the last day, 15,000 in all (4,407 ENDUR, 3,471 CALYPSO, 4,198 MUREX on the first); about 3–7% amended each day (`TRADE_VERSION` moves) |
| Source systems | `ENDUR` (ids `1000001` …), `CALYPSO` (`CLY-3000001` …), `MUREX` (`MX-20000001` …): ids unique across them |
| Tables | 85 (measured 2026-10-28): 3 trade tables, the test-data datasets of the banking packs (with [`var-limit`](../contracts/samples/market-risk.md#var-limit) ([contract](../contracts/packs/market-risk.md#vlim-var-limit-of-a-hierarchy-node-var-limit))), the reference tables of world-economy and news-and-markets, the public-data snapshots of us-equities, gleif and funds |
| Disk | about 3.4 GB (measured 2026-10-28, `du` of the 86 tables; the committed test data: about 181 MB, the VaR data on three business dates) |
| Counterparties | fictitious look-alikes (`CP-NORTHPLAINS`, `CP-VAULTGUARD`, …), with UEN, country of risk, legal entity, agreements and netting sets holding their trades |

Re-running `lake demo` rebuilds every table it writes (the folder is removed first) and is safe while a server runs: the server gets
the data-load signal for each table and date, or, when none is reachable, finds `data/bi-store/lake-rebuilt.json` at its next start
and clears its stored BI columns and rollups; a running server also sees each rebuilt Delta date by itself. The test data itself is
regenerated with `python3 tools/testdata/make_testdata.py` (deterministic, the same bytes on every Python version: `tools/testdata/test_testdata.py` and `test_deterministic.py` guard it).

**The test data's files are a generator's format, not feed files.** Each sixty-day dataset is one file a business date named by the
load rules (`trade_endur.2026-07-09.jsonl.gz`, [DATA_LAKE_GUIDE.md](DATA_LAKE_GUIDE.md#5-routing-business-dates-modes-repeats-and-undo) section 5.2), but only the first
day's holds the whole table (the snapshot, `kind: snapshot` in `testdata.yaml`); each later day's holds only the documents new or
changed that day. `lake demo` replays them, the snapshot with every change up to the day applied by key, and loads each day's whole
table under its dated name. Loaded on its own (`lake build --from` one of them, or a folder of them), a later day's file is only that
day's changes, not the day's table: build the demo tables with `lake demo` (`--only <folder>` for one dataset). The VaR, exposure and
public-data folders are whole tables, a date each, and load as they are.

### 11.2 Mixed Delta and Iceberg storage

(decided by the product owner on 2026-10-20) each source system's trades can be stored in **Delta Lake or Apache Iceberg**,
chosen per system when the demo lakes are built, so one installation proves that the connectors, history, search, the grouped pages and
BI answer the same whatever the storage. Every other dataset stays in Delta.

| Command (`drishti-console/.venv/bin/python tools/build_demo_lakes.py …`; Windows `drishti-console\.venv\Scripts\python.exe tools\build_demo_lakes.py …`) | Result |
|---|---|
| (no `--store`) | everything in Delta |
| `--store calypso=iceberg` | CALYPSO in Iceberg, ENDUR and MUREX in Delta (the mixed case) |
| `--store endur=delta --store calypso=iceberg --store murex=iceberg` | any mix, one `--store` per system |
| `--store all=iceberg` | every system in Iceberg (`all=` first, then each system over it: `--store all=iceberg --store endur=delta`) |
| `--store all=delta` | every system back in Delta |
| `--only trades/calypso --store calypso=iceberg --as-of <date>` | one system rebuilt and moved; the others kept as they are |
| `--source-dir <folder> --system calypso --files-only --store calypso=iceberg` | your own CALYPSO files into Iceberg |
| any of these with `--dry-run` | every step said, nothing changed |

- **Where:** an Iceberg system's table is `<data>/iceberg/trading/trade_<system>`, beside the Delta lake (`data/iceberg` for the default
  `data/delta`; `--lake /srv/data/delta/demo` gives `/srv/data/iceberg`; `DRISHTI_ICEBERG_ROOT` moves it). Its Delta table is removed: each
  system is in one store.
- **How it is written:** the system is built as Delta first (by `lake demo`, or by `lake build` of your files into a staging folder,
  `<data>/build/iceberg-stage`, removed after), so its documents and promoted columns are exactly those of a Delta build; then
  `tools/lake/stores.py` streams the rows into Drishti's own Iceberg loader (`IcebergLoader`, `engine: native`: no Hadoop, so it works on
  Windows), started from the server's exec jar (`kernel/drishti-server/target/drishti-server-*-exec.jar` or `DRISHTI_JAR`: build the server first;
  heap `DRISHTI_LOADER_HEAP`, default 2g). The loader's progress lines (`iceberg: … rows read`, `iceberg: written in … s`) and the usual
  30-second heartbeat show while it copies.
- **Connector files:** the build writes `trade-<system>.yaml` (in `config/connectors`, or `--connectors-dir`) from the trading pack's
  template with `plugin: iceberg`, `engine: native` and `table: {trade: trade_<system>}` (the Iceberg connector's `table.<kind>` setting,
  as the Delta connector's), or back with `plugin: delta` when the system returns to Delta; the text it replaces is kept in
  `<data>/connectors/history/`. One connector serves each system, never two; the trading pack's union of the three systems is unchanged.
  A running server restarts a switched connector by itself (live reload); `lake demo` leaves an Iceberg file as it is.
- **What a run keeps:** a full build without `--store` puts every system in Delta. A partial build (`--only`, `--files-only`) keeps each
  system it does not name in the store it has; `--only` with a `--store` that moves another system rebuilds that system too (it says so);
  `--files-only` cannot move a system whose trades it does not load, and says to run `--only trades/<system> --store …` first.
- **The closing lines** list every system's store and table, and what was done to each connector file:

  ```text
    Stores (each source system's trades):
      ENDUR    delta    …/data/delta/demo/trading/trade_endur
      CALYPSO  iceberg  …/data/iceberg/trading/trade_calypso
      MUREX    delta    …/data/delta/demo/trading/trade_murex
    Connector files:
      trade-endur: reads delta already (…/config/connectors/trade-endur.yaml)
      trade-calypso: now reads iceberg, was delta (…/config/connectors/trade-calypso.yaml; a running server restarts this connector by itself)
      trade-murex: reads delta already (…/config/connectors/trade-murex.yaml)
  ```

- **With a server:** `--server URL` tells it once at the end (pack trading, kind `trade`, the newest date), as for your files.
- **Proof:** `tools/lake/stores_equality.py --work <an empty folder> --port <a free port>` builds the same five synthetic business days
  (1,000 trades a system a day from the committed test data) all-Delta and mixed (ENDUR Delta, CALYPSO and MUREX Iceberg) and asks a server
  over each, one after the other: six trade pages, `TRD <id> 10` for each, two counterparty-trades pages, three searches and a BI model query
  (sum of `QUANTITY` by source system, currency and product type). Measured on 2026-10-21: **18 answers, the same** (only timings, the
  data-version stamps and the table generation in the provenance panel differ, as they name the storage).
- **Maintenance:** `lake maintain` for the Delta tables; the Iceberg tables with `IcebergLoader --keep-days` or the Iceberg maintenance of
  [ICEBERG_CONNECTOR.md, section 9](../../plugins/drishti-plugin-iceberg/README.md#retention-and-maintenance).
- **Default:** an option; the demo default is Delta.

### 11.3 The sizes

| Size | Command | What you get | When to use it |
|---|---|---|---|
| **samples** | none: the `demo` source serves them | the packs' samples (765 trades, 2,571 documents in the five banking packs), today only, ticking | a first look with no store, writing a pack |
| **the demo lakes** | `drishti.py lake demo` | 15,000 trades a day and every other dataset, sixty business days | demos, QA, the tutorials |
| **a store of your choice, small** | `tools/load-<store>.sh` | the samples as ten business days ending 2026-09-30 | trying PostgreSQL, DuckDB, … with the same data |
| **large** | `tools/load-<store>.sh … --trades 1000000 --days 3`, or `tools/samplegen/bulk_trades.py` | a million trades a day for 3 days | scale tests, performance measurements |

`--trades N` (the `load-*.sh` scripts) adds a book of N trades a day for `--days D` business days (default 3). The book's first trades
are the samples; the rest are clones of them, numbered from a higher range of each system's ids (`MX-30000000` …, `CLY-4000000` …),
keeping their template's product, portfolio, counterparty and legal entity (so every link opens) and scaling the amounts
(`NOTIONAL`, `QUANTITY`, `AMOUNT` at every level) by a factor between 0.2 and 5. Every size is reproducible: the same command writes
the same data. On a laptop keep books to about 10,000 trades; a million trades a day is for a server.

### 11.4 Delta Lake

The demo lakes above are the normal way. The older script writes the samples as ten days of history, with an optional book:

```bash
tools/load-delta.sh                              # the samples as 10 business days: data/delta/demo
tools/load-delta.sh --trades 10000               # and 10,000 trades a day for 3 days
tools/load-delta.sh /tmp/lake --trades 20000     # somewhere else
```

It generates, then loads, in separate steps ([DATA_GUIDE.md](#2-data-formats-json-lines-is-the-standard)): `make_data.py --lake --no-load` writes the
history as JSON Lines rows (`<data>/build/lake-rows/banking-history.tar` and `banking-restated.tar`), `tools/samplegen/load_rows.py`
loads them (rewriting the whole lake the packs read, in the layout each declares; the restatement as a second commit); then
`tools/samplegen/bulk_trades.py` writes the book as one JSONL file a day (`<data>/build/bulk/murex/trade_murex.<date>.jsonl.gz`, the load rules' name) and loads each
with `lake build`, replacing **one source system's** trade table (`trade_murex`, or `--system CALYPSO`) and leaving every other table as
it is (`--no-lake` stops after the JSONL). Neither step rewrites the packs' samples, the Sutra tests or the feed. The server reads the demo lake, `data/delta/demo` (or `DRISHTI_DELTA_ROOT`), and
picks a new load up within a minute; no restart is needed. The layout: [DELTA_CONNECTOR.md](../../plugins/drishti-plugin-delta/README.md).

For your own export (a large JSON array or JSON Lines of real trades), `drishti.py lake build` streams it into a Delta table with flat
memory, partitions and promoted columns ([CLI_REFERENCE.md](CLI_REFERENCE.md)).

### 11.5 PostgreSQL

```bash
docker compose -f deploy/docker/compose.data.yaml up -d postgres                                       # or your own server
tools/load-postgres.sh jdbc:postgresql://localhost:5432/drishti                                 # the samples, 10 days
tools/load-postgres.sh jdbc:postgresql://localhost:5432/drishti --trades 10000                  # and a book
SPRING_PROFILES_ACTIVE=postgres DRISHTI_PACKS=market-risk,counterparty-risk java -jar kernel/drishti-server/target/drishti-server-*-exec.jar
```

The profile is deprecated; the same connectors as files are in `config/connectors.examples/postgres/` (copy them to
`config/connectors/`; the example `trading-store.yaml` is one trade connector, see
[DATA_GUIDE.md section 9.4](#94-examples-one-per-store)). Measured in an earlier release: the samples in 2 s, a
million trades × 3 days in about 3 minutes and 11 GB. User and password come from `--user`/`--password`, else
`DRISHTI_PG_USER`/`DRISHTI_PG_PASSWORD`, else `drishti`. The samples' load recreates each domain's table; a running server sees a new
load within a minute. Layout and design: [POSTGRES_CONNECTOR.md](../../plugins/drishti-plugin-jdbc/README.md).

### 11.6 Apache Iceberg

```bash
tools/load-iceberg.sh ./data/iceberg                       # the samples
tools/load-iceberg.sh ./data/iceberg --trades 10000        # and a book, for a laptop
SPRING_PROFILES_ACTIVE=iceberg DRISHTI_PACKS=market-risk,counterparty-risk java -jar kernel/drishti-server/target/drishti-server-*-exec.jar
```

Design: [ICEBERG_CONNECTOR.md](../../plugins/drishti-plugin-iceberg/README.md). (`load-iceberg.sh` builds the plugin with Maven; on Windows use
`tools\windows\load-iceberg.ps1`, or put the demo lakes' trades in Iceberg with `build_demo_lakes.py --store`: [above](#112-mixed-delta-and-iceberg-storage).)

### 11.7 DuckDB

```bash
tools/load-duckdb.sh data/duckdb/drishti.duckdb                              # the samples
tools/load-duckdb.sh data/duckdb/drishti.duckdb --trades 10000 --days 3      # and a book, for a laptop
SPRING_PROFILES_ACTIVE=duckdb DRISHTI_PACKS=market-risk,counterparty-risk java -jar kernel/drishti-server/target/drishti-server-*-exec.jar
```

No server to run: one file holds every data domain, and the server reads it in-process. Each load writes a new file and renames it
over the old one, so it can run while the server answers; the server picks it up within 10 s. The database defaults to
`DRISHTI_DUCKDB_PATH`, else `data/duckdb/drishti.duckdb`. Design: [DUCKDB_CONNECTOR.md](../../plugins/drishti-plugin-duckdb/README.md).

### 11.8 MongoDB

```bash
docker run -d --name drishti-mongo -m 2g -p 27017:27017 mongo:7 --wiredTigerCacheSizeGB 0.5
tools/load-mongodb.sh mongodb://localhost:27017 drishti                       # the samples
tools/load-mongodb.sh mongodb://localhost:27017 drishti --trades 10000        # and a book, for a laptop
SPRING_PROFILES_ACTIVE=mongodb DRISHTI_PACKS=market-risk,counterparty-risk java -jar kernel/drishti-server/target/drishti-server-*-exec.jar
```

Design: [MONGODB_CONNECTOR.md](../../plugins/drishti-plugin-mongodb/README.md).

### 11.9 Redis

```bash
docker run -d --name drishti-redis -m 2g -p 6380:6379 redis:8 --maxmemory 1gb
tools/load-redis.sh redis://localhost:6380                       # the samples
tools/load-redis.sh redis://localhost:6380 --trades 10000        # and a book, for a laptop
DRISHTI_REDIS_URI=redis://localhost:6380 SPRING_PROFILES_ACTIVE=redis DRISHTI_PACKS=market-risk,counterparty-risk \
  java -jar kernel/drishti-server/target/drishti-server-*-exec.jar
```

(Port 6380 on the host leaves a Redis you already run on 6379 alone.) Redis holds everything in memory: about 1 GB per million trades
a day, so keep large books for a server. Each load replaces the business days it carries (`--merge` adds to them instead). Design:
[REDIS_CONNECTOR.md](../../plugins/drishti-plugin-redis/README.md).

### 11.10 Aerospike

```bash
docker compose -f deploy/docker/compose.data.yaml up -d aerospike
tools/load-aerospike.sh localhost:3000 test                                  # the samples
tools/load-aerospike.sh localhost:3000 test --trades 10000                   # and a book
SPRING_PROFILES_ACTIVE=aerospike DRISHTI_PACKS=market-risk,counterparty-risk java -jar kernel/drishti-server/target/drishti-server-*-exec.jar
```

`--ttl-days N` lets Aerospike expire each day after N days. The default namespace of the Docker image keeps data in memory and is too
small for a million-trade book; see [AEROSPIKE_CONNECTOR.md](../../plugins/drishti-plugin-aerospike/README.md#loading).

### 11.11 Apache Cassandra

Cassandra (or ScyllaDB) is yours to run; a single development node is enough for the samples. Then:

```bash
tools/load-cassandra.sh localhost:9042 datacenter1 --create --create-keyspace 1                   # the samples, a keyspace per domain
tools/load-cassandra.sh localhost:9042 datacenter1 --create --create-keyspace 1 --trades 10000   # and a book of 10,000 trades a day
cp config/connectors.examples/cassandra/*.yaml config/connectors/                                  # read the domains from Cassandra
```

Each business day is written under a new generation and switched to at the end, so a reload never shows half a day. `--ttl-days N`
expires cells after N days; `--keep-days N` drops the older days. See [CASSANDRA_CONNECTOR.md](../../plugins/drishti-plugin-cassandra/README.md#loading).

### 11.12 JSON-lines files (no store at all)

```bash
tools/load-files.sh                              # the samples: data/files
tools/load-files.sh --trades 10000               # and 10,000 trades a day for 3 days
SPRING_PROFILES_ACTIVE=files DRISHTI_PACKS=market-risk,counterparty-risk java -jar kernel/drishti-server/target/drishti-server-*-exec.jar
```

One file per kind per business day, `data/files/<domain>/<date>/<kind>.jsonl`: see [FILE_CONNECTOR.md](../../docs/connectors/types/file/README.md).

### 11.13 Your own JSONL

`drishti.py data ingest` (the module `tools/ingest_jsonl.py`) loads any JSON Lines (plain documents or loader envelopes; a folder or
single files) into a Delta lake or a files store, using the layout the connectors read. A pack made by `drishti.py pack make` (or
`tools/packgen/pack_from_jsonl.py`) carries the id and date fields it needs. For a large single export use `drishti.py lake build`
instead (streamed, flat memory).

```bash
# Delta lake (default): <root>/<domain>/<kind>/business_date=...; serve with DRISHTI_DELTA_ROOT
drishti-console/.venv/bin/python tools/drishti.py data ingest --from data/trade.jsonl --pack config/packs/my-bank --lake /tmp/lake
DRISHTI_PACKS=my-bank DRISHTI_DELTA_ROOT=/tmp/lake java -jar kernel/drishti-server/target/drishti-server-*-exec.jar
# JSON-lines files (stdlib + PyYAML only): <root>/<domain>/<date>/<kind>.jsonl; serve with DRISHTI_FILES_ROOT
drishti-console/.venv/bin/python tools/drishti.py data ingest --from your-folder/ --domain my-bank --key trade=TRADE_ID \
    --store files --root /tmp/files --dry-run
```

`--mode overwrite-dates` (default) replaces only the business dates present, so a re-run is idempotent; `append`; `replace`. Without
`--pack`, give `--domain` and `--key` per kind. Each file goes under the date in its name (`trade.2026-11-03.jsonl`), or `--as-of`, else
the business today ([DATA_LAKE_GUIDE.md](DATA_LAKE_GUIDE.md#5-routing-business-dates-modes-repeats-and-undo)); `--date FIELD` only names a field whose differing rows are
counted. See
[Generating a pack from JSON Lines](BUILD_GUIDE.md#10-a-pack-from-json-lines-pack-new) and
[CLI_REFERENCE.md](CLI_REFERENCE.md).

### 11.14 Your bank's names

The demo bank is *Drishti Bank*, and its legal entities are *Drishti Bank plc* (`LE-LDN`), *Drishti Bank Securities Inc.* (`LE-NY`),
*Drishti Bank Europe AG* (`LE-FRA`) and *Drishti Securities Japan K.K.* (`LE-TKY`), with branch forms such as *Drishti Bank plc,
Singapore Branch*. To demonstrate with other names, edit `config/demo/names.yaml`:

```yaml
bank: Example Bank                       # the bank as a whole (the top of the risk hierarchy)
short-name: EXAMPLE
legal-entities:
  LE-NY: Example Securities Inc.
  LE-LDN: Example Bank plc
  LE-FRA: Example Bank Europe AG
  LE-TKY: Example Securities Japan K.K.
# branches:                              # optional: a branch's own full name
#   "Drishti Bank plc, Singapore Branch": Example Bank plc, Singapore Branch
```

The defaults are the names above, so the file changes nothing until you edit it. The names are applied **as the demo lakes are
built**: `drishti.py lake demo` and `tools/build_demo_lakes.py` (both take `--names FILE`, default `config/demo/names.yaml`; with
`--dry-run` they print the mapping and build nothing) rename, in every kind as it is loaded, each value that **is** an old name, and
each value that **starts with** an old name followed by a comma (a branch keeps its suffix). The committed test data
(`data/testdata`) is never rewritten. **LEIs and BICs stay as they are**: they are identifiers. In the committed data the names are in:

| Where | Field | Names |
|---|---|---|
| `core/legal-entity` (test data, lake `reference/legal-entity`) | `NAME` | the four legal entities |
| `market-risk/pnl-history` (test data, lake `risk/pnl-history`) | `NAME` | the bank, at the top of both trees' histories |
| `config/packs/core/core/samples` | `legal-entity.jsonl` `NAME`, `catalog.jsonl` subtitles | the four legal entities |
| the retired example packs (`examples/packs/climate-risk`, `liquidity-risk`, `operational-risk`; `finance`) | `legalEntityName`, `legalEntity.name` | the legal entities |

So after an edit rebuild those two datasets (`--only core/legal-entity,market-risk/pnl-history`, or a full `lake demo`); the VaR trees
show each legal entity by its `legal-entity` record's `NAME` ([contract](../contracts/packs/core.md#le-bank-legal-entity-legal-entity), [example](../contracts/samples/core.md#legal-entity)), so they follow at once. The
packs' samples, served without a lake, follow the file when their generators are run again: `python3 tools/packgen/banking/make_data.py`
(the core samples; the catalogue's subtitles too) and an example pack's `make.py`. The VaR tree's top node is named by a server setting
of its own, the VaR root name (listed in [CONFIGURATION.md](../admin/CONFIGURATION.md) under the VaR
engine): the VaR tree's top name is set there.

### 11.15 The samples

The packs' samples in `config/packs/<domain>/<pack>/samples/` are what the `demo` source serves live: one JSON Lines file per kind
(`<kind>.jsonl`, one document a line, split into `<kind>.part-001.jsonl` … only above about 50 MB) and a `catalog.jsonl` listing every
sample's kind, id, title and subtitle (the type-ahead's text). The five banking packs' samples are written by

```bash
drishti-console/.venv/bin/python tools/packgen/banking/make_data.py          # add --check to verify instead of write
```

which prints (real output on a fresh clone):

```text
wrote 2571 documents into 5 packs' samples; 271 trading test files
feeds: …/data/feeds/fixing.jsonl
```

(world-economy and news-and-markets have their own generators under `tools/packgen/`.) The plain run also writes the sample feed file
`data/feeds/fixing.jsonl` (one fixing series a line, `FIX SOFR-HISTORY`; `lake demo` writes it too). Every `load-*.sh` script runs
`make_data.py --jsonl` or `--lake`, which write rows only: they never rewrite the samples, the Sutra tests or an existing feed. The samples per pack:

| Pack | Samples | Kinds | Live | On disk |
|---|---|---|---|---|
| `core` | 129 | 10 | 30 (books) | 247 KB |
| `market-data` | 220 | 20 | 119 (curves, FX, equities, indices, commodities, bonds, credit curves) | 409 KB |
| `trading` | 765 | 1 | 0 (trades do not tick; their valuations do) | 8.0 MB |
| `counterparty-risk` | 534 | 10 | 66 (netting sets) | 643 KB |
| `market-risk` | 923 | 6 | 765 (one trade valuation per trade) | 736 KB |
| `world-economy` | 9,582 | 13 | 0 | 19.0 MB |
| `news-and-markets` | 35 | 2 | 0 | 16 KB |

#### Live walks by kind

A live sample ticks while someone watches it (the `demo` source, [DEMO_CONNECTOR.md](../../docs/connectors/types/demo/README.md#coherent-walks-a-group-that-moves-together)).
Its `_meta.walk` (the `_meta` block keeps its lower-case keys; the paths inside the walk are the document's UPPER_SNAKE names) moves a headline **and everything that must agree with it**, so a table, a chart and a headline never
disagree. The specs are built by `tools/packgen/common/walks.py` from the finished sample; `DemoPackWalksTest` ticks every sample of
every kind below 40 times and checks the identities in the second column. The kinds of the seven packs that walk:

| Kind (pack) | Moves together, and the identity that holds after any tick | Stays still, and why |
|---|---|---|
| [`ir-curve`](../contracts/samples/market-data.md#ir-curve) ([contract](../contracts/packs/market-data.md#crv-interest-rate-curve-ir-curve)) (market-data) | one shift for the curve (a small tilt and per-tenor blur): every `ZERO_RATE` (%), every `QUOTE`, every `FORWARDS[].RATE` (%); each `DF = exp(-ZERO_RATE/100 × tenor)`; `TEN_Y` is the 10Y zero rate; `SLOPE_2S_10S` is 10Y less 2Y in bp | `MATURITY`, instruments, conventions |
| [`fx-spot`](../contracts/samples/market-data.md#fx-spot) ([contract](../contracts/packs/market-data.md#fx-fx-spot-rate-fx-spot)) | `MID` with `BID` and `ASK` by the same amount (the spread is constant); `CHANGE_1D = MID / previous close - 1`; today's `HISTORY` point is `MID` | the earlier history |
| `equity` | `PRICE`; `MARKET_CAP`; `CHANGE_1D`; today's history point and today's bar (`OHLC`: close is the price, high and low stretch, volume builds) | the bars before today, identifiers, beta |
| [`equity-index`](../contracts/samples/market-data.md#equity-index) ([contract](../contracts/packs/market-data.md#eqx-equity-index-equity-index)) | `LEVEL`; `CHANGE_1D`; today's history point | constituents and weights (a rebalance, not a tick) |
| `commodity` | `FRONT`; today's bar (close, high, low, volume) | contract specification |
| [`commodity-curve`](../contracts/samples/market-data.md#commodity-curve) ([contract](../contracts/packs/market-data.md#cmdc-commodity-forward-curve-commodity-curve)) | `FRONT` with every month (parallel, a little blur); `FRONT` is the first month; `ROLL`; `SHAPE` flips between backwardation and contango when the first two months swap | open interest (end of day) |
| [`credit-curve`](../contracts/samples/market-data.md#credit-curve) ([contract](../contracts/packs/market-data.md#cds-credit-curve-credit-curve)) | `SPREAD_5Y` with every tenor's spread; `HAZARD = SPREAD / 1e4 / (1 - recovery)`; `SURVIVAL = exp(-HAZARD × tenor)`; `PD_1Y = 1 - SURVIVAL(1Y)` | recovery, clause (terms) |
| `bond` | `PRICE`; `YIELD` from the price and modified duration; `Z_SPREAD` moves against the price; today's history point | the coupon schedule, the terms |
| [`trade-valuation`](../contracts/samples/market-risk.md#trade-valuation) ([contract](../contracts/packs/market-risk.md#tval-trade-valuation-trade-valuation)) (market-risk) | `MTM`; `PNL_1D = MTM - opening MTM` | the trade's terms (in the `trade` kind, which does not tick) |
| [`netting-set`](../contracts/samples/counterparty-risk.md#netting-set) ([contract](../contracts/packs/counterparty-risk.md#nset-netting-set-netting-set)) (counterparty-risk) | each listed trade's `MTM` walks; each asset class's total (`BY_ASSET`) is its trades (plus the unlisted ones, fixed) and `NET_MTM` is the sum; `UTILISATION` moves with it (exposure itself, EE and PFE, is not stored on the netting set: the exposure engine computes it) | collateral (moves on margin calls), the agreement |
| `book` (core) | each of the ten largest trades' `MTM` (`TOP_TRADES`) walks; `MTM` is those plus the rest (fixed) | trade count and DV01 (end of day) |

The retired example packs in `examples/packs/` keep their own walks (key risk indicators, card and deposit accounts, LCR, intraday
liquidity, containers, vessels) in their older camelCase shape. Samples that are not `live` keep the same walk, so switching one to live
needs no other change. To see it, open `/v/ir-curve/CRV-USD-BASIS` with the demo source on (`DRISHTI_DEMO_ENABLED=true`): the 10Y cell,
the other zero rates, the discount factors, the forwards and the curve in the chart all change at each tick.

### 11.16 JSON lines: one format for every loader

Every loader except Delta Lake's reads the same JSON lines, one per entity per business day. A real line written by
`make_data.py --jsonl` (the document shortened):

```json
{"domain": "trading", "kind": "trade", "id": "MX-20000001", "date": "2026-09-17",
 "doc": "{\"SOURCE_SYSTEM\": \"MUREX\", \"TRADE_ID\": \"MX-20000001\", \"TRADE_STATUS\": \"AMENDED\", …}",
 "columns": {"TRADE_ID": "MX-20000001", …}}
```

`columns` carries the fields the pack promotes (the trading pack's `layout.trade.columns`), taken from the same document. The trade's own fields are its [contract](../contracts/packs/trading.md#trd-trade-trade) and a whole record, every field explained, is its [example](../contracts/samples/trades.md#murex). Two
generators write it, by the load rules ([DATA_LAKE_GUIDE.md](DATA_LAKE_GUIDE.md#5-routing-business-dates-modes-repeats-and-undo)): rows of several kinds or days go
in a tar of `<kind>/<YYYY-MM-DD>/rows.jsonl` folders, each row in its own date's folder, and the loaders file each row under its
folder's date (never the row's `date`, which stays in the row as data):

```bash
drishti-console/.venv/bin/python tools/packgen/banking/make_data.py --jsonl data/build/loader-rows/banking.tar   # the samples, 10 days
drishti-console/.venv/bin/python tools/samplegen/bulk_trades.py --trades 10000 --days 3 --jsonl data/build/loader-rows/bulk-trades.tar
```

(`make_data.py --jsonl` took 9 s and wrote 25,430 rows over the ten days 2026-09-17 to 2026-09-30, 7,650 of them trades: 450 folders,
45 kinds a day.) A plain file, or standard input (`-`), is one kind on one date: `--as-of`, else its `.ready` file's, else its
name's (`trade.2026-09-30.jsonl`), else the business today; `bulk_trades.py --jsonl -` writes one only with `--days 1`. So any store
a new connector adds can be loaded with the same demo data.

### 11.17 The scripts

| Script | What it does |
|---|---|
| `tools/drishti.py lake demo [--lake DIR] [--as-of today\|data\|DATE] [--only …] [--with-real FILE] [--server URL] [--no-signal]` | the demo lakes from `data/testdata` (`tools/lake/demo.py`) |
| `tools/drishti.py lake build --from FILE --pack DIR --kind K …` | one Delta table from a large JSON export, streamed (`tools/lake/build.py`) |
| `tools/testdata/make_testdata.py [--check]` | writes `data/testdata` |
| `tools/load-files.sh [root] [--trades N] [--days D]` | `make_data.py --jsonl` (a tar), then `JsonlLoader` into `<root>/<domain>/<date>/<kind>.jsonl` |
| `tools/load-delta.sh [root] [--trades N] [--days D]` | `make_data.py --lake --no-load` (JSONL rows), `samplegen/load_rows.py` (into Delta), then `bulk_trades.py` (JSONL, then `lake build`) |
| `tools/load-postgres.sh [jdbc-url] [--trades N] [--days D] [--keep-months N] [--user U] [--password P] [--writers N]` | `make_data.py --jsonl` (a tar), then `PostgresLoader` (samples with `--recreate`), then the bulk book's tar |
| `tools/load-iceberg.sh [root] [--trades N] [--days D] [--keep-days N] [--catalog rest --uri U --warehouse W]` | `make_data.py --jsonl` and the bulk book (two tars), then `IcebergLoader` on their folder in one load (each day sorted by id) |
| `tools/load-duckdb.sh [database] [--trades N] [--days D] [--keep-days N] [--memory-limit M] [--parsers N] [--threads N]` | `make_data.py --jsonl` (a tar), then `DuckDbLoader` (samples with `--recreate`), then the bulk book's tar; each load builds `<database>.loading` and renames it over the database |
| `tools/load-mongodb.sh [uri] [db] [--trades N] [--days D] [--keep-days N \| --ttl-days N] [--doc-format string\|bson]` | `make_data.py --jsonl` (a tar), then `MongoLoader`, then the bulk book's tar |
| `tools/load-redis.sh [uri] [--trades N] [--days D] [--ttl-days N] [--merge] [--publish] [--cluster]` | `make_data.py --jsonl` (a tar), then `RedisLoader`, then the bulk book's tar |
| `tools/load-aerospike.sh [hosts] [namespace] [--trades N] [--days D] [--ttl-days N]` | `make_data.py --jsonl` (a tar), then `AerospikeLoader`, then the bulk book's tar |
| `tools/load-cassandra.sh [contact-points] [datacenter] [--create] [--create-keyspace RF] [--trades N] [--days D] [--ttl-days N] [--keep-days N]` | `make_data.py --jsonl` and the bulk book (two tars), then `CassandraLoader` on their folder in one load |
| `tools/packgen/banking/make_data.py` | the samples, `--lake` (JSONL rows, then loaded; `--no-load`), `--jsonl FILE.tar` (loader rows), `--check` |
| `tools/samplegen/load_rows.py --rows F --restated F --root R` | loads dated loader rows (JSONL) into the lake |
| `tools/samplegen/bulk_trades.py` | a large trade book for one source system: JSONL a day, then `lake build` (`--no-lake`), or loader rows (`--jsonl FILE.tar`, a tar of `trade/<YYYY-MM-DD>/` folders) |

`lake demo` and `lake build` need only the console's Python (`drishti-console/.venv`, with PyYAML, deltalake and pyarrow). The
`load-*.sh` scripts need `uv` (Python), Java 21 or newer (`JAVA_HOME`; the scripts default to Java 21), and Maven's offline cache for the
Java loaders (run `./mvnw install` once).
