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
# F8 · Impact

Impact answers one question: *"if this moves, what does it touch?"* Point it at a curve and it lists every trade
valued on that curve, then the netting sets those trades sit in, with the money at stake at each level.

## Try it in one minute

Open the terminal (`/t`) and type this command, then press Enter:

```text
# A discount curve from the market-data pack
CRV CRV-AUD-OIS
```

Now press **F8** (or open `/impact/ir-curve/CRV-AUD-OIS` directly).

You should see a page titled **Impact of CRV-AUD-OIS** with two sections:

| Section | What it lists | In this example |
|---|---|---|
| **Depends on it directly** | every entity whose document refers to the curve | *Trade · 31*, total about **+31.1m** (the sum of their MTM) |
| **Rolls up into** | where those dependents sit | *Netting set · 15*, total about **+16.1m** (the sum of their net MTM) |

Each row has the entity's identifier (click it to open the view), a measure (the trade's MTM, the netting set's
net MTM), and, in *Rolls up into*, the **Via** column: the field that led there (`nettingSet`). Numbers tick on
live data, so yours will differ slightly.

The small diagram icon beside each identifier runs impact on **that** entity. For example, click it next to
`T-10001` to see what depends on that trade in turn.

## Worked examples

| Start from | Command | What impact shows (all banking packs enabled) |
|---|---|---|
| a curve | `CRV CRV-AUD-OIS`, then F8 | 31 trades valued on it; then 15 netting sets |
| a trade | `TRD T-10001`, then F8 | the netting set that lists it (`NS-MERIDIAN-RE-NY`); then that netting set's credit limit |
| a netting set | `NSET NS-SUMMIT-NY`, then F8 | its 108 member trades (total MTM), its CVA, SA-CCR, exposure profile, margin calls, collateral balance and counterparty; then its credit limit |
| a counterparty | `CPTY CP-NORTHBRIDGE`, then F8 | its 35 trades, 4 netting sets, agreement, CSA, credit limit, SIMM, funding sources and climate profile |
| a port (logistics pack) | `PORT PORT-NLRTM`, then F8 | shipments bound for it, then their vessels |

*Nothing.* under a heading is a real answer: no document in any enabled source refers to the entity.

## How to read the numbers

- **The measure** is chosen per kind by the domain pack: MTM for a trade, net MTM for a netting set, the limit for
  a credit limit, declared value for a shipment. Kinds with no measure (an agreement, a CSA) are listed without
  one.
- **The total** in each group's header is the sum of that measure over the group, formatted as the pack says
  (`+31,125,408` for money with a sign, `43.0m` for compact figures).
- A group header such as *Trade · 31* gives the count, including entities you may not open.

!!! note "Entities you cannot see"
    If your role may not open some of the dependents, they are **counted but not shown**. The group says, for
    example, *2 netting sets you do not have access to*.

## Business dates

Impact follows the date in the top bar. With a date picked, it lists what referred to the entity **on that
business date**, with that day's measures. With **Live**, it uses current data.

## For pack authors: configuring impact

Impact needs no code. In `pack.yaml`, under `graph.impact`:

```yaml
# packs/counterparty-risk/pack.yaml (excerpt)
graph:
  impact:
    follow: [nettingSet, creditLimit]     # roll dependents up through these fields
    measures:                              # Rachana-EL, evaluated on each dependent
      netting-set: $.netMtm
      credit-limit: $.limit
    formats:                               # how to show each measure
      netting-set: signed0
      credit-limit: compact
```

- **Depends on it directly** is found through the sources' reverse lookups: every document with a reference field
  (declared under `graph.fields`) that holds the entity's id.
- **Rolls up into** follows the `follow` fields of those dependents. In the example above, a trade's `nettingSet`
  field leads to its netting set.
- `measures` and `formats` are merged across the enabled packs, so the trading pack can say a trade's measure is
  `$.mtm` while counterparty-risk says how to measure a netting set.

See [Domain packs](packs) for the rest of `pack.yaml`.
