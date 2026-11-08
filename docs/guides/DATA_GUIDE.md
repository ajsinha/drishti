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
script), **a pack from a schema** (section 5), and taking data out, **Export data** (section 6). **Lakes and loading** (the lake file,
the data inbox, the `.ready` file, `lake build`, the parallel loader, business dates, modes, undo, sizing, and reading a Databricks or
Iceberg lakehouse) have their own guide: [DATA_LAKE_GUIDE.md](DATA_LAKE_GUIDE.md); sections 2.7, 2.8, 3 and 7 below point there.

Connecting a store is [CONNECTORS.md](../connectors/CONNECTORS.md) and [CONNECTOR_FILES.md](../connectors/CONNECTOR_FILES.md); the
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

## 1. Telling Drishti new data has landed

Drishti reads data **where it lives**. It does not ingest, transform or schedule anything: loading the lake, the files or the database
is the job of your own ETL (or of a pipeline engine such as DishtaYantra). What only Drishti can do is **act on the data once it is
there**. One small signal, "this batch has landed", makes Drishti refresh what it caches, check the data is really readable, evaluate the
alert rules on it, and tell the right people. Expectations then flag a batch that has *not* landed in time.

Related: [API_REFERENCE.md](API_REFERENCE.md) (every endpoint, the error codes), [CLI_GUIDE.md](CLI_GUIDE.md) (the `drishti.py` program),
[CONFIGURATION.md](../admin/CONFIGURATION.md) (every setting), [BUILD_GUIDE.md](BUILD_GUIDE.md#31-the-packs-that-ship-turning-them-on-who-sees-which) (what a pack is),
[OPERATIONALISING.md](OPERATIONALISING.md) (shipping packs and data to servers).

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
[OPERATIONALISING.md](OPERATIONALISING.md) and [DEMO_DATA.md](../connectors/DEMO_DATA.md)). In production your ETL loads the store and, as its
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
They come from the pack's own `loads:` section and the administrator's override file. (Where the pack reads its data is separate: it is set in connector files, `config/connectors/<name>.yaml`, see [CONNECTOR_FILES.md](../connectors/CONNECTOR_FILES.md).)

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

The walk-through, with real output and pictures, is [tutorial 7 · Load data daily](../tutorials/07-load-data-daily.md): say when you expect data (step 1), announce a load from the command line (step 2), a failed
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
| 422 | `DRS-5012` | the pack has no such kind | `{"type":"about:blank","title":"load kind unknown","status":422,"detail":"DRS-5012 pack 'market-risk' has no kind 'nope'; its kinds are [stress-scenario, stress-result, frtb-sensitivity, pnl-explain, trade-valuation, position, portfolio, pnl-vector, scenario-set, risk-hierarchy]","instance":"/api/v1/packs/market-risk/loads","code":"DRS-5012"}` |

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

`tools/drishti.py` (see [CLI_GUIDE.md](CLI_GUIDE.md) for installing it and the connection options `--server`, `--token-file`, `--user`,
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

The same token announces `stress-scenario` and `stress-result` and nothing else: a call for another kind is `403`, for a pack it cannot reach `404 DRS-5011`,
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
JSON Lines ([CLI_GUIDE.md](CLI_GUIDE.md#convert-csv-a-json-array-or-a-folder-of-json-files-into-json-lines)):

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
[BUILD_GUIDE.md](BUILD_GUIDE.md#8-a-pack-from-a-json-schema-build--new-pack).

## 6. Export data: download a dataset

Taking data **out** of Drishti, a dataset and a day at a time: `EXPORT <GO>` (Views → Export data, `/data`; `DATA` works too), the API under `/api/v1/data` and
`drishti.py data get`. It is download only: rows are never shown in a grid. The screen itself, panel by panel, is in
[USER_GUIDE.md (Export data)](USER_GUIDE.md#export-data).

### 1. What a dataset is

A dataset is a **stored kind** of a loaded pack that a connector serves (`trade-valuation`, `trade`, `netting-set`); computed kinds (VaR, EE
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
  command is on (Admin → Commands); an edition or lens that hides the kind's command hides the dataset. The same rules as every other screen.
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

`drishti.py data get` does the same from a terminal ([CLI_GUIDE.md](CLI_GUIDE.md#data-get-a-dataset-as-jsonlgz-export-data)):

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

Drop files with a `.ready` marker in a folder and the server loads them into a lake by itself: [DATA_LAKE_GUIDE.md](DATA_LAKE_GUIDE.md), [section 3.1](DATA_LAKE_GUIDE.md#31-the-data-inbox) (the folders, the cycle, the settings, the Inbox screen), section 4 (the `.ready` file) and section 5 (routing rules, dates, modes, undo); the walk-through is [tutorial 36](../tutorials/36-load-files-with-the-data-inbox.md).
