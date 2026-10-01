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

A workspace puts up to four live views on one screen. Each pane is a full Drishti view, with its own live
updates, links and function keys. A pane can **follow** another pane: click a trade in a netting set's member
list, and the following pane opens that trade.

## Tutorial: a credit desk on one screen

The starter workspaces come from the **finance** pack. If **Workspaces** shows no starters, ask your administrator
to enable or assign the finance pack (see [Domain packs](packs)).

### 1. Open a starter

1. Click **Workspaces** in the top bar (or go to `/w`).
2. Under **Starters**, click **Credit desk**.

You should see three panes in the *one large, two stacked* layout:

| Pane | Shows | Follows |
|---|---|---|
| 1 (large, left) | netting set `NS-NORTH-01` | — |
| 2 (top right) | trade `IRS-48213` | pane 1 |
| 3 (bottom right) | curve `USD-SOFR` | — |

### 2. Watch a pane follow

In pane 1, click another trade in the *Member trades* table, for example `IRS-47102`. Pane 2 opens it.
Pane 1 stays where it is, so you can click your way through the members one by one.

A pane that nobody follows navigates in place when you click a link inside it.

### 3. Change what a pane shows

Each pane has a box in its header. Type a command or a bare identifier and press Enter:

```text
# Any of these work in a pane's box
TRD CFT-77120
NS-HARB-02
CRV USD-SOFR
```

The **↗** icon in a pane's header opens that pane's view full screen in the terminal.

### 4. Change the layout and panes

| Control | What it does |
|---|---|
| Layout menu | *Two columns*, *Three columns*, *Two by two*, or *One large, two stacked*. |
| **+ Pane** | Adds a pane (at most four). |
| **×** on a pane | Removes it. |
| *follows* menu on a pane | Chooses which pane this one follows (or *—* for none). |
| `Alt+1` … `Alt+4` | Moves the keyboard focus to pane 1 to 4. |

### 5. Save it as your own

- **Save** keeps the workspace under the name shown in the bar.
- **Save as…** asks for a new name (*Save workspace as:*), for example `My credit desk`, and saves a copy.
- **Delete** (on a saved workspace) removes it.

Your saved workspaces are listed under **Yours** on `/w`, and open at `/w/<name>`, for example
`/w/My credit desk`. They are saved to your account on the server, so they follow you to any browser. You can
keep up to 50.

!!! tip "Open a workspace when you sign in"
    In *My account → Settings*, set **After signing in, open** to `/w/My credit desk`, and that workspace opens
    every time you sign in.

## The starters

| Starter | Pack | Layout | Panes |
|---|---|---|---|
| Credit desk | finance | one large, two stacked | netting set `NS-NORTH-01`; the trade you pick in it (`IRS-48213` at first, follows pane 1); discount curve `USD-SOFR` |
| Rates | finance | two columns | swap `IRS-48213`; the linked entity you pick in it (`USD-SOFR` at first, follows pane 1) |
| Cross-asset | finance | two by two | the four reference views: `IRS-48213`, `FXS-20931`, `CFT-77120`, `NS-NORTH-01` |
| Shipment tracker | logistics | one large, two stacked | shipment `SHP-10042`; the container you pick (follows pane 1); vessel `VSL-9811000` |

Opening a starter does not change it for anyone else: you get a copy, and **Save** stores that copy under your
account.

## Worked idea: rates trade and its market data

With the finance pack enabled:

1. Open the **Rates** starter.
2. In pane 1, the swap `IRS-48213`, click the discount curve in *Linked entities*. Pane 2 shows the curve.
3. Click the netting set link in pane 1. Pane 2 now shows the netting set.
4. Change the layout to *Three columns*, press **+ Pane**, and type `CRV USD-SOFR` in pane 3, so the curve stays in
   view while pane 2 follows your clicks.
5. Press **Save as…** and call it `Rates and curve`.

## Things to know

- **Live updates.** All panes tick. The panes share the page's one live connection to the server, so a
  four-pane workspace is no heavier on the connection than a single view.
- **Business date.** Every pane follows the date in the top bar. Pick a date to see the whole workspace as of
  that business day; press **Live** to go back.
- **On a phone** the panes stack in one column, each a scrollable live view.

For the rest of the console, see [Using the terminal](using-the-terminal).
