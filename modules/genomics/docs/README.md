---
# Project Drishti · Any data. Any domain. One grammar.
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository for the full terms.
module: genomics
title: Genomics
domain: genomics
distributions: [drishti.genomics, drishti.all]
summary: The genomics sample module, built from the Module SDK alone; the kinds variant, sample, gene and cohort, the functions VARIANT, GENE and COHORT, and the variant lollipop visual.
---
# Genomics

A third party's module (publisher Helix Lab, a sample) made from the Module SDK's archetype and finished by hand. It uses the kernel API
and the plugin API only, and it carries no finance: Drishti Genomics boots the kernel with this module alone.

| Part | What |
|---|---|
| The data | the genomics lake's tables `genomics.variant`, `.sample`, `.gene`, `.cohort`, read through the genomics pack's connector; your VCF files and tables reach it through the genomics loader |
| Kinds | `variant`, `sample`, `gene` and `cohort` (the cohorts computed from the samples unless loaded) |
| Functions | `VARIANT <id>`, `GENE <symbol>`, `COHORT <id>`, `GWAS <trait>`, `PROTEIN <gene>`, `DESIGNS <gene>`; the Rachana function `genomics_af_class(af)` |
| Pages | under `/m/genomics/`: variants, genes (with the lollipop), cohorts; the showcase: the linked board, its guided mode, the protein workbench |
| Visuals | `genomics.lollipop`, `genomics.manhattan`, `genomics.oncoprint`, `protein.dms`, `protein.backbone`, `protein.landscape`: glue in the module's library `drishti-genomics` over drishti-chart's generic kinds |
| API | `/api/v1/genomics/variants`, `/genes/{id}`, `/cohorts/{id}`; the showcase's calls (`showcase/status`, `associations`, `cohorts/{id}/summary`, `oncoprint`, `samples/map`, `genes/{id}/lollipop`, `proteins/{id}/…`, `explain/…`: the guide lists them) |

## Data structures

| Kind | Key | Fields |
|---|---|---|
| `variant` | `VARIANT_ID` | CHROM, POS, REF, ALT, QUAL, FILTER, GENE, CONSEQUENCE, IMPACT, PROTEIN_POS, CARRIERS, AF, SAMPLES |
| `sample` | `SAMPLE_ID` | COHORT, SEX, POPULATION, VARIANTS |
| `gene` | `GENE` | CHROM, START, PROTEIN_LENGTH, DOMAINS, VARIANTS |
| `cohort` | `COHORT_ID` | SAMPLES, MEMBERS, VARIANTS, GENES |

The contracts are in `packs/genomics/contracts/`. Every sample and variant shipped is synthetic. Read the [guide](guide.md), then the
[tutorial](guide.md).
