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
  datasets/   <id>.dataset.yaml   one table each (rachana: 2, <model>-<table>.dataset.yaml): the table over a connector (by logical name), its columns, row rules and masks
                                  (BI_MODEL_SPEC.md section 1); the 2.1 rachana: 1 files were converted by drishti.py bi migrate --models and removed in 2.2,
                                  except the two live ones, live-trading and live-exposure, that their live reports still read. desk-pnl-vs-exposure-py-result is a
                                  Python dataset (source.python) over two model queries: DATASETS.md section 11 says what the 2.1 composed and derived datasets became
  models/     <id>.model.yaml     2.2 data models: datasets as aliases, relationships, measures, hierarchies, rollups (BI_MODEL_SPEC.md section 2); samples:
                                  trades-basic, trading-risk, trading-cashflows, counterparty-exposure, desk-pnl-vs-exposure-py, live-trading, live-exposure,
                                  region-economics, world-economics, market-risk-var (2.9.0: the published official VaR; counterparty-exposure and
                                  trading-risk read the published official exposure, counterparty-exposure-official-*); a report reads one with
                                  `model: <name>@1`
  reports/    <id>.report.yaml    pages of visuals over a model: filters, bookmarks, drill-through, theme, tooltip pages, interactions; drawn since phase 3;
                                  a page with layout: board is a wall of tiles (2.4.0: the boards desk-wall and python-wall are reports here)
                                  (commented samples: trades-overview, counterparty-risk, cashflow-ladder, live-trading-desk, live-exposure, python-desk-risk,
                                  region-economics, world-economics, market-risk-var (VaR from the published official results), the boards desk-wall
                                  and python-wall, and pnl-vs-exposure over two datasets: REPORTS.md section 16)
  boards/     <id>.board.yaml     2.3 boards (no samples ship): read as reports with board pages until converted (drishti.py bi migrate --boards config/bi/boards --write)
  themes/     <id>.theme.yaml     2.4.0 theme items: series colours, good and bad colours, a font, format defaults per kind of visual (samples: corporate, print)
  notebooks/  <id>.notebook.yaml  an analyst notebook: stored, versioned and published like any item, but not run (phase 9; one sample: pnl-vs-exposure-pandas, two extracts joined in pandas)
  python/     <id>.python.yaml    a Python Measure, Visual or Transform with its manifest (inputs, packages, target, limits, source); run by the console's sandboxed runner
                                  (target: server; the browser target was removed in 2.3.0) (commented samples: desk-pnl-volatility, pnl-histogram, pnl-vega, and the
                                  Transform pnl-vs-exposure behind the Python dataset desk-pnl-vs-exposure-py-result; docs/guides/PYTHON_BI.md)
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
| the dataset and model formats (`datasets/`, `models/`) | [docs/guides/DATASETS.md](../../docs/guides/DATASETS.md) |
| the report format (`reports/`): a report names its model with `model: <name>@<major>` (a 2.1 live report still names a `dataset:`) | [docs/guides/REPORTS.md](../../docs/guides/REPORTS.md) |
| boards, report pages of tiles (`layout: board`), and converting a 2.3 board file | [docs/guides/BOARDS.md](../../docs/guides/BOARDS.md) |
| the theme format (`themes/`) | [docs/guides/REPORTS.md section 5.2](../../docs/guides/REPORTS.md#52-themes) |
| publishing, review, certification and moving content between servers | [docs/guides/BI_GOVERNANCE.md](../../docs/guides/BI_GOVERNANCE.md) |
| the design and what each phase built | [docs/architecture/RUPAKA.md](../../docs/architecture/RUPAKA.md) (sections 6, 8 and 13, "Where BI files live", and section 20) |

Edit a sample and the next open shows it. To keep your own version of a sample, copy it to a workspace item (*Save a copy* in the Model view for a dataset or in the designer for a report, or *New item* in My workspace) rather than editing the shipped file.
