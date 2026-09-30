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

**F8** on any view (or `/impact/<kind>/<id>`) answers the question *"if this moves, what does it
touch?"*

- **Depends on it directly:** every entity that references it. For a curve, these are the trades
  discounted or projected on it. For a port, they are the shipments bound for it. For a netting set,
  they are its member trades.
- **Rolls up into:** where those dependents sit, following the fields the domain pack names:
  - in the finance pack, a trade's `nettingSet` and a netting set's `creditLimit`;
  - in the logistics pack, a shipment's `vessel` and `destination`.

Each group shows its size and the **amount at stake**: the sum of the kind's measure, such as trade
MTM, netting-set net MTM or shipment declared value. Every row opens the entity, and the diagram icon
runs impact on that entity in turn.

Entities your role may not open are **counted but not shown** ("2 netting sets you do not have
access to").

!!! note "For pack authors"
    Impact is configured in `pack.yaml` under `graph.impact`: `follow` lists the roll-up fields,
    `measures` gives the Rachana-EL expression summed per kind, and `formats` gives how to show it.
    Dependents are found through the sources' reverse lookups.
