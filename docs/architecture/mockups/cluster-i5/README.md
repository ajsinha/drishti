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

# Admin → Cluster → Engines (proposed): which engine runs where

Mock-ups for the product owner to choose from, for release i5. The design is
[CLUSTER_I5_DESIGN.md](../../CLUSTER_I5_DESIGN.md) (sections 5, 6 and 11); the question is Q16 there.

**Status: proposed, nothing built.** Static HTML, dark by default (`#light` for the light theme), illustrative figures. The built page
uses Drishti Elements only (the grid, chips, the drawer, the plan dialog).

All three show the same cluster: four nodes; VaR on `drishti-a` and `drishti-d`; exposure and sensitivities on `drishti-b`; FRTB-SA,
SIMM and the MR cube on `drishti-c`, which reaches the others through the engine lookup; exposure busy at the moment.

| Option | File | Starts from | Good at | Weak at |
|---|---|---|---|---|
| **A** | [A.html](A.html) | the engine × node matrix, a drawer for the selected cell, the calls between nodes below | "where does X run"; the same shape as the Modules tab; any number of nodes | the dependency picture is one column of text |
| **B** | [B.html](B.html) | one card per node | "what does this machine carry, how full is it" | more than about eight nodes; finding one engine |
| **C** | [C.html](C.html) | the needs map (engines, the nodes inside, arrows) and the single points | resilience planning, the end-of-day order | budgets; many engines |

**Recommendation: A**, with C's needs line in the drawer and C's *single points* table as a card under the matrix. B is not built: its
content belongs in the Members tab's row detail.

Screenshots are not included yet (no browser was run for this design-only step); they are taken when the owner has chosen.

## Build checklist (when an option is chosen)

- [ ] The matrix from the digests' `engines` part and the register's placement (design section 8); cells: running, waiting, blocked, not placed.
- [ ] The drawer: state, module and contract version, memory against budget, threads, in flight and queued, open dates, shards, needed by, inputs equal across the engine's nodes.
- [ ] *Move*, *Add a node*, *Remove*: each opens a placement plan (before and after, headroom, needs still met), four eyes to apply.
- [ ] Calls between nodes for the last hour from the measures (calls, p95, busy, failed over, refused).
- [ ] Live: the page follows `engine.available`, `engine.unavailable`, `engine.moved` without a reload.
- [ ] The kernel's per-node facilities shown apart, as switches, never as placement.
- [ ] Screenshots (dark and light) copied here and into ADMIN_GUIDE when the page exists.
