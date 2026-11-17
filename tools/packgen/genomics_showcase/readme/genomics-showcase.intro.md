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
---
generator: tools/packgen/genomics_showcase/make.py
sources:
- name: Synthetic and seeded (tools/packgen/genomics_showcase/make.py, seed 20261116)
  licence: Proprietary (part of Drishti)
  internal-only: false
  used-for: every kind (nothing in it is real)
refresh: python3 tools/packgen/genomics_showcase/make.py
---
## What it is

A synthetic cohort of 1,500 patients read against one invented gene, `HLXK7`, in the genomics module: 2,000 genes (24 of them planted
drivers), 12,000 variant sites, 45,000 variant calls, 3,000 copy-number segments, 500 pathway memberships and 36,000 association tests
of three traits. The tests are computed from the generated calls and traits, so the peaks, the figures and the records behind them
agree. It is the genomics half of the showcase; the protein-engineering pack is the other. Every
gene, patient and number is invented; nothing is real.

## Screens and commands

`ASSOC` opens one association test; `SGENE`, `SSAMPLE`, `SVARIANT`, `VCALL`, `CNV` and `PATHWAY` open the other kinds. Each is a plain record
page; linked fields (a variant's gene, a call's sample) open their kinds. The showcase's screens (association plot, cohort matrix, map of
samples, variant lollipop) are drawn from these kinds.

## Refreshing the data

Nothing is pulled: `python3 tools/packgen/genomics_showcase/make.py` writes the committed data (`data/testdata/genomics-showcase/`) from
a seed, the same bytes every time; `drishti.py lake demo --only genomics-showcase` builds it into the demo lake.
