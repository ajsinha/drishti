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

# title: Wrong-way risk flags on a counterparty's trades
# description: Every trade with the counterparty (search, then each trade): specific wrong-way risk where the trade references the counterparty itself or its group (as issuer, underlying or reference entity), general wrong-way risk where the reference issuer shares its sector or country, and protection sold or equity sold on names that move with it; flagged trades with their MTM, and the share of positive MTM they carry. Heuristic flags from reference data, not a model.
# kinds: counterparty, netting-set
# example: CPTY CP-SOLARIS

import pandas as pd

cp_id = view.id if view.kind == "counterparty" else view.doc["COUNTERPARTY"]
cp = view.doc if view.kind == "counterparty" else await drishti.get_async("counterparty", cp_id)
group = {n["LABEL"].split(" (")[0] for n in (cp.get("HIERARCHY") or {}).get("NODES") or []} | {cp["NAME"]}
key = cp_id.removeprefix("CP-")                                         # CP-LUMEN -> LUMEN: ISS-LUMEN, CDS-LUMEN, ...
trades = await drishti.search_async(f"TVAL where COUNTERPARTY = '{cp_id}' and NETTING_SET != '' limit 1000")
issuers = {}
rows = []
for tid in trades["ID"]:
    d = await drishti.get_async("trade", tid)
    refs = [d.get(k) for k in ("ISSUER", "UNDERLYING_EQUITY", "CREDIT_CURVE", "underlyingBond") if d.get(k)]
    flag, why = "", ""
    if any(key in r for r in refs):
        flag, why = "specific", f"references {cp_id} itself ({', '.join(r for r in refs if key in r)})"
    elif d.get("ISSUER"):
        if d["ISSUER"] not in issuers:
            issuers[d["ISSUER"]] = await drishti.get_async("issuer", d["ISSUER"])
        iss = issuers[d["ISSUER"]]
        if iss.get("NAME") in group:
            flag, why = "specific", f"issuer {iss['NAME']} is in the counterparty's group"
        elif iss.get("SECTOR") == cp.get("SECTOR"):
            flag, why = "general", f"issuer in the same sector ({iss['SECTOR']})"
        elif iss.get("COUNTRY") == cp.get("COUNTRY"):
            flag, why = "general", f"issuer in the same country ({iss['COUNTRY']})"
    sold = (d.get("TERMS") or {}).get("protection") == "Sold" or d["DIRECTION"] in ("Sell", "Short", "Sell protection")
    if flag and sold:
        why += "; we are short the name, so a default hurts twice"
    rows.append({"trade": tid, "PRODUCT": d["PRODUCT_TYPE"], "MTM": d["MTM"], "flag": flag, "why": why})
df = pd.DataFrame(rows)
flagged = df[df["flag"] != ""].sort_values("MTM", ascending=False)
show(flagged.reset_index(drop=True), title=f"{cp_id} ({cp['NAME']}, {cp.get('SECTOR')}, {cp.get('COUNTRY')}): "
                                           f"{len(flagged)} of {len(df)} trades flagged")
pos = df["MTM"].clip(lower=0).sum()
share = flagged["MTM"].clip(lower=0).sum() / pos if pos else 0
show(df.groupby("flag", as_index=False).agg(trades=("trade", "size"), mtm=("MTM", "sum")).replace({"flag": {"": "none"}}),
     title="By flag")
print(f"Flagged trades carry {share:.0%} of the positive MTM owed by {cp_id}" if len(flagged) else "No wrong-way flags")
