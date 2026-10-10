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
  datasets/   <id>.dataset.yaml   the semantic model: tables over connectors (by logical name), relationships, measures
  reports/    <id>.report.yaml    pages of visuals over datasets: filters, bookmarks, drill-through, theme
  python/                         typed Python implementations (transforms, measures, visuals) and their manifests and tests
```

See `docs/architecture/RUPAKA.md` (sections 6, 8 and 13, and "Where BI files live") for the formats, and
`docs/architecture/RUPAKA_POC.md` for what the phase 0 proof of concept does with this folder today (it lists the
dataset files it finds). The folders may be empty: nothing here is required for the rest of Drishti to run.
