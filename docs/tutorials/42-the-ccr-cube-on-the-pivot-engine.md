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
# Tutorial 42 · The CCR cube: the limit board, a netting set, pre-deal

The CCR cube is the pivot engine on the exposure engine's netting sets, with the counterparty lens around it. In this tutorial you open
the limit board (view A), check its bank row against `EXP BANK`, read a counterparty's fan with its limit line, see why a group's PFE is
not the sum of its members' PFEs, open a netting set (view B), run a pre-deal check and read the headroom after (view C), add SA-CCR EAD
and FVA as measures, and open the board on a phone. Each step shows the UI, the command line and the view's JSON. The guide is
[EXPOSURE_GUIDE.md section 30](../guides/EXPOSURE_GUIDE.md#30-the-ccr-cube-ccube); the pivot screen itself is
[tutorial 33](33-the-pivot-engine.md), and the MR cube, its market-risk twin, is [tutorial 38](38-the-mr-cube-on-the-pivot-engine.md).

> Screenshots marked *(filled in the heavy run)* are taken when the steps are run against a built server (`tools/docs/screenshots.py`);
> until then the marker stands where the picture goes.

## Time to complete

About 25 minutes.

## Before you start

- The server and console running with the demo risk lake ([tutorial 26](26-exposure-from-the-bank-to-a-trade.md)): `EXP BANK <GO>` answers.
- The engines `exposure`, `pivot` and `ccr-cube` on (Admin → Engines; `ccr-cube` is on as shipped). For step 6, `sa-ccr` and `fva` on too
  (both are on as shipped).
- A role that sees the counterparty-risk kinds at trades level (`credit-risk`), and a token for the command line in `DRISHTI_TOKEN` or a
  `--token-file` ([TOOLS.md](../guides/TOOLS.md)).

## What you will learn

- why every cell and every total of the cube is the exposure of its own netting sets' paths summed, and never a sum of peaks;
- how the limit board reads: peak PFE in each tenor bucket against the counterparty's limit, the use and the headroom;
- where a netting set's figures come from, and what collateral does to them;
- how a pre-deal check works, and why the browser never computes a figure.

## Step 1 · The landing: the limit board

**UI.** Type `CCUBE <GO>` (or **Engines → Exposure → CCR cube**). The pivot screen opens with the CCR lens on view A: the headline strip
(the bank's peak PFE at 95% with its tenor and PFE 99%, EPE and EEPE, the counterparties over and near their limits, the run), the lens
controls (q 95% · 99%, lagged · current · none, PFE · EE · EPE · EAD SA-CCR · FVA), the tabs A, B and C, and the pivot: groups and their
counterparties by tenor bucket (0–1Y, 1–5Y, 5Y+), each cell the peak PFE in the bucket and its use of the limit; beside it the fan and the
treemap.

**CLI.**

```
drishti.py pivot fields ccr
drishti.py pivot ccr board
drishti.py pivot ccr headline
```

**JSON** (the catalogue's `defaultView`, sent to `POST /api/v1/pivot/query`):

```json
{"source": "ccr", "rows": ["GROUP", "COUNTERPARTY", "NETTING_SET"], "columns": ["TENOR_BUCKET"],
 "values": [{"measure": "pfe()", "as": "Peak PFE"}, {"measure": "limit_use()", "as": "Use"}],
 "options": {"q": "0.95", "collateral": "lagged"}, "expandTo": {"rows": 2, "columns": 1}}
```

*(screenshot filled in the heavy run: the CCR cube's landing: the headline strip, the limit board by tenor bucket, the fan and the treemap)*

**You should see** a row per counterparty under its group, three bucket columns and a total; the grand total's peak PFE equals the peak
PFE of `EXP BANK` (and the headline's), and a counterparty over its limit shows a use above 100% in red.

## Step 2 · A counterparty's fan with its limit line

**UI.** Click a counterparty's row. The fan beside the board draws its EE (blue) and PFE (red) at every primary date, the counterparty's
stored credit limit as a dashed step line by bucket, the headroom shaded, the peak marked; under it the binding tenor (the date of the
least headroom) and that headroom. Click **Over limit** in the headline: the board keeps only the counterparties over their limits.

**CLI.**

```
drishti.py pivot ccr fan --scope CPTY:CP-ALDERSHOT
drishti.py pivot ccr fan --scope CPTY:CP-ALDERSHOT --q 0.99 --collateral current
```

**JSON** (`GET /api/v1/pivot/ccr/fan?scope=CPTY:CP-ALDERSHOT&q=0.95`, one point of the answer):

```json
{"tenor": "2W", "t": 0.0384, "ee": 0, "pfe": 0, "ene": 0, "bucket": "0-1Y", "limit": 0, "headroom": 0}
```

(the figures are the server's; the shape is what matters here)

*(screenshot filled in the heavy run: CP-ALDERSHOT's fan with the limit step line and the headroom shaded)*

**You should see** the fan's peak equal to the board's cell for the same counterparty and bucket, and `EXP CPTY:CP-ALDERSHOT`'s peak PFE
the same number.

## Step 3 · A group is its members' paths summed, then the quantile

**UI.** In the board, open a group with two or more counterparties (the arrow, or `→`). Compare the group's peak PFE with its
counterparties'. Then open view C: its **Group roll-up** lists the same tree with peak PFE, EPE and use.

**CLI.**

```
drishti.py pivot run --source ccr --rows GROUP,COUNTERPARTY --values "pfe()" --values "epe()" --level 2
```

**JSON:**

```json
{"source": "ccr", "rows": ["GROUP", "COUNTERPARTY"], "columns": [], "values": [{"measure": "pfe()", "as": "Peak PFE"},
 {"measure": "epe()", "as": "EPE"}], "options": {"q": "0.95"}, "expandTo": {"rows": 2}}
```

*(screenshot filled in the heavy run: a group opened, its members' peak PFEs and the group's)*

**You should see** the group's EPE equal to the sum of its members' EPEs (EPE adds), and its peak PFE below the sum of their peak PFEs: the
cell is the quantile of the members' exposures summed path by path, not a sum of quantiles (case X2 of COUNTERPARTY_ENGINES.md section 9
works it by hand: 3 against 2 for two sets).

## Step 4 · View B: a netting set

**UI.** Press `B`, or type `CCUBE NSET:NS-ALDERSHOT-LDN <GO>`. View B shows the set's tiles (peak PFE and its tenor, EPE and EEPE, the
current exposure after collateral, the run), the collateral effect at the peak's date (EE without collateral against EE with it), the
profile by tenor and the trades by contribution to the peak PFE (a hedge negative, in green).

**CLI.**

```
drishti.py exposure show NSET:NS-ALDERSHOT-LDN
drishti.py pivot ccr fan --scope NSET:NS-ALDERSHOT-LDN
```

**JSON:** view B reads the exposure engine's own answers, `GET /api/v1/exposure/scope?scope=NSET:NS-ALDERSHOT-LDN` and
`GET /api/v1/exposure/scope/contributions?scope=NSET:NS-ALDERSHOT-LDN&by=trade`.

*(screenshot filled in the heavy run: view B on NS-ALDERSHOT-LDN)*

**You should see** the set's peak PFE equal to its cell in the board (`NETTING_SET` row) and the trades' contributions adding up to it.

## Step 5 · View C: a pre-deal check and the headroom after

**UI.** Press `W` (or open view C). Type the counterparty (`CPTY:CP-ALDERSHOT`), a booked trade to copy, the netting set it joins and a
factor (1; −1 is its unwind), then **Pre-deal**. The tiles show the incremental peak PFE (before → after) and the headroom after with the
use of the limit; the overlay draws the PFE after (gold, dashed) over the PFE before, with the limit; a table lists the incremental PFE per
tenor.

**CLI.**

```
drishti.py pivot ccr predeal --scope CPTY:CP-ALDERSHOT --copy <a booked trade id> --into NS-ALDERSHOT-LDN --factor 1
```

**JSON** (`POST /api/v1/pivot/ccr/pre-deal`, the exposure engine's what-if request):

```json
{"scope": "CPTY:CP-ALDERSHOT", "method": "paths", "q": 0.95,
 "candidates": [{"tradeId": "CAND-1", "nettingSet": "NS-ALDERSHOT-LDN", "paths": {"copyOf": "<a booked trade id>", "factor": 1}}]}
```

*(screenshot filled in the heavy run: view C's pre-deal tiles and the overlay)*

**You should see** `base.peakPfe` equal to the counterparty's peak PFE in the board, `with.peakPfe` the peak after, and `headroomAfter` the
limit less it; the same request to `POST /api/v1/exposure/what-if` gives the same `base`, `with` and `incremental`.

## Step 6 · EAD (SA-CCR) and FVA as measures

**UI.** Click **EAD SA-CCR** in the lens's measure segment, then **FVA**: the board's first value becomes that measure. View C's tiles show
the counterparty's SA-CCR EAD and FVA beside the pre-deal check. Both are whole-life figures: a tenor bucket's cell is empty, with the reason
in its provenance; the row's total carries them.

**CLI.**

```
drishti.py pivot run --source ccr --rows COUNTERPARTY --values "ead()" --values "fva()" --values "pfe()"
drishti.py saccr show CPTY:CP-ALDERSHOT
drishti.py fva show CPTY:CP-ALDERSHOT
```

**JSON:**

```json
{"source": "ccr", "rows": ["COUNTERPARTY"], "columns": [], "values": [{"measure": "ead()", "as": "EAD SA-CCR"},
 {"measure": "fva()", "as": "FVA"}, {"measure": "pfe()", "as": "Peak PFE"}]}
```

*(screenshot filled in the heavy run: the board by EAD and FVA)*

**You should see** each counterparty's EAD equal to `SACCR CPTY:<id>`'s total and its FVA equal to `FVA CPTY:<id>`'s; both add up over
counterparties to the grand total. Turn `sa-ccr` off in Admin → Engines: the **EAD SA-CCR** button greys out and `ead()` is no longer in the
source's measures.

## Step 7 · On a phone

**UI.** Open `/ccube` on a phone (or the browser's 390 px view). The headline's five tiles stack two by two, the lens controls wrap, the
board scrolls inside its card and the page never scrolls sideways; views B and C stack their panels in one column. Dark and light themes
both carry the lens's colours, and over and near a limit say so in words as well as in red and amber.

**CLI.** Nothing new: the same `pivot ccr` commands.

**JSON:** the same views; the phone asks the same answers.

*(screenshot filled in the heavy run: the CCR cube on a phone, dark and light)*

**You should see** the bank's peak PFE in the first tile and the over-limit count beside it, without scrolling sideways.

## Next steps

- The guide: [EXPOSURE_GUIDE.md section 30](../guides/EXPOSURE_GUIDE.md#30-the-ccr-cube-ccube) (every measure, option, setting and endpoint).
- The pivot screen: [PIVOT_GUIDE.md](../guides/PIVOT_GUIDE.md); the exposure engine: [EXPOSURE_GUIDE.md](../guides/EXPOSURE_GUIDE.md);
  SA-CCR: [SACCR_GUIDE.md](../guides/SACCR_GUIDE.md); FVA: [FVA_GUIDE.md](../guides/FVA_GUIDE.md).
- The design: [COUNTERPARTY_ENGINES.md section 6](../architecture/COUNTERPARTY_ENGINES.md#6-the-ccr-cube-core-data-flow) and
  [CCR_CUBE_LENS.md section 6.7](../lenses/CCR_CUBE_LENS.md#67-mock-ups-the-screens-to-build).
