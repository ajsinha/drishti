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
# Screen Builder: the build plan of 1.20.0

> **Archived: replaced by [BUILD_WORKBENCH.md](../architecture/BUILD_WORKBENCH.md)** (steps 4 to 8 there: one persisted Design and one workbench page). This is the step table of [SCREEN_BUILDER.md](../architecture/SCREEN_BUILDER.md) as planned for 1.20.0, kept as history; the separate pages it names (`/build/shape`, `/build/design`) no longer exist.

## How it was built, step by step

> Steps 4–7 below are superseded by [BUILD_WORKBENCH.md](../architecture/BUILD_WORKBENCH.md), which organises all Build tools around one
> persisted *Design* and one workbench page (steps 4–8 there). Steps 1–3 here are built and stay.

Each step ships on its own, ends with tests that fail before it, its docs, and a drill. Later steps only add.

| Step | Delivers | Proven by |
|---|---|---|
| **1. Shape engine** | `drishti-engine` `shape` package: merge, types, formats, enums, maps, trees, roles with reasons; `POST /builder/shape` | unit tests per rule; a property test that **every sample validates against its inferred schema**; the 10 examples' JSON infer the roles their panels need. **Done in 2d525b4.** |
| **2. Shape extractor page** | `/build/shape`: upload (≤ 50), schema tree with roles and presence, conflicts report, downloads; Build menu entry; sample sets per user | console tests; Playwright upload-and-download; limits refused cleanly. **Done**: [user guide](../guides/SCREEN_DESIGNER.md#1-start-bring-data); sample sets were kept in the console's memory per user for `builder.ttl_hours` (since the workbench, `/build/shape` redirects to the Data step of `/build/new` and samples are kept with the Design); "Open in Studio" pastes the first sample (no Sutra drafted until step 3). |
| **3. Auto-design and suggest** | inference rules generalised from one document to a shape (`LayoutPacker`, `PanelRecipes`, shared column rules); `AutoDesigner` with reasons, ranked alternatives, sample-aware strip and charts, pruning against every sample; `POST /builder/design` and `/suggest`; "Draft a screen" on `/build/shape` with **Open in Studio** | auto-design of each example's samples renders with no panel errors and uses the expected kinds; a field missing in most samples gets its panel pruned with a reason; suggest's first choice per role; runtime inference unchanged on the existing tests. **Done**: see "As built (step 3)" under *Start: auto-design*; the visual designer (step 5) is built on top. |
| **4. Edit operations** | operations on `SutraLayoutEditor`; `POST /builder/edit` with undo-safe results | round-trip tests: operations keep comments and order; every op on every kind; bad ops are located problems **Done**, in the workbench (see [SCREEN_DESIGNER.md](../guides/SCREEN_DESIGNER.md)). |
| **5. Designer** | `/build/design`: canvas over the real preview with handles, palette, field drops with suggestions, inspector from the Rachana schema, YAML tab, undo, keyboard alternatives | Playwright: build the all-panels showcase from its shape without typing YAML; keyboard-only path; phone width **Done**, in the workbench (see [SCREEN_DESIGNER.md](../guides/SCREEN_DESIGNER.md)). |
| **6. Check every sample** | `POST /builder/check`, the matrix, design-time presence and conflict warnings | a sample set with a missing field shows the right empty/error cells **Done**, in the workbench (see [SCREEN_DESIGNER.md](../guides/SCREEN_DESIGNER.md)). |
| **7. Save, export, guide** | propose with evidence attached, pack scaffold, store sampling, the user guide in help with a walkthrough, Studio ↔ Designer links | an end-to-end test: upload samples → auto-design → edit → check → propose → approve → the view serves **Done**, in the workbench (see [SCREEN_DESIGNER.md](../guides/SCREEN_DESIGNER.md)). |

Steps 1 to 3 already give authors a useful tool (shape + a drafted Sutra to refine in Studio); steps 4 and 5 are the
visual designer; 6 and 7 make it safe to ship screens from it.
