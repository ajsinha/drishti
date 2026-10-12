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
# Map

A country on the map: the world with the country highlighted, and the same country's regions (states, provinces, counties) beside it.

**Panel kinds:** `map`, `kv`.

**Open it:** in the workbench, **Build → New → Examples** and pick `map`: it opens as a copy (this Sutra, the JSON as samples, this note as notes). Or **File → Open** in the workbench with `map.sutra.yaml` and `map.json`.

**What to look for:** the left map draws the world with Australia in the accent colour (`highlight: $.iso3` takes an ISO alpha-3 or alpha-2 code, or the English name); the right map draws Australia's states and territories (`region: $.iso3`, ISO 3166-2 codes such as `AU-NSW`); small territories are dots so they can still be clicked. Both are drawn from shapes shipped with the console: nothing is fetched from the internet.

**Lines to copy:** `highlight` and `region` (expressions), and, on a server that holds the entities, `link: { kind: jurisdiction, id: "JUR-{iso3}" }` so a click on a country opens its page, and `shade: { kind: economy, field: $.gdpUsdBn, label: "GDP (USD bn)" }` to colour the countries by a figure.
