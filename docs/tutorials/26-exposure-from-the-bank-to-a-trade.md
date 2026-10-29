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
# Tutorial 26 · Exposure from the bank to a trade

Counterparty exposure answers *if this counterparty defaulted at some date in the future, how much would we lose, on an average path
and on a bad one?* In this tutorial you build the counterparty-risk pack's exposure run into a lake, open the bank's exposure profile,
read a legal entity's counterparty board, open CP-ALDERSHOT and its four netting sets, look at how collateral changes
NS-ALDERSHOT-LDN's exposure and which trades make it, compare a trade alone with the same trade inside its netting set, capture a
counterparty and compare it, ask what an unwind would do before dealing (the exact `paths` method and the `addon` proxy), and read the
official end-of-day record. Each step shows the UI, the command line (`drishti.py exposure …`) and the YAML behind it, with real output
from the demo lake. Everything else about exposure (every measure, column, setting, endpoint and error code, the data contract and how
the engine stores a date) is in the one reference, [EXPOSURE_GUIDE.md](../guides/EXPOSURE_GUIDE.md); each step ends with the guide's
sections that go further.

## Time to complete

About 35 minutes.

## Before you start

- [Tutorial 1](01-first-15-minutes.md) done: a server and console you can build, start and stop.
- The counterparty-risk and core packs loaded (they are in the quick-start packs).
- For the command-line steps: `DRISHTI_SERVER=http://localhost:18480` and `DRISHTI_TOKEN=...` (My account → API tokens), or `--user`
  with sign-in off. The outputs below were captured as the user `rita` with sign-in off.
- For step 7's exact method only: the compute workers on (`DRISHTI_COMPUTE_ENABLED=true`; a Python 3.10 or newer, the console's own is
  enough: the exposure engine uses the standard library). Without them the `paths` method answers `503 DRS-5446` and the `addon` proxy
  still works.

The exposure paths are **synthetic** (one correlated factor per asset class and a walk of each trade's own, made by
`tools/packgen/ccr/`), not a bank's simulator.

## What you will learn

- where the exposure inputs live (every trade's MtM on 200 paths at 24 grid dates) and how a date is loaded into a hot file;
- what EE, ENE, EEE, PFE, EPE, EEPE and EAD mean on real numbers;
- how to walk from the bank to a legal entity, a counterparty, a netting set and a trade without a page load;
- why collateral can raise exposure (when the bank has posted it) and how the path fan shows the spread;
- the difference between a trade alone and its contribution inside its netting set (the netting benefit);
- scopes with `+`, `-` and `&`; captures and compare;
- a pre-deal what-if by the exact `paths` method (the Python engine, `XWIF`) and by the conservative `addon` proxy;
- the official end-of-day record and where it is published.

## Step 1 · Build the exposure data and open the bank

**From the command line.** `lake demo` generates the exposure run of the demo (`--exposure demo`, the default: one run
`XR-2026-09-30-01` of 200 paths on a 24-date grid for 74 netting sets of 23 counterparties, 8,022 live trades). Build it with the reference
tables beside it, on the data's own date (2026-09-30), so your numbers are the ones below:

```bash
python3 tools/drishti.py lake demo --as-of data \
  --only counterparty-risk/simulation-run,counterparty-risk/exposure-path,counterparty-risk/netting-set,counterparty-risk/csa,counterparty-risk/collateral-balance,counterparty-risk/credit-limit,market-risk/trade-valuation,core/counterparty,core/counterparty-group,core/legal-entity
```

(`--exposure committed` loads only the eight netting sets committed in `data/testdata/`; their figures are the same.) Start the server
and console (tutorial 1), then ask for the bank:

```text
$ drishti.py exposure show BANK
EXP BANK  ·  Bank  ·  bank  ·  2026-09-30
  peak PFE 95%   2,897,620,439 USD at 2W (2026-10-14)   PFE 99% 3,146,370,255
  EPE 947,202,991   EEPE 2,270,175,837   peak EE 2,275,263,996   CE 1,586,811,699   EAD (IMM, info) 3,178,246,172
  run XR-2026-09-30-01, 200 paths, collateral lagged, horizon 1.0 years, flags PAST_MATURITY
Tenor  Date        EE             ENE             EEE            PFE            PFE 99         EE uncoll
-----  ----------  -------------  --------------  -------------  -------------  -------------  -------------
1W     2026-10-07  2,009,952,855    -973,847,220  2,009,952,855  2,360,837,427  2,626,642,921  6,202,152,090
2W     2026-10-14  2,275,263,996  -1,138,249,392  2,275,263,996  2,897,620,439  3,146,370,255  6,304,130,477
1M     2026-10-30  1,220,520,495  -1,121,964,327  2,275,263,996  1,834,387,323  2,032,204,828  6,376,828,794
...
5Y     2031-09-30    285,855,833    -223,718,415  2,275,263,996    389,782,505    426,362,686  3,412,206,681
10Y    2036-09-30     88,977,137     -73,442,989  2,275,263,996    112,596,428    118,076,387    241,278,675
  74 netting sets, 8022 trades · hot file (float32 netting-set matrices, summed in float64) · 7 ms (miss)
```

(Thirteen primary dates are printed; a few rows are shown.) `drishti.py exposure status` shows how the date was loaded: 112,381 rows of
8,022 trades, 74 netting sets, none rejected, a 3.0 MB matrix in a 5.3 MB hot file, loaded in about 1.3 s; and the two netting sets the
run does not cover (`NS-SUMMIT-LDN`, `NS-SUMMIT-NY`). The trades are the live ones: since 2.9.2 a netting set holds no cancelled or
matured trade, so fewer trades fit than before and only CP-SUMMIT stays without paths under the 9,500-trade demo limit.

**In the UI.** Type `EXP BANK` and press Enter (or open `/exposure`):

![EXP BANK: the scope tree, the headline tiles, the bank's profile and the counterparty board](img/t26/01-bank.jpg)

The command line at the top carries the PFE confidence (q 95% or 99%), collateralised or uncollateralised, and the official-capture chip;
the tree on the left holds the bank, its legal entities, counterparties, netting sets and an open set's trades, each with its peak PFE.

**What it reads.** The counterparty-risk pack's connector `exposure-store` keeps the paths as a vector column of the `exposure-path`
table (`config/packs/counterparty-risk/pack.yaml`):

```yaml
connectors:
  exposure-store:
    plugin: delta
    kinds: [simulation-run, exposure-path]
    settings:
      domain: risk
      layout:
        simulation-run:
          columns: [RUN_ID, CALIBRATION, PATHS, STATUS, BATCH_ID]
        exposure-path:
          columns: [TRADE_ID, NETTING_SET, COUNTERPARTY, LEGAL_ENTITY, DESK, TT_BOOK, ASSET_CLASS, PRODUCT, SOURCE_SYSTEM,
                    MATURITY_DATE, NOTIONAL, GRID_INDEX, GRID_DATE, RUN_ID, BATCH_ID, STATUS]
          vectors: [MTM]
```

**You should see** a peak PFE 95% of 2,897,620,439 at two weeks and an EPE of 947,202,991 (since 2.9.6 the collateral is counted after
haircut and the members are the trade valuations' live trades; the flag `PAST_MATURITY` says some sets hold a live-status trade past its
maturity date, left out). The bank's EE uncollateralised (6.3 billion at
2W) is almost three times its collateralised EE (2.2 billion): collateral removes most of the bank's exposure.

**Further in the guide:** [section 1](../guides/EXPOSURE_GUIDE.md#1-the-question-exposure-answers) (the question),
[section 2](../guides/EXPOSURE_GUIDE.md#2-the-measures-with-worked-numbers) (the measures with worked numbers),
[section 15](../guides/EXPOSURE_GUIDE.md#15-inputs-from-upstream-engines) (the inputs).

## Step 2 · A legal entity's counterparty board

**In the UI.** Click `LE-LDN` in the tree. The tabs follow the level: at a legal entity, **Counterparties** (the board: each
counterparty's EE sparkline, peak PFE, EPE, its stored credit limit and how much of it is used, its netting sets) and **By tenor**. At the
bank there is also **Legal entities**.

![LE:LE-LDN: the London entity's profile and its counterparty board with limit use](img/t26/02-legal-entity-board.jpg)

**From the command line**, the whole tree with limit use, then the entity and its counterparties' contributions:

```text
$ drishti.py exposure tree
2026-09-30  run XR-2026-09-30-01  q 0.95  netting sets with paths 74 of 76
Node                                Peak PFE       EPE          Limit used
----------------------------------  -------------  -----------  ----------
Bank                                2,897,620,439  947,202,991
  LE-FRA                              126,314,351   20,293,496
  LE-LDN                              558,766,819  275,188,101
  LE-NY                             2,192,334,926  489,560,704
  LE-TKY                              344,315,638  173,614,005
    Aldershot Pension Trust           401,456,810   64,977,553        5.0%
      NS-ALDERSHOT-FRA                 30,534,120    1,832,126
      NS-ALDERSHOT-LDN                 85,063,192   14,729,920
      NS-ALDERSHOT-NY                 357,381,440   37,446,771
      NS-ALDERSHOT-TKY                 61,396,880   12,002,758
...
    Summit Clearing LLC                  no paths            —
      NS-SUMMIT-LDN                      no paths            —
      NS-SUMMIT-NY                       no paths            —
$ drishti.py exposure parts LE:LE-LDN --by counterparty --top 4
LE:LE-LDN  by counterparty  PFE 558,766,819 at 2026-11-30
Part                       Contribution  % of total  Incremental
-------------------------  ------------  ----------  -----------
Bramble Asset Management     63,698,479        11.4   45,048,531
Kestrel Global Macro Fund    58,241,106        10.4   23,247,331
Meridian Reinsurance Ltd     49,829,212         8.9   13,073,419
Solaris Energy Corp.         41,436,951         7.4   29,674,485
```

**What it means.** London's peak PFE (558.8 million at 2M) is far below the sum of its counterparties' own peaks: their bad paths are
not the same paths and their peaks fall on different dates. The contributions are the paths around London's 95% quantile at its peak
date, scaled to add up to it. The limit column reads the pack's stored `credit-limit` rows as stored: they were sized on the retired
stored profiles, so the use is low (5.0% for Aldershot).

**You should see** LE-LDN at 558,766,819 with 17 netting sets and 2,403 trades (`drishti.py exposure show LE:LE-LDN`), Bramble the
largest contributor, and one counterparty without paths (Summit Clearing) marked *no paths*.

**Further in the guide:** [section 3](../guides/EXPOSURE_GUIDE.md#3-the-explorer-screen-by-screen) (the explorer, the board),
[section 18](../guides/EXPOSURE_GUIDE.md#18-the-computation) (contributions), [section 13](../guides/EXPOSURE_GUIDE.md#13-troubleshooting) (*no paths*).

## Step 3 · CP-ALDERSHOT and its netting sets

**In the UI.** Click *Aldershot Pension Trust* on the board (or type `EXP CPTY:CP-ALDERSHOT`). The breadcrumb grows (Bank › legal
entity › counterparty), the browser's Back steps back up, and the **Netting sets** tab lists the four sets with their CSA, EE sparkline,
EPE, peak PFE alone and PFE contribution:

![CPTY:CP-ALDERSHOT: the counterparty's tiles with its limit use, its profile and its four netting sets](img/t26/03-counterparty-sets.jpg)

**From the command line:**

```text
$ drishti.py exposure show CPTY:CP-ALDERSHOT
EXP CPTY:CP-ALDERSHOT  ·  Aldershot Pension Trust  ·  counterparty  ·  2026-09-30
  peak PFE 95%   401,456,810 USD at 2W (2026-10-14)   PFE 99% 425,829,244
  EPE 64,977,553   EEPE 263,701,831   peak EE 264,093,896   CE 220,250,163   EAD (IMM, info) 369,182,563
  limit 7,980,000,000 USD (LIM-ALDERSHOT, PFE 95 peak)   used 5.0%
  run XR-2026-09-30-01, 200 paths, collateral lagged, horizon 1.0 years, flags PAST_MATURITY
...
  4 netting sets, 469 trades · hot file (float32 netting-set matrices, summed in float64) · 0 ms (hit)
$ drishti.py exposure parts CPTY:CP-ALDERSHOT
CPTY:CP-ALDERSHOT  by netting-set  PFE 401,456,810 at 2026-10-14
Part                             Contribution  % of total  Incremental
-------------------------------  ------------  ----------  -----------
NS-ALDERSHOT-NY · CP-ALDERSHOT    349,983,112        87.2  282,704,741
NS-ALDERSHOT-TKY · CP-ALDERSHOT    22,890,610         5.7   25,249,520
NS-ALDERSHOT-FRA · CP-ALDERSHOT    20,237,212         5.0   28,749,801
NS-ALDERSHOT-LDN · CP-ALDERSHOT     8,345,877         2.1   12,441,316
```

A counterparty's netting sets never net against each other: its paths are the **sum of each set's exposure**, path by path. Scopes
combine with `+`, `-` and `&`:

```text
$ drishti.py exposure show 'CPTY:CP-ALDERSHOT - NSET:NS-ALDERSHOT-NY'
EXP CPTY:CP-ALDERSHOT - NSET:NS-ALDERSHOT-NY  ·  CPTY:CP-ALDERSHOT - NSET:NS-ALDERSHOT-NY  ·  adhoc  ·  2026-09-30
  peak PFE 95%   118,752,069 USD at 2M (2026-11-30)   PFE 99% 131,150,464
  ...
  3 netting sets, 215 trades · hot file (float32 netting-set matrices, summed in float64) · 1 ms (miss)
$ drishti.py exposure show 'LE:LE-LDN & CPTY:CP-ALDERSHOT'
EXP LE:LE-LDN & CPTY:CP-ALDERSHOT  ·  LE:LE-LDN & CPTY:CP-ALDERSHOT  ·  adhoc  ·  2026-09-30
  peak PFE 95%   85,063,192 USD at 2M (2026-11-30)   PFE 99% 125,420,944
  ...
  1 netting sets, 159 trades · hot file (float32 netting-set matrices, summed in float64) · 1 ms (miss)
```

The group `GROUP:GRP-ALDERSHOT` has one counterparty, so its figures are Aldershot's.

**You should see** a peak PFE of 401,456,810 at 2W, EEPE 263,701,831, EAD (α 1.4 × EEPE, informational) 369,182,563, and New York
making 87.2% of the peak. Without New York the peak falls to 118.8 million and moves to 2M; London and Aldershot intersect in one set,
NS-ALDERSHOT-LDN.

**Further in the guide:** [section 4](../guides/EXPOSURE_GUIDE.md#4-scopes) (scopes and the grammar),
[section 2](../guides/EXPOSURE_GUIDE.md#2-the-measures-with-worked-numbers) (EEPE and EAD).

## Step 4 · NS-ALDERSHOT-LDN: collateral and trades

**In the UI.** Click `NS-ALDERSHOT-LDN`. At a netting set the chart adds the **path fan** (the 5–95% and 25–75% bands of the 200 paths),
and the tabs are **Collateral**, **Trades**, **Pre-deal**, **By tenor** and **Captures**. The **Collateral** tab shows the CSA's terms
(variation margin, a 10-business-day margin period, thresholds of 10 million each way, minimum transfers of 500 thousand) beside what is
held today and its effect at the peak date:

![NS-ALDERSHOT-LDN: the profile with the path fan and the Collateral tab](img/t26/04-collateral.jpg)

The **Trades** tab lists the set's 159 trades, paged, each with its EE contribution, share and peak PFE alone; click a trade to open it:

![NS-ALDERSHOT-LDN's trades with their EE contribution and peak PFE alone](img/t26/05-trades.jpg)

**From the command line**, the set with and without collateral, and the distribution of its 200 paths at one year:

```text
$ drishti.py exposure show NSET:NS-ALDERSHOT-LDN
EXP NSET:NS-ALDERSHOT-LDN  ·  NS-ALDERSHOT-LDN · Aldershot Pension Trust  ·  netting-set  ·  2026-09-30
  peak PFE 95%   85,063,192 USD at 2M (2026-11-30)   PFE 99% 125,420,944
  EPE 14,729,920   EEPE 18,756,782   peak EE 19,520,460   CE 0   EAD (IMM, info) 26,259,495
  run XR-2026-09-30-01, 200 paths, collateral lagged, horizon 1.0 years
Tenor  Date        EE          ENE          EEE         PFE         PFE 99       EE uncoll
-----  ----------  ----------  -----------  ----------  ----------  -----------  ----------
1W     2026-10-07   2,042,786  -38,521,266   2,042,786  14,675,361   34,375,272           0
2W     2026-10-14   5,808,311  -41,832,018   5,808,311  35,021,500   68,056,464           0
1M     2026-10-30  15,744,605  -22,167,433  15,744,605  63,596,712   91,457,504     343,409
2M     2026-11-30  19,520,460  -23,735,800  19,520,460  85,063,192  125,420,944   3,535,713
...
  1 netting sets, 159 trades · hot file (float32 netting-set matrices, summed in float64) · 0 ms (hit)
$ drishti.py exposure show NSET:NS-ALDERSHOT-LDN --collateral none
EXP NSET:NS-ALDERSHOT-LDN  ·  NS-ALDERSHOT-LDN · Aldershot Pension Trust  ·  netting-set  ·  2026-09-30
  peak PFE 95%   478,546,912 USD at 3Y (2029-09-28)   PFE 99% 648,609,088
  EPE 32,022,633   EEPE 32,022,633   peak EE 101,189,118   CE 0   EAD (IMM, info) 44,831,686
  run XR-2026-09-30-01, 200 paths, collateral none, horizon 1.0 years
...
$ drishti.py exposure distribution NSET:NS-ALDERSHOT-LDN --at 1Y
NSET:NS-ALDERSHOT-LDN at 1Y (2027-09-30): mean 16,654,426, 46.0% of 200 paths without exposure
  quantiles  50% 2,448,596   90% 50,876,264   95% 58,929,324   97.5% 73,162,384   99% 90,130,992
...
$ drishti.py exposure parts NSET:NS-ALDERSHOT-LDN --by trade --top 3
NSET:NS-ALDERSHOT-LDN  by trade  PFE 85,063,192 at 2026-11-30
Part                                    Contribution  % of total  Incremental
--------------------------------------  ------------  ----------  -----------
MX-30004272 · Convertible bond           289,693,262       340.6  -21,010,330
MX-30001587 · Equity total return swap   151,896,279       178.6  -34,428,116
MX-30002479 · Portfolio (basket) swap   -149,124,302      -175.3   -9,886,077
```

`drishti.py exposure profile NSET:NS-ALDERSHOT-LDN --csv` writes the profile as CSV (the UI's *Download the profile (CSV)*).

**What it means.** The set's net MtM today is −139.1 million (the bank owes Aldershot) and the bank has **posted** 111.1 million (after haircut) of
collateral. Early on, the uncollateralised EE is zero (the set is far out of the money) but the collateralised EE is not: on the paths
where the MtM recovers within the margin period, the posted collateral is owed back. That is why the Collateral tab's *Effect of collateral* at 2M
is **+452%** (EE 19.5 million with collateral against 3.5 million without; the bank's *Collateral effect* tile, by contrast, reads **−65%**:
collateral cuts the bank's EE at 2W by 65%). Later, collateral works for the bank: without it the peak PFE is 478.5 million at 3Y, with it 85.1 million at 2M. Trades'
contributions inside a set can be large and of either sign; they add up to the set's figure.

**You should see** EE 19,520,460 with collateral and 3,535,713 without at 2M, the peak PFE 85,063,192 at 2M, and at 1Y 46.0% of the paths
without exposure.

**Further in the guide:** [section 5](../guides/EXPOSURE_GUIDE.md#5-collateral-and-netting) (collateral and netting),
[section 18](../guides/EXPOSURE_GUIDE.md#18-the-computation) (collateral on each path, the formulas).

## Step 5 · A trade alone and in its netting set

**In the UI.** On the **Trades** tab click `MX-30001587` (an equity total return swap). The trade's tiles are its **peak PFE alone** (as if
it had no netting set), its **EE contribution** to the set at the set's peak date, the **netting benefit** (alone minus contribution) and
its desk. The chart is the trade alone; the **In its netting set** tab shows its contribution to NS-ALDERSHOT-LDN tenor by tenor:

![TRD:MX-30001587: the trade alone, its EE contribution in its netting set and the netting benefit](img/t26/06-trade-in-set.jpg)

**From the command line:**

```text
$ drishti.py exposure show TRD:MX-30001587
EXP TRD:MX-30001587  ·  MX-30001587 · Equity total return swap  ·  trade  ·  2026-09-30
  peak PFE 95%   126,221,133 USD at 2Y (2028-09-29)   PFE 99% 147,956,650
  EPE 22,960,827   EEPE 22,960,827   peak EE 31,360,953   CE 9,227,785   EAD (IMM, info) 32,145,157
  run XR-2026-09-30-01, 200 paths, collateral lagged, horizon 1.0 years, flags standalone
...
  1 netting sets, 1 trades · hot file (float32 netting-set matrices, summed in float64); trade paths from the lake · 4 ms (hit)
$ drishti.py exposure parts NSET:NS-ALDERSHOT-LDN --by trade --figure EE --at 2M --top 3
NSET:NS-ALDERSHOT-LDN  by trade  EE 19,520,460 at 2026-11-30
Part                                   Contribution  % of total  Incremental
-------------------------------------  ------------  ----------  -----------
MX-30004272 · Convertible bond           31,748,304       162.6            —
CLY-4002222 · Quanto option             -14,211,431       -72.8            —
MX-30002479 · Portfolio (basket) swap   -12,990,168       -66.5            —
```

A trade's figures alone are flagged `standalone`: the trade's paths are read from the lake (the hot file holds netting-set matrices),
which is why the first ask takes longer.

**You should see** the swap's peak PFE alone 126,221,133 at 2Y, and its EE contribution in the set **12,901,854** at 2M (66.1% of the
set's EE, the 7th largest; `--top 10` lists it): alone it would carry 126 million of potential exposure, inside its netting set the
other trades offset most of it. The largest contributions at 2M are a convertible bond's +31.7 million and two offsets, a quanto option
(−14.2 million) and a basket swap (−13.0 million).

**Further in the guide:** [section 5](../guides/EXPOSURE_GUIDE.md#5-collateral-and-netting) (netting, the benefit),
[section 18](../guides/EXPOSURE_GUIDE.md#18-the-computation) (Euler contributions).

## Step 6 · Capture and compare

**In the UI.** On `CPTY:CP-ALDERSHOT`, open **Captures**, type a note (*before the unwind*) and press **Capture exposure**. The capture is
listed with its peak PFE and EPE; **Compare with now** puts it beside the current figures, PFE tenor by tenor; **CSV** downloads it:

![A capture of CP-ALDERSHOT compared with now](img/t26/07-capture-compare.jpg)

**From the command line:**

```text
$ drishti.py exposure capture CPTY:CP-ALDERSHOT --note 'before the unwind'
{
  "id": "XCAP-20260930-1",
  "capturedAt": "2026-10-26T13:06:48.857460484Z",
  "epe": 64977553,
  "peakPfe": 401456810,
  "memberCount": 469
}
$ drishti.py exposure captures
[
  {
    "id": "XCAP-20260930-1",
    "scope": "CPTY:CP-ALDERSHOT",
    "label": "Aldershot Pension Trust",
    "businessDate": "2026-09-30",
    "owner": "rita",
    "note": "before the unwind",
    "confidence": 0.95,
    "epe": 64977553,
    "peakPfe": 401456810,
    ...
  }
]
$ drishti.py exposure compare XCAP-20260930-1 now        (abridged)
{
  "a": {"id": "XCAP-20260930-1", "peakPfe": 401456810, "epe": 64977553, "memberCount": 469, "dataVersion": "exposure-store=16888440e052bf4f", ...},
  "b": {"id": "now", "peakPfe": 401456810, "epe": 64977553, ...},
  "change": {"ce": 0.0, "epe": 0.0, "eepe": 0.0, "peakEe": 0.0, "peakPfe": 0.0, "eadImm": 0.0},
  "membersAdded": [], "membersRemoved": [],
  "pfeProfile": [{"tenor": "1W", "a": 333466552, "b": 333466552, "change": 0.0}, ...]
}
```

A capture keeps the scope's members (469 trades), the figures and the data version; compare lists trades added and removed. Captures are
yours: another user's capture answers `404 DRS-1001 no exposure capture … you may read`.

**You should see** no change against now (the run has not changed); after tomorrow's run, or another lake, the change column fills in.

**Further in the guide:** [section 6](../guides/EXPOSURE_GUIDE.md#6-captures-and-compare) (captures and compare),
[section 9](../guides/EXPOSURE_GUIDE.md#9-who-sees-what-entitlements-and-audit) (whose captures you read).

## Step 7 · Before dealing: a what-if by paths and by the addon

**In the UI.** On `NSET:NS-ALDERSHOT-LDN`, open **Pre-deal**. Pick a trade to copy (*Candidate: like trade* `MX-30001587`), a **factor**
of `-1` (its unwind), the **method** and whose figures to show (*its counterparty (the credit limit's)*), then **Run what-if**. With
`paths` the chip says *exact*; the result lists the peak PFE and EPE before and after, the incremental PFE by tenor and the limit headroom
after:

![Pre-deal by paths: the unwind of MX-30001587, exact, on CP-ALDERSHOT](img/t26/08-predeal-paths.jpg)

The same with the method `addon`: the chip says *proxy*:

![Pre-deal by the addon: the same unwind as a netting set of its own, uncollateralised](img/t26/09-predeal-addon.jpg)

The same pre-deal what-if is on the counterparty too: on `CPTY:CP-ALDERSHOT` open **Pre-deal**, pick *Into the netting set* (its
largest by peak PFE comes first), the candidate and the factor, and read the counterparty's figures before and after
([EXPOSURE_GUIDE.md section 7](../guides/EXPOSURE_GUIDE.md#7-pre-deal-what-if)).

**From the command line**, the exact method:

```text
$ drishti.py exposure whatif CPTY:CP-ALDERSHOT --like MX-30001587 --factor -1 --set NS-ALDERSHOT-LDN
what-if on CPTY:CP-ALDERSHOT  method paths  exact  run XR-2026-09-30-01
Figure    Before       After        Incremental
--------  -----------  -----------  -----------
Peak PFE  401,456,810  398,357,466   -3,099,344
EPE        64,977,553   64,362,265     -615,288
Peak EE   264,093,896  261,258,147   -2,835,749
  exact: each candidate's paths on the run XR-2026-09-30-01 added to its netting set path by path, collateral and exposure computed again by the Python exposure engine (exposure-whatif)
```

and the proxy (`--like` sends the trade's asset class, notional, maturity, MtM and delta, read from its netting set's contributions:
the addon is sized on them):

```text
$ drishti.py exposure whatif CPTY:CP-ALDERSHOT --like MX-30001587 --factor -1 --set NS-ALDERSHOT-LDN --method addon
what-if on CPTY:CP-ALDERSHOT  method addon  proxy  run XR-2026-09-30-01
Figure    Before       After        Incremental
--------  -----------  -----------  -----------
Peak PFE  401,456,810  957,232,427  555,775,617
EPE        64,977,553  137,421,691   72,444,139
Peak EE   264,093,896  283,307,935            —
  a conservative proxy: each candidate a netting set of its own, uncollateralised, with SA-CCR's supervisory factor; the exact method (paths) runs in the Python exposure engine
```

A candidate that is not a copy of a booked trade comes from a candidates file with the same terms; this one gives the same answer:

```json
[{"tradeId": "CANDIDATE-1", "label": "the unwind of MX-30001587", "nettingSet": "NS-ALDERSHOT-LDN",
  "paths": {"copyOf": "MX-30001587", "factor": -1},
  "assetClass": "Equity", "notional": 768495000, "maturityDate": "2031-10-20", "mtm": -9227785}]
```

```text
$ drishti.py exposure whatif CPTY:CP-ALDERSHOT --candidates unwind.json --method addon
what-if on CPTY:CP-ALDERSHOT  method addon  proxy  run XR-2026-09-30-01
Figure    Before       After        Incremental
--------  -----------  -----------  -----------
Peak PFE  401,456,810  957,232,427  555,775,617
EPE        64,977,553  137,421,691   72,444,139
Peak EE   264,093,896  283,307,935            —
  a conservative proxy: each candidate a netting set of its own, uncollateralised, with SA-CCR's supervisory factor; the exact method (paths) runs in the Python exposure engine
```

The exact method is the Python exposure engine, the computed kind `exposure-whatif` (`XWIF`) on the compute pool `exposure`
(`config/compute/compute.yaml`):

```yaml
compute:
  enabled: ${DRISHTI_COMPUTE_ENABLED:false}
  pools:
    exposure:                      # the Python exposure engine (the exact what-if)
      type: python
      engines: [drishti_compute.exposure.whatif:ExposureWhatIfEngine]
      environment: system          # standard library only
      memory: 2GB
```

**What it means.** Unwinding the swap barely moves Aldershot's peak (−3,099,344) and its EPE (−615,288): inside its netting set the
other trades already offset most of its exposure, so taking it out removes little. The proxy cannot see that: it puts the candidate in a
netting set of its own, uncollateralised, sized by SA-CCR's supervisory factor on its notional, so an unwind *adds* 557.3 million. Use `addon` as a
conservative first answer when the compute workers are off; `paths` is the answer.

**You should see** `exact` with −3,099,344 by paths, `proxy` with +555,775,617 by the addon. With the compute workers off, `paths` answers
`503 DRS-5446` (the Python exposure engine is not running).

**Further in the guide:** [section 7](../guides/EXPOSURE_GUIDE.md#7-pre-deal-what-if) (pre-deal, the two methods),
[section 8](../guides/EXPOSURE_GUIDE.md#8-exposure-as-a-kind) (`exposure-result` and `exposure-whatif` as kinds),
[section 12](../guides/EXPOSURE_GUIDE.md#12-settings) (the compute pool).

## Step 8 · The official end-of-day results

**In the UI.** The chip *official 13:06* on the command line says the business date has its official record, captured when the date's
run was complete (at start, `on-start: true`, once the lake's connectors are up). Every netting set, counterparty, group and hierarchy
node of the date is captured at q 95%, collateral lagged, as the system (not as a viewer).

**From the command line** (abridged):

```text
$ drishti.py exposure official
{
  "businessDate": "2026-09-30",
  "resultVersion": 1,
  "computedAt": "2026-10-26T13:06:17.539118436Z",
  "contractVersion": "1.1",
  "runId": "XR-2026-09-30-01",
  "confidence": 0.95,
  "collateralModel": "lagged",
  "trades": "exposure/official/2026-09-30-trades-v1.jsonl",
  "scopes": {
    "NSET:NS-ALDERSHOT-FRA": {"ce": 0, "epe": 1832126, "eepe": 18357151, "peakEe": 19075328, "peakPfe95": 30534120, ...},
    ...
    "CPTY:CP-ALDERSHOT": {"ce": 220250163, "epe": 64977553, "eepe": 263701831, "peakPfe95": 401456810, "peakPfe99": 425829244,
                          "peakPfeDate": "2026-10-14", "eadImm": 369182563, "pfe95At1Y": 130105296, "pfe95At5Y": 52495669, ...},
    ...
  }
}
$ drishti.py exposure official --capture
{
  "businessDate": "2026-09-30",
  "status": "unchanged",
  "resultVersion": 1
}
$ drishti.py exposure retention        (abridged)
{
  "tradePathsDays": 20, "tradePathsMonthEndYears": 1, "setMatricesYears": 1, "setMatricesMonthEndYears": 7,
  "statisticsYears": 7, "tradeContributionsYears": 7,
  "dates": [{"businessDate": "2026-09-30", "monthEnd": true, "tradePaths": "keep", "setMatrices": "keep", "statistics": "keep", "tradeContributions": "keep"}],
  "tradePathsBeyond": []
}
```

The record holds 124 scopes (74 netting sets, 23 counterparties, 22 groups, the four legal entities and the bank's management node)
and, since 2.9.5, the 43 nodes of the limit letters, a hierarchy built from the exposure paths' `LIMIT_LETTER` and `DESK` (167 in all;
with the market-risk pack's `risk-hierarchy` in the lake too, every MGMT and LEGAL node is captured as well: 250 scopes on the test data,
`LL:LL~LL-0011` among them). It is also appended to the tables `risk-results/exposure-official` and `risk-results/exposure-official-cpty`
of the lake: Delta by default (`"published": "… (Delta version 0)"`), Iceberg with `drishti.ccr.exposure.official.format: iceberg`
([EXPOSURE_GUIDE.md section 20](../guides/EXPOSURE_GUIDE.md); [tutorial 29](29-var-on-iceberg.md) for VaR's). A
second capture of the same run is `unchanged`; a new run of the date makes `resultVersion` 2. `--capture` is for administrators.

The settings (`drishti-server/src/main/resources/application.yaml`):

```yaml
drishti:
  ccr:
    exposure:
      official:
        enabled: ${DRISHTI_EXPOSURE_OFFICIAL:true}
        publish: true                # append to <root>/risk-results/exposure-official and exposure-official-cpty
        retention-years: 7
        on-start: true
```

**You should see** `resultVersion` 1 for 2026-09-30, CP-ALDERSHOT's official peak PFE 401,456,810 equal to step 3's, and the two
published tables in the lake.

**Further in the guide:** [section 20](../guides/EXPOSURE_GUIDE.md#20-official-end-of-day-results) (the official results),
[section 27](../guides/EXPOSURE_GUIDE.md#27-sizes-performance-and-retention) (retention),
[section 10](../guides/EXPOSURE_GUIDE.md#10-the-command-line) (every `drishti.py exposure` command).

## Next steps

- Read [EXPOSURE_GUIDE.md](../guides/EXPOSURE_GUIDE.md): the measures by hand, the explorer screen by screen, the scope grammar, the
  API ([section 11](../guides/EXPOSURE_GUIDE.md#11-the-api)), the data contract (Part II) and how a date is held in memory and on disk
  ([section 26](../guides/EXPOSURE_GUIDE.md#26-one-date-in-memory-and-the-hot-file)).
- Load your own simulator's run: the columns are in [EXPOSURE_GUIDE.md section 15](../guides/EXPOSURE_GUIDE.md#15-inputs-from-upstream-engines);
  send the data-load signal ([tutorial 7](07-load-data-daily.md)) and the date is reloaded and captured officially.
- Compare with VaR: [tutorial 25](25-var-from-the-bank-to-a-trade.md) walks the same way from the bank to a trade for market risk.

## When something goes wrong

| You see | Do |
|---|---|
| *no exposure paths for this netting set* (or `404 DRS-1001 no exposure paths for the netting set …`) | the run does not cover it: in the demo, CP-SUMMIT and CP-NORTHPLAINS have no paths; the tree marks them *no paths* |
| other numbers than these | you built the lake with `--as-of today` or `--exposure committed`; rebuild with `--as-of data` and the default `--exposure demo` |
| the `paths` what-if answers `503 DRS-5446` | the compute workers are off: `DRISHTI_COMPUTE_ENABLED=true`, and Admin → Compute shows the pool `exposure` |
| the `addon` what-if adds nothing | a `--candidates` entry without `assetClass`, `notional` or `maturityDate` (the addon is sized on them; `--like` fills them from the booked trade) |
| no *official* chip | the date's record is not taken yet: it is taken at start once the connectors are up, or by an administrator with `drishti.py exposure official --capture` |
