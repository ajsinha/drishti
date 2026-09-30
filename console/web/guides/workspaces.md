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
# Workspaces

A workspace puts several live views on one screen. Every pane is a full Drishti view, with its own
live updates, links and keys, and a pane can **follow** another. For example, pick a member trade in
the netting-set pane, and the pane that follows it opens that trade.

## Open one

Choose **Workspaces** in the top bar (`/w`). Your saved workspaces are listed first, then the
starters:

| Starter | Layout | Panes |
|---|---|---|
| Credit desk | one large, two stacked | a netting set; the trade you pick in it (follows pane 1); the discount curve |
| Rates | two columns | a swap; the linked entity you pick in it (follows pane 1) |
| Cross-asset | two by two | the four reference views at once |

## Change it

- **Layout:** two columns, three columns, two by two, or one large pane with two stacked.
- **What a pane shows:** type a command or identifier in the pane's box (`TRD FXS-20931`, `NS-NORTH-01`)
  and press Enter.
- **Follows:** choose which pane this one follows. A link clicked in the followed pane opens here.
  A pane that nobody follows navigates in place.
- **Panes:** add up to four; `×` removes one. `Alt+1`…`Alt+4` moves between panes.

## Save it

**Save** keeps it under its name, and **Save as…** under a new one. Workspaces are saved to your
account on the server, so they follow you to any browser. Each user has up to 50 workspaces.

!!! note "On a phone"
    Panes stack in one column, each a scrollable live view.
