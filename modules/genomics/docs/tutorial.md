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
# Genomics: the tutorial

1. **Start Drishti Genomics.** `python tools/drishti.py start all --distribution genomics` (Windows: `py tools\drishti.py start all --distribution genomics`). The
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

## The three-minute demo: the showcase

The script of the demo, step by step (the design's section 2). Each step gives the UI, the command line where there is one, and the YAML
or address behind it. The data is synthetic, made by the module's generator and loaded with the other packs.

1. **Load the data.** `python tools/drishti.py lake demo` (Windows: `py tools\drishti.py lake demo`) loads the packs `genomics` and
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

