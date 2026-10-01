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
# The logistics pack

The logistics pack shows Drishti on a second industry. The same engine, grammar and terminal cover
ocean freight, with no code changes: only this pack's configuration.

## Try it

Type each command in the terminal (`/t`) and press `Enter`:

| Command | Shows |
|---|---|
| `SHP SHP-10042 <GO>` | a shipment in transit, Singapore to Rotterdam: laid out by the `shipment` Sutra, its delay ticking |
| `SHP SHP-10077 <GO>` | a delivered shipment: no Sutra matches, so inference lays it out |
| `CTR CTR-MSKU1234567 <GO>` | the reefer container, with its temperature readings (inferred) |
| `VSL VSL-9811000 <GO>` | the vessel (inferred) |
| `PORT PORT-NLRTM <GO>` | the port of Rotterdam; also `PORT-SGSIN`, `PORT-LKCMB`, `PORT-EGPSD` |
| `SHP-10042 <GO>` | a bare identifier works too: `SHP-` tells Drishti it is a shipment |

Then:

- **Workspaces → Shipment tracker** puts the shipment, its container and its vessel side by side.
- **Monitors** (`/m`) → *Starters* → **Fleet watch** is a live watchlist of the shipment, container, vessel and Rotterdam.
- **Alert** on `SHP-10042` offers *Delayed more than 12 h*; on the container, *Reefer out of range*
  (below 2 °C or above 8 °C).

| Mnemonic | Opens | Identifiers |
|---|---|---|
| `SHP` | a shipment | `SHP-…` |
| `CTR` | a container | `CTR-…` |
| `VSL` | a vessel | `VSL-…` |
| `PORT` | a port | `PORT-…` |

## What you will see

`SHP-10042` is a pharmaceutical shipment from Singapore to Rotterdam, laid out by the `shipment`
Sutra. It shows:
- a strip with status, ports, ETA, the **delay in hours** (it ticks), weight and declared value;
- the route and milestones;
- the **reefer container's temperature** for the last 24 hours, read from the linked container.

Links open the ports, the vessel (`F7`) and the container. Badges show the container temperature, the
vessel's speed and each port's berth wait.

`SHP-10077` is delivered, so the Sutra (which matches shipments in transit) does not apply. Inference
lays it out from the pack's vocabulary instead: weights, temperatures and delays are recognised and
formatted.

!!! note "Turning the pack on or off"
    `DRISHTI_PACKS=finance,logistics` (the default is `finance`). See [Domain packs](../../../docs/PACKS.md).
