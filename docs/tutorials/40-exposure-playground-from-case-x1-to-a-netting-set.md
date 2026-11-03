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
# Tutorial 40 · The Exposure Playground from case X1 to a netting set

The Exposure Playground shows, for one netting set, how the exposure engine gets from the bank's simulated paths to EE, PFE, EPE and
EEPE. The paths are the data, and every step after them is a spreadsheet formula. In this tutorial you work case X1 by hand and in the
playground, read each tab of its workbook, change a choice and watch the checks, then open a demo netting set. There you choose how many
trades get their own columns (the rest go to Rest of set), read the paths at one date with every collateral step, and see the trades'
contributions add up to EE. Each step shows the UI, the command line and the YAML behind it. The reference is
[EXPOSURE_GUIDE.md section 29](../guides/EXPOSURE_GUIDE.md#29-the-exposure-playground-play-exp).

## Time to complete

About 25 minutes.

## Before you start

- The exposure engine on (`drishti.engines.exposure.enabled`, Admin → Engines) and an edition with the `EXP` and `PLAY` words.
- For steps 5 to 7, the demo lake's exposure run (`drishti.py lake demo` builds it; [tutorial 26](26-exposure-from-the-bank-to-a-trade.md),
  step 1) and a role that sees the counterparty-risk kinds at trade level. Steps 1 to 4 need no data: case X1 is built in.
- LibreOffice, Excel or Google Sheets to open the workbook.

## What you will learn

- the order of the steps: net per path, collateral per path, floor at zero per path, and only then the average and the percentile;
- how a CSA's thresholds, minimum transfer amount and look-back turn a path's value into collateral;
- why PFE uses `SMALL` at the engine's rank and not Excel's `PERCENTILE`;
- how the workbook proves itself: each figure beside the engine's with a check, and the band at the top of Main;
- how a large netting set fits a spreadsheet: the first N paths, the largest trades, and Rest of set.

## Step 1 · Case X1 by hand

Case X1 has two trades, A and B, on five paths. There are four quarterly dates and a CSA whose thresholds are 1 on each side and whose
counterparty MTA is 0.5 (amounts in USD millions). At the second date (6M), V = A + B on each path is 2, 2, 1, 3 and 1. The collateral is
the CSA's call at the look-back value L, a margin period of risk earlier. On path 4, L = 2.5, so the call is max(2.5 − 1, 0) = 1.5, and
after the MTA 1.0 is held. On path 1, L = 1.5 gives a call of 0.5, which the MTA takes to 0. The exposures are max(V − C, 0) = 2, 2, 1,
2 and 1, so EE = 8 / 5 = **1.6**. Do the same at each date and you get EE 0.8, 1.6, 1.1 and 1.3. EPE averages them over the year:
(0.8 + 1.6 + 1.1 + 1.3) × 0.25 = **1.2**. PFE at 95 % is the ⌈5 × 0.95⌉ = 5th smallest exposure, the largest. At 9M it is **2.5**, the
peak.

**In the UI.** Type `PLAY EXP EXAMPLE <GO>` (or **Engines → Exposure → Exposure Playground**). The tiles read CE 0.4, EPE 1.2,
EEPE 1.4, peak EE 1.6 (6M), peak PFE 80% 2 (6M), peak PFE 95% 2.5 (9M) and EAD_IMM 1.96. The profile lists the four dates with EE,
ENE, PFE, EEE and the collateral effect. *Screenshot pending (taken with the next release's screenshots).*

**From the command line.**

```bash
python3 tools/drishti.py play exp EXAMPLE
```

```text
Exposure Playground · EXAMPLE-X1 · CP-EXAMPLE · 2026-09-30 · run X1 (hand-built) · the first 5 of 5 paths · 2 trades
choices: confidences [0.8, 0.95] · rank rule kth · collateral lagged · contribution date 6M
CE 0.4000 · T 1.0000 y · EPE 1.2000 · EEPE 1.4000 · peak EE 1.6000 (6M) · EAD_IMM 1.9600 (informational)
peak PFE 80% 2.0000 (6M)
peak PFE 95% 2.5000 (9M)
…
```

**The YAML.** Case X1 is built into the server, so there is no file. Its numbers are the design's
([COUNTERPARTY_ENGINES.md section 9](../architecture/COUNTERPARTY_ENGINES.md#case-x1-the-exposure-playground-one-netting-set-five-paths)).
The engine's own choices are settings:

```yaml
drishti:
  ccr:
    exposure:
      confidence: [0.95, 0.99]       # PFE confidences of every answer
      rank-rule: kth                 # the ceil(P q)-th smallest exposure
      collateral-model: lagged       # called at the look-back point
```

## Step 2 · The workbook, tab by tab

**In the UI.** Click **Template workbook** (or **Export to Excel**) and open the file. Read the tabs left to right:

| Tab | What to look at |
|---|---|
| Main | The band says **ALL CHECKS PASS**. Each headline figure has Workbook, Engine and Check columns, followed by the profile with one check per date |
| Config | The CSA (`THRESHOLD_THEM` 1, `MTA_THEM` 0.5), the two trades with today's MTM (0.3 and 0.1, so V0 = 0.4 and CE = 0.4), and the eight grid dates (four primary dates, each with its look-back point) |
| Data | One row per path and one column per grid date and trade: the only numbers typed in |
| Netting | `=SUM(Data!B4:C4)`: V at each date and L at each look-back date |
| Collateral | target, rounded and C; path 4 at 6M: target 1.5, C 1.0 |
| Exposure | E, N and max(V, 0) |
| Stats | `=AVERAGE(...)` for EE; `=SMALL(..., rank)` for PFE, with the rank `ROUNDUP(P*q-1E-9,0)` in the rows above |
| Contributions | Each trade's share at 6M: A 1.133333, B 0.466667, adding up to EE 1.6 |

**From the command line.**

```bash
python3 tools/drishti.py play exp --template x1.xlsx
```

**The YAML.** The workbook's look and words are a template kept in the repository: `drishti-server/src/main/resources/playground/exposure/template/`
(`template.properties`, `xl/styles.xml`, `docProps/*.xml`, `explain.txt`). A bank can use its own folder:

```yaml
drishti:
  ccr:
    exposure:
      playground:
        template: ""                 # blank: the built-in playground/exposure/template/
```

## Step 3 · Change a choice and watch the checks

**In the UI (in the workbook).** On Main, set **Collateral model** to `none`. Every figure recalculates: EE at 6M becomes 1.8 (the
uncollateralised value). The Engine column keeps the engine's answer to the exported question, so those checks turn red and the band
reads **A CHECK FAILS**. The add-up checks stay green: the contributions still add up to EE, and every Data cell is still a number. Set it
back to `lagged` and the band is green again. Then set **Rank rule** to `interpolated`: PFE 95 % at 9M becomes
(1 − 0.75) × 2 + 0.75 × 2.5 = 2.375 (P·q = 4.75, between the 4th and 5th smallest).

**In the playground.** Pick **Collateral → none** or **Rank rule → interpolated** in the toolbar. The server computes again and the page
redraws; the browser computes nothing.

**From the command line.**

```bash
python3 tools/drishti.py play exp EXAMPLE --collateral none
python3 tools/drishti.py play exp EXAMPLE --rule interpolated --q 0.95
```

**The YAML.** These choices are per request. The engine's defaults are `collateral-model` and `rank-rule` (step 1).

## Step 4 · Why not PERCENTILE

At 9M, case X1's exposures are 0, 0, 1, 2 and 2.5. `PERCENTILE.INC(…, 0.95)` gives 2.4, because it interpolates with its own
convention. The engine's 95 % PFE is the 5th smallest, 2.5. So the workbook writes the engine's rule with `SMALL` and `ROUNDUP`, the same
rank, with no approximation. **In the workbook**, put `=PERCENTILE.INC(Exposure!D4:D8,0.95)` in a spare cell and compare it with
Stats' PFE at 9M. [EXPOSURE_GUIDE.md section 29.5](../guides/EXPOSURE_GUIDE.md#295-pfe-in-excel-the-rank-rule-exactly) states the one
limit: Excel rounds to 15 digits before `ROUNDUP`.

## Step 5 · A demo netting set, Rest of set and the size

**In the UI.** Type `PLAY EXP NS-ALDERSHOT-LDN <GO>`. The header shows the run, its calibration, and how many of the run's paths are
taken (every path, up to `max-paths`). The **Trades** drop-down beside **Export to Excel** lists each choice (50, 500, 2,000, All) with
its cells, file size and recalculation time; hover for the applications that open it. A choice over the server's limit, or over
Excel's 16,384 columns, is disabled with the reason. Pick a number smaller than the set's trades: the largest trades by |EE
contribution| get their own columns and **Rest of set** carries the sum of the others, so V, and every figure after it, is still the
whole set's.

**From the command line.**

```bash
python3 tools/drishti.py play exp NS-ALDERSHOT-LDN --estimate
python3 tools/drishti.py play exp NS-ALDERSHOT-LDN --trades 5 --xlsx aldershot.xlsx
```

Open `aldershot.xlsx`. Main's band reads **ALL CHECKS PASS**, Config's trade table ends with `REST-OF-SET` and the number of trades it
counts, and Main's check *Trades shown + Rest of set = the set* is green.

**The YAML.**

```yaml
drishti:
  ccr:
    exposure:
      playground:
        max-paths: 5000              # the first paths of the run taken
        export-choices: ["50", "500", "2000", "All"]
        export-default: "500"
        export-max-cells: 20000000   # more is refused with the size
```

## Step 6 · The paths at a date

**In the UI.** Open the **Paths at a date** tab and pick 1Y. Each of the first 200 paths shows V, L (the value a margin period
earlier), the CSA's target, the rounded call, C, E, N and max(V, 0). Find a path where L is above the threshold: C is the call less the
MTA. A path where L is negative shows collateral the bank posted (C below zero), and its E can be larger than without collateral. Click
the **C** column head: the formula bar shows the workbook's formula for C.

**From the command line.** The API answers the same table: `GET /api/v1/exposure/playground?nettingSet=NS-ALDERSHOT-LDN&at=1Y`
(`pathTable`).

**The YAML.** `browser-paths: 200` sets how many rows the screen lists. The figures always use every path taken.

## Step 7 · The contributions add up to EE

**In the UI.** Open **Contributions**. The note reads *EE at … is …; the shares add up to …*, and the two numbers are equal. Each trade
keeps its value less its share of the collateral on the paths with exposure. A negative share marks a hedge. Pick another
**Contribution date** in the toolbar and the shares are computed again at that date. In the workbook, the same date is Main's
**Contribution date** cell, and changing it re-points the Contributions tab through `MATCH` and `INDEX`.

**From the command line.**

```bash
python3 tools/drishti.py play exp NS-ALDERSHOT-LDN --contribution-date 1Y
```

**The YAML.**

```yaml
drishti:
  ccr:
    exposure:
      playground:
        contribution-date: peak      # or a tenor, 1Y
        browser-trades: 50           # the contributions the screen lists
```

## What you did

You worked a netting set's exposure by hand, then read the same steps in the playground and in a workbook that recalculates and checks
itself against the engine. You saw the order that matters (net, collateralise and floor per path, then average), the CSA's look-back
and minimum transfer amount, the engine's rank rule written exactly in Excel, and how Rest of set keeps a large set whole. Next:
[EXPOSURE_GUIDE.md section 29](../guides/EXPOSURE_GUIDE.md#29-the-exposure-playground-play-exp) for every tab and setting, and
[tutorial 26](26-exposure-from-the-bank-to-a-trade.md) for exposure from the bank down to a trade.

---

*Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. Unauthorised copying, use or distribution is prohibited.*
