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
# QA-UX activity log, 2026-10-03: the Build workbench, usability, accessibility, docs

Tester: QA agent (UX). Repo HEAD fea9398a (develop), jar built 12:26. Scratch server :18963 (security on, `DRISHTI_STUDIO_SAVE=true`, `DRISHTI_BUILDER_FILE_BINDING=true`, the nine packs of the brief, data in a scratch folder), console :17963 (`DRISHTI_AUTH_ENABLED=true`). Users made through `/admin/api/users`: `qaauthor` (author), `qaappr` (approver), `qaviewer` (viewer), `qaadmin` (seed admin). Everything driven with Playwright/Chromium (scripts/). Sample data: 6 and 50 small shipment JSON files. The user's :18480/:17480 were never touched; my own two processes were stopped by PID at the end.

| # | What | Script | Result (details in FINDINGS.md) |
|---|---|---|---|
| 1 | Guide s1/s2: New screen, choose a folder, name, Create; the workbench | s1_new.py | works, 0.35 s to the canvas; **Problems 1** on a clean design (UX-01) |
| 2 | Mouse path: palette drop, field bind, suggestions menu, move, resize (width, height, double-click), delete via "Remove panel", undo/redo buttons | s2_mouse.py | all work; B2 suggestion menu "1 panel kinds suit" (UX-27) |
| 3 | Keyboard-only path: Tab order, arrows, Alt/Shift arrows, N, B, Enter, Delete, Ctrl+Z, Ctrl+K, F1 | s3_kbd.py, s20_guide.py | works; 71 Tab presses to reach the canvas, no way back from the inspector (UX-10) |
| 4 | All 20 kinds from the palette and the inspector (labels, controls, required, draws) | s5_kinds.py, s5b.py, s6_options.py, s25_numeric.py | every option has a labelled control; 5 numeric options and 3 structured ones cannot be set (UX-03) |
| 5 | Inspector option types, expressions with autocomplete, span bounds, list editors | s11_inspect.py | autocomplete good; span 3.5 accepted (UX-26) |
| 6 | YAML tab edits round-trip, split, bad YAML, dup id, unknown option, bad expression | s9_errors.py, s10_yaml.py | bad expression is invisible on Problems (UX-02) |
| 7 | Versions, diff, restore, command palette (all commands), tests matrix, sample switcher, preview with a file | s12_versions.py | works; palette "go to sample: name" fails with folder names (UX-17) |
| 8 | End to end: propose, approve as another user, live | s7_e2e.py | works exactly as the guide says (P-000001, "proposed", "live v1") |
| 9 | Ship: export zip, `sutra test` on it, import it back, share link, recipient, revoke, file binding, IDE edit coming back, clash | s21_ship.py, s22_bind409.py | work; export file name differs from the guide (UX-20) |
| 10 | 409 from two tabs | s22_bind409.py | good message, design reloaded |
| 11 | Every old Studio URL (17 variants) and the JSON endpoints | s8_redirects.py | all redirect; unknown example/design/sutra fall back silently or with a bare name (UX-14, UX-15) |
| 12 | All 10 examples as copies; their help pages and links | s13_examples.py, s14_help.py | 7 of 10 open with "Problems 1" (UX-01); help links all live; duplicate links (UX-21) |
| 13 | 7 themes x desktop/390 px: sideways scroll, WCAG contrast | s15_themes.py, s19_phone.py | YAML tab and My designs scroll sideways at 390 (UX-08); no real contrast failures (gradient buttons are tool false positives) |
| 14 | Screen-reader semantics, focus order, focus traps, live regions | s16_a11y.py, s17_dialogs.py | solid; two identical live regions (UX-18) |
| 15 | Error messages (35 cases) and DRS codes | s9_errors.py, s23_start.py | DRS code twice on every new Build page (UX-04) |
| 16 | Performance: first canvas, check-as-you-type with 50 samples | s18_perf.py, s18b_perf.py | 0.1 s to canvas; ops 15-25 ms; 50-sample check 17-20 ms (Info) |
| 17 | Sutra CLI guide followed literally | (shell, see below) | works; two rough edges (UX-24) |
| 18 | F1, help links, guide screenshots against the real UI | s14_help.py, s20_guide.py | screenshots predate step 8 (UX-19) |

## CLI transcript (SUTRA_CLI.md, run from a scratch folder with the repo's packs/config linked)
- `sutra lint packs/market-risk` rc 0 (4.2 s). `sutra test packs/market-risk --junit target/sutra-tests.xml` rc 0, but 4 of 5 Sutras print "skip (no samples)". `sutra preview ... --out`, `shape --out`, `design --kind deal --out` write the files named in the guide (rc 0).
- `sutra bogus`, `sutra lint nopath`, `sutra lint`: rc 2, first line says why ("nopath does not exist", "lint needs a path"), then the usage. An `expect.yaml` with `bogus: 1`: rc 2 "unknown key 'bogus' (noErrors, nonEmpty, samples)".
- `sutra test docs/guides/examples` WITHOUT `DRISHTI_PACKS`: rc 1 ("linked-sources ... NS-SUMMIT-NY not found"); with the nine packs: rc 0. The guide says this in a paragraph further down; the failure line does not.
- A pack fragment exported from the workbench: `sutra lint` ok, `sutra test` ok (6 samples, rc 0, JUnit with 6 testcases), as the guide promises.
- Every run prints a Spring "Standard Commons Logging" line and a WARN "sutra directory .../sutras does not exist".

## Method notes and retractions
- A first reading of "File -> Open a malformed .yaml does nothing" was my tool's doing (Playwright dismisses the `confirm()` the workbench raises). With the dialog accepted the message is shown. Not a finding.
- The automatic contrast pass reports 'Drishti', 'Auto-design', 'New screen', 'Open as my copy' at ratios 1.0 to 1.3: they are gradient or background-clip text the pass cannot see. Computed styles checked by hand: white on rgb(152,91,10) and white on rgb(165,28,48) pass, dark on yellow passes.
- The designs quota (50) was hit during the run; `scripts/cleanup.py` empties it (this is also finding UX-05).
