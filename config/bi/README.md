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
# config/bi: where Drishti Rūpaka (business intelligence) files live

This folder is the one home of BI content. It is read by the server and the console from `drishti.bi.dir`
(environment variable `DRISHTI_BI_DIR`, default `./config/bi`). BI files are never placed inside a pack: a pack
describes data (Sutras, links, vocabulary); BI content may combine kinds from many packs and is deployed on its own.

```
config/bi/
  datasets/   <id>.dataset.yaml   the semantic model: tables over connectors (by logical name), relationships, measures; queried since phase 2 (three commented samples ship here)
  reports/    <id>.report.yaml    pages of visuals over datasets: filters, bookmarks, drill-through, theme
  boards/     <id>.board.yaml     a live board (stored in phase 1, not run)
  notebooks/  <id>.notebook.yaml  an analyst notebook (stored in phase 1, not run)
  python/                         typed Python implementations (transforms, measures, visuals) and their manifests and tests
```

See `docs/architecture/RUPAKA.md` (sections 6, 8 and 13, and "Where BI files live") for the formats, and
`docs/architecture/RUPAKA_POC.md` for what the phase 0 proof of concept does with this folder today (it lists the
dataset files it finds). Phase 1 reads the four kinds of file as read-only, published items of the catalogue (workspace "Shipped content"): the keys `title`, `description`,
`owner`, `tags`, `endorsement` (none, promoted, certified) and `audience` (`roles`, `users`, `packs`) describe the entry, and the whole file is its body (stored, not run).
The folder is **watched live** (`drishti.bi.watch`: auto, poll, off): add a file and it appears, change it and it becomes the next version, delete it and it goes; a file that
does not parse keeps its last good version and shows as a problem in Admin, BI settings and on Health. No restart. See `docs/guides/BI_GUIDE.md`.

The folders may be empty: nothing here is required for the rest of Drishti to run.

**Datasets** (`datasets/*.dataset.yaml`) are parsed, validated and **queried** since phase 2: see `docs/guides/DATASETS.md` for the format, `docs/guides/BI_TUTORIAL.md` for a walk-through of the three samples in this folder (`trades-basic`, `trading-risk`, `trading-cashflows`, each marked `sample: true` so the sample-visibility setting can hide them), and
`docs/guides/BI_GUIDE.md` section 13. A dataset that does not parse keeps its last good version and is reported; the layers it builds are written to the BI store (`drishti.bi.query.store-dir`, `./data/bi-store`), never into this folder.
