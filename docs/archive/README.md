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

# Archive

> **These documents are history: they describe designs that were replaced or removed. Do not use them to learn how Drishti works today.**

They are kept so an old decision can be traced, and because the code and the current designs still cite ids they defined (the parity
items P1 to P10 and R1 to R8, the waves W1 to W16). Nothing here is listed or searched in the help centre, and the documentation tests do not
check that what these files name still exists; their links must still resolve. An active document links here only where history is the
point, and says so on the same line.

| Document | What it was | Date or release | Replaced by |
|---|---|---|---|
| [RUPAKA_POC.md](RUPAKA_POC.md) | The Rūpaka phase 0 proof of concept: what it built and measured (Perspective live grid, masked Arrow out of DuckDB, DuckDB-Wasm in the browser, CSP with WebAssembly, JSON against typed columns against a rollup), and the Perspective retest of phase 5 | 2026-10-10, before 2.2.0; its code deleted in 2.2, Perspective and DuckDB-Wasm removed in 2.3.0 | [BI_DESIGN.md](../architecture/BI_DESIGN.md) (the design), [BI_GUIDE.md](../guides/BI_GUIDE.md) (how BI works today) |
| [BI_PARITY_PLAN.md](BI_PARITY_PLAN.md) | Rūpaka against Microsoft Power BI: gap analysis, top gaps, the redesign items R1 to R8, the Python data-prep items P1 to P10 and a phased roadmap | 2026-10-12, a study of 2.1.0 | [BI_DESIGN.md section 11](../architecture/BI_DESIGN.md#11-gap-check-against-power-bi) (gap check) and [section 12](../architecture/BI_DESIGN.md#12-w1-as-built-against-this-design-and-the-revised-wave-plan) (waves) |
| [BI_MODEL_ARCHITECTURE_WAVES.md](BI_MODEL_ARCHITECTURE_WAVES.md) | Sections 4 and 5 of BI_MODEL_ARCHITECTURE.md: the wave plan W1 to W16 (2.2 to 3.0) and its risks | 2026-10-12, after 2.1.0 | [BI_DESIGN.md section 12](../architecture/BI_DESIGN.md#12-w1-as-built-against-this-design-and-the-revised-wave-plan); the decisions D1 to D6 stay current in [BI_MODEL_ARCHITECTURE.md](../architecture/BI_MODEL_ARCHITECTURE.md) |
| [RUPAKA_2_1_ENGINE.md](RUPAKA_2_1_ENGINE.md) | The opening of section 7 (the query engine) and section 17 (the architecture diagram) of RUPAKA.md as designed: Arrow for Perspective and DuckDB-Wasm, Graphic Walker, the modules `drishti-bi-core`, `-query`, `-live`, `-ai` | 2.1.0; replaced in 2.3.0 (wave B: every number on the servers) | [BI_DESIGN.md section 4](../architecture/BI_DESIGN.md#4-query-architecture) and [section 3.5](../architecture/BI_DESIGN.md#35-python-sandboxed-workers-on-the-console-backend); the rest of [RUPAKA.md](../architecture/RUPAKA.md) stays current |
| [SCREEN_BUILDER_BUILD_PLAN.md](SCREEN_BUILDER_BUILD_PLAN.md) | The step table of SCREEN_BUILDER.md: steps 1 to 7 as planned, with the separate pages `/build/shape` and `/build/design` | 1.20.0 | [BUILD_WORKBENCH.md](../architecture/BUILD_WORKBENCH.md) (steps 4 to 8: one persisted Design, one workbench page); the rest of [SCREEN_BUILDER.md](../architecture/SCREEN_BUILDER.md) stays current |
| [DATASETS_2_1_COMPOSED_DERIVED.md](DATASETS_2_1_COMPOSED_DERIVED.md) | Composed (`from:` and `join:`) and derived (a Python Transform) datasets: DATASETS.md sections 11.1 to 11.4, what was not built for them, and their TROUBLESHOOTING entries | 2.1.0; removed in 2.2.0 (`DRS-9190`, `drishti.py bi migrate --models`) | models with several fact tables ([DATASETS.md section 3.1](../guides/DATASETS.md#31-several-unrelated-fact-tables-in-one-query)) and Python datasets ([PYTHON_BI.md section 13](../guides/PYTHON_BI.md#13-transforms-a-table-in-a-table-out)); what the samples became is [DATASETS.md section 11.5](../guides/DATASETS.md#115-the-samples-as-converted-in-22) |

**Not archived** (still the main reference for something that exists): [RUPAKA.md](../architecture/RUPAKA.md) (the BI page, datasets, reports,
the designer, Python interfaces and section 20, every phase and wave as built; the code cites its sections), [HOW_IT_FITS.md](../architecture/HOW_IT_FITS.md)
(the only end-to-end walk through a command, *About this page* and *Share and Discussion*; its names are of the 2.2-era packs, as its banner says),
[SCREEN_BUILDER.md](../architecture/SCREEN_BUILDER.md) (the shape engine, auto-design and the builder API) and
[BI_MODEL_ARCHITECTURE.md](../architecture/BI_MODEL_ARCHITECTURE.md) (the decisions D1 to D6).
