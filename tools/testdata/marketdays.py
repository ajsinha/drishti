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

"""The market documents of each test-data day, with their quoted fields moving together (QA2-201).

A generic walk moved one headline number of a document back day by day and left the rest where the last day had it: an FX MID outside
its BID/ASK, a bond PRICE moving while its YIELD, Z_SPREAD and MOD_DURATION never changed, an equity PRICE that was not the close of its
own OHLC bar, a curve's FRONT that was not its first point. Here a market document of a day is built from the last day's document as one
picture:

    equity, commodity     the day's bar of the document's own OHLC series (extended back before its first bar by a walk with the
                          series' own volatility) is the PRICE / FRONT; OHLC and HISTORY are the trailing windows ending on the day
                          (no bar after it); an equity's MARKET_CAP moves with its price
    equity-index          LEVEL is the day's HISTORY close; HISTORY the trailing window
    fx-spot               MID is the day's HISTORY mid; BID and ASK the last day's spread around it
    bond                  PRICE is the day's HISTORY price; YIELD moves against it by the modified duration (dy = -dP/P / D); Z_SPREAD
                          takes the credit share of the yield move (z / (z + 100 bp)); MOD_DURATION follows the yield (convexity) and the
                          bond's age (longer the earlier the day)
    rate-fixing           FIXINGS the index's trailing fixings ending on the day (its own calendar), LATEST the newest, RATE_PCT = RATE x 100
    ir-curve, repo-curve  one shift and twist per currency and day (every curve of a currency moves together): ZERO_RATE, QUOTE, DF,
                          FORWARDS, TEN_Y, SLOPE_2S_10S and OVERNIGHT move by it; BUILT_AT is the day's
    commodity-curve       every point scaled by the commodity's FRONT of the day (shape kept); FRONT is the first point, ROLL recomputed
    fx-forward-curve      SPOT is the pair's MID of the day; OUTRIGHT and PIPS scale with it; POINTS_3M and POINTS_1Y are the tenors' pips

Credit curves, vol surfaces and cubes, dividend, inflation and correlation documents do not move day to day (their headline is their own
grid's point on every day). The last day is the universe as built, unchanged. ``contradictions`` names what a document's quoted fields
say against one another (and against the day before): the guard test runs it over every committed day.
"""
from __future__ import annotations

import copy
import hashlib
import math
import random
import statistics
import sys
from datetime import date
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
for sub in ("tools", "tools/packgen/banking"):
    if str(ROOT / sub) not in sys.path:
        sys.path.insert(0, str(ROOT / sub))
from samplegen.dates import Calendar, tenor_years  # noqa: E402

EXT = 70                                   # business days before the first test day a series may be asked for (an OHLC window of 60)
USNY = Calendar.of("USNY")
IR_VOL, TWIST_VOL = 0.03, 0.015            # per day, in percentage points: a 3 bp parallel move and a 1.5 bp 2s10s twist


def _seed(*parts) -> random.Random:
    return random.Random(int(hashlib.sha256("/".join(map(str, parts)).encode()).hexdigest()[:12], 16))


def _dp(values) -> int:
    """The most decimal places among the values (at most 6)."""
    best = 0
    for v in values:
        s = repr(float(v))
        if "e" not in s and "." in s:
            best = max(best, len(s.split(".")[1].rstrip("0")))
    return min(best, 6)


def _vol(values) -> float:
    rets = [b / a - 1 for a, b in zip(values, values[1:]) if a]
    return max(statistics.pstdev(rets) if len(rets) > 1 else 0.0, 1e-4)


def _upto(points: list[dict], day: str, n: int) -> list[dict]:
    return [p for p in points if p["DATE"] <= day][-n:]


def _calendar_of(currency: str | None) -> Calendar:
    try:
        import data_names as N
        return Calendar.of(N.CCY[currency][2])
    except (ImportError, KeyError, TypeError):
        return USNY


class MarketDays:
    """``day(kind, id, i)``: the document ``id`` of ``kind`` on test day i (-1: the business day before the first), coherent."""

    def __init__(self, docs: dict[str, dict[str, dict]], dates: list[str]):
        self.docs, self.dates = docs, dates
        d, before = date.fromisoformat(dates[0]), []
        for _ in range(EXT):
            d = USNY.add_business_days(d, -1)
            before.append(d.isoformat())
        self.ext = sorted(before) + list(dates)
        self.last = len(self.ext) - 1
        self._cache: dict = {}

    def handles(self, kind: str) -> bool:
        return kind in HANDLERS

    def day(self, kind: str, id_: str, i: int) -> dict:
        built = self.docs[kind][id_]
        if i == len(self.dates) - 1:
            return copy.deepcopy(built)
        doc = copy.deepcopy(built)
        HANDLERS[kind](self, id_, doc, EXT + i)
        return doc

    # ---- series ---------------------------------------------------------------------------------------------------------------------
    def _line(self, kind: str, id_: str, points: list[dict], field: str, dates: list[str] | None = None) -> list[dict]:
        """The built series ({DATE, field}, ascending) with points before its first walked back on ``dates`` (default the USNY days)."""
        key = (kind, id_, "line", field)
        if key not in self._cache:
            pts = sorted(points, key=lambda p: p["DATE"])
            vals = [p[field] for p in pts]
            dp, vol, rnd = _dp(vals), _vol(vals), _seed(kind, id_, "before", field)
            earlier = [d for d in (dates or self.ext) if d < pts[0]["DATE"]]
            v, out = float(vals[0]), []
            for d in reversed(earlier):
                v /= 1 + rnd.gauss(0, vol)
                out.append({"DATE": d, field: round(v, dp)})
            self._cache[key] = list(reversed(out)) + [dict(p) for p in pts]
        return self._cache[key]

    def _bars(self, kind: str, id_: str, bars: list[dict]) -> list[dict]:
        """The built OHLC bars with bars before the first: the closes walked back, an open near the previous close, a high and low."""
        key = (kind, id_, "bars")
        if key not in self._cache:
            closes = self._line(kind, id_, [{"DATE": b["DATE"], "CLOSE": b["CLOSE"]} for b in bars], "CLOSE")
            first, dp = bars[0]["DATE"], _dp(b["CLOSE"] for b in bars)
            vol, volume = _vol([b["CLOSE"] for b in bars]), statistics.mean(b.get("VOLUME") or 0 for b in bars)
            rnd, out, prev = _seed(kind, id_, "bars"), [], None
            for p in closes:
                if p["DATE"] >= first:
                    break
                c = p["CLOSE"]
                o = c * (1 + rnd.gauss(0, vol * 0.6)) if prev is None else prev * (1 + rnd.gauss(0, vol * 0.25))
                hi, lo = max(o, c) * (1 + abs(rnd.gauss(0, vol * 0.5))), min(o, c) * (1 - abs(rnd.gauss(0, vol * 0.5)))
                out.append({"DATE": p["DATE"], "OPEN": round(o, dp), "HIGH": round(hi, dp), "LOW": round(lo, dp), "CLOSE": c,
                            "VOLUME": int(volume * rnd.uniform(0.5, 1.8))})
                prev = c
            self._cache[key] = out + [dict(b) for b in bars]
        return self._cache[key]

    def _rates_shift(self, currency: str, j: int) -> tuple[float, float]:
        """(parallel shift, 2s10s twist) in percentage points of the currency's curves on ext day j against the last day."""
        key = ("ir", currency)
        if key not in self._cache:
            rnd, s, w, out = _seed("ir", currency), 0.0, 0.0, [(0.0, 0.0)]
            for _ in range(self.last):
                s += rnd.gauss(0, IR_VOL)
                w += rnd.gauss(0, TWIST_VOL)
                out.append((s, w))
            self._cache[key] = list(reversed(out))            # index j: j days into ext; the last is (0, 0)
        return self._cache[key][j]

    def _value(self, kind: str, id_: str, j: int) -> float | None:
        """The headline of another document on ext day j (a curve's commodity FRONT, a forward curve's FX MID)."""
        doc = self.docs.get(kind, {}).get(id_)
        if not doc:
            return None
        day = self.ext[j]
        if kind == "commodity":
            return _upto(self._bars(kind, id_, doc["OHLC"]), day, 1)[-1]["CLOSE"]
        if kind == "fx-spot":
            return _upto(self._line(kind, id_, doc["HISTORY"], "MID"), day, 1)[-1]["MID"]
        return None


# ---- one handler per kind: (market, id, the document to change in place, ext day index) ---------------------------------------------------
def _equity(m: MarketDays, id_: str, doc: dict, j: int) -> None:
    bars = _upto(m._bars("equity", id_, doc["OHLC"]), m.ext[j], len(doc["OHLC"]))
    p0 = doc["PRICE"]
    if doc.get("HISTORY"):
        doc["HISTORY"] = [{"DATE": b["DATE"], "CLOSE": b["CLOSE"]} for b in bars[-len(doc["HISTORY"]):]]
    doc["OHLC"], doc["PRICE"] = bars, bars[-1]["CLOSE"]
    if isinstance(doc.get("MARKET_CAP"), (int, float)) and p0:
        doc["MARKET_CAP"] = round(doc["MARKET_CAP"] * doc["PRICE"] / p0)


def _commodity(m: MarketDays, id_: str, doc: dict, j: int) -> None:
    bars = _upto(m._bars("commodity", id_, doc["OHLC"]), m.ext[j], len(doc["OHLC"]))
    doc["OHLC"], doc["FRONT"] = bars, bars[-1]["CLOSE"]


def _line_headline(kind: str, head: str, field: str):
    def handler(m: MarketDays, id_: str, doc: dict, j: int) -> None:
        pts = _upto(m._line(kind, id_, doc["HISTORY"], field), m.ext[j], len(doc["HISTORY"]))
        doc["HISTORY"], doc[head] = pts, pts[-1][field]
    return handler


def _fx_spot(m: MarketDays, id_: str, doc: dict, j: int) -> None:
    half = (doc["ASK"] - doc["BID"]) / 2
    _line_headline("fx-spot", "MID", "MID")(m, id_, doc, j)
    doc["BID"], doc["ASK"] = round(doc["MID"] - half, 6), round(doc["MID"] + half, 6)


def _bond(m: MarketDays, id_: str, doc: dict, j: int) -> None:
    p0, y0, z0, d0 = doc["PRICE"], doc["YIELD"], doc["Z_SPREAD"], doc["MOD_DURATION"]
    _line_headline("bond", "PRICE", "PRICE")(m, id_, doc, j)
    dy = -(doc["PRICE"] / p0 - 1) / d0                      # the yield move the price move says, at the modified duration
    doc["YIELD"] = round(y0 + dy, 6)
    doc["Z_SPREAD"] = round(max(0.1, z0 + z0 / (z0 + 100) * dy * 1e4), 1)
    doc["MOD_DURATION"] = round(d0 * (1 - 0.5 * d0 * dy) + 0.87 * (m.last - j) / 252, 2)


def _rate_fixing(m: MarketDays, id_: str, doc: dict, j: int) -> None:
    built = doc["FIXINGS"]
    cal, first = _calendar_of(doc.get("CURRENCY")), min(p["DATE"] for p in built)
    dates, d = [], date.fromisoformat(first)
    while d.isoformat() > m.ext[0]:
        d = cal.add_business_days(d, -1)
        dates.append(d.isoformat())
    line = m._line("rate-fixing", id_, built, "RATE", sorted(dates))
    volume = {p["DATE"]: p.get("VOLUME_BN") for p in built}
    rnd = _seed("rate-fixing", id_, "volume")
    vols = {p["DATE"]: volume.get(p["DATE"]) or round(rnd.uniform(50, 2400), 1) for p in line}
    pts = _upto(line, m.ext[j], len(built))
    doc["FIXINGS"] = [{"DATE": p["DATE"], "RATE": p["RATE"], "RATE_PCT": round(p["RATE"] * 100, 4), "VOLUME_BN": vols[p["DATE"]]}
                      for p in reversed(pts)]
    doc["LATEST"] = doc["FIXINGS"][0]["RATE"]


def _ir_curve(m: MarketDays, id_: str, doc: dict, j: int) -> None:
    s, w = m._rates_shift(doc.get("CURRENCY"), j)

    def dz(t: float) -> float:                               # percentage points at t years
        return s + w * (t - 2) / 8

    for p in doc.get("POINTS") or []:
        t = tenor_years(p["TENOR"])
        if isinstance(p.get("ZERO_RATE"), (int, float)):
            p["ZERO_RATE"] = round(p["ZERO_RATE"] + dz(t), 4)
        if isinstance(p.get("QUOTE"), (int, float)):
            p["QUOTE"] = round(p["QUOTE"] + dz(t) / 100, 6)
        if isinstance(p.get("DF"), (int, float)):
            p["DF"] = round(p["DF"] * math.exp(-dz(t) / 100 * t), 6)
    for f in doc.get("FORWARDS") or []:                      # the 1Y forward from t: d(z t)/dt moves by s + w (2t - 1) / 8
        t = tenor_years(f["TENOR"])
        f["RATE"] = round(f["RATE"] + s + w * (2 * t - 1) / 8, 4)
    if isinstance(doc.get("TEN_Y"), (int, float)):
        doc["TEN_Y"] = round(doc["TEN_Y"] + dz(10) / 100, 6)
    if isinstance(doc.get("SLOPE_2S_10S"), (int, float)):
        doc["SLOPE_2S_10S"] = round(doc["SLOPE_2S_10S"] + (dz(10) - dz(2)) * 100, 1)
    if isinstance(doc.get("BUILT_AT"), str) and len(doc["BUILT_AT"]) > 10:
        doc["BUILT_AT"] = m.ext[j] + doc["BUILT_AT"][10:]


def _repo_curve(m: MarketDays, id_: str, doc: dict, j: int) -> None:
    s, _ = m._rates_shift(doc.get("CURRENCY"), j)
    for p in doc.get("POINTS") or []:
        p["RATE"] = round(p["RATE"] + s, 4)
    if isinstance(doc.get("OVERNIGHT"), (int, float)):
        doc["OVERNIGHT"] = round(doc["OVERNIGHT"] + s / 100, 6)


def _commodity_curve(m: MarketDays, id_: str, doc: dict, j: int) -> None:
    front = m._value("commodity", doc.get("COMMODITY_REF"), j)
    pts = doc.get("POINTS") or []
    if front is None or not pts or not pts[0].get("PRICE"):
        return
    k = front / pts[0]["PRICE"]
    for p in pts:
        p["PRICE"] = round(p["PRICE"] * k, 3)
    doc["FRONT"] = pts[0]["PRICE"]
    if len(pts) > 1:
        doc["ROLL"] = round((pts[1]["PRICE"] / pts[0]["PRICE"] - 1) * 100, 2)


def _fx_forward_curve(m: MarketDays, id_: str, doc: dict, j: int) -> None:
    spot = m._value("fx-spot", doc.get("FX_SPOT"), j)
    if spot is None or not doc.get("SPOT"):
        return
    k = spot / doc["SPOT"]
    for p in doc.get("POINTS") or []:
        p["OUTRIGHT"] = round(p["OUTRIGHT"] * k, 5)
        p["PIPS"] = round(p["PIPS"] * k, 1)
    doc["SPOT"] = spot
    pips = {p["TENOR"]: p["PIPS"] for p in doc.get("POINTS") or []}
    for head, tenor in (("POINTS_3M", "3M"), ("POINTS_1Y", "1Y")):
        if head in doc and tenor in pips:
            doc[head] = pips[tenor]


HANDLERS = {"equity": _equity, "commodity": _commodity, "equity-index": _line_headline("equity-index", "LEVEL", "CLOSE"),
            "fx-spot": _fx_spot, "bond": _bond, "rate-fixing": _rate_fixing, "ir-curve": _ir_curve, "repo-curve": _repo_curve,
            "commodity-curve": _commodity_curve, "fx-forward-curve": _fx_forward_curve}


# ---- the guard ------------------------------------------------------------------------------------------------------------------------
def _close(a, b, tol: float) -> bool:
    return isinstance(a, (int, float)) and isinstance(b, (int, float)) and abs(a - b) <= tol


def _after(doc: dict, series: str, day: str | None) -> list[str]:
    late = [p["DATE"] for p in doc.get(series) or [] if day and isinstance(p, dict) and str(p.get("DATE", "")) > day]
    return [f"{series} has points after the day ({late[0]} > {day})"] if late else []


def _bars_ok(doc: dict) -> list[str]:
    bad = [b["DATE"] for b in doc.get("OHLC") or [] if not (b["LOW"] <= min(b["OPEN"], b["CLOSE"]) and b["HIGH"] >= max(b["OPEN"], b["CLOSE"]))]
    return [f"OHLC bar {bad[0]}: OPEN/CLOSE outside LOW..HIGH"] if bad else []


def contradictions(kind: str, doc: dict, before: dict | None = None, day: str | None = None) -> list[str]:
    """What the document's quoted fields say against one another, against the same document the business day before (``before``), and
    against its day (``day``: no series point after it). Empty when the document is one coherent picture."""
    out: list[str] = []
    last = lambda series: (doc.get(series) or [{}])[-1]          # noqa: E731
    if kind == "fx-spot":
        if not doc["BID"] <= doc["MID"] <= doc["ASK"]:
            out.append(f"MID {doc['MID']} outside BID {doc['BID']} / ASK {doc['ASK']}")
        if doc.get("HISTORY") and last("HISTORY").get("MID") != doc["MID"]:
            out.append(f"MID {doc['MID']} is not the HISTORY's last mid {last('HISTORY').get('MID')}")
        out += _after(doc, "HISTORY", day)
    elif kind == "bond":
        if doc.get("HISTORY") and last("HISTORY").get("PRICE") != doc["PRICE"]:
            out.append(f"PRICE {doc['PRICE']} is not the HISTORY's last price {last('HISTORY').get('PRICE')}")
        out += _after(doc, "HISTORY", day)
        if before:
            dp, dy, dz = doc["PRICE"] - before["PRICE"], doc["YIELD"] - before["YIELD"], doc["Z_SPREAD"] - before["Z_SPREAD"]
            if dp * dy > 0:
                out.append(f"PRICE and YIELD both {'up' if dp > 0 else 'down'} on the day ({before['PRICE']} -> {doc['PRICE']}, "
                           f"{before['YIELD']} -> {doc['YIELD']})")
            if abs(dp) / before["PRICE"] > 1e-4 and dy == 0:
                out.append(f"PRICE moved ({before['PRICE']} -> {doc['PRICE']}) and YIELD {doc['YIELD']} did not")
            if dz * dy < 0:
                out.append(f"Z_SPREAD moved against the YIELD ({before['Z_SPREAD']} -> {doc['Z_SPREAD']})")
    elif kind in ("equity", "commodity"):
        head = "PRICE" if kind == "equity" else "FRONT"
        if doc.get("OHLC") and last("OHLC").get("CLOSE") != doc[head]:
            out.append(f"{head} {doc[head]} is not the last OHLC close {last('OHLC').get('CLOSE')}")
        if doc.get("HISTORY") and last("HISTORY").get("CLOSE") != doc[head]:
            out.append(f"{head} {doc[head]} is not the HISTORY's last close {last('HISTORY').get('CLOSE')}")
        out += _bars_ok(doc) + _after(doc, "OHLC", day) + _after(doc, "HISTORY", day)
    elif kind == "equity-index":
        if doc.get("HISTORY") and last("HISTORY").get("CLOSE") != doc["LEVEL"]:
            out.append(f"LEVEL {doc['LEVEL']} is not the HISTORY's last close {last('HISTORY').get('CLOSE')}")
        out += _after(doc, "HISTORY", day)
    elif kind == "rate-fixing":
        fx = doc.get("FIXINGS") or []
        if fx and fx[0]["RATE"] != doc["LATEST"]:
            out.append(f"LATEST {doc['LATEST']} is not the newest fixing {fx[0]['RATE']}")
        out += [f"fixing {p['DATE']}: RATE_PCT {p['RATE_PCT']} is not RATE x 100" for p in fx if not _close(p["RATE"] * 100, p["RATE_PCT"], 1e-3)][:1]
        if [p["DATE"] for p in fx] != sorted((p["DATE"] for p in fx), reverse=True):
            out.append("FIXINGS not newest first")
        out += _after(doc, "FIXINGS", day)
    elif kind == "commodity-curve":
        pts = doc.get("POINTS") or []
        if pts and not _close(pts[0]["PRICE"], doc["FRONT"], 1e-3):
            out.append(f"FRONT {doc['FRONT']} is not the first point {pts[0]['PRICE']}")
    elif kind == "fx-forward-curve":
        pip = 100 if "JPY" in str(doc.get("PAIR")) else 10000
        for p in doc.get("POINTS") or []:
            if not _close(p["OUTRIGHT"], doc["SPOT"] + p["PIPS"] / pip, 0.2 / pip + 1e-5):
                out.append(f"{p['TENOR']}: OUTRIGHT {p['OUTRIGHT']} is not SPOT {doc['SPOT']} + {p['PIPS']} pips")
                break
    elif kind == "credit-curve":
        pts = {p["TENOR"]: p for p in doc.get("POINTS") or []}
        if "5Y" in pts and not _close(pts["5Y"]["SPREAD"], doc["SPREAD_5Y"], 0.51):
            out.append(f"SPREAD_5Y {doc['SPREAD_5Y']} is not the 5Y point {pts['5Y']['SPREAD']}")
        if "1Y" in pts and not _close(1 - pts["1Y"]["SURVIVAL"], doc["PD_1Y"], 2e-6):
            out.append(f"PD_1Y {doc['PD_1Y']} is not 1 - the 1Y survival")
    elif kind in ("ir-curve", "repo-curve") and before:
        now, then = {p["TENOR"]: p for p in doc.get("POINTS") or []}, {p["TENOR"]: p for p in before.get("POINTS") or []}
        if kind == "ir-curve" and "10Y" in now and "10Y" in then and isinstance(doc.get("TEN_Y"), (int, float)):
            if not _close((doc["TEN_Y"] - before["TEN_Y"]) * 100, now["10Y"]["ZERO_RATE"] - then["10Y"]["ZERO_RATE"], 3e-4):
                out.append("TEN_Y moved apart from the 10Y zero rate")
            for t, p in now.items():
                if t in then and p.get("DF") and then[t].get("DF"):
                    dz = (p["ZERO_RATE"] - then[t]["ZERO_RATE"]) / 100
                    if dz and (p["DF"] - then[t]["DF"]) * dz > 0:
                        out.append(f"{t}: DF and ZERO_RATE moved the same way")
                        break
        if kind == "repo-curve" and "ON" in now and "ON" in then and isinstance(doc.get("OVERNIGHT"), (int, float)):
            if not _close((doc["OVERNIGHT"] - before["OVERNIGHT"]) * 100, now["ON"]["RATE"] - then["ON"]["RATE"], 3e-4):
                out.append("OVERNIGHT moved apart from the ON point")
    if kind == "ir-curve" and isinstance(doc.get("BUILT_AT"), str) and isinstance(doc.get("AS_OF"), str) \
            and doc["BUILT_AT"][:10] != doc["AS_OF"]:
        out.append(f"BUILT_AT {doc['BUILT_AT']} is not of the curve's AS_OF {doc['AS_OF']}")
    return out
