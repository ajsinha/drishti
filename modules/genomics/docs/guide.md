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

**The visuals.** The module's library `drishti-genomics` (`elements/drishti-genomics/`, served at
`/m/genomics/elements/drishti-genomics/<module version>/`) registers six kinds of drishti-chart. Each is glue: it maps the module's answers
and words onto a generic Elements kind, so the drawing, the card, theme, tooltips, Explain, the data table, export and the keyboard are
the generic library's. The Elements gallery shows each while the module is loaded.

| Kind | File | Built on (generic Elements) |
|---|---|---|
| `genomics.lollipop` | `drishti_genomics.js` | drishti-chart `lollipop`, coloured by impact or by the scan's measured effect; domains as bands |
| `genomics.manhattan` | `drishti_genomics.js` | drishti-chart `scatter` with `segments` (the chromosomes), `labelTop` (the peaks' genes), annotation lines |
| `genomics.oncoprint` | `drishti_genomics.js` | drishti-chart `heatmap` with category cells, `x.domain`, heatmap `tracks` |
| `protein.dms` | `drishti_protein.js` | drishti-chart `heatmap`, diverging, with three `tracks` and the overview strip (`zoom`) |
| `protein.backbone` | `drishti_protein.js` | drishti-chart `trace3d` (ECharts GL, loaded on first use; flat where WebGL is off) |
| `protein.landscape` | `drishti_protein.js` | drishti-chart `scatter` with a `surface` and `frames` (Play the rounds) |

## The showcase: one protein, read and rewritten

The showcase (its design: `docs/architecture/GENOMICS_SHOWCASE.md`) is two screens over about 200,000
synthetic rows in two packs of the module, `genomics` and `protein`: a cohort of patients that points at one gene, and that gene's
protein measured change by change and redesigned over eight rounds. Everything is invented (seed 20261116) and every page says so.

| Screen | Route | Word | What it shows |
|---|---|---|---|
| The linked board | `/m/genomics/showcase`, `/m/genomics/gwas/<trait>` | `GWAS RESPONSE` | the association plot, the figures, the cohort matrix, the map of samples, the lollipop coloured by the scan, the records |
| The guided board | `/m/genomics/showcase/guided` | | the same cards in four steps with the presenter's sentence (drishti-core's `Tour`); the phone's way in |
| The protein workbench | `/m/genomics/proteins/<gene or protein>` | `PROTEIN HLXK7` | the scan, the structure, one residue's changes, the design campaign with Play, activity by round, the leaderboard, the records |
| The campaign | `/m/genomics/designs/<gene or protein>` | `DESIGNS HLXK7` | the workbench, opened at the campaign |

**How the views link.** One selection runs through every card, and a selection is a filter of the server's next call, never a recount in
the page: a peak or a matrix row picks the gene (`genomics.gene`); a cluster name or a rectangle drawn on the map of samples (the
generic rectangle brush) picks samples (`genomics.samples`, sent as `cluster=` or `box=x0,x1,y0,y1`); a cell of the scan picks a residue
(`protein.residue`: the structure marks it, the campaign rings the designs that carry the change); a round (`protein.round`: the
leaderboard follows); a design (`protein.design`: its cells framed on the scan, its changes marked on the molecule). The channels are
told on drishti-core's bus. The lollipop and Explain link the two screens (`/proteins/HLXK7?residue=117`).

**Explain.** Every mark opens the server's Explain: the sentence, the steps with their tables and row counts, and the records behind it,
which fill the Records grid (a lollipop head: its carriers joined to the scan's score of that exact change; a cell of the scan: its
measurements; a design: its changes and their scores).

**The server.** The module's Java computes every figure (`GenomicsBoard`, `ProteinBench`, `ShowcaseController`), reading the fourteen
kinds through the kernel's bindings as rows (`ShowcaseData`, one snapshot, answers cached per snapshot):

| Call (under `/api/v1/genomics/`) | Answers |
|---|---|
| `showcase/status` | rows per kind and the kinds with none (`refresh=true` reads again) |
| `cohorts/{id}/summary?gene=` | samples, response rate, the gene altered, response if altered and if not |
| `associations?trait=` | every site below p 1e-4 and an even sample of the rest (about 5,400), the chromosomes, the peaks |
| `cohorts/{id}/oncoprint?genes=24&gene=` | the most altered genes, the samples in mutual-exclusivity order, the cells, three tracks |
| `cohorts/{id}/samples/map` | every sample's precomputed position, the clusters' figures, the selected samples |
| `genes/{id}/lollipop` | the gene's variants carried in the selection, each joined to the scan (score, effect), the domains |
| `proteins/{id}/dms?assay=`, `structure`, `residues/{pos}` | the scan and the residues' tracks; the coordinates; one residue's changes |
| `proteins/{id}/designs?residue=&aa=`, `rounds`, `leaderboard?round=&n=`, `designs/{design}` | the campaign (each round's best 50 and a sample, the surface, the designs carrying a change); quantiles by round; the best designs with their changes; one design |
| `explain/{variant,site,cluster,cell,design,residue}/{id}` | the Explain block of a mark |

Every call that applies takes the selection (`cluster=`, `box=`, `samples=`). A scan score of −1 or less is *damaging*, −0.4 or less
*intermediate*, else *tolerated* (0 is the natural protein). A server without the packs says which kinds have no rows, on the page and
in `showcase/status`, and asks nothing else.

**How it is built.** `mvn verify` compiles the code, runs its tests, packs `target/dmod/genomics-0.1.0.dmod` and runs the conformance
checks C1 to C15. See the [tutorial](tutorial.md).
