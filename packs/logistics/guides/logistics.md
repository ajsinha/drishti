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

| Mnemonic | Opens | Example |
|---|---|---|
| `SHP` | a shipment | `SHP SHP-10042 <GO>` |
| `CTR` | a container | `CTR CTR-MSKU1234567 <GO>` |
| `VSL` | a vessel | `VSL VSL-9811000 <GO>` |
| `PORT` | a port | `PORT PORT-NLRTM <GO>` |

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

## Try the workspace

**Workspaces → Shipment tracker** puts the shipment, its container and its vessel side by side.

!!! note "Turning the pack on or off"
    `DRISHTI_PACKS=finance,logistics` (the default is `finance`). See the Packs guide.
