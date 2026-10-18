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
# Lake maintenance: Admin → Lakes → Maintain and `lake maintain`

A Delta lake that is only ever written grows without bound: every load leaves small files, old business dates stay forever and the
transaction log gets longer. **Maintenance** does what Drishti's readers cannot do themselves:

| Step | What it does | Setting |
|---|---|---|
| **Retention** | Deletes the business dates older than the history window, counted back from today (never from the newest date in the table). A run that would delete more than `max-drop-share` (0.5) of a table's rows deletes nothing and says why. | `keep-business-days` (blank keeps every date) |
| **Compaction** | Merges each date's small files into files of the target size. A table whose pack declares a layout is instead rewritten in that layout, only for the dates that drifted from it. | `target-file-mb` (128) |
| **Checkpoint** | Writes a Delta checkpoint, so readers replay a short log. | `checkpoint` (on) |
| **Vacuum** | Removes the files no table version uses any more, once they are older than the window. A *known at* view reaches back that far, no further. | `vacuum-hours` (168, one week) |

There is one engine, `tools/lake/maintain.py`. The page **Admin → Lakes** runs it in the console, and `drishti.py lake maintain` runs it
from a terminal, cron or a container. Both read and write the same file, `<data>/lakes/lake-maintenance.yaml`, and keep the same history.

- [1. The tables and who owns them](#1-the-tables-and-who-owns-them)
- [2. Dry run](#2-dry-run)
- [3. Maintain now](#3-maintain-now)
- [4. Settings per table](#4-settings-per-table)
- [5. Schedules](#5-schedules)
- [6. History](#6-history)
- [7. The file, the API and the settings](#7-the-file-the-api-and-the-settings)

The examples use a small demo lake, the same one in the UI and on the command line. It has two trade tables that `lake build` made from
six small loads each (`trading/trade_endur` and `trading/trade_calypso`), and one table that another system published (`risk/var_published`).

## 1. The tables and who owns them

**The ownership rule.** Drishti maintains only the tables that its own lake tool, `drishti.py lake build`, built. Every build that commits records
its table in `<lake>/_drishti-lake/tables-built.json`, with the pack, kind and first and last build. Builds made before that file existed are
found once in their run summaries (`_drishti-lake/runs/*.json`). A table with no build record was published by another system, such as a
vendor feed or another team's Spark job. That system decides how the table is compacted and vacuumed. If Drishti did it too, it could remove
files the publisher still expects, or rewrite files under a reader that is in the middle of a scan. Drishti therefore never touches these
tables: it lists them read-only.

**In the UI.** Open **Admin → Data loads → Lakes** (`/admin/lakes`) and scroll to **The lakes**. Each table shows its rows, files, size,
partitions, business dates and last build; its name opens the table's own page (dates, columns, commits, loads, the connectors that read
it: [TOOLS.md section 10.2](../guides/TOOLS.md)). The **Maintenance** column shows the table's current settings, or for a foreign table
*Maintained by its publisher: not built by drishti.py lake build: read only here*. A foreign table's checkbox is disabled, and the reason is
also shown when you hover over it.

![The lakes with Maintain: a table another system published is read only](img/lake-maintenance/lake-maintain-tables.png)

To choose tables, tick them one by one, use **Select all**, or use **Select a domain**, which ticks every table of that domain that Drishti owns.
The line under the table always shows the command that does the same from a terminal or cron, with a **Copy** button.

**From the command line.** `lake status` lists the same tables:

```text
$ drishti.py lake status --lake delta
tables (3):
  table                  rows   files  size     partitions  dates  latest date  last build
  risk/var_published     1      1      669 B    1           1      2026-10-16   2026-10-17 23:51:39Z
  trading/trade_calypso  3,000  6      64.4 KB  3           3      2026-10-16   2026-10-17 23:51:39Z
  trading/trade_endur    3,000  6      64.4 KB  3           3      2026-10-16   2026-10-17 23:51:37Z
lock: free
```

`lake maintain` refuses a foreign table that you name. A run over every table skips foreign tables without failing:

```text
$ drishti.py lake maintain --once --tables risk/var_published
{"at": "2026-10-17T19:51:57-04:00", "event": "refused", "table": "delta/risk/var_published", "reason": "maintained by its publisher: not built by drishti.py lake build (no build record in _drishti-lake)"}
{"at": "2026-10-17T19:51:57-04:00", "event": "run", "outcome": "failed", "tables": 1, "failures": 1}
(exit 1)
```

`--force-foreign` overrides the refusal. It prints a warning, and the history marks the table *forced*. Use it only when the publisher has
agreed. The page never offers it.

**The YAML.** Ownership is not configured anywhere: it is the build record. Only tables you can tick have settings in the file.

## 2. Dry run

**In the UI.** Tick the tables and press **Dry run**. The run happens in the background. For each table it shows:

- the files and size **before → after**, marked *estimate*: a dry run changes nothing, so the size after is worked out from the dates
  retention would keep and from how compaction would merge their small files;
- the dates **retention would drop**, or *keeps every date*;
- what **vacuum would delete**: the number of files, their size, and the window in hours.

![A dry run: files before and after, retention, vacuum](img/lake-maintenance/lake-maintain-dry-run.png)

**From the command line.**

```text
$ drishti.py lake maintain --once --dry-run --tables trading/trade_endur --as-of 2026-10-16
{"at": "2026-10-17T19:51:57-04:00", "event": "maintained", "dry_run": true, "table": "delta/trading/trade_endur", "before": {"files": 6, "mb": 0.06, "dates": 3, "latestDate": "2026-10-16", "latestRows": 1000}, "retention": {"cutoff": "2025-10-17", "would_remove_files": 0, "dates": []}, "estimate": {"files": 3, "mb": 0.06, "dates": 3, "latestDate": "2026-10-16", "latestRows": 1000, "estimate": true}, "vacuum": {"files": 0, "dry_run": true, "hours": 168, "first": [], "mb": 0.0}, ...}
{"at": "2026-10-17T19:51:57-04:00", "event": "run", "outcome": "ok", "tables": 1, "failures": 0}
(exit 0)
```

`--as-of DATE` counts retention back from that date instead of today. Without `--config` the command reads
`<data>/lakes/lake-maintenance.yaml` (`<data>` is `DRISHTI_DATA_DIR`, else `./data`), the same file the page uses.

**The YAML.** A dry run writes nothing to the file. It does add a line to the history, marked *dry run*.

## 3. Maintain now

**In the UI.** Press **Maintain now**. The progress bar counts the tables, names the one in hand, and lists each finished table as it ends.

- **One run at a time per lake.** A second run on the same lake is refused while one is going. Each run also takes the lake tool's lock
  (`<lake>/_drishti-lake/lock`) exclusively; `lake build` holds it shared while it loads (and its own table's lock exclusively), so
  maintenance waits for every load and no load starts while it runs. A table whose lake is locked fails with *the lake is locked by …*
  and nothing is changed.
- **Cancel after this table** stops the run before the next table. The table in hand finishes, so a table is never left half maintained.
- **Afterwards** each changed table that was built for a pack sends the **data-load signal** (`POST /api/v1/packs/{pack}/loads` for its
  latest business date, `source: lake maintain`), as `lake build` does. The server refreshes and readers' caches stay right. The signal is
  sent as the administrator who started the run; for a schedule, as the administrator who made it. Each row of the result shows
  *readers told (date)*, or why the signal failed. The maintenance itself still stands if the signal fails.

**From the command line.**

```text
$ drishti.py lake maintain --once --tables trading/trade_endur,trading/trade_calypso
{"at": "2026-10-17T19:51:58-04:00", "event": "maintained", "dry_run": false, "table": "delta/trading/trade_calypso", "before": {"files": 6, "mb": 0.06, ...}, "compact": {"numFilesAdded": 3, "numFilesRemoved": 6}, "checkpoint": 6, "vacuum": {"files": 0, "dry_run": false, "hours": 168, "first": []}, "after": {"files": 3, "mb": 0.02, ...}}
{"at": "2026-10-17T19:51:58-04:00", "event": "maintained", "dry_run": false, "table": "delta/trading/trade_endur", ... "after": {"files": 3, "mb": 0.02, ...}}
{"at": "2026-10-17T19:51:58-04:00", "event": "run", "outcome": "ok", "tables": 2, "failures": 0}
(exit 0)
```

Add `--signal http://server:18080` (with `DRISHTI_TOKEN` or `--token-file`) to send the data-load signal from the command line.
`--tables` takes `domain/table` names, or `root|domain/table` when the file lists several lakes. Without `--tables`, every table of every
lake in the file is maintained, apart from foreign tables. The exit code is 0 when every table succeeded and 1 when any failed.

## 4. Settings per table

**In the UI.** Tick the tables, then open **Settings for the selected tables**. With exactly one table ticked, its current values are filled in.

| Field | Saved as | Default |
|---|---|---|
| Retention (business days) | `keep-business-days` | blank: keep every date |
| Target file size (MB) | `target-file-mb` | 128 |
| Write a checkpoint | `checkpoint` | on |
| Vacuum files unused for (hours) | `vacuum-hours` | 168; blank: no vacuum |

Only the fields you change are saved, to every ticked table. Below 168 hours, a warning appears beside the vacuum field: a shorter window
removes files that a reader, or a *known at* view, may still be using.

![Settings for the selected tables, with the vacuum warning](img/lake-maintenance/lake-maintain-settings.png)

**From the command line and the YAML.** The settings are the per-table entries of the file. Edit the file by hand and the page shows your
values the next time it loads:

```yaml
lakes:
  - root: data/delta
    domains: ["*"]
    keep-business-days: null      # the lake's defaults ...
    vacuum-hours: 168
    tables:                       # ... and per table, over them
      trading/trade_endur: {keep-business-days: 260, target-file-mb: 64, checkpoint: true, vacuum-hours: 168}
```

A table's settings are its own entries over its lake's, and its lake's over the defaults. Keys the page does not know (`max-drop-share`,
`compact`, `storage-options`, comments aside) are kept when it saves. The page rewrites the file whole: comments you wrote by hand are not
kept.

## 5. Schedules

**In the UI.** Tick the tables (all from one lake), open **Schedules**, name the schedule and choose when it runs:

- **At a time.** Tick the days and pick the time. The cron text (`minute hour day month weekday`) is written for you as you pick, and its
  plain words show below it, such as *Tue–Sat 02:30*. You can also type cron text yourself, for example `*/30 * * * *`. Times are in the
  schedule zone (`schedule.zone` in the file, default America/New_York).
- **After every build of the table.** The schedule runs when `lake build` writes one of its tables again, whether the build came from the
  page, the command line or cron.

The list shows each schedule's times, its tables, its next run and its last run. **Pause** stops a schedule without forgetting it,
**Resume** starts it again, and **Delete** removes it after you confirm.

![Schedules: the list, and a new schedule with the day-and-time picker](img/lake-maintenance/lake-maintain-schedules.png)

**Missed runs.** If the console was stopped when a run was due, it runs that schedule **once** when it starts again. Several missed runs
still make one run. The history marks it *catch-up: the run due … was missed while nothing was running* (the behaviour of systemd's
`Persistent=true`).

**Where schedules run.** They run in the console (`lakes.maintenance_schedules: true`), checked every 30 seconds. To run them without a
console, use the lake tool's daemon, which reads the same file and uses the same scheduler:

```text
$ drishti.py lake maintain --daemon            # the file's schedules; with none, every table daily at schedule.at
```

Run the schedules in **one** place. When the daemon runs them, set `DRISHTI_LAKE_MAINTENANCE_SCHEDULES=false` on the console. Drishti uses
no other scheduler for this, and no crontab entry is needed. To use cron instead, have it run `lake maintain --once`, the line the page shows.

**The YAML.**

```yaml
lakes:
  - root: data/delta
    schedules:
      - {name: nightly, when: "30 2 * * 2,3,4,5,6", tables: [trading/trade_endur], paused: false, by: admin, roles: [admin]}
      - {name: after-load, when: after-build, tables: [trading/trade_calypso], paused: true, by: admin, roles: [admin]}
schedule: {zone: America/New_York}
```

What each schedule last did is kept beside the history, in `<data>/lakes/maintenance-schedule-state.json`.

## 6. History

Every run is kept in `<data>/lakes/maintenance-history.jsonl`: dry runs, manual runs, scheduled runs and command-line runs. Each entry
records when it ran, who ran it or which schedule, the tables, the files and size before → after, the outcome (ok, partial, failed or
cancelled), any note (such as a catch-up), and the errors. The last 2,000 runs are kept.

**In the UI.** **Maintenance history** lists the last 50 runs, newest first.

![The maintenance history](img/lake-maintenance/lake-maintain-history.png)

**From the command line.**

```text
$ drishti.py lake maintain --history
2026-10-17T19:51:57-04:00  ok        run     by ashutosh  2 table(s)  files 12 -> 6  MB 0.12 -> 0.04
2026-10-17T19:51:57-04:00  failed    run     by ashutosh  1 table(s)  files 0 -> 0  MB 0.00 -> 0.00
    risk/var_published: refused: maintained by its publisher: not built by drishti.py lake build (no build record in _drishti-lake); --force-foreign maintains it anyway
2026-10-17T19:51:57-04:00  ok        dry run by ashutosh  1 table(s)  files 6 -> 3  MB 0.06 -> 0.06
```

`--history --json` prints the entries as JSON, and `--limit N` sets how many are shown.

## 7. The file, the API and the settings

| What | Where |
|---|---|
| Settings and schedules | `<data>/lakes/lake-maintenance.yaml` (`lake maintain --config` reads any file in this format; `deploy/lake-maintenance.yaml` is an example) |
| History | `<data>/lakes/maintenance-history.jsonl`, or `history:` in the file |
| Schedule state | `<data>/lakes/maintenance-schedule-state.json` |
| Ownership | `<lake>/_drishti-lake/tables-built.json`, written by `lake build` |
| The lock | `<lake>/_drishti-lake/lock`, shared with `lake build` |

The page calls the console's own API. These endpoints are for administrators only, under `/admin/api/lakes/maintain`:

| Method | Path | Body / answer |
|---|---|---|
| `GET` | (the base path) | Every table with `owned`, `owner`, `reason` and `settings`; the schedules with `friendly`, `next` and `lastRun`; the last 50 history entries; `configFile`, `zone` and the runs in progress |
| `POST` | `/runs` | `{tables: {root: [domain/table]}, dryRun}` gives `202` and the run. A foreign table gives `403 DRS-5002`; a lake with a run in progress gives `409 DRS-5032` |
| `GET` | `/runs/{id}` | The run's state, the table in hand, `done` of `total`, the results, and the history `entry` once the run ends |
| `POST` | `/runs/{id}/cancel` | Stops the run before its next table |
| `PUT` | `/settings` | `{tables, settings: {keep-business-days, target-file-mb, checkpoint, vacuum-hours}}` gives `{saved, warnings, tables}` |
| `POST` | `/schedules` | `{name, tables}` with `days[]` and `at`, or `cron`, or `afterBuild: true`; gives `201` |
| `POST` | `/schedules/pause`, `/schedules/delete` | `{root, name[, paused]}` |
| `POST` | `/command` | `{tables, dryRun}` gives the equivalent `lake maintain` line |

Console settings ([CONFIGURATION.md](CONFIGURATION.md)):

- `lakes.data_dir` (`DRISHTI_DATA_DIR`, default `../data`): where the file and the history live.
- `lakes.maintenance_schedules` (`DRISHTI_LAKE_MAINTENANCE_SCHEDULES`, default `true`): whether the console runs the schedules.
- `lakes.roots`: the lakes the page can maintain.

See also [TOOLS.md §7](../guides/TOOLS.md#7-lake-maintenance-toolslakemaintainpy) for the command line in depth, including `relayout`,
storage options for S3, and `max-drop-share`.
