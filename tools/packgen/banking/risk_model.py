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

"""The risk pack's taxonomy model: product, market-data and risk/reference specifications, and the tiny field
language they are written in. This file is the single source of truth: make_sutras.py, make_data.py and
make_docs.py all read these specifications, so Sutras, sample data and documentation cannot drift apart.

Field language: "name:Label:fmt:generator", separated by ";". Generators:
  rate(lo,hi)  num(lo,hi,dp)  int(lo,hi)  choice(a|b|c)  date(y0,y1)  tenor(1Y|5Y)  bool(p)  const(v)  pick(key)
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field

CCYS = ["USD", "EUR", "GBP", "JPY", "CHF", "AUD", "CAD"]
RFR = {"USD": "SOFR", "EUR": "ESTR", "GBP": "SONIA", "JPY": "TONA", "CHF": "SARON", "AUD": "AONIA", "CAD": "CORRA"}
IBOR = {"USD": "TERM-SOFR-3M", "EUR": "EURIBOR-6M", "GBP": "SONIA-TERM-3M", "JPY": "TIBOR-3M", "CHF": "SARON-3M", "AUD": "BBSW-3M", "CAD": "CDOR-3M"}
PAIRS = ["EURUSD", "GBPUSD", "USDJPY", "USDCHF", "AUDUSD", "USDCAD", "EURGBP", "EURJPY", "USDCNH", "USDINR", "USDBRL", "USDKRW"]
NDF_PAIRS = ["USDCNH", "USDINR", "USDBRL", "USDKRW"]


@dataclass(frozen=True)
class F:
    name: str
    label: str
    fmt: str | None
    gen: str


def fields(spec: str) -> list[F]:
    out = []
    for part in [p.strip() for p in spec.split(";") if p.strip()]:
        name, label, fmt, gen = (part.split(":", 3) + ["", "", ""])[:4]
        out.append(F(name.strip(), label.strip(), fmt.strip() or None, gen.strip() or "const()"))
    return out


GEN = re.compile(r"(\w+)\((.*)\)$")


def parse_gen(g: str) -> tuple[str, list[str]]:
    m = GEN.match(g)
    if not m:
        return "const", [g]
    args = m.group(2)
    return m.group(1), [a.strip() for a in (args.split("|") if "|" in args else args.split(","))] if args else []


@dataclass
class Product:
    code: str                    # productType, e.g. IRS_FIXFLOAT
    name: str
    family: str                  # swap, option, bond, ... (drives layout)
    asset: str                   # Rates, FX, Credit, Equity, Commodity, Inflation, Fixed income, Securities financing, Structured
    desc: str
    terms: list[F]
    schedule: str | None = None  # cashflows | coupons | exercise | observations | fixings | amortization | collateral | constituents
    md: list[tuple[str, str, str]] = field(default_factory=list)   # (field, kind, selector)
    risk: list[str] = field(default_factory=list)                   # risk measures shown in the strip / bars
    legs: int = 0                # number of swap legs
    notional: tuple[float, float] = (5e6, 250e6)
    underlier: str = "ccy"       # ccy | pair | issuer | index | equity | basket | commodity | inflation | bond
    listed: bool = False

    @property
    def mnemonic_id(self) -> str:
        return self.code.replace("_", "")


# ---- market-data selectors shared by Sutras and data: (field, kind, selector) -----------------------------------
def MD(*items: str) -> list[tuple[str, str, str]]:
    table = {
        "ois": ("discountCurve", "ir-curve", "ois:{ccy}"), "proj": ("forwardCurve", "ir-curve", "proj:{ccy}"),
        "govt": ("benchmarkCurve", "ir-curve", "govt:{ccy}"), "basis": ("basisCurve", "ir-curve", "basis:{ccy}"),
        "swvol": ("swaptionVolCube", "ir-vol-cube", "swaption:{ccy}"), "capvol": ("capVolSurface", "cap-vol-surface", "cap:{ccy}"),
        "fx": ("fxSpot", "fx-spot", "{pair}"), "fxfwd": ("fxForwardCurve", "fx-forward-curve", "{pair}"),
        "fxvol": ("fxVolSurface", "fx-vol-surface", "{pair}"), "cds": ("creditCurve", "credit-curve", "{issuer}"),
        "eq": ("underlyingEquity", "equity", "{equity}"), "eqvol": ("equityVolSurface", "equity-vol-surface", "{equity}"),
        "div": ("dividendCurve", "dividend-curve", "{equity}"), "idx": ("underlyingIndex", "equity-index", "{index}"),
        "idxvol": ("equityVolSurface", "equity-vol-surface", "{index}"), "infl": ("inflationCurve", "inflation-curve", "{infl}"),
        "cpi": ("inflationIndex", "inflation-index", "{infl}"), "cmd": ("commodityCurve", "commodity-curve", "{commodity}"),
        "cmdvol": ("commodityVolSurface", "commodity-vol-surface", "{commodity}"), "fix": ("fixingIndex", "rate-fixing", "rfr:{ccy}"),
        "bond": ("underlyingBond", "bond", "{bond}"), "repo": ("repoCurve", "repo-curve", "{ccy}"),
        "corr": ("correlation", "correlation-matrix", "{corr}"),
    }
    return [table[i] for i in items]


# ---- market-data, risk and reference kinds ----------------------------------------------------------------------
@dataclass
class Panel:
    kind: str                         # kv | table | line | area | hbar | ladder | surface | waterfall | histogram | scatter | candlestick | graph | timeline | pivot
    id: str
    title: str
    rows: str | None = None           # Rachana-EL path to the rows (or object for kv)
    columns: list[tuple] = field(default_factory=list)   # (label, bind, fmt, tone, total)
    x: str | None = None
    y: str | None = None
    series: list[tuple] = field(default_factory=list)    # (label, field, tone)
    label: str | None = None
    value: str | None = None
    fmt: str | None = None
    tone: str | None = None
    limit: str | None = None
    area: str = "main"
    key: str | None = None
    code: str | None = None
    opts: dict = field(default_factory=dict)             # further options, in order: scalars, or lists of mappings (markers)
    pivot: dict | None = None                            # a table's Pivot tab: { fields, rows, columns, values, filters, heat }

    def paths(self) -> list[str]:
        """Every option that holds a document path or expression (rows, nodes, edges, marker values), for the checks."""
        out = [self.rows] if self.rows else []
        for v in self.opts.values():
            if isinstance(v, str):
                out.append(v)
            elif isinstance(v, list):
                out += [str(m.get("value")) for m in v if isinstance(m, dict) and m.get("value")]
        return out


@dataclass
class Kind:
    kind: str
    mnemonic: str
    prefix: str                       # identifier prefix, e.g. "CRV-" (also the id pattern)
    label: str
    group: str
    desc: str
    id_field: str
    strip: list[tuple]                # (label, bind, fmt, tone, emphasis)
    panels: list[Panel]
    links: dict[str, tuple[str, str]] = field(default_factory=dict)   # field -> (kind, label)
    badge: str | None = None
    live: dict[str, float] = field(default_factory=dict)              # _meta.walk for sample ticking
    pill: str | None = None
    with_: str | None = None          # title "with" expression
