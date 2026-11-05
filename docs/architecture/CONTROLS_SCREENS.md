<!--
  Project Drishti · Any data. Any domain. One grammar.
  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
  PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository.
-->

# Controls screens: run status and sign-off, the breach queue, number honesty

The screens of the controls a risk function runs every day, chosen by the product owner from mock-ups (2026-11-05), built in 2.9.11.f
(trust, RBAC, security and controls; the security rules themselves are settled in that release's design discussion). The mock-ups are
in [mockups/controls/](mockups/controls/); people, roles and API keys are in [EDITORS.md](EDITORS.md) sections 5 and 6; the Approvals
queue and the Explain drawer they use are in [SHARED_SCREENS.md](SHARED_SCREENS.md).

## Contents

1. [Run status and sign-off](#1-run-status-and-sign-off)
2. [One breach and escalation queue](#2-one-breach-and-escalation-queue)
3. [Number honesty: computed, stored, official](#3-number-honesty-computed-stored-official)
4. [Build checklist](#4-build-checklist)

## 1. Run status and sign-off

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

## 2. One breach and escalation queue

Mock-up: [f2-breaches.html](mockups/controls/f2-breaches.html), option **A** as the risk-control screen, **B** in the Approvals inbox, **C**
as a tab.

- **One list** of open breaches and near-breaches (VaR limits and credit limits); the chosen one is a **case**: the figures with Explain
  and *Why it moved*, the **escalation ladder** (who was told when, the next level and when it is reached), the actions taken, and how it
  may be resolved: back under the limit, an approved temporary increase (four eyes), or a waiver with a reason.
- A breach escalated to a person appears at the top of **their Approvals inbox**.
- A **heat map** (limits × days, coloured by use) as a tab, for trends building toward a breach.
- Rules: **credit-limit use is computed from the engine, never stored**; near-breaches shown from a configurable threshold (e.g. 85%);
  escalation by time and level, configured; built on the one alert engine (today's separate alert lists fold into it); everything audited.

## 3. Number honesty: computed, stored, official

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

## 4. Build checklist

Each item ships with a click test for every button, its guide section, its tutorial steps and its help entry, and deletes what it replaces.

| # | Screen | Replaces | Done |
|---|---|---|---|
| 1 | Run status and sign-off (the day's run, the grid, the timeline) for VaR and exposure | the capture pages' partial status | ☐ |
| 2 | One breach and escalation queue (cases, ladder, resolve, inbox, heat map) | the separate alert lists for limits | ☐ |
| 3 | Number honesty (badges, dots, legend, the drawer's first line, export column, guard test) | unlabelled stored figures | ☐ |
