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

# title: Credit limit: utilisation, headroom and the PFE profile
# description: The limit's buckets: used against limit, utilisation against the early-warning line, headroom; and the peak PFE of the counterparty's netting sets in each bucket's tenor range (their exposure profiles, read in full), set against the bucket's limit: how much a new trade could add before a bucket breaches.
# kinds: credit-limit

import pandas as pd
from drishti import quant as q

doc = view.doc
warn = doc.get("earlyWarning", 0.8)
b = pd.DataFrame(doc["buckets"])
b["headroom"] = b["limit"] - b["used"]
b["status"] = ["breach" if u >= 1 else "early warning" if u >= warn else "ok" for u in b["utilisation"]]
show(b, title=f"{view.id}: {doc['counterparty']['name']}, {doc.get('measure', '')} limit {doc['limit']:,.0f} ({doc.get('status', '')})")

cp = doc["counterparty"]["id"]
sets = await drishti.search_async(f"NSET where counterparty.id = '{cp}' limit 100")
profile = []
for nid in sets["id"]:
    ns = await drishti.get_async("netting-set", nid)
    for p in ns.get("exposure") or []:
        profile.append({"netting set": nid, "years": q.tenor_years(p["tenor"]), "pfe95": p["pfe95"]})
prof = pd.DataFrame(profile)


def bucket_range(name):                                     # "1-3Y" -> (1, 3), "10Y+" -> (10, inf)
    lo, _, hi = name.rstrip("Y+").partition("-")
    return float(lo), float(hi.rstrip("Y")) if hi else float("inf")


rows = []
for _, r in b.iterrows():
    lo, hi = bucket_range(r["bucket"])
    inside = prof[(prof["years"] >= lo) & (prof["years"] <= hi)] if len(prof) else prof
    peak = inside.groupby("years")["pfe95"].sum().max() if len(inside) else 0.0     # netting sets summed per tenor
    rows.append({"bucket": r["bucket"], "limit": r["limit"], "used (booked)": r["used"], "peak PFE95 from profiles": peak,
                 "room for new PFE": r["limit"] - max(peak, r["used"])})
show(pd.DataFrame(rows), title=f"Buckets against the netting sets' PFE ({len(sets)} netting sets)")
chart(b, kind="bar", x="bucket", y=["limit", "used"], title="Limit and use by bucket")
