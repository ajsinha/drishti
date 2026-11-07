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


"""Records the Python exposure what-if engine's answers (drishti_compute.exposure.whatif, as of origin/develop 2.9.11.a) on a synthetic
book for the Java parity test (drishti-compute-banking/src/test/resources/ccr/whatif-parity.json). Runs the real engine's compute() over Arrow
inputs exactly as the reactive connector would give them. The engine is retired on this branch: run it from a checkout of 2.9.11.a,

    PYTHONPATH=<2.9.11.a checkout>/drishti-compute/src/main/python python3 tools/ccr/record_whatif_parity.py <out.json>"""
import datetime as dt
import json
import math
import os
import random
import sys
import tempfile

from drishti_compute import arrow as A
from drishti_compute.engine import Inputs, Progress, Request
from drishti_compute.exposure import measures as M
from drishti_compute.exposure.whatif import ExposureWhatIfEngine

OUT = sys.argv[1]
rnd = random.Random(20261103)
BD = dt.date(2026, 9, 30)
PRIM_DAYS = [7, 30, 91, 182, 273, 365, 547, 730, 1095, 1825]
TENORS = ["1W", "1M", "3M", "6M", "9M", "1Y", "18M", "2Y", "3Y", "5Y"]
# look-back 10 days before primaries 1..7 (the first and the last two have none: today's collateral)
days, roles, lbof = [], [], []
for i, d in enumerate(PRIM_DAYS):
    if 1 <= i <= 7:
        days.append(d - 10)
        roles.append("LOOKBACK")
        lbof.append(None)                              # filled below
    days.append(d)
    roles.append("PRIMARY")
    lbof.append(-1)
for k in range(len(days)):
    if roles[k] == "LOOKBACK":
        lbof[k] = k + 1
G = len(days)
P = 100
primary = [r == "PRIMARY" for r in roles]
lookback_for = [-1] * G
for k in range(G):
    if roles[k] == "LOOKBACK":
        lookback_for[lbof[k]] = k
grid = M.Grid(primary=primary, lookback_for=lookback_for)
times_all = [d / 365.0 for d in days]
times = [times_all[k] for k in grid.primaries]
dates = [(BD + dt.timedelta(days=days[k])).isoformat() for k in grid.primaries]
GP = len(times)


def walk(scale, drift, start=0.0):
    """G x P: a random walk per path over the grid's times, rounded to cents."""
    m = [0.0] * (G * P)
    for j in range(P):
        x, t0 = start, 0.0
        for k in range(G):
            dt_ = times_all[k] - t0
            x += drift * dt_ + scale * math.sqrt(dt_) * rnd.gauss(0, 1)
            t0 = times_all[k]
            m[k * P + j] = round(x, 2)
    return m


SETS = {
    "NS-A": dict(csa=dict(present=True, margined=True, th_them=50, th_us=30, mta_them=5, mta_us=5, ia=10, rounding=2.5, held_today=12,
                          im_held=8), v=walk(120, 10, 15)),
    "NS-B": dict(csa=dict(present=True, margined=False, th_them=0, th_us=0, mta_them=0, mta_us=0, ia=20, rounding=0, held_today=0,
                          im_held=0), v=walk(80, -5, 5)),
    "NS-C": dict(csa=dict(present=False, margined=False, th_them=0, th_us=0, mta_them=0, mta_us=0, ia=0, rounding=0, held_today=0,
                          im_held=0), v=walk(60, 3, 20)),
    "NS-D": dict(csa=dict(present=True, margined=True, th_them=0, th_us=0, mta_them=1, mta_us=1, ia=0, rounding=0, held_today=-15,
                          im_held=0), v=walk(150, 0, -10)),
}
for s in SETS.values():
    s["v0"] = round(sum(s["v"][:P]) / P * 0.9, 2)
    s["life"] = round(rnd.uniform(0.5, 6), 4)
OTHER = [round(x, 2) for x in walk(40, -400, 900)]                       # the scope's other exposure (pieces nobody joins), G x P
OTHER = [OTHER[k * P + j] for k in grid.primaries for j in range(P)]   # at the primary dates: GP x P
OTHER_CE = 7.5

CANDS = {
    "c1": dict(TRADE_ID="CAND-1", LABEL="IRS 10y payer", NETTING_SET="NS-A", MTM=walk(60, 2), MTM0=3.2, LIFE_YEARS=2.0),
    "c2": dict(TRADE_ID="CAND-2", LABEL="FX forward", NETTING_SET="NS-B", MTM=walk(40, 1, 2), MTM0=None, LIFE_YEARS=0.5),
    "c3": dict(TRADE_ID="CAND-3", LABEL="new counterparty", NETTING_SET="", MTM=walk(30, 4, -1), MTM0=-4.0, LIFE_YEARS=5.0),
    "c4": dict(TRADE_ID="CAND-4", LABEL="IRS 2y receiver", NETTING_SET="NS-A", MTM=walk(25, -3), MTM0=None, LIFE_YEARS=1.5),
    "c5": dict(TRADE_ID="CAND-5", LABEL="unwind of NS-C", NETTING_SET="NS-C", MTM=[-x for x in SETS["NS-C"]["v"]],
               MTM0=-SETS["NS-C"]["v0"], LIFE_YEARS=3.0),
    "c6": dict(TRADE_ID="CAND-6", LABEL="swaption", NETTING_SET="NS-D", MTM=walk(90, 0), MTM0=1.0, LIFE_YEARS=4.0),
}
SCENARIOS = [
    dict(name="lagged-kth-95", collateral="lagged", rule="kth", q=0.95, life=4.0, cands=["c1", "c2", "c3", "c4", "c6"]),
    dict(name="current-kth-95", collateral="current", rule="kth", q=0.95, life=4.0, cands=["c1", "c2", "c3", "c4", "c6"]),
    dict(name="none-kth-975", collateral="none", rule="kth", q=0.975, life=4.0, cands=["c1", "c3", "c6"]),
    dict(name="lagged-interp-99", collateral="lagged", rule="interpolated", q=0.99, life=4.0, cands=["c1", "c3"]),
    dict(name="lagged-unwind", collateral="lagged", rule="kth", q=0.95, life=4.0, cands=["c5"]),
    dict(name="short-horizon-90", collateral="lagged", rule="kth", q=0.9, life=0.4, cands=["c2", "c3"]),
]


def csa(d):
    return M.Csa(**d)


def run(sc, tmp):
    model = sc["collateral"]
    e = [max(x, 0.0) for x in OTHER]
    n = [min(x, 0.0) for x in OTHER]
    ce = OTHER_CE
    set_rows = []
    for sid, s in SETS.items():
        es, ns = M.exposure(s["v"], grid, P, csa(s["csa"]), model)
        e = [a + b for a, b in zip(e, es)]
        n = [a + b for a, b in zip(n, ns)]
        ces = M.current_exposure(s["v0"], csa(s["csa"]), model)
        ce += ces
        set_rows.append((sid, s, es, ns, ces))
    d = os.path.join(tmp, sc["name"])
    os.makedirs(d, exist_ok=True)
    prim = [1.0 if x else 0.0 for x in primary]
    lb = [float(x) for x in lookback_for]
    A.write_file(A.Table([A.utf8("SCOPE", ["CPTY:CP-PARITY"]), A.utf8("CANONICAL", ["CPTY:CP-PARITY"]), A.utf8("BUSINESS_DATE", [BD.isoformat()]),
                          A.utf8("RUN_ID", ["XR-PARITY"]), A.int64("PATHS", [P]), A.float64("CONFIDENCE", [sc["q"]]), A.utf8("RULE", [sc["rule"]]),
                          A.utf8("TENORS", [",".join(TENORS)]), A.utf8("DATES", [",".join(dates)]), A.float64("CE_SCOPE", [ce]),
                          A.float64("LIFE_YEARS", [sc["life"]]), A.utf8("WHATIF_SET_IDS", [",".join(SETS)]), A.vectors("E_SCOPE", len(e), e),
                          A.vectors("N_SCOPE", len(n), n), A.vectors("TIMES", GP, times), A.vectors("PRIMARY", G, prim),
                          A.vectors("LOOKBACK_FOR", G, lb)]), os.path.join(d, "base.arrows"))
    cols = {k: [] for k in ("NETTING_SET", "CSA_PRESENT", "MARGINED", "TH_THEM", "TH_US", "MTA_THEM", "MTA_US", "IA", "ROUNDING",
                            "HELD_TODAY", "IM_HELD", "V0", "CE_SET")}
    vs, ess, nss = [], [], []
    for sid, s, es, ns, ces in set_rows:
        c = s["csa"]
        cols["NETTING_SET"].append(sid)
        cols["CSA_PRESENT"].append(c["present"])
        cols["MARGINED"].append(c["margined"])
        for k in ("th_them", "th_us", "mta_them", "mta_us", "ia", "rounding", "held_today", "im_held"):
            cols[k.upper()].append(float(c[k]))
        cols["V0"].append(s["v0"])
        cols["CE_SET"].append(ces)
        vs += s["v"]
        ess += es
        nss += ns
    A.write_file(A.Table([A.utf8("NETTING_SET", cols["NETTING_SET"]), A.vectors("V", G * P, vs), A.vectors("E_SET", GP * P, ess),
                          A.vectors("N_SET", GP * P, nss), A.boolean("CSA_PRESENT", cols["CSA_PRESENT"]), A.boolean("MARGINED", cols["MARGINED"])]
                         + [A.float64(k, cols[k]) for k in ("TH_THEM", "TH_US", "MTA_THEM", "MTA_US", "IA", "ROUNDING", "HELD_TODAY", "IM_HELD",
                                                            "V0", "CE_SET")]), os.path.join(d, "sets.arrows"))
    cs = [CANDS[c] for c in sc["cands"]]
    A.write_file(A.Table([A.utf8("TRADE_ID", [c["TRADE_ID"] for c in cs]), A.utf8("LABEL", [c["LABEL"] for c in cs]),
                          A.utf8("NETTING_SET", [c["NETTING_SET"] for c in cs]), A.float64("MTM0", [c["MTM0"] for c in cs]),
                          A.float64("LIFE_YEARS", [c["LIFE_YEARS"] for c in cs]), A.utf8("RUN_ID", ["XR-PARITY"] * len(cs)),
                          A.vectors("MTM", G * P, [x for c in cs for x in c["MTM"]])]), os.path.join(d, "sandbox.arrows"))
    req = Request(id="req-" + sc["name"], kind="exposure-whatif", params={"q": sc["q"], "collateral": sc["collateral"]}, user="asha", roles=(),
                  view_signature="", cls="interactive", seed=0, scratch=d)
    r = ExposureWhatIfEngine().compute(req, Inputs(tmp, {"base": sc["name"] + "/base.arrows", "sets": sc["name"] + "/sets.arrows",
                                                          "sandbox": sc["name"] + "/sandbox.arrows"}), Progress(lambda x: None))
    return {"row": r.rows[0], "profile": r.tables["profile"].to_rows(), "items": r.tables["items"].to_rows()}


with tempfile.TemporaryDirectory() as tmp:
    for sc in SCENARIOS:
        sc["expected"] = run(sc, tmp)

fixture = {
    "about": "Answers of the Python exposure what-if engine (drishti_compute.exposure.whatif 1.0, retired in 2.9.11.g) on a synthetic book, "
             "recorded on 2026-11-03 from origin/develop 2.9.11.a; the Java ExposureWhatIfMath must give them again.",
    "businessDate": BD.isoformat(), "paths": P, "runId": "XR-PARITY",
    "grid": {"days": days, "roles": roles, "lookbackOf": lbof, "tenors": [TENORS[grid.primaries.index(k)] if primary[k] else None for k in range(G)]},
    "sets": {sid: {"csa": s["csa"], "v": s["v"], "v0": s["v0"]} for sid, s in SETS.items()},
    "other": OTHER, "otherCe": OTHER_CE,
    "candidates": CANDS,
    "scenarios": SCENARIOS,
}
with open(OUT, "w") as f:
    json.dump(fixture, f, separators=(",", ":"))
print("wrote", OUT, os.path.getsize(OUT), "bytes;", [(s["name"], round(s["expected"]["row"]["CHANGE_PEAK_PFE"], 2)) for s in SCENARIOS])
