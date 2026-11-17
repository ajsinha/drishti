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

Four invented proteins and the campaign that redesigned one of them, Helixase-7 (gene `HLXK7`, 312 amino acids): the fold with its
coordinates, burial and contacts; a deep mutational scan of every position and every amino acid in two assays (47,840 scores); and eight
rounds of 1,500 designs that climb to about fifteen times the natural activity, 33,000 changes in all. It is the protein half of the
showcase; the genomics-showcase pack is the other, joined through the gene, the position and the
amino acid. Every protein, number and design is invented; nothing is real.

## Screens and commands

`PROT` opens a protein and `DESIGN` a design; `RESIDUE`, `DMS`, `CONTACT`, `DMUT` and `DROUND` open the other kinds. Each is a plain
record page; linked fields open their kinds. The workbench's screens (scan heatmap, structure, design campaign, leaderboard) are drawn
from these kinds.

## Refreshing the data

Nothing is pulled: `python3 tools/packgen/genomics_showcase/make.py` writes the committed data (`data/testdata/protein-engineering/`) from
a seed, the same bytes every time; `drishti.py lake demo --only protein-engineering` builds it into the demo lake.
