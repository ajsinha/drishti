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

"""A stand-in server holding market data with known problems, shaped as the server answers (search rows of ``$.FIELD`` values, raw
documents with provenance, one field's history with each point's data date), for Drishti Market's landings (test_market_landing.py,
test_edition.py, test_edition_market_browser.py). The business date is 2026-09-30 (FakeBackend's). Its problems, one of each check:

* stale: CRV-GBP-OIS's AS_OF is 2026-09-24 (four business days old); the latest INF loaded is of 2026-09-25 (three);
* gap: CRV-AUD-PROJ has no 15Y point, which the two other projection curves have;
* forwards: CRV-EUR-OIS's discount factor rises at 30Y (critical) and its 10Y forward is negative (warning);
* surfaces: FXV-USDTRY's ATM total variance falls from 3M to 6M (critical); EQV-ZERO has a vol of zero (critical).

History: every curve's TEN_Y has 21 real business days, CRV-EUR-OIS jumping on the last; the EUR/USD vol surface holds the business date
only (so its tile shows no change); the CDS spreads hold both days."""
from __future__ import annotations

from datetime import date, timedelta

from conftest import FakeBackend
from core.backend import BackendError

BUSINESS = date(2026, 9, 30)
TENORS = ["1M", "3M", "6M", "1Y", "2Y", "3Y", "5Y", "7Y", "10Y", "15Y", "20Y", "30Y"]
YEARS = [1 / 12, 0.25, 0.5, 1, 2, 3, 5, 7, 10, 15, 20, 30]


def bdays(n: int, end: date = BUSINESS) -> list[date]:
    out, d = [], end
    while len(out) < n:
        if d.weekday() < 5:
            out.append(d)
        d -= timedelta(days=1)
    return out[::-1]


def curve(cid: str, ctype: str, base: float, as_of: str = "2026-09-30", drop: str | None = None, df_rise: bool = False,
          neg_fwd: bool = False) -> dict:
    pts = []
    for t, y in zip(TENORS, YEARS):
        if t == drop:
            continue
        r = base + 0.04 * y ** 0.5
        df = round((1 + r / 100) ** -y, 6)
        if df_rise and t == "30Y":
            df = 0.70                                   # higher than the 20Y's: a broken curve
        pts.append({"TENOR": t, "ZERO_RATE": round(r, 4), "DF": df})
    fwd = [{"TENOR": t, "RATE": round(base + 0.1 * i, 4)} for i, t in enumerate(["1Y", "2Y", "3Y", "5Y", "7Y", "10Y"])]
    if neg_fwd:
        fwd[5]["RATE"] = -0.12
    return {"CURVE_ID": cid, "CURRENCY": cid.split("-")[1], "CURVE_TYPE": ctype, "TEN_Y": round((base + 0.04 * 10 ** 0.5) / 100, 6),
            "SLOPE_2S_10S": 46.0, "AS_OF": as_of, "INDEX": "SOFR" if cid == "CRV-USD-OIS" else "", "POINTS": pts, "FORWARDS": fwd}


CURVES = {c["CURVE_ID"]: c for c in [
    curve("CRV-USD-OIS", "Discount (OIS)", 3.80), curve("CRV-EUR-OIS", "Discount (OIS)", 2.10, df_rise=True, neg_fwd=True),
    curve("CRV-GBP-OIS", "Discount (OIS)", 3.90, as_of="2026-09-24"),
    curve("CRV-USD-PROJ", "Projection", 3.95), curve("CRV-EUR-PROJ", "Projection", 2.20), curve("CRV-AUD-PROJ", "Projection", 3.60, drop="15Y")]}
FXV = {"FXV-EURUSD": {"SURFACE_ID": "FXV-EURUSD", "PAIR": "EURUSD", "ATM_1M": 7.8, "GRID": [
           {"EXPIRY": "1M", "P_25": 8.1, "ATM": 7.8, "C_25": 7.9, "RR_25": -0.3}, {"EXPIRY": "3M", "P_25": 8.2, "ATM": 7.9, "C_25": 8.0, "RR_25": -0.3},
           {"EXPIRY": "6M", "P_25": 8.4, "ATM": 8.1, "C_25": 8.2, "RR_25": -0.4}]},
       "FXV-USDTRY": {"SURFACE_ID": "FXV-USDTRY", "PAIR": "USDTRY", "ATM_1M": 20.0, "GRID": [
           {"EXPIRY": "1M", "ATM": 20.0, "RR_25": 2.0}, {"EXPIRY": "3M", "ATM": 19.0, "RR_25": 2.1}, {"EXPIRY": "6M", "ATM": 12.0, "RR_25": 2.2}]}}
EQV = {"EQV-ZERO": {"SURFACE_ID": "EQV-ZERO", "GRID": [{"EXPIRY": "1M", "M_90": 30.0, "M_100": 28.0}, {"EXPIRY": "3M", "M_90": 0.0, "M_100": 27.5}]},
       "EQV-FINE": {"SURFACE_ID": "EQV-FINE", "GRID": [{"EXPIRY": "1M", "M_90": 30.0, "M_100": 28.0}, {"EXPIRY": "1Y", "M_90": 29.0, "M_100": 27.0}]}}
CDS = {"CDS-A": 60.0, "CDS-B": 70.0, "CDS-C": 300.0}
CDS_BEFORE = {"CDS-A": 58.0, "CDS-B": 69.0, "CDS-C": 296.0}
KIND_OF = {"CRV": "ir-curve", "FXV": "fx-vol-surface", "EQV": "equity-vol-surface", "CDS": "credit-curve", "INF": "inflation-index"}
COUNTS = {"CRV": 28, "REPO": 7, "IRV": 7, "CPV": 7, "FIX": 7, "INF": 4, "INFC": 4, "FX": 12, "FXF": 12, "FXV": 12, "CDS": 16, "BND": 32,
          "EQ": 10, "EQX": 5, "DIV": 15, "EQV": 15, "CMD": 8, "CMDC": 8, "CMDV": 8, "CORR": 3}


def _rows(kind, items):
    return {"kind": kind, "matched": len(items), "scanned": len(items),
            "rows": [{"ref": {"kind": kind, "id": i}, "title": i, "values": {f"$.{k}": v for k, v in vals.items()}} for i, vals in items]}


def _ten_y(cid: str) -> list[float]:
    """21 business days of a curve's 10Y rate: a small wobble, and CRV-EUR-OIS jumping 30bp on the last day."""
    base = CURVES[cid]["TEN_Y"]
    vals = [base + (0.0001 if i % 2 else -0.0001) for i in range(21)]
    vals[-1] = base
    if cid == "CRV-EUR-OIS":
        vals[-1] = vals[-2] + 0.003
    if cid == "CRV-USD-OIS":
        vals[-2] = base + 0.0004                        # the tile: -4bp against the day before
    return vals


class MarketBackend(FakeBackend):
    """The pack's answers; ``history=False`` serves no history at all (every point carried from an earlier day)."""

    def __init__(self, history: bool = True):
        super().__init__()
        self.history = history
        self.asked: list[str] = []

    async def search(self, q, ident=None):
        self.asked.append(q)
        head = q.split()[0]
        if q.endswith(" limit 1") and " where " not in q and head in COUNTS and head not in ("CRV", "INF"):
            return {**_rows("x", []), "matched": COUNTS[head]}
        if head == "CRV":
            out = _rows("ir-curve", [(i, {"CURRENCY": c["CURRENCY"]}) for i, c in CURVES.items()])
            out["matched"] = 28 if q.endswith("limit 1") else len(CURVES)
            return out
        if head == "FXV":
            return _rows("fx-vol-surface", [(i, {}) for i in FXV])
        if head == "EQV":
            return _rows("equity-vol-surface", [(i, {}) for i in EQV])
        if head == "CDS":
            return _rows("credit-curve", [(i, {"SPREAD_5Y": s}) for i, s in sorted(CDS.items(), key=lambda x: x[1])])
        if head == "INF":
            return _rows("inflation-index", [("INF-UKRPI", {})])
        if head in COUNTS:
            return _rows("x", [])
        raise BackendError(404, "DRS-4023", f"{head} is not part of the edition")

    async def raw(self, kind, id_, ident=None):
        prov = {"source": "market-store", "businessDate": "2026-09-30"}
        if kind == "ir-curve" and id_ in CURVES:
            return {"ref": {"kind": kind, "id": id_}, "provenance": prov, "data": CURVES[id_]}
        if kind == "fx-vol-surface" and id_ in FXV:
            return {"ref": {"kind": kind, "id": id_}, "provenance": prov, "data": FXV[id_]}
        if kind == "equity-vol-surface" and id_ in EQV:
            return {"ref": {"kind": kind, "id": id_}, "provenance": prov, "data": EQV[id_]}
        if kind == "credit-curve" and id_ in CDS:
            return {"ref": {"kind": kind, "id": id_}, "provenance": prov, "data": {"CURVE_ID": id_, "SPREAD_5Y": CDS[id_],
                                                                                   "POINTS": [{"TENOR": "5Y", "SPREAD": CDS[id_]}]}}
        if kind == "inflation-index" and id_ == "INF-UKRPI":
            return {"ref": {"kind": kind, "id": id_}, "provenance": {**prov, "businessDate": "2026-09-25"}, "data": {"INDEX_ID": id_}}
        raise BackendError(404, "DRS-1001", f"no source holds {kind}/{id_}")

    async def series(self, kind, id_, path, days, ident=None):
        dates = bdays(days)
        vals: list = [None] * len(dates)
        real = [True] * len(dates)
        if kind == "ir-curve" and id_ in CURVES and path == "$.TEN_Y":
            v = _ten_y(id_)
            vals = v[-len(dates):] if len(dates) <= len(v) else [None] * (len(dates) - len(v)) + v
        elif kind == "ir-curve" and id_ in CURVES and path == "$.SLOPE_2S_10S":
            vals = [45.0] * (len(dates) - 1) + [46.0]
        elif kind == "ir-curve" and id_ in CURVES and path == "$.POINTS":
            ago = [{**p, "ZERO_RATE": round(p["ZERO_RATE"] + 0.05, 4)} for p in CURVES[id_]["POINTS"]]
            vals = [ago] * (len(dates) - 1) + [CURVES[id_]["POINTS"]]
        elif kind == "fx-vol-surface" and path == "$.ATM_1M":
            vals = [7.5] * len(dates)
            real = [False] * (len(dates) - 1) + [True]  # only the business date holds data: no change shown
        elif kind == "credit-curve" and id_ in CDS:
            vals = [CDS_BEFORE[id_]] * (len(dates) - 1) + [CDS[id_]]
        else:
            raise BackendError(404, "DRS-1001", f"no history of {kind}/{id_}")
        if not self.history:
            real = [False] * (len(dates) - 1) + [True]
        points = []
        for d, v, ok in zip(dates, vals, real):
            data_day = d if ok else date(2026, 7, 8)
            points.append({"date": d.isoformat(), "value": v, "dataDate": data_day.isoformat(), "source": "market-store"})
        return {"ref": {"kind": kind, "id": id_}, "path": path, "label": path, "dated": True, "points": points}

    async def command_history(self, ident=None):
        return ["CRV CRV-USD-OIS", "ECON CAN", "VAR BANK", "FXV FXV-EURUSD", "CRV CRV-USD-OIS", "TRD MX-20000001"]
