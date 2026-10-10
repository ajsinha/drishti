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
# Tutorial 1 · Your first view

In ten minutes you will open an interest rate swap, follow it to its netting set, watch it tick, and
see exactly where every number came from.

## Before you start

The steps use one interest rate swap as their example: `IRS-48213` from the demo data. On a site with
its own data, pick any swap instead: `TRD productType=IRS <GO>` lists them, and every step works the
same way with its identifier.

| Command | Does |
|---|---|
| `TRD <trade id> <GO>` | opens one trade (`NSET`, `CRV`, `CPTY` do the same for netting sets, curves, counterparties) |
| `TRD <start of an id> <GO>` | a pick list: one match opens, several give a table to pick from |
| `TRD where mtm < 0 order by mtm <GO>` | a search: the trades with a negative MTM, worst first |

## 1. Open the terminal

Go to **/t**, or choose **Open the terminal** on the landing page. The amber box at the top is the
command line. Press `/` from anywhere to jump to it.

## 2. Type a command

Type slowly and watch the dropdown:

```text
# Suggestions as you type
T              → TRD  Trade, and your recent entities
TRD IRS-4      → IRS-47102, IRS-48213, … with a one-line description each
```

Use `↓` to highlight **IRS-48213**, then press `Enter`, which is the same as `<GO>`. You can also type
the whole command: `TRD IRS-48213 <GO>`.

!!! tip "Bare identifiers work too"
    `IRS-48213` alone opens the trade: its shape tells Drishti the kind.

## 3. Read the view

- **Title line:** what it is (*Trade · Interest rate swap*), its identifier, and the counterparty. The counterparty is a link.
- **Strip:** eight key figures. MTM is highlighted, and negative numbers carry a true minus sign and the negative colour.
- **Left column:** legs (as tabs), the cashflow ladder with totals, the floating leg, and *How this view was built*.
- **Right column:** the SOFR curve with the trade's maturity marked, linked entities with badges, and DV01 by tenor.

Press `F2`, `F3` and `F4` to jump between panels. The footer lists every key the view offers.

## 4. Follow a link

In **Linked entities**, choose **NS-NORTH-01** (its badge shows `EE 4.1m`). You are now on the netting
set: its exposure profile against the credit limit, member trades, CSA terms and collateral. The
breadcrumb `← IRS-48213 / NS-NORTH-01` shows the path you took. `Alt+←` goes back.

## 5. Watch it tick

The top bar says **Live, p99 N ms**. MTM, DV01 and the curve move while you watch, and changed values
flash briefly. Only what changed is redrawn.

## 6. See where it came from

Press `F9` for the raw document exactly as the source sent it, with its source and generation. Close
it with `Esc`. *How this view was built* names:
- the layout (`Sutra irs-vanilla v3 + inference`);
- the data's shape fingerprint;
- the source and generation.

!!! note "What next"
    [Using the terminal](../../../../docs/guides/USER_GUIDE.md) lists every key. [Tutorial 2](write-your-first-sutra.md) shows how
    a layout like this one is written.
