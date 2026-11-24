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
checks C1 to C14. See the [tutorial](tutorial.md).

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

## The design of the showcase

**Status: built** (module `genomics`, packs `genomics` and `protein`); the design the build followed: A showcase for the genomics module
(the `drishti.genomics` distribution, [DISTRIBUTIONS.md section 4](../../../docs/architecture/KERNEL_AND_MODULES.md#4-drishti-genomics-sample)) that helps sell the
system outside finance: two hero screens, one for **genomics** and one for **protein engineering**, joined by one story, on about
**200,000 rows** of synthetic, seeded data that load on a laptop with no network. The mock-ups are in
[mockups/genomics-showcase/](../../../docs/architecture/mockups/genomics-showcase/index.html); the questions for the owner are in section 12.

### 1. The idea in one paragraph

A cohort of 1,500 patients points at one gene. The gene's protein has been measured change by change in the laboratory, so every
variant a patient carries gets a functional reading at once. The same measurements then drive eight rounds of protein design, and the
audience watches the designs climb to a protein fifteen times more active than the natural one. **Genomics reads nature's changes;
protein engineering writes our own; the join is the protein.** What it sells is not biology: it is that one selection runs through
every card, every figure is answered by the server in milliseconds, every mark opens its records, and none of it is finance.

The gene (`HLXK7`) and its enzyme (Helixase-7, 312 residues) are invented, and every screen carries a "Synthetic" stamp with the seed.

### 2. The three-minute demo script

The presenter starts on Home of Drishti Genomics; the cohort card opens the genomics screen. Times are cumulative.

| Time | The presenter does | The presenter says | What the screen proves |
|---|---|---|---|
| 0:00 | Opens the cohort screen | "Fifteen hundred patients, twelve thousand sites tested against response to therapy. This is a laptop, with no network." | Loads in under a second |
| 0:20 | Points at the association plot; clicks the tallest peak | "Four places in the genome stand out. The tallest sits on a gene called HLXK7." | Every card follows the click: the matrix row, the lollipop, the locus, the figures, the command bar |
| 0:45 | Clicks the cluster name "C3 immune-hot" on the map of samples; then drags a rectangle | "Who carries it? Pick any group of patients, by name or by hand." | The cohort matrix narrows to the group; the response rate and the counts are answered again by the server |
| 1:10 | Points at "Response if altered" | "Patients with a change in this gene respond less than half as often. That is the finding." | A figure that follows the selection |
| 1:20 | Clicks a red lollipop head; Explain opens with the records | "Which changes matter? Each one sits on the protein, coloured by what that exact change does in the laboratory. And here are the patients behind the number." | Explain: the sentence, the steps, the rows |
| 1:45 | Clicks "Open HLXK7 in the protein workbench" | "Same protein, the other direction. Every position, every possible amino acid, measured: six thousand experiments in one picture." | The scan heatmap with the fold and the active site on it |
| 2:05 | Clicks a blue cell | "Orange hurts, blue helps. This change helps; here it is on the structure, and these are the designs that carry it." | The structure turns to the residue; the designs with the change are ringed |
| 2:20 | Presses **Play the rounds** | "Now we design. Eight rounds, fifteen hundred designs each. Watch them leave the natural protein, some get caught on a false peak, and the campaign reaches the summit." | The landscape animates; the box plot and the leaderboard follow each round |
| 2:45 | Clicks the lead design | "The winner: three changes, fifteen times the activity, five degrees more stable. Its changes are marked on the scan and on the molecule." | One design, shown on three cards at once |
| 2:55 | Types `GENE HLXK7` in the command bar | "Two hundred thousand rows, two sciences, one grammar. Your data would sit here the same way." | The grammar, and the close |

A shorter path for a phone: the guided mode (option C of the genomics mock-ups) walks the first five rows as four steps.

### 3. The hero views, ranked by effect for effort

Effect is how much an audience reacts; effort is builder-days for the visual alone (section 10 has the whole build).

#### 3.1 Genomics

| Rank | View | Why it lands | Effort | Verdict |
|---|---|---|---|---|
| 1 | **Association plot** (Manhattan), whole genome | The most recognised picture in genetics; one click on a peak drives the page | 1 day (a scatter kind) | Hero |
| 2 | **Cohort matrix** (oncoprint) with clinical tracks | Dense, colourful, instantly "a real cohort"; narrows when samples are brushed | 2 days | Hero |
| 3 | **Map of samples** with a brush | The brush is the moment people see the linking | 0.5 day (scatter exists; the brush is section 8) | Hero |
| 4 | **Lollipop coloured by the scan** | The bridge to the protein screen; the element exists | 0.5 day | Hero (it is the join) |
| 5 | Locus zoom over a gene track | Depth for a geneticist; quiet for anyone else | 1 day | Second wave |
| 6 | Copy-number track on the genome axis | Completes a genome-browser layout only | 0.5 day | Only with layout B |

#### 3.2 Protein engineering

| Rank | View | Why it lands | Effort | Verdict |
|---|---|---|---|---|
| 1 | **Design campaign** (fitness landscape) with **Play the rounds** | The only animation in the demo; a non-specialist understands it without a word | 1.5 days | Hero |
| 2 | **Deep mutational scan heatmap** with fold, burial and active-site tracks | "Six thousand experiments in one picture"; every cell opens records | 2 days | Hero |
| 3 | **Structure**, a 3D backbone trace | A turning molecule is what people photograph | 1.5 days on the vendored ECharts GL | Hero |
| 4 | Leaderboard and activity by round | The business answer (the winner, the progress); both elements exist | 0.5 day | Hero (cheap) |
| 5 | "Which changes won": change by round | Shows the ordinary pivot engine doing science | 0.5 day | Second wave |
| 6 | Contact map; one residue's twenty options | Depth for a structural biologist | 0.5 day each | Second wave |

#### 3.3 The structure viewer: 3D or 2D

| Option | What it gives | Cost | Verdict |
|---|---|---|---|
| A full molecular viewer (Mol\*, NGL, 3Dmol) | Ribbons, surfaces, ligands, real PDB files | A new vendored library of 1 to 5 MB with its own interaction, theme and accessibility model, outside the Elements rules; about 5 days to wrap, and a second drawing engine | **No**, not for a showcase |
| **A backbone trace on ECharts GL** (`line3D`, `scatter3D`) | One point per residue, coloured by any measure, turned by drag, with markers for the selection, the active site and a design's changes | Nothing new to vendor (ECharts GL is in `static/vendor/echarts-gl/`); 1.5 days; a drishti-chart kind like any other | **Yes** (recommended) |
| A 2D contact map only | The fold flattened; exact and accessible | 0.5 day on the heatmap | As the table alternative of the 3D trace and its fallback where WebGL is off |

The trace is honest about what the data is (synthetic coordinates, one per residue) and keeps one drawing engine. A customer with real
structures is the moment to revisit a full viewer, as its own decision.

### 4. How the views link

Linking uses what drishti-chart and drishti-grid already have: `link: {channel}` on a chart and `linkGrid(grid, {channel})` on a grid,
over drishti-core's bus. A page declares its channels; no card knows another.

| Channel | Set by | Followed by |
|---|---|---|
| `genomics.gene` | a peak of the association plot, a row of the cohort matrix, the gene track, the command bar | the matrix (row marked), the lollipop, the locus, the figures, the records grid |
| `genomics.samples` | a brush or a cluster on the map of samples; the clinical tracks of the matrix | the matrix (columns narrowed and sorted again), the lollipop's heights, the figures, the records grid |
| `genomics.variant` | a lollipop head, a row of the records grid | Explain |
| `protein.residue` | a cell of the scan, a residue of the structure or the contact map, a row of "which changes won" | the scan (column), the structure (turns to it, neighbours drawn), the campaign (designs with the change ringed), the residue card |
| `protein.design` | a point of the campaign, a row of the leaderboard | the scan (its cells framed), the structure (its changes marked), Explain |
| `protein.round` | the slider, Play, a box of the round chart | the campaign, the leaderboard, the figures, "which changes won" |

A selection is a **filter of the next server call**, never a client-side recount: the page sends the channel's value (a gene, a set of
sample ids or a cluster, a round) and draws the answer. Across the two screens the link is a route: the lollipop and the gene card open
`/proteins/HLXK7?residue=117`.

### 5. Explain and the drill to records

Every card has the Elements' Explain entry and every mark is clickable. Explain (drishti-insight) shows three things, as the VaR pages
do: the sentence, the steps the server took with the tables and row counts, and the records behind the mark in a drishti-grid that
opens the full grid. Examples in the mock-ups: a lollipop head (the carriers, joined to the scan's score for that exact substitution);
a cell of the scan (the three replicate measurements); a design (its changes, its measurements and its parent). The server returns the
explanation with the data; the page writes none of the sentences.

### 6. The data model

Two packs in the module, 14 kinds, **200,000 rows**. Names are UPPER_SNAKE; every table is JSONL loaded into Delta by the loader, and
served through the Delta connector like every other pack's data (Iceberg and the file store identically).

#### 6.1 Pack `genomics` (100,000 rows)

| Kind | Rows | Key | Main columns |
|---|---|---|---|
| `gene` | 2,000 | GENE | CHROM, START, END, STRAND, ROLE, PROTEIN_LENGTH |
| `sample` | 1,500 | SAMPLE_ID | COHORT, TUMOUR_TYPE, STAGE, AGE_BAND, RESPONSE, CLUSTER, EMB_X, EMB_Y |
| `variant` | 12,000 | VARIANT_ID | CHROM, POS, REF, ALT, GENE, CONSEQUENCE, IMPACT, PROTEIN_POS, AA_REF, AA_ALT (the existing contract, plus the two amino acids) |
| `variant_call` | 45,000 | SAMPLE_ID, VARIANT_ID | GENOTYPE, VAF, DEPTH |
| `association` | 36,000 | TRAIT, VARIANT_ID | P, NEG_LOG10_P, BETA, SE, R2_LEAD (3 traits × 12,000 sites) |
| `cnv_segment` | 3,000 | SAMPLE_ID, CHROM, START | END, COPY_NUMBER, CALL |
| `pathway_gene` | 500 | PATHWAY, GENE | ROLE_IN_PATHWAY |

#### 6.2 Pack `protein` (100,000 rows)

| Kind | Rows | Key | Main columns |
|---|---|---|---|
| `protein` | 4 | PROTEIN | GENE, LENGTH, FAMILY, SEQUENCE |
| `residue` | 1,196 | PROTEIN, POS | AA, SECONDARY, DOMAIN, BURIAL, ACTIVE_SITE, X, Y, Z, MEAN_ACTIVITY, MEAN_STABILITY (lengths 312, 248, 286, 350) |
| `residue_contact` | 5,952 | PROTEIN, POS_A, POS_B | DISTANCE |
| `dms_score` | 47,840 | PROTEIN, POS, AA, ASSAY | SCORE, SE, REPLICATES (1,196 positions × 20 changes × 2 assays) |
| `design` | 12,000 | DESIGN_ID | PROTEIN, ROUND, PARENT, N_CHANGES, ACTIVITY, DELTA_TM, MODEL_SCORE, STATUS, EMB_X, EMB_Y (8 rounds × 1,500) |
| `design_mutation` | 33,000 | DESIGN_ID, POS | AA_FROM, AA_TO, MUTATION |
| `design_round` | 8 | PROTEIN, ROUND | METHOD, STARTED, DESIGNS, NOTE |

**Precomputed, never computed at view time.** The sample and design embeddings (`EMB_X`, `EMB_Y`), the clusters, the residue
coordinates, burial, contacts, the per-residue means and the linkage with the lead variant are columns the generator writes. The
server's work at view time is what it does everywhere: filter, join, count, pivot, quantiles, sort and sample.

**The join.** `variant.GENE + PROTEIN_POS + AA_ALT` → `dms_score.PROTEIN + POS + AA`. This one join is the showcase's argument.

### 7. The generator

One standalone script, `tools/packgen/genomics_showcase/make.py` (built; planned first as a module script with `.sh` and `.ps1` twins), in the shape of the other
packs' generators: Python standard library only, one seed (`--seed`, default 20261116), one output per kind
(`data/testdata/genomics/<kind>/<kind>.<date>.jsonl.gz` and `data/testdata/protein/<kind>/…`), listed in `testdata.yaml` and loaded by
`drishti.py lake demo`. The same seed gives byte-identical files; a test holds the row counts of section 6 and the file checksums.

How it makes a believable world, in order:

1. **Genome and genes.** 23 chromosomes of plausible lengths; 2,000 genes placed at random with invented symbols; 24 "driver" genes with
   alteration frequencies falling from 40% to 5%.
2. **Samples.** Five clusters as Gaussian clouds in two dimensions (the embedding is the cloud, so it needs no projection); tumour type
   and stage drawn by cluster; each driver enriched in one cluster.
3. **Variants and calls.** Sites drawn per gene; three hotspots per driver in its domains; calls drawn per sample to a fixed quota so
   the table has exactly 45,000 rows.
4. **Response and association.** Response is drawn from a model with a planted effect of `HLXK7` and of the immune-hot cluster; the
   association table is then **computed from the calls and the response** (a real test per site), so the peak, the figures on the page
   and the records agree by construction and not by decoration. Three further loci are planted for three traits.
5. **Proteins.** For each of four proteins: a sequence, a secondary structure, and coordinates built by laying helices and strands on a
   grid and joining them with loops; burial and contacts are measured from those coordinates; an active site is chosen at the centre.
6. **The scan.** A score per position and amino acid from a small physical model (buried positions and the active site are
   intolerant; a like-for-like change is mild; proline breaks helices; stop codons are fatal) plus noise, with 14 planted beneficial
   changes on the surface.
7. **The campaign.** A fitness field with a global peak, a local one and a ridge; each round's 1,500 designs are drawn nearer the
   peak, part of rounds 3 to 6 trapped on the local one; each design's changes are drawn increasingly from the beneficial set, to a
   quota of exactly 33,000 rows; activity follows the field and stability follows the scan.

No real patient, organism, sequence or structure is used anywhere. Section 12 (Q2) asks whether gene symbols stay invented.
