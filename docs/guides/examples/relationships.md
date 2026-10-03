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
# Relationships

How entities relate: a group and its agreements as a graph, netting sets as tabs, and the entities a document refers to.

**Panel kinds:** `graph`, `tabs`, `links`.

**Open it:** Studio **File → Open** with `relationships.sutra.yaml` and `relationships.json`, or `/studio?example=relationships`.

**What to look for:** the group at the top of the graph and the netting sets at the bottom; one tab per netting set, each a small `kv`. The `links` panel resolves ids in the document against the live reference catalogue, so it lists entries only where your packs know them.

**Lines to copy:** `nodes`, `edges`, `layout: tree` on the graph; `each`, `tabTitle` and `body` on tabs.
