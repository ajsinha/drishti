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

"""The genomics and biology pack: genes, variants, proteins, pathways, samples, sequencing runs, expression
studies and clinical trials. Reference facts are public (see reference.py); samples, runs, studies and trials are
synthetic.

    python3 tools/packgen/genomics/make.py            write packs/genomics
    python3 tools/packgen/genomics/make.py --check    fail if the pack differs from what would be written
    uv run --with deltalake --with pyarrow --with pyyaml python tools/packgen/genomics/make.py --lake data/delta
"""
from __future__ import annotations

import hashlib
import random
import sys
from datetime import date, timedelta
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent / "common"))
sys.path.insert(0, str(HERE.parent / "banking"))
import packbuild as PB  # noqa: E402
import taxonomy as T  # noqa: E402
from reference import GENES, PATHWAYS, TISSUES, VARIANTS  # noqa: E402
from risk_model import Kind, Panel as P  # noqa: E402

G = "Genomics and biology"
AS_OF = date(2026, 9, 30)
POPULATIONS = ["African", "East Asian", "European (non-Finnish)", "Latino/Admixed American", "South Asian"]

KINDS = [
    Kind("gene", "GENE", "GENE-", "Gene", G, "A human gene: location on GRCh38, transcripts, expression by tissue and its known variants.", "geneId",
         [("Symbol", "$.symbol", None, None, True), ("Name", "$.name", None, None, False), ("Location", "$.location", None, None, False),
          ("Length (bp)", "$.length", "amount0", None, False), ("Strand", "$.strand", None, None, False), ("Transcripts", "$.transcripts", "amount0", None, False)],
         [P("hbar", "expression", "Expression by tissue (TPM)", "$.expression", label="tissue", value="tpm", fmt="price2", key="F2"),
          P("table", "variants", "Known variants", "$.variants", [("Variant", "@.id", None, None, False), ("Protein change", "@.protein", None, None, False),
            ("Significance", "@.significance", None, None, False)], key="F3"),
          P("kv", "identifiers", "Identifiers", "$.identifiers", area="right")],
         links={"protein": ("protein", "Protein"), "pathway": ("pathway", "Pathway")}, badge="$.location"),
    Kind("variant", "VRNT", "VRNT-", "Variant", G, "A sequence variant: HGVS names, consequence, clinical significance and population frequencies.", "variantId",
         [("Gene", "$.geneSymbol", None, None, False), ("Protein change", "$.proteinChange", None, None, True), ("HGVS", "$.hgvsC", None, None, False),
          ("Consequence", "$.consequence", None, None, False), ("Significance", "$.significance", None, None, False), ("dbSNP", "$.rsid", None, None, False)],
         [P("hbar", "frequencies", "Allele frequency by population", "$.frequencies", label="population", value="af", fmt="pct4", key="F2"),
          P("table", "evidence", "Evidence", "$.evidence", [("Source", "@.source", None, None, False), ("Assertion", "@.assertion", None, None, False),
            ("Review stars", "@.stars", "amount0", None, False), ("Last evaluated", "@.date", "date", None, False)], key="F3"),
          P("kv", "annotation", "Annotation", "$.annotation", area="right")],
         links={"gene": ("gene", "Gene")}, badge="$.significance"),
    Kind("protein", "PROT", "PROT-", "Protein", G, "A protein (UniProt): length, mass, domains and where it acts.", "proteinId",
         [("Protein", "$.name", None, None, False), ("Accession", "$.accession", None, None, True), ("Length (aa)", "$.length", "amount0", None, False),
          ("Mass (kDa)", "$.massKda", "price2", None, False), ("Location", "$.subcellular", None, None, False)],
         [P("table", "domains", "Domains and regions", "$.domains", [("Region", "@.name", None, None, False), ("Start", "@.start", "amount0", None, False),
            ("End", "@.end", "amount0", None, False)], key="F2"),
          P("kv", "function", "Function", "$.function", area="right")],
         links={"gene": ("gene", "Gene")}),
    Kind("pathway", "PWY", "PWY-", "Pathway", G, "A biological pathway and the genes that take part in it.", "pathwayId",
         [("Pathway", "$.name", None, None, True), ("Category", "$.category", None, None, False), ("Genes", "$.geneCount", "amount0", None, False)],
         [P("table", "members", "Member genes", "$.members", [("Gene", "@.symbol", None, None, False), ("Role", "@.role", None, None, False),
            ("Location", "@.location", None, None, False)], key="F2")]),
    Kind("sample", "SMPL", "SMPL-", "Sample", G, "A sequenced specimen: tissue, diagnosis, variants called with allele fraction and depth, and QC.", "sampleId",
         [("Tissue", "$.tissue", None, None, False), ("Diagnosis", "$.diagnosis", None, None, False), ("Tumour purity", "$.purity", "pct0", None, False),
          ("Variants called", "$.variantCount", "amount0", None, True), ("Mean coverage", "$.meanCoverage", "amount0", None, False), ("Collected", "$.collected", "date", None, False)],
         [P("table", "calls", "Variant calls", "$.calls", [("Variant", "@.variant", None, None, False), ("Gene", "@.gene", None, None, False),
            ("VAF", "@.vaf", "pct2", None, True), ("Depth", "@.depth", "amount0", None, False), ("Filter", "@.filter", None, "status", False)], key="F2"),
          P("kv", "qc", "Quality control", "$.qc", key="F3", area="right")],
         links={"sequencingRun": ("sequencing-run", "Sequencing run"), "trial": ("clinical-trial", "Trial")}, badge="$.diagnosis"),
    Kind("sequencing-run", "SEQ", "SEQ-", "Sequencing run", G, "An instrument run: yield, quality by cycle, and the samples it carried.", "runId",
         [("Instrument", "$.instrument", None, None, False), ("Flow cell", "$.flowcell", None, None, False), ("Yield (Gb)", "$.yieldGb", "price2", None, True),
          ("Q30", "$.q30", "pct2", None, False), ("Clusters PF", "$.clustersPf", "pct2", None, False), ("Status", "$.status", None, "status", False)],
         [P("line", "quality", "Mean quality score by cycle", "$.qualityByCycle", x="cycle", y="q", fmt="price2", key="F2"),
          P("table", "samples", "Samples", "$.samples", [("Sample", "@.sample", None, None, False), ("Reads (M)", "@.readsM", "price2", None, True),
            ("Mean coverage", "@.coverage", "amount0", None, False)], key="F3")],
         badge="fmt($.yieldGb, 'price2') + ' Gb'"),
    Kind("expression-study", "EXPR", "EXPR-", "Expression study", G, "A differential-expression (RNA-seq) comparison: fold changes and adjusted p-values.", "studyId",
         [("Study", "$.title", None, None, False), ("Comparison", "$.comparison", None, None, False), ("Samples", "$.sampleCount", "amount0", None, False),
          ("Significant genes", "$.significant", "amount0", None, True)],
         [P("hbar", "foldChange", "log2 fold change", "$.results", label="gene", value="log2fc", fmt="price2", tone="sign", key="F2"),
          P("table", "results", "Results", "$.results", [("Gene", "@.gene", None, None, False), ("log2 FC", "@.log2fc", "signed2", "sign", True),
            ("Mean expression", "@.baseMean", "amount0", None, False), ("Adjusted p", "@.padj", None, None, False)], key="F3")]),
    Kind("clinical-trial", "TRIAL", "TRIAL-", "Clinical trial", G, "A targeted-therapy trial: phase, biomarker, arms and enrolment against target.", "trialId",
         [("Trial", "$.title", None, None, False), ("Phase", "$.phase", None, None, False), ("Status", "$.status", None, "status", False),
          ("Enrolled", "$.enrolled", "amount0", None, True), ("Target", "$.target", "amount0", None, False), ("Biomarker", "$.biomarkerName", None, None, False)],
         [P("line", "enrolment", "Cumulative enrolment", "$.enrolment", x="month", y="patients", fmt="amount0", key="F2"),
          P("table", "arms", "Arms", "$.arms", [("Arm", "@.arm", None, None, False), ("Intervention", "@.intervention", None, None, False),
            ("Patients", "@.patients", "amount0", None, False), ("Response rate", "@.orr", "pct0", None, False)], key="F3")],
         links={"gene": ("gene", "Target gene"), "biomarker": ("variant", "Biomarker")}, badge="$.phase"),
]


def rng(key: str) -> random.Random:
    return random.Random(int(hashlib.sha256(key.encode()).hexdigest()[:12], 16))


def months(n: int) -> list[str]:
    y, m, out = AS_OF.year, AS_OF.month, []
    for _ in range(n):
        out.append(f"{y}-{m:02d}")
        y, m = (y, m - 1) if m > 1 else (y - 1, 12)
    return out[::-1]


def build() -> dict[str, dict[str, dict]]:
    docs = {k.kind: {} for k in KINDS}
    ref = {"source": "reference-genome", "generation": 1}
    pathway_of = {g: pid for pid, (_, genes, _) in PATHWAYS.items() for g in genes}
    for sym, (name, chrom, start, end, strand, tx, acc, aa, pname) in GENES.items():
        r = rng(sym)
        docs["gene"][f"GENE-{sym}"] = {
            "geneId": f"GENE-{sym}", "symbol": sym, "name": name, "location": f"chr{chrom}:{start:,}-{end:,}", "chromosome": chrom, "start": start, "end": end,
            "length": end - start + 1, "strand": strand, "transcripts": tx,
            "expression": [{"tissue": t, "tpm": round(r.lognormvariate(2.5, 1.3), 2)} for t in TISSUES],
            "variants": [{"id": vid, "protein": v[2], "significance": v[6]} for vid, v in VARIANTS.items() if v[0] == sym],
            "identifiers": {"assembly": "GRCh38.p14", "hgncSymbol": sym, "uniprot": acc, "biotype": "protein coding"},
            "protein": f"PROT-{acc}", "pathway": pathway_of[sym], "_meta": ref}
        docs["protein"][f"PROT-{acc}"] = {
            "proteinId": f"PROT-{acc}", "name": pname, "accession": acc, "length": aa, "massKda": round(aa * 0.110, 1),
            "subcellular": {"TP53": "Nucleus", "BRCA1": "Nucleus", "EGFR": "Cell membrane", "KRAS": "Cell membrane (inner)", "BRAF": "Cytoplasm",
                            "CFTR": "Apical cell membrane", "APOE": "Secreted", "HBB": "Cytoplasm (red cells)"}[sym],
            "domains": [{"name": n, "start": s, "end": min(aa, e)} for n, s, e in _domains(sym, aa)],
            "function": {"summary": pname + " (UniProt " + acc + ")", "gene": sym, "reviewed": "Swiss-Prot"}, "gene": f"GENE-{sym}", "_meta": ref}
    for vid, (sym, rsid, prot, hgvs, gpos, cons, sig, cond) in VARIANTS.items():
        r = rng(vid)
        germline = "somatic" not in sig
        docs["variant"][vid] = {
            "variantId": vid, "geneSymbol": sym, "proteinChange": prot, "hgvsC": hgvs, "genomic": gpos, "consequence": cons, "significance": sig, "rsid": rsid,
            "condition": cond,
            "frequencies": [{"population": p, "af": round(r.uniform(0.0001, 0.2) if germline and sym in ("APOE", "HBB") else r.uniform(0, 0.0004), 6)} for p in POPULATIONS],
            "evidence": [{"source": s, "assertion": sig, "stars": st, "date": (AS_OF - timedelta(days=r.randint(60, 1500))).isoformat()}
                         for s, st in [("ClinVar", r.choice([2, 3, 4])), ("CIViC" if not germline else "ClinGen", r.choice([3, 4]))]],
            "annotation": {"genomicGrch38": gpos, "condition": cond, "origin": "Somatic" if not germline else "Germline"},
            "gene": f"GENE-{sym}", "_meta": ref}
    for pid, (name, genes, cat) in PATHWAYS.items():
        docs["pathway"][pid] = {"pathwayId": pid, "name": name, "category": cat, "geneCount": len(genes),
                                "members": [{"symbol": g, "role": _role(g), "location": docs["gene"][f"GENE-{g}"]["location"]} for g in genes], "_meta": ref}
    trials = {"TRIAL-ONC-101": ("BRAF V600E melanoma: BRAF plus MEK inhibition", "Phase III", "VRNT-BRAF-V600E", 540),
              "TRIAL-ONC-214": ("EGFR-mutant NSCLC: third-generation TKI first line", "Phase III", "VRNT-EGFR-L858R", 620),
              "TRIAL-ONC-322": ("KRAS G12D pancreatic cancer: selective inhibitor", "Phase I/II", "VRNT-KRAS-G12D", 180),
              "TRIAL-GEN-045": ("Sickle cell disease: gene editing of BCL11A enhancer", "Phase II", "VRNT-HBB-E6V", 60)}
    for tid, (title, phase, vid, target) in trials.items():
        r = rng(tid)
        ms = months(24)
        enrolled, cum = min(target, round(target * r.uniform(0.45, 1.0))), []
        for k, m in enumerate(ms):
            cum.append({"month": m, "patients": round(enrolled * ((k + 1) / len(ms)) ** 1.4)})
        drug = r.choice(["DRS-4471", "DRS-5102", "DRS-6630"])
        docs["clinical-trial"][tid] = {
            "trialId": tid, "title": title, "phase": phase, "status": "Recruiting" if enrolled < target else "Active, not recruiting", "enrolled": enrolled,
            "target": target, "biomarkerName": VARIANTS[vid][0] + " " + VARIANTS[vid][2], "enrolment": cum,
            "arms": [{"arm": "Experimental", "intervention": drug + (" + MEK inhibitor" if "MEK" in title else ""), "patients": enrolled // 2, "orr": round(r.uniform(0.35, 0.7), 2)},
                     {"arm": "Control", "intervention": "Standard of care", "patients": enrolled - enrolled // 2, "orr": round(r.uniform(0.1, 0.3), 2)}],
            "gene": f"GENE-{VARIANTS[vid][0]}", "biomarker": vid, "_meta": {"source": "trial-registry", "generation": 1}}
    runs = [f"SEQ-2026-09{d:02d}-{c}" for d, c in [(3, "A"), (10, "A"), (17, "B"), (24, "A")]]
    run_samples = {r_: [] for r_ in runs}
    diag = [("Melanoma", "Skin", "VRNT-BRAF-V600E", "TRIAL-ONC-101"), ("Lung adenocarcinoma", "Lung", "VRNT-EGFR-L858R", "TRIAL-ONC-214"),
            ("Pancreatic adenocarcinoma", "Pancreas", "VRNT-KRAS-G12D", "TRIAL-ONC-322"), ("Colorectal adenocarcinoma", "Colon", "VRNT-KRAS-G12D", None),
            ("Breast carcinoma", "Breast", "VRNT-BRCA1-185DELAG", None), ("Sickle cell disease", "Blood", "VRNT-HBB-E6V", "TRIAL-GEN-045")]
    for i in range(18):
        r = rng(f"smpl{i}")
        dx, tissue, driver, trial = diag[i % len(diag)]
        sid = f"SMPL-{104120 + i * 13}"
        run = runs[i % len(runs)]
        cov = r.randint(180, 900) if tissue != "Blood" else r.randint(30, 60)
        calls = [{"variant": driver, "gene": VARIANTS[driver][0], "vaf": round(r.uniform(0.12, 0.48) if tissue != "Blood" else r.choice([0.5, 1.0]), 3),
                  "depth": round(cov * r.uniform(0.8, 1.2)), "filter": "PASS"}]
        if tissue not in ("Blood",) and r.random() < 0.5:
            calls.append({"variant": "VRNT-TP53-R175H", "gene": "TP53", "vaf": round(r.uniform(0.05, 0.4), 3), "depth": round(cov * r.uniform(0.8, 1.2)), "filter": "PASS"})
        if r.random() < 0.3:
            calls.append({"variant": "VRNT-APOE-E4", "gene": "APOE", "vaf": 0.5, "depth": round(cov * 0.4), "filter": "Germline"})
        reads = round(cov * 0.35 * r.uniform(0.9, 1.1), 1)
        doc = {"sampleId": sid, "tissue": tissue, "diagnosis": dx, "purity": round(r.uniform(0.3, 0.9), 2) if tissue != "Blood" else 1.0, "variantCount": len(calls),
               "meanCoverage": cov, "collected": (AS_OF - timedelta(days=r.randint(10, 120))).isoformat(), "calls": calls,
               "qc": {"readsMillions": reads, "duplicationRate": f"{r.uniform(4, 18):.1f}%", "onTarget": f"{r.uniform(72, 94):.1f}%", "contamination": f"{r.uniform(0, 1.2):.2f}%",
                      "panel": "Solid tumour 523-gene panel" if tissue != "Blood" else "Whole exome"},
               "sequencingRun": run, "_meta": {"source": "lims", "generation": 1}}
        if trial:
            doc["trial"] = trial
        docs["sample"][sid] = doc
        run_samples[run].append({"sample": sid, "readsM": reads, "coverage": cov})
    for run in runs:
        r = rng(run)
        docs["sequencing-run"][run] = {
            "runId": run, "instrument": r.choice(["NovaSeq X Plus", "NovaSeq 6000"]), "flowcell": f"{r.choice(['22', '23'])}{r.randint(1000, 9999)}LT3",
            "yieldGb": round(r.uniform(900, 3200), 1), "q30": round(r.uniform(0.88, 0.95), 4), "clustersPf": round(r.uniform(0.74, 0.86), 4), "status": "Completed",
            "qualityByCycle": [{"cycle": str(c), "q": round(36.5 - 0.012 * c - (0.8 if c > 140 else 0) + r.gauss(0, 0.25), 2)} for c in range(1, 152, 10)],
            "samples": run_samples[run], "_meta": {"source": "lims", "generation": 1}}
    for eid, title, comp, genes in [("EXPR-001", "KRAS-mutant vs wild-type colorectal tumours", "KRAS G12D vs WT", ["DUSP6", "ETV5", "SPRY4", "PHLDA1", "CDX2", "LGR5", "KRT20", "MUC2"]),
                                    ("EXPR-002", "Hypoxia response in lung epithelial cells", "1% O2 vs normoxia", ["VEGFA", "CA9", "BNIP3", "SLC2A1", "PGK1", "LDHA", "EGLN3", "ANKRD37"])]:
        r = rng(eid)
        res = sorted([{"gene": g, "log2fc": round(r.uniform(-4, 5) if k % 3 else r.uniform(1.5, 5), 2), "baseMean": round(r.lognormvariate(6, 1.2)),
                       "padj": f"{r.uniform(1, 9):.1f}e-{r.randint(3, 40)}"} for k, g in enumerate(genes)], key=lambda x: -x["log2fc"])
        docs["expression-study"][eid] = {"studyId": eid, "title": title, "comparison": comp, "sampleCount": r.choice([12, 24, 48]), "significant": r.randint(300, 2400),
                                         "results": res, "_meta": {"source": "analysis-pipeline", "generation": 1}}
    return docs


def _domains(sym: str, aa: int) -> list[tuple[str, int, int]]:
    known = {"TP53": [("Transactivation", 1, 61), ("DNA-binding", 94, 292), ("Tetramerisation", 318, 358)],
             "BRCA1": [("RING finger", 24, 64), ("Coiled coil", 1364, 1437), ("BRCT 1", 1642, 1736), ("BRCT 2", 1756, 1855)],
             "EGFR": [("Extracellular", 25, 645), ("Transmembrane", 646, 668), ("Protein kinase", 712, 979)],
             "KRAS": [("G domain (GTPase)", 1, 166), ("Hypervariable region", 167, 189)],
             "BRAF": [("RAS-binding", 155, 227), ("Phorbol-ester/DAG-type zinc finger", 234, 280), ("Protein kinase", 457, 717)],
             "CFTR": [("ABC transmembrane 1", 81, 365), ("ABC transporter 1 (NBD1)", 423, 646), ("ABC transmembrane 2", 859, 1155), ("ABC transporter 2 (NBD2)", 1210, 1443)],
             "APOE": [("LDL receptor binding", 158, 168), ("Lipid binding", 244, 272)],
             "HBB": [("Globin", 2, 146)]}
    return known.get(sym, [("Full length", 1, aa)])


def _role(g: str) -> str:
    return {"EGFR": "Receptor tyrosine kinase", "KRAS": "Small GTPase", "BRAF": "MAP3 kinase", "TP53": "Transcription factor", "BRCA1": "E3 ligase / repair scaffold",
            "APOE": "Lipid transport", "HBB": "Oxygen carrier", "CFTR": "Chloride channel"}[g]


def spec() -> PB.PackSpec:
    return PB.PackSpec(
        sample=True,
        code="GENO",
        columns={'gene': ['symbol', 'name', 'chromosome', 'location', 'length'], 'variant': ['geneSymbol', 'proteinChange', 'consequence', 'significance', 'condition'], 'sample': ['tissue', 'diagnosis', 'purity', 'variantCount', 'collected'], 'clinical-trial': ['title', 'phase', 'status', 'enrolled', 'biomarkerName'], 'protein': ['name', 'accession', 'length', 'massKda', 'subcellular'], 'sequencing-run': ['instrument', 'yieldGb', 'q30', 'status']},  # key fields shown beside each entity in pick lists
        name="genomics", title="Genomics and biology", requires=[], generator="tools/packgen/genomics/make.py",
        description="Genes, variants, proteins, pathways, sequenced samples, sequencing runs, expression studies and clinical trials.",
        domains={"genomics": KINDS},
        examples=[("GENE GENE-TP53", "Gene · GRCh38 location, expression by tissue, known variants"), ("VRNT VRNT-BRAF-V600E", "Variant · significance, population frequencies"),
                  ("PROT PROT-P00533", "Protein · EGFR domains"), ("SMPL SMPL-104120", "Tumour sample · variant calls with VAF and depth"),
                  ("SEQ SEQ-2026-0917-B", "Sequencing run · quality by cycle"), ("TRIAL TRIAL-ONC-101", "Clinical trial · enrolment and arms")],
        overview="Genes carry GRCh38 coordinates, proteins UniProt accessions and variants dbSNP ids. A gene links to its protein and pathway, a variant to its gene, a sample "
                 "to the run that sequenced it and the trial it was screened for, and a trial to its target gene and biomarker variant.",
        roles={"scientist": {"kinds": [k.kind for k in KINDS], "raw": True}})


if __name__ == "__main__":
    PB.main(spec(), build(), T.graph_fields())
