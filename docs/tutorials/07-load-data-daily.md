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
# Tutorial 7 · Load data daily

In production your own job loads the store every day, and its last step tells Drishti: *this pack's data for this business date has landed*. Drishti then refreshes what it caches, checks that the data
can be read, looks at the alerts that follow loads and tells the right people. If the job never calls, Drishti notices that too. In this tutorial you play the job: set an expectation, announce
loads from the command line and the API, watch a failure and a late day, and make an alert that fires when a day's trades are not there.

## Time to complete

About 30 minutes.

## Before you start

- [Tutorial 1](01-first-15-minutes.md) done, with the token for the command line. The token must have the scope `loads:write` (tutorial 1 ticked it). A real nightly job should use its own token with that one scope and nothing else.
- Signed in as the development administrator for the pages. The pack here is `trading` (it comes with `market-risk`) and its kind is `trade`.
- Drishti never copies or transforms your data: it reads the store your job loaded (see [DATA_LOADS.md](../guides/DATA_LOADS.md) for the whole story).

## What you will learn

- the **data-load signal** (`ready` and `failed`), through the command line and through the REST API;
- why announcing the same batch twice is harmless;
- an **expectation** (`trade by 18:00 New York time`) and its states: on time, late, landed late, missing, failed;
- where late data shows: Data loads, Admin → Health, and a notice to the people the pack names;
- a **measure alert** with the condition *no data by a time*, triggered after a load.

## Step 1 · Say when you expect data

Open **Admin**, then **Data loads**, then the pack `trading`.

![The Data loads page of the trading pack: no expectations, no loads yet](img/t07/01-loads-empty.jpg)

You should see two tables: **Expectations** (*Nothing is expected yet*) and **History** (*No loads recorded yet*), with the command your ETL would use. Press `e` or **Expectations settings**.

![Expectations settings: who is told, the smoke test, and trade due by 18:00 America/New_York on the USNY calendar](img/t07/02-expectations.jpg)

You should see a dialog: **Tell these roles** (`admin`), users, an email box, a **smoke test** count (after each ready load Drishti opens that many sample entities to prove they render), and a table *Expected each business day*. **Add a kind**: `trade`, due by
`18:00`, zone `America/New_York`, calendar `USNY` (a day that is not a business day of that calendar expects nothing). **Save**. The setting is an override file beside the load history: the pack's own files are not touched.

## Step 2 · Announce a load from the command line

Your job's last step is one command:

```text
$ python3 tools/drishti.py data landed --pack trading --kind trade --date 2026-10-06 --rows 750 --rejected 0 --batch eod-20261006 --source nightly-trade-etl
trade for 2026-10-06 loaded: 750 rows, 0 rejected; 0 alerts fired
  load mv4mja3q-4  status ready  verified verified  attempt 1
  record  ok      recorded
  refresh ok      caches dropped: file, finance-lake, trading-store, demo
  verify  ok      750 trade entities on 2026-10-06
  alerts  ok      0 alerts fired
  smoke   ok      opened 2 of 2 sample entities
  notices ok      1 person told
```

You should see a summary line and six steps. `record` stored the announcement; `refresh` dropped the caches (so screens read the new data); `verify` opened the kind for that date and counted `750 trade entities`;
`alerts` evaluated the alerts that follow loads; `smoke` opened sample entities; `notices` told one person. The exit is 0 unless a `ready` load cannot be verified, so a scheduler sees failures.

Announce the very same batch again:

```text
$ python3 tools/drishti.py data landed --pack trading --kind trade --date 2026-10-06 --rows 750 --rejected 0 --batch eod-20261006 --source nightly-trade-etl
trade for 2026-10-06 loaded: 750 rows, 0 rejected; 0 alerts fired
  load mv4mja3q-4  status ready  verified verified  attempt 1  (already announced: nothing ran again)
  record  ok      recorded
  refresh ok      caches dropped: file, finance-lake, trading-store, demo
  verify  ok      750 trade entities on 2026-10-06
  alerts  ok      0 alerts fired
  smoke   ok      opened 2 of 2 sample entities
  notices ok      1 person told
```

You should see `(already announced: nothing ran again)` on the load line. A load is keyed by pack, kind, business date **and batch id**: the same key with the same facts is a duplicate and does nothing, so a job may
be retried safely. (The step lines below repeat the first record.) A different batch id for the same date is a reload, and runs the steps again.

## Step 3 · A failed batch

If the copy breaks, say so:

```text
$ python3 tools/drishti.py data landed --pack trading --kind trade --date 2026-10-05 --status failed --note copy stopped: disk full --batch eod-20261005
trade load for 2026-10-05 FAILED: copy stopped: disk full
  load mv4mjawl-5  status failed  verified skipped  attempt 1
  record  ok      recorded
  late    skipped a failed load does not clear a late-data flag
  notices ok      1 person told
```

You should see `FAILED`, the note, and only `record` and `notices`: Drishti tells people and does nothing else, and `late` says that a failed load does not clear a flag already raised. A later `ready` for the same kind and
date replaces a `failed`.

## Step 4 · The same through the API

The command is a wrapper for one REST call, which is what Airflow, cron or any scheduler would make:

```text
$ curl -s -X POST $DRISHTI_SERVER/api/v1/packs/trading/loads -H "Authorization: Bearer $DRISHTI_TOKEN" -H 'Content-Type: application/json' -d '{...}' | python3 -m json.tool
{
    "id": "mv4mjbfk-6",
    "pack": "trading",
    "kind": "trade",
    "businessDate": "2026-10-02",
    "status": "ready",
    "rows": 748,
    "rejected": 1,
    "source": "nightly-trade-etl",
    "batchId": "eod-20261002",
    "receivedAt": "2026-10-12T02:19:40.064309406Z",
    "receivedBy": "drishti-dev-admin",
    "attempt": 1,
    "reload": false,
    "verified": "verified",
    "entities": 750,
    "alerts": 0,
    "notices": 1,
    "steps": [
        {
            "name": "record",
            "status": "ok",
            "detail": "recorded",
            "ms": 0
        },
        {
            "name": "refresh",
            "status": "ok",
            "detail": "caches dropped: file, finance-lake, trading-store, demo",
            "ms": 10
        },
        {
            "name": "verify",
            "status": "ok",
            "detail": "750 trade entities on 2026-10-02",
            "ms": 8
        },
        {
            "name": "alerts",
            "status": "ok",
            "detail": "0 alerts fired",
            "ms": 0
        },
        {
            "name": "smoke",
...
```

You should see the same steps as JSON: `receivedBy` is the token's owner, `verified: verified`, `entities`, `alerts` fired and `notices` sent, and the list of `steps`. The status is `201` for a new load and `200` for a duplicate.
[DATA_LOADS.md](../guides/DATA_LOADS.md) has recipes for curl, Python, Airflow, a shell script after a copy, and DishtaYantra.

## Step 5 · Read the history and what is due

```text
$ python3 tools/drishti.py data loads --pack trading --limit 6 --expectations
received             kind              date        status        rows    rej  verified  alerts told  batch / source
2026-10-12 02:19:39  trade             2026-10-05  failed                     skipped        0    1  eod-20261005
2026-10-12 02:19:38  trade             2026-10-06  ready          750      0  verified       0    1  eod-20261006 nightly-trade-etl
2026-10-12 02:19:30  trade             2026-10-08  ready          750      2  verified       0    1  eod-20261008-2 nightly-trade-etl
2026-10-12 02:19:30  trade             2026-10-08  failed                     skipped        0    1  eod-20261008 nightly-trade-etl
2026-10-12 02:19:30  trade             2026-10-07  ready          750      0  verified       0    1  eod-20261007 nightly-trade-etl
expectations:
  2026-10-09  trade             by 18:00 America/New_York    missing
  2026-10-08  trade             by 18:00 America/New_York    landed-late
  2026-10-07  trade             by 18:00 America/New_York    landed-late
```

You should see the history first, newest first: status, rows, rejected, whether it verified, alerts fired, people told, batch and source. Under `expectations:` is each business day of the lookback window and its state. Here the
date 2026-10-09 is `missing` (nothing ever landed `ready`) and 2026-10-08 and 2026-10-07 are `landed-late` (they landed after their 18:00 deadline, because this tutorial announced them days later).

The same on the page, with each load's steps on a click:

![The history of loads with each step open: recorded, refreshed, verified, alerts, smoke, notices](img/t07/03-history.jpg)

You should see a row for each announcement, a *ready* or *failed* badge, a *verified* badge, and the load's steps. A pop-up in the corner is the notice the pack sent: *load-late trade for 2026-10-09 is MISSING: expected
by 18:00 America/New_York*.

![The expectations table: 2026-10-09 missing, 2026-10-08 and 2026-10-07 landed-late](img/t07/04-expectation-states.jpg)

The states are: `pending` (today, deadline not yet passed), `on-time`, `landed-late`, `late` (today, the deadline passed, nothing ready), `missing` (an earlier day, nothing ever ready) and `failed` (the newest
announcement for the date says so). `late` and `missing` are flagged **once**: one notice to the people the pack names, and a line on Admin → Health that makes the overall status `DEGRADED`. A restart does not tell again; a `ready` load clears the flag.

![Admin → Health: data late, with the pack, kind, date and deadline](img/t07/05-health-late.jpg)

You should see the late data listed on Health, linked to the pack's Data loads page.

## Step 6 · An alert for a missing day

An expectation tells the people the pack names. An **alert** can tell *you*, and carry a number. Open **Views**, then **Reports**, then **My alerts** (on the Reports page), then **New alert on a number**.

![The alert form: a measure of a dataset, no data by 18:30, after a data load of kind trade, by inbox](img/t07/06-alert-form.jpg)

Fill in: name `trades-loaded`, title `Trades have not been loaded`, severity `warn`; **Watch** *a measure of a dataset*, dataset `file:dataset:trades-basic`, measure `Trades`; **Tell me when** *has no data by a time*, `18:30`;
**Look** *after a data load*, only loads of kind `trade`; **Tell me by** *my inbox and the bell*. You should see the form as in the picture. Press **Check now**.

![Check now: what the alert reads, without saving anything](img/t07/07-alert-checked.jpg)

You should see an answer in words that says what the alert read: here `Nothing to read yet: the latest data is of 2026-09-30, the business date is 2026-10-09`, because the sample dataset has no row for the current business date, which is
exactly what *no data* means. Check now saves nothing and fires nothing. Press **Save alert**.

![The alerts page: trades-loaded, a dataset measure, no data by 18:30, after-load, armed](img/t07/08-alert-saved.jpg)

You should see `Alert saved: trades-loaded` and the alert in **Alerts on numbers**: *no data by 18:30*, *after-load*, state `armed`. On a business day, from 18:30 on, if the dataset shows no data for the day, it fires into your inbox; and
because a *no data* alert is looked at after **every** load, announcing a `ready` load clears it once the data is there. The number is read as you, with your row rules and masks.

## Next steps

- Wire your own job: [DATA_LOADS.md](../guides/DATA_LOADS.md) section 11 has copy-ready recipes; make a token with only `loads:write` for it ([Permissions](../guides/DATA_LOADS.md#10-permissions)).
- [DISTRIBUTION.md](../guides/DISTRIBUTION.md) section 4 covers alerts on numbers in full: thresholds, changes, hysteresis, cooldown, delivery.
- [Tutorial 8 · Share and collaborate](08-share-and-collaborate.md).

## If something goes wrong

| You see | Meaning |
|---|---|
| `403 ... lacks the scope loads:write` | the token was made without that scope; make another (tutorial 1, Step 9) |
| `verified: failed` and exit 1 | the load was announced `ready` but Drishti cannot read the kind for that date: the data is not where the connector looks |
| an expectation `missing` for a day you did load | the load was announced for another kind, pack or business date: compare with `data loads` |
| nothing is expected on a weekend | the calendar has no business day there, by design |
