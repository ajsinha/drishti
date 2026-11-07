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
# Metric

A desk's headline numbers as big-number tiles: one large formatted, toned figure each, with a small change, a unit and a caption.

**Panel kinds:** `metric`, `hbar`.

**Open it:** in the workbench, **Build → New → Examples** and pick `metric`: it opens as a copy (this Sutra, the JSON as samples, this note as notes). Or **File → Open** in the workbench with `metric.sutra.yaml` and `metric.json`.

**What to look for:** the MTM tile shows a signed figure toned by its sign, with the change since yesterday in its own tone beside it; the VaR tile's change is toned `neg` whichever way it moves (a rise in risk is bad); the limit-use tile has a caption built with `${...}`; the last tile is a plain count; the bars below say where the MTM comes from.

**Lines to copy:** `value` (an expression, required), `fmt` and `tone` for the figure, `delta` with `deltaFmt` and `deltaTone` for the change, `unit` and `caption` for the small texts.
