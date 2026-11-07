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
# Tutorial inventory (internal working file)

**Status: working file, 2026-11-04.** The product owner asked (2026-11-04, about 20:35) for every tutorial to be inventoried, then
reorganised, merged or retired ("lean & streamlined"), ahead of running and validating every one. This file is internal: the customer
documents are [docs/tutorials/](../tutorials/README.md). It says, per tutorial, what it teaches, what it uses, what was stale and what
was done, when its text and pictures were last produced, what it overlaps, and the verdict; then every output still to be re-run, and
the tutorials planned but not written.

## 1. How it was checked

- **Commands**: every `drishti.py <group> <command>` in every tutorial was checked against the CLI's own parser (`drishti.py <group> --help`):
  all resolve.
- **Command words**: every command word a tutorial types (`TRD`, `VAR`, `MCUBE`, `PVAR`, `FUND` ...) was found in a pack, the engines file,
  `menus.yaml` or the built-in words: all resolve (`DQ`, `DQP` and `MYTRD` are made by the reader in tutorials 5 and 21).
- **Menus**: every *Admin → X*, *Build → X*, *BI → X* and *Review → X* against `drishti-console/config/menus.yaml`, the Admin navigation
  and BI's rail: all exist.
- **Removed today**: the Studio stubs and `studio` switch, the notes API, LegacyAsk and Ask's model settings, the fva / sa-ccr / ccr-cube
  engine switches, `PLAY PVAR` and the parametric views B and C, the duplicate menu items, the pre-`drishti_` asset names: no tutorial
  used any of them except as noted below (tutorial 43 had been rewritten already). `DRISHTI_STUDIO_SAVE` is still the server's
  `studio-save`, not the removed switch.
- **Load rules** (dated file names, no row dates, append default): tutorials 34 and 36 were run under them; tutorial 5's `pack make`
  still reports the kind's date field (the pack's, not the load's); tutorial 7's `--date` is `data landed`'s own option.
- **Under construction** (`config/under-construction.yaml`): tutorial 2 already marks the netting set's CVA; tutorial 26's limit use is the
  explorer's, not the stored `USED`.
- **Version history and plans** in customer text (2.1, 2.2, "wave W3"): found in 10, 12, 13, 16 and 17 and removed.
- **Dates**: the last commit of each tutorial and of its `img/tNN/` folder (`git log -1`). Many text dates of 2026-10-31 are a docs-wide
  pass, not a re-run; the picture dates are the better guide.
- The docs gate (the docs, help, tutorial, picture and knowledge tests, `under_construction.py --check`, `settings_catalogue.py --check`).
- A place is written `NN:line`: tutorial NN's file (`docs/tutorials/NN-*.md`) at that line **before** this change.

## 2. What was decided

| Change | What |
|---|---|
| Merged | **22 into 6**: *Design a screen: in the workbench and on a live page*, part A the Build workbench (steps 1-8), part B the Sutra designer (steps 9-15). Every link to 22 now points at part B; `/help/docs-tutorials-22-build-a-screen-with-the-designer` redirects there (help.yaml `moved:`). |
| Retired (part) | Tutorial 17's *Upgrading to 2.2: BI items become models*: its picture, its sample `data/world-economics-21.dataset.yaml` and its shot. The migration itself stays in BI_GUIDE section 11 and CONFIGURATION. |
| Corrected | 1 (Engines menu; skip the install with a running server), 10, 12, 13, 16, 17 (version history and plans), 26 (a typo). |
| Kept | every other tutorial; reasons below. No other tutorial is obsolete: the ones that will change (BI, Build, compute) change with releases still to come, and are noted, not rewritten for the future. |
| Reorganised | The index in six tracks and a trader path. Numbers are kept as names (not renumbered): they key the picture folders (`img/tNN`), the picture scripts (`tools/docs/shots/t<NN>.py` files, `--guide tNN`), the help slugs and some hundred references in the guides; renumbering would break old links for nothing a track list does not already give. |

## 3. The tracks

| Track | Tutorials, in reading order |
|---|---|
| Start | 1 Your first 15 minutes, 2 Look up anything, 3 Query by form, 21 An entity over time, 8 Share and collaborate, 9 Reports |
| Risk engines | 25 VaR from the bank to a trade, 43 Parametric VaR beside historical: the fat-tail gap, 38 The MR cube: desks by risk class, with the right totals, 26 Exposure from the bank to a trade, 30 FRTB-SA from the hand-built cases to a desk, 32 SIMM from the hand-built examples to a netting set |
| BI and analysis | 10 Your first BI report, 11 BI that runs itself, 12 Live BI and boards, 13 Govern BI, 18 Several datasets in one report, 20 Statements and field parameters, 14 Python and Analyse, 15 AI in Drishti, 33 The pivot engine: trades by product, legs by desk, then VaR by desk, 37 Custom functions, "why did it move" and what-if, 27 Groupings and analytics, computed, 28 Funds and ETFs |
| Data in and out | 4 Connect your data, 7 Load data daily, 36 Load files automatically with the data inbox, 34 One file per lake, 29 The risk lake and the official results on Iceberg, 31 News held in memory only, 35 Data out: from a token to a pandas DataFrame of a desk's VaR scenario P&L |
| Build | 5 Build a pack from your data, 6 Design a screen: in the workbench and on a live page, 23 Computed panels: a reactive connector, 24 Correct a trade and approve it: write-back, 16 Embed Drishti |
| Operate | 17 Run it in production, 19 A cluster of three (Drishti*net) |
| Trader: your first 10 minutes | a path, not a tutorial: 1 (steps 3-4), 2 (steps 3-6), 21 (steps 1-3), 25 (steps 2-4 and 11), 33 (steps 1-2); see section 6 |

## 4. The inventory

| # | Title | Track | Verdict | Reason |
|---|---|---|---|---|
| 1 | Your first 15 minutes | Start | REWRITE-STEPS | the entry point; kept, with the install made skippable; three outputs and one picture to re-run. Splitting install (operators) from the tour (everyone) is 2.9.11.h's four role doors |
| 2 | Look up anything | Start | KEEP | current (text and pictures 2026-11-04); the core of the Start track |
| 3 | Query by form | Start | KEEP | the form is its own way in; distinct from the command line; text 2026-10-17, pictures 2026-11-01 |
| 4 | Connect your data | Data in and out | KEEP | the only tutorial on writing a connector |
| 5 | Build a pack from your data | Build | KEEP | the only pack-building tutorial; current with the load rules (the wizard's business-date field is the pack's, not the load's) |
| 6 | Design a screen: in the workbench and on a live page | Build | MERGED (22 into 6) | one tutorial for one job (design a screen), two parts; 2.9.11.e's one Build home will change both parts' entry points: rewrite then |
| 7 | Load data daily | Data in and out | KEEP | the other way data arrives; cross-linked with 36 |
| 8 | Share and collaborate | Start | KEEP | short (5 steps) but complete; the only collaboration tutorial |
| 9 | Reports | Start | KEEP | the scheduled-search side; renaming 'Reports' is a product decision (2.9.11.e) |
| 10 | Your first BI report | BI and analysis | KEEP (rewrite after 2.9.11.d) | teaches datasets, models and DEL, which 2.9.11.d replaces (virtual tables, DEL in Rachana): noted, not rewritten for the future |
| 11 | BI that runs itself | BI and analysis | KEEP (rewrite after 2.9.11.d) | BI internals change in 2.9.11.d |
| 12 | Live BI and boards | BI and analysis | KEEP (rewrite after 2.9.11.d) | the live samples are version 1 datasets: their move to virtual tables is 2.9.11.d's |
| 13 | Govern BI | BI and analysis | KEEP (rewrite after 2.9.11.d) | governance stays; the objects it governs change in 2.9.11.d; 2.9.11.e's one Approvals queue will change the review steps |
| 14 | Python and Analyse | BI and analysis | KEEP (rewrite after 2.9.11.d) | 70 minutes and 13 steps, the longest BI tutorial; 2.9.11.d's one Python runtime and Pivot → Report path replace much of it: split or rewrite then |
| 15 | AI in Drishti | BI and analysis | KEEP | the only AI tutorial |
| 16 | Embed Drishti | Build | KEEP | the only embedding tutorial |
| 17 | Run it in production | Operate | REWRITE-STEPS | the operator's checklist stays; the version-specific migration goes; outputs to re-run |
| 18 | Several datasets in one report | BI and analysis | KEEP (rewrite after 2.9.11.d) | models become virtual tables in 2.9.11.d |
| 19 | A cluster of three (Drishti*net) | Operate | KEEP | the only cluster tutorial |
| 20 | Statements and field parameters | BI and analysis | KEEP (rewrite after 2.9.11.d) | statements stay; their model side changes in 2.9.11.d |
| 21 | An entity over time | Start | KEEP | the deep version of a past date; in the trader path |
| 22 | Build a screen with the designer | (merged into 6) | MERGED INTO 6 | one job, one tutorial: now tutorial 6, part B (steps 9-15); /help/docs-tutorials-22-build-a-screen-with-the-designer redirects there |
| 23 | Computed panels: a reactive connector | Build | KEEP (rewrite after 2.9.11.d/e) | 2.9.11.d's one Python runtime and 2.9.11.e's remaining Python compute in Java change the engine side |
| 24 | Correct a trade and approve it: write-back | Build | KEEP | write-back is its own job; 2.9.11.e's one Approvals queue will move Review → Changes |
| 25 | VaR from the bank to a trade | Risk engines | KEEP | the VaR tutorial (the two-docs-per-measure rule); at 993 lines and 18 steps it is the first candidate for 2.9.11.h's split into a tutorial and a guide's worked examples |
| 26 | Exposure from the bank to a trade | Risk engines | KEEP | the exposure tutorial (two docs per measure) |
| 27 | Groupings and analytics, computed | BI and analysis | KEEP | its guide's pair |
| 28 | Funds and ETFs | BI and analysis | KEEP | its guide's pair |
| 29 | The risk lake and the official results on Iceberg | Data in and out | KEEP | the only Iceberg tutorial |
| 30 | FRTB-SA from the hand-built cases to a desk | Risk engines | KEEP | its guide's pair |
| 31 | News held in memory only | Data in and out | KEEP | its guide's pair |
| 32 | SIMM from the hand-built examples to a netting set | Risk engines | KEEP | its guide's pair |
| 33 | The pivot engine: trades by product, legs by desk, then VaR by desk | BI and analysis | KEEP | the pivot basics; in the trader path |
| 34 | One file per lake | Data in and out | KEEP | its guide's pair; current |
| 35 | Data out: from a token to a pandas DataFrame of a desk's VaR scenario P&L | Data in and out | KEEP | its guide's pair; current |
| 36 | Load files automatically with the data inbox | Data in and out | KEEP | its guide's pair; current |
| 37 | Custom functions, "why did it move" and what-if | BI and analysis | KEEP | the pivot engine's extension; current |
| 38 | The MR cube: desks by risk class, with the right totals | Risk engines | KEEP | its guide's pair; current |
| 43 | Parametric VaR beside historical: the fat-tail gap | Risk engines | KEEP | freshly run; folding it into 25 belongs with 25's split in 2.9.11.h |

### 4.1 Each tutorial in detail

#### 1. Your first 15 minutes

- **Teaches**: install, the demo lakes, start with sign-in, the home page, open a trade, search, the form, where BI is, a token.
- **Size**: 9 steps, 416 lines, 12 pictures. **Last produced**: text 2026-11-04, pictures (`img/t01`) 2026-11-01.
- **Commands**: `bi catalogue`, `doctor`, `lake demo`, `server health`, `view get`.
- **Command words**: `TRD`.
- **API calls**: 1 distinct, e.g. `/api/v1/search`.
- **Stale or changed**: 01:171 the top bar listed Views, BI, Build, Admin, Help without **Engines** (menus.yaml has it since 2.9.11.a): text fixed, picture img/t01/02-home.jpg (2026-11-01) to be re-run. 01:64-75 the `lake demo` summary predates the test data's conversion to dated files (2026-11-04): marked to be re-run. 01:99 `doctor` shows `drishti-server-2.9.11.b-exec.jar (version 2.5.0)` and 01:366 `version 2.9.2`: the jar name was edited into an older capture by the 2.9.11.b release commit (97d8b59ac): marked to be re-run. Opens with git clone and a build (UX review): a note now lets a reader with a running server start at step 3.
- **Overlap**: step 7 is a teaser of 3; step 8 of 10.
- **Verdict**: REWRITE-STEPS: the entry point; kept, with the install made skippable; three outputs and one picture to re-run. Splitting install (operators) from the tour (everyone) is 2.9.11.h's four role doors.

#### 2. Look up anything

- **Teaches**: command words, type-ahead, pick lists, links, a past date and Live, Compare, public data.
- **Size**: 7 steps, 348 lines, 12 pictures. **Last produced**: text 2026-11-04, pictures (`img/t02`) 2026-11-04.
- **Commands**: `view get`.
- **Command words**: `JUR`, `NSET`, `TRD`.
- **API calls**: 5 distinct, e.g. `/api/v1/business-date`, `/api/v1/command`, `/api/v1/command/suggest`, `/api/v1/history/trade/{id}/diff`.
- **Stale or changed**: none found (CVA marked under construction on 11-04).
- **Overlap**: step 5 is the short form of 21.
- **Verdict**: KEEP: current (text and pictures 2026-11-04); the core of the Start track.

#### 3. Query by form

- **Teaches**: query by form: criteria, regular expressions, amounts, dates, checkbox dropdowns, a saved search and a monitor.
- **Size**: 5 steps, 204 lines, 9 pictures. **Last produced**: text 2026-10-17, pictures (`img/t03`) 2026-11-01.
- **Command words**: `TRD`.
- **API calls**: 3 distinct, e.g. `/api/v1/forms/trading`, `/api/v1/forms/trading/trade/compile`, `/api/v1/search`.
- **Stale or changed**: none found.
- **Overlap**: 01 step 7 (a teaser); USER_GUIDE covers the form twice (a guide issue, not this tutorial's).
- **Verdict**: KEEP: the form is its own way in; distinct from the command line; text 2026-10-17, pictures 2026-11-01.

#### 4. Connect your data

- **Teaches**: a connector file, test it, TLS, a proxy, retries, the pool; Admin → Connectors and `drishti.py connector`.
- **Size**: 7 steps, 329 lines, 9 pictures. **Last produced**: text 2026-10-26, pictures (`img/t04`) 2026-11-01.
- **Commands**: `connector apply`, `connector delete`, `connector disable`, `connector get`, `connector history`, `connector list`, `connector plugins`, `connector test`.
- **Menus and pages**: Admin → Connectors, Admin → Health.
- **Stale or changed**: none found.
- **Overlap**: 05 continues it.
- **Verdict**: KEEP: the only tutorial on writing a connector.

#### 5. Build a pack from your data

- **Teaches**: a pack from a schema and a folder (Build → New pack), deploy and roll back, `pack make`.
- **Size**: 7 steps, 346 lines, 11 pictures. **Last produced**: text 2026-11-04, pictures (`img/t05`) 2026-11-01.
- **Commands**: `connector list`, `pack make`, `pack verify`, `server packs`.
- **Command words**: `DQ`.
- **Menus and pages**: Admin → Health, Admin → Packs, Build → New.
- **Stale or changed**: 05:264/290 `pack verify` output says `this is 2.6.7` (a capture of 2.6.7): cosmetic, to be re-run with the next picture run.
- **Overlap**: 04 before it, 06 after it.
- **Verdict**: KEEP: the only pack-building tutorial; current with the load rules (the wizard's business-date field is the pack's, not the load's).

#### 6. Design a screen: in the workbench and on a live page

- **Teaches**: Part A, the Build workbench: a screen from sample documents (tabs, slider, column filters, About, map, `sutra lint`/`preview`); Part B, the Sutra designer on a live page (Customize, a linked record, refresh, edit beside and in a drawer, your page, Describe, Outline and Grid).
- **Size**: 15 steps, 583 lines, 25 pictures. **Last produced**: text 2026-10-31, pictures (`img/t06`) 2026-11-01.
- **Commands**: `design check`, `design create`, `design get`, `design list`, `design propose`, `lake demo`, `sutra lint`, `sutra preview`, `view explain`, `view get`.
- **Command words**: `JUR`, `PTRD`, `TRD`.
- **Menus and pages**: Build → My, Build → New, My → API.
- **Stale or changed**: 06:245-253 'Or with the designer' pointed to tutorial 22: replaced by part B. 06:28 `DRISHTI_STUDIO_SAVE` checked: still the server's `studio-save` (application.yaml:983), not the removed `studio` feature switch.
- **Overlap**: was 06 + 22 (the UX review's overlap).
- **Verdict**: MERGED (22 into 6): one tutorial for one job (design a screen), two parts; 2.9.11.e's one Build home will change both parts' entry points: rewrite then.

#### 7. Load data daily

- **Teaches**: the data-landed signal (`drishti.py data landed`), expectations, late data, an alert on a missing load, `data get`.
- **Size**: 7 steps, 292 lines, 8 pictures. **Last produced**: text 2026-11-01, pictures (`img/t07`) 2026-11-01.
- **Commands**: `data get`, `data landed`, `data loads`.
- **Command words**: `DATA`.
- **Menus and pages**: Admin → Audit, Admin → Health.
- **API calls**: 4 distinct, e.g. `/api/v1/admin/loads/trading/config`, `/api/v1/me/measure-alerts/preview`, `/api/v1/me/measure-alerts/{name}`, `/api/v1/packs/trading/loads`.
- **Stale or changed**: none found (`--date` here is `data landed`'s own option, still valid).
- **Overlap**: 36 (the inbox loads files itself; 7 is for an ETL that loads).
- **Verdict**: KEEP: the other way data arrives; cross-linked with 36.

#### 8. Share and collaborate

- **Teaches**: share a filtered view, discuss, mention, the inbox.
- **Size**: 5 steps, 137 lines, 12 pictures. **Last produced**: text 2026-10-17, pictures (`img/t08`) 2026-11-01.
- **Command words**: `TRD`.
- **Menus and pages**: Admin → Collaboration, Admin → Users.
- **API calls**: 1 distinct, e.g. `/api/v1/admin/users`.
- **Stale or changed**: none found (the removed notes API is not used: sharing is threads).
- **Overlap**: 09 (both about telling people).
- **Verdict**: KEEP: short (5 steps) but complete; the only collaboration tutorial.

#### 9. Reports

- **Teaches**: schedule a search, subscribe a colleague, history, the Reports menu.
- **Size**: 7 steps, 228 lines, 11 pictures. **Last produced**: text 2026-10-31, pictures (`img/t09`) 2026-11-01.
- **Commands**: `bi catalogue`.
- **Command words**: `TRD`.
- **Menus and pages**: Admin → Collaboration, Views → Reports.
- **API calls**: 6 distinct, e.g. `/api/v1/me/reports`, `/api/v1/me/reports/Big`, `/api/v1/me/reports/{name}`, `/api/v1/me/subscriptions`.
- **Stale or changed**: none found.
- **Overlap**: 11's BI subscriptions; 'Reports' names four things (UX review).
- **Verdict**: KEEP: the scheduled-search side; renaming 'Reports' is a product decision (2.9.11.e).

#### 10. Your first BI report

- **Teaches**: BI Studio: a report on a model (cards, a drillable map, a bar, a slicer), cross-filter, publish, filters, the Counterparty risk sample, relative dates, the entity view, a tablet layout.
- **Size**: 14 steps, 841 lines, 43 pictures. **Last produced**: text 2026-10-31, pictures (`img/t10`) 2026-11-01.
- **Commands**: `bi catalogue`, `bi item`, `bi migrate`, `bi model`, `bi mq`, `bi workspace`, `lake demo`.
- **Command words**: `BI`.
- **Menus and pages**: BI → Models.
- **API calls**: 6 distinct, e.g. `/api/v1/bi/models/validate`, `/api/v1/bi/mq`, `/api/v1/bi/pack-datasets`, `/api/v1/bi/reports/validate`.
- **Stale or changed**: 10:839 'a 2.1 composed or derived dataset: removed in 2.2' (version history): reworded to what is.
- **Overlap**: 11-14, 18, 20 build on it.
- **Verdict**: KEEP (rewrite after 2.9.11.d): teaches datasets, models and DEL, which 2.9.11.d replaces (virtual tables, DEL in Rachana): noted, not rewritten for the future.

#### 11. BI that runs itself

- **Teaches**: a self-refreshing dataset, subscriptions and mail, an alert on a number, exports, expected values, narrative, webhook.
- **Size**: 8 steps, 579 lines, 21 pictures. **Last produced**: text 2026-10-31, pictures (`img/t11`) 2026-11-01.
- **Commands**: `bi catalogue`, `bi dataset`, `bi item`, `bi mq`.
- **API calls**: 8 distinct, e.g. `/api/v1/bi/datasets/`, `/api/v1/distribution/status`, `/api/v1/export/`, `/api/v1/export/csv`.
- **Stale or changed**: none found.
- **Overlap**: 09.
- **Verdict**: KEEP (rewrite after 2.9.11.d): BI internals change in 2.9.11.d.

#### 12. Live BI and boards

- **Teaches**: live tables and a live report, pause and resume, the Desk wall kiosk, a board, the history of a live table, a Python wall.
- **Size**: 9 steps, 538 lines, 16 pictures. **Last produced**: text 2026-10-31, pictures (`img/t12`) 2026-11-01.
- **Commands**: `bi catalogue`, `bi item`, `bi migrate`, `bi model`, `bi mq`, `view get`.
- **Command words**: `BI`, `CRV`.
- **Menus and pages**: Admin → Connectors.
- **API calls**: 4 distinct, e.g. `/api/v1/bi/boards/validate`, `/api/v1/bi/datasets/file:dataset:live-trading/layers`, `/api/v1/bi/reports/file:report:live-trading-desk/query`, `/api/v1/bi/reports/validate`.
- **Stale or changed**: 12:30-32 'Live and the model layer (2.2) ... move to the model layer in wave W3' and 12:416 'until wave W3 moves the live samples' (plans and version history): reworded to what is.
- **Overlap**: 16 embeds its live report.
- **Verdict**: KEEP (rewrite after 2.9.11.d): the live samples are version 1 datasets: their move to virtual tables is 2.9.11.d's.

#### 13. Govern BI

- **Teaches**: history and diff, four-eyes review, certify, lineage, a refused breaking change, an environments bundle.
- **Size**: 12 steps, 461 lines, 22 pictures. **Last produced**: text 2026-10-31, pictures (`img/t13`) 2026-11-01.
- **Commands**: `bi item`, `bi model`.
- **Menus and pages**: Admin → BI, Admin → Connectors, Admin → Roles, Admin → Users, My → API.
- **API calls**: 12 distinct, e.g. `/api/v1/admin/role-definitions/bi-writer`, `/api/v1/admin/users`, `/api/v1/bi/bundle`, `/api/v1/bi/bundle/import`.
- **Stale or changed**: 13:22 'the model layer (2.2)': version dropped.
- **Overlap**: none.
- **Verdict**: KEEP (rewrite after 2.9.11.d): governance stays; the objects it governs change in 2.9.11.d; 2.9.11.e's one Approvals queue will change the review steps.

#### 14. Python and Analyse

- **Teaches**: a Python measure and visual, the Notebook (SQL on the server, a Python cell for the compute pool, notebook, promote), slicing in the Pivot, Vega-Lite, item tests, Calc, the Transform editor.
- **Size**: 13 steps, 720 lines, 29 pictures. **Last produced**: text 2026-10-31, pictures (`img/t14`) 2026-11-01.
- **Commands**: `bi catalogue`, `bi item`, `bi model`, `bi mq`, `bi sql`, `bi transform`.
- **Command words**: `ORDER`.
- **Menus and pages**: BI → Python, BI → Transforms.
- **API calls**: 1 distinct, e.g. `/api/v1/me/measure-alerts/Desk`.
- **Stale or changed**: none found.
- **Overlap**: 23 (Python compute), 37 (functions).
- **Verdict**: KEEP (rewrite after 2.9.11.d): 70 minutes and 13 steps, the longest BI tutorial; 2.9.11.d's one Python runtime and Pivot → Report path replace much of it: split or rewrite then.

#### 15. AI in Drishti

- **Teaches**: the mock AI provider, the AI policy, report from words, ask the data, explain this number, designer suggestions, the dataset helper, Test connection, provider types.
- **Size**: 8 steps, 422 lines, 11 pictures. **Last produced**: text 2026-10-31, pictures (`img/t15`) 2026-11-01.
- **Commands**: `bi mq`.
- **Menus and pages**: Admin → AI.
- **API calls**: 6 distinct, e.g. `/api/v1/bi/ai/ask`, `/api/v1/bi/ai/dataset-helper`, `/api/v1/bi/ai/draft`, `/api/v1/bi/ai/explain`.
- **Stale or changed**: checked against the removal of LegacyAsk and Ask's endpoint/api-key settings: the tutorial already uses provider files (`data/ai/*.yaml`) and BI's AI hooks; nothing to change.
- **Overlap**: none.
- **Verdict**: KEEP: the only AI tutorial.

#### 16. Embed Drishti

- **Teaches**: register a host app, a view and a report in its page under a strict CSP, tokens, limits, elements, React/Vue/Angular samples.
- **Size**: 10 steps, 302 lines, 12 pictures. **Last produced**: text 2026-10-31, pictures (`img/t16`) 2026-11-01.
- **Commands**: `bi mq`.
- **Command words**: `TRD`, `TVAL`.
- **Menus and pages**: Admin → Embedding, Admin → Users.
- **API calls**: 2 distinct, e.g. `/api/v1/admin/embed/apps`, `/api/v1/embed/token`.
- **Stale or changed**: 16:163 'in 2.2 it stays on its 2.1 dataset ...' and 16:180 '(2.2)': version history reworded.
- **Overlap**: 12 (its live report).
- **Verdict**: KEEP: the only embedding tutorial.

#### 17. Run it in production

- **Teaches**: security on, your administrator, roles and BI powers, tokens, backups, heap and pools, health, the type-ahead index, upgrade and rollback, Admin → Artifacts.
- **Size**: 9 steps, 370 lines, 15 pictures. **Last produced**: text 2026-11-04, pictures (`img/t17`) 2026-11-01.
- **Commands**: `doctor`, `lake demo`, `server caches`.
- **Menus and pages**: Admin → Artifacts, Admin → Audit, Admin → BI, Admin → Caches, Admin → Connectors, Admin → Health, Admin → Roles, Admin → Tokens, Admin → Users, My → API.
- **API calls**: 3 distinct, e.g. `/api/v1/admin/health`, `/api/v1/admin/status`, `/api/v1/sources`.
- **Stale or changed**: 17:322-380 'Upgrading to 2.2: BI items become models' (a one-off 2.1 → 2.2 migration: version history): retired with img/t17/12-migrated.jpg, data/world-economics-21.dataset.yaml and the `migrated` shot of tools/docs/shots/t17.py; the migration stays documented in BI_GUIDE section 11 and CONFIGURATION (`drishti.bi.migrate`). 17:188 '(2.2: ...)' dropped. 17:281 `doctor` and the health outputs mix `2.9.11.b-exec.jar` with versions 2.5.0 and 2.9.2: marked to be re-run.
- **Overlap**: 19 (cluster operations).
- **Verdict**: REWRITE-STEPS: the operator's checklist stays; the version-specific migration goes; outputs to re-run.

#### 18. Several datasets in one report

- **Teaches**: one model with two facts in the model designer, why rows are not joined across facts, a measure over both, the Python Transform, two readers, the join in Analyse, Power BI comparison.
- **Size**: 9 steps, 524 lines, 19 pictures. **Last produced**: text 2026-10-31, pictures (`img/t18`) 2026-11-01.
- **Commands**: `bi catalogue`, `bi expr`, `bi model`, `bi mq`, `bi sql`, `lake demo`.
- **Menus and pages**: Admin → Users.
- **API calls**: 2 distinct, e.g. `/api/v1/bi/items/{id}/publish`, `/api/v1/bi/workspaces/exposure-desk/items`.
- **Stale or changed**: none found.
- **Overlap**: 10.
- **Verdict**: KEEP (rewrite after 2.9.11.d): models become virtual tables in 2.9.11.d.

#### 19. A cluster of three (Drishti*net)

- **Teaches**: config/net with CLI and UI, three servers from one seed, routing and Explain, nodes per role, a multi-server console, drain, stop one and carry on.
- **Size**: 8 steps, 378 lines, 10 pictures. **Last produced**: text 2026-10-31, pictures (`img/t19`) 2026-10-27.
- **Commands**: `net add-peer`, `net check`, `net list`, `net members`, `net set`.
- **Command words**: `TRD`.
- **Menus and pages**: Admin → Audit, Admin → Health, Admin → Net, Admin → Roles, Admin → Users.
- **API calls**: 5 distinct, e.g. `/api/v1/admin/users`, `/api/v1/admin/users/rita`, `/api/v1/net/drain`, `/api/v1/net/explain`.
- **Stale or changed**: 19:185-187, 325-327 `net members` shows version 2.4.0 (a 2.4.0 capture): cosmetic, to be re-run.
- **Overlap**: 17.
- **Verdict**: KEEP: the only cluster tutorial.

#### 20. Statements and field parameters

- **Teaches**: field parameters, a switchable chart, a statement page (paper, header, footer, breaks), the Paper tab, `bi statement` PDF/Excel, bursting.
- **Size**: 6 steps, 247 lines, 6 pictures. **Last produced**: text 2026-10-31, pictures (`img/t20`) 2026-10-20.
- **Commands**: `bi item`, `bi mq`, `bi statement`.
- **Menus and pages**: My → API.
- **API calls**: 3 distinct, e.g. `/api/v1/bi/reports/statement/file`, `/api/v1/me/subscriptions`, `/api/v1/me/subscriptions/{name}`.
- **Stale or changed**: pictures captured separately on 2026-10-20 (no picture script): cannot be remade by `screenshots.py`.
- **Overlap**: 10.
- **Verdict**: KEEP (rewrite after 2.9.11.d): statements stay; their model side changes in 2.9.11.d.

#### 21. An entity over time

- **Teaches**: an entity over time: ranges, the history table, changes only and DIFF, a day as it was, an equity's chart, a pack's `history:` block, export, limits.
- **Size**: 8 steps, 312 lines, 6 pictures. **Last produced**: text 2026-10-31, pictures (`img/t21`) 2026-10-20.
- **Commands**: `history`, `history equity`, `history trade`, `lake demo`, `sutra lint`, `view get`.
- **Command words**: `DIFF`, `EQ`, `FROM`, `MYTRD`, `TO`, `TRD`.
- **Menus and pages**: Admin → Packs, My → API.
- **API calls**: 1 distinct, e.g. `/api/v1/history/trade/{id}`.
- **Stale or changed**: pictures captured separately on 2026-10-20 (no picture script).
- **Overlap**: 02 step 5.
- **Verdict**: KEEP: the deep version of a past date; in the trader path.

#### 22. Build a screen with the designer

- **Teaches**: the Sutra designer on a live page.
- **Last produced**: text 2026-10-31; pictures were the Rachana reference's (`docs/guides/img/designer`).
- **Stale or changed**: its CLI steps show commands but no captured output (never run for real); its pictures are the Rachana reference's (docs/guides/img/designer).
- **Overlap**: 06 (the UX review).
- **Verdict**: MERGED INTO 6: one job, one tutorial: now tutorial 6, part B (steps 9-15); /help/docs-tutorials-22-build-a-screen-with-the-designer redirects there.

#### 23. Computed panels: a reactive connector

- **Teaches**: compute workers and the compute-demo pack, a Python engine and its Java twin, panels filled in place, typed parameters, `compute run`, an event that recomputes.
- **Size**: 7 steps, 242 lines, 6 pictures. **Last produced**: text 2026-10-31, pictures (`img/t23`) 2026-10-25.
- **Commands**: `compute cancel`, `compute event`, `compute reactive`, `compute run`.
- **Command words**: `PBOOK`, `PSUMJ`.
- **Menus and pages**: Admin → Compute, Admin → Connectors, My → API.
- **API calls**: 1 distinct, e.g. `/api/v1/compute/path-sum-java/path-sum-java`.
- **Stale or changed**: none found (the compute-demo pack and `drishti.py compute` exist).
- **Overlap**: 14.
- **Verdict**: KEEP (rewrite after 2.9.11.d/e): 2.9.11.d's one Python runtime and 2.9.11.e's remaining Python compute in Java change the engine side.

#### 24. Correct a trade and approve it: write-back

- **Teaches**: editing on, a form that saves, a correction with its review sheet, four eyes in Review → Changes, a stale change conflicted, `drishti.py change`.
- **Size**: 7 steps, 174 lines, 5 pictures. **Last produced**: text 2026-10-31, pictures (`img/t24`) 2026-10-26.
- **Commands**: `change approve`, `change history`, `change propose`, `change show`, `edits status`.
- **Command words**: `PTRD`.
- **Menus and pages**: Admin → Edits, Build → Govern, My → API, Review → Changes.
- **Stale or changed**: none found.
- **Overlap**: 06 part B step 12 (a form that keeps drafts; 24 writes back).
- **Verdict**: KEEP: write-back is its own job; 2.9.11.e's one Approvals queue will move Review → Changes.

#### 25. VaR from the bank to a trade

- **Teaches**: VaR: build the data, the bank to a trade, the P&L path, contributions, scopes, derived positions, baskets, captures, history and backtest, the hot file, VRES, what-if, entitlements, intraday, limit letters, 10-day, SMAT, regulatory backtest, the Playground.
- **Size**: 18 steps, 993 lines, 21 pictures. **Last produced**: text 2026-11-04, pictures (`img/t25`) 2026-11-04.
- **Commands**: `compute run`, `lake demo`, `var`, `var backtest`, `var basket`, `var baskets`, `var capture`, `var compare`, `var history`, `var intraday`, `var matrix`, `var official`, `var parts`, `var path`, `var playground`, `var reload`, `var report`, `var show`, `var stats`, `var status`, `var tree`, `var whatif`.
- **Command words**: `PLAY`, `SMAT`, `VAR`, `VRES`.
- **Menus and pages**: Admin → Connectors, My → API.
- **API calls**: 3 distinct, e.g. `/api/v1/var/matrix`, `/api/v1/var/scope`, `/api/v1/var/what-if`.
- **Stale or changed**: none found (text and pictures 2026-11-04).
- **Overlap**: 43 (parametric is a toggle of this explorer), 38 and 33 step 6 (VaR in a pivot).
- **Verdict**: KEEP: the VaR tutorial (the two-docs-per-measure rule); at 993 lines and 18 steps it is the first candidate for 2.9.11.h's split into a tutorial and a guide's worked examples.

#### 26. Exposure from the bank to a trade

- **Teaches**: exposure: the bank, a legal entity's board, a counterparty and its netting sets, collateral, a trade alone and in its set, capture and compare, pre-deal what-if (paths, addon), official results.
- **Size**: 8 steps, 559 lines, 9 pictures. **Last produced**: text 2026-10-31, pictures (`img/t26`) 2026-10-30.
- **Commands**: `exposure`, `exposure capture`, `exposure captures`, `exposure compare`, `exposure distribution`, `exposure official`, `exposure parts`, `exposure profile`, `exposure retention`, `exposure show`, `exposure status`, `exposure tree`, `exposure whatif`, `lake demo`.
- **Command words**: `EXP`.
- **Menus and pages**: Admin → Compute, My → API.
- **Stale or changed**: checked against the under-construction registry: the limit use shown is the explorer's PFE against the limit, not the stored `USED` (credit-limit-usage); the pre-deal what-if is built, the limit decision is not and is not taught. 26:168-169 typo 'stored stored' fixed.
- **Overlap**: none.
- **Verdict**: KEEP: the exposure tutorial (two docs per measure).

#### 27. Groupings and analytics, computed

- **Teaches**: computed groupings and analytics: LEIG, LEIC, SIC, ECG, CORR, EQB, USEQ ratios, PXH off and internal, the guard.
- **Size**: 10 steps, 531 lines, 10 pictures. **Last produced**: text 2026-10-31, pictures (`img/t27`) 2026-10-26.
- **Commands**: `compute run`, `contract show`, `lake demo`, `view get`.
- **Command words**: `CORR`, `ECG`, `EQ`, `EQB`, `FACT`, `IDX`, `LEIC`, `LEIG`, `PXH`, `SIC`, `USEQ`.
- **Menus and pages**: Admin → Connectors, My → API.
- **Stale or changed**: none found.
- **Overlap**: 28 (public-data packs).
- **Verdict**: KEEP: its guide's pair.

#### 28. Funds and ETFs

- **Teaches**: the funds pack: FUND fact sheet, look-through, against its index, HELD, TRACK, the SEC refresh.
- **Size**: 9 steps, 478 lines, 9 pictures. **Last produced**: text 2026-11-04, pictures (`img/t28`) 2026-10-27.
- **Commands**: `lake demo`, `view get`.
- **Command words**: `FUND`, `HELD`, `IDX`, `TRACK`, `USEQ`.
- **Menus and pages**: My → API.
- **API calls**: 4 distinct, e.g. `/api/v1/command/suggest`, `/api/v1/entities/fund/QQQ/raw`, `/api/v1/search`, `/api/v1/views/fund/VTI`.
- **Stale or changed**: none found.
- **Overlap**: 27.
- **Verdict**: KEEP: its guide's pair.

#### 29. The risk lake and the official results on Iceberg

- **Teaches**: the risk lake and the official results on Iceberg, equal to Delta to the cent, Admin → Lakes' Format, BI's reader, back to Delta.
- **Size**: 7 steps, 297 lines, 5 pictures. **Last produced**: text 2026-10-31, pictures (`img/t29`) 2026-11-04.
- **Commands**: `connector get`, `connector list`, `lake build`, `lake demo`, `lake maintain`, `var intraday`, `var official`, `var show`.
- **Command words**: `VAR`.
- **Menus and pages**: Admin → Connectors, Admin → Lakes.
- **Stale or changed**: none found (pictures 2026-11-04).
- **Overlap**: 34 (lake files).
- **Verdict**: KEEP: the only Iceberg tutorial.

#### 30. FRTB-SA from the hand-built cases to a desk

- **Teaches**: FRTB-SA: the hand-built cases, a bucket by hand, three scenarios, the bucket drill, PLAY FRTB switches and workbook, recipes, a desk's contributions.
- **Size**: 6 steps, 283 lines, 6 pictures. **Last produced**: text 2026-11-02, pictures (`img/t30`) 2026-11-02.
- **Commands**: `frtb contributions`, `frtb explain`, `frtb sa`, `lake demo`, `play frtb`, `sens show`.
- **Command words**: `FRSA`, `PLAY`, `VAR`.
- **Menus and pages**: Admin → Engines.
- **Stale or changed**: none found.
- **Overlap**: 32 (same playground shape).
- **Verdict**: KEEP: its guide's pair.

#### 31. News held in memory only

- **Teaches**: the in-memory lake: news on mem://, the store and tables, replenish jobs, DRS-5219, the cap, eviction, Health, back to disk.
- **Size**: 8 steps, 258 lines, 6 pictures. **Last produced**: text 2026-11-04, pictures (`img/t31`) 2026-11-02.
- **Commands**: `lake demo`, `lake memory`, `view get`.
- **Command words**: `CO`, `QUOTE`.
- **Menus and pages**: Admin → Connectors, Admin → Engines, Admin → Health, Admin → Lakes.
- **API calls**: 1 distinct, e.g. `/api/v1/me/reports/news-daily`.
- **Stale or changed**: none found.
- **Overlap**: 34.
- **Verdict**: KEEP: its guide's pair.

#### 32. SIMM from the hand-built examples to a netting set

- **Teaches**: SIMM: a parameter version (the toy file), the hand-built examples, a netting set both ways, PLAY SIMM, a recipe, the counterparty's call.
- **Size**: 7 steps, 299 lines, 6 pictures. **Last produced**: text 2026-11-02, pictures (`img/t32`) 2026-11-02.
- **Commands**: `lake demo`, `play simm`, `simm against`, `simm explain`, `simm list`, `simm show`, `simm versions`.
- **Command words**: `PLAY`, `SIMM`.
- **Stale or changed**: none found.
- **Overlap**: 30.
- **Verdict**: KEEP: its guide's pair.

#### 33. The pivot engine: trades by product, legs by desk, then VaR by desk

- **Teaches**: the pivot engine: lazy rows, expand by another field, top N and Others, provenance, document paths, VaR by desk, charts, save/share/Excel, Exact/≈, rollups, Live, comments.
- **Size**: 10 steps, 273 lines, 5 pictures. **Last produced**: text 2026-11-03, pictures (`img/t33`) 2026-11-03.
- **Commands**: `data load`, `lake demo`, `pivot comments`, `pivot export`, `pivot fields`, `pivot live`, `pivot run`, `pivot save`, `pivot views`, `var show`.
- **Command words**: `DATA`, `PIVOT`, `VAR`.
- **Menus and pages**: Admin → Data.
- **API calls**: 5 distinct, e.g. `/api/v1/pivot/live`, `/api/v1/pivot/query`, `/api/v1/pivot/rows`, `/api/v1/pivot/views`.
- **Stale or changed**: none found.
- **Overlap**: 37 (its extension), 38.
- **Verdict**: KEEP: the pivot basics; in the trader path.

#### 34. One file per lake

- **Teaches**: one lake file per lake: the demo lake's file, a connector naming it, a load and a refused --partition-by, append, `lake check`, migrating.
- **Size**: 6 steps, 310 lines, 6 pictures. **Last produced**: text 2026-11-04, pictures (`img/t34`) 2026-11-04.
- **Commands**: `lake build`, `lake check`, `lake demo`, `lake migrate-config`, `lake show`.
- **Command words**: `TRADE`.
- **Menus and pages**: Admin → Connectors, Admin → Lakes.
- **API calls**: 1 distinct, e.g. `/api/v1/admin/lakes/files`.
- **Stale or changed**: none (run for real 2026-11-04).
- **Overlap**: 29, 36.
- **Verdict**: KEEP: its guide's pair; current.

#### 35. Data out: from a token to a pandas DataFrame of a desk's VaR scenario P&L

- **Teaches**: Arrow Flight: on, a data:read token, a desk's scenario P&L in pandas, Java and JDBC, a live stream resumed, Admin → Data out with Stop.
- **Size**: 8 steps, 266 lines, 5 pictures. **Last produced**: text 2026-11-04, pictures (`img/t35`) 2026-11-04.
- **Commands**: `server engines`.
- **Menus and pages**: Admin → Data, Admin → Engines, My → API.
- **API calls**: 2 distinct, e.g. `/api/v1/me/tokens`, `/api/v1/var/intraday/streams/{name}/apply`.
- **Stale or changed**: none (run for real 2026-11-04).
- **Overlap**: none.
- **Verdict**: KEEP: its guide's pair; current.

#### 36. Load files automatically with the data inbox

- **Teaches**: the data inbox: on, a routing rule, a day of trades with .ready, ready.all, a failed file retried, a duplicate, outbox and ledger.
- **Size**: 8 steps, 404 lines, 6 pictures. **Last produced**: text 2026-11-04, pictures (`img/t36`) 2026-11-04.
- **Commands**: `dataload`, `dataload retry`, `dataload run`, `dataload status`, `lake demo`, `server engines`.
- **Menus and pages**: Admin → Data, Admin → Engines.
- **API calls**: 1 distinct, e.g. `/api/v1/admin/dataload/report`.
- **Stale or changed**: none (run for real 2026-11-04, by the load rules).
- **Overlap**: 07.
- **Verdict**: KEEP: its guide's pair; current.

#### 37. Custom functions, "why did it move" and what-if

- **Teaches**: custom functions (formula, playground and harness, SQL macro, approval), why it moved, what-if, Python on, BI's totals.
- **Size**: 9 steps, 271 lines, 5 pictures. **Last produced**: text 2026-11-04, pictures (`img/t37`) 2026-11-04.
- **Commands**: `pivot explain`, `pivot functions`, `pivot run`, `pivot whatif`.
- **Command words**: `PIVOT`.
- **Menus and pages**: Admin → Audit, Admin → Roles.
- **API calls**: 4 distinct, e.g. `/api/v1/pivot/explain`, `/api/v1/pivot/functions/test`, `/api/v1/pivot/functions/you/wret`, `/api/v1/pivot/whatif`.
- **Stale or changed**: none (run for real 2026-11-04).
- **Overlap**: 33.
- **Verdict**: KEEP: the pivot engine's extension; current.

#### 38. The MR cube: desks by risk class, with the right totals

- **Teaches**: the MR cube: the landing checked against VAR BANK, a cell's tail and why, the lens, re-pivot and drill, components and waterfall, DV01 ladder and what-if, save and export.
- **Size**: 7 steps, 191 lines, 8 pictures. **Last produced**: text 2026-11-04, pictures (`img/t38`) 2026-11-04.
- **Commands**: `pivot export`, `pivot fields`, `pivot mr`, `pivot run`, `pivot save`.
- **Command words**: `MCUBE`, `VAR`.
- **Menus and pages**: Admin → Engines.
- **API calls**: 3 distinct, e.g. `/api/v1/pivot/mr/detail`, `/api/v1/pivot/mr/what-if`, `/api/v1/pivot/query`.
- **Stale or changed**: none (run for real 2026-11-04).
- **Overlap**: 33 step 6, 43 step 6.
- **Verdict**: KEEP: its guide's pair; current.

#### 43. Parametric VaR beside historical: the fat-tail gap

- **Teaches**: parametric VaR as the explorer's toggle: the tiles, a desk's ? line by line, the curve and N, the tail, Euler components and correlations, pvar() in MCUBE, the Playground's Parametric tab.
- **Size**: 7 steps, 274 lines, 5 pictures. **Last produced**: text 2026-11-04, pictures (`img/t43`) 2026-11-04.
- **Commands**: `lake demo`, `pivot run`, `var parts`, `var playground`, `var show`.
- **Command words**: `MCUBE`, `PLAY`, `PVAR`, `VAR`.
- **Menus and pages**: My → API.
- **API calls**: 6 distinct, e.g. `/api/v1/pivot/query`, `/api/v1/var/playground`, `/api/v1/var/playground.xlsx`, `/api/v1/var/scope`.
- **Stale or changed**: none (rewritten to the toggle and run for real 2026-11-04; PLAY PVAR and views B and C gone).
- **Overlap**: 25.
- **Verdict**: KEEP: freshly run; folding it into 25 belongs with 25's split in 2.9.11.h.

## 5. Outputs and pictures to be re-run

Marked *(to be re-run)* in the text where the output is shown; none was invented.

| Tutorial | What | Why |
|---|---|---|
| 1, step 1 | the `lake demo` summary | predates the test data's conversion to dated files (2026-11-04) |
| 1, step 1 | the `doctor` output | the jar name `2.9.11.b-exec.jar` was edited into a capture whose versions read 2.5.0 (and 2.9.2 in step 9) |
| 1, step 4 | the picture `img/t01/02-home.jpg` | predates the Engines menu |
| 1, step 9 | the `doctor --server` output | as step 1 (not marked separately) |
| 5, steps 6-7 | `pack verify` outputs (`this is 2.6.7`) | a 2.6.7 capture (cosmetic) |
| 6, steps 9-15 (part B) | every command-line call (`view get`, `design list|get|check`, `/me/settings`, `/drafts`, `/me/pages`) | the commands were never run for the tutorial: they show no output; to be run and their output added |
| 17, steps 6-8 | `doctor`, `admin/health` and the version outputs | as tutorial 1 (2.5.0 and 2.9.2 beside the 2.9.11.b jar) |
| 19, steps 3 and 7 | `net members` (version 2.4.0) | a 2.4.0 capture (cosmetic) |
| 20, 21 | every picture | captured by hand on 2026-10-20; no picture script, so `screenshots.py` cannot remake them: write `t20.py` and `t21.py` |
| 10-16, 18, 20 | everything | the general run the product owner asked for: these have not been run since 2026-11-01 (pictures) |

## 6. Planned, not written

| Entry | What | When |
|---|---|---|
| **Trader: your first 10 minutes** | A tutorial of its own for a trader, on one set of dates: sign in, the desk's trades, a trade's history, the desk's VaR to a trade, a what-if, a pivot. It cannot be built honestly now: the steps it would reuse were captured on different lakes and dates (tutorial 1 on the seven-day lakes of 2026-10-16, 25 on the VaR lake of 2026-09-30, 33 on the demo lake), so the figures would contradict each other. Until then the index has a path through those tutorials' steps. | with 2.9.11.e's ticking trader home, run on one lake |
| **The BI track rewritten** | 10-14, 18 and 20 on virtual tables and Rachana expressions; 14 split (Python vs Analyse) | after 2.9.11.d |
| **The Build track rewritten** | 6 (both parts), 24's review step, 23's engines, on the one Build home, one Approvals queue and one Python runtime | after 2.9.11.e |
| **Role doors** | install vs first tour (tutorial 1 split), the four role doors | 2.9.11.h |
| **25 split** | the VaR tutorial (993 lines, 18 steps) into the tutorial and the guide's worked examples; 43 folded in | 2.9.11.h |

