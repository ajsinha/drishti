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
# Tree table

Tables and ladders whose rows hold their own children: an organisation of units, each with sub-units, to any depth.

**Panel kinds:** `table` and `ladder`, both with `children` and `expand`.

**Open it:** Studio **File → Open** with `tree-table.sutra.yaml` and `tree-table.json`, or `/studio?example=tree-table`.

**What to look for:** a triangle beside each unit that has children; it opens in place, indented. The table opens to the second level (`expand: 2`), the ladder opens everything (`expand: all`). The `Total` adds up only rows without children, so nothing counts twice. Type in the filter: parents of the rows found stay.

**Lines to copy:** `children: "@.children"` (an expression per row, giving the rows nested under it) and `expand`.
