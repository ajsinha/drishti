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

"""Proves the VaR engine's numbers: recomputes, in Python, the VaR and ES the engine should show after the morning run and every
intraday batch, from the JSONL files themselves, and compares them with the server's answer.

    python tools/pvgen/check.py --morning DIR [--intraday DIR_OR_FILE ...] [--date 2026-10-27] [--server http://host:port]
                                [--token-file F] [--scope BANK --scope DESK:DESK-RATES ...] [--json]

``--morning`` is the folder tools/pvgen/morning.py wrote (scenario-pv, pnl-vector and fx-scenario JSONL); ``--intraday`` the folders or
files of the intraday batches (tools/pvgen/intraday.py, or publish_kafka.py ``--save``). Each live trade's USD P&L is its P&L vector as
sent, or its scenario PVs through the engine's own formula (``scenario_fx.engine_pnl``: PV(s) × FX(s) − PV(base) × FX(base), with the
supplied scenario FX); the latest intraday row per trade by ``COMPUTED_AT`` replaces it (one before the morning run ignored), a cancel
removes it. Then VaR 99% (the 6th worst of 509) and ES 97.5% of the bank and of each desk (or the ``--scope``s), next to the server's
``GET /api/v1/var/scope`` for the date. Without ``--server`` it prints the expected figures only. Exit 1 when a figure differs by more
than one unit (the engine rounds to whole units). Guide: docs/guides/VAR_GUIDE.md section 15.12."""
from __future__ import annotations

import argparse
import json
import sys
import urllib.parse
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import common as C  # noqa: E402


def files(paths: list[str]) -> list[Path]:
    out = []
    for p in paths:
        q = Path(p)
        out += sorted(x for x in q.rglob("*.jsonl*")) if q.is_dir() else [q]
    return out


def load_morning(folder: Path) -> dict[str, list[dict]]:
    rows: dict[str, list[dict]] = {}
    for f in files([str(folder)]):
        r = C.read_jsonl(f)
        if r:
            rows.setdefault(C.kind_of(r), []).extend(r)
    return rows


def server_figures(server: str, token: str | None, scope: str, day: str) -> dict:
    q = urllib.parse.urlencode({"scope": scope, "asOf": day})
    req = urllib.request.Request(f"{server.rstrip('/')}/api/v1/var/scope?{q}", headers={"Accept": "application/json",
                                                                                       **({"Authorization": "Bearer " + token} if token else {})})
    with urllib.request.urlopen(req, timeout=120) as r:
        return json.loads(r.read())


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0], epilog=__doc__.split("\n\n", 1)[1],
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--morning", required=True, help="the morning run's folder (tools/pvgen/morning.py)")
    p.add_argument("--intraday", action="append", default=[], help="an intraday batch file or folder; repeatable")
    p.add_argument("--date", help="the business date (default: the morning rows')")
    p.add_argument("--server", help="the Drishti server to compare with (http://host:port)")
    p.add_argument("--token-file", help="the API token for --server (default DRISHTI_TOKEN)")
    p.add_argument("--scope", action="append", default=[], help="a scope to check (default: BANK and every desk)")
    p.add_argument("--json", action="store_true")
    a = p.parse_args(argv)
    morning = load_morning(Path(a.morning))
    day = a.date or str((morning.get("scenario-pv") or morning.get("pnl-vector"))[0]["BUSINESS_DATE"])[:10]
    intraday = [r for f in files(a.intraday) for r in C.read_jsonl(f)]
    exp = C.expected(morning, intraday, day)
    scopes = a.scope or ["BANK"] + sorted(s for s in exp if s.startswith("DESK:"))
    token = C.token_of(a.token_file)
    rows, bad = [], 0
    for s in scopes:
        v = exp.get(s)
        var, es = C.measures(v) if v else (0.0, 0.0)
        row = {"scope": s, "expectedVar": round(var), "expectedEs": round(es)}
        if a.server:
            got = server_figures(a.server, token, s, day)
            row.update(serverVar=got.get("var"), serverEs=got.get("es"), intraday=(got.get("intraday") or {}).get("updates"))
            ok = abs(row["serverVar"] - var) <= 1 and abs(row["serverEs"] - es) <= 1
            row["ok"] = ok
            bad += 0 if ok else 1
        rows.append(row)
    if a.json:
        print(json.dumps({"businessDate": day, "intradayRows": len(intraday), "rows": rows}, indent=1))
    else:
        print(f"{day}: the morning run ({sum(len(v) for k, v in morning.items() if k != 'fx-scenario'):,} trades) and {len(intraday):,} intraday rows")
        for r in rows:
            line = f"  {r['scope']:<24} VaR {r['expectedVar']:>16,}  ES {r['expectedEs']:>16,}"
            if a.server:
                line += f"   server VaR {r['serverVar']:>16,}  ES {r['serverEs']:>16,}  {'ok' if r['ok'] else 'DIFFERENT'}"
                line += f"  (intraday updates {r['intraday']})" if r.get("intraday") else ""
            print(line)
        if a.server:
            print("every figure agrees" if not bad else f"{bad} scope(s) differ")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
