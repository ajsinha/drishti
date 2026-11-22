# Project Drishti · Any data. Any domain. One grammar.
#
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
# All rights reserved.
#
# PROPRIETARY AND CONFIDENTIAL.
#
# This file is the confidential and proprietary property of Ashutosh Sinha.
# Unauthorised copying, use, modification, distribution or disclosure of this
# file, via any medium, is strictly prohibited except with the express prior
# written permission of the copyright holder.
#
# See the LICENSE file in the root of this repository for the full terms.

"""What the two showcase packs hold: every kind, its fields and what each means.

The one source of the contracts, the Sutras, the README's data structures, the About card, the search forms and the lake's layout:
tools/packgen/genomics_showcase/files.py writes them from this table, model.py writes the data it describes. Field order here is the
order of every document's keys. Everything is synthetic: the genes, the protein, the patients and the designs are invented.
"""
from __future__ import annotations

from dataclasses import dataclass

GENOMICS = "genomics-showcase"
PROTEIN = "protein-engineering"


@dataclass(frozen=True)
class F:
    """One field: name, contract type (id, text, integer, amount, rate, boolean, date, list), what it means, an example, a unit, whether
    every document has it, the kind it links to and the values it takes when few."""
    name: str
    type: str
    means: str
    example: object = None
    unit: str | None = None
    presence: str = "required"
    link: str | None = None
    enum: tuple = ()


@dataclass
class Kind:
    kind: str
    pack: str
    word: str            # the mnemonic
    title: str
    key: str
    prefix: str | None   # the id's first characters (the graph's id pattern), when it has a fixed one
    description: str
    grain: str
    fields: list
    strip: list          # the fields of the Sutra's strip, the record's headline
    columns: list        # the list view's columns
    promote: list        # the lake table's promoted columns (what a search can filter on)
    search: list         # the fields of the kind's search form
    rows: int = 0        # how many documents the generator writes (a test holds it)
    label: str = ""


KINDS: list[Kind] = [
    # ---------------------------------------------------------------------------------------------------------- genomics-showcase
    Kind("study-gene", GENOMICS, "SGENE", "Study gene", "GENE", None,
         "One gene of the synthetic genome: where it sits, what it does, how long its protein is and how many samples of the cohort carry a "
         "damaging change in it. 24 of the 2,000 genes are the planted drivers, led by HLXK7.",
         "One row per gene, keyed by GENE.",
         [F("GENE", "id", "The gene's invented symbol (HLX followed by letters and digits).", "HLXK7"),
          F("CHROM", "text", "The chromosome: 1 to 22 or X.", "7"),
          F("START", "integer", "The first base of the gene on its chromosome.", 61864222, "bp"),
          F("END", "integer", "The last base of the gene on its chromosome.", 61917398, "bp"),
          F("STRAND", "text", "The strand the gene is read from.", "+", enum=("+", "-")),
          F("ROLE", "text", "What the gene's product does.", "kinase (drug target)"),
          F("PROTEIN_LENGTH", "integer", "The amino acids of its protein: the axis of the variant lollipop.", 312, "amino acids"),
          F("DRIVER", "boolean", "True for the 24 driver genes whose alterations the generator plants in the cohort.", True),
          F("VARIANTS", "integer", "How many variant sites the gene has. Source: counted from study-variant.", 220, "sites"),
          F("ALTERED_SAMPLES", "integer", "How many samples carry a damaging change (high or moderate impact) in the gene. Source: counted from "
            "variant-call.", 598, "samples"),
          F("ALTERED_FRACTION", "rate", "ALTERED_SAMPLES over the cohort's 1,500 samples.", 0.3987, "fraction")],
         ["GENE", "CHROM", "ROLE", "PROTEIN_LENGTH", "ALTERED_FRACTION"],
         ["GENE", "CHROM", "ROLE", "PROTEIN_LENGTH", "DRIVER", "ALTERED_SAMPLES"],
         ["GENE", "CHROM", "START", "END", "ROLE", "PROTEIN_LENGTH", "DRIVER", "ALTERED_SAMPLES"],
         ["GENE", "CHROM", "ROLE", "DRIVER"], 2000, "Gene of the study"),
    Kind("study-sample", GENOMICS, "SSAMPLE", "Study sample", "SAMPLE_ID", "S",
         "One patient's sample of the 1,500 of cohort ONC-1: the clinical tracks, the response to therapy, the cluster it belongs to and its "
         "place on the two-dimensional map of samples (precomputed, never projected at view time).",
         "One row per sample, keyed by SAMPLE_ID.",
         [F("SAMPLE_ID", "id", "The sample's id: S and four digits.", "S0042"),
          F("COHORT", "text", "The cohort the sample belongs to.", "ONC-1"),
          F("TUMOUR_TYPE", "text", "The tumour type.", "Colorectal", enum=("Breast", "Lung", "Colorectal", "Ovarian")),
          F("STAGE", "text", "The stage at sampling.", "II", enum=("I", "II", "III", "IV")),
          F("AGE_BAND", "text", "The patient's age in ten-year bands.", "60-69"),
          F("RESPONSE", "text", "The response to therapy.", "responder", enum=("responder", "non-responder")),
          F("TOXICITY", "text", "Whether the patient had a severe adverse reaction.", "none", enum=("severe", "none")),
          F("RELAPSE", "text", "Whether the disease returned within twelve months.", "free", enum=("relapsed", "free")),
          F("CLUSTER", "text", "The group of similar samples the map shows.", "C3 immune-hot",
            enum=("C1 luminal-like", "C2 basal-like", "C3 immune-hot", "C4 stromal", "C5 proliferative")),
          F("EMB_X", "amount", "The sample's place on the map, left to right (0 to 1). Written by the generator.", 0.4871),
          F("EMB_Y", "amount", "The sample's place on the map, top to bottom (0 to 1). Written by the generator.", 0.6032),
          F("MUTATION_COUNT", "integer", "How many variant calls the sample has. Source: counted from variant-call.", 31, "calls")],
         ["SAMPLE_ID", "TUMOUR_TYPE", "STAGE", "RESPONSE", "CLUSTER"],
         ["SAMPLE_ID", "TUMOUR_TYPE", "STAGE", "AGE_BAND", "RESPONSE", "CLUSTER"],
         ["SAMPLE_ID", "COHORT", "TUMOUR_TYPE", "STAGE", "RESPONSE", "TOXICITY", "RELAPSE", "CLUSTER"],
         ["SAMPLE_ID", "TUMOUR_TYPE", "STAGE", "RESPONSE", "CLUSTER"], 1500, "Sample of the cohort"),
    Kind("study-variant", GENOMICS, "SVARIANT", "Study variant", "VARIANT_ID", "HX-",
         "One variant site of the synthetic genome: its place, the change it makes to the gene's protein (the amino acids before and after, "
         "the key into the protein workbench's scan) and how many samples carry it.",
         "One row per variant site, keyed by VARIANT_ID.",
         [F("VARIANT_ID", "id", "HX- and six digits, in genome order.", "HX-004711"),
          F("CHROM", "text", "The chromosome.", "7"),
          F("POS", "integer", "The base the variant is at.", 61887913, "bp"),
          F("REF", "text", "The base the reference has there.", "G"),
          F("ALT", "text", "The base the variant has there.", "A"),
          F("GENE", "id", "The gene the variant lies in; opens SGENE.", "HLXK7", link="study-gene"),
          F("CONSEQUENCE", "text", "What the change does to the gene's product.", "missense_variant",
            enum=("missense_variant", "synonymous_variant", "stop_gained", "frameshift_variant", "splice_region_variant", "intron_variant")),
          F("IMPACT", "text", "How much the change matters: HIGH, MODERATE, LOW or MODIFIER.", "MODERATE",
            enum=("HIGH", "MODERATE", "LOW", "MODIFIER")),
          F("PROTEIN_POS", "integer", "The amino acid position in the protein; empty for a change outside the coding sequence.", 117,
            presence="optional"),
          F("AA_REF", "text", "The amino acid before the change (one letter); empty outside the coding sequence.", "L", presence="optional"),
          F("AA_ALT", "text", "The amino acid after the change (one letter, * for a stop, - for a frameshift); empty outside the coding "
            "sequence. With the gene and PROTEIN_POS it is the key into dms-score (PROTEIN, POS, AA).", "P", presence="optional"),
          F("CARRIERS", "integer", "How many samples carry the variant. Source: counted from variant-call.", 372, "samples")],
         ["VARIANT_ID", "GENE", "CONSEQUENCE", "IMPACT", "CARRIERS"],
         ["VARIANT_ID", "GENE", "CONSEQUENCE", "IMPACT", "PROTEIN_POS", "AA_REF", "AA_ALT", "CARRIERS"],
         ["VARIANT_ID", "GENE", "CHROM", "POS", "CONSEQUENCE", "IMPACT", "PROTEIN_POS", "CARRIERS"],
         ["VARIANT_ID", "GENE", "CHROM", "CONSEQUENCE", "IMPACT"], 12000, "Variant site of the study"),
    Kind("variant-call", GENOMICS, "VCALL", "Variant call", "CALL_ID", "CALL-",
         "One sample carrying one variant: the genotype, the fraction of reads that show it and the read depth. 45,000 calls over 1,500 "
         "samples; the association tests are computed from them.",
         "One row per sample and variant, keyed by CALL_ID.",
         [F("CALL_ID", "id", "CALL- and six digits, in sample and variant order.", "CALL-012345"),
          F("SAMPLE_ID", "id", "The sample; opens SSAMPLE.", "S0042", link="study-sample"),
          F("VARIANT_ID", "id", "The variant; opens SVARIANT.", "HX-004711", link="study-variant"),
          F("GENE", "id", "The gene the variant lies in; opens SGENE.", "HLXK7", link="study-gene"),
          F("GENOTYPE", "text", "0/1 heterozygous, 1/1 homozygous for the variant.", "0/1", enum=("0/1", "1/1")),
          F("VAF", "rate", "The variant allele fraction: the share of reads that show the variant.", 0.412, "fraction"),
          F("DEPTH", "integer", "How many reads cover the site.", 187, "reads")],
         ["CALL_ID", "SAMPLE_ID", "VARIANT_ID", "GENOTYPE", "VAF"],
         ["CALL_ID", "SAMPLE_ID", "VARIANT_ID", "GENE", "GENOTYPE", "VAF", "DEPTH"],
         ["CALL_ID", "SAMPLE_ID", "VARIANT_ID", "GENE", "GENOTYPE", "VAF"],
         ["SAMPLE_ID", "VARIANT_ID", "GENE", "GENOTYPE"], 45000, "Variant call"),
    Kind("association", GENOMICS, "ASSOC", "Association of a variant with a trait", "ASSOC_ID", "GWAS-",
         "One variant tested against one trait: the p-value, its negative log, the effect and its error and how strongly the variant is "
         "linked to the peak's lead. Three traits over 12,000 sites: 36,000 tests, each computed from the calls and the samples' traits "
         "(a Wald test on the log odds ratio), never painted.",
         "One row per trait and variant, keyed by ASSOC_ID.",
         [F("ASSOC_ID", "id", "GWAS-, the trait, - and the variant's id.", "GWAS-RESPONSE-HX-004711"),
          F("TRAIT", "text", "The trait tested: RESPONSE (responder), TOXICITY (severe) or RELAPSE (relapsed).", "RESPONSE",
            enum=("RESPONSE", "TOXICITY", "RELAPSE")),
          F("VARIANT_ID", "id", "The variant tested; opens SVARIANT.", "HX-004711", link="study-variant"),
          F("CHROM", "text", "The variant's chromosome.", "7"),
          F("POS", "integer", "The variant's base on it.", 61887913, "bp"),
          F("GENE", "id", "The gene the variant lies in; opens SGENE.", "HLXK7", link="study-gene"),
          F("P", "amount", "The two-sided p-value of the test; 1 where no sample carries the variant.", 1.2e-23),
          F("NEG_LOG10_P", "amount", "Minus the base-10 logarithm of P: the height on the association plot.", 22.92),
          F("BETA", "amount", "The log odds ratio of the trait for a carrier against a non-carrier (0.5 added to each cell); empty without "
            "carriers.", -1.84, presence="optional"),
          F("SE", "amount", "The standard error of BETA; empty without carriers.", 0.19, presence="optional"),
          F("R2_LEAD", "amount", "The squared correlation of the variant's carriers with the carriers of the lead variant of the peak it lies "
            "in (within 2 Mb); 0 outside a peak.", 0.82)],
         ["ASSOC_ID", "TRAIT", "GENE", "NEG_LOG10_P", "BETA"],
         ["ASSOC_ID", "TRAIT", "VARIANT_ID", "GENE", "NEG_LOG10_P", "BETA", "R2_LEAD"],
         ["TRAIT", "CHROM", "VARIANT_ID", "GENE", "NEG_LOG10_P", "BETA"],
         ["TRAIT", "GENE", "CHROM", "NEG_LOG10_P"], 36000, "Association"),
    Kind("cnv-segment", GENOMICS, "CNV", "Copy-number segment", "SEGMENT_ID", "CNV-",
         "One stretch of a sample's genome with a copy number other than two: an amplification or a deletion over a driver gene, or a "
         "broad gain or loss.",
         "One row per sample segment, keyed by SEGMENT_ID.",
         [F("SEGMENT_ID", "id", "CNV- and five digits.", "CNV-00421"),
          F("SAMPLE_ID", "id", "The sample; opens SSAMPLE.", "S0042", link="study-sample"),
          F("CHROM", "text", "The chromosome.", "7"),
          F("START", "integer", "The first base of the segment.", 61300000, "bp"),
          F("END", "integer", "The last base of the segment.", 62400000, "bp"),
          F("COPY_NUMBER", "integer", "How many copies of the stretch the sample has (2 is normal).", 6, "copies"),
          F("CALL", "text", "The call: deep deletion, loss, gain or amplification.", "amplification",
            enum=("deep deletion", "loss", "gain", "amplification")),
          F("GENE", "id", "The driver gene the segment was placed over; empty for a broad segment.", "HLXK7", presence="optional",
            link="study-gene")],
         ["SEGMENT_ID", "SAMPLE_ID", "CHROM", "COPY_NUMBER", "CALL"],
         ["SEGMENT_ID", "SAMPLE_ID", "CHROM", "START", "END", "COPY_NUMBER", "CALL"],
         ["SEGMENT_ID", "SAMPLE_ID", "CHROM", "CALL", "GENE"],
         ["SAMPLE_ID", "CHROM", "CALL", "GENE"], 3000, "Copy-number segment"),
    Kind("pathway-gene", GENOMICS, "PATHWAY", "Pathway membership", "PATHWAY_GENE_ID", "PWG-",
         "One gene in one biological pathway and the part it plays there. Twelve invented pathways, 500 memberships.",
         "One row per pathway and gene, keyed by PATHWAY_GENE_ID.",
         [F("PATHWAY_GENE_ID", "id", "PWG- and four digits.", "PWG-0007"),
          F("PATHWAY", "text", "The pathway's name.", "Growth signalling"),
          F("GENE", "id", "The member gene; opens SGENE.", "HLXK7", link="study-gene"),
          F("ROLE_IN_PATHWAY", "text", "The part the gene plays in the pathway.", "activator",
            enum=("activator", "inhibitor", "scaffold", "effector", "regulator"))],
         ["PATHWAY_GENE_ID", "PATHWAY", "GENE", "ROLE_IN_PATHWAY"],
         ["PATHWAY_GENE_ID", "PATHWAY", "GENE", "ROLE_IN_PATHWAY"],
         ["PATHWAY_GENE_ID", "PATHWAY", "GENE", "ROLE_IN_PATHWAY"],
         ["PATHWAY", "GENE", "ROLE_IN_PATHWAY"], 500, "Pathway membership"),
    # ---------------------------------------------------------------------------------------------------------- protein-engineering
    Kind("protein", PROTEIN, "PROT", "Protein", "PROTEIN", None,
         "One invented protein: its sequence, family and active site. Four proteins; Helixase-7, the enzyme of gene HLXK7 (312 amino acids), "
         "is the one the cohort screen and the design campaign are about.",
         "One row per protein, keyed by PROTEIN (the symbol of its gene).",
         [F("PROTEIN", "id", "The protein's id: the symbol of its gene.", "HLXK7"),
          F("GENE", "id", "The gene that codes for it; opens SGENE.", "HLXK7", link="study-gene"),
          F("NAME", "text", "The protein's name.", "Helixase-7"),
          F("LENGTH", "integer", "The amino acids in it.", 312, "amino acids"),
          F("FAMILY", "text", "The family it belongs to.", "Helicase-like kinase"),
          F("SEQUENCE", "text", "The amino acid sequence, one letter each.", "MKTAYIAKQR"),
          F("ACTIVE_SITE", "vector", "The positions of the active site's residues.", None),
          F("DESIGN_ROUNDS", "integer", "How many rounds of design the campaign ran on it; 0 for a protein never designed.", 8,
            "rounds")],
         ["PROTEIN", "NAME", "LENGTH", "FAMILY", "DESIGN_ROUNDS"],
         ["PROTEIN", "NAME", "LENGTH", "FAMILY", "DESIGN_ROUNDS"],
         ["PROTEIN", "GENE", "NAME", "LENGTH", "FAMILY", "DESIGN_ROUNDS"],
         ["PROTEIN", "NAME", "FAMILY"], 4, "Protein"),
    Kind("residue", PROTEIN, "RESIDUE", "Residue", "RESIDUE_ID", None,
         "One amino acid position of a protein: what it is, where it sits in the fold, how buried it is, whether it is in the active site, "
         "its place in space and the mean effect of changing it. Coordinates, burial and the means are written by the generator.",
         "One row per protein position, keyed by RESIDUE_ID.",
         [F("RESIDUE_ID", "id", "The protein, a colon and the position.", "HLXK7:117"),
          F("PROTEIN", "id", "The protein; opens PROT.", "HLXK7", link="protein"),
          F("POS", "integer", "The position, counted from 1.", 117),
          F("AA", "text", "The amino acid there (one letter).", "D"),
          F("SECONDARY", "text", "The element of the fold it is in.", "helix", enum=("helix", "strand", "loop")),
          F("DOMAIN", "text", "The domain it is in.", "N-lobe", enum=("N-lobe", "hinge", "C-lobe")),
          F("BURIAL", "rate", "How buried it is, 0 (on the surface) to 1 (deep inside): the rank of its neighbours within 11 angstroms.", 0.83),
          F("ACTIVE_SITE", "boolean", "True for the residues of the active site.", True),
          F("X", "amount", "Its place along x.", 4.2, "angstrom"),
          F("Y", "amount", "Its place along y.", -11.8, "angstrom"),
          F("Z", "amount", "Its place along z.", 7.5, "angstrom"),
          F("MEAN_ACTIVITY", "amount", "The mean activity score of the 19 changes of the position (negative hurts, positive helps).", -1.42),
          F("MEAN_STABILITY", "amount", "The mean stability score of the 19 changes of the position.", -0.96)],
         ["RESIDUE_ID", "AA", "SECONDARY", "DOMAIN", "BURIAL"],
         ["RESIDUE_ID", "POS", "AA", "SECONDARY", "DOMAIN", "BURIAL", "ACTIVE_SITE", "MEAN_ACTIVITY"],
         ["RESIDUE_ID", "PROTEIN", "POS", "SECONDARY", "ACTIVE_SITE"],
         ["PROTEIN", "SECONDARY", "DOMAIN", "ACTIVE_SITE"], 1196, "Residue"),
    Kind("residue-contact", PROTEIN, "CONTACT", "Residue contact", "CONTACT_ID", None,
         "Two positions of a protein that sit close in space though they are not neighbours in the chain: the contact map's cells. The "
         "nearest pairs at least three positions apart, as many as the generator keeps for the protein.",
         "One row per contact, keyed by CONTACT_ID.",
         [F("CONTACT_ID", "id", "The protein, a colon, the two positions joined by a dash.", "HLXK7:12-45"),
          F("PROTEIN", "id", "The protein; opens PROT.", "HLXK7", link="protein"),
          F("POS_A", "integer", "The lower position.", 12),
          F("POS_B", "integer", "The higher position.", 45),
          F("DISTANCE", "amount", "How far apart the two residues are.", 5.9, "angstrom")],
         ["CONTACT_ID", "PROTEIN", "POS_A", "POS_B", "DISTANCE"],
         ["CONTACT_ID", "POS_A", "POS_B", "DISTANCE"],
         ["CONTACT_ID", "PROTEIN", "POS_A", "POS_B"],
         ["PROTEIN", "POS_A", "POS_B"], 5952, "Residue contact"),
    Kind("dms-score", PROTEIN, "DMS", "Deep mutational scan score", "SCORE_ID", None,
         "One measurement of the scan: what changing one position of a protein to one amino acid did to one assay. Every position, every one "
         "of the 20 amino acids, two assays: 47,840 experiments.",
         "One row per position, amino acid and assay, keyed by SCORE_ID.",
         [F("SCORE_ID", "id", "The protein, position, amino acid and assay, joined by colons (the assay in capitals).", "HLXK7:117:P:ACTIVITY"),
          F("PROTEIN", "id", "The protein; opens PROT.", "HLXK7", link="protein"),
          F("POS", "integer", "The position changed.", 117),
          F("AA_REF", "text", "The amino acid the protein has there (one letter).", "L"),
          F("AA", "text", "The amino acid it was changed to; the same as AA_REF measures the unchanged protein.", "P"),
          F("MUTATION", "text", "The change written as AA_REF, POS and AA.", "L117P"),
          F("ASSAY", "text", "What was measured.", "activity", enum=("activity", "stability")),
          F("SCORE", "amount", "The effect against the natural protein: negative hurts, positive helps, 0 no effect.", -2.31),
          F("SE", "amount", "The standard error over the replicates.", 0.12),
          F("REPLICATES", "integer", "How many replicate measurements went into it.", 3, "replicates")],
         ["SCORE_ID", "MUTATION", "ASSAY", "SCORE", "SE"],
         ["SCORE_ID", "PROTEIN", "POS", "MUTATION", "ASSAY", "SCORE", "SE"],
         ["SCORE_ID", "PROTEIN", "POS", "AA", "ASSAY", "SCORE"],
         ["PROTEIN", "POS", "AA", "ASSAY"], 47840, "Scan score"),
    Kind("design", PROTEIN, "DESIGN", "Protein design", "DESIGN_ID", "DSG-",
         "One designed variant of Helixase-7 from one round of the campaign: its parent, how many changes it carries, how active and "
         "stable it measured, what the model predicted and where it sits on the map of designs. Eight rounds of 1,500 designs climb to a "
         "protein about fifteen times as active as the natural one.",
         "One row per design, keyed by DESIGN_ID.",
         [F("DESIGN_ID", "id", "DSG-, the protein, the round and the design's number in it.", "DSG-HLXK7-R8-0412"),
          F("PROTEIN", "id", "The protein designed; opens PROT.", "HLXK7", link="protein"),
          F("ROUND", "integer", "The round, 1 to 8.", 8),
          F("PARENT", "text", "The design of the round before that is nearest to it on the map; NATURAL in round 1.",
            "DSG-HLXK7-R7-0988"),
          F("N_CHANGES", "integer", "How many amino acid changes it carries against the natural protein.", 3, "changes"),
          F("CHANGES", "text", "The changes written out, in position order; the rows are in design-mutation.", "A117G L203F"),
          F("ACTIVITY", "amount", "The measured activity as a multiple of the natural protein's (1.0).", 15.2, "x natural"),
          F("DELTA_TM", "amount", "The melting temperature against the natural protein: positive is more stable.", 5.1, "degrees C"),
          F("MODEL_SCORE", "amount", "The activity the design model predicted.", 14.1, "x natural"),
          F("STATUS", "text", "lead for the best design of the campaign, selected for the best 5% of its round, tested for the rest.",
            "lead", enum=("lead", "selected", "tested")),
          F("EMB_X", "amount", "Its place on the map of designs, left to right (0 to 1). Written by the generator.", 0.7412),
          F("EMB_Y", "amount", "Its place on the map of designs, top to bottom (0 to 1). Written by the generator.", 0.3196)],
         ["DESIGN_ID", "ROUND", "N_CHANGES", "ACTIVITY", "STATUS"],
         ["DESIGN_ID", "ROUND", "N_CHANGES", "CHANGES", "ACTIVITY", "DELTA_TM", "STATUS"],
         ["DESIGN_ID", "PROTEIN", "ROUND", "ACTIVITY", "STATUS"],
         ["PROTEIN", "ROUND", "STATUS", "ACTIVITY"], 12000, "Design"),
    Kind("design-mutation", PROTEIN, "DMUT", "Design change", "DESIGN_MUTATION_ID", None,
         "One amino acid change of one design: the position, what it was and what it became. 33,000 changes over 12,000 designs.",
         "One row per design and position, keyed by DESIGN_MUTATION_ID.",
         [F("DESIGN_MUTATION_ID", "id", "The design's id, a colon and the position.", "DSG-HLXK7-R8-0412:117"),
          F("DESIGN_ID", "id", "The design; opens DESIGN.", "DSG-HLXK7-R8-0412", link="design"),
          F("PROTEIN", "id", "The protein; opens PROT.", "HLXK7", link="protein"),
          F("ROUND", "integer", "The design's round.", 8),
          F("POS", "integer", "The position changed.", 117),
          F("AA_FROM", "text", "The natural amino acid (one letter).", "L"),
          F("AA_TO", "text", "The amino acid it was changed to.", "G"),
          F("MUTATION", "text", "The change written as AA_FROM, POS and AA_TO.", "L117G")],
         ["DESIGN_MUTATION_ID", "DESIGN_ID", "MUTATION", "ROUND"],
         ["DESIGN_MUTATION_ID", "DESIGN_ID", "ROUND", "MUTATION"],
         ["DESIGN_MUTATION_ID", "DESIGN_ID", "PROTEIN", "ROUND", "POS"],
         ["DESIGN_ID", "ROUND", "POS", "AA_TO"], 33000, "Design change"),
    Kind("design-round", PROTEIN, "DROUND", "Design round", "ROUND_ID", None,
         "One round of the campaign: how the designs were made, how many, and how well the round did. Eight rounds.",
         "One row per protein and round, keyed by ROUND_ID.",
         [F("ROUND_ID", "id", "The protein, a dash, R and the round.", "HLXK7-R8"),
          F("PROTEIN", "id", "The protein; opens PROT.", "HLXK7", link="protein"),
          F("ROUND", "integer", "The round, 1 to 8.", 8),
          F("METHOD", "text", "How the round's designs were chosen.", "Model-guided with the scan as the prior"),
          F("STARTED", "date", "The day the round started.", "2026-04-20"),
          F("DESIGNS", "integer", "How many designs the round tested.", 1500, "designs"),
          F("BEST_DESIGN", "id", "The round's best design; opens DESIGN.", "DSG-HLXK7-R8-0412", link="design"),
          F("BEST_ACTIVITY", "amount", "Its activity.", 15.2, "x natural"),
          F("MEDIAN_ACTIVITY", "amount", "The median activity of the round's designs.", 6.8, "x natural"),
          F("TRAPPED", "integer", "How many designs were caught on the false peak.", 0, "designs"),
          F("NOTE", "text", "What happened in the round.", "The campaign reaches the summit.")],
         ["ROUND_ID", "ROUND", "METHOD", "BEST_ACTIVITY", "MEDIAN_ACTIVITY"],
         ["ROUND_ID", "ROUND", "METHOD", "DESIGNS", "BEST_ACTIVITY", "MEDIAN_ACTIVITY", "TRAPPED"],
         ["ROUND_ID", "PROTEIN", "ROUND", "METHOD"],
         ["PROTEIN", "ROUND"], 8, "Design round"),
]

BY_KIND = {k.kind: k for k in KINDS}


def kinds_of(pack: str) -> list:
    return [k for k in KINDS if k.pack == pack]


def names(k: Kind) -> list:
    return [f.name for f in k.fields]
