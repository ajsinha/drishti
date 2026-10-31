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
---
sources:
- name: Synthetic P&L strips (a seeded random walk, config/packs/compute-demo/tools/make_samples.py)
  licence: Proprietary (part of Drishti)
  internal-only: false
  used-for: path-book, path-strip
- name: Computed on request by the reactive connectors path-sum-python and path-sum-java
  licence: Proprietary (part of Drishti)
  internal-only: false
  used-for: path-sum, path-sum-java
fields:
  path-book: {MEMBERS: The strips of the book (their ids), PATH_ID: The id the computed kinds take for this book}
  path-strip: {PNL: The strip's P&L on each of 250 scenario days}
  path-sum: {SUM: Sum of the book's path over all days, WORST: Worst day of the book's path, BEST: Best day, ROWS: Strips added up, WIDTH: Scenario days, RESULTS: The engine's rows, TABLES: "total: the book's path, one row per day (DAY, PNL)", STAMP: How it was computed (pin, inputs, engine, where it ran, result hash), COMPUTE: The panel's state (ready, computing, stale, failed, refused) and request id}
  path-sum-java: {SUM: Sum of the book's path over all days, WORST: Worst day of the book's path, BEST: Best day, ROWS: Strips added up, WIDTH: Scenario days, RESULTS: The engine's rows, TABLES: "total: the book's path, one row per day (DAY, PNL)", STAMP: How it was computed (pin, inputs, engine, result hash), COMPUTE: The panel's state and request id}
---
## What it is

The sample pack of reactive connectors (docs/architecture/DATA_ARCHITECTURE.md): three books of synthetic P&L strips, 250 scenario
days each, and the sum of a book's paths computed when a screen asks for it. `path-sum` is computed by the sample Python engine in the
compute pool `sample`, cut over every worker of the pool; `path-sum-java` by Drishti's own Java engine inside the server. The book's
screen shows both, each panel with its own state (computing with progress, ready, stale, failed, refused). The numbers are made up.
Not loaded by default: add `compute-demo` to `DRISHTI_PACKS`; the Python panels need `compute.enabled: true`.

## Refreshing the data

The books and strips are written by `config/packs/compute-demo/tools/make_samples.py` from a fixed seed; nothing is downloaded. The
computed kinds are never stored: they are computed on request, cached, and computed again when their inputs' data version moves or a
write-back event names a book.
