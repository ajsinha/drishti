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

# The genomics showcase: one protein, read and rewritten

**Status: design and mock-ups, for the product owner's choice. Nothing is built.** A showcase for the genomics module
(the `drishti.genomics` distribution, [DISTRIBUTIONS.md section 4](DISTRIBUTIONS.md#4-drishti-genomics-sample)) that helps sell the
system outside finance: two hero screens, one for **genomics** and one for **protein engineering**, joined by one story, on about
**200,000 rows** of synthetic, seeded data that load on a laptop with no network. The mock-ups are in
[mockups/genomics-showcase/](mockups/genomics-showcase/index.html); the questions for the owner are in section 12.

## Contents

1. The idea in one paragraph
2. The three-minute demo script
3. The hero views, ranked by effect for effort
4. How the views link
5. Explain and the drill to records
6. The data model
7. The generator
8. Drishti Elements: what exists, what is new
9. The server: words, APIs, Sutras
10. Effort
11. Risks
12. Questions for the product owner
13. The mock-ups and the build checklist

## 1. The idea in one paragraph

A cohort of 1,500 patients points at one gene. The gene's protein has been measured change by change in the laboratory, so every
variant a patient carries gets a functional reading at once. The same measurements then drive eight rounds of protein design, and the
audience watches the designs climb to a protein fifteen times more active than the natural one. **Genomics reads nature's changes;
protein engineering writes our own; the join is the protein.** What it sells is not biology: it is that one selection runs through
every card, every figure is answered by the server in milliseconds, every mark opens its records, and none of it is finance.

The gene (`HLXK7`) and its enzyme (Helixase-7, 312 residues) are invented, and every screen carries a "Synthetic" stamp with the seed.

## 2. The three-minute demo script

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

## 3. The hero views, ranked by effect for effort

Effect is how much an audience reacts; effort is builder-days for the visual alone (section 10 has the whole build).

### 3.1 Genomics

| Rank | View | Why it lands | Effort | Verdict |
|---|---|---|---|---|
| 1 | **Association plot** (Manhattan), whole genome | The most recognised picture in genetics; one click on a peak drives the page | 1 day (a scatter kind) | Hero |
| 2 | **Cohort matrix** (oncoprint) with clinical tracks | Dense, colourful, instantly "a real cohort"; narrows when samples are brushed | 2 days | Hero |
| 3 | **Map of samples** with a brush | The brush is the moment people see the linking | 0.5 day (scatter exists; the brush is section 8) | Hero |
| 4 | **Lollipop coloured by the scan** | The bridge to the protein screen; the element exists | 0.5 day | Hero (it is the join) |
| 5 | Locus zoom over a gene track | Depth for a geneticist; quiet for anyone else | 1 day | Second wave |
| 6 | Copy-number track on the genome axis | Completes a genome-browser layout only | 0.5 day | Only with layout B |

### 3.2 Protein engineering

| Rank | View | Why it lands | Effort | Verdict |
|---|---|---|---|---|
| 1 | **Design campaign** (fitness landscape) with **Play the rounds** | The only animation in the demo; a non-specialist understands it without a word | 1.5 days | Hero |
| 2 | **Deep mutational scan heatmap** with fold, burial and active-site tracks | "Six thousand experiments in one picture"; every cell opens records | 2 days | Hero |
| 3 | **Structure**, a 3D backbone trace | A turning molecule is what people photograph | 1.5 days on the vendored ECharts GL | Hero |
| 4 | Leaderboard and activity by round | The business answer (the winner, the progress); both elements exist | 0.5 day | Hero (cheap) |
| 5 | "Which changes won": change by round | Shows the ordinary pivot engine doing science | 0.5 day | Second wave |
| 6 | Contact map; one residue's twenty options | Depth for a structural biologist | 0.5 day each | Second wave |

### 3.3 The structure viewer: 3D or 2D

| Option | What it gives | Cost | Verdict |
|---|---|---|---|
| A full molecular viewer (Mol\*, NGL, 3Dmol) | Ribbons, surfaces, ligands, real PDB files | A new vendored library of 1 to 5 MB with its own interaction, theme and accessibility model, outside the Elements rules; about 5 days to wrap, and a second drawing engine | **No**, not for a showcase |
| **A backbone trace on ECharts GL** (`line3D`, `scatter3D`) | One point per residue, coloured by any measure, turned by drag, with markers for the selection, the active site and a design's changes | Nothing new to vendor (ECharts GL is in `static/vendor/echarts-gl/`); 1.5 days; a drishti-chart kind like any other | **Yes** (recommended) |
| A 2D contact map only | The fold flattened; exact and accessible | 0.5 day on the heatmap | As the table alternative of the 3D trace and its fallback where WebGL is off |

The trace is honest about what the data is (synthetic coordinates, one per residue) and keeps one drawing engine. A customer with real
structures is the moment to revisit a full viewer, as its own decision.

## 4. How the views link

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

## 5. Explain and the drill to records

Every card has the Elements' Explain entry and every mark is clickable. Explain (drishti-insight) shows three things, as the VaR pages
do: the sentence, the steps the server took with the tables and row counts, and the records behind the mark in a drishti-grid that
opens the full grid. Examples in the mock-ups: a lollipop head (the carriers, joined to the scan's score for that exact substitution);
a cell of the scan (the three replicate measurements); a design (its changes, its measurements and its parent). The server returns the
explanation with the data; the page writes none of the sentences.

## 6. The data model

Two packs in the module, 14 kinds, **200,000 rows**. Names are UPPER_SNAKE; every table is JSONL loaded into Delta by the loader, and
served through the Delta connector like every other pack's data (Iceberg and the file store identically).

### 6.1 Pack `genomics` (100,000 rows)

| Kind | Rows | Key | Main columns |
|---|---|---|---|
| `gene` | 2,000 | GENE | CHROM, START, END, STRAND, ROLE, PROTEIN_LENGTH |
| `sample` | 1,500 | SAMPLE_ID | COHORT, TUMOUR_TYPE, STAGE, AGE_BAND, RESPONSE, CLUSTER, EMB_X, EMB_Y |
| `variant` | 12,000 | VARIANT_ID | CHROM, POS, REF, ALT, GENE, CONSEQUENCE, IMPACT, PROTEIN_POS, AA_REF, AA_ALT (the existing contract, plus the two amino acids) |
| `variant_call` | 45,000 | SAMPLE_ID, VARIANT_ID | GENOTYPE, VAF, DEPTH |
| `association` | 36,000 | TRAIT, VARIANT_ID | P, NEG_LOG10_P, BETA, SE, R2_LEAD (3 traits × 12,000 sites) |
| `cnv_segment` | 3,000 | SAMPLE_ID, CHROM, START | END, COPY_NUMBER, CALL |
| `pathway_gene` | 500 | PATHWAY, GENE | ROLE_IN_PATHWAY |

### 6.2 Pack `protein` (100,000 rows)

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

## 7. The generator

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

## 8. Drishti Elements: what exists, what is new

No screen code draws anything: every card is an Elements component, and what is missing is added to a library (the standing rule,
[ELEMENTS.md section 3.1](https://github.com/ajsinha/drishti-elements/blob/v2.4.0/docs/DESIGN.md)). Domain kinds go to the module's own library
(`modules/genomics/elements/drishti-genomics/`), built from drishti-chart's primitives; anything another domain would use goes to the
generic library.

**When the data or the API is not there, the screens say so.** The page first asks the module's server `showcase/status`. With a kind of
the showcase packs missing it names the kinds and how to load them; when a call fails (the API not served: `404`, `410` or `503`; not
allowed; no answer; a server error) it shows, in place of the cards, which call failed, the server's words with the HTTP status and what
to do (for a missing API: turn the genomics module on in Admin → Modules on that server). It never stays on "Loading…".

| Card | Built from | State |
|---|---|---|
| Figures, command bar, tabs, selection chip | drishti-core, drishti-inputs | exist |
| Records, leaderboard | drishti-grid (static mode), `linkGrid` | exist |
| "Which changes won" | drishti-grid pivot with heat cells | exists |
| Activity by round | drishti-chart `boxplot` | exists |
| One residue's options | drishti-chart `bar` | exists |
| Map of samples | drishti-chart `scatter` + **a rectangle brush that publishes a selection** | scatter exists; the two-axis brush is new, generic (extends `rangeBrush`) |
| Lollipop | `genomics.lollipop` + **colour by a measure** | exists; one option added |
| Association plot | **`genomics.manhattan`**: scatter on a banded genome axis, threshold line, peak labels | new, drishti-genomics |
| Cohort matrix | **`genomics.oncoprint`**: heatmap with glyph cells and annotation tracks | new, drishti-genomics, on the generic tracks below |
| Locus zoom | **`genomics.locus`**: scatter over a gene track | new, drishti-genomics |
| Scan heatmap | **`protein.dms`**: diverging heatmap with an overview brush and annotation tracks | new, drishti-genomics |
| Structure, 3D | **`protein.backbone`**: ECharts GL `line3D` and `scatter3D` | new, drishti-genomics; engine vendored already |
| Structure, flat | **`protein.contacts`**: heatmap | new, small |
| Design campaign | **`protein.landscape`**: scatter over a heat field, with frames | new, drishti-genomics |
| Explain | drishti-insight | exists |

**Three generic additions to drishti-chart**, each useful to finance too: (1) **annotation tracks** on a heatmap's axes (categorical
or numeric strips aligned to the cells); (2) the **rectangle brush** on a scatter, publishing to a link channel; (3) **frames**: a
spec's `frames: {field, play}` that steps one field (a round, a date) with a slider and a Play button, respecting reduced motion. Each
gets its gallery Playground page and its data-table alternative. The module's library stays one file per kind under 2,000 lines, with
`sample()` for the gallery, and is declared in `console/plugin.yaml` under `visuals:`.

**Phone.** The rows of cards fold to one column; the scan shows a narrower window with the overview strip to move it; the brush is a
desktop gesture (a tap on a cluster name does the same); the guided mode is the phone's default.

## 9. The server: words, APIs, Sutras

The server computes; the console displays ([THIN_CONSOLE_DESIGN.md](THIN_CONSOLE_DESIGN.md), the thin console). The module's Java part
serves its API under its prefix (`genomics`); every answer carries its Explain block.

**Words** (one way per job: the existing words grow, three are added).

| Word | Opens |
|---|---|
| `GENE HLXK7` (exists) | the gene: lollipop coloured by the scan, locus, cohort figures |
| `COHORT ONC-1` (exists) | the genomics hero screen |
| `VARIANT <id>` (exists) | the variant and its carriers |
| `GWAS RESPONSE` (new) | the association plot for a trait |
| `PROTEIN HLXK7` (new) | the protein hero screen |
| `DESIGNS HLXK7` (new) | the campaign, at a round |

**APIs** (all `GET`, all accept `samples=` or `cluster=` where a selection applies).

| Call | Answers | Server work |
|---|---|---|
| `/cohorts/{id}/summary` | the figures | counts and rates over `sample`, `variant_call` |
| `/associations?trait=` | points for the association plot | every site below 1e-4 and a density-preserving sample of the rest (about 5,400 of 12,000) |
| `/associations/locus?gene=&trait=` | the locus and its gene track | range filter |
| `/cohorts/{id}/oncoprint?genes=24` | the non-empty cells, the tracks, the gene and sample order | pivot of `variant_call` and `cnv_segment`; the mutual-exclusivity sort |
| `/cohorts/{id}/samples/map` | 1,500 points | none (columns) |
| `/genes/{id}/lollipop` | variants with carriers and the scan's score | count, join to `dms_score` |
| `/proteins/{id}/dms?assay=` | the matrix and the residue tracks | one partition of `dms_score`, `residue` |
| `/proteins/{id}/structure` | residues with coordinates; contacts | none (columns) |
| `/proteins/{id}/designs?round=` | each round's top 50 and a stratified sample; the best of each round; the field | filter, rank, sample |
| `/proteins/{id}/rounds` | quantiles of activity by round | group and quantile |
| `/proteins/{id}/enrichment` | change by round shares | the pivot engine on `design_mutation` |
| `/explain/{what}/{id}` | the sentence, the steps, the records | the same queries, with their row counts |

Payloads are small by design (the largest is the scan matrix, 6,240 cells); each answer is cached by its query and the lake's version.

**Sutras.** Each kind gets its Sutra in the pack (`modules/genomics/packs/<pack>/sutras/`), and the two hero screens are Sutras of
panels, so a customer can copy and change them in the designer: `cohort-showcase` and `protein-workbench`. The guided mode is a third
Sutra over the same panels.

## 10. Effort

Builder-days for one builder; three builders in parallel finish the full scope in about two working weeks.

| Part | Days |
|---|---|
| Generator, 14 contracts, test-data entries, loader and row-count tests | 4 |
| Server: kinds, the 12 calls, the three words, Explain blocks, tests | 5 |
| drishti-chart: annotation tracks, rectangle brush, frames; gallery pages | 3 |
| drishti-genomics: manhattan, oncoprint, locus, lollipop colour | 4.5 |
| drishti-genomics: dms, backbone, contacts, landscape | 5.5 |
| The two screens as Sutras, linking, the guided mode, phone | 3 |
| Guide, tutorial (UI and CLI), help, README, screenshots, browser walk test | 3 |
| **Full scope** | **28** |
| **First cut** (the heroes of section 3 only: no locus, copy number, contact map, residue options, "which changes won") | **19** |

## 11. Risks

| Risk | Answer |
|---|---|
| A specialist finds the synthetic biology naive | Plant effects through simple, defensible models (section 7); the association is computed, not painted; stamp "Synthetic" everywhere; have one scientist read the script before a customer sees it |
| Synthetic results on real gene names read as findings | Invented symbols by default (Q2) |
| WebGL is off on a demo laptop or a phone | `protein.backbone` falls back to the contact map; the walk test covers both |
| The sample module becomes a product by accident | It stays the sample publisher's module and out of Drishti All; a real genomics product is a separate decision (Q7) |
| The module today serves its kinds from VCF in memory; the showcase reads Delta | The new kinds are ordinary pack kinds on the Delta connector; the VCF reader stays for `variant` from files. One way per kind, to be confirmed at build |
| Scope creep toward a genome browser or a molecular viewer | Section 3 fixes the heroes; anything else is a later wave |
| 200,000 rows undersells scale | The script says so once ("a laptop"); the generator takes `--scale` for a later large run on the same screens |

## 12. Questions for the product owner

| # | Question | Recommendation |
|---|---|---|
| Q1 | Which layouts? | **Genomics A (the linked board)** and **Protein A (the workbench)**, with **Genomics C as the guided mode** of A for a phone and a first-time presenter |
| Q2 | Invented gene and protein names (HLXK7, Helixase-7), or real symbols (EGFR, TP53) on synthetic numbers? | **Invented.** Real names win recognition but make synthetic results look like findings |
| Q3 | The structure: a backbone trace on the vendored ECharts GL, or a full molecular viewer? | **The trace** (section 3.3); no new vendored library |
| Q4 | Full scope (28 days) or the first cut (19 days)? | **The first cut**, then the second wave if the demos land |
| Q5 | One pack or two (`genomics`, `protein`)? | **Two**, in the one module: each can be switched off, and the join between them is the point |
| Q6 | New words `GWAS`, `PROTEIN`, `DESIGNS`? | **Yes**; `GENE`, `COHORT` and `VARIANT` grow rather than gain siblings |
| Q7 | Does genomics stay a sample (out of Drishti All, not for customers), or become a demo distribution shown to prospects? | **A demo distribution**: keep it out of All, but list it in the decks as the proof beyond finance |
| Q8 | Which release? | After i5 moves the module to the top-level layout; front-load the generator and the three generic chart additions when a builder is free |
| Q9 | A larger run (2 to 20 million rows) of the same screens for the scale story? | **Later**, with the benchmark work; the generator's `--scale` is built now |
| Q10 | The three chart additions (tracks, brush, frames) go to the generic drishti-chart? | **Yes**: finance uses all three (heatmap tracks for limits, the brush for scatter selection, frames for dates) |

## 13. The mock-ups and the build checklist

Self-contained pages (everything inline, no network), dark by default, `#light` or the Theme button for light, usable at phone width.
Each is clickable: the selection, the brush, Explain, Play and the links between the two screens work.

| File | Layout |
|---|---|
| [index.html](mockups/genomics-showcase/index.html) | the six options |
| [genomics-A.html](mockups/genomics-showcase/genomics-A.html) | **A, the linked board (recommended)**: association plot, cohort matrix, map of samples, lollipop, locus, records |
| [genomics-B.html](mockups/genomics-showcase/genomics-B.html) | B, the genome browser: tracks on one genome axis, the gene and cohort in a side panel |
| [genomics-C.html](mockups/genomics-showcase/genomics-C.html) | C, the guided story: four steps with the presenter's sentence |
| [protein-A.html](mockups/genomics-showcase/protein-A.html) | **A, the workbench (recommended)**: scan, structure, campaign, rounds, leaderboard |
| [protein-B.html](mockups/genomics-showcase/protein-B.html) | B, structure first: the molecule as the hero |
| [protein-C.html](mockups/genomics-showcase/protein-C.html) | C, the design race: the campaign as the hero, with "which changes won" |

**Build checklist** (after the owner's choices).

- [ ] The generator, its `.sh` and `.ps1`, the 14 contracts and READMEs of the two packs, the test-data entries; row counts and checksums tested
- [ ] The kinds on the Delta connector; the storage parity test (Delta, Iceberg, file store)
- [ ] The 12 calls and three words, each with its Explain block; no figure computed in the console (`test_console_does_not_aggregate.py`)
- [ ] drishti-chart: annotation tracks, rectangle brush, frames; a Playground page and a data table each
- [ ] drishti-genomics: the kinds of section 8, each with `sample()`, declared under `visuals:`; nothing drawn by hand
- [ ] The two screens and the guided mode as Sutras; the channels of section 4; the route between the screens
- [ ] Dark and light, three densities, phone width, keyboard, axe on every page; reduced motion stops Play and the turning molecule
- [ ] Runs with no network and no broker; nothing from a CDN
- [ ] The guide and the tutorial (UI, CLI and YAML at every step), help, screenshots, the browser walk of the script in section 2
- [ ] The decks: one chapter, "beyond finance"
