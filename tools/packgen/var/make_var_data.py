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
date (data/testdata/trades/*) and the core pack's desks, as an upstream risk batch would deliver them:

    pnl-vector/       one P&L vector per live trade: 509 scenario P&Ls in USD, the vector column PNL (list<double> in the lake), with the
                      trade's terms the server derives positions from (SECURITY_ID, TRADE_STATUS, MATURITY_DATE, product, book)
    scenario-pv/      2.9.4: the Endur trades' scenario PVs instead (scenario_fx.py): each trade's PV under the 509 scenarios and its base
                      PV, in its own currency, the vector column SCENARIO_PV; the server turns them into USD P&L when it loads the date
    fx-scenario/      2.9.4: the scenario FX the batch supplies: per currency, USD per unit today and under each scenario
    scenario-set/     HS-509: the scenario days of the business date
    risk-hierarchy/   the MGMT tree (bank, division, desk, book) and the LEGAL tree (bank, legal entity, desk, book), with VaR limits
    portfolio/        portfolio definitions of the three types (HIERARCHY, RULE, BASKET), overlapping on purpose: no figures

No position, no VaR and no node history is written: positions are derived by the server from the trades and the pack's rules (VAR_GUIDE.md
section 3.7), every figure is computed when asked, and a node's history is its official end-of-day captures. Every live trade is valued
(statuses NEW, VALIDATED, AMENDED; maturity on or after the date; VAR_GUIDE.md section 23.1 question 1), about 9,600 trades. Every file is
dated by its documents' BUSINESS_DATE (testdata.yaml ``dated-by``); the vectors are split into files of about 1.2 MB. Deterministic:
the same trades give the same files, byte for byte.

    python3 tools/packgen/var/make_var_data.py                    write data/testdata/market-risk/<kind>/ and their testdata.yaml entries
    python3 tools/packgen/var/make_var_data.py --check            fail if they differ from what would be written
    python3 tools/packgen/var/make_var_data.py --budget 60 --out /path/outside/the/repo     a sample of every book (local sizing)
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
import scenario_fx as SFX  # noqa: E402
import portfolios as PF  # noqa: E402
import positions as P  # noqa: E402
import varmath as VM  # noqa: E402

PACK, CONNECTOR, SET = "market-risk", "risk-store", "HS-509"
#: kind -> its id field (the lake key)
KEYS = {"pnl-vector": "VECTOR_ID", "scenario-pv": "SCENARIO_PV_ID", "fx-scenario": "FX_SCENARIO_ID", "scenario-set": "SCENARIO_SET_ID",
        "risk-hierarchy": "NODE_ID", "portfolio": "PORTFOLIO_ID"}
#: the folders this generator used to write and no longer does (removed when it writes)
RETIRED = ("position", "var-node")
PART_BYTES = 1_200_000                       # compressed bytes per file: the vectors are split into a few small files
PACK_YAML = ROOT / "config" / "packs" / PACK / "pack.yaml"


def business_days(end: str, n: int) -> list[str]:
    from samplegen.dates import Calendar
    from samplegen.lake import business_days as bd
    return [d.isoformat() for d in bd(date.fromisoformat(end), n, Calendar.of("USNY"))]


def previous_day(day: str) -> str:
    return business_days(day, 2)[0]


nice = PF.nice


class Demo:
    """Everything the VaR demo data holds for one business date."""

    def __init__(self, testdata: Path, dates: list[str], budget: int = 0, rules: P.Rules | None = None, pv_systems=None):
        self.cob = dates[-1]
        trades: dict[str, dict] = {}
        for system in ("endur", "calypso", "murex"):
            trades.update(P.replay(testdata / "trades" / system, "TRADE_ID", dates))
        desks = P.replay(testdata / "core" / "desk", "DESK_ID", dates)
        vals = P.latest(testdata / "market-risk" / "trade-valuation", "VALUATION_ID", dates)
        self.desks = desks
        self.rules = rules or P.Rules.of_pack(PACK_YAML)
        market = F.Market()
        self.fx = SFX.Fx(market, SFX.today_rates(P.replay(testdata / "market-data" / "fx-spot", "PAIR", dates)))
        self.batch = P.Batch(trades, desks, vals, self.cob, self.rules, budget, market=market, fx=self.fx, pv_systems=pv_systems)
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

    def hierarchy_rows(self) -> list[dict]:
        return [{**{k: v for k, v in n.items()}, "BUSINESS_DATE": self.cob, "VALID_FROM": "2026-01-02", "VALID_TO": None}
                for _, n in sorted(self.nodes.items())]

    def scenario_set(self) -> dict:
        return {"SCENARIO_SET_ID": SET, "BUSINESS_DATE": self.cob, "SCENARIO_DATES": self.scenario_dates, "SCENARIO_COUNT": self.n,
                "WINDOW_START": self.scenario_dates[0], "WINDOW_END": self.scenario_dates[-1], "HORIZON_DAYS": 1, "REVALUATION": "full",
                "WEIGHTS": None, "SOURCE_BATCH": f"B-{self.cob}-01", "DESCRIPTION": "Historical simulation, 509 business days, equal weights"}


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
            # sorted as a book's trades are contiguous (the engine sorts a date in memory anyway: the lake's order does not matter)
            "pnl-vector": sorted(demo.batch.vector_rows, key=lambda r: (r["LEGAL_ENTITY"], r["DESK"], r["TT_BOOK"], r["TRADE_ID"])),
            "scenario-pv": sorted(demo.batch.pv_rows, key=lambda r: (r["LEGAL_ENTITY"], r["DESK"], r["TT_BOOK"], r["TRADE_ID"])),
            "fx-scenario": demo.fx.rows(demo.cob, SET, f"B-{demo.cob}-01"),
            "portfolio": PF.rows(demo)}


def write(out: Path, demo: Demo, with_samples: bool = True) -> list[dict]:
    """Writes the folders (and the pack's reference samples); returns their testdata.yaml entries."""
    entries = []
    docs = documents(demo)
    if with_samples:
        want = samples(demo, docs)
        for path, text in want.items():
            path.parent.mkdir(parents=True, exist_ok=True)
            if not path.is_file() or path.read_text(encoding="utf-8") != text:
                path.write_text(text, encoding="utf-8")
        for path in retired_samples(want):
            path.unlink()
    for kind in RETIRED:
        folder = out / PACK / kind
        if folder.is_dir():
            for f in folder.glob("*.jsonl.gz"):
                f.unlink()
            folder.rmdir()
    for kind, rows in docs.items():
        dates = sorted({r["BUSINESS_DATE"] for r in rows})
        stem = f"cob-{dates[-1]}" if len(dates) == 1 else f"history-{dates[0]}-to-{dates[-1]}"
        files = _gz_files(out / PACK / kind, stem, [dumps(r) for r in rows])
        entries.append({"folder": f"{PACK}/{kind}", "pack": PACK, "kind": kind, "connector": CONNECTOR, "key": KEYS[kind],
                        "dated-by": "BUSINESS_DATE", "first-date": dates[0], "last-date": dates[-1], "documents": len(rows),
                        "files": [f.name for f in files], "bytes": sum(f.stat().st_size for f in files)})
    return entries


FOLDERS = tuple(f"{PACK}/{k}" for k in (*KEYS, *RETIRED))
SAMPLES = ROOT / "config" / "packs" / PACK / "samples"
TESTS = ROOT / "config" / "packs" / PACK / "tests"


def retired_samples(want: dict[Path, str] | None = None) -> list[Path]:
    """Samples and Sutra tests on disk that are no longer written: the retired stored kinds', and earlier picks' tests."""
    out = [SAMPLES / "var-node.jsonl"] + sorted(TESTS.glob("var-node/*"))
    if want is not None:
        out += [f for kind in ("position", "portfolio") for f in sorted((TESTS / kind).glob("*.json")) if f not in want]
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
             "portfolio": [r for r in docs["portfolio"] if r["PORTFOLIO_ID"] in ("PF-RATES-DESK", "PF-USD-RATES", "PF-ASHA-UNWIND")]}
    out = {SAMPLES / f"{k}.jsonl": "".join(json.dumps(d, ensure_ascii=False, separators=(",", ":")) + "\n" for d in v) for k, v in picks.items()}
    # the pack's Sutra tests (tests/<kind>/<id>.json and expect.yaml: java -jar <server> sutra test config/packs/market-risk). The VaR
    # figures of these pages are the engine's, added when the page is opened: a test of the stored document checks the rest.
    head = "".join(f"# {ln}".rstrip() + "\n" for ln in (ROOT / "config" / "license-header.txt").read_text(encoding="utf-8").strip().splitlines())
    expect = {"position": "noErrors: true\nnonEmpty: [trades, where]\n", "portfolio": "noErrors: true\nnonEmpty: [definition]\n"}
    for kind, key in (("position", "POSITION_ID"), ("portfolio", "PORTFOLIO_ID")):
        for d in picks[kind]:
            out[TESTS / kind / f"{d[key]}.json"] = json.dumps(d, ensure_ascii=False, indent=1) + "\n"
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
    a = p.parse_args(argv)
    import yaml
    td = Path(a.testdata)
    dates = yaml.safe_load((td / "testdata.yaml").read_text(encoding="utf-8"))["business-dates"]
    demo = Demo(td, dates, a.budget, pv_systems=pv_systems(a.pv_systems, a.pnl_systems, a.all_vectors))
    if a.check:
        import tempfile
        with tempfile.TemporaryDirectory(prefix="drishti-var-check-") as tmp:
            write(Path(tmp), demo, with_samples=False)
            want = samples(demo, documents(demo))
            stale_samples = [str(p) for p, t in want.items() if not p.is_file() or p.read_text(encoding="utf-8") != t]
            stale = [f"{k}/{f.name}" for k in FOLDERS for f in sorted((Path(tmp) / k).glob("*.jsonl.gz"))
                     if not (td / k / f.name).is_file() or gzip.decompress((td / k / f.name).read_bytes()) != gzip.decompress(f.read_bytes())]
            stale += [f"{k} (retired, still on disk)" for k in RETIRED if (td / PACK / k).is_dir()]
        stale += stale_samples + [str(x) for x in retired_samples(want)]
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
    fungible = sum(1 for x in b.positions if x["TRADE_COUNT"] > 1)
    print(f"wrote {len(b.trade_vectors):,} trades in {len(b.positions):,} positions ({fungible:,} fungible, "
          f"{sum(1 for x in b.positions if x['LONG_TRADES'] and x['SHORT_TRADES'])} with buys and sells), {len(demo.nodes)} hierarchy nodes, "
          f"{len(entries)} folders, {total / 1e6:.2f} MB")
    print(f"  {len(b.vector_rows):,} trades as P&L vectors, {len(b.pv_rows):,} as scenario PVs ({', '.join(b.pv_systems) or 'none'}), "
          f"scenario FX supplied for {', '.join(SFX.CURRENCIES)}")
    print("  the bank as the engine computes it: " + bank_figures(demo))
    return 0


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
