#!/usr/bin/env python3
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
"""A rehearsal batch of netting sets with their agreements and CSAs, in the tar layout the inbox takes.

    python tools/pvgen/bulk_netting.py --out data/rehearsal-nset --dates 2026-07-08,2026-07-09 --sets 5000

It clones the shipped synthetic records (data/testdata/counterparty-risk: netting-set, csa, agreement) up to --sets netting sets, each with its own
id, its own CSA and (one in three) its own agreement, linked by the contract's fields (AGREEMENT, CSA, NETTING_SETS, CSAS), and writes
``<out>/<kind>/<date>/<kind>.jsonl.gz`` for each kind and date: ``tar -cf eod.tar -C <out> netting-set agreement csa`` makes one tar a date. The second
and later dates move each netting set's MtM a little. Synthetic only; never commit the output.
"""
import argparse
import copy
import gzip
import json
import os
import random

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "data", "testdata", "counterparty-risk")


def rows(kind, date):
    with gzip.open(os.path.join(ROOT, kind, f"{kind}.{date}.jsonl.gz"), "rt", encoding="utf-8") as f:
        return [json.loads(line) for line in f if line.strip()]


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", required=True)
    ap.add_argument("--dates", default="2026-07-08,2026-07-09")
    ap.add_argument("--sets", type=int, default=5000)
    ap.add_argument("--seed", type=int, default=3)
    ap.add_argument("--template-date", default="2026-07-08", help="the shipped day the records are cloned from")
    a = ap.parse_args()
    rnd = random.Random(a.seed)
    ns0, csa0, agr0 = rows("netting-set", a.template_date), rows("csa", a.template_date), rows("agreement", a.template_date)
    csa_of = {c["CSA_ID"]: c for c in csa0}
    agr_of = {g["AGREEMENT_ID"]: g for g in agr0}
    base = []   # (netting set, csa, agreement) templates
    for n in ns0:
        c = csa_of.get(n["CSA"])
        g = agr_of.get(n["AGREEMENT"])
        if c and g:
            base.append((n, c, g))
    nsets, csas, agrs = [], [], {}
    for i in range(a.sets):
        n, c, g = copy.deepcopy(base[i % len(base)])
        tag = f"{i:06d}"
        gid = f"{g['AGREEMENT_ID']}-{i // 3:05d}"
        n["NETTING_SET_ID"] = f"{n['NETTING_SET_ID']}-{tag}"
        n["CSA"] = c["CSA_ID"] = f"{c['CSA_ID']}-{tag}"
        n["AGREEMENT"] = c["AGREEMENT"] = gid
        c["NETTING_SETS"] = [n["NETTING_SET_ID"]]
        if gid not in agrs:
            g["AGREEMENT_ID"] = gid
            g["CSAS"] = []
            agrs[gid] = g
        agrs[gid]["CSAS"].append(c["CSA_ID"])
        nsets.append(n)
        csas.append(c)
    for d in a.dates.split(","):
        for kind, data in (("netting-set", nsets), ("csa", csas), ("agreement", list(agrs.values()))):
            os.makedirs(os.path.join(a.out, kind, d), exist_ok=True)
            with gzip.open(os.path.join(a.out, kind, d, f"{kind}.jsonl.gz"), "wt", encoding="utf-8") as f:
                for r in data:
                    r = copy.deepcopy(r)
                    if kind == "netting-set" and d != a.dates.split(",")[0]:
                        r["NET_MTM"] = round(r["NET_MTM"] * (1 + rnd.uniform(-0.05, 0.05)), 2)
                    f.write(json.dumps(r, separators=(",", ":")) + "\n")
        print(f"{d}: {len(nsets)} netting sets, {len(csas)} CSAs, {len(agrs)} agreements")


if __name__ == "__main__":
    main()
