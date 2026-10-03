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
# Linked sources

Panels that read other entities with `source: "link(id, kind)"`: the counterparty's netting sets, a netting set's exposure and a desk's pivot, all fetched live.

**This example is not self-contained.** Its JSON names the entities (`CP-SUMMIT`, `NS-SUMMIT-NY`, `DESK-FI`), and the panels read those from a running server with the demo packs (`counterparty-risk`, `market-risk`). Without them the panels say they are waiting for the entity.

**Panel kinds:** `tabs`, `area`, `pivot`, each with `source`.

**Open it:** Studio **File → Open** with `linked-sources.sutra.yaml` and `linked-sources.json`, or `/studio?example=linked-sources`.

**What to look for:** the panels' data does not come from the trade's own document; change `desk` in the JSON and the pivot follows.

**Lines to copy:** `source: "link($.desk, 'desk')"` (an expression giving the entity to read), then write `rows`, `each` or the other options as if the linked document were the page's own.
