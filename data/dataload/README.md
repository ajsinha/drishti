<!--
  Project Drishti · Any data. Any domain. One grammar.
  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
  PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository.
-->

# The data inbox

This folder is the data inbox of the server (kept in git with only these README files; what a load writes here is never committed). Drop a data file in `inbox/`, then its `<file>.ready` marker (the file first, the marker last); the next cycle, or **Load now** on Admin → Data loads → Inbox, loads it into the lake, moves it to `outbox/<day>/<run>/` with a report, or to `failed/<day>/<run>/` with the reason. A cycle runs every 10 minutes by default. The inbox is an engine, off as shipped: Admin → Engines → Data inbox, or `DRISHTI_DATALOAD_ENABLED=true`.

| Folder | Holds |
|---|---|
| `inbox/` | your files and their `.ready` markers: the only folder you write to |
| `inprocess/` | the files a run has claimed, while they load |
| `outbox/` | loaded files with their reports |
| `failed/` | files that could not be loaded, with the reason |
| `ledger/` | one line per step of every file, kept for ever |

Every `README.md` here is ignored by the inbox. See sections 3.1 and 4 of [DATA_LAKE_GUIDE.md](../../docs/guides/DATA_LAKE_GUIDE.md).
