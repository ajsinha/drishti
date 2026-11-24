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
| `genomics.manhattan` | `drishti_genomics.js` | drishti-chart `segmented` (the chromosomes as segments, the genome-wide and suggestive thresholds, the peaks' genes) |
| `genomics.oncoprint` | `drishti_genomics.js` | drishti-chart `matrix` (category cells in the server's order, clinical tracks) |
| `protein.dms` | `drishti_genomics.js` | drishti-chart `heatmap`, diverging, with three `tracks` and the overview strip (`zoom`) |
| `protein.backbone` | `drishti_genomics.js` | drishti-chart `trace3d` (ECharts GL, loaded on first use; flat where WebGL is off) |
| `protein.landscape` | `drishti_genomics.js` | drishti-chart `scatter` with a `surface` and `frames` (Play the rounds) |

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
checks C1 to C14. See the [tutorial](#genomics-the-tutorial).

**The showcase: two packs of invented data.** Besides the VCF reader the module carries two packs, `genomics-showcase` and
`protein-engineering`, that show Drishti outside finance: a cohort of 1,500 patients read against one invented gene (`HLXK7`), and the
eight-round design campaign of the gene's enzyme (Helixase-7, 312 amino acids). 200,000 rows in 14 kinds, every one seeded and
invented (no real gene, patient, organism or structure), written by `python3 tools/packgen/genomics_showcase/make.py`: the same seed
gives byte-identical files in `data/testdata/genomics-showcase/` and `data/testdata/protein-engineering/`, which
`drishti.py lake demo --only genomics-showcase,protein-engineering` builds into the demo lake and the packs' own Delta connectors
(`genomics-showcase-store`, `protein-engineering-store`) serve. Like every pack they are data and contracts; the words are
`ASSOC` (a variant tested against a trait), `PROT` (a protein) and `DESIGN` (a design of the campaign), with `SGENE`, `SSAMPLE`,
`SVARIANT`, `VCALL`, `CNV`, `PATHWAY`, `RESIDUE`, `DMS`, `CONTACT`, `DMUT` and `DROUND` for the other kinds. Each word opens a plain
record page, so it works on its own; the showcase's screens are drawn from the same kinds and opened by the module's own words `GWAS`,
`PROTEIN` and `DESIGNS`.

What is computed and what is written. The association tests (a Wald test of the log odds ratio of the generated calls against the
generated traits) are computed by the generator from the calls, so the peak on the association plot, the figures and the records
behind them agree by construction. The map of samples, the map of designs, the residues' coordinates, burial and contacts and the
scan's per-position means are columns the generator writes: nothing is projected or measured at view time. The join between the two
packs is one key: a variant's gene, protein position and amino acid after the change (`study-variant`) are a scan score's protein,
position and amino acid (`dms-score`). Each pack's README lists every kind's fields; the pack guides (`genomics-showcase`,
`protein-engineering`, under Help) say what is in them and how to make the data again.

## Genomics: the tutorial

1. **Start Drishti Genomics.** `python tools/drishti.py start all --distribution genomics` (Windows: `py -3.13 tools\drishti.py start all --distribution genomics`). The
   kernel starts with this module alone: the Research lens, no finance anywhere.
2. **Open a gene.** Type `GENE TP53` and press Enter. The page shows TP53's variants along the protein: each stem at an amino acid
   position, the head as high as the samples carrying it, coloured by impact; the protein's domains are the bands. The grid under it
   lists the same variants.
3. **Open a variant.** Click a variant id in the grid (or type `VARIANT HX-A001`): where it is, its consequence and impact, and the
   samples that carry it.
4. **Open a cohort.** Type `COHORT COH-A`: the variants its twelve samples carry.
5. **Add your own VCF file.** Copy a VCF 4.x file into `<data folder>/modules/genomics/vcf/` and restart the server (or switch the module
   off and on in Admin → Modules): its variants and samples join the synthetic ones.
6. **Use it in an expression.** Anywhere Rachana runs, `genomics_af_class(AF)` gives `rare`, `low` or `common`.

Read the [guide](guide.md) for what each part is.

### The three-minute demo: the showcase

The script of the demo, step by step (the design's section 2). Each step gives the UI, the command line where there is one, and the YAML
or address behind it. The data is synthetic, made by the module's generator and loaded with the other packs.

1. **Load the data.** `python tools/drishti.py lake demo` (Windows: `py -3.13 tools\drishti.py lake demo`) loads the packs `genomics` and
   `protein` with the rest. Check what the server sees: open `/api/v1/genomics/showcase/status`, or

   ```bash
   curl -s http://localhost:18480/api/v1/genomics/showcase/status
   ```

   `missing` is empty when every kind has rows. A page opened before then says which kinds it is waiting for.
2. **0:00, open the board.** UI: Genomics → *Cohort showcase*, or the Home card. Command bar: `GWAS RESPONSE` then Enter. "Fifteen hundred
   patients, twelve thousand sites tested against response to therapy. This is a laptop, with no network."
3. **0:20, click the tallest peak.** UI: click a point of the peak on the association plot. Every card follows: the matrix marks the gene,
   the lollipop and the figures are answered again. CLI (the same answer):

   ```bash
   curl -s 'http://localhost:18480/api/v1/genomics/associations?trait=RESPONSE' | python -m json.tool | head -40
   ```
4. **0:45, pick patients.** UI: click the cluster *C3 immune-hot* above the figures, then drag a rectangle on the map of samples. The
   matrix narrows to the group; the response rate and counts come back from the server. The call carries the selection:

   ```bash
   curl -s 'http://localhost:18480/api/v1/genomics/cohorts/ONC-1/summary?gene=HLXK7&cluster=C3%20immune-hot'
   ```
5. **1:10, the finding.** Point at *Response if altered* against *Response if not*: carriers of a change in HLXK7 respond less than half
   as often.
6. **1:20, Explain a lollipop head.** Click a red head: Explain opens with the sentence and the steps, and the Records grid fills with
   the carriers. CLI: `curl -s 'http://localhost:18480/api/v1/genomics/explain/variant/<VARIANT_ID>?cohort=ONC-1'`.
7. **1:45, cross to the protein.** Click *Open HLXK7 in the protein workbench* (or type `PROTEIN HLXK7`). "Every position, every
   possible amino acid, measured."
8. **2:05, click a blue cell.** The structure turns to the residue and marks it, one residue's changes are drawn, and the campaign rings
   the designs that carry the change: `…/proteins/HLXK7/designs?residue=117&aa=K` answers `carrying`.
9. **2:20, Play the rounds.** Press *Play the rounds* on the campaign: eight rounds climb the fitness surface; the leaderboard follows
   each round. With reduced motion asked for by the system, the slider steps the rounds instead.
10. **2:45, the winner.** Click the first row of the leaderboard: its changes are framed on the scan and marked on the molecule; the note
    says its activity and stability. CLI: `curl -s http://localhost:18480/api/v1/genomics/proteins/HLXK7/designs/<DESIGN_ID>`.
11. **2:55, the close.** Type `GENE HLXK7` in the command bar. "Two sciences, one grammar."

**On a phone, or for a first-time presenter:** Genomics → *Cohort showcase* → *Guided mode* (`/m/genomics/showcase/guided`) walks steps 2
to 6 as four steps with the sentence to say; Back and Next move between them.

**The visuals in your own page.** Each card is a drishti-chart spec; the workbench's scan, for one, is

```yaml
kind: protein.dms            # glue over the generic heatmap with tracks
title: Deep mutational scan
data: { source: /m/genomics/api/proteins/HLXK7/dms }
```

and the generic parts (heatmap tracks, the rectangle brush, frames, the lollipop, the 3D trace, the tour) are in the Elements gallery
with their own pages.

## The genomics-showcase pack

A synthetic cohort of 1,500 patients read against one invented gene, `HLXK7`. It is a pack of the **genomics module**
(`modules/genomics/`), made to show Drishti outside finance: the same words, links, pages and Explain drill-down over genes, variants,
samples and association tests instead of trades and curves. Every record is invented (the genes `HLX…`, the patients, the numbers) and
every screen that shows it says *Synthetic*.

### What is in it

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

### The words

`ASSOC GWAS-RESPONSE-<variant>` opens one test; `SGENE HLXK7`, `SSAMPLE S0001`, `SVARIANT <id>`, `VCALL <id>`, `CNV <id>` and
`PATHWAY <id>` open the other records. Each is a plain record page (the strip and a table of every field, linked fields opening their
kinds). The showcase's own screens (the association plot, the cohort matrix, the map of samples, the variant lollipop) are drawn from
the same kinds by the genomics module, opened by its own words GWAS, PROTEIN and DESIGNS.

### The join to proteins

`study-variant` carries the gene, the protein position and the amino acid after the change. With the gene as the protein's id, those
three values are the key of a scan score in the [protein-engineering pack](guide.md): every variant of a gene that has a
protein has a measured functional reading.

### Making the data again

`python3 tools/packgen/genomics_showcase/make.py` writes the committed data (`data/testdata/genomics-showcase/<kind>/`, gzipped JSON
Lines) and this pack's contracts, Sutras, samples, README and guide from one seed (`--seed`, default 20261116). The same seed gives
byte-identical files; `--check` fails when the committed files differ from what the generator would write.

### Data retrieval

Where each kind's data comes from, how it reaches the lake and how to check it. Three origins: **external** data, fetched from a public source by its data feed (each source, its licence and worked examples on the [External data](../../../docs/guides/WORLD_DATA_GUIDE.md) help page); **synthetic** test data (or hand-written reference data), written by a generator with fictitious names; **computed** by a Drishti engine when it is asked, never stored by a source.

| Command | Kind | Origin | How it reaches Drishti |
|---|---|---|---|
| `SGENE` | `study-gene` | synthetic: `python3 tools/packgen/genomics_showcase/make.py` | `data/testdata/genomics-showcase/study-gene/` → `lake demo` → connector `genomics-showcase-store` |
| `SSAMPLE` | `study-sample` | synthetic: `python3 tools/packgen/genomics_showcase/make.py` | `data/testdata/genomics-showcase/study-sample/` → `lake demo` → connector `genomics-showcase-store` |
| `SVARIANT` | `study-variant` | synthetic: `python3 tools/packgen/genomics_showcase/make.py` | `data/testdata/genomics-showcase/study-variant/` → `lake demo` → connector `genomics-showcase-store` |
| `VCALL` | `variant-call` | synthetic: `python3 tools/packgen/genomics_showcase/make.py` | `data/testdata/genomics-showcase/variant-call/` → `lake demo` → connector `genomics-showcase-store` |
| `ASSOC` | `association` | synthetic: `python3 tools/packgen/genomics_showcase/make.py` | `data/testdata/genomics-showcase/association/` → `lake demo` → connector `genomics-showcase-store` |
| `CNV` | `cnv-segment` | synthetic: `python3 tools/packgen/genomics_showcase/make.py` | `data/testdata/genomics-showcase/cnv-segment/` → `lake demo` → connector `genomics-showcase-store` |
| `PATHWAY` | `pathway-gene` | synthetic: `python3 tools/packgen/genomics_showcase/make.py` | `data/testdata/genomics-showcase/pathway-gene/` → `lake demo` → connector `genomics-showcase-store` |

#### Generators

| Generator | Writes | Check |
|---|---|---|
| `python3 tools/packgen/genomics_showcase/make.py` | the genomics module's showcase, 200,000 invented rows from one seed (a cohort of 1,500 patients, an invented gene and protein, a design campaign) into data/testdata/genomics-showcase/<kind>/ and data/testdata/protein-engineering/<kind>/ | `python3 tools/packgen/genomics_showcase/make.py --check` |

#### Into the lake, checking, offline

- **The lake.** `python3 tools/drishti.py lake demo` reads `data/testdata/testdata.yaml` and writes every dataset (test data, the packs' samples and the committed public-data snapshots) into `<data>/delta/<domain>/<kind>/`, where the pack's Delta connector serves it; a computed kind needs nothing loaded. `lake build` loads your own large JSON exports the same way ([Command line](../../../docs/guides/CLI_REFERENCE.md)).
- **Checking.** `python3 tools/contracts.py check` compares every contract with the pack and its data; each generator's `--check` (above) fails when the committed files differ from what it would write; in the console, `<MNEMONIC> <GO>` lists a kind and the pack's code opens its overview with the count of each kind.
- **Offline.** Everything committed works without a network: the test data, the samples and the public-data snapshots are in the repository and `lake demo` reads only them. A data feed runs offline from its download cache (`--offline`); a connector that reaches the internet (news, yfinance, the public-data feeds) is off as shipped and never starts on a machine configured offline (`drishti.offline.plugins`).

## The protein-engineering pack

Four invented proteins and the campaign that redesigned one of them: **Helixase-7**, the enzyme of gene `HLXK7` (312 amino acids). A
pack of the **genomics module** (`modules/genomics/`), the other half of the showcase: genomics reads the changes nature made, protein
engineering writes our own, and the join is the protein. Every record is invented and every screen that shows it says *Synthetic*.

### What is in it

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

### The words

`PROT HLXK7` opens a protein; `DESIGN <id>` a design; `RESIDUE`, `DMS`, `CONTACT`, `DMUT` and `DROUND` the other records. Each is
a plain record page, linked fields opening their kinds. The workbench's own screens (the scan heatmap, the structure, the design
campaign with its rounds, the leaderboard) are drawn from the same kinds by the genomics module, opened by its own words GWAS, PROTEIN and DESIGNS.

### The join to genomics

The [genomics-showcase pack](guide.md)'s `study-variant` ([contract](../../../docs/contracts/packs/genomics-showcase.md#svariant-study-variant-study-variant),
[example](../../../docs/contracts/samples/genomics-showcase.md#study-variant)) carries a gene, a protein position and an amino acid; with the gene
as the protein's id they are the key of a `dms-score` row.

### Making the data again

One generator writes both packs: `python3 tools/packgen/genomics_showcase/make.py` (see the genomics-showcase guide).

### Data retrieval

Where each kind's data comes from, how it reaches the lake and how to check it. Three origins: **external** data, fetched from a public source by its data feed (each source, its licence and worked examples on the [External data](../../../docs/guides/WORLD_DATA_GUIDE.md) help page); **synthetic** test data (or hand-written reference data), written by a generator with fictitious names; **computed** by a Drishti engine when it is asked, never stored by a source.

| Command | Kind | Origin | How it reaches Drishti |
|---|---|---|---|
| `PROT` | `protein` | synthetic: `python3 tools/packgen/genomics_showcase/make.py` | `data/testdata/protein-engineering/protein/` → `lake demo` → connector `protein-engineering-store` |
| `RESIDUE` | `residue` | synthetic: `python3 tools/packgen/genomics_showcase/make.py` | `data/testdata/protein-engineering/residue/` → `lake demo` → connector `protein-engineering-store` |
| `CONTACT` | `residue-contact` | synthetic: `python3 tools/packgen/genomics_showcase/make.py` | `data/testdata/protein-engineering/residue-contact/` → `lake demo` → connector `protein-engineering-store` |
| `DMS` | `dms-score` | synthetic: `python3 tools/packgen/genomics_showcase/make.py` | `data/testdata/protein-engineering/dms-score/` → `lake demo` → connector `protein-engineering-store` |
| `DESIGN` | `design` | synthetic: `python3 tools/packgen/genomics_showcase/make.py` | `data/testdata/protein-engineering/design/` → `lake demo` → connector `protein-engineering-store` |
| `DMUT` | `design-mutation` | synthetic: `python3 tools/packgen/genomics_showcase/make.py` | `data/testdata/protein-engineering/design-mutation/` → `lake demo` → connector `protein-engineering-store` |
| `DROUND` | `design-round` | synthetic: `python3 tools/packgen/genomics_showcase/make.py` | `data/testdata/protein-engineering/design-round/` → `lake demo` → connector `protein-engineering-store` |

#### Generators

| Generator | Writes | Check |
|---|---|---|
| `python3 tools/packgen/genomics_showcase/make.py` | the genomics module's showcase, 200,000 invented rows from one seed (a cohort of 1,500 patients, an invented gene and protein, a design campaign) into data/testdata/genomics-showcase/<kind>/ and data/testdata/protein-engineering/<kind>/ | `python3 tools/packgen/genomics_showcase/make.py --check` |

#### Into the lake, checking, offline

- **The lake.** `python3 tools/drishti.py lake demo` reads `data/testdata/testdata.yaml` and writes every dataset (test data, the packs' samples and the committed public-data snapshots) into `<data>/delta/<domain>/<kind>/`, where the pack's Delta connector serves it; a computed kind needs nothing loaded. `lake build` loads your own large JSON exports the same way ([Command line](../../../docs/guides/CLI_REFERENCE.md)).
- **Checking.** `python3 tools/contracts.py check` compares every contract with the pack and its data; each generator's `--check` (above) fails when the committed files differ from what it would write; in the console, `<MNEMONIC> <GO>` lists a kind and the pack's code opens its overview with the count of each kind.
- **Offline.** Everything committed works without a network: the test data, the samples and the public-data snapshots are in the repository and `lake demo` reads only them. A data feed runs offline from its download cache (`--offline`); a connector that reaches the internet (news, yfinance, the public-data feeds) is off as shipped and never starts on a machine configured offline (`drishti.offline.plugins`).
