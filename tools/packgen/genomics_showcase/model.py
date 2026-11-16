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

"""The showcase's data: one seeded world, 200,000 rows.

    build(seed)    {pack: {kind: {id: document}}} for the genomics-showcase and protein-engineering packs, and the story's headline ids

Python's standard library only. The same seed gives the same documents, byte for byte; each stage draws from its own generator, so a
change to one stage never moves another's numbers. Nothing here is real: the genes (HLX...), the protein, the 1,500 patients and the
designs are invented. What the screens show is computed, not painted: the association table is a Wald test of the generated calls
against the generated traits; the contacts, the burial and the active site are measured from the generated coordinates; the design
campaign's activity follows its fitness field and its stability follows the scan.
"""
from __future__ import annotations

import math
import random
from collections import defaultdict
from datetime import date, timedelta

import spec as S

SEED = 20261116

N_SAMPLES = 1500
N_GENES = 2000
N_VARIANTS = 12000
N_CALLS = 45000
N_CNV = 3000
N_PATHWAY = 500
N_CONTACTS = 5952
N_MUTATIONS = 33000
ROUNDS = 8
PER_ROUND = 1500
LEAD_GENE = "HLXK7"

CHROMS = [str(i) for i in range(1, 23)] + ["X"]
CHROM_MB = [248, 242, 198, 190, 182, 171, 159, 145, 138, 134, 135, 133, 114, 107, 102, 90, 83, 80, 59, 64, 47, 51, 156]
CHROM_INDEX = {c: i for i, c in enumerate(CHROMS)}

#: the 24 driver genes, strongest first: symbol, chromosome, what its product does
DRIVERS = [("HLXK7", "7", "kinase (drug target)"), ("HLXT17", "17", "tumour suppressor"), ("HLXR3", "3", "receptor"),
           ("HLXM12", "12", "GTPase"), ("HLXP1", "1", "transcription factor"), ("HLXB13", "13", "DNA repair"),
           ("HLXA5", "5", "chromatin remodeller"), ("HLXC9", "9", "phosphatase"), ("HLXN2", "2", "kinase (drug target)"),
           ("HLXS19", "19", "tumour suppressor"), ("HLXD10", "10", "receptor"), ("HLXE4", "4", "GTPase"),
           ("HLXF11", "11", "transcription factor"), ("HLXG6", "6", "DNA repair"), ("HLXH8", "8", "chromatin remodeller"),
           ("HLXJ14", "14", "phosphatase"), ("HLXL16", "16", "kinase (drug target)"), ("HLXQ20", "20", "tumour suppressor"),
           ("HLXU22", "22", "receptor"), ("HLXV15", "15", "GTPase"), ("HLXW18", "18", "transcription factor"),
           ("HLXY21", "21", "DNA repair"), ("HLXZX", "X", "chromatin remodeller"), ("HLXO1", "1", "phosphatase")]
ROLES = ["kinase (drug target)", "tumour suppressor", "receptor", "GTPase", "transcription factor", "DNA repair", "chromatin remodeller",
         "phosphatase", "transporter", "structural protein"]
#: the four proteins: gene, name, length, family
PROTEINS = [("HLXK7", "Helixase-7", 312, "Helicase-like kinase"), ("HLXT17", "Tessellase-17", 248, "Isomerase-like"),
            ("HLXR3", "Ridgease-3", 286, "Hydrolase"), ("HLXM12", "Mendase-12", 350, "Transferase")]
#: the traits and what plants them: trait -> (base log odds, {driver: effect on the log odds when altered}, {cluster index: effect})
TRAITS = {"RESPONSE": (0.45, {"HLXK7": -2.0, "HLXT17": -1.0, "HLXP1": 0.9, "HLXD10": -1.1}, {2: 0.45}),
          "TOXICITY": (-1.6, {"HLXR3": 1.5}, {}),
          "RELAPSE": (-1.0, {"HLXB13": 1.3}, {4: 0.3})}
TRAIT_YES = {"RESPONSE": "responder", "TOXICITY": "severe", "RELAPSE": "relapsed"}
CLUSTERS = ["C1 luminal-like", "C2 basal-like", "C3 immune-hot", "C4 stromal", "C5 proliferative"]
CLUSTER_WEIGHTS = [0.24, 0.21, 0.20, 0.17, 0.18]
CLUSTER_CENTRE = [(.24, .30), (.72, .24), (.50, .60), (.20, .76), (.80, .74)]
CLUSTER_BURDEN = [1.0, 1.2, 1.6, 0.8, 1.3]
TUMOURS = ["Breast", "Lung", "Colorectal", "Ovarian"]
PATHWAYS = ["Growth signalling", "Cell-cycle control", "DNA repair", "Apoptosis", "Chromatin remodelling", "Immune checkpoint",
            "Metabolic reprogramming", "Angiogenesis", "Cell adhesion", "RNA processing", "Protein turnover", "Hormone response"]
PATHWAY_ROLES = ["activator", "inhibitor", "scaffold", "effector", "regulator"]
AA20 = "AVILMFWYGPSTCNQDEKRH"
GROUP = {a: (0 if i < 8 else 1 if i < 10 else 2 if i < 15 else 3 if i < 17 else 4) for i, a in enumerate(AA20)}
BACKGROUND_AA = "LLLLAAAAGGGGVVVVSSSSEEEEKKKDDDTTTIIIPPPRRRNNQQFFYMHCW"
#: the loci that carry a peak, and how many neighbouring genes are placed beside each (the hitchhikers of the locus plot)
PEAK_GENES = ["HLXK7", "HLXT17", "HLXP1", "HLXD10", "HLXR3", "HLXB13"]
NEIGHBOURS = 10
HOT_SHARE = (0.62, 0.20, 0.08)          # the share of a driver's alterations at its three hotspots; the rest are spread over its other sites
CAPTURED = "2026-11-16"
METHODS = ["Scan-guided library: single changes at tolerant positions", "Recombination of the best single changes",
           "Model-guided, trained on rounds 1 and 2", "Model-guided, with the scan as the prior",
           "Model-guided, with stability as a second goal", "Local search around the round's best",
           "Model-guided with a trust region on the scan", "Final polish around the lead"]
NOTES = ["Mostly small gains: the natural protein is the start.", "The first beneficial changes are combined.",
         "The designs spread; some are caught on a false peak.", "The false peak still holds a share of the designs.",
         "Stability is added as a second goal; the false peak holds on.", "The best designs pull away from the false peak.",
         "The campaign leaves the false peak behind.", "The campaign reaches the summit."]


def rng(seed: int, stage: str) -> random.Random:
    """The generator of one stage (a string seed is hashed the same way on every run and every Python 3)."""
    return random.Random(f"{seed}:{stage}")


def clamp(v: float, lo: float, hi: float) -> float:
    return lo if v < lo else hi if v > hi else v


def doc(kind: str, **v) -> dict:
    """A document of a kind, its keys in the kind's field order; a field the spec lists must be given (None where empty)."""
    return {f.name: v[f.name] for f in S.BY_KIND[kind].fields}


# ------------------------------------------------------------------------------------------------------------------------ protein
class Protein:
    """One protein: its fold, coordinates, burial, contacts, active site, sequence and the scan, all generated and measured."""

    def __init__(self, seed: int, index: int, gene: str, name: str, length: int, family: str):
        self.gene, self.name, self.L, self.family = gene, name, length, family
        r = rng(seed, f"protein:{gene}")
        self.secondary = self._fold(r)
        self.xyz = self._coordinates()
        self._measure()
        self.sequence = self._sequence(r)
        self.hot = self._beneficial(r)
        self.activity = self._scan(rng(seed, f"scan:{gene}:activity"), 1.0)
        self.stability = self._scan(rng(seed, f"scan:{gene}:stability"), 0.65)
        self.mean_activity = [self._mean(row, i) for i, row in enumerate(self.activity)]
        self.mean_stability = [self._mean(row, i) for i, row in enumerate(self.stability)]

    # the fold: loops of 3 to 7, then a helix (60%) of 9 to 17 or a strand of 5 to 8
    def _fold(self, r) -> list:
        ss, k = [], 0
        while len(ss) < self.L:
            for _ in range(3 + int(r.random() * 5)):
                if len(ss) < self.L:
                    ss.append(("L", -1, 0, 0))
            if len(ss) >= self.L:
                break
            helix = r.random() < 0.6
            n = min(self.L - len(ss), 9 + int(r.random() * 9) if helix else 5 + int(r.random() * 4))
            for j in range(n):
                ss.append(("H" if helix else "E", k, j, n))
            k += 1
        return ss

    # helices and strands laid on a grid of columns, joined by loops that bridge their ends
    def _coordinates(self) -> list:
        L, ss = self.L, self.secondary
        xyz: list = [None] * L
        i = 0
        while i < L:
            t, k, _, n = ss[i]
            if t == "L":
                i += 1
                continue
            gr = k // 5
            gc = (4 - k % 5) if gr % 2 else k % 5
            bx, by = gc * 10 + (2 if gr % 2 else -2), gr * 9.5
            direction = -1 if k % 2 else 1
            rise = 1.5 if t == "H" else 3.2
            length = (n - 1) * rise
            for j in range(n):
                z = direction * (-length / 2 + j * rise)
                xyz[i] = ((bx + 2.3 * math.cos(j * 1.745), by + 2.3 * math.sin(j * 1.745), z) if t == "H"
                          else (bx + (0.9 if j % 2 else -0.9), by, z))
                i += 1
        i = 0
        while i < L:
            if xyz[i] is not None:
                i += 1
                continue
            a = i
            while i < L and xyz[i] is None:
                i += 1
            b, n = i, i - a
            P = xyz[a - 1] if a > 0 else None
            Q = xyz[b] if b < L else None
            for j in range(a, b):
                t = (j - a + 1) / (n + 1)
                if P and Q:
                    mx, my = (P[0] + Q[0]) / 2, (P[1] + Q[1]) / 2
                    ml = math.hypot(mx, my) or 1.0
                    bu = math.sin(math.pi * t) * (3 + n * 0.6)
                    zs = (1 if P[2] >= 0 else -1)
                    xyz[j] = (P[0] + (Q[0] - P[0]) * t + mx / ml * bu * 0.6, P[1] + (Q[1] - P[1]) * t + my / ml * bu * 0.6,
                              P[2] + (Q[2] - P[2]) * t + zs * bu)
                else:
                    E = P or Q
                    s = (j - a + 1) if P else (b - j)
                    zs = (1 if E[2] >= 0 else -1)
                    xyz[j] = (E[0] + s * 1.4, E[1] + s * 1.1, E[2] + s * 2 * zs)
        c = [math.fsum(p[d] for p in xyz) / L for d in range(3)]
        return [(p[0] - c[0], p[1] - c[1], p[2] - c[2]) for p in xyz]

    # burial = the rank of the neighbours within 11 angstroms; contacts = the nearest pairs; the active site = the residue nearest the
    # middle of the fold and its five nearest neighbours in space
    def _measure(self) -> None:
        L, xyz = self.L, self.xyz
        count = [0] * L
        pairs = []
        for i in range(L):
            for j in range(i + 1, L):
                d = math.dist(xyz[i], xyz[j])
                if d < 11:
                    count[i] += 1
                    count[j] += 1
                if j - i >= 3:
                    pairs.append((d, i, j))
        order = sorted(range(L), key=lambda i: (count[i] + i * 1e-4, i))
        self.burial = [0.0] * L
        for rank, i in enumerate(order):
            self.burial[i] = rank / (L - 1)
        pairs.sort()
        self.pairs = pairs
        c0 = min(range(L), key=lambda i: (math.hypot(*xyz[i]), i))
        near = sorted((math.dist(xyz[i], xyz[c0]), i) for i in range(L) if abs(i - c0) > 3)[:5]
        self.centre = c0
        self.active = sorted({c0} | {i for _, i in near})

    def _sequence(self, r) -> list:
        seq = []
        for i in range(self.L):
            if i == 0:
                seq.append("M")
                continue
            if i in self.active:
                seq.append("DEHKC"[int(r.random() * 5)])
                continue
            pool = ("AVILMF" if self.burial[i] > 0.55 else "SGTNQDEKRAHP" if self.secondary[i][0] == "L" else "STNQDEKRAH")
            seq.append(pool[int(r.random() * len(pool))])
        return seq

    # 14 beneficial changes on the surface, away from the active site and from each other
    def _beneficial(self, r) -> list:
        hot, tries = [], 0
        while len(hot) < 14 and tries < 9000:
            tries += 1
            i = 6 + int(r.random() * (self.L - 12))
            if self.burial[i] > 0.6 or i in self.active or any(abs(h[0] - i) < 6 for h in hot):
                continue
            while True:
                a = int(r.random() * 20)
                if AA20[a] != self.sequence[i] and AA20[a] != "P":
                    break
            hot.append((i, a, 0.5 + r.random() * 1.2))
        hot.sort(key=lambda h: -h[2])
        return hot

    # buried positions and the active site are intolerant; a like-for-like change is mild; proline breaks helices and strands
    def _scan(self, r, scale: float) -> list:
        rows = []
        hot_at = {(h[0], h[1]): h[2] for h in self.hot}
        hot_pos = {h[0] for h in self.hot}
        for i in range(self.L):
            wt, row = self.sequence[i], []
            for a, x in enumerate(AA20):
                if x == wt:
                    row.append(0.0)
                    continue
                v = -(0.12 + 2 * self.burial[i] ** 1.6) * (0.35 if GROUP[x] == GROUP[wt] else 1.0)
                if x == "P" and self.secondary[i][0] != "L":
                    v -= 1.3
                if i in self.active:
                    v -= 2.2
                v += (r.random() - 0.5) * 0.5
                if (i, a) in hot_at:
                    v = hot_at[(i, a)] * scale + (r.random() - 0.5) * 0.2
                elif i in hot_pos:
                    v = max(v, -0.4) + 0.1
                row.append(round(clamp(v, -3.2, 1.8), 3))
            rows.append(row)
        return rows

    def _mean(self, row: list, i: int) -> float:
        vals = [v for a, v in enumerate(row) if AA20[a] != self.sequence[i]]
        return round(math.fsum(vals) / len(vals), 3)

    def domain(self, i: int) -> str:
        f = i / self.L
        return "N-lobe" if f < 0.45 else "hinge" if f < 0.55 else "C-lobe"

    def worst(self, i: int) -> int:
        """The amino acid whose change at position i hurts activity most."""
        row = self.activity[i]
        return min((a for a in range(20) if AA20[a] != self.sequence[i]), key=lambda a: (row[a], a))

    def hotspots(self) -> list:
        """Three damaging (position, amino acid) pairs for the gene's variants: the active site's centre, then the two worst positions
        elsewhere (buried, at least 10 apart)."""
        out = [self.centre]
        for i in sorted(range(self.L), key=lambda i: (self.mean_activity[i], i)):
            if len(out) == 3:
                break
            if all(abs(i - p) >= 10 for p in out) and self.sequence[i] != "P":
                out.append(i)
        return [(i, self.worst(i)) for i in out]


# ------------------------------------------------------------------------------------------------------------------------ genomics
def _symbols(r) -> list:
    taken, out = {d[0] for d in DRIVERS}, []
    letters = "ABCDEFGHJKLMNPQRSTUVWXYZ"
    while len(out) < N_GENES - len(DRIVERS):
        s = f"HLX{r.choice(letters)}{r.choice(letters)}{r.randint(1, 99)}"
        if s not in taken:
            taken.add(s)
            out.append(s)
    return out


def make_genes(seed: int) -> list:
    r = rng(seed, "genes")
    lens = {p[0]: p[2] for p in PROTEINS}
    genes = []
    for sym, chrom, role in DRIVERS:
        mb = CHROM_MB[CHROM_INDEX[chrom]]
        start = int(r.uniform(0.15 * mb, 0.85 * mb) * 1e6)
        genes.append({"GENE": sym, "chrom": chrom, "START": start, "END": start + int(r.uniform(30e3, 180e3)), "ROLE": role,
                      "PROTEIN_LENGTH": lens.get(sym) or int(r.uniform(220, 740)), "driver": True})
    for sym in _symbols(r):
        ci = r.choices(range(23), weights=CHROM_MB)[0]
        start = int(r.uniform(0.5, CHROM_MB[ci] - 0.5) * 1e6)
        genes.append({"GENE": sym, "chrom": CHROMS[ci], "START": start, "END": start + int(r.uniform(8e3, 220e3)),
                      "ROLE": r.choice(ROLES), "PROTEIN_LENGTH": int(r.uniform(180, 900)), "driver": False})
    by = {g["GENE"]: g for g in genes}
    background = [g for g in genes if not g["driver"]]
    r.shuffle(background)
    for sym in PEAK_GENES:                       # neighbours placed within 1.4 Mb of each peak gene: the locus plot's hitchhikers
        lead = by[sym]
        lead["neighbours"] = []
        for _ in range(NEIGHBOURS):
            g = background.pop()
            g["chrom"] = lead["chrom"]
            g["START"] = max(100_000, lead["START"] + int(r.uniform(-1.4e6, 1.4e6)))
            g["END"] = g["START"] + int(r.uniform(8e3, 120e3))
            lead["neighbours"].append(g["GENE"])
        lead["neighbours"].sort(key=lambda n: abs(by[n]["START"] - lead["START"]))
    genes.sort(key=lambda g: (CHROM_INDEX[g["chrom"]], g["START"], g["GENE"]))
    return genes


def make_samples(seed: int) -> list:
    r = rng(seed, "samples")
    out = []
    for s in range(N_SAMPLES):
        k = r.choices(range(5), weights=CLUSTER_WEIGHTS)[0]
        tumour = TUMOURS[(k + (0 if r.random() < 0.7 else 1 + int(r.random() * 3))) % 4]
        out.append({"SAMPLE_ID": f"S{s + 1:04d}", "k": k, "TUMOUR_TYPE": tumour,
                    "STAGE": r.choices(["I", "II", "III", "IV"], weights=[2, 4, 3, 1])[0],
                    "AGE_BAND": r.choices(["30-39", "40-49", "50-59", "60-69", "70-79", "80+"], weights=[1, 3, 5, 6, 4, 1])[0],
                    "EMB_X": round(clamp(CLUSTER_CENTRE[k][0] + r.gauss(0, 0.075), 0.02, 0.98), 4),
                    "EMB_Y": round(clamp(CLUSTER_CENTRE[k][1] + r.gauss(0, 0.075), 0.02, 0.98), 4)})
    return out


def _site(r, gene: dict, protein: Protein | None, pos: int | None, consequence: str, aa_alt: str | None = None) -> dict:
    """One variant site of a gene (genome id and counts are added later)."""
    coding = consequence in ("missense_variant", "synonymous_variant", "stop_gained", "frameshift_variant", "splice_region_variant")
    plen = gene["PROTEIN_LENGTH"]
    if coding and pos is None:
        pos = 1 + int(r.random() * plen)
    if coding:
        genomic = gene["START"] + int((pos - 1) / plen * (gene["END"] - gene["START"]))
    else:
        pos = None
        genomic = gene["START"] + int(r.random() * (gene["END"] - gene["START"]))
    ref = r.choice("ACGT")
    alt = r.choice([b for b in "ACGT" if b != ref])
    aa_ref = a_alt = None
    if consequence in ("missense_variant", "synonymous_variant", "stop_gained", "frameshift_variant"):
        aa_ref = protein.sequence[pos - 1] if protein else r.choice(BACKGROUND_AA)
        if consequence == "missense_variant":
            a_alt = aa_alt or r.choice([a for a in AA20 if a != aa_ref])
        elif consequence == "synonymous_variant":
            a_alt = aa_ref
        else:
            a_alt = "*" if consequence == "stop_gained" else "-"
    impact = {"missense_variant": "MODERATE", "stop_gained": "HIGH", "frameshift_variant": "HIGH", "synonymous_variant": "LOW",
              "splice_region_variant": "LOW", "intron_variant": "MODIFIER"}[consequence]
    return {"gene": gene["GENE"], "CHROM": gene["chrom"], "POS": genomic, "REF": ref, "ALT": alt, "CONSEQUENCE": consequence,
            "IMPACT": impact, "PROTEIN_POS": pos, "AA_REF": aa_ref, "AA_ALT": a_alt, "role": "other"}


def make_variants(seed: int, genes: list, proteins: dict) -> list:
    """12,000 sites: 220 in HLXK7 and 100 in each other driver (three hotspots each), the rest over the other genes; one site of each
    neighbour of a peak gene is marked as linked to the peak."""
    r = rng(seed, "variants")
    sites = []
    for g in genes:
        if not g["driver"]:
            continue
        prot = proteins.get(g["GENE"])
        n = 220 if g["GENE"] == LEAD_GENE else 100
        if prot:
            hots = [(i + 1, AA20[a]) for i, a in prot.hotspots()]
        else:
            plen = g["PROTEIN_LENGTH"]
            hots = [(int(plen * (f + r.random() * 0.1)) + 1, r.choice(AA20)) for f in (0.15, 0.55, 0.35)]
        for h, (pos, aa) in enumerate(hots):
            s = _site(r, g, prot, pos, "missense_variant", aa)
            s["role"] = f"hot{h + 1}"
            sites.append(s)
        for _ in range(n - 3):
            c = r.choices(["missense_variant", "stop_gained", "frameshift_variant"], weights=[70, 15, 15])[0]
            s = _site(r, g, prot, None, c)
            s["role"] = "driver"
            sites.append(s)
    background = [g for g in genes if not g["driver"]]
    extra = defaultdict(int)
    for g in r.choices(background, k=N_VARIANTS - len(sites) - 4 * len(background)):
        extra[g["GENE"]] += 1
    for g in background:
        for _ in range(4 + extra[g["GENE"]]):
            c = r.choices(["missense_variant", "synonymous_variant", "stop_gained", "frameshift_variant", "splice_region_variant",
                           "intron_variant"], weights=[55, 27, 4, 4, 5, 5])[0]
            sites.append(_site(r, g, None, None, c))
    by_gene = defaultdict(list)
    for s in sites:
        by_gene[s["gene"]].append(s)
    gene = {g["GENE"]: g for g in genes}
    for sym in PEAK_GENES:                        # the neighbours' first site follows the peak's lead: stronger the nearer it is
        for rank, n in enumerate(gene[sym]["neighbours"]):
            s = by_gene[n][0]
            s["role"], s["linked"] = "linked", (sym, round(0.85 - 0.04 * rank, 2))
    sites.sort(key=lambda s: (CHROM_INDEX[s["CHROM"]], s["POS"]))
    for n, s in enumerate(sites):
        s["VARIANT_ID"] = f"HX-{n + 1:06d}"
    return sites


def make_calls(seed: int, genes: list, samples: list, sites: list) -> tuple:
    """45,000 calls: the drivers' alterations at their hotspots and other sites, the linked sites that follow a peak's lead, and
    passengers (heavy-tailed site frequencies, more in the immune-hot and proliferative clusters) up to the exact total."""
    r = rng(seed, "calls")
    n = len(samples)
    carriers = defaultdict(set)                      # site index -> sample indices
    altered = [set() for _ in samples]               # the driver genes each sample is altered in
    drivers = [g for g in genes if g["driver"]]
    # the drivers keep their strength order by their place in DRIVERS
    ranked = sorted(drivers, key=lambda g: [d[0] for d in DRIVERS].index(g["GENE"]))
    of_gene = defaultdict(lambda: defaultdict(list))
    for vi, s in enumerate(sites):
        of_gene[s["gene"]][s["role"]].append(vi)
    freq = {g["GENE"]: 0.40 * (0.05 / 0.40) ** (i / 23) for i, g in enumerate(ranked)}
    for si, smp in enumerate(samples):
        for gi, g in enumerate(ranked):
            sym = g["GENE"]
            if r.random() >= freq[sym] * (1.9 if gi % 5 == smp["k"] else 0.75):
                continue
            u, sh = r.random(), HOT_SHARE
            roles = of_gene[sym]
            if u < sh[0]:
                vi = roles["hot1"][0]
            elif u < sh[0] + sh[1]:
                vi = roles["hot2"][0]
            elif u < math.fsum(sh):
                vi = roles["hot3"][0]
            else:
                vi = roles["driver"][int(r.random() * len(roles["driver"]))]
            carriers[vi].add(si)
            altered[si].add(sym)
    for vi, s in enumerate(sites):                   # a linked site follows its peak's lead variant, with a little noise
        if s["role"] != "linked":
            continue
        lead, strength = s["linked"]
        lead_set = carriers[of_gene[lead]["hot1"][0]]
        for si in sorted(lead_set):
            if r.random() < strength:
                carriers[vi].add(si)
        for si in range(n):
            if si not in lead_set and r.random() < 0.012:
                carriers[vi].add(si)
    used = sum(len(c) for c in carriers.values())
    remaining = N_CALLS - used
    if remaining <= 0:
        raise ValueError(f"the drivers already make {used} calls")
    pool = [vi for vi, s in enumerate(sites) if s["role"] == "other"]
    weights = [min(r.paretovariate(1.6), 30.0) for _ in pool]
    cum, acc = [], 0.0
    for w in weights:
        acc += w
        cum.append(acc)
    burden = [CLUSTER_BURDEN[s["k"]] * r.lognormvariate(0, 0.35) for s in samples]
    total = math.fsum(burden)
    exact = [remaining * b / total for b in burden]
    counts = [int(x) for x in exact]
    for i in sorted(range(n), key=lambda i: (-(exact[i] - counts[i]), i))[:remaining - sum(counts)]:
        counts[i] += 1
    for si in range(n):
        chosen: set = set()
        while len(chosen) < counts[si]:
            chosen.update(r.choices(pool, cum_weights=cum, k=counts[si] - len(chosen)))
        for vi in chosen:
            carriers[vi].add(si)
    calls = sorted((si, vi) for vi, cs in carriers.items() for si in cs)
    assert len(calls) == N_CALLS, len(calls)
    return calls, carriers, altered


def sigmoid(x: float) -> float:
    return 1 / (1 + math.exp(-x))


def make_traits(seed: int, samples: list, altered: list) -> dict:
    """RESPONSE, TOXICITY and RELAPSE per sample: a logistic model of the planted driver effects and the cluster."""
    r = rng(seed, "traits")
    out = {t: [] for t in TRAITS}
    for si, s in enumerate(samples):
        for trait, (base, gene_effect, cluster_effect) in TRAITS.items():
            x = base + math.fsum(e for g, e in gene_effect.items() if g in altered[si]) + cluster_effect.get(s["k"], 0.0)
            out[trait].append(r.random() < sigmoid(x))
    return out


def wald(carriers: int, cases_in: int, cases: int, n: int) -> tuple:
    """(p, -log10 p, beta, se) of the log odds ratio of a carrier against a non-carrier, 0.5 added to each cell."""
    if carriers == 0:
        return 1.0, 0.0, None, None
    a, b = cases_in + 0.5, carriers - cases_in + 0.5
    c, d = cases - cases_in + 0.5, (n - carriers) - (cases - cases_in) + 0.5
    beta = math.log(a * d / (b * c))
    se = math.sqrt(1 / a + 1 / b + 1 / c + 1 / d)
    z = abs(beta) / se
    p = math.erfc(z / math.sqrt(2))
    nlp = -math.log10(p) if p > 0 else (z * z / 2 + math.log(z * math.sqrt(math.pi / 2))) / math.log(10)
    return p, nlp, beta, se


def r2(a: set, b: set, n: int) -> float:
    both = len(a & b)
    den = len(a) * (n - len(a)) * len(b) * (n - len(b))
    return (both * n - len(a) * len(b)) ** 2 / den if den else 0.0


def make_associations(sites: list, carriers: dict, traits: dict, n: int) -> list:
    """36,000 tests (every trait, every site), each from the calls and the traits; R2_LEAD against the lead of the peak the site is in."""
    out = []
    for trait, yes in traits.items():
        cases = sum(yes)
        rows = []
        for vi, s in enumerate(sites):
            cs = carriers.get(vi, set())
            rows.append(wald(len(cs), sum(1 for c in cs if yes[c]), cases, n))
        leads = []                                   # greedy peaks: the highest site above 5 and every site within 2 Mb of it
        for vi in sorted(range(len(sites)), key=lambda i: -rows[i][1]):
            if rows[vi][1] < 5:
                break
            s = sites[vi]
            if not any(sites[l]["CHROM"] == s["CHROM"] and abs(sites[l]["POS"] - s["POS"]) <= 2_000_000 for l in leads):
                leads.append(vi)
        linked = [0.0] * len(sites)
        for lead in leads:
            ls = sites[lead]
            for vi, s in enumerate(sites):
                if s["CHROM"] == ls["CHROM"] and abs(s["POS"] - ls["POS"]) <= 2_000_000:
                    linked[vi] = max(linked[vi], 1.0 if vi == lead else r2(carriers.get(lead, set()), carriers.get(vi, set()), n))
        for vi, s in enumerate(sites):
            p, nlp, beta, se = rows[vi]
            out.append(doc("association", ASSOC_ID=f"GWAS-{trait}-{s['VARIANT_ID']}", TRAIT=trait, VARIANT_ID=s["VARIANT_ID"],
                           CHROM=s["CHROM"], POS=s["POS"], GENE=s["gene"], P=float(f"{max(p, 1e-300):.4g}"),
                           NEG_LOG10_P=round(nlp, 3), BETA=None if beta is None else round(beta, 3),
                           SE=None if se is None else round(se, 3), R2_LEAD=round(linked[vi], 3)))
    return out


def make_cnv(seed: int, genes: list, samples: list, altered: list) -> list:
    """3,000 segments: deep deletions of altered tumour suppressors and amplifications of other altered drivers, then broad gains and
    losses to the exact total."""
    r = rng(seed, "cnv")
    gene = {g["GENE"]: g for g in genes}
    rows = []
    for si in range(len(samples)):
        for sym in sorted(altered[si]):
            if r.random() >= 0.12:
                continue
            g = gene[sym]
            suppressor = g["ROLE"] == "tumour suppressor"
            cn = r.choice([0, 0, 1]) if suppressor else r.choice([4, 5, 6, 8])
            rows.append((si, g["chrom"], max(1, g["START"] - int(r.uniform(2e4, 5e5))), g["END"] + int(r.uniform(2e4, 5e5)), cn,
                         "deep deletion" if cn == 0 else "loss" if cn == 1 else "amplification", sym))
    while len(rows) < N_CNV:
        ci = r.choices(range(23), weights=CHROM_MB)[0]
        start = int(r.uniform(0, CHROM_MB[ci] - 60) * 1e6) + 1
        cn = r.choices([1, 3, 4], weights=[5, 6, 2])[0]
        rows.append((int(r.random() * len(samples)), CHROMS[ci], start, start + int(r.uniform(5e6, 60e6)), cn,
                     "loss" if cn == 1 else "gain" if cn == 3 else "amplification", None))
    rows = rows[:N_CNV]
    rows.sort(key=lambda t: (t[0], CHROM_INDEX[t[1]], t[2]))
    return [doc("cnv-segment", SEGMENT_ID=f"CNV-{i + 1:05d}", SAMPLE_ID=samples[t[0]]["SAMPLE_ID"], CHROM=t[1], START=t[2], END=t[3],
                COPY_NUMBER=t[4], CALL=t[5], GENE=t[6]) for i, t in enumerate(rows)]


def make_pathways(seed: int, genes: list) -> list:
    r = rng(seed, "pathways")
    members = set()
    for g in genes:
        if g["driver"]:
            for p in r.sample(range(12), 2):
                members.add((p, g["GENE"]))
    background = [g["GENE"] for g in genes if not g["driver"]]
    while len(members) < N_PATHWAY:
        members.add((r.randrange(12), r.choice(background)))
    rows = sorted(members)
    return [doc("pathway-gene", PATHWAY_GENE_ID=f"PWG-{i + 1:04d}", PATHWAY=PATHWAYS[p], GENE=g, ROLE_IN_PATHWAY=r.choice(PATHWAY_ROLES))
            for i, (p, g) in enumerate(rows)]


# ------------------------------------------------------------------------------------------------------------------------ campaign
def fitness(x: float, y: float) -> float:
    """The campaign's fitness field: a global peak, a smaller false peak and a broad ridge."""
    return (math.exp(-((x - 0.74) ** 2 + (y - 0.32) ** 2) / 0.03) + 0.34 * math.exp(-((x - 0.30) ** 2 + (y - 0.74) ** 2) / 0.02)
            + 0.18 * math.exp(-((x - 0.5) ** 2 + (y - 0.5) ** 2) / 0.12))


def make_campaign(seed: int, prot: Protein) -> tuple:
    """Eight rounds of 1,500 designs of one protein: positions drawn nearer the global peak each round, part of rounds 3 to 6 caught on
    the false one; changes drawn increasingly from the beneficial set, to exactly 33,000; the winner three changes."""
    r = rng(seed, "campaign")
    L, hot = prot.L, prot.hot
    designs = []
    for rd in range(1, ROUNDS + 1):
        t = (rd - 1) / (ROUNDS - 1)
        cx, cy, sd = 0.2 + 0.54 * t, 0.56 - 0.24 * t + 0.1 * math.sin(t * 3.1), 0.125 - 0.07 * t
        for n in range(1, PER_ROUND + 1):
            trap = 3 <= rd <= 6 and r.random() < 0.2
            x = clamp((0.30 if trap else cx) + r.gauss(0, 0.045 if trap else sd), 0.02, 0.98)
            y = clamp((0.74 if trap else cy) + r.gauss(0, 0.045 if trap else sd), 0.03, 0.97)
            fit = max(0.2, 0.5 + 12.5 * fitness(x, y) * (0.86 + 0.28 * r.random()) + r.gauss(0, 0.2))
            nm = 1 + int(r.random() * (1 + rd * 0.7))
            muts: dict = {}
            for _ in range(nm):
                if r.random() < min(0.92, 0.1 + 0.09 * rd + (0.25 if fit > 6 else 0)):
                    i, a, _g = hot[int(r.random() * r.random() * len(hot))]
                else:
                    i = 4 + int(r.random() * (L - 8))
                    a = r.choice([k for k in range(20) if AA20[k] != prot.sequence[i]])
                muts.setdefault(i, a)
            designs.append({"round": rd, "n": n, "x": x, "y": y, "fit": fit, "trap": trap, "muts": muts,
                            "pred": fit * (0.78 + 0.44 * r.random()), "id": f"DSG-{prot.gene}-R{rd}-{n:04d}"})
    lead = max(designs, key=lambda d: d["fit"])
    lead["muts"] = {i: a for i, a, _g in hot[:3]}
    total = sum(len(d["muts"]) for d in designs)
    rest = [d for d in designs if d is not lead]
    while total < N_MUTATIONS:
        d = rest[int(r.random() * len(rest))]
        if len(d["muts"]) >= 8:
            continue
        i, a, _g = hot[int(r.random() * len(hot))] if r.random() < 0.5 else (4 + int(r.random() * (L - 8)), None, 0)
        if i in d["muts"]:
            continue
        d["muts"][i] = a if a is not None else r.choice([k for k in range(20) if AA20[k] != prot.sequence[i]])
        total += 1
    while total > N_MUTATIONS:
        d = rest[int(r.random() * len(rest))]
        if len(d["muts"]) < 2:
            continue
        del d["muts"][sorted(d["muts"])[int(r.random() * len(d["muts"]))]]
        total -= 1
    tm_rng = rng(seed, "campaign-tm")
    for d in designs:
        d["tm"] = math.fsum(prot.stability[i][a] * 2.4 for i, a in d["muts"].items()) + tm_rng.gauss(0, 0.5)
    by_round = defaultdict(list)
    for d in designs:
        by_round[d["round"]].append(d)
    for rd, ds in by_round.items():
        top = sorted(ds, key=lambda d: -d["fit"])
        for d in top[:PER_ROUND // 20]:
            d["status"] = "selected"
        by_round[rd] = ds
    for d in designs:
        d.setdefault("status", "tested")
    lead["status"] = "lead"
    for rd in range(2, ROUNDS + 1):                  # the parent: the nearest of the previous round's best 200 on the map
        prev = sorted(by_round[rd - 1], key=lambda d: -d["fit"])[:200]
        for d in by_round[rd]:
            d["parent"] = min(prev, key=lambda p: ((p["x"] - d["x"]) ** 2 + (p["y"] - d["y"]) ** 2, p["id"]))["id"]
    return designs, by_round, lead


# ------------------------------------------------------------------------------------------------------------------------ the world
def build(seed: int = SEED) -> tuple:
    """(docs, story): docs {pack: {kind: {id: document}}}; story the headline ids the pack's examples and tests use."""
    proteins = {p[0]: Protein(seed, i, *p) for i, p in enumerate(PROTEINS)}
    genes = make_genes(seed)
    samples = make_samples(seed)
    sites = make_variants(seed, genes, proteins)
    calls, carriers, altered = make_calls(seed, genes, samples, sites)
    traits = make_traits(seed, samples, altered)
    for si, s in enumerate(samples):
        s["RESPONSE"] = "responder" if traits["RESPONSE"][si] else "non-responder"
        s["TOXICITY"] = "severe" if traits["TOXICITY"][si] else "none"
        s["RELAPSE"] = "relapsed" if traits["RELAPSE"][si] else "free"
    vr = rng(seed, "reads")
    call_docs, per_sample = [], defaultdict(int)
    gene_altered = defaultdict(set)
    for n, (si, vi) in enumerate(calls):
        s = sites[vi]
        vaf = clamp(vr.gauss(0.38 if s["role"] != "other" else 0.25, 0.12 if s["role"] != "other" else 0.1), 0.05, 0.95)
        call_docs.append(doc("variant-call", CALL_ID=f"CALL-{n + 1:06d}", SAMPLE_ID=samples[si]["SAMPLE_ID"], VARIANT_ID=s["VARIANT_ID"],
                             GENE=s["gene"], GENOTYPE="1/1" if vaf > 0.8 else "0/1", VAF=round(vaf, 3),
                             DEPTH=max(30, int(vr.gauss(180, 50)))))
        per_sample[si] += 1
        if s["IMPACT"] in ("HIGH", "MODERATE"):
            gene_altered[s["gene"]].add(si)
    gene_docs = {}
    count = defaultdict(int)
    for s in sites:
        count[s["gene"]] += 1
    for g in genes:
        alt = len(gene_altered[g["GENE"]])
        gene_docs[g["GENE"]] = doc("study-gene", GENE=g["GENE"], CHROM=g["chrom"], START=g["START"], END=g["END"],
                                   STRAND="+" if (g["START"] // 1000) % 2 == 0 else "-", ROLE=g["ROLE"],
                                   PROTEIN_LENGTH=g["PROTEIN_LENGTH"], DRIVER=g["driver"], VARIANTS=count[g["GENE"]],
                                   ALTERED_SAMPLES=alt, ALTERED_FRACTION=round(alt / N_SAMPLES, 4))
    sample_docs = {s["SAMPLE_ID"]: doc("study-sample", SAMPLE_ID=s["SAMPLE_ID"], COHORT="ONC-1", TUMOUR_TYPE=s["TUMOUR_TYPE"],
                                       STAGE=s["STAGE"], AGE_BAND=s["AGE_BAND"], RESPONSE=s["RESPONSE"], TOXICITY=s["TOXICITY"],
                                       RELAPSE=s["RELAPSE"], CLUSTER=CLUSTERS[s["k"]], EMB_X=s["EMB_X"], EMB_Y=s["EMB_Y"],
                                       MUTATION_COUNT=per_sample[i]) for i, s in enumerate(samples)}
    variant_docs = {s["VARIANT_ID"]: doc("study-variant", VARIANT_ID=s["VARIANT_ID"], CHROM=s["CHROM"], POS=s["POS"], REF=s["REF"],
                                         ALT=s["ALT"], GENE=s["gene"], CONSEQUENCE=s["CONSEQUENCE"], IMPACT=s["IMPACT"],
                                         PROTEIN_POS=s["PROTEIN_POS"], AA_REF=s["AA_REF"], AA_ALT=s["AA_ALT"],
                                         CARRIERS=len(carriers.get(vi, ()))) for vi, s in enumerate(sites)}
    assoc = make_associations(sites, carriers, traits, N_SAMPLES)
    genomics = {"study-gene": gene_docs, "study-sample": sample_docs, "study-variant": variant_docs,
                "variant-call": {d["CALL_ID"]: d for d in call_docs}, "association": {d["ASSOC_ID"]: d for d in assoc}}
    cnv = make_cnv(seed, genes, samples, altered)
    genomics["cnv-segment"] = {d["SEGMENT_ID"]: d for d in cnv}
    pw = make_pathways(seed, genes)
    genomics["pathway-gene"] = {d["PATHWAY_GENE_ID"]: d for d in pw}

    # ---- protein engineering
    lead_prot = proteins[LEAD_GENE]
    designs, by_round, lead = make_campaign(seed, lead_prot)
    protein_docs, residue_docs, contact_docs, dms_docs = {}, {}, {}, {}
    quota = [N_CONTACTS * p.L // sum(q.L for q in proteins.values()) for p in proteins.values()]
    for i in sorted(range(4), key=lambda i: -((N_CONTACTS * list(proteins.values())[i].L) % sum(q.L for q in proteins.values())))[
            :N_CONTACTS - sum(quota)]:
        quota[i] += 1
    for pi, (sym, p) in enumerate(proteins.items()):
        protein_docs[sym] = doc("protein", PROTEIN=sym, GENE=sym, NAME=p.name, LENGTH=p.L, FAMILY=p.family, SEQUENCE="".join(p.sequence),
                                ACTIVE_SITE=[i + 1 for i in p.active], DESIGN_ROUNDS=ROUNDS if sym == LEAD_GENE else 0)
        for i in range(p.L):
            x, y, z = p.xyz[i]
            rid = f"{sym}:{i + 1}"
            residue_docs[rid] = doc("residue", RESIDUE_ID=rid, PROTEIN=sym, POS=i + 1, AA=p.sequence[i],
                                    SECONDARY={"H": "helix", "E": "strand", "L": "loop"}[p.secondary[i][0]], DOMAIN=p.domain(i),
                                    BURIAL=round(p.burial[i], 3), ACTIVE_SITE=i in p.active, X=round(x, 2), Y=round(y, 2), Z=round(z, 2),
                                    MEAN_ACTIVITY=p.mean_activity[i], MEAN_STABILITY=p.mean_stability[i])
        for d, i, j in p.pairs[:quota[pi]]:
            cid = f"{sym}:{i + 1}-{j + 1}"
            contact_docs[cid] = doc("residue-contact", CONTACT_ID=cid, PROTEIN=sym, POS_A=i + 1, POS_B=j + 1, DISTANCE=round(d, 2))
        sr = rng(seed, f"se:{sym}")
        for assay, scan in (("activity", p.activity), ("stability", p.stability)):
            for i in range(p.L):
                for a, aa in enumerate(AA20):
                    sid = f"{sym}:{i + 1}:{aa}:{assay}"
                    dms_docs[sid] = doc("dms-score", SCORE_ID=sid, PROTEIN=sym, POS=i + 1, AA_REF=p.sequence[i], AA=aa,
                                        MUTATION=f"{p.sequence[i]}{i + 1}{aa}", ASSAY=assay, SCORE=scan[i][a],
                                        SE=round(0.04 + 0.2 * sr.random(), 3), REPLICATES=3)
    design_docs, mutation_docs = {}, {}
    for d in designs:
        ms = sorted(d["muts"].items())
        text = " ".join(f"{lead_prot.sequence[i]}{i + 1}{AA20[a]}" for i, a in ms)
        design_docs[d["id"]] = doc("design", DESIGN_ID=d["id"], PROTEIN=LEAD_GENE, ROUND=d["round"], PARENT=d.get("parent", "NATURAL"),
                                   N_CHANGES=len(ms), CHANGES=text, ACTIVITY=round(d["fit"], 2), DELTA_TM=round(d["tm"], 2),
                                   MODEL_SCORE=round(d["pred"], 2), STATUS=d["status"], EMB_X=round(d["x"], 4), EMB_Y=round(d["y"], 4))
        for i, a in ms:
            mid = f"{d['id']}:{i + 1}"
            mutation_docs[mid] = doc("design-mutation", DESIGN_MUTATION_ID=mid, DESIGN_ID=d["id"], PROTEIN=LEAD_GENE, ROUND=d["round"],
                                     POS=i + 1, AA_FROM=lead_prot.sequence[i], AA_TO=AA20[a],
                                     MUTATION=f"{lead_prot.sequence[i]}{i + 1}{AA20[a]}")
    round_docs, start = {}, date(2026, 3, 2)
    for rd in range(1, ROUNDS + 1):
        fits = sorted(d["fit"] for d in by_round[rd])
        best = max(by_round[rd], key=lambda d: d["fit"])
        rid = f"{LEAD_GENE}-R{rd}"
        round_docs[rid] = doc("design-round", ROUND_ID=rid, PROTEIN=LEAD_GENE, ROUND=rd, METHOD=METHODS[rd - 1],
                              STARTED=(start + timedelta(days=7 * (rd - 1))).isoformat(), DESIGNS=len(by_round[rd]),
                              BEST_DESIGN=best["id"], BEST_ACTIVITY=round(best["fit"], 2),
                              MEDIAN_ACTIVITY=round(fits[len(fits) // 2], 2), TRAPPED=sum(1 for d in by_round[rd] if d["trap"]),
                              NOTE=NOTES[rd - 1])
    protein = {"protein": protein_docs, "residue": residue_docs, "residue-contact": contact_docs, "dms-score": dms_docs,
               "design": design_docs, "design-mutation": mutation_docs, "design-round": round_docs}
    lead_variant = next(s["VARIANT_ID"] for s in sites if s["gene"] == LEAD_GENE and s["role"] == "hot1")
    story = {"lead_gene": LEAD_GENE, "lead_variant": lead_variant, "lead_design": lead["id"], "lead_sample": "S0001",
             "lead_residue": f"{LEAD_GENE}:{lead_prot.centre + 1}", "lead_score": f"{LEAD_GENE}:{lead_prot.centre + 1}:{AA20[lead_prot.worst(lead_prot.centre)]}:activity",
             "lead_contact": next(iter(contact_docs)), "lead_round": f"{LEAD_GENE}-R{lead['round']}", "lead_segment": next(iter(genomics["cnv-segment"])),
             "lead_call": next(iter(genomics["variant-call"])), "lead_pathway": next(iter(genomics["pathway-gene"])),
             "lead_mutation": next(iter(mutation_docs))}
    return {S.GENOMICS: genomics, S.PROTEIN: protein}, story
