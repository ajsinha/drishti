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

"""Public reference facts the genomics pack is built on: genes (GRCh38 coordinates, HGNC names), their proteins
(UniProt accessions and lengths) and well-known variants (HGVS, dbSNP). Everything patient-level is synthetic."""

# symbol: (name, chromosome, start, end, strand, transcripts, protein accession, protein length aa, protein name)
GENES = {
    "TP53": ("tumor protein p53", "17", 7668402, 7687550, "-", 27, "P04637", 393, "Cellular tumor antigen p53"),
    "BRCA1": ("BRCA1 DNA repair associated", "17", 43044295, 43125483, "-", 38, "P38398", 1863, "Breast cancer type 1 susceptibility protein"),
    "EGFR": ("epidermal growth factor receptor", "7", 55019017, 55211628, "+", 16, "P00533", 1210, "Epidermal growth factor receptor"),
    "KRAS": ("KRAS proto-oncogene, GTPase", "12", 25205246, 25250936, "-", 6, "P01116", 189, "GTPase KRas"),
    "BRAF": ("B-Raf proto-oncogene, serine/threonine kinase", "7", 140719327, 140924929, "-", 12, "P15056", 766, "Serine/threonine-protein kinase B-raf"),
    "CFTR": ("CF transmembrane conductance regulator", "7", 117480025, 117668665, "+", 11, "P13569", 1480, "Cystic fibrosis transmembrane conductance regulator"),
    "APOE": ("apolipoprotein E", "19", 44905796, 44909393, "+", 5, "P02649", 317, "Apolipoprotein E"),
    "HBB": ("hemoglobin subunit beta", "11", 5225464, 5229395, "-", 4, "P68871", 147, "Hemoglobin subunit beta"),
}
# id: (gene, rsid, protein change, HGVS coding, genomic GRCh38, consequence, clinical significance, condition)
VARIANTS = {
    "VRNT-BRAF-V600E": ("BRAF", "rs113488022", "p.Val600Glu", "c.1799T>A", "chr7:140753336 A>T", "Missense", "Pathogenic (somatic)", "Melanoma, colorectal and thyroid cancer"),
    "VRNT-KRAS-G12D": ("KRAS", "rs121913529", "p.Gly12Asp", "c.35G>A", "chr12:25245350 C>T", "Missense", "Pathogenic (somatic)", "Pancreatic, colorectal and lung cancer"),
    "VRNT-EGFR-L858R": ("EGFR", "rs121434568", "p.Leu858Arg", "c.2573T>G", "chr7:55191822 T>G", "Missense", "Drug response", "Non-small cell lung cancer (EGFR TKI sensitive)"),
    "VRNT-TP53-R175H": ("TP53", "rs28934578", "p.Arg175His", "c.524G>A", "chr17:7675088 C>T", "Missense", "Pathogenic", "Li-Fraumeni syndrome; many cancers"),
    "VRNT-CFTR-F508DEL": ("CFTR", "rs113993960", "p.Phe508del", "c.1521_1523del", "chr7:117559590 ATCT>A", "In-frame deletion", "Pathogenic", "Cystic fibrosis"),
    "VRNT-APOE-E4": ("APOE", "rs429358", "p.Cys130Arg", "c.388T>C", "chr19:44908684 T>C", "Missense", "Risk factor", "Late-onset Alzheimer disease"),
    "VRNT-HBB-E6V": ("HBB", "rs334", "p.Glu7Val", "c.20A>T", "chr11:5227002 T>A", "Missense", "Pathogenic", "Sickle cell disease"),
    "VRNT-BRCA1-185DELAG": ("BRCA1", "rs80357914", "p.Glu23fs", "c.68_69del", "chr17:43124027 ACT>A", "Frameshift", "Pathogenic", "Hereditary breast and ovarian cancer"),
}
PATHWAYS = {
    "PWY-MAPK": ("MAPK signalling (RAS-RAF-MEK-ERK)", ["EGFR", "KRAS", "BRAF"], "Signal transduction"),
    "PWY-P53": ("p53 signalling and DNA damage response", ["TP53", "BRCA1"], "Cell cycle and apoptosis"),
    "PWY-HR": ("Homologous recombination repair", ["BRCA1", "TP53"], "DNA repair"),
    "PWY-LIPID": ("Lipoprotein metabolism", ["APOE"], "Metabolism"),
    "PWY-O2": ("Oxygen transport (haemoglobin)", ["HBB"], "Transport"),
    "PWY-ION": ("Epithelial ion transport", ["CFTR"], "Transport"),
}
TISSUES = ["Brain", "Lung", "Liver", "Colon", "Breast", "Skin", "Blood", "Pancreas"]
