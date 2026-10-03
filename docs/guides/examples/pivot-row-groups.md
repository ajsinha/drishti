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
# Pivot with row groups

A pivot whose `by` is a list: desks hold books, books hold product families, each level with a subtotal.

**Panel kinds:** `pivot` (three of them: a three-level `by`, a two-level one with `expand: all`, and a flat one with `heat`).

**Open it:** Studio **File → Open** with `pivot-row-groups.sutra.yaml` and `pivot-row-groups.json`, or `/studio?example=pivot-row-groups`.

**What to look for:** in the first pivot only the desks show, each with a subtotal and a triangle; click a triangle to open the books, then the families. The second opens every level at once. Heat shading shows on the flat pivot only: nested pivots are not shaded.

**Lines to copy:** `by: [desk, book, family]` for the nesting, `expand: 1` (or `2`, or `all`) for the levels open at first, `across: currency` for the columns.
