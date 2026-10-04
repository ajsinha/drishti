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
# Adversarial QA, round 2 (2026-10-03): the Build workbench

Scope: everything added since [round 1](../2026-10-01/README.md): the Screen Builder (shape, auto-design, suggest), the
workbench (Designs, the visual canvas, operations, revisions, the checker, versions, command palette), Studio folded in,
shipping (propose with evidence, pack export/import, share links, file binding), the headless `sutra` CLI, `source:` on
every kind, expandable row groups, and the open-design / gated-deploy policy. Three passes ran in parallel, each on its
own scratch server and console; nothing ran against the developer's own server, containers or data. No product code
was changed during QA.

| Area | Findings | Log, findings, reproductions |
|---|---|---|
| Security and access | 0 High, 3 Medium, 7 Low, 4 Info | [security/](security/) (about 950 requests; isolation matrix of 25 endpoints × 6 callers) |
| Data and engine | 0 High, 7 Medium, 14 Low, 8 Info | [data/](data/) (brute-force checkers for shape, pivot, tree, matrix; 1,800 random shape sets) |
| Workbench usability and docs | 0 High, 8 Medium, 18 Low, 3 Info | [ux/](ux/) (guide followed end to end; 72 screenshots kept locally, not committed) |
| **Total** | **0 High, 18 Medium, 39 Low, 15 Info** | |

## Medium findings

| Id | Finding |
|---|---|
| [S2-01](security/FINDINGS.md) | With development file binding on, *Save to file* makes a Sutra live without review (overwrites an approved version, or creates one). **Fixed** in 89121ae7. |
| [S2-02](security/FINDINGS.md) | A JSON Schema with huge `minItems`/`minLength` makes synthetic sampling run the server out of memory; any signed-in user can send it. **Fixed** in 89121ae7. |
| [S2-03](security/FINDINGS.md) | A design's Sutra, notes and tests are unbounded and not counted against the design and user size limits. **Fixed** in 89121ae7. |
| [M-1](data/FINDINGS.md) | Approving a design edited from `name@v` rewrites version v instead of creating v+1; a pack-owned version is refused only at approval, leaving the proposal stuck. **Fixed** in 89121ae7. |
| [M-2](data/FINDINGS.md) | No rebase when the base Sutra moves: no flag, no replay, no problem (the design document promises them). **Fixed** in 89121ae7. |
| [M-3](data/FINDINGS.md) | Integers beyond 2^53 and infinities break the shape schema (`minimum` written as a double or as the string "Infinity"). **Fixed** in 69343448 |
| [M-4](data/FINDINGS.md) | Sparse optional object sections turn a record into a map and merge their fields. **Fixed** in 69343448 |
| [M-5](data/FINDINGS.md) | Export then import grows the notes on every cycle (README re-imported) and rewrites non-ASCII sample names. **Fixed** in 1615c22f |
| [M-6](data/FINDINGS.md) | `sutra test` writes no JUnit file when a sample is invalid JSON or starts with a BOM. **Fixed** in 2a9e9ea2 |
| [M-7](data/FINDINGS.md) | `sutra test` passes an empty (0-byte) sample. **Fixed** in 2a9e9ea2 |
| [UX-01](ux/FINDINGS.md) | A design whose kind no pack defines opens with a false problem ("'shipment' is not a valid kind"); 7 of 10 examples do. **Fixed** in b05bca8e |
| [UX-02](ux/FINDINGS.md) | A mistyped expression shows no problem; the canvas says "Nothing to draw yet" and the error is only on the Tests tab (with the code twice). **Fixed** in b05bca8e |
| [UX-03](ux/FINDINGS.md) | The inspector cannot set some options: numeric `limit`/`expand` refuse numbers; `graph.layout` should be a choice; `histogram.markers`, the table `pivot` object and the pivot `by` list are YAML-only. **Fixed** in 272d0572 |
| [UX-04](ux/FINDINGS.md) | DRS codes written twice on Build pages ("DRS-5006 … (DRS-5006)"). **Fixed** in b05bca8e |
| [UX-05](ux/FINDINGS.md) | Every `/studio` visit, example open or Help link makes a scratch design that counts toward the 50-design quota; at the limit everything answers 413 and there is no bulk delete. **Fixed** in 89121ae7. |
| [UX-06](ux/FINDINGS.md) | A design's notes are invisible in the workbench yet go to the reviewer. **Fixed** in 1ee0729f |
| [UX-08](ux/FINDINGS.md) | At 390 px the YAML tab and My designs scroll sideways. **Fixed** in 4ff7a824 |
| [UX-10](ux/FINDINGS.md) | Keyboard: 71 Tab presses to reach the canvas; Esc in the inspector does not return focus. **Fixed** in 1ee0729f |

Low and info findings are in each area's FINDINGS.md. Notable lows: cross-site GETs create designs (S2-04, overlaps
UX-05); symlinks followed in file binding (S2-05); absolute paths shown by `/binding` (S2-06); bare 500s on odd input
(S2-07); entity ids in reference sample names reach share links and evidence (S2-10); auto-design gaps (monthly series,
single-root trees, years as amounts, empty links panel, non-reproducible key order, unvalidated `kind` injection L-7);
CLI `design --kind` path escape; `setStrip` drops comments; `markdown` rejects `source:`; doubled live announcements;
guide screenshots before the Ship button.

## What held up

Design isolation across 25 endpoints and 6 callers (with path, method and header variants, id guessing, mass
assignment); share links (no sample contents or notes; revocation and renewal immediate); the open-design / gated-deploy
policy and four-eyes outside file binding; kind rights and field masks on every new surface; pack import against
zip-slip, zip bombs, billion laughs and `!!java` tags; console CSRF (90 rows), JSON-as-text, stored XSS; the shape engine
(1,800 random sets and 45 hostile cases validate against their own schema; required and presence equal brute force);
the check matrix equal to the preview cell by cell; nested pivots against brute force (120 cases, five aggregations,
masks collapse to one `•••` group); `source:` on 13 kinds; file and database stores identical and restart-safe; quotas,
expiry, the 100-step undo cap; 16 concurrent writers produce exactly one winner; the guide followed end to end (propose,
approve as another user, live); first canvas in 0.1–0.35 s and a 50-sample check in about 20 ms; ARIA roles and contrast
in all seven themes.

## Fix status

Wave 1 (governance and safety) is fixed in 89121ae7: S2-01 to S2-14, M-1, M-2, L-7 (with the CLI `--kind` escape) and UX-05. Each row in the area FINDINGS.md names its test. S2-08 (clean 413 for oversized requests) and S2-11 (opt-in `mask-copies`, dotted masks, pack defaults) are **Fixed** in 1.14.1.

## Proposed fix order

| Wave | Findings |
|---|---|
| 1. Governance and safety | S2-01, M-1, M-2, S2-02, S2-03, S2-04, UX-05, S2-05, S2-06, S2-07, S2-10, L-7, CLI `--kind` escape |
| 2. Correctness | M-3, M-4, M-5, M-6, M-7, UX-01, UX-02, UX-03 and the data lows |
| 3. Usability and docs | UX-04, UX-06, UX-08, UX-10 and the UX lows, guide screenshots |
