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
# Role Home mock-ups (milestone H1)

Three directions, each shown for two roles (risk manager, trader). Static HTML, inline CSS, synthetic data only. Open the files in a browser.

| Direction | Files | What it is | Strength | Risk |
|---|---|---|---|---|
| A command centre | `A-risk-manager.html`, `A-trader.html` | Mnemonic bar first, a KPI strip, watch tiles, one main table | Closest to the terminal habit; fastest to the next mnemonic | Everyone sees a dashboard, even when nothing changed |
| B briefing | `B-risk-manager.html`, `B-trader.html` | Exceptions first, then what changed since yesterday, then numbers | Answers "what do I need to do now"; best for the morning | Less room for the user's own watch list; needs change detection on the server |
| C workspace | `C-risk-manager.html`, `C-trader.html` | Pinned panels the user arranges; the role supplies the default layout | Most personal; power users stay | Per-person layout storage; an empty workspace is a weak first visit |

## How a role Home is declared

- A Home is one file, `config/homes/<module>/<home>.yaml` (see `docs/plans/I7B_PLAN.md` 3.2). It holds a Sutra: a tree of panels, each pulled from a **library fragment** (KPI strip, watch tile, table, exceptions list, change feed, mnemonic bar). No panel is bespoke; every one is an Elements component.
- A role names its Home (`homes.roles.<role>` in the distribution, edited in Admin, Roles). The role's Sutra picks the direction: A is a grid of `kpi` and `watch` fragments; B is a `brief` fragment over the change feed and exceptions; C is a `workspace` fragment whose default layout comes from the role and whose per-person overrides live in the person's settings.
- Visibility is by role plus lens rules: a fragment appears only when the person's role holds its power, its module is on, and its mnemonic is active. A Home hides nothing else (existing rule). Every tile and row opens a mnemonic (`VAR DESK-A <GO>`), so the terminal stays first-class.
- Roles in scope: trader, risk manager, risk officer/CRO, credit officer, operations/admin, quant/analyst, economist (public-sector). Only two are mocked here.

## Open choices for the owner

1. Pick a direction (A, B, C), or a hybrid. A suggested hybrid is B on top of A: a short briefing strip above the command centre.
2. Is the layout fixed per role (A, B) or user-arranged (C)? If C, where is the layout stored (person settings) and may an administrator lock it?
3. Is "what changed since yesterday" a server feature (needs a change-detection service) or only a feed of the existing events?
4. One Home per role, or several (the picker per plan decision 11)?
5. Should the KPI figures be configured per role in YAML only, or editable by the user from the Home?

## Owner decision (2026-11-22)

The hybrid: direction B (exceptions first, what changed since yesterday) on top of direction A (KPI strip and mnemonic bar always present); direction C's pinned panels are an option, not the default (the role supplies the default layout; a person may arrange and save their own). "What changed since yesterday" is a real server feature (change detection), designed in H1.

## Hybrid (chosen) mock-ups

`H-risk-manager.html` and `H-trader.html` at 1600x1000: mnemonic bar and KPI strip always on top; "Exceptions first" beside "Since yesterday 17:00 (what changed and why)"; the working area (risk manager: desk VaR and limits with use bars, exposure by counterparty, stress tiles; trader: positions and P&L blotter, pre-deal check, market tiles); a right rail of quick mnemonics, calendar and news. "arrange my Home" (top right) shows C's drag and pin handles as an option; it is off by default.

- Panels and the planned Sutra fragments they come from: `mnemonic-bar`, `kpi-strip`, `exceptions-list`, `change-feed`, `table-with-use-bars` (desk VaR, positions), `exposure-table`, `tile-grid` (stress, market), `precheck-box`, `quick-mnemonics`, `calendar-list`, `news-list`, all from the Home library; `workspace` (C) only wraps them in arrange mode.
- Server feature "what changed since yesterday" (change detection): it compares the current business day's results with the previous cut-off (D-1 17:00) by key (desk, book, trade, counterparty, risk factor) and flags moves above a per-measure threshold, new or amended trades, loads and limit state changes.
- It runs on the server, after each load or recompute, in Java, and stores the change records per day so the Home only reads them (no browser computation).
- It explains a change by attribution: the same computation is run with one input group swapped at a time (new trades, amended trades, market data, rolled window), and the top drivers are named with their share of the move (for example "rates risk factor RF-USD-10Y, 71%").
- Each record carries the mnemonic that opens the detail (`WHY <key> <GO>`), is filtered by the person's role and lens, and needs no new data: it reads what the engines already hold.
