<!--
  Project Drishti · Any data. Any domain. One grammar.
  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
  PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository.
-->

# Shared screens: one Build home, one Approvals queue, one Explain, the trader home, the controls screens and the editors

Four screens that replace several today, chosen by the product owner from mock-ups, followed by the controls screens (section 6) and
the editors (section 7). Each folds duplicates into one and deletes the old in the same release (*lean & streamlined*). The mock-ups are in
[mockups/shared-screens/](mockups/shared-screens/) and open in a browser; the editors they lead to are in [EDITORS.md](#7-the-editors-one-pattern-for-every-object-people-author).

## Contents

1. [One Build home](#1-one-build-home)
2. [One Approvals queue](#2-one-approvals-queue)
3. [One Explain for every number](#3-one-explain-for-every-number)
4. [The trader home](#4-the-trader-home)
5. [Build checklist](#5-build-checklist)
6. [Controls screens](#6-controls-screens-run-status-and-sign-off-the-breach-queue-number-honesty)
7. [The editors](#7-the-editors-one-pattern-for-every-object-people-author)

## 1. One Build home

Mock-up: [e1-build-home.html](mockups/shared-screens/e1-build-home.html), option **A** as the home, **B** as the first-visit view, **C**'s
lineage columns in A's lists.

- **One rail** grouped **Data** (connectors, lakes, tables and models, Python tables) · **Screens** (Sutras, packs, the UI kit) ·
  **Analysis** (reports and boards, pivot views, functions) · **Work** (my drafts, waiting for review, recently changed), each with a count.
- The middle lists the chosen kind: name, kind, status with versions, **feeds / fed by** (lineage), last change and by whom; opening an
  item goes to its editor (EDITORS.md's three-pane shell).
- **First visit** (or by choice): four task tiles, *Bring data in · Shape it · Show it · Package it*, with "continue where you left off"
  and "waiting for you".
- One search across everything a person may build; + New opens the guided steps; what a person may build follows their role.
- Studio, BI Studio, the workbench and the pack tools redirect here and their separate homes are deleted.

## 2. One Approvals queue

Mock-up: [e2-approvals.html](mockups/shared-screens/e2-approvals.html), option **A**, with **B**'s *due soon* items pinned at the top.

- **One inbox** of everything waiting for me, of any kind: Sutras, tables and models, reports, trade corrections, pivot functions, VaR and
  exposure sign-offs, users and roles, API keys, captures. Filters: Data · Screens · Risk · Access; *Mine sent* and *Done*.
- **Due soon** (a sign-off with a deadline) pinned on top.
- The chosen item shows **what changes** (a diff, with the maker's reason), **its effect if approved** (e.g. a scope's VaR and limit use
  before and after, the reports that refresh) and **the rules** that allow me to decide.
- Approve; reject with a reason; or **ask a question** (a thread on the item). The maker can never approve their own. Everything audited;
  a count on the top bar; the per-kind queues redirect here and are deleted.
- A team board by stage (proposed, questions, approved, rejected) only if asked for later.

## 3. One Explain for every number

Mock-up: [e3-explain.html](mockups/shared-screens/e3-explain.html), option **A**, with **B** as its preview and **C** through a link.

- Every computed number on every screen (VaR, ES, components, parametric VaR, exposure, FRTB-SA, SIMM, the cubes, Pivot cells, BI
  measures) has one Explain.
- **Preview** (hover, or `?`): a one-line card with the answer and links.
- **Drawer** (click, or `E`) on the right, the page still visible, four tabs, the same everywhere: **Formula** (with the actual values),
  **Inputs & lineage** (down to rows), **Why it moved** (since the previous date, steps that add up), **Workbook** (the live-formula
  Excel); plus checks (inputs complete, equal to the official capture).
- **Full page** from the drawer, for auditors and printing.
- Computed and explained on the server; a stored figure says it is stored. Today's explain panels (How calculated, the ? pop-ups, Why it
  moved and the others) fold into it and are deleted.

## 4. The trader home

Mock-up: [e4-trader-home.html](mockups/shared-screens/e4-trader-home.html), option **A**, with **C**'s feed as a column and **B** one
click from a book.

- **Five headline figures**: today's P&L (live), VaR against its limit, the biggest risk, breaches (and near ones), approvals for me.
- **My books**: live P&L, VaR and a limit-use bar each; a click opens the book position by position (P&L, sensitivities, VaR
  contribution).
- **Markets I watch**, live; **what changed since this morning** (new and amended trades, market, with *Why it moved*); a **live feed**
  column (trades booked, market moves, limits approached, approvals waiting).
- What a person sees follows their desk and role; every figure is computed on the server and ticks through the live hub, never in the
  browser; every number opens the Explain; the command line is always at the top. It is the Trading lens's home (configuration, not a
  new product); a pre-deal *what if I add this trade* box joins it, drawn from the Java pre-deal service (built: `POST /api/v1/risk/exposure/predeal`, EXPOSURE_GUIDE.md section 18.9) when its screen is built on the Elements libraries.

## 5. Build checklist

Each item ships with a click test for every button, its guide section, its tutorial steps (UI, command line and YAML) and its help entry,
and deletes what it replaces.

| # | Screen | Replaces | Done |
|---|---|---|---|
| 1 | One Build home (rail, lists with lineage, first-visit tiles, one search) | Studio, BI Studio, the workbench, the pack tools' homes | ☑ (2.9.11.e: `/build`; BI Studio's own home `/bi` redirects to it, its four lists the home's For you strip) |
| 2 | One Approvals queue (inbox, due soon, diff, effect, rules, questions) | the per-kind approval queues | ☑ (built in 2.9.11.e: `/approvals`, `/api/v1/approvals`; the access kinds and the effect in risk numbers are under construction) |
| 3 | One Explain (preview, drawer with four tabs, full page) | the five explain panels | ◐ built for VaR, ES, parametric, exposure, the MR cube's cells and the Pivot's Why did it move? card (`GET /api/v1/explain`, `drishti_explain.js`); BI measures and Sutra metrics in the under-construction registry (`explain-*`) |
| 4 | The trader home (headline figures, books, markets, what changed, feed) | the generic home for the Trading lens | ☐ |

## 6. Controls screens: run status and sign-off, the breach queue, number honesty

The screens of the controls a risk function runs every day, chosen by the product owner from mock-ups (2026-11-05), built in 2.9.11.f
(trust, RBAC, security and controls; the security rules themselves are settled in that release's design discussion). The mock-ups are
in [mockups/controls/](mockups/controls/); people, roles and API keys are in [EDITORS.md](#7-the-editors-one-pattern-for-every-object-people-author) sections 5 and 6; the Approvals
queue and the Explain drawer they use are in [SHARED_SCREENS.md](SHARED_SCREENS.md).

### 6.1 Run status and sign-off

Mock-up: [f1-run-signoff.html](mockups/controls/f1-run-signoff.html), option **A** for a day's run, **B** as the overview, **C** as a tab.

- **A day's run** (VaR, exposure) as five steps: **inputs arrived → complete → checks → computed → signed off**, each green, amber or
  waiting. The checks are listed (positions not priced and their share of gross, a day-on-day move over its threshold with *Why it
  moved*, the backtest zone).
- **The sign-off panel**: who prepared it, who signs, by when, the signer's comment, and what signing means: the figures and their capture
  become **Official**.
- **Overview**: a grid of business dates × measures, each cell computed, awaiting sign-off, signed (by whom, when) or **restated**; a cell
  opens the day's run. **Timeline**: everything that happened to the run, as a tab of the day's run.
- Rules: "Official" only after sign-off; the signer is never the person who prepared it; warnings and the comment are stored with the
  capture; a change after sign-off is a restatement with its reason and a second sign-off; an overdue sign-off reaches the Approvals
  queue as *due soon*; everything audited.

### 6.2 One breach and escalation queue

Mock-up: [f2-breaches.html](mockups/controls/f2-breaches.html), option **A** as the risk-control screen, **B** in the Approvals inbox, **C**
as a tab.

- **One list** of open breaches and near-breaches (VaR limits and credit limits); the chosen one is a **case**: the figures with Explain
  and *Why it moved*, the **escalation ladder** (who was told when, the next level and when it is reached), the actions taken, and how it
  may be resolved: back under the limit, an approved temporary increase (four eyes), or a waiver with a reason.
- A breach escalated to a person appears at the top of **their Approvals inbox**.
- A **heat map** (limits × days, coloured by use) as a tab, for trends building toward a breach.
- Rules: **credit-limit use is computed from the engine, never stored**; near-breaches shown from a configurable threshold (e.g. 85%);
  escalation by time and level, configured; built on the one alert engine (today's separate alert lists fold into it); everything audited.

### 6.3 Number honesty: computed, stored, official

Mock-up: [f3-honesty.html](mockups/controls/f3-honesty.html), mixed by context.

| Status | Means | Headline tiles and cards | Tables, trees, the pivot |
|---|---|---|---|
| **Computed** | by Drishti, from its inputs, now | badge `COMPUTED` | green dot |
| **Stored** | a source's figure as delivered, not recomputed | badge `STORED · as delivered` | amber dot |
| **Official** | signed off (section 1) | badge `OFFICIAL · signed <time>` | blue dot |

- One legend per screen with dots; colour is never the only cue (each dot's tooltip says the word).
- The Explain drawer's first line states the status, with the source and the time.
- The server sends each figure's status; the console only displays it; a guard test fails if a figure is drawn without one.
- Exports carry a status column. A stored figure whose computation is under construction keeps the registry's chip
  ([UNDER_CONSTRUCTION.md](../admin/UNDER_CONSTRUCTION.md)).

### 6.4 Build checklist

Each item ships with a click test for every button, its guide section, its tutorial steps and its help entry, and deletes what it replaces.

| # | Screen | Replaces | Done |
|---|---|---|---|
| 1 | Run status and sign-off (the day's run, the grid, the timeline) for VaR and exposure | the capture pages' partial status | ☐ |
| 2 | One breach and escalation queue (cases, ladder, resolve, inbox, heat map) | the separate alert lists for limits | ☐ |
| 3 | Number honesty (badges, dots, legend, the drawer's first line, export column, guard test) | unlabelled stored figures | ☐ |

## 7. The editors: one pattern for every object people author

How Drishti's authoring screens look and behave: tables and virtual tables, semantic models, Python-driven tables and models, people
and roles, and API keys. One shell and one set of rules, so a person who has learned one editor knows them all. The product owner chose
each design from mock-ups (2026-11-05); the mock-ups are in [mockups/editors/](mockups/editors/) and are opened in a browser.

Which release builds what: tables, virtual tables, semantic models and Python-driven tables and models come with the BI foundation;
people, roles and API keys come with trust, RBAC and security, whose rules are settled in that release's design discussion (this
document fixes only their screens).

### 7.1 The shell every editor shares

Mock-up: [g1-editor-shell.html](mockups/editors/g1-editor-shell.html), option **C** as the home, option **B** for every *+ New*.

- **Three panes.** Left: the objects (tables, models, Python tables) with search. Middle: the editor, with the **live YAML below it**,
  the line being edited highlighted. Right: a **live preview with checks** (rows, totals computed by the pivot engine, warnings,
  impact).
- **A Visual / YAML / Both toggle**, remembered per person. YAML edits validate as typed and show back in the visual side; a layman can
  keep the YAML collapsed.
- **The header**: where you are, the name, the status with versions (*draft v4*, *approved v3*), and Validate, Preview, History,
  Impact and **Propose for review**.
- **+ New** opens guided steps in plain language (option B); when the object exists it opens in the three-pane editor.
- **As built**: the shell is drishti-designer's `DesignerShell` and the YAML its `YamlPane` over drishti-inputs' `CodeEditor`
  ([ELEMENTS.md section 8.9](ELEMENTS.md#89-as-built-drishti-designer-the-designer-kit)), the same for every designer: the table,
  model and Python editors, the Build workbench's canvas and YAML, the BI report designer's canvas, undo and YAML. Its look is mock-up
  g7's: **studio** (option A, the default: the left rail's tabs, the canvas with the YAML docked under it, the inspector, the preview),
  **side by side** (B: the canvas and the YAML side by side, the inspector a card by the selection) and **terminal** (C: a command line
  on Ctrl+K and the function keys F3 Validate, F4 YAML, F5 Preview, F7 History, F8 Impact, F9 Propose); each person's layout, mode and
  whether the YAML is open are kept by the server. A YAML edit is read on the server after a pause and each problem is marked at its
  line (a sign in the gutter, the list under the editor); the visual side keeps the last valid version.

### 7.2 Tables and virtual tables

Mock-up: [g2-tables.html](mockups/editors/g2-tables.html), option **A** as the editor, option **C** as its Text toggle.

- **The source and its look-ups as rows**; **columns as a checklist** with the name here, where it comes from, its type, and computed
  columns as Rachana formulas. A look-up brings one row of another table by a key (many to one: `related(table.COLUMN, on = key)`).
  A plain table is the same editor with one source and no look-up.
- **Tables use look-ups; models join** (product owner, 2026-11-06): a table enriches its rows with look-ups and never multiplies them;
  combining tables row by row (one to many, several facts) is a semantic model's relationships (section 3).
- **Text toggle**: the same table as one Rachana statement (`table … from … select … where …`, look-ups as `related(…)` columns) with
  type-ahead.
- **A look-up across connectors shows its cost** (rows each side, memory, time) and is refused above a limit until the reader filters.
- The diagram (option B) is not an editing mode; it may appear later as a read-only picture of the look-ups and a model's relationships.

### 7.3 Semantic models

Mock-up: [g3-models.html](mockups/editors/g3-models.html), option **A** as the editor, **B** as the Overview and the *+ New* steps,
**C** for a measure's details and the Access tab.

- **Tabs**: Overview, Tables, Fields, Measures, Hierarchies, Row rules, Masks, Access, each with a count.
- **Overview** answers four questions: what it is about, what people look at it by, what numbers it gives, who may see what. The same
  four questions are the *+ New model* steps.
- **A measure's ?** opens its card: the formula, a plain explanation, a worked test value, and where it is used.
- **Access** is a matrix of roles against rows, columns (masked or not) and editing.
- Whether a total adds up is the engine's answer, never the author's: VaR, ES and ratios are computed per total.

### 7.4 Python-driven tables and models

Mock-up: [g4-python.html](mockups/editors/g4-python.html), option **A** as the editor, **B** (the Notebook) to explore, **C**
(templates) for common cases; all three end in A.

- **Code beside its output contract**: the columns, types, keys and rules the code must return. The inputs, when it runs (on demand
  and cached per business date, or on each load of an input), and its compute pool and limits are on the same page; a sample run and its
  checks on the right.
- **Notebook → Turn into table**: cells explored on the server become a build function, and the contract is filled in.
- **Templates** (join and derive, resample, statistics per group, a file or feed from an approved source) write the Python and show it.
- A Python **model** is the same, with its measures declared in the contract.
- **Where it runs.** The sample run, the Notebook's cells and the table's rows when read all run on the server's compute pool `python`, in
  its strict sandbox: the inputs read as the person running it (row rules and masks), pinned libraries, the code's time and memory limits, no
  network (an approved source is read by the server and handed in as an input), files only in its own folder, every run audited; the rows are
  checked against the contract and a mismatch is refused in words ([PYTHON_COMPUTE.md section 19](PYTHON_COMPUTE.md#19-the-python-runtime-for-code-people-write)).
  Where the server cannot run Python safely (compute off, Windows) the buttons are off and say why.

### 7.5 People and access

Mock-up: [g5-people.html](mockups/editors/g5-people.html); all three options, as the three tabs of **Admin → People & access**.

- **People**: a searchable list; a person's page with sign-in (directory, MFA, last seen), roles, scope (packs, desks, lens), powers,
  API keys, recent activity, and **what this person sees**.
- **Roles**: every role against every power on one page; built-in roles (from configuration) are locked.
- **Check access**: *can this person do this?* answered yes or no **with the reason** (the role, the rule and the attribute that
  decided it) and how to change it.

### 7.6 API keys

Mock-up: [g6-apikeys.html](mockups/editors/g6-apikeys.html); all three options.

- **My keys** (everyone): what each key acts as (its owner, never more than the owner's rights), its scopes in words, its limits (only
  these models, only from this network), expiry and **last use** (when, from where, what), Rotate and Revoke.
- **+ New key** in steps: purpose, what it may do, limits and expiry, then the secret **shown once** (Drishti keeps only a fingerprint).
- **Admin → API keys**: every key with owner, scopes, expiry, last use and flags (expiring, unused, used from outside the network),
  bulk revoke, and the key policy (maximum lifetime, unused keys revoked, a rotation warning).

### 7.7 Rules every editor follows

- **Nothing goes live without review**: a change is proposed and approved by another person (four eyes), versioned and audited.
- **Impact before change**: every editor shows what uses the object (models, Sutras, reports, boards, keys) before it is changed.
- **One grammar**: every formula, filter, row rule and computed column is Rachana, typed in drishti-inputs' code editor (one line for a
  field, the whole text for the statement) with the function catalogue and the editor's names as its completion.
- **Built from Drishti Elements, never by hand** ([ELEMENTS.md section 3.1](ELEMENTS.md#31-standing-rule-no-bespoke-ui)): every pane of
  fields is drishti-inputs' `Form` (live, with the formula widget for a Rachana field: the table's name, rows and look-ups, the model's
  Overview and masks, the Build workbench's inspector through drishti-designer's `Inspector`); the rows of a table of settings (a model's
  tables, relationships, measures, hierarchies, row rules, fields and access matrix; a table's source and look-ups and its columns
  checklist) are drishti-grid with cell editing (`E.rowGrid`): Enter, F2, a double click or typing edits a cell, Enter saves, Escape
  cancels, Ctrl+Z undoes; a select of the offered values, a checkbox that turns at once, and a formula cell that is drishti-inputs'
  one-line Rachana code editor completing from the editor's names and the functions (the grid's `code` editor). Each edit, paste or undo
  is one change of the document; a row's ?, Remove and the engine's "adds up?" are cells the editor draws.
- **One entitlement service**: row rules and masks are enforced on the server for every reader (BI, Pivot, VaR, Flight, the API).
- **Computed on the server**: previews, totals and Python runs happen on the server; the console only displays.
- **Under construction**: an editor or a part of one that depends on unbuilt work is hidden or marked from the registry
  ([UNDER_CONSTRUCTION.md](../admin/UNDER_CONSTRUCTION.md)).

### 7.8 Build checklist

Each item ships with its tests (a click test for every button), its guide section, its tutorial steps (UI, command line and YAML) and
its help entry.

| # | Editor | Built in | Done |
|---|---|---|---|
| 1 | The three-pane shell, the Visual / YAML / Both toggle, the header, + New steps | BI foundation | ☑ |
| 1a | The shell on the designer kit (DesignerShell, YamlPane) in g7's three layouts; every designer on the kit (ELEMENTS.md 12.3) | Drishti Elements | ☑ shell, YAML; ☐ the editors' own panes |
| 2 | Tables and virtual tables (grid, Text toggle, cross-connector cost) | BI foundation | ☑ |
| 3 | Semantic models (tabs, Overview, measure cards, Access matrix) | BI foundation | ☑ |
| 4 | Python-driven tables and models (code and contract, Notebook → table, templates, the sample run on the compute pool) | BI foundation | ☑ |
| 5 | People & access (People, Roles, Check access) | Trust, RBAC and security | ☐ |
| 6 | API keys (My keys, + New key, Admin overview) | Trust, RBAC and security | ☐ |

