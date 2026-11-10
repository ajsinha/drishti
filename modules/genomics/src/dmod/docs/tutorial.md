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

1. **Start Drishti Genomics.** `bin/start-all.sh --distribution genomics` (Windows: `bin\start-all.ps1 -Distribution genomics`). The
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
