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
# Market charts

Market data: daily bars, a line of closes and a volatility surface.

**Panel kinds:** `candlestick`, `line`, `surface`.

**Open it:** in the workbench, **Build → New → Examples** and pick `market-charts`: it opens as a copy (this Sutra, the JSON as samples, this note as notes). Or **File → Open** in the workbench with `market-charts.sutra.yaml` and `market-charts.json`.

**What to look for:** 30 bars with volume under them (scroll inside the chart to zoom); the surface opens as a 3D plot you can rotate (`view: 3d`; leave it out for a heatmap).

**Lines to copy:** `volume: volume` on a candlestick; `y: month` plus `columns:` (one per point across) on a surface.
