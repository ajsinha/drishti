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

Disabling, uninstalling (to an archive that can be restored) and uploading files here is **Admin → Artifacts**; see [docs/admin/ARTIFACTS.md](../../docs/admin/ARTIFACTS.md).

```
config/bi/
  datasets/   <id>.dataset.yaml   the semantic model: tables over connectors (by logical name), relationships, measures, row rules and masks; queried since phase 2
                                  (commented samples ship: trades-basic, trading-risk, trading-cashflows, the live live-trading and live-exposure, and for several datasets counterparty-exposure, the composed desk-pnl-vs-exposure and the derived desk-pnl-vs-exposure-py: DATASETS.md section 11)
                                  2.2: rachana: 2 datasets, one table each (<model>-<table>.dataset.yaml), made from those by drishti.py bi migrate --models (BI_MODEL_SPEC.md 8.3.1);
                                  the rachana: 1 files stay until the 2.1 reader is removed
  models/     <id>.model.yaml     2.2 data models: datasets as aliases, relationships, measures, hierarchies, rollups (BI_MODEL_SPEC.md section 2); every sample
                                  dataset above converted, under the same name; the reports (except the two live ones, until W3) read them with `model: <name>@1`
  reports/    <id>.report.yaml    pages of visuals over a dataset: filters, bookmarks, drill-through, theme; drawn since phase 3
                                  (seven commented samples: trades-overview, counterparty-risk, cashflow-ladder, live-trading-desk, live-exposure, python-desk-risk, and pnl-vs-exposure over two datasets: REPORTS.md section 16)
  boards/     <id>.board.yaml     a wall of live tiles from several reports, with alerts and kiosk mode; drawn since phase 5 (one commented sample: desk-wall)
  notebooks/  <id>.notebook.yaml  an analyst notebook: stored, versioned and published like any item, but not run (phase 9; one sample: pnl-vs-exposure-pandas, two extracts joined in pandas)
  python/     <id>.python.yaml    a Python Measure or Visual with its manifest (inputs, packages, target, limits, source); run by the console's sandboxed runner or in the browser, since phase 6
                                  (commented samples: desk-pnl-volatility, pnl-histogram, and the Transform pnl-vs-exposure behind the derived dataset desk-pnl-vs-exposure-py; docs/guides/PYTHON_BI.md)
```

Every file is a **published, read-only item of the catalogue** (workspace "Shipped content", id `file:<kind>:<name>`). The keys `title`, `description`, `owner`, `tags`, `endorsement` (none, promoted,
certified), `audience` (`roles`, `users`, `packs`) and `sample` describe the catalogue entry; the whole file is its body. The samples are marked `sample: true`, so *Admin, Packs, Hide samples* hides them from business users, as it hides sample packs.

The folder is **watched live** (`drishti.bi.watch`: auto, poll, off): add a file and it appears, change it and it becomes the next version, delete it and it goes; a file that
does not parse keeps its last good version and shows as a problem in Admin, BI settings and on Health. No restart. The folders may be empty: nothing here is required for the rest of Drishti to run.
The layers a dataset builds are written to the BI store (`drishti.bi.query.store-dir`, `./data/bi-store`), never into this folder.

Where to read more, in the order a newcomer wants it:

| For | Read |
|---|---|
| a tour of the pages and a walk-through with the samples in this folder | [docs/guides/BI_GUIDE.md](../../docs/guides/BI_GUIDE.md) (start here), [docs/tutorials/10-first-bi-report.md](../../docs/tutorials/10-first-bi-report.md) |
| the dataset format (`datasets/`) | [docs/guides/DATASETS.md](../../docs/guides/DATASETS.md) |
| the report format (`reports/`): a report names its dataset with `dataset:` (a catalogue id, a file name such as `trades-basic`, or the dataset's name) | [docs/guides/REPORTS.md](../../docs/guides/REPORTS.md) |
| the board format (`boards/`) | [docs/guides/BOARDS.md](../../docs/guides/BOARDS.md) |
| publishing, review, certification and moving content between servers | [docs/guides/BI_GOVERNANCE.md](../../docs/guides/BI_GOVERNANCE.md) |
| the design and what each phase built | [docs/architecture/RUPAKA.md](../../docs/architecture/RUPAKA.md) (sections 6, 8 and 13, "Where BI files live", and section 20) |

Edit a sample and the next open shows it. To keep your own version of a sample, copy it to a workspace item (*Save a copy* in the Model view for a dataset or in the designer for a report, or *New item* in My workspace) rather than editing the shipped file.
