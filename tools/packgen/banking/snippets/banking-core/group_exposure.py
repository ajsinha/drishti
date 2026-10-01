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

# title: Counterparty group: members, exposure, expected loss
# description: The group's members read in full: rating, sector, net MTM, peak PFE and one-year PD; the group's exposure against its limit, each member's share, expected loss as PD x LGD 60% x peak PFE, a rating-weighted PD for the group, and the netting sets behind each member.
# kinds: counterparty-group
# example: GRP GRP-SUMMIT

import pandas as pd

doc = view.doc
rows, nsets = [], []
for m in doc["members"]:
    cp = await drishti.get_async("counterparty", m["id"])
    pd1 = (cp.get("kyc") or {}).get("pd1y", 0.0)
    rows.append({"member": cp["counterpartyId"], "name": cp["name"], "rating": cp.get("rating"), "sector": cp.get("sector"),
                 "country": cp.get("country"), "net MTM": cp.get("netMtm"), "peak PFE": cp.get("pfePeak"), "PD 1Y": pd1,
                 "expected loss": pd1 * 0.6 * (cp.get("pfePeak") or 0)})
    for ns in cp.get("nettingSets") or []:
        nsets.append({"member": cp["counterpartyId"], "netting set": ns["id"], "agreement": ns.get("agreement"),
                      "trades": ns.get("trades"), "net MTM": ns.get("netMtm")})
members = pd.DataFrame(rows)
total = members["peak PFE"].sum()
members["share of group PFE"] = members["peak PFE"] / total if total else 0
show(members, title=f"{view.id}: {doc['name']}, {len(members)} members")
weighted_pd = (members["PD 1Y"] * members["peak PFE"]).sum() / total if total else float("nan")
show(pd.DataFrame({
    "measure": ["group limit", "peak PFE (sum of members)", "utilisation (recomputed)", "utilisation (booked)", "net MTM",
                "exposure-weighted PD 1Y", "expected loss"],
    "value": [doc.get("limit"), total, total / doc["limit"] if doc.get("limit") else None, doc.get("utilisation"),
              members["net MTM"].sum(), f"{weighted_pd:.3%}", members["expected loss"].sum()],
}), title="The group against its limit")
show(pd.DataFrame(nsets), title="Netting sets by member")
chart(members, kind="bar", x="member", y="peak PFE", title="Peak PFE by member")
