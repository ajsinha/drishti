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
# The genomics-showcase pack

A synthetic cohort of 1,500 patients read against one invented gene, `HLXK7`. It is a pack of the **genomics module**
(`modules/genomics/`), made to show Drishti outside finance: the same words, links, pages and Explain drill-down over genes, variants,
samples and association tests instead of trades and curves. Every record is invented (the genes `HLX…`, the patients, the numbers) and
every screen that shows it says *Synthetic*.

## What is in it

| Kind | Rows | One row is |
|---|---|---|
| `study-gene` | 2,000 | a gene; 24 are the planted drivers, `HLXK7` the strongest |
| `study-sample` | 1,500 | a patient's sample of cohort ONC-1, with its clinical tracks, response and place on the map of samples |
| `study-variant` | 12,000 | a variant site, with the amino acid change that joins it to the protein workbench's scan |
| `variant-call` | 45,000 | a sample carrying a variant: genotype, allele fraction, depth |
| `association` | 36,000 | a variant tested against a trait (RESPONSE, TOXICITY, RELAPSE) |
| `cnv-segment` | 3,000 | an amplification, deletion, gain or loss in one sample |
| `pathway-gene` | 500 | a gene in one of twelve pathways |

The tests are **computed**, not painted: each association is a Wald test of the log odds ratio of the generated calls against the
generated traits, so the peak on the association plot, the figures on a page and the records behind them agree by construction. The
peaks are planted by the generator's model (an altered `HLXK7` cuts the response sharply; three more drivers move it less; one
driver each moves toxicity and relapse), and the neighbours of each peak gene carry variants linked to its lead.

## The words

`ASSOC GWAS-RESPONSE-<variant>` opens one test; `SGENE HLXK7`, `SSAMPLE S0001`, `SVARIANT <id>`, `VCALL <id>`, `CNV <id>` and
`PATHWAY <id>` open the other records. Each is a plain record page (the strip and a table of every field, linked fields opening their
kinds). The showcase's own screens (the association plot, the cohort matrix, the map of samples, the variant lollipop) are drawn from
the same kinds by the genomics module, opened by its own words GWAS, PROTEIN and DESIGNS.

## The join to proteins

`study-variant` carries the gene, the protein position and the amino acid after the change. With the gene as the protein's id, those
three values are the key of a scan score in the [protein-engineering pack](../../protein-engineering/guides/protein-engineering.md): every variant of a gene that has a
protein has a measured functional reading.

## Making the data again

`python3 tools/packgen/genomics_showcase/make.py` writes the committed data (`data/testdata/genomics-showcase/<kind>/`, gzipped JSON
Lines) and this pack's contracts, Sutras, samples, README and guide from one seed (`--seed`, default 20261116). The same seed gives
byte-identical files; `--check` fails when the committed files differ from what the generator would write.

## Data contracts

Every kind of the pack has a data contract: a YAML file beside the pack (`modules/genomics/packs/genomics-showcase/contracts/<kind>.contract.yaml`, checked against `tools/contracts.schema.json`) that says what one record is, its key, how it is stored and dated, and every field with its type, presence, unit and meaning. The contracts are published on [docs/contracts/packs/genomics-showcase.md](../../../../../docs/contracts/packs/genomics-showcase.md) and each kind's example (a real record of the test data, every field explained) on [docs/contracts/samples](../../../../../docs/contracts/samples/README.md); this guide links them and copies neither. Check them against the pack and its data with `python3 tools/contracts.py check` (or `drishti.py contract check`); show one with `python3 tools/contracts.py show <MNEMONIC>`.

| Command | Kind | Class | Key | Grain | Contract | Example |
|---|---|---|---|---|---|---|
| `SGENE` | `study-gene` | fundamental | `GENE` | One row per gene, keyed by GENE. | [SGENE: Study gene](../../../../../docs/contracts/packs/genomics-showcase.md#sgene-study-gene-study-gene) (`contracts/study-gene.contract.yaml`) | [example](../../../../../docs/contracts/samples/genomics-showcase.md#study-gene) |
| `SSAMPLE` | `study-sample` | fundamental | `SAMPLE_ID` | One row per sample, keyed by SAMPLE_ID. | [SSAMPLE: Study sample](../../../../../docs/contracts/packs/genomics-showcase.md#ssample-study-sample-study-sample) (`contracts/study-sample.contract.yaml`) | [example](../../../../../docs/contracts/samples/genomics-showcase.md#study-sample) |
| `SVARIANT` | `study-variant` | fundamental | `VARIANT_ID` | One row per variant site, keyed by VARIANT_ID. | [SVARIANT: Study variant](../../../../../docs/contracts/packs/genomics-showcase.md#svariant-study-variant-study-variant) (`contracts/study-variant.contract.yaml`) | [example](../../../../../docs/contracts/samples/genomics-showcase.md#study-variant) |
| `VCALL` | `variant-call` | fundamental | `CALL_ID` | One row per sample and variant, keyed by CALL_ID. | [VCALL: Variant call](../../../../../docs/contracts/packs/genomics-showcase.md#vcall-variant-call-variant-call) (`contracts/variant-call.contract.yaml`) | [example](../../../../../docs/contracts/samples/genomics-showcase.md#variant-call) |
| `ASSOC` | `association` | fundamental | `ASSOC_ID` | One row per trait and variant, keyed by ASSOC_ID. | [ASSOC: Association of a variant with a trait](../../../../../docs/contracts/packs/genomics-showcase.md#assoc-association-of-a-variant-with-a-trait-association) (`contracts/association.contract.yaml`) | [example](../../../../../docs/contracts/samples/genomics-showcase.md#association) |
| `CNV` | `cnv-segment` | fundamental | `SEGMENT_ID` | One row per sample segment, keyed by SEGMENT_ID. | [CNV: Copy-number segment](../../../../../docs/contracts/packs/genomics-showcase.md#cnv-copy-number-segment-cnv-segment) (`contracts/cnv-segment.contract.yaml`) | [example](../../../../../docs/contracts/samples/genomics-showcase.md#cnv-segment) |
| `PATHWAY` | `pathway-gene` | fundamental | `PATHWAY_GENE_ID` | One row per pathway and gene, keyed by PATHWAY_GENE_ID. | [PATHWAY: Pathway membership](../../../../../docs/contracts/packs/genomics-showcase.md#pathway-pathway-membership-pathway-gene) (`contracts/pathway-gene.contract.yaml`) | [example](../../../../../docs/contracts/samples/genomics-showcase.md#pathway-gene) |

*Class*: **fundamental** data is delivered by a source system and stored; **input** is stored data an engine reads; **computed** is worked out by Drishti when asked and never stored by a source; **drishti-owned** is kept by Drishti itself.

## Data retrieval

Where each kind's data comes from, how it reaches the lake and how to check it. Three origins: **external** data, fetched from a public source by its data feed (each source, its licence and worked examples on the [External data](../../../../../docs/guides/WORLD_DATA_GUIDE.md) help page); **synthetic** test data (or hand-written reference data), written by a generator with fictitious names; **computed** by a Drishti engine when it is asked, never stored by a source.

| Command | Kind | Origin | How it reaches Drishti |
|---|---|---|---|
| `SGENE` | `study-gene` | synthetic: `python3 tools/packgen/genomics_showcase/make.py` | `data/testdata/genomics-showcase/study-gene/` → `lake demo` → connector `genomics-showcase-store` |
| `SSAMPLE` | `study-sample` | synthetic: `python3 tools/packgen/genomics_showcase/make.py` | `data/testdata/genomics-showcase/study-sample/` → `lake demo` → connector `genomics-showcase-store` |
| `SVARIANT` | `study-variant` | synthetic: `python3 tools/packgen/genomics_showcase/make.py` | `data/testdata/genomics-showcase/study-variant/` → `lake demo` → connector `genomics-showcase-store` |
| `VCALL` | `variant-call` | synthetic: `python3 tools/packgen/genomics_showcase/make.py` | `data/testdata/genomics-showcase/variant-call/` → `lake demo` → connector `genomics-showcase-store` |
| `ASSOC` | `association` | synthetic: `python3 tools/packgen/genomics_showcase/make.py` | `data/testdata/genomics-showcase/association/` → `lake demo` → connector `genomics-showcase-store` |
| `CNV` | `cnv-segment` | synthetic: `python3 tools/packgen/genomics_showcase/make.py` | `data/testdata/genomics-showcase/cnv-segment/` → `lake demo` → connector `genomics-showcase-store` |
| `PATHWAY` | `pathway-gene` | synthetic: `python3 tools/packgen/genomics_showcase/make.py` | `data/testdata/genomics-showcase/pathway-gene/` → `lake demo` → connector `genomics-showcase-store` |

### Generators

| Generator | Writes | Check |
|---|---|---|
| `python3 tools/packgen/genomics_showcase/make.py` | the genomics module's showcase, 200,000 invented rows from one seed (a cohort of 1,500 patients, an invented gene and protein, a design campaign) into data/testdata/genomics-showcase/<kind>/ and data/testdata/protein-engineering/<kind>/ | `python3 tools/packgen/genomics_showcase/make.py --check` |

### Into the lake, checking, offline

- **The lake.** `python3 tools/drishti.py lake demo` reads `data/testdata/testdata.yaml` and writes every dataset (test data, the packs' samples and the committed public-data snapshots) into `<data>/delta/<domain>/<kind>/`, where the pack's Delta connector serves it; a computed kind needs nothing loaded. `lake build` loads your own large JSON exports the same way ([Command line](../../../../../docs/guides/CLI_REFERENCE.md)).
- **Checking.** `python3 tools/contracts.py check` compares every contract with the pack and its data; each generator's `--check` (above) fails when the committed files differ from what it would write; in the console, `<MNEMONIC> <GO>` lists a kind and the pack's code opens its overview with the count of each kind.
- **Offline.** Everything committed works without a network: the test data, the samples and the public-data snapshots are in the repository and `lake demo` reads only them. A data feed runs offline from its download cache (`--offline`); a connector that reaches the internet (news, yfinance, the public-data feeds) is off as shipped and never starts on a machine configured offline (`drishti.offline.plugins`).
