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


"""The demo-desk API parity of the exposure what-if: the Python engine (before) against the Java engine (after), field by field.

    # 1. a server of origin/develop (2.9.11.a: the Python engine), demo lake with the exposure run, compute ON (DRISHTI_COMPUTE_ENABLED=true)
    python3 tools/ccr/whatif_api_parity.py record --repo <checkout> --server http://<lan-ip>:18480 --user rita --out before.json
    # 2. a server of wip/exposure-whatif-java (the Java engine), the same lake and date (compute may be off)
    python3 tools/ccr/whatif_api_parity.py check  --repo <checkout> --server http://<lan-ip>:18480 --user rita --against before.json

Both ask the same what-ifs (method paths and addon) on the demo's CP-ALDERSHOT, its netting sets and their trades (found through the API on
the first run and kept in the recording, so the second run asks exactly the same); check compares every number of every answer (the
rounded USD figures, the profile, the items) allowing 1 USD for a rounding at .5, and every text but engine and note. Exit 0 when equal.
Stdlib only; the server's API client is the repository's tools/drishti.py Api."""
import argparse
import json
import sys
from pathlib import Path

SKIP = {"engine", "note"}


def api_for(a):
    sys.path.insert(0, str(Path(a.repo) / "tools"))
    import drishti  # noqa: E402 (the repository's own client)
    return drishti.Api(a.server, None, a.user, timeout=300)


def plan(api, cp: str) -> list[dict]:
    """The what-ifs: from the counterparty's netting sets (largest first) and the trades of its two largest."""
    head = api.json("GET", "/api/v1/risk/exposure/scope?scope=CPTY:" + cp)
    sets = [s["nettingSet"] for s in sorted(head.get("byNettingSet") or [], key=lambda s: -(s.get("peakPfe") or 0))]
    if not sets:
        raise SystemExit(f"CPTY:{cp} has no netting sets on this lake")
    trades = {}
    for s in sets[:2]:
        rows = api.json("GET", "/api/v1/risk/exposure/scope/contributions?scope=NSET:" + s + "&by=trade&figure=EE").get("rows") or []
        trades[s] = [r["key"] for r in rows if r.get("key")][:3]
    s0, s1 = sets[0], sets[1] if len(sets) > 1 else sets[0]
    t0, t1 = trades[s0][:2] if len(trades[s0]) > 1 else (trades[s0] * 2)[:2]
    u0 = (trades.get(s1) or trades[s0])[0]

    def c(tid, f, ns, label):
        out = {"tradeId": label, "label": label, "paths": {"copyOf": tid, "factor": f}}
        if ns:
            out["nettingSet"] = ns
        return out

    cpty = "CPTY:" + cp
    return [
        {"scope": cpty, "method": "paths", "q": 0.95, "candidates": [c(t0, -1, s0, "unwind-" + t0)]},
        {"scope": cpty, "method": "paths", "q": 0.95, "collateral": "current", "candidates": [c(t0, -1, s0, "unwind-" + t0)]},
        {"scope": cpty, "method": "paths", "q": 0.99, "collateral": "none", "candidates": [c(t1, 2, s0, "double-" + t1)]},
        {"scope": "NSET:" + s0, "method": "paths", "q": 0.95, "candidates": [c(t0, 0.5, s0, "half-" + t0), c(t1, 1, "", "own-" + t1)]},
        {"scope": cpty, "method": "paths", "q": 0.95, "candidates": [c(u0, 1, s1, "again-" + u0), c(t0, -1, s0, "unwind-" + t0),
                                                                    c(t1, -0.25, "", "own-" + t1)]},
        {"scope": cpty, "method": "addon", "q": 0.95, "candidates": [{"tradeId": "ADDON-1", "assetClass": "Rates", "notional": 50_000_000,
                                                                      "maturityDate": "2031-09-30", "mtm": 250_000, "delta": 1}]},
    ]


def ask(api, asof, body):
    path = "/api/v1/risk/exposure/what-if" + (f"?asOf={asof}" if asof else "")
    try:
        return {"ok": api.json("POST", path, body)}
    except Exception as e:  # an error answer is part of the record: the same refusal must come again
        return {"error": str(e)}


def diff(a, b, at="") -> list[str]:
    out = []
    if isinstance(a, dict) and isinstance(b, dict):
        for k in sorted(set(a) | set(b)):
            if k in SKIP:
                continue
            if k not in a or k not in b:
                out.append(f"{at}.{k}: only in {'after' if k in b else 'before'}")
            else:
                out += diff(a[k], b[k], f"{at}.{k}")
    elif isinstance(a, list) and isinstance(b, list):
        if len(a) != len(b):
            out.append(f"{at}: {len(a)} against {len(b)} entries")
        for i, (x, y) in enumerate(zip(a, b)):
            out += diff(x, y, f"{at}[{i}]")
    elif isinstance(a, (int, float)) and isinstance(b, (int, float)) and not isinstance(a, bool):
        if abs(a - b) > 1.0 + 1e-9:
            out.append(f"{at}: {a} against {b}")
    elif a != b:
        out.append(f"{at}: {a!r} against {b!r}")
    return out


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("what", choices=["record", "check"])
    p.add_argument("--repo", required=True)
    p.add_argument("--server", required=True)
    p.add_argument("--user", default="rita")
    p.add_argument("--cp", default="CP-ALDERSHOT")
    p.add_argument("--as-of", default="")
    p.add_argument("--out")
    p.add_argument("--against")
    a = p.parse_args()
    api = api_for(a)
    if a.what == "record":
        asks = plan(api, a.cp)
        rec = {"server": a.server, "cp": a.cp, "asOf": a.as_of, "asks": [{"body": b, "answer": ask(api, a.as_of, b)} for b in asks]}
        Path(a.out).write_text(json.dumps(rec, indent=1))
        bad = [x for x in rec["asks"] if "error" in x["answer"]]
        print(f"recorded {len(asks)} what-ifs to {a.out}; {len(bad)} answered an error" + "".join(f"\n  {x['answer']['error']}" for x in bad))
        return 0
    rec = json.loads(Path(a.against).read_text())
    fails = 0
    for i, x in enumerate(rec["asks"]):
        now = ask(api, rec.get("asOf") or a.as_of, x["body"])
        d = diff(x["answer"], now, f"ask {i + 1} ({x['body']['method']}, {x['body']['scope']})")
        fails += bool(d)
        print(("EQUAL " if not d else "DIFFERENT ") + f"ask {i + 1}: {x['body']['method']} on {x['body']['scope']}, "
              f"{len(x['body']['candidates'])} candidate(s)" + "".join("\n    " + s for s in d[:20]))
    print(f"{len(rec['asks']) - fails} of {len(rec['asks'])} what-ifs equal")
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
