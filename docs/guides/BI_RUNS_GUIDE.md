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
7. [Schedules](#schedules) · 8. [Export](#export) · 9. [Promote a run](#promote-a-run) · 10. [The bell and the inbox](#the-bell-and-the-inbox) ·
11. [On the command line](#on-the-command-line) · 12. [The Admin page for runs](#the-admin-page-for-runs) ·
13. [Rollup suggestions](#rollup-suggestions) · 14. [The words for each state](#the-words-for-each-state)

## The run card

A visual whose question takes longer than the inline wait shows a run card instead of its chart. The rest of the page keeps working.

![A run card on a report while its run runs: the estimate, why it is large, building 12 of 41 dates at 29 %, Cancel and Open in Runs](img/bi-runs/run-card-running.png)

The card says:

* **Running: 3 to 6 min.** The estimate is always a range, with how it was made. It is never a single number that looks more precise than it is.
* **Why it is large.** For example "182 business dates of trade_endur, 41 to build first; no rollup has grain desk × month".
* **Progress**, as a bar with its percentage and what is happening ("building 12 of 41 dates"). Progress is measured, never guessed. When the
  server cannot measure it, the card says "progress is not measured for this one" and shows no bar.
* **Cancel** stops the run and frees everything it holds at once. **Open in Runs** opens the run in the [Runs tab](#the-runs-tab).
* **Export CSV** and **Export Excel**, once the run is done (see [Export](#export)).

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
long time." Such a question always asks first, when you ask it yourself (a model query, SQL, the CLI). A report's visual of unknown size does
not ask: it draws as usual and becomes a run card if it takes longer than the inline wait, so a page on such a source never asks for every
visual. A report's visual whose size is known asks as any question does.

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
| **Run again** (choose how in **Run again as**, then press the button) | *Same as before (as of D)*: the same question on the same dates and data versions; when the result is still stored it is **reused** at once and the drawer says *Done at once: reused the answer from <time>* with **Open result**. *With the latest data*: relative dates (this year, latest) worked out again. Changing the choice does nothing on its own. While the new run goes the button says *Running…* with a spinner (a still dot with reduced motion); the new run is highlighted at the top of the list, the drawer switches to it and follows it to Done or Failed, and the old run's row says *superseded by* the new run, with a link. |
| **Cancel** | Stops a queued or running run. Its row says *Cancelled: cancelled by you*. |
| **Export CSV** · **Export Excel** | The stored result as a file, your own version of it: nothing is computed again, masked cells stay masked (see [Export](#export)). |
| **Promote…** | A model query's or SQL run's result to a dataset snapshot or a report on it, as a draft for review (see [Promote a run](#promote-a-run)). Offered only when the server says you may (`can.promote`). |
| **Keep longer** | 90 days, 180 days or a year, instead of the 30 days after the last open. |
| **Delete result** | Removes the stored result now. The run stays listed, as expired. |
| **Share…** · **Unshare** | See [Sharing a run](#sharing-a-run). |
| **Schedule…** · **Edit** · **Pause** · **Remove** | See [Schedules](#schedules). |
| **Run in background / Discard** | For a run waiting for your confirmation. |

An expired run says when its result was removed, and offers Run again.

## The same answer for the same access profile

A report visual's answer is shared between everyone with the same **access profile**: the same roles, masks and row rules on the
model it reads. When a colleague with your access profile has already drawn the visual on the same data and dates, you get their
answer at once (**Reused**), or wait for the same run if it is still going; nothing is computed twice. Someone with another mask or
row rule never gets your answer, and you never get theirs. A visual that depends on who is looking (a filter or value naming you,
such as `user.name` or `me`, a row rule by your user name, a personal model) stays yours alone.

## Sharing a run

![The drawer of a shared, scheduled run in the Terminal theme: bob re-masked, dave sees the same, carol recomputed as them with her own run; the schedule business-days 07:30 with its next firing, and the schedule form set to weekly on Monday at 08:00](img/bi-runs/run-share-schedule.png)

**Share…** opens a box with the people picker: type two letters of a name or a role and choose it (`↑` `↓`, `Enter`), and it becomes a
chip; `×` takes it off. You can also type names, comma separated (`bob, role:risk-team`). A role is expanded to its members when you share.
**Share** sends it. A share never hands over your result as such. The server decides, for each person, what their own rights allow, and
the drawer lists each recipient with that decision:

| Shown as | Meaning |
|---|---|
| **sees the same** | they have exactly your rights: they read your stored result |
| **re-masked** | the same rows with their own extra masks applied: a masked column shows the mask, never a value |
| **recomputed as them** | their rights differ, so the question runs again as them (the drawer names their run), and they are told when it is ready |

A person who may not read the model gets no share, and the drawer says so in words with the code ("Not shared with eve (DRS-9021)").
Ten people of one desk with equal rights cost one recomputation, not ten. The decision is taken again each time they read it, so a
recipient whose rights narrowed since is told to re-run it, never shown the wider answer.

**Unshare** beside a recipient stops sharing with that person. A run recomputed as them stays theirs.

**What a recipient sees.** The run is in their own Runs tab (**Who** set to *shared with me* or *both*), marked **⇄ shared by anna**. Its
drawer says "Shared with you by anna. Masks hold: you see what your own rights allow." They can open and export it (their own version);
sharing on, scheduling and deleting stay with the owner. The bell tells them when it was shared.

## Schedules

**Schedule…** in the drawer asks the same question again on a schedule, as you, at the time it fires (if you lose access, the firing fails
and you are told; it never borrows anyone's rights). Choose how often from the presets and the form writes the schedule for you, shown
under it ("Writes `weekly mon 08:00`"):

| Preset | Writes |
|---|---|
| Daily at | `daily 07:00` |
| Weekdays at | `weekdays 07:30` (Monday to Friday) |
| Business days at | `business-days 07:30` (the calendar's business days) |
| Weekly on | `weekly mon 08:00` |
| Monthly, the last day at | `monthly last 18:00` |
| Monthly, the last business day at | `monthly last 18:00 business` |
| Written out | anything in the same grammar, typed |

The grammar is the subscriptions' one ([API_GUIDE.md](API_GUIDE.md), `POST /api/v1/bi/runs/{id}/schedule`); `after-load` is not offered
for runs. **On** chooses the newest data (relative dates worked out again at each firing) or the same dates. Times are the server's zone.

Once scheduled, the drawer's **Schedule** line says what it is and when it fires next ("business-days 07:30 on the newest data · next
2026-10-22 07:30 UTC"). **Edit** opens the form on it, **Pause** stops the firings until you **Resume**, and **Remove** deletes the
schedule (the runs it made stay listed). Each firing is a visible background run, confirmed in advance, and a notice on the bell.

## Export

**Export CSV** and **Export Excel** download a finished run's result as a file. They are in the drawer, on the run card while a finished
run's result is drawn, and on the line above a report drawn from a run (`?run=<id>`, "Drawn from the run of…"). The file is made by the
server from the stored result, so nothing is computed again, and it is **your** version: a recipient of a share gets their re-masked or
recomputed answer, and masked cells stay masked (the file's note names the masked columns). A very large result is cut at the export row
limit, with a note saying so. Parquet and PDF are not offered for runs yet.

## Promote a run

**Promote…** in the drawer asks for a **Name** (the snapshot's and the dataset's, prefilled from the run's title), a **Title**, and **To**:
*a dataset snapshot* keeps the result as a read-only table and makes a draft dataset in My workspace (`source: {run: …}`,
[DATASETS.md](DATASETS.md)); *a report on it* also makes a draft report with one table of every column. The rows are kept as you see them:
a column masked for you stays masked for every reader. The drawer then says *Promoted to name@1* with links to **the draft dataset** and
**the draft report**. Both follow the normal review before publishing, and the reviewer sees as whom the data was computed.
Only the runner promotes, with the bi-author power, and only a model query or a SQL run (not a report visual): the button appears only when
the server allows it. The same name again is version 2 of the same draft. A name another person promoted is refused: *That snapshot name is
taken by another person's promotion: choose another name (DRS-9004)*. A dataset that points at a snapshot it was not promoted to reads
*This dataset may not read that run snapshot (DRS-9108)*: promote the run again to make your own. On the command line:
`drishti.py bi runs promote RUN --to report --name desk-pnl-2026 --title "Desk P&L 2026"`.

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
$ python3 tools/drishti.py bi runs share r-java-2-01JAC4Q8Z6T3M4R9B0X1Y2Z3W5 --to bob --to carol --to eve
bob: re-masked (your result with their extra masks)
carol: recomputed as them (their rights differ: a run of their own) (run r-java-2-01JAC4Q8Z6T3M4R9B0X1Y2Z3W6)
eve: not shared: eve may not read the model trading (DRS-9021)                      (on stderr)
$ python3 tools/drishti.py bi runs unshare r-java-2-01JAC4Q8Z6T3M4R9B0X1Y2Z3W5 carol
carol: no longer shared
$ python3 tools/drishti.py bi runs schedule r-java-2-01JAC4Q8Z6T3M4R9B0X1Y2Z3W5 "weekly mon 08:00" --data latest
sch-1  weekly mon 08:00  on latest data  next 2026-10-26 08:00
$ python3 tools/drishti.py bi runs schedules edit sch-1 "business-days 07:30"     # also: schedules [list] | disable | enable | delete ID
$ python3 tools/drishti.py bi runs export r-java-2-01JAC4Q8Z6T3M4R9B0X1Y2Z3W5 --format xlsx --out pnl.xlsx
$ python3 tools/drishti.py bi runs suggestions --model trading                    # bi-admin
sg-1  trading  desk x month  suggested  37 runs, about 4 min each
    asked 37 times this week, about 4 min each: make it a rollup refreshed at load
$ python3 tools/drishti.py bi runs suggestions accept sg-1                         # or: dismiss sg-1
sg-1  accepted: the rollup is in the draft of trading (bi-model-...); review it and publish it
```

`bi runs estimate` gives the tier, the engine and the estimate and runs nothing. A large question without `--yes` is refused with its estimate
and exit 2. `drishti.py bi mq FILE --async` and `drishti.py bi sql "…" --model M --async` ask as a run: the answer when it is done within
`--wait` (2 s by default), else the run's id; `--yes` accepts a large one in advance. Without `--async` they behave as before 2.6.7.

## The Admin page for runs

`/admin/bi/runs`, for BI administrators (also linked from Admin → BI). It shows the engine's gate now (slots in use per work class: interactive,
standing and background, with the background cap), the interactive admission wait (the sign that background work is not slowing anyone),
the queue per person, what runs now (with **Cancel**: the person is told an administrator cancelled it), the result store (results and
snapshots, the biggest users), how accurate the estimates were, how many questions were answered inline, the settings file, and the heavy
questions asked often enough that a rollup would answer them at once (see [Rollup suggestions](#rollup-suggestions)). It shows
state, sizes and nodes, never a value. `drishti.py bi runs storage` prints the same.

## Rollup suggestions

The same heavy question asked again and again is better answered by a rollup in the model: then it is instant for everyone. The server
counts the finished heavy model queries per model, grain and the shape of their filters (the field and the operator, never a value). When
one is asked often enough (by default 5 times in 7 days, with a median of a minute or more), it becomes a suggestion with the rollup drafted.

![Admin → BI → Runs, Repeated heavy questions in the light theme: desk × month on the trading model asked 37 times, about 4 min each, its rollup by_desk_month open, with Accept and Dismiss; counterparty asked 6 times](img/bi-runs/rollup-suggestions.png)

**Repeated heavy questions** on `/admin/bi/runs` lists each one: the model, the grain and filters, how many runs, the typical time, its
status, and **The rollup** (open it to read the YAML that would be added). For a BI administrator:

* **Accept** adds the drafted rollup to the model's **draft**, under `rollups:` (the model's comments and order are kept). Nothing is
  published: the change is reviewed and published like any other, and the message links to the model. For a model shipped in a pack the
  suggestion is accepted with the YAML for you to add by hand.
* **Dismiss** stops suggesting it.

Once the model has a rollup at that grain the status says **Built**, and those questions are answered at once instead of becoming runs.
While a model has open suggestions, its **model designer** shows a notice under the toolbar ("A heavy question on this model was asked 37
times this week: a rollup would answer it at once. Review the suggested rollups") that opens this list for that model
(`/admin/bi/runs?model=trading#suggestions`). The notice shows to BI administrators, who can accept. On the command line:
`drishti.py bi runs suggestions [--model M]`, `suggestions accept ID`, `suggestions dismiss ID`.

## The words for each state

| State | Shown as | Meaning |
|---|---|---|
| `awaiting-confirm` | Waiting for you | it asked you to confirm; nothing has run |
| `queued` | Queued | waiting for a slot |
| `running` | Running, with progress | reading and computing |
| `storing` | Storing | writing the result |
| `done` | Done | the result is stored |
| `reused` | Reused | the same question on the same data was already answered, by you or by someone with the same access profile: nothing ran |
| `failed` | Failed: *reason* (code) | ended with a problem; the reason says what to do |
| `cancelled` | Cancelled: *by whom* | by you, an administrator, a newer selection, or the page closing |
| `expired` | Expired | the result was removed by retention or the storage budget |

The codes `DRS-9080` to `DRS-9089` are listed with their fixes in [TROUBLESHOOTING.md](TROUBLESHOOTING.md).
