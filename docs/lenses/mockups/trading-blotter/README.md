<!--
  Project Drishti · Any data. Any domain. One grammar.
  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
  PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository for the full terms.
-->
# The live blotter: mock-ups

Three options for the live, editable blotter (the trading module's trade blotter is the first declaration; the same screen serves every
blotter a module declares). Each is a self-contained HTML file with the theme tokens inlined, no CDN; `#light` shows the light theme.
PNGs at 1440 × 900, dark and light, from headless Chrome.

| Option | File | In one line |
|---|---|---|
| A | `blotter-A-terminal-grid.html` | the dense terminal grid: every editable cell edits in place; refusals as a toast |
| B | `blotter-B-grid-and-panel.html` | the grid plus the record's panel: editable fields, reason, four-eyes state, version history, links to Explain, VaR and limits |
| C | `blotter-C-grouped-stream.html` | grouped by desk and book with live subtotals, an event ticker and the tray of my changes (the outbox) |

**Recommended: B**, with C's *My changes* tray and grouping as grid options (grouping is a grid feature in every option): quick edits stay
in the cells, while a change that needs a reason or an approver is reviewed in the panel, where the version and who changed the record
last are in view before anything is sent.
