---
# Project Drishti · Any data. Any domain. One grammar.
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository for the full terms.
module: genomics
title: Genomics
domain: genomics
distributions: [drishti.genomics, drishti.all]
summary: The genomics sample module, built from the Module SDK alone; a VCF reader, the kinds variant, sample, gene and cohort, the functions VARIANT, GENE and COHORT, and the variant lollipop visual.
---
# Genomics

A third party's module (publisher Helix Lab, a sample) made from the Module SDK's archetype and finished by hand. It uses the kernel API
and the plugin API only, and it carries no finance: Drishti Genomics boots the kernel with this module alone.

| Part | What |
|---|---|
| The VCF reader | `genomics-vcf`, a connector extension inside the module: reads VCF 4.x files (plain or gzipped) |
| Kinds | `variant` and `sample` (from the VCF files), `gene` and `cohort` (computed) |
| Functions | `VARIANT <id>`, `GENE <symbol>`, `COHORT <id>`; the Rachana function `genomics_af_class(af)` |
| Pages | one page folder, under `/m/genomics/`: variants, genes (with the lollipop), cohorts |
| Visual | `genomics.lollipop`, a kind of drishti-chart from the module's library `drishti-genomics` |
| API | `/api/v1/genomics/variants`, `/genes/{id}`, `/cohorts/{id}` |

## Data structures

| Kind | Key | Fields |
|---|---|---|
| `variant` | `VARIANT_ID` | CHROM, POS, REF, ALT, QUAL, FILTER, GENE, CONSEQUENCE, IMPACT, PROTEIN_POS, CARRIERS, AF, SAMPLES |
| `sample` | `SAMPLE_ID` | COHORT, SEX, POPULATION, VARIANTS |
| `gene` | `GENE` | CHROM, START, PROTEIN_LENGTH, DOMAINS, VARIANTS |
| `cohort` | `COHORT_ID` | SAMPLES, MEMBERS, VARIANTS, GENES |

The contracts are in `packs/genomics/contracts/`. Every sample and variant shipped is synthetic. Read the [guide](guide.md), then the
[tutorial](tutorial.md).
