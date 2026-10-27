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

"""The demo risk batch: one P&L vector per live trade, and the positions Drishti derives from them (docs/guides/VAR_GUIDE.md section 15.6).

**Positions are Drishti's**, derived by the server when it loads a date, by the rules of the market-risk pack's ``positions:`` section
(the same rules are read here, :class:`Rules`, and applied the same way as the server's ``PositionRules``): a trade is live when its
status is live and it has not matured; securities (bonds, bills, shares, warrants) are keyed by book and security id, listed futures and
options by book and contract, everything else (OTC) is a position of one trade. ``POSITION_ID = POS-<TT_BOOK>~<key>``. The generator
derives them too, for the vectors' model (a position's trades share its market path), the portfolios' members and the pack's samples.

The committed trades carry no security id: the batch gives each trade of a securities or listed product a deterministic synthetic
``SECURITY_ID`` (``SEC-GOVT_BOND-USD-03``, ``XCH-BOND_FUTURE-EUR-01``), a few securities per product type and currency, so several
trades share one and fungible positions (long and short trades netting) exist; a bank's batch copies the trade's own security id.

The **vector grain is the trade**: each live trade gets its own 509 scenario P&Ls (``pnl-vector``), and a position's vector is the sum
of its trades' (P&L adds). A trade's P&L is its signed quantity times its position's P&L per unit (factors.unit_path) plus a little of
its own (different dates, strikes, counterparties), rounded to whole USD.

Since 2.9.4 the trades of the systems in scenario_fx.PV_SYSTEMS (Endur) come as **scenario PVs** in their own currency
(``scenario-pv``) instead of a P&L vector, with the supplied scenario FX (``fx-scenario``): the same factor-model move, valued in the
trade's currency (scenario_fx.py). ``trade_vectors`` keeps every trade's factor-model USD P&L (the limits and portfolios are set from it,
as before); ``engine_vectors`` holds, for the scenario-PV trades, the USD P&L the server computes from their files."""
from __future__ import annotations

import gzip
import hashlib
import json
from collections import defaultdict
from pathlib import Path

import factors as F
import scenario_fx as SFX

LIVE = ("NEW", "VALIDATED", "AMENDED")
DIVISIONS = {"DESK-MM": "DIV-TREASURY", "DESK-SFT": "DIV-TREASURY"}       # every other desk is in DIV-MARKETS
DIVISION_NAMES = {"DIV-MARKETS": "Markets", "DIV-TREASURY": "Treasury"}
#: the synthetic security id's prefix by the rule that keys the trade's position
PREFIX = {"securities": "SEC", "listed": "XCH"}
#: trades per synthetic security, about (a product type and currency with fewer trades has one security)
PER_SECURITY = 6


def replay(folder: Path, key: str, dates: list[str]) -> dict[str, dict]:
    """A dataset's documents on its last day: the first day's snapshot, then each later day's changes by key."""
    state: dict[str, dict] = {}
    for i, day in enumerate(dates):
        for f in sorted(folder.glob(f"{'snapshot' if i == 0 else 'changes'}-{day}*.jsonl.gz")):
            with gzip.open(f, "rt", encoding="utf-8") as fh:
                for line in fh:
                    if line.strip():
                        d = json.loads(line)
                        state[str(d[key])] = d
    return state


def latest(folder: Path, key: str, dates: list[str]) -> dict[str, dict]:
    """replay() when the folder has the dated files, else empty (a dataset this generator can do without, as trade valuations)."""
    return replay(folder, key, dates) if folder.is_dir() else {}


class Rules:
    """The ``positions:`` section of the market-risk pack (the server's ``PositionRules``, the same semantics)."""

    def __init__(self, section: dict | None):
        section = section or {}
        self.live = {s.upper() for s in section.get("live-statuses") or LIVE}
        self.rules = []
        for n, r in enumerate(section.get("rules") or [], 1):
            self.rules.append({"name": r.get("name") or f"rule-{n}", "groups": {g.upper() for g in r.get("groups") or []},
                               "types": {t.upper() for t in r.get("types") or []}, "key": list(r.get("key") or [])})

    @classmethod
    def of_pack(cls, pack_yaml: Path) -> "Rules":
        import yaml
        return cls(yaml.safe_load(pack_yaml.read_text(encoding="utf-8")).get("positions"))

    def is_live(self, status) -> bool:
        return not status or str(status).upper() in self.live

    def rule(self, group, ptype) -> dict | None:
        for r in self.rules:
            if not r["groups"] and not r["types"]:
                return r
            if (group and str(group).upper() in r["groups"]) or (ptype and str(ptype).upper() in r["types"]):
                return r
        return None

    def derive(self, trade_id: str, book: str, group, ptype, field) -> dict:
        """POSITION_ID, INSTRUMENT_ID, SECURITY_ID and the rule's name, as the server derives them."""
        r = self.rule(group, ptype)
        if r is not None:
            for k in r["key"]:
                v = field(k)
                if v is not None and str(v).strip():
                    v = str(v).strip()
                    return {"POSITION_ID": f"POS-{book or ''}~{v}", "INSTRUMENT_ID": v, "SECURITY_ID": v, "POSITION_RULE": r["name"]}
            name = r["name"] if not r["key"] else "otc"
        else:
            name = "otc"
        return {"POSITION_ID": f"POS-{book or ''}~{trade_id}", "INSTRUMENT_ID": trade_id, "SECURITY_ID": None, "POSITION_RULE": name}


def book_of(t: dict) -> str:
    return t.get("TT_BOOK") or t.get("PORTFOLIO") or ""


def group_of(t: dict) -> str:
    g = str(t.get("PRODUCT_GROUP") or "")
    return g if g else "Commodity"


def signed_qty(t: dict) -> float:
    q = float(t.get("QUANTITY") or 0.0)
    return -abs(q) if str(t.get("BUY_SELL_CODE") or "B").upper().startswith("S") else abs(q)


def instrument_name(t: dict) -> str:
    what = t.get("PRODUCT_SUB_TYPE") or t.get("PRODUCT_TYPE") or "Instrument"
    return f"{what} {t.get('CURRENCY') or 'USD'} {str(t.get('MATURITY_DATE') or '')[:7]}".strip()


def _h(text: str) -> int:
    return int(hashlib.sha256(text.encode("utf-8")).hexdigest()[:12], 16)


def security_ids(trades: list[dict], rules: Rules) -> dict[str, str]:
    """trade id -> a synthetic SECURITY_ID for the trades of a securities or listed rule keyed by SECURITY_ID: a few securities per
    product type and currency (about PER_SECURITY trades each), a trade's chosen by a hash of its id (deterministic)."""
    groups: dict[tuple[str, str, str], list[str]] = defaultdict(list)
    for t in trades:
        r = rules.rule(group_of(t), t.get("PRODUCT_TYPE"))
        if r is not None and "SECURITY_ID" in r["key"]:
            groups[(r["name"], str(t.get("PRODUCT_TYPE")), str(t.get("CURRENCY") or "USD"))].append(str(t["TRADE_ID"]))
    out = {}
    for (rule, ptype, ccy), ids in sorted(groups.items()):
        n = max(1, round(len(ids) / PER_SECURITY))
        for tid in sorted(ids):
            out[tid] = f"{PREFIX.get(rule, 'SEC')}-{ptype}-{ccy}-{_h(tid) % n + 1:02d}"
    return out


class Batch:
    """The demo risk batch of one business date: every live trade valued, each with its vector; the positions the rules derive."""

    def __init__(self, trades: dict[str, dict], desks: dict[str, dict], valuations: dict[str, dict], cob: str, rules: Rules, budget: int = 0,
                 market: F.Market | None = None, fx: SFX.Fx | None = None):
        self.cob, self.market, self.rules, self.fx = cob, market or F.Market(), rules, fx
        self.desk_of_book: dict[str, str] = {}
        self.le_of_desk: dict[str, str] = {}
        for d in desks.values():
            self.le_of_desk[d["DESK_ID"]] = d.get("LEGAL_ENTITY") or ""
            for b in d.get("BOOKS") or []:
                self.desk_of_book[b["ID"]] = d["DESK_ID"]
        live = [t for t in trades.values() if rules.is_live(t.get("TRADE_STATUS")) and str(t.get("MATURITY_DATE") or "9999") >= cob
                and book_of(t) in self.desk_of_book]
        live.sort(key=lambda t: str(t["TRADE_ID"]))
        self.security = security_ids(live, rules)
        groups: dict[str, list[dict]] = defaultdict(list)
        self.derived: dict[str, dict] = {}
        for t in live:
            tid = str(t["TRADE_ID"])
            d = rules.derive(tid, book_of(t), group_of(t), t.get("PRODUCT_TYPE"), lambda k: self.security.get(tid) if k == "SECURITY_ID" else t.get(k))
            self.derived[tid] = d
            groups[d["POSITION_ID"]].append(t)
        self.groups = self._sample(groups, budget)
        self.valuations = valuations
        self.trade_vectors: dict[str, list[int]] = {}       # trade id -> scenario P&Ls, then the business date's own move last
        self.positions: list[dict] = []
        self.vector_rows: list[dict] = []
        self.pv_rows: list[dict] = []                       # the trades sent as scenario PVs (scenario-pv)
        self.engine_vectors: dict[str, list[float]] = {}    # their USD P&L as the server computes it from the files
        self._value()

    @staticmethod
    def _sample(groups: dict, budget: int) -> dict:
        """Every position (budget 0, the committed data), or up to ``budget`` trades a book, whole positions, both directions first."""
        if budget <= 0:
            return dict(sorted(groups.items()))
        by_book: dict[str, list] = defaultdict(list)
        for pid, ts in groups.items():
            by_book[book_of(ts[0])].append((pid, ts))
        out = {}
        for book in sorted(by_book):
            cands = sorted(by_book[book], key=lambda x: (len({signed_qty(t) > 0 for t in x[1]}) < 2, len(x[1]), x[0]))
            used = 0
            for pid, ts in cands:
                if used and used + len(ts) > budget:
                    continue
                out[pid] = ts
                used += len(ts)
                if used >= budget:
                    break
        return dict(sorted(out.items()))

    def _value(self) -> None:
        m = self.market
        for pid, ts in self.groups.items():
            t0 = ts[0]
            book = book_of(t0)
            desk = self.desk_of_book[book]
            grp = group_of(t0)
            r = F.seeded("instrument", book, pid)
            risk = F.BASE_RISK.get(grp.lower(), 150_000) * r.lognormvariate(0.0, 0.55)
            gross = sum(abs(signed_qty(t)) for t in ts) or 1.0
            per_unit = risk / gross
            unit = F.unit_path(m, F.loadings(grp, t0.get("PRODUCT_TYPE"), t0.get("PRODUCT_SUB_TYPE"), t0.get("CURRENCY")),
                               F.is_option(t0.get("PRODUCT_TYPE")), r)
            le = self.le_of_desk.get(desk) or t0.get("LEGAL_ENTITY")
            for t in ts:
                tid = str(t["TRADE_ID"])
                q = signed_qty(t)
                tr = F.seeded("trade", tid)
                own = 0.06 * abs(q) * per_unit
                vec = [round(q * per_unit * unit[j] + own * F.student_t(tr)) for j in range(m.n + 1)]
                self.trade_vectors[tid] = vec
                pv = float((self.valuations.get(f"TVAL-{tid}") or {}).get("MTM") or 0.0)
                ccy = t.get("CURRENCY") or "USD"
                terms = {"TRADE_ID": tid, "SECURITY_ID": self.security.get(tid), "INSTRUMENT": instrument_name(t),
                         "BUSINESS_DATE": self.cob, "SCENARIO_SET_ID": "HS-509", "SOURCE_SYSTEM": t.get("SOURCE_SYSTEM"),
                         "TRADE_VERSIONS": f"v{t.get('TRADE_VERSION') or 1}", "TRADE_STATUS": t.get("TRADE_STATUS"),
                         "MATURITY_DATE": t.get("MATURITY_DATE"), "LEGAL_ENTITY": le, "DIVISION": DIVISIONS.get(desk, "DIV-MARKETS"), "DESK": desk,
                         "TT_BOOK": book, "PORTFOLIO": t.get("PORTFOLIO") or book, "STRATEGY": t.get("STRATEGY") or "", "PRODUCT_GROUP": grp,
                         "PRODUCT_TYPE": t.get("PRODUCT_TYPE"), "CURRENCY": ccy, "COUNTERPARTY": t.get("COUNTERPARTY") or "",
                         "DIRECTION": "LONG" if q >= 0 else "SHORT", "QUANTITY": q, "TAGS": "|".join(self._tags(t))}
                run = {"STATUS": "ok", "PRICER": "demo-factor-model", "MODEL_VERSION": "demo-2", "COMPUTED_AT": f"{self.cob}T22:30:00Z",
                       "BATCH_ID": f"B-{self.cob}-01", "RESTATEMENT": 0, "RUN_KIND": "FULL"}   # a full run (VAR_GUIDE.md section 8.4)
                if self.fx is not None and t.get("SOURCE_SYSTEM") in SFX.PV_SYSTEMS and self.fx.has(ccy):
                    base, pvs = SFX.scenario_pvs(self.fx, ccy, float(round(pv)), vec)
                    self.engine_vectors[tid] = SFX.engine_pnl(self.fx, ccy, base, pvs)
                    self.pv_rows.append({"SCENARIO_PV_ID": f"SPV-{tid}-HS-509", **terms, "BASE_PV": base, "VALUATION_DATE": self.cob,
                                         "BASE_VALUATION_DATE": self.cob, "SCENARIO_PV": pvs[:m.n], **run})
                else:
                    self.vector_rows.append({"VECTOR_ID": f"PV-{tid}-HS-509", **terms, "REPORTING_CCY": "USD", "PV": round(pv),
                                             "PNL": vec[:m.n], **run})
            self.positions.append(self.position(pid, ts))

    def position(self, pid: str, ts: list[dict]) -> dict:
        """A position as the server serves it (VarPositions.document): derived from its trades, no VaR figure on it."""
        t0 = ts[0]
        book = book_of(t0)
        desk = self.desk_of_book[book]
        d = self.derived[str(t0["TRADE_ID"])]
        qty = sum(signed_qty(t) for t in ts)
        rows = []
        for t in sorted(ts, key=lambda x: str(x["TRADE_ID"])):
            q = signed_qty(t)
            pv = float((self.valuations.get(f"TVAL-{t['TRADE_ID']}") or {}).get("MTM") or 0.0)
            rows.append({"TRADE_ID": str(t["TRADE_ID"]), "QUANTITY": q, "DIRECTION": "LONG" if q > 0 else "SHORT" if q < 0 else "FLAT",
                         "PV": float(round(pv)), "PRODUCT_TYPE": t.get("PRODUCT_TYPE"), "COUNTERPARTY": t.get("COUNTERPARTY") or "",
                         "TRADE_CURRENCY": t.get("CURRENCY") or "USD"})   # the currency its PVs come in (VAR_GUIDE.md section 15.11)
        cps = sorted({r["COUNTERPARTY"] for r in rows} - {""})
        mv = sum(r["PV"] for r in rows)
        tags = sorted({x for t in ts for x in self._tags(t)})
        return {
            "POSITION_ID": pid, "BUSINESS_DATE": self.cob, "POSITION_RULE": d["POSITION_RULE"], "SECURITY_ID": d["SECURITY_ID"],
            "INSTRUMENT_ID": d["INSTRUMENT_ID"], "INSTRUMENT": instrument_name(t0), "POSITION_TYPE": "fungible" if len(ts) > 1 else "single-trade",
            "DIRECTION": "LONG" if qty > 0 else "SHORT" if qty < 0 else "FLAT", "QUANTITY": qty,
            "GROSS_QUANTITY": float(sum(abs(signed_qty(t)) for t in ts)), "TRADE_COUNT": len(ts),
            "LONG_TRADES": sum(1 for t in ts if signed_qty(t) > 0), "SHORT_TRADES": sum(1 for t in ts if signed_qty(t) < 0),
            "TRADE_IDS": [r["TRADE_ID"] for r in rows], "TRADES": rows,
            "LEGAL_ENTITY": self.le_of_desk.get(desk) or t0.get("LEGAL_ENTITY"), "DIVISION": DIVISIONS.get(desk, "DIV-MARKETS"), "DESK": desk,
            "TT_BOOK": book, "PORTFOLIO": t0.get("PORTFOLIO") or book, "STRATEGY": t0.get("STRATEGY") or "",
            "COUNTERPARTY": cps[0] if len(cps) == 1 and all(r["COUNTERPARTY"] for r in rows) else "", "PRODUCT_GROUP": group_of(t0),
            "PRODUCT_TYPE": t0.get("PRODUCT_TYPE"), "CURRENCY": t0.get("CURRENCY") or "USD", "COUNTERPARTIES": cps,
            "MARKET_VALUE": round(mv), "NOTIONAL": round(sum(abs(signed_qty(t)) for t in ts)), "TAGS": tags,
            "STATUS": "open" if qty else "flat", "SCENARIO_SET_ID": "HS-509", "VAR_SCOPE": "POS:" + pid}

    @staticmethod
    def _tags(t: dict) -> list[str]:
        tags = []
        if str(t.get("INTERNAL_EXTERNAL_CODE") or "").lower() == "internal":
            tags.append("internal")
        if F.is_option(t.get("PRODUCT_TYPE")):
            tags.append("option")
        return tags

    def position_vector(self, pos: dict, upto: int | None = None) -> list[int]:
        """The position's vector: the sum of its trades' (``upto`` scenario days, all with the business date's move when None)."""
        n = len(self.trade_vectors[pos["TRADE_IDS"][0]]) if upto is None else upto
        out = [0] * n
        for tid in pos["TRADE_IDS"]:
            v = self.trade_vectors[tid]
            for j in range(n):
                out[j] += v[j]
        return out
