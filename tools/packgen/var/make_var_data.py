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

"""Writes the VaR engine's demo data (VAR_DATA_MODEL.md, VAR_ENGINE.md section 5) into data/testdata/market-risk/, from the committed
trades of the business date (data/testdata/trades/*) and the core pack's desks:

    position/         positions: holdings of an instrument in a book, netting their trades (positions.py)
    pnl-vector/       one P&L vector per trade: 509 scenario P&Ls in USD, the vector column PNL (list<double> in the lake)
    scenario-set/     HS-509: the scenario days of the business date
    risk-hierarchy/   the MGMT tree (bank, division, desk, book) and the LEGAL tree (bank, legal entity, desk, book), with VaR limits
    var-node/         every node's official VaR, ES and hypothetical P&L for the last 250 business days (the backtest), its vector
                      on the business date
    portfolio/        demo portfolios of the three types (HIERARCHY, RULE, BASKET), overlapping on purpose, with their positions

Every file is dated by its documents' BUSINESS_DATE (testdata.yaml ``dated-by``): ``lake demo`` loads a folder in one run, each document
on its own date moved with the business dates. The vectors are for the last business date only (about 3 MB compressed); the history is
the nodes' figures (small). Deterministic: the same trades give the same files, byte for byte.

    python3 tools/packgen/var/make_var_data.py                    write data/testdata/market-risk/<kind>/ and their testdata.yaml entries
    python3 tools/packgen/var/make_var_data.py --check            fail if they differ from what would be written
    python3 tools/packgen/var/make_var_data.py --budget 0 --out /path/outside/the/repo     every live trade (local sizing; never committed)
"""
from __future__ import annotations

import argparse
import gzip
import json
import sys
from collections import defaultdict
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(ROOT / "tools"))
import factors as F  # noqa: E402
import positions as P  # noqa: E402
import rules as R  # noqa: E402
import varmath as VM  # noqa: E402

PACK, CONNECTOR, SET = "market-risk", "risk-store", "HS-509"
HISTORY_DAYS = 250
#: kind -> its id field (the lake key)
KEYS = {"position": "POSITION_ID", "pnl-vector": "VECTOR_ID", "scenario-set": "SCENARIO_SET_ID", "risk-hierarchy": "NODE_ID",
        "var-node": "VAR_NODE_ID", "portfolio": "PORTFOLIO_ID"}
PART_BYTES = 1_200_000                       # compressed bytes per file: the vectors are split into a few small files


def business_days(end: str, n: int) -> list[str]:
    from samplegen.dates import Calendar
    from samplegen.lake import business_days as bd
    return [d.isoformat() for d in bd(date.fromisoformat(end), n, Calendar.of("USNY"))]


def previous_day(day: str) -> str:
    return business_days(day, 2)[0]


def nice(x: float) -> int:
    """A limit as a desk would set it: two significant figures."""
    if x <= 0:
        return 0
    mag = 10 ** max(0, len(str(int(x))) - 2)
    return int(round(x / mag) * mag)


class Demo:
    """Everything the VaR demo data holds for one business date."""

    def __init__(self, testdata: Path, dates: list[str], budget: int = 60):
        self.cob = dates[-1]
        trades: dict[str, dict] = {}
        for system in ("endur", "calypso", "murex"):
            trades.update(P.replay(testdata / "trades" / system, "TRADE_ID", dates))
        desks = P.replay(testdata / "core" / "desk", "DESK_ID", dates)
        vals = P.latest(testdata / "market-risk" / "trade-valuation", "VALUATION_ID", dates)
        self.desks = desks
        self.batch = P.Batch(trades, desks, vals, self.cob, budget)
        self.scenario_dates = business_days(previous_day(self.cob), F.N_SCENARIOS)
        self.history = business_days(self.cob, HISTORY_DAYS)
        self.n = F.N_SCENARIOS
        self.nodes = self._hierarchy()
        self.book_vec = {b: VM.add(self.batch.trade_vectors[t] for p in ps for t in p["TRADE_IDS"])
                         for b, ps in self._positions_by_book().items()}
        self._position_measures()

    # ---------------------------------------------------------------------------------------------------------------- hierarchy
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

    # ---------------------------------------------------------------------------------------------------------------- positions
    def _position_measures(self) -> None:
        """Each position's VaR and ES on its own, and its component VaR in its book (the engine's rules, varmath.py)."""
        for book, ps in self._positions_by_book().items():
            vecs = {p["POSITION_ID"]: self.batch.position_vector(p, self.n) for p in ps}
            total = self.book_vec[book][:self.n]
            comp = VM.components(total, vecs)
            book_var = VM.var(total)
            for p in ps:
                v = vecs[p["POSITION_ID"]]
                p.update({"VAR_99": round(VM.var(v)), "ES_975": round(VM.es(v)), "COMPONENT_VAR": round(comp[p["POSITION_ID"]]),
                          "BOOK_VAR": round(book_var), "PCT_OF_BOOK_VAR": round(comp[p["POSITION_ID"]] / book_var, 4) if book_var else None,
                          "SCENARIO_SET_ID": SET})

    # ---------------------------------------------------------------------------------------------------------------- var-node
    def var_nodes(self) -> list[dict]:
        """Each node's figures over the last 250 business days. On the business date: exactly the engine's VaR and ES of the node's
        vector, and the vector. Before it: the node's VaR moved with its books' sizes (each book wanders slowly about today's size),
        and the hypothetical P&L of the day, the books' P&L under that day's real move (the scenario of that date, or the business
        date's own move), so it adds up from books to the bank as P&L does. An exception is a loss beyond the previous day's VaR."""
        hist = self.history
        index = {d: j for j, d in enumerate(self.scenario_dates)}
        size: dict[str, list[float]] = {}
        for b in self.book_vec:
            r = F.seeded("book-size", b)
            s, path = 1.0, []
            for _ in hist:
                path.append(s)
                s = 1.0 + 0.92 * (s - 1.0) + 0.035 * r.gauss(0, 1)
            size[b] = list(reversed(path))                          # today is 1.0; earlier days wander
        hypo_book = {b: [size[b][i] * self.book_vec[b][index.get(d, self.n)] for i, d in enumerate(hist)] for b in self.book_vec}
        standalone = {b: VM.var(v[:self.n]) for b, v in self.book_vec.items()}
        rows = []
        for nid, n in sorted(self.nodes.items()):
            vec = self.node_vector(nid)[:self.n]
            v_now, e_now = VM.var(vec), VM.es(vec)
            w = sum(standalone[b] for b in n["BOOKS"]) or 1.0
            limit = n["LIMIT_VAR"]
            prev_var = None
            positions = [p for p in self.batch.positions if p["TT_BOOK"] in n["BOOKS"]]
            trades = sum(p["TRADE_COUNT"] for p in positions)
            exceptions = 0
            for i, d in enumerate(hist):
                f = sum(standalone[b] * size[b][i] for b in n["BOOKS"]) / w
                var_d = v_now if d == self.cob else v_now * f
                es_d = e_now if d == self.cob else e_now * f
                hypo = sum(hypo_book[b][i] for b in n["BOOKS"])
                exc = prev_var is not None and hypo < -prev_var
                exceptions += exc
                row = {"VAR_NODE_ID": f"VN-{nid}", "NODE_ID": nid, "HIERARCHY": n["HIERARCHY"], "LEVEL": n["LEVEL"], "NAME": n["NAME"],
                       "PARENT_ID": n["PARENT_ID"], "BUSINESS_DATE": d, "SCENARIO_SET_ID": SET, "VAR_99": round(var_d), "ES_975": round(es_d),
                       "HYPO_PNL": round(hypo), "EXCEPTION": bool(exc), "PREVIOUS_VAR": round(prev_var) if prev_var is not None else None,
                       "LIMIT": limit, "LIMIT_USE": round(var_d / limit, 4) if limit else None,
                       "POSITION_COUNT": len(positions), "TRADE_COUNT": trades, "BATCH_ID": f"B-{d}-01", "OFFICIAL": True}
                if d == self.cob:
                    row["PNL"] = vec
                    row["EXCEPTIONS_250"] = exceptions
                rows.append(row)
                prev_var = var_d
        return rows

    def limits(self) -> None:
        """A VaR limit on every node: its VaR today times a headroom (most green, a few close to the limit), two figures."""
        tight = {"DESK-STRUCT", "BOOK-RATES-2", "BOOK-FX-3", "LE-TKY"}
        for nid, n in self.nodes.items():
            v = VM.var(self.node_vector(nid)[:self.n])
            r = F.seeded("limit", nid)
            head = (1.04 + 0.06 * r.random()) if nid.split("~")[1] in tight else (1.2 + 0.7 * r.random())
            n["LIMIT_VAR"] = nice(v * head)

    def hierarchy_rows(self) -> list[dict]:
        return [{**{k: v for k, v in n.items()}, "BUSINESS_DATE": self.cob, "VALID_FROM": "2026-01-02", "VALID_TO": None}
                for _, n in sorted(self.nodes.items())]

    def scenario_set(self) -> dict:
        return {"SCENARIO_SET_ID": SET, "BUSINESS_DATE": self.cob, "SCENARIO_DATES": self.scenario_dates, "SCENARIO_COUNT": self.n,
                "WINDOW_START": self.scenario_dates[0], "WINDOW_END": self.scenario_dates[-1], "HORIZON_DAYS": 1, "REVALUATION": "full",
                "WEIGHTS": None, "SOURCE_BATCH": f"B-{self.cob}-01", "DESCRIPTION": "Historical simulation, 509 business days, equal weights"}


# ---------------------------------------------------------------------------------------------------------------------- portfolios
def portfolio_specs(demo: Demo) -> list[dict]:
    """Demo portfolios of each type, over the same positions, overlapping on purpose (a basket inside a desk, a rule across desks)."""
    pos = demo.batch.positions
    rates = [p for p in pos if p["DESK"] == "DESK-RATES"]
    fx = [p for p in pos if p["DESK"] == "DESK-FX"]
    credit = [p for p in pos if p["DESK"] == "DESK-CREDIT"]
    mixed = sorted([p for p in pos if p["LONG_TRADES"] and p["SHORT_TRADES"]], key=lambda p: p["POSITION_ID"])
    basket1 = [{"POSITION_ID": p["POSITION_ID"]} for p in (rates[:4] + fx[:3])] + [{"TRADE_ID": p["TRADE_IDS"][0]} for p in credit[:2]]
    basket2 = [{"POSITION_ID": p["POSITION_ID"]} for p in mixed[:6]] + [{"TRADE_ID": t} for p in rates[4:6] for t in p["TRADE_IDS"][:1]]
    return [
        {"PORTFOLIO_ID": "PF-RATES-DESK", "NAME": "Rates desk (New York)", "OWNER": "rates-risk", "TYPE": "HIERARCHY",
         "DEFINITION": {"NODE": "MGMT~DESK-RATES"}, "ROLLS_UP_TO": {"KIND": "desk", "ID": "DESK-RATES"}, "LIMIT_VAR": None},
        {"PORTFOLIO_ID": "PF-COMM-BOOK-1", "NAME": "Commodities book 1", "OWNER": "comm-risk", "TYPE": "HIERARCHY",
         "DEFINITION": {"NODE": "MGMT~BOOK-COMM-1"}, "ROLLS_UP_TO": {"KIND": "book", "ID": "BOOK-COMM-1"}, "LIMIT_VAR": None},
        {"PORTFOLIO_ID": "PF-USD-RATES", "NAME": "USD rates exposure", "OWNER": "group-risk", "TYPE": "RULE",
         "DEFINITION": {"RULE": "PRODUCT_GROUP in ('Rates', 'Fixed income', 'Money markets') and CURRENCY == 'USD'"},
         "ROLLS_UP_TO": {"KIND": "division", "ID": "DIV-MARKETS"}, "LIMIT_VAR": None},
        {"PORTFOLIO_ID": "PF-EUR-ALL", "NAME": "Everything in EUR", "OWNER": "group-risk", "TYPE": "RULE",
         "DEFINITION": {"RULE": "CURRENCY == 'EUR'"}, "ROLLS_UP_TO": {"KIND": "bank", "ID": "BANK"}, "LIMIT_VAR": None},
        {"PORTFOLIO_ID": "PF-SHORT-RISK", "NAME": "Short positions, credit and rates", "OWNER": "group-risk", "TYPE": "RULE",
         "DEFINITION": {"RULE": "DIRECTION == 'SHORT' and PRODUCT_GROUP in ('Credit', 'Rates')"},
         "ROLLS_UP_TO": {"KIND": "division", "ID": "DIV-MARKETS"}, "LIMIT_VAR": None},
        {"PORTFOLIO_ID": "PF-ASHA-UNWIND", "NAME": "Unwind candidates (Asha)", "OWNER": "asha", "TYPE": "BASKET",
         "DEFINITION": {"MEMBERS": basket1}, "ROLLS_UP_TO": {"KIND": "division", "ID": "DIV-MARKETS"}, "LIMIT_VAR": None},
        {"PORTFOLIO_ID": "PF-NETTED-FLOW", "NAME": "Two-way client flow", "OWNER": "markets-coo", "TYPE": "BASKET",
         "DEFINITION": {"MEMBERS": basket2}, "ROLLS_UP_TO": {"KIND": "bank", "ID": "BANK"}, "LIMIT_VAR": None},
    ]


def members(demo: Demo, spec: dict) -> dict[str, list[str]]:
    """The portfolio's positions on the date, each with the trades of it the portfolio holds (all of them, or the named ones)."""
    pos = demo.batch.positions
    d = spec["DEFINITION"]
    if spec["TYPE"] == "HIERARCHY":
        books = set(demo.nodes[d["NODE"]]["BOOKS"])
        return {p["POSITION_ID"]: list(p["TRADE_IDS"]) for p in pos if p["TT_BOOK"] in books}
    if spec["TYPE"] == "RULE":
        rule = R.Rule(d["RULE"])
        return {p["POSITION_ID"]: list(p["TRADE_IDS"]) for p in pos if rule.matches(p)}
    out: dict[str, list[str]] = {}
    by_trade = {t: p for p in pos for t in p["TRADE_IDS"]}
    by_id = {p["POSITION_ID"]: p for p in pos}
    for m in d["MEMBERS"]:
        if "POSITION_ID" in m and m["POSITION_ID"] in by_id:
            out[m["POSITION_ID"]] = list(by_id[m["POSITION_ID"]]["TRADE_IDS"])
        elif "TRADE_ID" in m and m["TRADE_ID"] in by_trade:
            p = by_trade[m["TRADE_ID"]]
            have = out.setdefault(p["POSITION_ID"], [])
            if m["TRADE_ID"] not in have and len(have) < len(p["TRADE_IDS"]):
                have.append(m["TRADE_ID"])
    return out


def portfolio_rows(demo: Demo) -> list[dict]:
    by_id = {p["POSITION_ID"]: p for p in demo.batch.positions}
    tv = demo.batch.trade_vectors
    n = demo.n
    rows = []
    for spec in portfolio_specs(demo):
        mem = members(demo, spec)
        vecs = {pid: VM.add(tv[t][:n] for t in ts) for pid, ts in sorted(mem.items())}
        total = VM.add(vecs.values()) if vecs else [0.0] * n
        v, e = VM.var(total), VM.es(total)
        comp = VM.components(total, vecs) if vecs else {}
        lines = []
        for pid, ts in sorted(mem.items()):
            p = by_id[pid]
            q = sum(x["QUANTITY"] for x in p["TRADE_QUANTITIES"] if x["TRADE_ID"] in ts)
            share = q / p["QUANTITY"] if p["QUANTITY"] else (1.0 if len(ts) == len(p["TRADE_IDS"]) else 0.0)
            lines.append({"POSITION_ID": pid, "INSTRUMENT": p["INSTRUMENT"], "DIRECTION": "LONG" if q > 0 else "SHORT" if q < 0 else "FLAT",
                          "QUANTITY": q, "MARKET_VALUE": round(p["MARKET_VALUE"] * share) if len(ts) < len(p["TRADE_IDS"]) else p["MARKET_VALUE"],
                          "CURRENCY": p["CURRENCY"], "TT_BOOK": p["TT_BOOK"], "DESK": p["DESK"], "PRODUCT_GROUP": p["PRODUCT_GROUP"],
                          "PRODUCT_TYPE": p["PRODUCT_TYPE"], "TRADES": len(ts), "PARTIAL": len(ts) < len(p["TRADE_IDS"]),
                          "VAR_99": round(VM.var(vecs[pid])), "COMPONENT_VAR": round(comp[pid]),
                          "PCT_OF_VAR": round(comp[pid] / v, 4) if v else None})
        lines.sort(key=lambda x: -x["COMPONENT_VAR"])

        def summary(field):
            groups: dict[str, list[dict]] = defaultdict(list)
            for ln in lines:
                groups[ln[field]].append(ln)
            return [{"KEY": k, "POSITIONS": len(g), "NET_MV": sum(x["MARKET_VALUE"] for x in g),
                     "GROSS_MV": sum(abs(x["MARKET_VALUE"]) for x in g), "COMPONENT_VAR": sum(x["COMPONENT_VAR"] for x in g)}
                    for k, g in sorted(groups.items(), key=lambda kv: -sum(x["COMPONENT_VAR"] for x in kv[1]))]
        limit = nice(v * (1.3 + 0.4 * F.seeded("pf-limit", spec["PORTFOLIO_ID"]).random()))
        rows.append({**spec, "LIMIT_VAR": limit, "BUSINESS_DATE": demo.cob, "VALID_FROM": "2026-01-02", "VALID_TO": None,
                     "SCENARIO_SET_ID": SET, "POSITION_COUNT": len(lines), "TRADE_COUNT": sum(x["TRADES"] for x in lines),
                     "LONG_COUNT": sum(1 for x in lines if x["DIRECTION"] == "LONG"),
                     "SHORT_COUNT": sum(1 for x in lines if x["DIRECTION"] == "SHORT"),
                     "GROSS_MV": sum(abs(x["MARKET_VALUE"]) for x in lines), "NET_MV": sum(x["MARKET_VALUE"] for x in lines),
                     "VAR_99": round(v), "ES_975": round(e), "LIMIT_USE": round(v / limit, 4) if limit else None,
                     "POSITIONS": lines, "BY_PRODUCT": summary("PRODUCT_TYPE"), "BY_CURRENCY": summary("CURRENCY"),
                     "BY_DIRECTION": summary("DIRECTION"), "TOP_CONTRIBUTORS": lines[:10],
                     "ALL_TRADES": {"KIND": "portfolio-trades", "ID": spec["DEFINITION"].get("NODE", "").split("~")[-1]}
                     if spec["TYPE"] == "HIERARCHY" else None})
    return rows


# ---------------------------------------------------------------------------------------------------------------------- writing
def _gz_files(folder: Path, stem: str, lines: list[str]) -> list[Path]:
    """Deterministic gzip, split into files of about PART_BYTES compressed."""
    folder.mkdir(parents=True, exist_ok=True)
    for old in folder.glob("*.jsonl.gz"):
        old.unlink()
    parts: list[list[str]] = [[]]
    size = 0
    for ln in lines:
        if size > PART_BYTES * 3 and parts[-1]:                    # about 3:1 compression on these files
            parts.append([])
            size = 0
        parts[-1].append(ln)
        size += len(ln)
    out = []
    for i, chunk in enumerate(parts, 1):
        name = f"{stem}.jsonl.gz" if len(parts) == 1 else f"{stem}.part-{i:03d}.jsonl.gz"
        path = folder / name
        with path.open("wb") as raw, gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0, compresslevel=9) as gz:
            gz.write(("\n".join(chunk) + "\n").encode("utf-8"))
        out.append(path)
    return out


def dumps(d: dict) -> str:
    return json.dumps(d, separators=(",", ":"), ensure_ascii=False)


def documents(demo: Demo) -> dict[str, list[dict]]:
    demo.limits()
    return {"risk-hierarchy": demo.hierarchy_rows(), "scenario-set": [demo.scenario_set()],
            "position": sorted(demo.batch.positions, key=lambda p: (p["LEGAL_ENTITY"], p["DESK"], p["TT_BOOK"], p["POSITION_ID"])),
            # sorted as the lake keeps them (VAR_DATA_MODEL.md section 7): a desk's and a book's vectors are contiguous
            "pnl-vector": sorted(demo.batch.vector_rows, key=lambda r: (r["LEGAL_ENTITY"], r["DESK"], r["TT_BOOK"], r["POSITION_ID"], r["TRADE_ID"])),
            "var-node": demo.var_nodes(), "portfolio": portfolio_rows(demo)}


def write(out: Path, demo: Demo, with_samples: bool = True) -> list[dict]:
    """Writes the folders (and the pack's reference samples); returns their testdata.yaml entries."""
    entries = []
    docs = documents(demo)
    if with_samples:
        for path, text in samples(docs).items():
            path.parent.mkdir(parents=True, exist_ok=True)
            if not path.is_file() or path.read_text(encoding="utf-8") != text:
                path.write_text(text, encoding="utf-8")
    for kind, rows in docs.items():
        dates = sorted({r["BUSINESS_DATE"] for r in rows})
        stem = f"cob-{dates[-1]}" if len(dates) == 1 else f"history-{dates[0]}-to-{dates[-1]}"
        files = _gz_files(out / PACK / kind, stem, [dumps(r) for r in rows])
        entries.append({"folder": f"{PACK}/{kind}", "pack": PACK, "kind": kind, "connector": CONNECTOR, "key": KEYS[kind],
                        "dated-by": "BUSINESS_DATE", "first-date": dates[0], "last-date": dates[-1], "documents": len(rows),
                        "files": [f.name for f in files], "bytes": sum(f.stat().st_size for f in files)})
    return entries


FOLDERS = tuple(f"{PACK}/{k}" for k in KEYS)
SAMPLES = ROOT / "config" / "packs" / PACK / "samples"


def samples(docs: dict[str, list[dict]]) -> dict[Path, str]:
    """The few documents of each kind the pack's samples/ keep for its README and docs (never served from there: the lake is)."""
    pos = docs["position"]
    mixed = next((p for p in pos if p["LONG_TRADES"] and p["SHORT_TRADES"]), pos[0])
    rates = next((p for p in pos if p["DESK"] == "DESK-RATES"), pos[-1])
    picks = {"position": [mixed, rates] if rates is not mixed else [mixed],
             "pnl-vector": [r for r in docs["pnl-vector"] if r["POSITION_ID"] == mixed["POSITION_ID"]][:1],
             "scenario-set": docs["scenario-set"],
             "risk-hierarchy": [n for n in docs["risk-hierarchy"] if n["NODE_ID"] in ("MGMT~DESK-RATES", "LEGAL~LE-NY")],
             "var-node": [r for r in docs["var-node"] if r["NODE_ID"] == "MGMT~DESK-RATES"][-2:],
             "portfolio": [r for r in docs["portfolio"] if r["PORTFOLIO_ID"] in ("PF-USD-RATES", "PF-ASHA-UNWIND")]}
    return {SAMPLES / f"{k}.jsonl": "".join(json.dumps(d, ensure_ascii=False, separators=(",", ":")) + "\n" for d in v) for k, v in picks.items()}


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
    p.add_argument("--budget", type=int, default=60, help="most trades valued per book, whole positions only (default 60; 0: every live trade)")
    p.add_argument("--check", action="store_true", help="fail if the committed files differ from what would be written")
    a = p.parse_args(argv)
    import yaml
    td = Path(a.testdata)
    dates = yaml.safe_load((td / "testdata.yaml").read_text(encoding="utf-8"))["business-dates"]
    demo = Demo(td, dates, a.budget)
    if a.check:
        import tempfile
        with tempfile.TemporaryDirectory(prefix="drishti-var-check-") as tmp:
            write(Path(tmp), demo, with_samples=False)
            stale_samples = [str(p) for p, t in samples(documents(demo)).items() if not p.is_file() or p.read_text(encoding="utf-8") != t]
            stale = [f"{k}/{f.name}" for k in FOLDERS for f in sorted((Path(tmp) / k).glob("*.jsonl.gz"))
                     if not (td / k / f.name).is_file() or gzip.decompress((td / k / f.name).read_bytes()) != gzip.decompress(f.read_bytes())]
        stale += stale_samples
        if stale:
            print(f"VaR demo data out of date ({stale[:3]}); run tools/packgen/var/make_var_data.py")
            return 1
        print("VaR demo data up to date")
        return 0
    out = Path(a.out) if a.out else td
    entries = write(out, demo)
    if not a.out:
        update_manifest(td, entries)
    b = demo.batch
    total = sum(e["bytes"] for e in entries)
    print(f"wrote {len(b.positions):,} positions netting {len(b.trade_vectors):,} trades ({sum(1 for x in b.positions if x['LONG_TRADES'] and x['SHORT_TRADES'])} "
          f"with buys and sells), {len(demo.nodes)} hierarchy nodes over {HISTORY_DAYS} days, {len(entries)} folders, {total / 1e6:.2f} MB")
    return 0


if __name__ == "__main__":
    sys.exit(main())
