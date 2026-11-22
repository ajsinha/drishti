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
# Level 1.2 panels

The panel kinds of Rachana language levels 1.1 and 1.2 on one desk screen, each reading the document in `level-1-2-panels.json`;
[all-panels-showcase](all-panels-showcase.md) has the other twenty-two.

**Panel kinds:** `grid`, `tree`, `chart`, `treemap`, `bullet`, `form`, `live`.

**Open it:** in the workbench, **Create → New → Examples** and pick `level-1-2-panels`, or **File → Open** with `level-1-2-panels.sutra.yaml` and `level-1-2-panels.json`.

**What to look for:** the grid groups the trades by book and sorts them by notional; the tree adds VaR up the nodes; the chart sums
notional by book; the treemap sizes issuers by exposure and colours them by P&L; the bullet shows the net delta inside its two-sided
limit; the form's Set writes the quantity into the screen's `state`; the live panel reads the table its `data:` entry names
(`live-orders`, key `ORDER_ID`) and stays empty until a live table of that name is served.

**Lines to copy:** `rows` with `rowKey`, `group`, `sort` (grid); `node`, `parent`, `label` (tree); `x`, `y`, `agg` (chart); `group`,
`label`, `value`, `color` (treemap); `value`, `lower`, `upper` (bullet); `form:` and `on: { submit }` (form); `data:` and `rows: $.<name>` (live).
