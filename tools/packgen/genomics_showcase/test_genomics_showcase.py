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

"""The genomics showcase's data (tools/packgen/genomics_showcase): the row counts of the design, the story the screens tell (the peak on
HLXK7, a response less than half as likely when it is altered, a scan that covers every variant, a campaign that reaches fifteen
times the natural activity with a three-change winner), byte-identical output for one seed, and the committed files matching the
generator. One world is built once for the whole module.

    python3 -m pytest -q tools/packgen/genomics_showcase/test_genomics_showcase.py
"""
from __future__ import annotations

import gzip
import hashlib
import json
import subprocess
import sys
from collections import Counter, defaultdict
from pathlib import Path

import pytest

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(ROOT / "tools" / "testdata"))
import model as M  # noqa: E402
import spec as S  # noqa: E402
import publicsnap as SNAP  # noqa: E402


@pytest.fixture(scope="module")
def world():
    return M.build(M.SEED)


def test_every_kind_has_the_rows_of_the_design(world):
    docs, _ = world
    for k in S.KINDS:
        assert len(docs[k.pack][k.kind]) == k.rows, k.kind
    assert {p: sum(len(v) for v in ks.values()) for p, ks in docs.items()} == {S.GENOMICS: 100_000, S.PROTEIN: 100_000}


def test_every_document_has_exactly_the_fields_of_its_kind_and_a_unique_key(world):
    docs, _ = world
    for k in S.KINDS:
        names = S.names(k)
        for key, d in docs[k.pack][k.kind].items():
            assert list(d) == names and d[k.key] == key, (k.kind, key)
            for f in k.fields:
                if f.presence == "required":
                    assert d[f.name] is not None, (k.kind, key, f.name)
                if f.enum and d[f.name] is not None:
                    assert d[f.name] in f.enum, (k.kind, f.name, d[f.name])


def test_nothing_is_real_every_symbol_is_invented(world):
    docs, _ = world
    assert all(g.startswith("HLX") for g in docs[S.GENOMICS]["study-gene"])
    assert all(p in {"HLXK7", "HLXT17", "HLXR3", "HLXM12"} for p in docs[S.PROTEIN]["protein"])


def test_links_resolve(world):
    docs, _ = world
    index = {k.kind: docs[k.pack][k.kind] for k in S.KINDS}
    for k in S.KINDS:
        for f in k.fields:
            if f.link:
                missing = [d[f.name] for d in docs[k.pack][k.kind].values() if d[f.name] is not None and d[f.name] not in index[f.link]]
                assert not missing, (k.kind, f.name, missing[:3])


def test_the_response_peak_is_on_the_lead_gene_and_three_others_stand_out(world):
    docs, _ = world
    rows = [d for d in docs[S.GENOMICS]["association"].values() if d["TRAIT"] == "RESPONSE"]
    top = max(rows, key=lambda d: d["NEG_LOG10_P"])
    assert top["GENE"] == M.LEAD_GENE and top["NEG_LOG10_P"] > 15
    peaks = {d["GENE"] for d in rows if d["NEG_LOG10_P"] > 5 and d["R2_LEAD"] == 1.0}
    assert {"HLXK7", "HLXT17"} <= peaks and len(peaks) >= 4, peaks
    noise = [d["NEG_LOG10_P"] for d in rows if d["GENE"] not in {"HLXK7", "HLXT17", "HLXP1", "HLXD10"} and d["R2_LEAD"] == 0.0]
    assert max(noise) < 6, max(noise)


def test_each_other_trait_has_its_own_peak(world):
    docs, _ = world
    for trait, gene in (("TOXICITY", "HLXR3"), ("RELAPSE", "HLXB13")):
        rows = [d for d in docs[S.GENOMICS]["association"].values() if d["TRAIT"] == trait]
        assert max(rows, key=lambda d: d["NEG_LOG10_P"])["GENE"] == gene, trait


def test_patients_with_an_altered_lead_gene_respond_less_than_half_as_often(world):
    docs, _ = world
    samples = docs[S.GENOMICS]["study-sample"]
    altered = {c["SAMPLE_ID"] for c in docs[S.GENOMICS]["variant-call"].values()
               if c["GENE"] == M.LEAD_GENE}
    rate = lambda ids: sum(samples[i]["RESPONSE"] == "responder" for i in ids) / len(ids)
    others = set(samples) - altered
    assert rate(altered) < 0.5 * rate(others), (rate(altered), rate(others))
    assert len(altered) > 400


def test_the_driver_frequencies_fall_from_forty_percent_to_five(world):
    docs, _ = world
    genes = docs[S.GENOMICS]["study-gene"]
    f = [genes[d[0]]["ALTERED_FRACTION"] for d in M.DRIVERS]
    assert 0.33 < f[0] < 0.47 and 0.02 < f[-1] < 0.09 and f[0] > f[11] > f[-1]
    assert sum(1 for g in genes.values() if g["DRIVER"]) == 24


def test_every_variant_of_a_protein_gene_joins_to_the_scan(world):
    docs, _ = world
    scan = docs[S.PROTEIN]["dms-score"]
    seq = {p: d["SEQUENCE"] for p, d in docs[S.PROTEIN]["protein"].items()}
    joined = 0
    for v in docs[S.GENOMICS]["study-variant"].values():
        if v["GENE"] in seq and v["CONSEQUENCE"] == "missense_variant":
            assert seq[v["GENE"]][v["PROTEIN_POS"] - 1] == v["AA_REF"], v["VARIANT_ID"]
            assert f"{v['GENE']}:{v['PROTEIN_POS']}:{v['AA_ALT']}:ACTIVITY" in scan, v["VARIANT_ID"]
            joined += 1
    assert joined > 100


def test_the_lead_variants_hurt_in_the_scan(world):
    docs, story = world
    v = docs[S.GENOMICS]["study-variant"][story["lead_variant"]]
    score = docs[S.PROTEIN]["dms-score"][f"{v['GENE']}:{v['PROTEIN_POS']}:{v['AA_ALT']}:ACTIVITY"]["SCORE"]
    assert v["GENE"] == M.LEAD_GENE and score < -1.5
    assert docs[S.PROTEIN]["residue"][f"{v['GENE']}:{v['PROTEIN_POS']}"]["ACTIVE_SITE"]


def test_the_scan_covers_every_position_amino_acid_and_assay_once(world):
    docs, _ = world
    scan = docs[S.PROTEIN]["dms-score"]
    assert len(scan) == 1196 * 20 * 2
    assert Counter(d["ASSAY"] for d in scan.values()) == {"activity": 23920, "stability": 23920}
    plants = [d for d in scan.values() if d["ASSAY"] == "activity" and d["SCORE"] > 0.4 and d["PROTEIN"] == "HLXK7"]
    assert len(plants) >= 10


def test_contacts_are_measured_from_the_coordinates(world):
    docs, _ = world
    res = docs[S.PROTEIN]["residue"]
    import math
    for c in list(docs[S.PROTEIN]["residue-contact"].values())[:300]:
        a, b = res[f"{c['PROTEIN']}:{c['POS_A']}"], res[f"{c['PROTEIN']}:{c['POS_B']}"]
        d = math.dist((a["X"], a["Y"], a["Z"]), (b["X"], b["Y"], b["Z"]))
        assert abs(d - c["DISTANCE"]) < 0.05 and c["POS_B"] - c["POS_A"] >= 3
    assert Counter(c["PROTEIN"] for c in docs[S.PROTEIN]["residue-contact"].values())["HLXK7"] > 1000


def test_the_campaign_climbs_to_fifteen_times_with_a_three_change_winner(world):
    docs, story = world
    designs = docs[S.PROTEIN]["design"]
    lead = designs[story["lead_design"]]
    assert lead["STATUS"] == "lead" and lead["N_CHANGES"] == 3 and 14 <= lead["ACTIVITY"] <= 17 and lead["DELTA_TM"] > 3
    assert Counter(d["STATUS"] for d in designs.values())["lead"] == 1
    medians = [r["MEDIAN_ACTIVITY"] for r in docs[S.PROTEIN]["design-round"].values()]
    assert medians == sorted(medians) and medians[-1] > 3 * medians[0]
    rounds = docs[S.PROTEIN]["design-round"]
    assert [rounds[f"HLXK7-R{r}"]["TRAPPED"] > 0 for r in range(1, 9)] == [False, False, True, True, True, True, False, False]
    by = defaultdict(int)
    for m in docs[S.PROTEIN]["design-mutation"].values():
        by[m["DESIGN_ID"]] += 1
    assert all(by[i] == d["N_CHANGES"] for i, d in designs.items())


def _digest(docs, pack):
    h = hashlib.sha256()
    for rel, data in sorted(SNAP.files(pack, docs[pack]).items()):
        h.update(rel.encode() + hashlib.sha256(gzip.decompress(data)).digest())
    return h.hexdigest()


def test_the_same_seed_gives_the_same_bytes(world):
    docs, story = world
    again, story2 = M.build(M.SEED)
    assert story == story2
    for pack in (S.GENOMICS, S.PROTEIN):
        assert _digest(docs, pack) == _digest(again, pack)
    other, _ = M.build(M.SEED + 1)
    assert _digest(docs, S.GENOMICS) != _digest(other, S.GENOMICS)


def test_the_committed_packs_and_data_match_the_generator():
    r = subprocess.run([sys.executable, str(HERE / "make.py"), "--check"], capture_output=True, text=True)
    assert r.returncode == 0, r.stdout + r.stderr


def test_the_committed_snapshot_is_small():
    total = sum(f.stat().st_size for p in (S.GENOMICS, S.PROTEIN) for f in (ROOT / "data" / "testdata" / p).rglob("*.gz"))
    assert 0 < total < 20e6, f"{total / 1e6:.1f} MB"
