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
# P&L explain

Where a trade's P&L came from, and how sensitive it is.

**Panel kinds:** `waterfall`, `hbar`, `kv`.

**Open it:** in the workbench, **Build → New → Examples** and pick `pnl-explain`: it opens as a copy (this Sutra, the JSON as samples, this note as notes). Or **File → Open** in the workbench with `pnl-explain.sutra.yaml` and `pnl-explain.json`.

**What to look for:** the waterfall steps from the opening MTM through each effect to a closing bar; red and green bars on the DV01 chart (`tone: sign`); signed figures coloured in the key-value panel.

**Lines to copy:** `sum: Closing MTM` (the label of the final total bar) on the waterfall, `tone: sign` on bars and values.
