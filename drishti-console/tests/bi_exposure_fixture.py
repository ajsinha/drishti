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

"""The published official exposure of a BI test lake (risk-results/exposure-official-cpty), as the server's ExposurePublisher writes it
(docs/guides/EXPOSURE_GUIDE.md section 20): the BI samples' exposure measures read that table since 2.9.0, and a test lake made by
tools/packgen/banking/make_data.py --lake has netting sets but no exposure paths for the server to compute from. Each netting set of the
lake's last business date gets one row, and each counterparty one row of its own; the figures are illustrative (from the set's net MTM),
deterministic, and only for tests that compare BI answers with each other.

    python bi_exposure_fixture.py <lake>        (run with deltalake and pyarrow, as make_lake does)
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

STRINGS = ("HIERARCHY", "NODE_ID", "NODE_NAME", "PARENT_ID", "LEVEL", "COUNTERPARTY", "LEGAL_ENTITY")
DOUBLES = ("CE", "EPE", "EEPE", "PEAK_EE", "PEAK_PFE_95", "PEAK_PFE_99", "EAD_IMM", "PFE_95_1Y", "PFE_95_5Y", "EE_1Y", "EE_5Y")
LONGS = ("RESULT_VERSION", "PATHS", "NETTING_SETS", "TRADES", "FAILED_TRADES", "PARTIAL_SETS")
OTHER = ("INPUT_VERSIONS", "COMPUTED_AT", "CONTRACT_VERSION", "RUN_ID", "CALIBRATION", "PEAK_PFE_DATE", "PROFILE")


def netting_sets(lake: Path) -> tuple[str, list[dict]]:
    """The lake's netting sets on its last business date: (date, documents)."""
    from deltalake import DeltaTable
    table = next(p for p in sorted(lake.glob("*/netting-set")) if (p / "_delta_log").is_dir())
    rows = DeltaTable(str(table)).to_pyarrow_table().to_pylist()
    dates = sorted({str(r.get("business_date") or r.get("BUSINESS_DATE") or "")[:10] for r in rows} - {""})
    last = dates[-1]
    docs = {}
    for r in rows:
        if str(r.get("business_date") or r.get("BUSINESS_DATE") or "")[:10] == last:
            d = json.loads(r["doc"]) if isinstance(r.get("doc"), str) else r
            docs[d.get("NETTING_SET_ID") or r.get("id")] = d
    return last, [docs[k] for k in sorted(docs)]


def row(date: str, hierarchy: str, node: str, name: str, parent: str, level: str, cp: str, le: str, ee: float, sets: int, trades: int) -> dict:
    r = {"HIERARCHY": hierarchy, "NODE_ID": node, "NODE_NAME": name, "PARENT_ID": parent, "LEVEL": level, "COUNTERPARTY": cp, "LEGAL_ENTITY": le,
         "RESULT_VERSION": 1, "INPUT_VERSIONS": "{}", "COMPUTED_AT": f"{date}T23:59:00Z", "CONTRACT_VERSION": "1.2", "RUN_ID": f"XR-{date}-01",
         "PATHS": 200, "CALIBRATION": "REAL_WORLD", "CE": float(round(ee * 0.4)), "EPE": float(round(ee * 0.7)), "EEPE": float(round(ee * 0.8)), "PEAK_EE": float(round(ee)),
         "PEAK_PFE_95": float(round(ee * 2.4)), "PEAK_PFE_99": float(round(ee * 3.1)), "EAD_IMM": float(round(ee * 1.12)), "PEAK_PFE_DATE": date,
         "PFE_95_1Y": float(round(ee * 2.2)), "PFE_95_5Y": float(round(ee * 1.1)), "EE_1Y": float(round(ee * 0.9)), "EE_5Y": float(round(ee * 0.45)), "PROFILE": "[]",
         "NETTING_SETS": sets, "TRADES": trades, "FAILED_TRADES": 0, "PARTIAL_SETS": 0}
    r["business_date"] = date
    r["id"] = f"{hierarchy}:{node}@{date}#v1"
    r["doc"] = json.dumps({k: v for k, v in r.items() if k not in ("id", "doc", "business_date")} | {"BUSINESS_DATE": date})
    return r


def build(lake: Path) -> int:
    import pyarrow as pa
    from deltalake import write_deltalake
    date, sets = netting_sets(lake)
    rows, by_cp = [], {}
    for d in sets:
        ns, cp, le = d.get("NETTING_SET_ID"), d.get("COUNTERPARTY") or "", d.get("LEGAL_ENTITY") or ""
        ee = abs(float(d.get("NET_MTM") or 0)) * 0.25 + 1_000_000.0
        trades = int(d.get("TRADE_COUNT") or len(d.get("TRADES") or []))
        rows.append(row(date, "NSET", f"NSET:{ns}", ns, f"CPTY:{cp}", "netting-set", cp, le, ee, 1, trades))
        c = by_cp.setdefault(cp, [0.0, 0, 0])
        c[0], c[1], c[2] = c[0] + ee, c[1] + 1, c[2] + trades
    for cp, (ee, n, trades) in sorted(by_cp.items()):
        rows.append(row(date, "CPTY", f"CPTY:{cp}", cp, "", "counterparty", cp, "", ee * 0.85, n, trades))
    fields = [pa.field("id", pa.string()), pa.field("doc", pa.string()), pa.field("business_date", pa.date32())]
    fields += [pa.field(c, pa.string()) for c in STRINGS] + [pa.field(c, pa.int64()) for c in ("RESULT_VERSION",)]
    fields += [pa.field(c, pa.string()) for c in ("INPUT_VERSIONS", "COMPUTED_AT", "CONTRACT_VERSION", "RUN_ID")]
    fields += [pa.field("PATHS", pa.int64()), pa.field("CALIBRATION", pa.string())]
    fields += [pa.field(c, pa.float64()) for c in ("CE", "EPE", "EEPE", "PEAK_EE", "PEAK_PFE_95", "PEAK_PFE_99", "EAD_IMM")]
    fields += [pa.field("PEAK_PFE_DATE", pa.string())] + [pa.field(c, pa.float64()) for c in ("PFE_95_1Y", "PFE_95_5Y", "EE_1Y", "EE_5Y")]
    fields += [pa.field("PROFILE", pa.string())] + [pa.field(c, pa.int64()) for c in ("NETTING_SETS", "TRADES", "FAILED_TRADES", "PARTIAL_SETS")]
    schema = pa.schema(fields)
    from datetime import date as D
    data = {f.name: [(D.fromisoformat(r[f.name]) if f.name == "business_date" else r[f.name]) for r in rows] for f in schema}
    write_deltalake(str(lake / "risk-results" / "exposure-official-cpty"), pa.table(data, schema=schema), partition_by=["business_date"], mode="overwrite")
    return len(rows)


if __name__ == "__main__":
    print(f"{build(Path(sys.argv[1]))} exposure rows")
