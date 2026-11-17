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
# The protein-engineering pack

Four invented proteins and the campaign that redesigned one of them: **Helixase-7**, the enzyme of gene `HLXK7` (312 amino acids). A
pack of the **genomics module** (`modules/genomics/`), the other half of the showcase: genomics reads the changes nature made, protein
engineering writes our own, and the join is the protein. Every record is invented and every screen that shows it says *Synthetic*.

## What is in it

| Kind | Rows | One row is |
|---|---|---|
| `protein` | 4 | a protein: sequence, family, active site (lengths 312, 248, 286, 350) |
| `residue` | 1,196 | a position: fold, domain, burial, coordinates, the mean effect of changing it |
| `residue-contact` | 5,952 | two positions close in space: the contact map's cells |
| `dms-score` | 47,840 | a scan measurement: position, amino acid, assay (activity or stability) |
| `design` | 12,000 | a designed variant of Helixase-7: eight rounds of 1,500 |
| `design-mutation` | 33,000 | one amino acid change of one design |
| `design-round` | 8 | a round: method, best and median activity, designs caught on the false peak |

The fold's coordinates are generated (helices and strands laid on a grid and joined by loops); burial, contacts and the active site are
**measured** from those coordinates. The scan comes from a small physical model: buried positions and the active site are intolerant, a
like-for-like change is mild, proline breaks helices and strands, and 14 beneficial changes sit on the surface. The campaign's activity
follows a fitness field with a summit, a false peak and a ridge; its stability follows the scan.

## The words

`PROT HLXK7` opens a protein; `DESIGN <id>` a design; `RESIDUE`, `DMS`, `CONTACT`, `DMUT` and `DROUND` the other records. Each is
a plain record page, linked fields opening their kinds. The workbench's own screens (the scan heatmap, the structure, the design
campaign with its rounds, the leaderboard) are drawn from the same kinds by the genomics module, opened by its own words GWAS, PROTEIN and DESIGNS.

## The join to genomics

The [genomics-showcase pack](../../genomics-showcase/guides/genomics-showcase.md)'s `study-variant` carries a gene, a protein position and an amino acid; with the gene
as the protein's id they are the key of a `dms-score` row.

## Making the data again

One generator writes both packs: `python3 tools/packgen/genomics_showcase/make.py` (see the genomics-showcase guide).

## Data contracts

Every kind of the pack has a data contract: a YAML file beside the pack (`modules/genomics/packs/protein-engineering/contracts/<kind>.contract.yaml`, checked against `tools/contracts.schema.json`) that says what one record is, its key, how it is stored and dated, and every field with its type, presence, unit and meaning. The contracts are published on [docs/contracts/packs/protein-engineering.md](../../../../../docs/contracts/packs/protein-engineering.md) and each kind's example (a real record of the test data, every field explained) on [docs/contracts/samples](../../../../../docs/contracts/samples/README.md); this guide links them and copies neither. Check them against the pack and its data with `python3 tools/contracts.py check` (or `drishti.py contract check`); show one with `python3 tools/contracts.py show <MNEMONIC>`.

| Command | Kind | Class | Key | Grain | Contract | Example |
|---|---|---|---|---|---|---|
| `PROT` | `protein` | fundamental | `PROTEIN` | One row per protein, keyed by PROTEIN (the symbol of its gene). | [PROT: Protein](../../../../../docs/contracts/packs/protein-engineering.md#prot-protein-protein) (`contracts/protein.contract.yaml`) | [example](../../../../../docs/contracts/samples/protein-engineering.md#protein) |
| `RESIDUE` | `residue` | fundamental | `RESIDUE_ID` | One row per protein position, keyed by RESIDUE_ID. | [RESIDUE: Residue](../../../../../docs/contracts/packs/protein-engineering.md#residue-residue-residue) (`contracts/residue.contract.yaml`) | [example](../../../../../docs/contracts/samples/protein-engineering.md#residue) |
| `CONTACT` | `residue-contact` | fundamental | `CONTACT_ID` | One row per contact, keyed by CONTACT_ID. | [CONTACT: Residue contact](../../../../../docs/contracts/packs/protein-engineering.md#contact-residue-contact-residue-contact) (`contracts/residue-contact.contract.yaml`) | [example](../../../../../docs/contracts/samples/protein-engineering.md#residue-contact) |
| `DMS` | `dms-score` | fundamental | `SCORE_ID` | One row per position, amino acid and assay, keyed by SCORE_ID. | [DMS: Deep mutational scan score](../../../../../docs/contracts/packs/protein-engineering.md#dms-deep-mutational-scan-score-dms-score) (`contracts/dms-score.contract.yaml`) | [example](../../../../../docs/contracts/samples/protein-engineering.md#dms-score) |
| `DESIGN` | `design` | fundamental | `DESIGN_ID` | One row per design, keyed by DESIGN_ID. | [DESIGN: Protein design](../../../../../docs/contracts/packs/protein-engineering.md#design-protein-design-design) (`contracts/design.contract.yaml`) | [example](../../../../../docs/contracts/samples/protein-engineering.md#design) |
| `DMUT` | `design-mutation` | fundamental | `DESIGN_MUTATION_ID` | One row per design and position, keyed by DESIGN_MUTATION_ID. | [DMUT: Design change](../../../../../docs/contracts/packs/protein-engineering.md#dmut-design-change-design-mutation) (`contracts/design-mutation.contract.yaml`) | [example](../../../../../docs/contracts/samples/protein-engineering.md#design-mutation) |
| `DROUND` | `design-round` | fundamental | `ROUND_ID` | One row per protein and round, keyed by ROUND_ID. | [DROUND: Design round](../../../../../docs/contracts/packs/protein-engineering.md#dround-design-round-design-round) (`contracts/design-round.contract.yaml`) | [example](../../../../../docs/contracts/samples/protein-engineering.md#design-round) |

*Class*: **fundamental** data is delivered by a source system and stored; **input** is stored data an engine reads; **computed** is worked out by Drishti when asked and never stored by a source; **drishti-owned** is kept by Drishti itself.

## Data retrieval

Where each kind's data comes from, how it reaches the lake and how to check it. Three origins: **external** data, fetched from a public source by its data feed (each source, its licence and worked examples on the [External data](../../../../../docs/guides/WORLD_DATA_GUIDE.md) help page); **synthetic** test data (or hand-written reference data), written by a generator with fictitious names; **computed** by a Drishti engine when it is asked, never stored by a source.

| Command | Kind | Origin | How it reaches Drishti |
|---|---|---|---|
| `PROT` | `protein` | synthetic: `python3 tools/packgen/genomics_showcase/make.py` | `data/testdata/protein-engineering/protein/` → `lake demo` → connector `protein-engineering-store` |
| `RESIDUE` | `residue` | synthetic: `python3 tools/packgen/genomics_showcase/make.py` | `data/testdata/protein-engineering/residue/` → `lake demo` → connector `protein-engineering-store` |
| `CONTACT` | `residue-contact` | synthetic: `python3 tools/packgen/genomics_showcase/make.py` | `data/testdata/protein-engineering/residue-contact/` → `lake demo` → connector `protein-engineering-store` |
| `DMS` | `dms-score` | synthetic: `python3 tools/packgen/genomics_showcase/make.py` | `data/testdata/protein-engineering/dms-score/` → `lake demo` → connector `protein-engineering-store` |
| `DESIGN` | `design` | synthetic: `python3 tools/packgen/genomics_showcase/make.py` | `data/testdata/protein-engineering/design/` → `lake demo` → connector `protein-engineering-store` |
| `DMUT` | `design-mutation` | synthetic: `python3 tools/packgen/genomics_showcase/make.py` | `data/testdata/protein-engineering/design-mutation/` → `lake demo` → connector `protein-engineering-store` |
| `DROUND` | `design-round` | synthetic: `python3 tools/packgen/genomics_showcase/make.py` | `data/testdata/protein-engineering/design-round/` → `lake demo` → connector `protein-engineering-store` |

### Generators

| Generator | Writes | Check |
|---|---|---|
| `python3 tools/packgen/genomics_showcase/make.py` | the genomics module's showcase, 200,000 invented rows from one seed (a cohort of 1,500 patients, an invented gene and protein, a design campaign) into data/testdata/genomics-showcase/<kind>/ and data/testdata/protein-engineering/<kind>/ | `python3 tools/packgen/genomics_showcase/make.py --check` |

### Into the lake, checking, offline

- **The lake.** `python3 tools/drishti.py lake demo` reads `data/testdata/testdata.yaml` and writes every dataset (test data, the packs' samples and the committed public-data snapshots) into `<data>/delta/<domain>/<kind>/`, where the pack's Delta connector serves it; a computed kind needs nothing loaded. `lake build` loads your own large JSON exports the same way ([Command line](../../../../../docs/guides/CLI_REFERENCE.md)).
- **Checking.** `python3 tools/contracts.py check` compares every contract with the pack and its data; each generator's `--check` (above) fails when the committed files differ from what it would write; in the console, `<MNEMONIC> <GO>` lists a kind and the pack's code opens its overview with the count of each kind.
- **Offline.** Everything committed works without a network: the test data, the samples and the public-data snapshots are in the repository and `lake demo` reads only them. A data feed runs offline from its download cache (`--offline`); a connector that reaches the internet (news, yfinance, the public-data feeds) is off as shipped and never starts on a machine configured offline (`drishti.offline.plugins`).
