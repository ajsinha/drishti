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
# Genomics: the guide

**What it does.** The module reads VCF files and answers three functions: `VARIANT HX-A001` (a variant, where it is, what it does and
who carries it), `GENE TP53` (a gene's variants along its protein, drawn as a lollipop) and `COHORT COH-A` (a cohort's samples and the
variants they carry). Its pages are under `/m/genomics/`; its menu is Genomics (Variants, Genes, Cohorts).

**Where the data comes from.** The VCF reader (`genomics-vcf`, `VcfConnector`) is a connector extension of the kernel: the plugin API's
`SourcePlugin`, connected by the kernel when the module loads and withdrawn when it goes. It reads:

- the module's synthetic samples, `data/samples/*.vcf` and `data/samples/gene.jsonl` inside the package;
- a site's own files, `<data folder>/modules/genomics/vcf/*.vcf` (or `.vcf.gz`) and `gene.jsonl` beside them.

Each VCF record gives one `variant` per alternate allele; the INFO keys `GENE`, `CSQ`, `IMPACT` and `AAPOS` fill the gene, the
consequence, the impact and the protein position. The sample columns give the `sample` kind, and each `##SAMPLE=<ID=..,Cohort=..>` line
the sample's cohort. `gene` and `cohort` are computed from them (`GenomicsKinds`, a `KindProvider`).

**Why the reader is in the module and not in `lib/`.** The site's `lib/` folder holds jars every part of the server may use (JDBC drivers,
connectors any pack reads through). The VCF reader serves only this module's kinds and must go when the module goes: inside the module it
is unloaded with it, leak-checked, and a removed module leaves nothing behind.

**The visual.** `genomics.lollipop` is a kind of drishti-chart registered by the module's library `drishti-genomics`
(`elements/drishti-genomics/0.1.0/`). It is built from drishti-chart's scatter, its annotation bands and a bar series, so it has the
card, theme, tooltips, Explain, the data table and export of every chart. The Elements gallery shows it while the module is loaded.

**How it is built.** `mvn verify` compiles the code, runs its tests, packs `target/dmod/genomics-0.1.0.dmod` and runs the conformance
checks C1 to C15. See the [tutorial](tutorial.md).

**The showcase: two packs of invented data.** Besides the VCF reader the module carries two packs, `genomics-showcase` and
`protein-engineering`, that show Drishti outside finance: a cohort of 1,500 patients read against one invented gene (`HLXK7`), and the
eight-round design campaign of the gene's enzyme (Helixase-7, 312 amino acids). 200,000 rows in 14 kinds, every one seeded and
invented (no real gene, patient, organism or structure), written by `python3 tools/packgen/genomics_showcase/make.py`: the same seed
gives byte-identical files in `data/testdata/genomics-showcase/` and `data/testdata/protein-engineering/`, which
`drishti.py lake demo --only genomics-showcase,protein-engineering` builds into the demo lake and the packs' own Delta connectors
(`genomics-showcase-store`, `protein-engineering-store`) serve. Like every pack they are data and contracts; the words are
`GWAS` (a variant tested against a trait), `PROTEIN` (a protein) and `DESIGNS` (a design of the campaign), with `SGENE`, `SSAMPLE`,
`SVARIANT`, `VCALL`, `CNV`, `PATHWAY`, `RESIDUE`, `DMS`, `CONTACT`, `DMUT` and `DROUND` for the other kinds. Each word opens a plain
record page, so it works on its own; the showcase's screens are drawn from the same kinds.

What is computed and what is written. The association tests (a Wald test of the log odds ratio of the generated calls against the
generated traits) are computed by the generator from the calls, so the peak on the association plot, the figures and the records
behind them agree by construction. The map of samples, the map of designs, the residues' coordinates, burial and contacts and the
scan's per-position means are columns the generator writes: nothing is projected or measured at view time. The join between the two
packs is one key: a variant's gene, protein position and amino acid after the change (`study-variant`) are a scan score's protein,
position and amino acid (`dms-score`). Each pack's README lists every kind's fields; the pack guides (`genomics-showcase`,
`protein-engineering`, under Help) say what is in them and how to make the data again.
