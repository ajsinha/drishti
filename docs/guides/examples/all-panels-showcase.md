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
# All panels showcase

All twenty-one panel kinds on one screen, reading one self-contained bond trade.

**Panel kinds:** `kv`, `status`, `waterfall`, `line`, `hbar`, `gauge`, `ladder`, `timeline`, `table`, `tabs`, `graph`, `area`, `pivot` (nested `by` list), `scatter`, `histogram`, `candlestick`, `surface`, `provenance`, `markdown`, `links`.

**Open it:** in the workbench, **Build → New → Examples** and pick `all-panels-showcase`: it opens as a copy (this Sutra, the JSON as samples, this note as notes) and a new scratch design opens on it by default. Or **File → Open** in the workbench with `all-panels-showcase.sutra.yaml` and `all-panels-showcase.json`.

**What to look for:** the number in each panel title is its place in the list; the kind is named after it. The `links` panel resolves ids it finds in the document against the live reference catalogue, so it shows entries only where your packs know them.

**Lines to copy:** each panel is a block of its own. Copy one under `panels:` and change its `rows`, `bind` and field names to your document's.

See [Rachana, step by step](../RACHANA_GUIDE.md) section 10 for each kind explained, and [the reference](../RACHANA_REFERENCE.md) for every option.
