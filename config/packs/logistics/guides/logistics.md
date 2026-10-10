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

## Kinds

| Mnemonic | Opens | Identifiers |
|---|---|---|
| `SHP` | a shipment | `SHP-…` |
| `CTR` | a container | `CTR-…` |
| `VSL` | a vessel | `VSL-…` |
| `PORT` | a port | `PORT-…` |

## Finding things

Type each command in the terminal (`/t`) and press `Enter`:

| Command | Does |
|---|---|
| `SHP <shipment id> <GO>` | Opens that shipment. A bare identifier works too: `SHP-` tells Drishti it is a shipment. |
| `SHP <start of an id> <GO>` | A pick list: one match opens, several give a table to pick from. `*` is a wildcard, and case never matters. |
| `SHP <field>=<value> <GO>` | Lists shipments by field value, for example `SHP status=Delivered`. |
| `SHP <GO>` | Lists every shipment. |

`CTR`, `VSL` and `PORT` work the same way for containers, vessels and ports.

Then:

- **Workspaces → Shipment tracker** puts a shipment, its container and its vessel side by side.
- **Monitors** (`/m`) watch a list of shipments, containers, vessels and ports live.
- **Alert** on a shipment offers *Delayed more than 12 h*; on a container, *Reefer out of range*
  (below 2 °C or above 8 °C).

## What you will see

A shipment **in transit** is laid out by the `shipment` Sutra. It shows:
- a strip with status, ports, ETA, the **delay in hours** (it ticks), weight and declared value;
- the route and milestones;
- the **reefer container's temperature** for the last 24 hours, read from the linked container.

Links open the ports, the vessel (`F7`) and the container. Badges show the container temperature, the
vessel's speed and each port's berth wait.

A shipment in any other status (delivered, say) does not match the Sutra. Inference lays it out from
the pack's vocabulary instead: weights, temperatures and delays are recognised and formatted.
Containers, vessels and ports have no Sutra and are always inferred.

!!! note "Turning the pack on or off"
    `DRISHTI_PACKS=finance,logistics` (the default is `finance`). See [Domain packs](../../../../docs/guides/PACKS.md).
