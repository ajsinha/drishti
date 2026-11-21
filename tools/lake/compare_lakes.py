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

# Drishti's own acceptance check: two Delta lakes compared by delta-rs, an independent reader on purpose (Drishti Lake's parity kit
# compares stores through its own cases, not two lakes on disk).
"""compare_lakes: do two Delta lakes hold the same tables with the same row counts per business date (and per partition)?

    python3 tools/lake/compare_lakes.py LAKE_A LAKE_B [--out result.json]

Reads each table's Delta log (the rows of its live data files by partition values, from the files' own statistics: no data is read), so
it is fast on a large lake. Exit 0 when the tables, their partitions and their row counts agree, 1 when not (every difference is
printed). Needs deltalake.
"""
from __future__ import annotations

import argparse
import json
import pathlib
import sys


def counts(lake: pathlib.Path) -> dict[str, dict[str, int]]:
    """{table: {partition: rows}} of every Delta table under the lake."""
    from deltalake import DeltaTable
    out: dict[str, dict[str, int]] = {}
    for log in sorted(lake.glob("*/*/_delta_log")):
        table = log.parent
        rows: dict[str, int] = {}
        import pyarrow as pa
        for a in pa.table(DeltaTable(str(table)).get_add_actions(flatten=True)).to_pylist():
            part = ",".join(f"{k[len('partition.'):]}={a[k]}" for k in sorted(a) if k.startswith("partition."))
            rows[part] = rows.get(part, 0) + int(a.get("num_records") or 0)
        out[f"{table.parent.name}/{table.name}"] = dict(sorted(rows.items()))
    return out


def compare(a: pathlib.Path, b: pathlib.Path) -> tuple[list[str], dict]:
    ca, cb = counts(a), counts(b)
    diffs: list[str] = []
    for t in sorted(set(ca) | set(cb)):
        if t not in ca or t not in cb:
            diffs.append(f"{t}: only in {'B' if t not in ca else 'A'}")
            continue
        for p in sorted(set(ca[t]) | set(cb[t])):
            if ca[t].get(p) != cb[t].get(p):
                diffs.append(f"{t} [{p}]: A {ca[t].get(p)} rows, B {cb[t].get(p)} rows")
    summary = {"tables": {"A": len(ca), "B": len(cb)}, "partitions": {"A": sum(map(len, ca.values())), "B": sum(map(len, cb.values()))},
               "rows": {"A": sum(sum(v.values()) for v in ca.values()), "B": sum(sum(v.values()) for v in cb.values())},
               "differences": diffs,
               "perTable": {t: {"A": sum(ca.get(t, {}).values()), "B": sum(cb.get(t, {}).values())} for t in sorted(set(ca) | set(cb))}}
    return diffs, summary


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("a")
    ap.add_argument("b")
    ap.add_argument("--out")
    x = ap.parse_args(argv)
    diffs, s = compare(pathlib.Path(x.a), pathlib.Path(x.b))
    if x.out:
        pathlib.Path(x.out).write_text(json.dumps(s, indent=1), encoding="utf-8")
    print(f"{s['tables']['A']} / {s['tables']['B']} tables, {s['partitions']['A']} / {s['partitions']['B']} partitions, "
          f"{s['rows']['A']:,} / {s['rows']['B']:,} rows: " + ("identical" if not diffs else f"{len(diffs)} differences"))
    print("\n".join(diffs[:50]))
    return 1 if diffs else 0


if __name__ == "__main__":
    sys.exit(main())
