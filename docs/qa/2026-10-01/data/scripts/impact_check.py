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

import json, sys, urllib.request, collections
port, truth, asof, ns = sys.argv[1:5]
docs = [json.loads(json.loads(l)["doc"]) for l in open(truth)]
mine = [d for d in docs if any(v == ns for v in (d.get("nettingSet"), d.get("book"), d.get("desk"), (d.get("counterparty") or {}).get("id")))]
exp_mtm = sum(d["mtm"] for d in mine if d.get("nettingSet") == ns)
d = json.load(urllib.request.urlopen(f"http://localhost:{port}/api/v1/impact/netting-set/{ns}?asOf={asof}", timeout=60))
for g in d["groups"]:
    print(g["level"], g["kind"], "items", len(g["items"]), "hidden", g["hidden"], "more", g["more"], "total", g["total"])
print("expected trades referencing", ns, len([x for x in mine if x.get("nettingSet") == ns]), "sum mtm", exp_mtm)
