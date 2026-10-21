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
# Runs: long BI questions in the background

Release 2.6.7. The design, with every rule and the API, is [BI_RUNS.md](../architecture/BI_RUNS.md). This guide is what you see and what you do.

Every BI question (a report visual, a board tile, a model query, SQL) goes through one pipeline. The console waits about two seconds for
the answer. When it comes in time, the visual is drawn exactly as before and nothing else happens. When it takes longer, the question
becomes a **run**: it goes on in the background, the visual shows a run card, and you can keep working. The bell tells you when the run
is ready. A slicer, a cross-filter or a drill on a dashboard never becomes a run: it is answered at once, or it tells you that it needs one.

## Contents

1. [The run card](#the-run-card) · 2. [Confirming a large run](#confirming-a-large-run) · 3. [A selection that needs a run](#a-selection-that-needs-a-run) ·
4. [The Runs tab](#the-runs-tab) · 5. [One run: the drawer](#one-run-the-drawer) · 6. [Sharing a run](#sharing-a-run) ·
7. [Promote a run](#promote-a-run) · 8. [The bell and the inbox](#the-bell-and-the-inbox) · 9. [On the command line](#on-the-command-line) ·
10. [The Admin page for runs](#the-admin-page-for-runs) · 11. [The words for each state](#the-words-for-each-state)

## The run card

A visual whose question takes longer than the inline wait shows a run card instead of its chart. The rest of the page keeps working.

![A run card on a report while its run runs: the estimate, why it is large, building 12 of 41 dates at 29 %, Cancel and Open in Runs](img/bi-runs/run-card-running.png)

The card says:

* **Running: 3 to 6 min.** The estimate is always a range, with how it was made. It is never a single number that looks more precise than it is.
* **Why it is large.** For example "182 business dates of trade_endur, 41 to build first; no rollup has grain desk × month".
* **Progress**, as a bar with its percentage and what is happening ("building 12 of 41 dates"). Progress is measured, never guessed. When the
  server cannot measure it, the card says "progress is not measured for this one" and shows no bar.
* **Cancel** stops the run and frees everything it holds at once. **Open in Runs** opens the run in the [Runs tab](#the-runs-tab).

When the run finishes while the page is open, the card turns into the chart without a reload. If you have left the page, the bell counts
the run (see [the bell](#the-bell-and-the-inbox)).

## Confirming a large run

A question that would read a great deal of data (above a minute, or 500 million rows, by default) asks you first. Nothing has run yet.

```
 Confirm a background run
 About 1.84 billion rows over 182 business days (Delta statistics). Expected 3 to 6 min on this server.
 A rollup at this grain in the model makes this instant next time.
 [ Run in background ]   [ Narrow the dates ]   [ Not now ]
```

* **Run in background** starts the run, and the card shows its progress.
* **Narrow the dates** puts the focus on the report's date, so you can ask for fewer days.
* **Not now** leaves it. Nothing was read. `Esc` does the same.

When the size of a table is not known, the card says "We cannot estimate this yet: it reads tables whose size we do not know. It may take a
long time." Such a question always asks first.

## A selection that needs a run

Dashboards stay instant. A slicer, a cross-filter or a drill is answered from the model's rollups and the built layers. When a selection
would need a long scan instead, the visual says so at once, before anything is read:

```
 This selection needs a background run (3 to 6 min)
 [ Run in background ]   [ Not now ]
```

Choosing **Run in background** starts it as a visible run, with its card. The model's owner can add a rollup at that grain, and then the
same selection is instant.

## The Runs tab

Open it from the BI rail (**Runs**, with the number of your runs queued or running), from My workspace's tab strip (**Items · Runs ·
Members**), by typing `BI RUNS <GO>`, or with `g` then `r` on any BI page. The address keeps the tab: `/bi/w/me?tab=runs`.

![The Runs tab in the Terminal green theme: a running run at 29 %, expired, cancelled, failed with its reason in words, a run shared by anna, and a done run with its estimate](img/bi-runs/runs-tab-terminal.png)

Each row shows the question and where it came from, its status, the dates it is as of, the data version it read (for example
`trade_endur delta v412`), how long it took, its rows and the estimate you were shown. Hover the data version for every table and date it
read, and the estimate for "estimated 3 to 6 min, took 4 min 12 s". A failed run has a second line with its reason in words, and the code
in brackets for support. ⇄ marks a run someone shared with you.

Rows change as the runs do: there is no reload and no polling (**⟳ live**). Screen readers hear each change ("MTM by desk and month: Done ·
4 min 12 s · 4,320 rows").

Filters: **Status** (all, active, waiting for you, done, failed, cancelled, expired), **Source** (reports, boards, model queries, SQL,
statements, schedules), **Who** (mine, shared with me, or both), **Since** (1 to 90 days) and **Search** on the title. A shared workspace's
Runs tab lists the runs of that workspace's items that you may see.

Keys: the arrow keys move between the tabs; `↑` and `↓` move between the rows; `Enter` opens a run; `Esc` closes it and puts the focus
back on its row.

## One run: the drawer

![The drawer of a finished run in the light theme: Open, Re-run, Export, Promote; source, who ran it, filters, parameters, as of, data, estimate and actual, tier; kept until, shared with bob (re-masked), schedule](img/bi-runs/run-drawer-light.png)

The drawer shows what the run is: its source, who ran it and as whom it was computed, the filters and parameters, the dates, the data
versions (**all versions** lists each table), the estimate next to the actual, the tier and the engine. Then what you can do with it. The
server decides what each person may do with a run, and the drawer shows only those actions:

| Action | What it does |
|---|---|
| **Open** | A report's run opens the report with `?run=<id>`: the visual is drawn from the stored result and a line says so. Any other run opens its result. |
| **Re-run ▾ on the same data** | The same question on the same dates and data versions. When the result is still stored, it is **reused** at once. |
| **Re-run ▾ on the newest data** | The same question with relative dates (this year, latest) worked out again. |
| **Cancel** | Stops a queued or running run. Its row says *Cancelled: cancelled by you*. |
| **Export ▾** | CSV, Excel, Parquet, and PDF for a report visual, from the stored result: nothing is computed again. Masked cells stay masked. |
| **Promote ▾** | To a dataset snapshot or a report, as a draft for review (see [Promote a run](#promote-a-run)). |
| **Keep longer** | 90 days, 180 days or a year, instead of the 30 days after the last open. |
| **Delete result** | Removes the stored result now. The run stays listed, as expired. |
| **Share…** | See [Sharing a run](#sharing-a-run). |
| **Schedule…** | The same question on a schedule (`daily 07:00`, `business-days 07:30`, `monthly last 18:00 business`, `after-load TRD`), on the newest data or the same dates. Each firing is a run and a notice. |
| **Run in background / Discard** | For a run waiting for your confirmation. |

An expired run says when its result was removed, and offers both re-runs.

## Sharing a run

**Share…** takes names and roles (`bob, role:risk-team`). A share never hands over your result as such. Each recipient gets what their own
rights allow, and the drawer says which:

* **sees your result**: they have exactly your rights;
* **re-masked**: they see the same rows with their own extra masks applied (a masked column shows the mask, never a value);
* **their own run**: their rights differ, so the question runs again as them, and they are told when it is ready.

Ten people of one desk with equal rights cost one recomputation, not ten. **Remove** stops sharing with one person.

## Promote a run

**Promote ▾ → to a dataset snapshot** keeps the result as a read-only table and makes a draft dataset in your workspace (`source: {run: …}`,
[DATASETS.md](DATASETS.md)). **→ to a report** makes a draft report with the question as a table visual. Both follow the normal review
before publishing, and the reviewer sees as whom the data was computed. Promoting needs the bi-author power.

## The bell and the inbox

When a visible run finishes, fails, or is started again after a server restart, the bell counts it once (a page draw that made several runs
rings once, when the last one ends). The notice says "Run finished: MTM by desk and month, 2026-01-02 to 2026-09-30 (182 rows, 3 min 40 s)":
names and counts only, never a value. Opening it goes to `/bi/run/<id>`, which opens the report drawn from the stored result, or the run in
the Runs tab. The inbox has a **Runs** tab of its own (`/inbox?tab=run`).

## On the command line

`drishti.py bi runs` does what the Runs tab does, as the token's user (the full list is in [TOOLS.md](TOOLS.md) section 5.7 and
[BI_RUNS.md](../architecture/BI_RUNS.md) section 14.4):

```
$ python3 tools/drishti.py bi runs list --state running,done --since 7d
r-java-2-01JAC4Q8Z6T3M4R9B0X1Y2Z3W4  MTM by desk and month
    running · building 12 of 41 dates · 29 %  · as of 2026-01-02 to 2026-09-30 (182 dates)  · trade_endur delta v412  · est. 3 to 6 min
$ python3 tools/drishti.py bi runs submit --sql "SELECT desk, MEASURE('MTM') FROM trades GROUP BY desk" --model trading --wait 0 --yes
r-java-2-01JAC4Q8Z6T3M4R9B0X1Y2Z3W5  queued  (expected 3 to 6 min)
  follow it:  drishti.py bi runs wait r-java-2-01JAC4Q8Z6T3M4R9B0X1Y2Z3W5
$ python3 tools/drishti.py bi runs wait r-java-2-01JAC4Q8Z6T3M4R9B0X1Y2Z3W5 --timeout 30m      # exit 0 done, 3 failed, 4 cancelled, 5 still running
$ python3 tools/drishti.py bi runs result r-java-2-01JAC4Q8Z6T3M4R9B0X1Y2Z3W5 --format csv --out pnl.csv
$ python3 tools/drishti.py bi runs share r-java-2-01JAC4Q8Z6T3M4R9B0X1Y2Z3W5 --to bob --to role:risk-team
```

`bi runs estimate` gives the tier, the engine and the estimate and runs nothing. A large question without `--yes` is refused with its estimate
and exit 2. `drishti.py bi mq FILE --async` and `drishti.py bi sql "…" --model M --async` ask as a run: the answer when it is done within
`--wait` (2 s by default), else the run's id; `--yes` accepts a large one in advance. Without `--async` they behave as before 2.6.7.

## The Admin page for runs

`/admin/bi/runs`, for BI administrators (also linked from Admin → BI). It shows the engine's gate now (slots in use per work class: interactive,
standing and background, with the background cap), the interactive admission wait (the sign that background work is not slowing anyone),
the queue per person, what runs now (with **Cancel**: the person is told an administrator cancelled it), the result store (results and
snapshots, the biggest users), how accurate the estimates were, how many questions were answered inline, the settings file, and the heavy
questions asked often enough that a rollup would answer them at once. It shows state, sizes and nodes, never a value.
`drishti.py bi runs storage` prints the same.

## The words for each state

| State | Shown as | Meaning |
|---|---|---|
| `awaiting-confirm` | Waiting for you | it asked you to confirm; nothing has run |
| `queued` | Queued | waiting for a slot |
| `running` | Running, with progress | reading and computing |
| `storing` | Storing | writing the result |
| `done` | Done | the result is stored |
| `reused` | Reused | the same question on the same data was already answered: nothing ran |
| `failed` | Failed: *reason* (code) | ended with a problem; the reason says what to do |
| `cancelled` | Cancelled: *by whom* | by you, an administrator, a newer selection, or the page closing |
| `expired` | Expired | the result was removed by retention or the storage budget |

The codes `DRS-9080` to `DRS-9089` are listed with their fixes in [TROUBLESHOOTING.md](TROUBLESHOOTING.md).
