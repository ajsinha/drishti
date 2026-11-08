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

"""Writes the VaR engine's demo inputs (docs/guides/VAR_GUIDE.md) into data/testdata/market-risk/, from the committed trades of the business
dates (data/testdata/trades/*) and the core pack's desks, as an upstream risk batch would deliver them, for the last VAR_DATES (three)
business dates, each date in files of its own (docs/guides/DATA_LAKE_GUIDE.md section 5.2): each date's trades replayed to it, its scenario window a day further on (a day
keeps its move on every date: factors.Market ``back``), one book moved between desks (MOVED_BOOK), so the VaR change explanation
(VAR_GUIDE.md section 3.11) compares real dates. The stress set, the desk P&L and the long P&L history are the newest date's only:

    pnl-vector/       one P&L vector per live trade: 509 scenario P&Ls in USD, the vector column PNL (list<double> in the lake), with the
                      trade's terms the server derives positions from (SECURITY_ID, TRADE_STATUS, MATURITY_DATE, product, book)
    scenario-pv/      2.9.4: the Endur trades' scenario PVs instead (scenario_fx.py): each trade's PV under the 509 scenarios and its base
                      PV, in its own currency, the vector column SCENARIO_PV; the server turns them into USD P&L when it loads the date
    fx-scenario/      2.9.4: the scenario FX the batch supplies: per currency, USD per unit today and under each scenario
    scenario-set/     HS-509: the scenario days of the business date
    stress-*          the stress scenario set HS-STRESS-2008 (stress.py): its scenario set, P&L vectors, scenario PVs and scenario FX in
                      files of their own (stress-cob...) beside the normal set's, under the same table and date, for stressed VaR
    risk-hierarchy/   the MGMT tree (bank, division, desk, book) and the LEGAL tree (bank, legal entity, desk, book); their VaR limits are var-limit rows
    portfolio/        portfolio definitions of the three types (HIERARCHY, RULE, BASKET), overlapping on purpose: no figures
    var-limit/        2.9.5: the VaR limits by hierarchy and node id (USD): every node of the limit-letter hierarchy LL (built by the
                      server from the rows' LIMIT_LETTER and DESK; a few left without one, shown blank), and the MGMT and LEGAL nodes' own
                      limits, and every portfolio's (hierarchy PORTFOLIO): 2.9.7, the one home of every VaR limit (the
                      risk-hierarchy rows and the portfolios carry none)
    desk-pnl/         the desks' daily actual and hypothetical P&L for regulatory backtesting (desk_pnl.py): 260 business days to the
                      business date, generated from each desk's scenario P&L (its risk-theoretical P&L) with front-office and finance
                      differences

Every P&L and scenario-PV row carries its LIMIT_LETTER (2.9.5, positions.limit_letters): twelve letters, two books split between two.

No position, no VaR and no node history is written: positions are derived by the server from the trades and the pack's rules (VAR_GUIDE.md
section 3.7), every figure is computed when asked, and a node's history is its official end-of-day captures. Every live trade is valued
(statuses NEW, VALIDATED, AMENDED; maturity on or after the date; VAR_GUIDE.md section 23.1 question 1), about 9,600 trades. Each kind's
folder holds one file per business date, <kind>.<YYYY-MM-DD>.jsonl.gz, or tar archives of <kind>/<YYYY-MM-DD>/ folders where a date is
several files or one run writes many dates (TARS: the P&L vectors and scenario PVs one archive a date, their vectors split into files of
about 1.2 MB; the desks' 260-day P&L one archive; scenario-set and fx-scenario, whose newest date holds the stress set's file too, one
archive), written by tools/lake/feedfiles.py; every row's BUSINESS_DATE is its file's (folder's) date. Deterministic: the same trades give
the same files, byte for byte.

    python3 tools/packgen/var/make_var_data.py                    write data/testdata/market-risk/<kind>/ and their testdata.yaml entries
    python3 tools/packgen/var/make_var_data.py --check            fail if they differ from what would be written
    python3 tools/packgen/var/make_var_data.py --budget 60 --out /path/outside/the/repo     a sample of every book (local sizing)
"""
from __future__ import annotations

import argparse
import json
import sys
from collections import defaultdict
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(ROOT / "tools"))
sys.path.insert(0, str(ROOT / "tools" / "lake"))
if str(ROOT / "drishti-console") not in sys.path:
    sys.path.insert(0, str(ROOT / "drishti-console"))
from core.pack_paths import pack_dir  # noqa: E402  (the one rule for where a pack's folder is)
import feedfiles as FF  # noqa: E402  (tools/lake/feedfiles.py: our dated feed files)
import factors as F  # noqa: E402
import scenario_fx as SFX  # noqa: E402
import stress as ST  # noqa: E402
import pnl_history as HI  # noqa: E402
import portfolios as PF  # noqa: E402
import desk_pnl as DP  # noqa: E402
import positions as P  # noqa: E402
import varmath as VM  # noqa: E402
sys.path.insert(0, str(ROOT / "tools" / "packgen" / "common"))
import sutracases as SC  # noqa: E402  (tests/<sutra>/cases.jsonl)

PACK, CONNECTOR, SET = "market-risk", "risk-store", "HS-509"
#: kind -> its id field (the lake key)
KEYS = {"pnl-vector": "VECTOR_ID", "scenario-pv": "SCENARIO_PV_ID", "fx-scenario": "FX_SCENARIO_ID", "scenario-set": "SCENARIO_SET_ID",
        "risk-hierarchy": "NODE_ID", "portfolio": "PORTFOLIO_ID", "var-limit": "LIMIT_ID", "desk-pnl": "DESK_PNL_ID", "pnl-history": "HISTORY_ID"}
#: the folders this generator used to write and no longer does (removed when it writes)
RETIRED = ("position", "var-node")
#: the business dates the VaR data is written for: the newest and the two before it, so the VaR change explanation (VAR_GUIDE.md
#: section 3.11) has real dates to compare; the stress set, the desk P&L and the long P&L history are the newest date's only
VAR_DATES = 3
#: the reorganisation the earlier dates show (the hierarchy step of the explanation): BOOK-FI-3 sat under the credit desk until the newest
#: date but one, when it moved to the fixed income desk (both LE-NY, Markets): (book, the desk before, dates back it still sat there from)
MOVED_BOOK = ("BOOK-FI-3", "DESK-CREDIT", 2)
PART_BYTES = 1_200_000                       # compressed bytes per file: the vectors are split into a few small files
#: the kinds written as tar archives of <kind>/<YYYY-MM-DD>/ folders (docs/guides/DATA_LAKE_GUIDE.md section 5.2): the P&L vectors
#: and scenario PVs one archive per date (a date is ten files, and every date in one archive would be too large to commit), the desks'
#: daily P&L (260 dates of one run) one archive; a kind whose date holds the stress set's file too (scenario-set, fx-scenario) one
#: archive of its dates; every other kind one file per date, <kind>.<YYYY-MM-DD>.jsonl.gz
TARS = {"pnl-vector": "date", "scenario-pv": "date", "desk-pnl": "table"}
PACK_YAML = pack_dir(PACK) / "pack.yaml"


def business_days(end: str, n: int) -> list[str]:
    from samplegen.dates import Calendar
    from samplegen.lake import business_days as bd
    return [d.isoformat() for d in bd(date.fromisoformat(end), n, Calendar.of("USNY"))]


def previous_day(day: str) -> str:
    return business_days(day, 2)[0]


nice = PF.nice


class Demo:
    """Everything the VaR demo data holds for one business date (``back`` business days before the newest)."""

    def __init__(self, testdata: Path, dates: list[str], budget: int = 0, rules: P.Rules | None = None, pv_systems=None,
                 position_systems=(), back: int = 0):
        """``back``: the date that many business days before ``dates[-1]`` (its trades, desks and market as of that day: the demo's
        earlier VaR dates, VAR_DATES)."""
        dates = dates[:len(dates) - back]
        self.cob, self.back = dates[-1], back
        trades: dict[str, dict] = {}
        for system in ("endur", "calypso", "murex"):
            trades.update(P.replay(testdata / "trades" / system, "TRADE_ID", dates))
        desks = P.replay(testdata / "core" / "desk", "DESK_ID", dates)
        book, before, since = MOVED_BOOK
        if back >= since and before in desks:                     # the risk system's tree on that date: the book still on its old desk
            desks = {k: {**d, "BOOKS": [b for b in d.get("BOOKS") or [] if b.get("ID") != book]} for k, d in desks.items()}
            desks[before]["BOOKS"] = desks[before]["BOOKS"] + [{"ID": book}]
        vals = P.latest(testdata / "market-risk" / "trade-valuation", "VALUATION_ID", dates)
        self.desks = desks
        self.rules = rules or P.Rules.of_pack(PACK_YAML)
        market = F.Market(back=back)
        self.fx = SFX.Fx(market, SFX.today_rates(P.replay(testdata / "market-data" / "fx-spot", "PAIR", dates)))
        self.batch = P.Batch(trades, desks, vals, self.cob, self.rules, budget, market=market, fx=self.fx, pv_systems=pv_systems,
                             position_systems=position_systems)
        self.scenario_dates = business_days(previous_day(self.cob), F.N_SCENARIOS)
        self.n = F.N_SCENARIOS
        self.nodes = self._hierarchy()
        self.book_vec = {b: VM.add(self.batch.trade_vectors[t] for p in ps for t in p["TRADE_IDS"])
                         for b, ps in self._positions_by_book().items()}

    def _positions_by_book(self) -> dict[str, list[dict]]:
        out: dict[str, list[dict]] = defaultdict(list)
        for p in self.batch.positions:
            out[p["TT_BOOK"]].append(p)
        return out

    def _hierarchy(self) -> dict[str, dict]:
        """MGMT: BANK > division > desk > book; LEGAL: BANK > legal entity > desk > book. Only books the batch values are leaves."""
        books = sorted(self._positions_by_book())
        desk_books: dict[str, list[str]] = defaultdict(list)
        for b in books:
            desk_books[self.batch.desk_of_book[b]].append(b)
        nodes: dict[str, dict] = {}

        def node(hier, level, name_id, parent, name, ref=None):
            nid = f"{hier}~{name_id}"
            nodes[nid] = {"NODE_ID": nid, "HIERARCHY": hier, "LEVEL": level, "PARENT_ID": parent, "NAME": name, "REF": ref,
                          "CHILDREN": [], "BOOKS": []}
            if parent:
                nodes[parent]["CHILDREN"].append(nid)
            return nid

        for hier in ("MGMT", "LEGAL"):
            bank = node(hier, "bank", "BANK", None, "Drishti Bank")
            mids: dict[str, str] = {}
            for desk in sorted(desk_books):
                if hier == "MGMT":
                    div = P.DIVISIONS.get(desk, "DIV-MARKETS")
                    mid = mids.get(div) or node(hier, "division", div, bank, P.DIVISION_NAMES[div])
                else:
                    div = self.batch.le_of_desk.get(desk) or "LE-NY"
                    mid = mids.get(div) or node(hier, "legal-entity", div, bank, div, {"KIND": "legal-entity", "ID": div})
                mids[div] = mid
                dn = node(hier, "desk", desk, mid, self.desks.get(desk, {}).get("NAME") or desk, {"KIND": "desk", "ID": desk})
                for b in desk_books[desk]:
                    node(hier, "book", b, dn, b, {"KIND": "book", "ID": b})
        for n in nodes.values():                                    # every node lists the books beneath it
            if n["LEVEL"] == "book":
                walk = n
                while walk:
                    walk["BOOKS"].append(n["REF"]["ID"])
                    walk = nodes.get(walk["PARENT_ID"]) if walk["PARENT_ID"] else None
        return nodes

    def node_vector(self, nid: str) -> list[int]:
        return VM.add(self.book_vec[b] for b in self.nodes[nid]["BOOKS"])

    def limits(self) -> None:
        """A VaR limit on every node, as a risk manager sets it (an input): the node's VaR today times a headroom (most green, a few close
        to the limit), two significant figures."""
        tight = {"DESK-STRUCT", "BOOK-RATES-2", "BOOK-FX-3", "LE-TKY"}
        for nid, n in self.nodes.items():
            v = VM.var(self.node_vector(nid)[:self.n])
            r = F.seeded("limit", nid)
            head = (1.04 + 0.06 * r.random()) if nid.split("~")[1] in tight else (1.2 + 0.7 * r.random())
            n["LIMIT_VAR"] = nice(v * head)
        # one board-approved firm-wide limit: both hierarchies split the same bank, so their roots carry the management root's limit
        for n in self.nodes.values():
            if n["LEVEL"] == "bank":
                n["LIMIT_VAR"] = self.nodes["MGMT~BANK"]["LIMIT_VAR"]

    def letter_nodes(self) -> dict[str, list[str]]:
        """The limit-letter hierarchy LL as the server builds it from the rows (LIMIT_LETTER > DESK, path ids): node id -> its trades."""
        b = self.batch
        out: dict[str, list[str]] = defaultdict(list)
        for p in b.positions:
            for t in p["TRADE_IDS"]:
                letter = b.letter[t]
                out["LL~BANK"].append(t)
                out[f"LL~{letter}"].append(t)
                out[f"LL~{letter}/{p['DESK']}"].append(t)
        return dict(sorted(out.items()))

    def var_limit_rows(self) -> list[dict]:
        """The var-limit rows: every LL node's limit (its VaR today times a headroom, as limits() sets them; the bank's is the firm-wide
        limit; two letter-desk nodes left without one: no limit, shown blank), then every MGMT and LEGAL node's own LIMIT_VAR."""
        rows = []

        def row(h, nid, limit):
            rows.append({"LIMIT_ID": f"VL-{nid}", "HIERARCHY": h, "NODE_ID": nid, "LIMIT_VAR": limit, "CURRENCY": "USD",
                         "HORIZON": "1D", "CONFIDENCE": 0.99, "BUSINESS_DATE": self.cob, "VALID_FROM": "2026-01-02", "VALID_TO": None})
        b = self.batch
        unlimited = [n for n in self.letter_nodes() if "/" in n][3::17][:2]
        for nid, trades in self.letter_nodes().items():
            if nid in unlimited:
                continue
            if nid == "LL~BANK":
                row("LL", nid, self.nodes["MGMT~BANK"]["LIMIT_VAR"])
                continue
            v = VM.var(VM.add(b.trade_vectors[t][:self.n] for t in trades))
            r = F.seeded("limit", nid)
            row("LL", nid, nice(v * (1.15 + 0.6 * r.random())))
        for nid, n in sorted(self.nodes.items()):
            row(n["HIERARCHY"], nid, n["LIMIT_VAR"])
        return rows

    def hierarchy_rows(self) -> list[dict]:
        """The nodes without their limits: every VaR limit lives in var-limit (2.9.7, decision 8 of COMPUTED_ATTRIBUTES.md)."""
        return [{**{k: v for k, v in n.items() if k != "LIMIT_VAR"}, "BUSINESS_DATE": self.cob, "VALID_FROM": "2026-01-02", "VALID_TO": None}
                for _, n in sorted(self.nodes.items())]

    def portfolio_limit_rows(self, portfolios: list[dict]) -> list[dict]:
        """A portfolio's VaR limit as a var-limit row of hierarchy PORTFOLIO (2.9.7): the limit the portfolio's definition was set with."""
        return [{"LIMIT_ID": f"VL-{p['PORTFOLIO_ID']}", "HIERARCHY": "PORTFOLIO", "NODE_ID": p["PORTFOLIO_ID"], "LIMIT_VAR": p["LIMIT_VAR"],
                 "CURRENCY": "USD", "HORIZON": "1D", "CONFIDENCE": 0.99, "BUSINESS_DATE": self.cob, "VALID_FROM": "2026-01-02",
                 "VALID_TO": None}
                for p in sorted(portfolios, key=lambda p: p["PORTFOLIO_ID"]) if p.get("LIMIT_VAR") is not None]

    def stress(self) -> "ST.Stress":
        """The stress scenario set's rows of the date (stress.py: HS-STRESS-2008, 250 synthetic 2008-like days), made once."""
        if getattr(self, "_stress", None) is None:
            self._stress = ST.Stress(self)
        return self._stress

    def scenario_set(self) -> dict:
        return {"SCENARIO_SET_ID": SET, "BUSINESS_DATE": self.cob, "SCENARIO_DATES": self.scenario_dates, "SCENARIO_COUNT": self.n,
                "WINDOW_START": self.scenario_dates[0], "WINDOW_END": self.scenario_dates[-1], "HORIZON_DAYS": 1, "REVALUATION": "full",
                "WEIGHTS": None, "SOURCE_BATCH": f"B-{self.cob}-01", "DESCRIPTION": "Historical simulation, 509 business days, equal weights"}


# ---------------------------------------------------------------------------------------------------------------------- writing
def gz_parts(stem: str, lines: list[str]) -> list[tuple[str, bytes]]:
    """A date's lines as (file name, deterministic gzip) parts of about PART_BYTES compressed each (feedfiles.gz_parts)."""
    return FF.gz_parts(stem, lines, PART_BYTES)


def same_files(want: Path, have: Path) -> bool:
    """Whether a folder holds the same feed files as another: the same names and, decompressed, the same rows."""
    return have.is_dir() and [f.name for f in FF.files(want)] == [f.name for f in FF.files(have)] and FF.contents(want) == FF.contents(have)


def dumps(d: dict) -> str:
    return json.dumps(d, separators=(",", ":"), ensure_ascii=False)


def demos(testdata: Path, dates: list[str], budget: int = 0, **kw) -> list[Demo]:
    """The Demo of every VaR date, the oldest first and the newest last."""
    return [Demo(testdata, dates, budget, back=k, **kw) for k in range(VAR_DATES - 1, -1, -1)]


#: the kinds written for the newest date only (histories of their own, or the stress window's input)
NEWEST_ONLY = ("desk-pnl", "pnl-history")


def all_documents(ds: list[Demo]) -> dict[str, list[dict]]:
    """Every date's documents by kind, the oldest date first. Limits are a risk manager's settings, not the day's VaR: the earlier dates
    carry the newest date's limit wherever it has one (a node or portfolio only an earlier date has keeps its own)."""
    newest = documents(ds[-1])
    limit = {r["LIMIT_ID"]: r["LIMIT_VAR"] for r in newest["var-limit"]}
    out: dict[str, list[dict]] = {k: [] for k in newest}
    for d in ds[:-1]:
        docs = documents(d, newest_only=False)
        docs["var-limit"] = [{**r, "LIMIT_VAR": limit.get(r["LIMIT_ID"], r["LIMIT_VAR"])} for r in docs["var-limit"]]
        for k, rows in docs.items():
            out[k] += rows
    for k, rows in newest.items():
        out[k] += rows
    return out


def newest(docs: dict[str, list[dict]], cob: str) -> dict[str, list[dict]]:
    """The documents of the date ``cob`` (the NEWEST_ONLY kinds whole)."""
    return {k: v if k in NEWEST_ONLY else [r for r in v if r["BUSINESS_DATE"] == cob] for k, v in docs.items()}


def documents(demo: Demo, newest_only: bool = True) -> dict[str, list[dict]]:
    """The documents of the demo's date (``newest_only`` False: without the NEWEST_ONLY kinds)."""
    demo.limits()
    portfolios = PF.rows(demo)
    limits = demo.var_limit_rows() + demo.portfolio_limit_rows(portfolios)
    return {"risk-hierarchy": demo.hierarchy_rows(), "scenario-set": [demo.scenario_set()],
            # sorted as a book's trades are contiguous (the engine sorts a date in memory anyway: the lake's order does not matter)
            "pnl-vector": sorted(demo.batch.vector_rows, key=lambda r: (r["LEGAL_ENTITY"], r["DESK"], r["TT_BOOK"], r["TRADE_ID"])),
            "scenario-pv": sorted(demo.batch.pv_rows, key=lambda r: (r["LEGAL_ENTITY"], r["DESK"], r["TT_BOOK"], r["TRADE_ID"])),
            "fx-scenario": demo.fx.rows(demo.cob, SET, f"B-{demo.cob}-01"),
            "portfolio": [{k: v for k, v in p.items() if k != "LIMIT_VAR"} for p in portfolios], "var-limit": limits,
            **({"desk-pnl": DP.rows(demo),
                # the long-history P&L of today's portfolio per node, for the stress window selection (history.py; VAR_GUIDE.md section 18.9)
                "pnl-history": HI.History(demo, demo.stress()).rows()} if newest_only else {})}


def write(out: Path, ds: list[Demo], with_samples: bool = True) -> list[dict]:
    """Writes the folders (and the pack's reference samples) for the dates of ``ds`` (the newest last); returns their testdata.yaml
    entries. Each date has its own files (tools/lake/feedfiles.py: one dated file, or a tar's date folder, TARS)."""
    entries = []
    demo = ds[-1]
    docs = all_documents(ds)
    if with_samples:
        want = samples(demo, newest(docs, demo.cob))
        for path, text in want.items():
            path.parent.mkdir(parents=True, exist_ok=True)
            if not path.is_file() or path.read_text(encoding="utf-8") != text:
                path.write_text(text, encoding="utf-8")
        for path in retired_samples(want):
            path.unlink()
    for kind in RETIRED:
        folder = out / PACK / kind
        if folder.is_dir():
            for f in folder.iterdir():
                f.unlink()
            folder.rmdir()
    stressed = demo.stress().rows()
    for kind, rows in docs.items():
        extra = stressed.get(kind) or []
        by_day: dict[str, list[tuple[str, bytes]]] = {}
        for day in sorted({r["BUSINESS_DATE"] for r in rows}):
            by_day[day] = gz_parts("cob", [dumps(r) for r in rows if r["BUSINESS_DATE"] == day])
        for day in sorted({r["BUSINESS_DATE"] for r in extra}):     # the stress set (newest date): its own files beside the normal set's (stress.py)
            by_day.setdefault(day, []).extend(gz_parts(f"{ST.FILE_PREFIX}cob", [dumps(r) for r in extra if r["BUSINESS_DATE"] == day]))
        dates = sorted(by_day)
        files = FF.write_table(out / PACK / kind, kind, by_day, TARS.get(kind))
        entries.append({"folder": f"{PACK}/{kind}", "pack": PACK, "kind": kind, "connector": CONNECTOR, "key": KEYS[kind],
                        "dated-by": "BUSINESS_DATE", "first-date": dates[0], "last-date": dates[-1], "documents": len(rows) + len(extra),
                        "files": [f.name for f in files], "bytes": sum(f.stat().st_size for f in files)})
    return entries


FOLDERS = tuple(f"{PACK}/{k}" for k in (*KEYS, *RETIRED))
SAMPLES = pack_dir(PACK) / "samples"
TESTS = pack_dir(PACK) / "tests"


def retired_samples(want: dict[Path, str] | None = None) -> list[Path]:
    """Samples and Sutra tests on disk that are no longer written: the retired stored kinds', and earlier picks' tests."""
    out = [SAMPLES / "var-node.jsonl"] + sorted(TESTS.glob("var-node/*"))
    if want is not None:
        out += [f for kind in ("position", "portfolio") for f in sorted((TESTS / kind).glob("*.json*")) if f not in want]   # the older <id>.json too
    return [p for p in out if p.is_file()]


def samples(demo: Demo, docs: dict[str, list[dict]]) -> dict[Path, str]:
    """The few documents of each kind the pack's samples/ keep for its README and docs (never served from there: the lake is), and the
    pack's Sutra tests of the position and portfolio pages. The positions are the server's derived documents (no figures)."""
    pos = sorted(demo.batch.positions, key=lambda p: p["POSITION_ID"])
    fungible = next((p for p in pos if p["POSITION_RULE"] == "securities" and p["LONG_TRADES"] and p["SHORT_TRADES"]),
                    next(p for p in pos if p["TRADE_COUNT"] > 1))
    otc = next(p for p in pos if p["DESK"] == "DESK-RATES" and p["POSITION_RULE"] == "otc")
    picks = {"position": [fungible, otc],
             "pnl-vector": [r for r in docs["pnl-vector"] if r["TRADE_ID"] == fungible["TRADE_IDS"][0]],
             "scenario-pv": [r for r in docs["scenario-pv"] if r["CURRENCY"] != "USD"][:1],
             "fx-scenario": [r for r in docs["fx-scenario"] if r["CURRENCY"] == "EUR"],
             "scenario-set": docs["scenario-set"],
             "risk-hierarchy": [n for n in docs["risk-hierarchy"] if n["NODE_ID"] in ("MGMT~DESK-RATES", "LEGAL~LE-NY")],
             "var-limit": [r for r in docs["var-limit"] if r["HIERARCHY"] == "LL"][:3] + [r for r in docs["var-limit"]
                                                                                          if r["NODE_ID"] == "MGMT~DESK-RATES"],
             "portfolio": [r for r in docs["portfolio"] if r["PORTFOLIO_ID"] in ("PF-RATES-DESK", "PF-USD-RATES", "PF-ASHA-UNWIND")],
             "desk-pnl": [r for r in docs["desk-pnl"] if r["BUSINESS_DATE"] == demo.cob][:3],
             # a node's history cut to its first 20 days, so the sample stays readable (the lake's rows hold every day since 2007)
             "pnl-history": [{**r, "HISTORY_DATES": r["HISTORY_DATES"][:20], "PNL": r["PNL"][:20], "DAY_COUNT": 20,
                              "LAST_DAY": r["HISTORY_DATES"][19]} for r in docs["pnl-history"] if r["NODE_ID"] == "MGMT~DESK-RATES"]}
    # and the same data at position grain (VAR_GUIDE.md section 15.13): a position its source system sends whole, naming its trades
    b = demo.batch
    for kind, key, form in (("pnl-vector", "VECTOR_ID", "PNL"), ("scenario-pv", "SCENARIO_PV_ID", "PV")):
        rows = {r["TRADE_ID"]: r for r in docs[kind] if r.get("GRAIN") == "TRADE"}
        for p in pos:
            members = [rows.get(t) for t in p["TRADE_IDS"]]
            if len(members) > 1 and all(members) and len({m["SOURCE_SYSTEM"] for m in members}) == 1:
                k, row = b.supply(p["POSITION_ID"], members[0]["SOURCE_SYSTEM"], members)
                if k == kind:
                    picks[kind] = picks[kind] + [row]
                    break
    out = {SAMPLES / f"{k}.jsonl": "".join(json.dumps(d, ensure_ascii=False, separators=(",", ":")) + "\n" for d in v) for k, v in picks.items()}
    # the pack's Sutra tests (tests/<kind>/cases.jsonl and expect.yaml: java -jar <server> sutra test config/packs/banking/market-risk). The VaR
    # figures of these pages are the engine's, added when the page is opened: a test of the stored document checks the rest.
    head = "".join(f"# {ln}".rstrip() + "\n" for ln in (ROOT / "config" / "license-header.txt").read_text(encoding="utf-8").strip().splitlines())
    expect = {"position": "noErrors: true\nnonEmpty: [trades, where]\n", "portfolio": "noErrors: true\nnonEmpty: [definition]\n"}
    for kind, key in (("position", "POSITION_ID"), ("portfolio", "PORTFOLIO_ID")):
        out[TESTS / kind / SC.FILE] = SC.text(sorted((str(d[key]), d) for d in picks[kind]))
        out[TESTS / kind / "expect.yaml"] = head + "\n" + expect[kind]
    return out


def update_manifest(testdata: Path, entries: list[dict]) -> None:
    """testdata.yaml with these entries in place of earlier ones (after the market-risk datasets), its header and the rest kept."""
    import yaml
    path = testdata / "testdata.yaml"
    text = path.read_text(encoding="utf-8")
    head_lines = []
    for ln in text.splitlines():
        if ln.startswith("#") or not ln.strip():
            head_lines.append(ln)
        else:
            break
    m = yaml.safe_load(text)
    keep = [d for d in m["datasets"] if d.get("folder") not in FOLDERS]
    at = max((i for i, d in enumerate(keep) if d.get("pack") == PACK), default=len(keep) - 1) + 1
    m["datasets"] = keep[:at] + entries + keep[at:]
    path.write_text("\n".join(head_lines) + "\n" + yaml.safe_dump(m, sort_keys=False, width=140), encoding="utf-8")


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0], formatter_class=argparse.RawDescriptionHelpFormatter,
                                epilog=__doc__.split("\n\n", 1)[1])
    p.add_argument("--testdata", default=str(ROOT / "data" / "testdata"), help="the test data the trades and desks are read from")
    p.add_argument("--out", help="where to write (default: --testdata, with its testdata.yaml entries)")
    p.add_argument("--budget", type=int, default=0, help="most trades valued per book, whole positions only (default 0: every live trade)")
    p.add_argument("--check", action="store_true", help="fail if the committed files differ from what would be written")
    p.add_argument("--pv-systems", default=",".join(SFX.PV_SYSTEMS), metavar="SYS,SYS",
                   help=f"the source systems whose trades come as scenario PVs in their own currency (default {','.join(SFX.PV_SYSTEMS)})")
    p.add_argument("--pnl-systems", default="", metavar="SYS,SYS", help="systems whose trades come as P&L vectors (USD) even if listed above")
    p.add_argument("--all-vectors", action="store_true", help="every system as P&L vectors, exactly as in 2.9.3 (no scenario PVs)")
    p.add_argument("--position-systems", default="", metavar="SYS,SYS",
                   help="systems whose records come per position (GRAIN POSITION, summed from their trades, naming them in TRADE_IDS) "
                        "rather than per trade (default: none)")
    p.add_argument("--inputs-config", action="store_true",
                   help="print the drishti.var.inputs snippet matching the data the flags produce, and stop")
    a = p.parse_args(argv)
    import yaml
    td = Path(a.testdata)
    dates = yaml.safe_load((td / "testdata.yaml").read_text(encoding="utf-8"))["business-dates"]
    pv = pv_systems(a.pv_systems, a.pnl_systems, a.all_vectors)
    by_position = tuple(s.strip().upper() for s in a.position_systems.split(",") if s.strip())
    if a.inputs_config:
        print(inputs_config(pv, by_position))
        return 0
    ds = demos(td, dates, a.budget, pv_systems=pv, position_systems=by_position)
    demo = ds[-1]
    if a.check:
        import tempfile
        with tempfile.TemporaryDirectory(prefix="drishti-var-check-") as tmp:
            write(Path(tmp), ds, with_samples=False)
            want = samples(demo, documents(demo))
            stale_samples = [str(p) for p, t in want.items() if not p.is_file() or p.read_text(encoding="utf-8") != t]
            stale = [k for k in FOLDERS if (Path(tmp) / k).is_dir() and not same_files(Path(tmp) / k, td / k)]
            stale += [f"{k} (retired, still on disk)" for k in RETIRED if (td / PACK / k).is_dir()]
        stale += stale_samples + [str(x) for x in retired_samples(want)]
        if stale:
            print(f"VaR demo data out of date ({stale[:3]}); run tools/packgen/var/make_var_data.py")
            return 1
        print("VaR demo data up to date")
        return 0
    out = Path(a.out) if a.out else td
    entries = write(out, ds)
    if not a.out:
        update_manifest(td, entries)
    b = demo.batch
    total = sum(e["bytes"] for e in entries)
    fungible = sum(1 for x in b.positions if x["TRADE_COUNT"] > 1)
    print(f"wrote {len(b.trade_vectors):,} trades in {len(b.positions):,} positions ({fungible:,} fungible, "
          f"{sum(1 for x in b.positions if x['LONG_TRADES'] and x['SHORT_TRADES'])} with buys and sells), {len(demo.nodes)} hierarchy nodes, "
          f"{len(entries)} folders, {total / 1e6:.2f} MB")
    print(f"  {len(b.vector_rows):,} trades as P&L vectors, {len(b.pv_rows):,} as scenario PVs ({', '.join(b.pv_systems) or 'none'}), "
          f"scenario FX supplied for {', '.join(SFX.CURRENCIES)}")
    for d in ds:
        print(f"  the bank on {d.cob} as the engine computes it: " + bank_figures(d) + f" ({len(d.batch.positions):,} positions, "
              f"{len(d.batch.trade_vectors):,} trades)")
    print("  the bank under the stress set: " + demo.stress().bank_figures())
    return 0


SYSTEMS = ("ENDUR", "MUREX", "CALYPSO")


def inputs_config(pv: tuple[str, ...], by_position: tuple[str, ...]) -> str:
    """The drishti.var.inputs pins matching the data (VAR_GUIDE.md section 15.13): optional, a cross-check of each record's own fields."""
    lines = ["drishti:", "  var:", "    inputs:                      # optional: a record that disagrees with its system's pin is refused (DRS-5209)"]
    for s in SYSTEMS:
        form = "pv" if s in pv else "pnl"
        grain = "position" if s in by_position else "trade"
        lines.append(f"      {s + ':':<9}{{ form: {form + ',':<4} grain: {grain} }}")
    lines.append("      default: { form: any, grain: any }")
    return "\n".join(lines)


def pv_systems(pv: str, pnl: str = "", all_vectors: bool = False) -> tuple[str, ...]:
    """The systems sent as scenario PVs: ``--pv-systems`` less ``--pnl-systems``; none with ``--all-vectors`` (every trade a P&L vector)."""
    if all_vectors:
        return ()
    keep = [s.strip().upper() for s in (pv or "").split(",") if s.strip()]
    drop = {s.strip().upper() for s in (pnl or "").split(",") if s.strip()}
    return tuple(s for s in keep if s not in drop)


def bank_figures(demo: Demo) -> str:
    """The bank's VaR 99% and ES 97.5% in USD from the files as the server reads them (vectors as sent, scenario PVs converted), and
    from the factor model's USD vectors alone (what every trade's P&L vector was before 2.9.4): the difference is the FX translation of
    the scenario-PV trades in other currencies."""
    b = demo.batch
    model = VM.add(v[:demo.n] for v in b.trade_vectors.values())
    engine = VM.add((b.engine_vectors.get(t) or v)[:demo.n] for t, v in b.trade_vectors.items())
    return (f"VaR {VM.var(engine):,.0f} ES {VM.es(engine):,.0f} (all as P&L vectors: VaR {VM.var(model):,.0f} ES {VM.es(model):,.0f})")


if __name__ == "__main__":
    sys.exit(main())
