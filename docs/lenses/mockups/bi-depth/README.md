<!--
  Project Drishti · Any data. Any domain. One grammar.
  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
  PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository for the full terms.
-->
# BI depth: five sets of mock-ups

**The request.** The screens for the BI-depth release: a decomposition tree that can break down VaR, a Playground for Drishti Elements,
linked brushing, the first new visuals, and modelling of many-to-many and both-ways relationships. The gaps they close are in
[BI_GUIDE.md](../../../guides/BI_GUIDE.md#part-6--drishti-bi-and-power-bi-compared).

Each HTML file is self-contained (system fonts, no CDN). The caption bar switches option, state and theme; the address keeps the choice
(`#B-error-light`). The interactions work, in plain JavaScript written for these mock-ups: the figures are synthetic, but VaR is really
computed for each selection from generated scenario P&L (509 scenarios). The real screens are built from Drishti Elements, never from this code.

## 1. Decomposition tree: [decomposition-tree.html](decomposition-tree.html)

| A · Tree | A · Notional | B · Ladder | C · Waterfall |
|---|---|---|---|
| ![](decomposition-tree-A-var.png) | ![](decomposition-tree-A-sum.png) | ![](decomposition-tree-B-var.png) | ![](decomposition-tree-C-var.png) |

Light: ![](decomposition-tree-A-var-light.png)

**Non-additive splits.** Every box is its own number, recomputed for that slice. A VaR split states *Σ standalone − diversification = parent*
beside the column; a plain sum says the children add exactly.

- **A · Tree.** Boxes left to right, one open at each level, the next split chosen by a list at the column head. *For:* the shape Power BI
  users know; the path is visible at once; diversification sits on each split. *Against:* wide after four levels; horizontal scrolling.
- **B · Ladder.** Indented lists of levels, down the page. *For:* the best fit on a narrow panel; every level has its share column. *Against:*
  reads as a report, the exploring feel is weaker.
- **C · Waterfall.** One split at a time: standalone bars, the green diversification step, the true figure. *For:* the clearest statement of
  the benefit, and it needs no explanation for a risk manager. *Against:* only one level is on screen.

**Recommendation: A**, with C as the view a node opens ("explain this split") in a later release. The diversification line in A is what no
other tool has.

**Decide:** whether to show Euler contribution (component VaR) beside standalone. It adds up exactly, but it needs the risk engine's vectors.

## 2. Elements Playground: [elements-playground.html](elements-playground.html)

| A · One column | A · Error | B · Workbench | B · Error |
|---|---|---|---|
| ![](elements-playground-A-ok.png) | ![](elements-playground-A-error.png) | ![](elements-playground-B-ok.png) | ![](elements-playground-B-error.png) |

Light: ![](elements-playground-A-ok-light.png)

Both have the live visual, an editable data table, an options form, and formatted, editable HTML and JavaScript; every edit redraws after a
short pause; Reset restores the example. The frame is `sandbox="allow-scripts"` with no same-origin and a content policy that blocks the
network. **An error** names the message and the line, marks the line in the gutter, offers *Go to the line*, and keeps the last good drawing
greyed out above it. A new frame is built for each edit and swapped in only if it ran cleanly.

- **A · One column** (like a D3 reference page). *For:* reads as documentation, then an experiment; the page links well. *Against:* the code is
  below the fold on a laptop.
- **B · Workbench** (code left, visual right). *For:* code and result side by side while editing. *Against:* no room for the prose, weak as reference.

**Recommendation: A.** It is what was asked for, and it doubles as the element's reference page.

## 3. Linked brushing: [linked-brushing.html](linked-brushing.html)

| A · Filter | B · Highlight | C · Crossfilter | Cleared |
|---|---|---|---|
| ![](linked-brushing-A-brushed.png) | ![](linked-brushing-B-brushed.png) | ![](linked-brushing-C-brushed.png) | ![](linked-brushing-A-empty.png) |

Light: ![](linked-brushing-A-brushed-light.png)

Drag across the time chart, the histogram or the scatter; click bars. Clearing: the chip's ✕, *Clear all*, Esc, or a click on empty chart area.
VaR in the tiles and the bars is recomputed for the selection, never re-added.

- **A · Filter.** One selection; the other charts filter to it. *For:* one engine query per brush, easy to read. *Against:* the context goes.
- **B · Highlight.** Same, with the unfiltered figures ghosted behind. *For:* keeps proportion. *Against:* two queries per chart, and costly for VaR.
- **C · Crossfilter.** Several brushes at once, each chart ignoring only its own. *For:* the analyst's tool. *Against:* more to explain, more queries.

**Recommendation: A** as the default for every chart, with C as a report setting.

## 4. First new visuals: [new-visuals.html](new-visuals.html)

| A · Sunburst | A · Icicle | A · Calendar | B · Board |
|---|---|---|---|
| ![](new-visuals-A-sunburst.png) | ![](new-visuals-A-icicle.png) | ![](new-visuals-A-calendar.png) | ![](new-visuals-B-sunburst.png) |

Light: ![](new-visuals-A-sunburst-light.png)

Segment size is **notional**, because it adds up; **VaR use of limit is colour**, read per node, because VaR does not add. Click to zoom,
the centre or the path to go back. The calendar shows limit breaches or backtesting exceptions per day, with non-business days hollow.

- **A · One visual at a time,** with a detail panel. *For:* the size needed for labels; simple. *Against:* no comparison.
- **B · Board.** The three linked: the sunburst sets the scope of the icicle and the calendar. *For:* a daily risk view. *Against:* the sunburst labels are tight.

**Recommendation: A** for the elements themselves. B then needs no new element: it is a report that places three of them.

## 5. Modelling: [modelling.html](modelling.html)

| A · Bridge | A · Both ways | A · Refused | B · Bridge | B · Refused |
|---|---|---|---|---|
| ![](modelling-A-bridge.png) | ![](modelling-A-direction.png) | ![](modelling-A-refused.png) | ![](modelling-B-bridge.png) | ![](modelling-B-refused.png) |

Light: ![](modelling-A-bridge-light.png)

**The refusal** names the two paths in words and draws them in red; the model is not saved, and each fix is one button. The bridge form shows why group rows do not add to the total.

- **A · Canvas and inspector.** *For:* the paths are visible, which is the whole subject. *Against:* a new graph element; a model of forty tables gets messy.
- **B · Table first.** A grid of relationships, the same forms, a diagram below. *For:* scales; built from the grid and inputs that exist. *Against:* the paths are one step away.

**Recommendation: B**, drawing the diagram on the refusal and on request. The new graph element then has one small job.

## Build checklist

| Item | Library | Data | Interaction | Status |
|---|---|---|---|---|
| Decomposition tree: columns, split list, links, diversification line | `drishti-tree` | pivot engine; VaR from the engine per node | open a node, change a split | built |
| Playground page per element; sandbox with error reporting; last good drawing; reset | `drishti-inputs` (code editor), `drishti-grid` (data) | element's example | edit redraws | built |
| Brush on line, histogram, scatter; click on bars; chips; Esc | `drishti-chart`, `drishti-core` (events) | cross-filter of the report, VaR recomputed | drag, clear | built |
| Sunburst, icicle | `drishti-tree` (its modes) | hierarchy from promoted columns | zoom, path | built (reports; Sutra panels later, with the Rachana track) |
| Calendar heatmap | `drishti-chart` | breaches or exceptions per business date | pick a day | built (reports; Sutra panels later) |
| Relationship grid and forms; bridge form; path diagram | `drishti-grid`, `drishti-inputs`, `drishti-chart` | the model file | edit, save | to build |
| Refusal with paths and one-click fixes | server model check; `drishti-core` | a new error code, one per refusal | apply a fix, undo | to build |

The language these screens rest on is designed in [HOW_IT_FITS.md section A28.4](../../../../HOW_IT_FITS.md#a284-the-expression-language-del-and-its-checks).
