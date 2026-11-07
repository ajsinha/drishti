<!--
  Project Drishti · Any data. Any domain. One grammar.
  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
  PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository.
-->

# Shared screens: one Build home, one Approvals queue, one Explain, the trader home

Four screens that replace several today, chosen by the product owner from mock-ups (2026-11-05), built in 2.9.11.e ("one Build home,
one search"). Each folds duplicates into one and deletes the old in the same release (*lean & streamlined*). The mock-ups are in
[mockups/shared-screens/](mockups/shared-screens/) and open in a browser; the editors they lead to are in [EDITORS.md](EDITORS.md).

## Contents

1. [One Build home](#1-one-build-home)
2. [One Approvals queue](#2-one-approvals-queue)
3. [One Explain for every number](#3-one-explain-for-every-number)
4. [The trader home](#4-the-trader-home)
5. [Build checklist](#5-build-checklist)

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
  new product); a pre-deal *what if I add this trade* box joins it when the Java pre-deal service is built.

## 5. Build checklist

Each item ships with a click test for every button, its guide section, its tutorial steps (UI, command line and YAML) and its help entry,
and deletes what it replaces.

| # | Screen | Replaces | Done |
|---|---|---|---|
| 1 | One Build home (rail, lists with lineage, first-visit tiles, one search) | Studio, BI Studio, the workbench, the pack tools' homes | ☑ (2.9.11.e: `/build`; BI Studio's own home `/bi` redirects to it) |
| 2 | One Approvals queue (inbox, due soon, diff, effect, rules, questions) | the per-kind approval queues | ☑ (built in 2.9.11.e: `/approvals`, `/api/v1/approvals`; the access kinds and the effect in risk numbers are under construction) |
| 3 | One Explain (preview, drawer with four tabs, full page) | the five explain panels | ◐ built for VaR, ES, parametric, exposure, the MR cube's cells and the Pivot's Why did it move? card (`GET /api/v1/explain`, `drishti_explain.js`); BI measures and Sutra metrics in the under-construction registry (`explain-*`) |
| 4 | The trader home (headline figures, books, markets, what changed, feed) | the generic home for the Trading lens | ☐ |
