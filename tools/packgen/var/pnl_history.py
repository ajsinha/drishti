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

"""The demo's **long-history P&L** of today's portfolio per hierarchy node (``pnl-history``; docs/guides/VAR_GUIDE.md section 18.9): what
a bank's monthly batch sends so the stress window selection tool can value every 12-month window since 2007 as a stressed VaR.

- **The days.** Every business day (the New York calendar) from 2007-01-02 to the last scenario day of the normal set: about 5,000.
- **Anchored to the two scenario sets.** The days of the stress set (``HS-STRESS-2008``, 2008-07-01 to 2009-06-30) carry each node's P&L
  under the stress set exactly as the engine computes it (scenario PVs converted with the stress set's FX), and the last 509 days carry the
  normal set's (``HS-509``): the tool's figure for the configured window is the node's stressed VaR, and its VaR over the last 509 days is
  the node's VaR today, to the cent.
- **Synthetic elsewhere.** Every other day is made per book: the book's own daily volatility (from its normal-set P&L) times a regime scale,
  with a common market move weighted by the book's correlation with the bank (so books diversify as they do today) and the book's own
  noise, Student-t shocks; episodes shaped like the crises since 2007 (2007's quant quake, Bear Stearns in March 2008, the euro-area
  crisis of 2011, the 2013 taper tantrum, August 2015, February 2018, March 2020, the rates shock of 2022, March 2023), with rates books
  hit harder in 2013 and 2022, equity books in 2020 and commodity books in 2015. **Not market history**: the rows say so
  (``SYNTHETIC``, ``DESCRIPTION``).
- **The rows.** One per node of MGMT and LEGAL above the books (the bank, divisions or legal entities, desks), on the business date:
  ``HISTORY_DATES`` and ``PNL`` (USD) in date order; a node's P&L is the sum of its books', so the series add up the hierarchy.

Deterministic: the same trades give the same rows (Python's own Random, seeded by text)."""
from __future__ import annotations

import math
from datetime import date, timedelta

import factors as F

KIND = "pnl-history"
FIRST = "2007-01-02"
#: (first day, last day, the market's scale, {product group: the group's own scale}) of each episode; outside every episode both are 1
EPISODES = (
    ("2007-07-16", "2007-12-31", 1.25, {"credit": 1.6, "structured": 1.5}),
    ("2008-01-02", "2008-06-30", 1.45, {"credit": 1.8, "structured": 1.6}),
    ("2009-07-01", "2009-12-31", 1.20, {"credit": 1.4}),
    ("2010-05-03", "2010-06-30", 1.20, {"equity": 1.5}),
    ("2011-08-01", "2011-12-30", 1.40, {"credit": 1.8, "fixed income": 1.6}),
    ("2013-05-22", "2013-07-15", 1.10, {"rates": 2.4, "fixed income": 2.2, "inflation": 2.0}),
    ("2015-08-17", "2016-02-29", 1.10, {"commodity": 1.7, "": 1.7}),
    ("2018-02-01", "2018-02-28", 1.20, {"equity": 2.6, "structured": 2.0}),
    ("2020-02-24", "2020-05-29", 1.55, {"equity": 3.2, "structured": 2.4, "commodity": 1.8, "": 1.8}),
    ("2022-02-24", "2022-11-30", 1.25, {"rates": 3.4, "fixed income": 3.0, "inflation": 3.0, "money markets": 2.2, "fx": 2.0}),
    ("2023-03-08", "2023-03-31", 1.20, {"rates": 2.6, "money markets": 2.0}),
)
#: crash days: the market falls this many of the bank's daily standard deviations at once
CRASHES = {"2007-02-27": 2.6, "2007-08-09": 3.0, "2008-01-21": 3.4, "2008-03-14": 5.3, "2008-03-17": 4.6, "2010-05-06": 3.0,
           "2011-08-08": 3.8, "2015-08-24": 3.3, "2018-02-05": 3.0, "2020-03-09": 4.0, "2020-03-12": 4.4, "2020-03-16": 4.7,
           "2022-06-13": 3.0, "2022-09-13": 3.2}
#: the book's own part against its share of the market (its idiosyncratic standard deviation as a fraction of the normal set's)
OWN = 0.40
DESCRIPTION = ("The long-history P&L of today's portfolio at this node, one value per business day since 2007, for the stress window "
               "selection; synthetic for the demo (not market history), equal to the stress set's and the normal set's P&L on their days")


def business_days(first: str, last: str) -> list[str]:
    from samplegen.dates import Calendar
    cal = Calendar.of("USNY")
    d, end, out = date.fromisoformat(first), date.fromisoformat(last), []
    while d <= end:
        if cal.is_business_day(d):
            out.append(d.isoformat())
        d += timedelta(days=1)
    return out


def _mean(v: list[float]) -> float:
    return sum(v) / len(v)


def _cov(a: list[float], b: list[float]) -> float:
    ma, mb = _mean(a), _mean(b)
    return sum((x - ma) * (y - mb) for x, y in zip(a, b)) / max(1, len(a) - 1)


def _episode(day: str) -> tuple[float, dict]:
    for lo, hi, s, extra in EPISODES:
        if lo <= day <= hi:
            return s, extra
    return 1.0, {}


class History:
    """The pnl-history rows of a demo batch (make_var_data.Demo), anchored to its normal and stress sets.

    A synthetic day of a book is ``beta * M + own * (0.7 G + 0.71 E)``: M the bank's market move (the bank's own daily standard deviation
    from the normal set times the episode's scale and a GARCH Student-t draw; a crash day a fall of CRASHES sigmas), beta the book's
    covariance with the bank over the bank's variance (the betas add up to one, so the books add up to the market), G its product
    group's move and E its own, both Student-t, scaled by OWN of the book's residual standard deviation and the episode's group scale."""

    def __init__(self, demo, stress):
        self.demo, self.stress = demo, stress
        if not stress.engine_vectors:
            stress.rows()                                              # the scenario-PV trades' USD P&L as the engine computes it
        b = demo.batch
        self.days = business_days(FIRST, demo.scenario_dates[-1])
        at = {d: j for j, d in enumerate(self.days)}
        normal_at = [at[d] for d in demo.scenario_dates]
        stress_at = [at[d] for d in stress.days]
        n = demo.n
        normal = {t: [float(x) for x in (b.engine_vectors.get(t) or v)[:n]] for t, v in b.trade_vectors.items()}
        stressed = {t: stress.engine_vectors.get(t) or [float(x) for x in v] for t, v in stress.trade_vectors.items()}
        books: dict[str, list[str]] = {}
        counts: dict[str, dict[str, int]] = {}
        for p in b.positions:
            books.setdefault(p["TT_BOOK"], []).extend(p["TRADE_IDS"])
            c = counts.setdefault(p["TT_BOOK"], {})
            grp = (p.get("PRODUCT_GROUP") or "").lower()
            c[grp] = c.get(grp, 0) + 1
        groups = {bk: sorted(c.items(), key=lambda kv: (-kv[1], kv[0]))[0][0] for bk, c in counts.items()}   # the book's main group
        bank = _add(normal.values())
        var_bank = _cov(bank, bank)
        market = self._market(math.sqrt(var_bank))
        group_moves = {g: [_clip(F.student_t(r), 4.0) for _ in self.days] for g in sorted(set(groups.values()))
                       for r in [F.seeded("history", "group", g)]}
        self.book_series: dict[str, list[float]] = {}
        for book in sorted(books):
            nv = _add(normal[t] for t in books[book])
            sv = _add(stressed[t] for t in books[book])
            beta = _cov(nv, bank) / var_bank if var_bank > 0 else 0.0
            resid = [x - beta * y for x, y in zip(nv, bank)]
            own = OWN * math.sqrt(max(0.0, _cov(resid, resid)))
            grp = groups.get(book, "")
            gm = group_moves[grp]
            r = F.seeded("history", "book", book)
            series = [0.0] * len(self.days)
            for j, d in enumerate(self.days):
                s, extra = _episode(d)
                series[j] = beta * market[j] + own * extra.get(grp, 1.0) * (0.7 * gm[j] + 0.71 * _clip(F.student_t(r), 4.0))
            for k, j in enumerate(normal_at):
                series[j] = nv[k]
            for k, j in enumerate(stress_at):
                series[j] = sv[k]
            self.book_series[book] = series

    def _market(self, sigma: float) -> list[float]:
        """The bank's market move of every day: GARCH(1,1) Student-t at the episode's scale; a crash day falls CRASHES sigmas."""
        r = F.seeded("history", "market")
        omega, alpha, beta = 0.05, 0.10, 0.85
        var, prev, out = 1.0, 0.0, []
        for d in self.days:
            s, _ = _episode(d)
            var = omega + alpha * prev * prev + beta * var
            x = _clip(F.student_t(r), 3.0) * math.sqrt(var)
            prev = x
            out.append(sigma * (-CRASHES[d] if d in CRASHES else x * s))
        return out

    def rows(self) -> list[dict]:
        """One row per MGMT and LEGAL node above the books: its books' series added, rounded to the cent."""
        import stress as ST
        demo = self.demo
        out = []
        for nid, node in sorted(demo.nodes.items()):
            if node["LEVEL"] == "book":
                continue
            v = _add(self.book_series[bk] for bk in node["BOOKS"] if bk in self.book_series)
            if not v:
                continue
            out.append({"HISTORY_ID": f"PNLH-{nid}", "BUSINESS_DATE": demo.cob, "HIERARCHY": node["HIERARCHY"], "NODE_ID": nid,
                        "NAME": node["NAME"], "LEVEL": node["LEVEL"], "CURRENCY": "USD", "FIRST_DAY": self.days[0],
                        "LAST_DAY": self.days[-1], "DAY_COUNT": len(self.days), "HISTORY_DATES": self.days,
                        "PNL": [round(x, 2) for x in v], "SYNTHETIC": True, "ANCHORS": [ST.SET, "HS-509"],
                        "SOURCE_BATCH": f"B-{demo.cob}-HIST", "DESCRIPTION": DESCRIPTION})
        return out


def _add(vectors) -> list[float]:
    out = None
    for v in vectors:
        if out is None:
            out = list(v)
        else:
            for j, x in enumerate(v):
                out[j] += x
    return out or []


def _clip(x: float, limit: float) -> float:
    """A draw kept within limit standard deviations: the crash days, not a stray draw, make the worst days of a synthetic year."""
    return max(-limit, min(limit, x))
