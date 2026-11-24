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

This folder is the one home of BI content shipped with a site. It is read by the server and the console from `drishti.bi.dir`
(environment variable `DRISHTI_BI_DIR`, default `./config/bi`). BI files are never placed inside a pack: a pack
describes data (Sutras, links, vocabulary); BI content may combine kinds from many packs and is deployed on its own.

Disabling, uninstalling (to an archive that can be restored) and uploading files here is **Admin → Artifacts**; see [docs/guides/OPERATIONS_GUIDE.md](../../docs/guides/OPERATIONS_GUIDE.md#10-admin--artifacts-install-and-uninstall-safely).

```
config/bi/
  datasets/   <id>.dataset.yaml   one table each (rachana: 2, <model>-<table>.dataset.yaml): the table over a connector, its columns, row rules and masks;
                                  live-trading and live-exposure are the 2.1 live ones; desk-pnl-vs-exposure-py-result is a Python dataset (source.python)
  models/     <id>.model.yaml     data models: datasets as aliases, relationships, measures, hierarchies, rollups; a report reads one with `model: <name>@1`
                                  (samples: trades-basic, trading-risk, trading-cashflows, counterparty-exposure, live-trading, region-economics, market-risk-var, ...)
  reports/    <id>.report.yaml    pages of visuals over a model: filters, bookmarks, drill-through, theme, tooltip pages; layout: board is a wall of tiles
                                  (the boards desk-wall and python-wall are reports here)
  boards/     <id>.board.yaml     boards (no samples ship): read as reports with board pages (POST /api/v1/bi/boards/convert shows the report)
  themes/     <id>.theme.yaml     series colours, good and bad colours, a font, format defaults per kind of visual (samples: corporate, print)
  notebooks/  <id>.notebook.yaml  an analyst notebook: stored, versioned and published like any item, but not run (sample: pnl-vs-exposure-pandas)
  python/     <id>.python.yaml    a Python Measure, Visual or Transform with its manifest (inputs, packages, target, limits, source); run on the
                                  server's compute pool python (samples: desk-pnl-volatility, pnl-histogram, pnl-vega, the Transform pnl-vs-exposure)
```

Every file is a **published, read-only item of the catalogue** (workspace "Shipped content", id `file:<kind>:<name>`). The keys `title`, `description`, `owner`, `tags`, `endorsement` (none, promoted,
certified), `audience` (`roles`, `users`, `packs`) and `sample` describe the catalogue entry; the whole file is its body. The samples are marked `sample: true`, so *Admin, Packs, Hide samples* hides them from business users, as it hides sample packs.

The folder is **watched live** (`drishti.bi.watch`: auto, poll, off): add a file and it appears, change it and it becomes the next version, delete it and it goes; a file that
does not parse keeps its last good version and shows as a problem in Admin, BI settings and on Health. No restart. The folders may be empty: nothing here is required for the rest of Drishti to run.
The layers a dataset builds are written to the BI store (`drishti.bi.query.store-dir`, `./data/bi-store`), never into this folder.

Where to read more, in the order a newcomer wants it:

| For | Read |
|---|---|
| a tour of the pages and a walk-through with the samples in this folder | [docs/guides/bi/README.md](../../docs/guides/bi/README.md) (start here), [docs/tutorials/10-first-bi-report.md](../../docs/guides/bi/REPORTS.md#part-a--your-first-bi-report) |
| the dataset and model formats (`datasets/`, `models/`) | [docs/guides/bi/README.md](../../docs/guides/bi/TABLES_AND_MODELS.md#1-models-and-datasets-what-a-report-reads) |
| the report format (`reports/`): a report names its model with `model: <name>@<major>` (a 2.1 live report still names a `dataset:`) | [docs/guides/bi/README.md](../../docs/guides/bi/REPORT_FILE_FORMAT_STATEMENTS.md#1-the-report-file-pages-visuals-filters-and-slicers) |
| boards (`layout: board`) and the theme format (`themes/`) | [the report file](../../docs/guides/bi/REPORT_FILE_FORMAT_STATEMENTS.md#23-themes) |
| publishing, review, certification and moving content between servers | [docs/guides/bi/README.md](../../docs/guides/bi/GOVERNANCE_AND_AI.md#2-publishing-and-governance-versions-review-certification-lineage-impact-environments) |
| the design and what each phase built | [docs/architecture/ARCHITECTURE.md §28](../../docs/architecture/ARCHITECTURE.md#28-business-intelligence-on-the-table-layer) (sections 6, 8 and 13, "Where BI files live", and section 20) |

Edit a sample and the next open shows it. To keep your own version of a sample, copy it to a workspace item (*Save a copy* in the Model view for a dataset or in the designer for a report, or *New item* in My workspace) rather than editing the shipped file.
