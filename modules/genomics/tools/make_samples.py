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

"""The genomics module's synthetic samples (src/dmod/data/samples/): two VCF files (cohorts COH-A and COH-B, 24 samples) and gene.jsonl
(eight genes with their protein domains). Seeded, so a run writes the same bytes: python3 modules/genomics/tools/make_samples.py.
Every variant is made up: positions, alleles and genotypes are random within each gene's span; nothing is a real person's data."""
from __future__ import annotations

import json
import random
from pathlib import Path

OUT = Path(__file__).resolve().parent.parent / "src" / "dmod" / "data" / "samples"
# gene, chromosome, start, protein length, domains (name, from, to): public reference facts, rounded
GENES = [
    ("BRCA1", "17", 43044295, 1863, [("RING", 24, 64), ("BRCT1", 1642, 1736), ("BRCT2", 1756, 1855)]),
    ("BRCA2", "13", 32315474, 3418, [("BRC repeats", 1002, 2082), ("DNA binding", 2479, 3192)]),
    ("TP53", "17", 7661779, 393, [("Transactivation", 1, 61), ("DNA binding", 102, 292), ("Tetramerisation", 325, 356)]),
    ("EGFR", "7", 55019017, 1210, [("Receptor L", 57, 167), ("Kinase", 712, 979)]),
    ("KRAS", "12", 25205246, 189, [("G domain", 1, 166)]),
    ("PTEN", "10", 87863113, 403, [("Phosphatase", 14, 185), ("C2", 190, 350)]),
    ("APC", "5", 112707498, 2843, [("Armadillo", 453, 767), ("Beta-catenin binding", 1020, 1169)]),
    ("MLH1", "3", 36993332, 756, [("ATPase", 22, 330), ("MutL C", 501, 756)]),
]
CSQ = [("missense_variant", "MODERATE", 0.55), ("synonymous_variant", "LOW", 0.25), ("stop_gained", "HIGH", 0.08),
       ("frameshift_variant", "HIGH", 0.07), ("splice_region_variant", "LOW", 0.05)]
AA = "ACDEFGHIKLMNPQRSTVWY"


def pick(rng):
    x, acc = rng.random(), 0.0
    for c, imp, w in CSQ:
        acc += w
        if x <= acc:
            return c, imp
    return CSQ[0][:2]


def vcf(rng, cohort: str, samples: list[str], n: int) -> str:
    head = ["##fileformat=VCFv4.3", "##source=helixlab-synthetic", "##reference=GRCh38",
            '##INFO=<ID=GENE,Number=1,Type=String,Description="Gene symbol">',
            '##INFO=<ID=CSQ,Number=1,Type=String,Description="Consequence">',
            '##INFO=<ID=IMPACT,Number=1,Type=String,Description="HIGH, MODERATE or LOW">',
            '##INFO=<ID=AAPOS,Number=1,Type=Integer,Description="Protein position">',
            '##FORMAT=<ID=GT,Number=1,Type=String,Description="Genotype">']
    for i, s in enumerate(samples):
        head.append(f"##SAMPLE=<ID={s},Cohort={cohort},Sex={'F' if i % 2 == 0 else 'M'},Population={['EUR', 'AFR', 'EAS', 'SAS', 'AMR'][i % 5]}>")
    head.append("#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\t" + "\t".join(samples))
    rows = []
    for k in range(n):
        gene, chrom, start, length, _ = GENES[rng.randrange(len(GENES))]
        aapos = rng.randint(1, length)
        pos = start + aapos * 3 + rng.randint(0, 2)
        ref, alt = rng.sample("ACGT", 2)
        csq, impact = pick(rng)
        gts = []
        for _ in samples:
            r = rng.random()
            gts.append("1/1" if r < 0.03 else "0/1" if r < 0.18 else "0/0" if r < 0.97 else "./.")
        qual = round(rng.uniform(30, 99), 1)
        flt = "PASS" if qual > 35 else "LowQual"
        vid = f"HX-{cohort[-1]}{k + 1:03d}"
        rows.append((chrom, pos, f"{chrom}\t{pos}\t{vid}\t{ref}\t{alt}\t{qual}\t{flt}\tGENE={gene};CSQ={csq};IMPACT={impact};AAPOS={aapos}\tGT\t" + "\t".join(gts)))
    rows.sort(key=lambda r: (int(r[0]), r[1]))
    return "\n".join(head + [r[2] for r in rows]) + "\n"


def main() -> None:
    rng = random.Random(20261110)
    OUT.mkdir(parents=True, exist_ok=True)
    a = [f"HXS-{i:03d}" for i in range(1, 13)]
    b = [f"HXS-{i:03d}" for i in range(13, 25)]
    (OUT / "cohort-a.vcf").write_text(vcf(rng, "COH-A", a, 60), encoding="utf-8")
    (OUT / "cohort-b.vcf").write_text(vcf(rng, "COH-B", b, 60), encoding="utf-8")
    with (OUT / "gene.jsonl").open("w", encoding="utf-8") as f:
        for gene, chrom, start, length, domains in GENES:
            f.write(json.dumps({"GENE": gene, "CHROM": chrom, "START": start, "PROTEIN_LENGTH": length,
                                "DOMAINS": [{"NAME": n, "FROM": s, "TO": e} for n, s, e in domains]}) + "\n")
    print(f"wrote {OUT}")


if __name__ == "__main__":
    main()
