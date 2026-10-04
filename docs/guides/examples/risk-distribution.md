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
# Risk distribution

How risk is spread: scenario P&L as a histogram with VaR lines, books as points, a limit as a dial.

**Panel kinds:** `histogram`, `scatter`, `gauge`.

**Open it:** in the workbench, **Build → New → Examples** and pick `risk-distribution`: it opens as a copy (this Sutra, the JSON as samples, this note as notes). Or **File → Open** in the workbench with `risk-distribution.sutra.yaml` and `risk-distribution.json`.

**What to look for:** dashed marker lines for VaR 99%, ES 97.5% and the mean on the histogram; one labelled point per book on the scatter; the gauge reading usage against the limit.

**Lines to copy:** `markers:` with `value: "-$.var99"` (an expression over the document), `x`, `y` and `label` on the scatter, `value` and `max` on the gauge.
