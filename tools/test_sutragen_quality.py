#!/usr/bin/env python3
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

"""The quality of generated Sutras, measured against the hand-built ones.

Synthetic Endur-, Calypso- and Murex-like nested trades (written here, under data/tmp, never committed: legs with cash flows and
resets, events, a nested settlement record, a list of reporting regimes) go through `pack new`'s generator
(tools/packgen/pack_from_jsonl.py, the server's auto-designer); each generated Sutra is compared with the hand-built Sutra of the
same product (modules/banking/dmod/packs/trading/sutras/<system>/<product>.v1.sutra.yaml, its fragments expanded) on structure:

  title     the id and the counterparty link
  kinds     every panel kind the hand-built screen uses (kv, tabs, table, line, hbar, timeline, provenance, links)
  tables    every array it shows as rows (legs, cash flows, resets, events)
  charts    every array it charts (the notional profile and fixings, the payment ladder, the events)
  strip     every key figure (currency, side, notional, quantity, trade date, maturity, status, portfolio)
  keys      the function key that opens the counterparty

The score is the share of those items the generated screen has; the bar is BAR. The generated pack must also pass `sutra lint`
and `sutra test`. Needs the server jar (kernel/drishti-server/target/drishti-server-*-exec.jar).

    python3 tools/test_sutragen_quality.py         # the score, product by product, with what is missing
    python3 -m pytest tools/test_sutragen_quality.py -q
"""
from __future__ import annotations

import argparse
import datetime as dt
import glob
import json
import pathlib
import random
import re
import shutil
import sys
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools" / "packgen"))
sys.path.insert(0, str(ROOT / "tools"))
import yaml  # noqa: E402  (PyYAML)

HAND = ROOT / "modules" / "banking" / "dmod" / "packs" / "trading" / "sutras"
KIT = ROOT / "config" / "ui-kit" / "fragments"
WORK = ROOT / "data" / "tmp" / "sutragen-quality"
BAR = 0.90

# (source system, product type, the hand-built Sutra, what the product looks like)
PRODUCTS = [
    ("ENDUR", "ComSwap", "endur/endur-comswap", "comswap"),
    ("CALYPSO", "CDS_SINGLE", "calypso/cds-single", "cds"),
    ("CALYPSO", "FX_OPTION", "calypso/fx-option", "fxo"),
    ("CALYPSO", "AMORT_BOND", "calypso/amort-bond", "bond"),
    ("MUREX", "IRS_FIXFLOAT", "murex/irs-fixfloat", "irs"),
]
COUNTERPARTIES = ["CP-HARBORPT", "CP-NORTHWIND", "CP-BLUEFIN", "CP-REDWOOD", "CP-SILVERLK"]
BOOKS = ["BOOK-COMM-2", "BOOK-RATES-1", "BOOK-CREDIT-3", "BOOK-FX-1"]


# ---------------------------------------------------------------------------------------------------------- the fixtures

def _months(start: dt.date, n: int, step: int = 1) -> list[tuple[dt.date, dt.date]]:
    out, d = [], start
    for _ in range(n):
        m = d.month - 1 + step
        e = dt.date(d.year + m // 12, m % 12 + 1, 1)
        out.append((d, e - dt.timedelta(days=1)))
        d = e
    return out


def _leg(rnd, tid: int, no: int, kind: str, ccy: str, notional: float, periods, fixed: float | None, index: str, amortise=False):
    flows = []
    for j, (s, e) in enumerate(periods, 1):
        resets = []
        if kind == "FLOAT":
            resets = [{"RESET_ID": str(k), "ACCRUAL": 1.0, "START_DATE": s.isoformat(), "END_DATE": e.isoformat(),
                       "RESET_DATE": s.isoformat(), "RATE": round(fixed * rnd.uniform(0.9, 1.1), 4) if s < dt.date(2026, 11, 1) else ""}
                      for k in range(1, 3)]
        n = round(notional * (1 - (j - 1) / (len(periods) + 1)) if amortise else notional, 2)
        flows.append({"FLOW_ID": f"{tid}{no}{j:03d}", "FLOW_TYPE": "Fixed" if kind == "FIXED" else "Floating", "NOTIONAL": n,
                      "RATE": fixed if kind == "FIXED" else "", "FLOAT_SPREAD": "" if kind == "FIXED" else round(rnd.uniform(0, 0.002), 5),
                      "START_DATE": s.isoformat(), "END_DATE": e.isoformat(), "PAYMENT_DATE": (e + dt.timedelta(days=5)).isoformat(),
                      "RATE_DETERMINATION_DATE": e.isoformat() if kind == "FLOAT" else "", "STRIKE_PRICE": fixed if kind == "FIXED" else 0.0,
                      "RESETS": resets})
    return {"LEG_ID": str(no), "LEG_TYPE": kind, "LEG_ECONOMIC_TYPE": "Pay" if no == 1 else "Receive", "CURRENCY": ccy,
            "FLOAT_SPREAD": "", "PRICE_UNIT": "", "NOTIONAL": notional, "DISCOUNT_INDEX": f"CRV-{ccy}-OIS", "SETTLEMENT_TYPE": "Cash Settlement",
            "RESET_CONVENTION": "" if kind == "FIXED" else "Average", "DAILY_VOLUME": "",
            "INDEX": {"NAME": "" if kind == "FIXED" else index, "GROUP": "" if kind == "FIXED" else index.split("-")[0], "SUB_GROUP": ""},
            "CASH_FLOWS": flows}


def trade(rnd, system: str, product: str, shape: str, i: int) -> dict:
    """One synthetic trade in the common UPPER_SNAKE shape of the three booking systems (no real values)."""
    tid = {"ENDUR": 1_000_000, "CALYPSO": 3_000_000, "MUREX": 5_000_000}[system] + i
    ccy = rnd.choice(["USD", "CAD", "EUR", "GBP"])
    notional = float(rnd.choice([5, 10, 25, 39, 50, 100])) * 1_000_000
    start = dt.date(2025, rnd.randint(1, 12), 1)
    years = rnd.choice([2, 3, 5])
    quantity, price, strike, option = None, None, None, None
    if shape == "comswap":
        price = round(rnd.uniform(60, 2400), 4)
        quantity = float(rnd.choice([12000, 36000, 72000]))
        months = _months(start, years * 12)
        legs = [_leg(rnd, tid, 1, "FIXED", ccy, notional, months, price, ""), _leg(rnd, tid, 2, "FLOAT", ccy, notional, months, price, "NYMEX-WTI")]
        for leg in legs:
            leg["PRICE_UNIT"] = "BBL"
    elif shape == "cds":
        yearly = _months(start, years, 12)
        legs = [_leg(rnd, tid, 1, "FIXED", ccy, notional, yearly, 0.01, ""), _leg(rnd, tid, 2, "FLOAT", ccy, notional, yearly[-1:], 0.4, "RECOVERY-SNR")]
        legs[1]["LEG_ECONOMIC_TYPE"] = "Protection"
    elif shape == "fxo":
        strike, option = round(rnd.uniform(1.05, 1.45), 4), rnd.choice(["Call", "Put"])
        expiry = _months(start, rnd.choice([3, 6, 12]))[-1:]
        legs = [_leg(rnd, tid, 1, "FIXED", ccy, notional, expiry, strike, "")]
    elif shape == "bond":
        price = round(rnd.uniform(95, 104), 3)
        legs = [_leg(rnd, tid, 1, "FIXED", ccy, notional, _months(start, years * 2, 6), round(rnd.uniform(0.02, 0.06), 4), "", amortise=True)]
    else:   # irs
        quarters = _months(start, years * 4, 3)
        legs = [_leg(rnd, tid, 1, "FIXED", ccy, notional, quarters, round(rnd.uniform(0.02, 0.05), 4), ""),
                _leg(rnd, tid, 2, "FLOAT", ccy, notional, quarters, 0.035, f"{ccy}-SOFR-3M")]
    events, n = [], 0
    for leg in legs:
        for f in leg["CASH_FLOWS"][:6]:
            n += 1
            events.append({"LEG_ID": leg["LEG_ID"], "EVENT_ID": f"9{tid}{n:03d}", "EVENT_NUMBER": n, "EVENT_TYPE": "CASH_SETTLEMENT",
                           "PAYMENT_TYPE": "Coupon" if leg["LEG_TYPE"] == "FIXED" else "Floating", "EVENT_DATE": f["PAYMENT_DATE"],
                           "AMOUNT": round(rnd.uniform(-1, 1) * notional / 100, 2), "CURRENCY": ccy})
    maturity = max(f["END_DATE"] for leg in legs for f in leg["CASH_FLOWS"])
    cp = rnd.choice(COUNTERPARTIES)
    return {
        "SOURCE_SYSTEM": system, "SOURCE_UPDATE_TIME": "", "TRADE_DATE": (start - dt.timedelta(days=rnd.randint(5, 40))).isoformat(),
        "INPUT_DATE": "", "TRADE_ID": str(tid), "TRADE_STATUS": rnd.choice(["VALIDATED", "VALIDATED", "MATURED", "AMENDED"]),
        "TRADE_ACTION": "", "TRADE_VERSION": str(rnd.randint(1, 4)), "TRANSACTION_ID": str(700_000_000 + i), "PACKAGE_ID": "",
        "QUANTITY": quantity, "PRICE": price, "BUY_SELL_CODE": rnd.choice(["B", "S"]), "PRODUCT_TYPE": product,
        "PRODUCT_SUB_TYPE": {"comswap": "ENGY-SWAP"}.get(shape, ""), "PRODUCT_GROUP": "", "PRODUCT_SUB_GROUP": "",
        "OPTION_TYPE": option, "STRIKE_PRICE": strike, "COUNTERPARTY": cp, "COUNTERPARTY_UEN": f"UEN0{rnd.randint(100000, 999999)}",
        "COUNTERPARTY_COUNTRY_OF_RISK": rnd.choice(["US", "CA", "GB", "DE"]), "ORIGINAL_COUNTERPARTY": "", "INTERNAL_EXTERNAL_CODE": "External",
        "TRANSIT": f"00{rnd.randint(100, 999)}", "LEGAL_ENTITY": rnd.choice(["LE-NY", "LE-TOR", "LE-LDN"]), "PORTFOLIO": rnd.choice(BOOKS),
        "TRADER_ID": rnd.choice(["TRDR-FROSSI", "TRDR-AKHAN", "TRDR-MLEE"]), "TRADER_FIRST_NAME": "F.", "TRADER_LAST_NAME": "Rossi",
        "START_DATE": start.isoformat(), "MATURITY_DATE": maturity, "CURRENCY": ccy, "DELIVERY_VERSUS_PAYMENT": "NO", "STRATEGY": None,
        "SETTLEMENT_DATE": "", "TT_BOOK": "",
        "SETTLEMENT_INSTRUCTIONS": {"METHOD": rnd.choice(["SWIFT", "CLS"]), "OUR_AGENT_BIC": "DRSHUS33", "THEIR_AGENT_BIC": rnd.choice(["HRBRUS44", "NWNDGB2L"]),
                                    "SSI_ID": f"SSI-{rnd.randint(1000, 9999)}"},
        "REPORTING_REGIMES": rnd.sample(["CFTC", "EMIR", "CSA", "MAS"], 2),
        "LEGS": legs, "EVENTS": events,
    }


def write_fixtures(folder: pathlib.Path, per_product: int = 12, seed: int = 7) -> dict[str, int]:
    """trade_<system>.2026-11-13.jsonl under folder: per_product trades of each product. Returns rows per file."""
    rnd = random.Random(seed)
    folder.mkdir(parents=True, exist_ok=True)
    out: dict[str, list[dict]] = {}
    for system, product, _, shape in PRODUCTS:
        for i in range(per_product):
            out.setdefault(system.lower(), []).append(trade(rnd, system, product, shape, len(out.get(system.lower(), [])) + 1))
    for system, docs in out.items():
        (folder / f"trade_{system}.2026-11-13.jsonl").write_text("".join(json.dumps(d) + "\n" for d in docs), encoding="utf-8")
    return {s: len(d) for s, d in out.items()}


# ---------------------------------------------------------------------------------------------------------- the structure

CHARTS = {"line", "area", "hbar", "histogram", "scatter", "waterfall", "timeline", "candlestick"}
TABLES = {"table", "ladder", "tabs", "pivot"}


def _fragment(use: str) -> list[dict]:
    name = use.split("@")[0]
    f = HAND / "common" / f"{name.split('/')[-1]}.v1.fragment.yaml" if "/" in name else KIT / f"{name}.v1.fragment.yaml"
    text = re.sub(r"%\{([^}]*)\}", r"P-\1", f.read_text(encoding="utf-8"))      # the fragment's %{param}s: their value does not matter here
    return yaml.safe_load(text).get("panels", [])


def _strip_filters(expr: str) -> str:
    """$.LEGS[?@.X == 'A'][0].CASH_FLOWS -> $.LEGS[].CASH_FLOWS (filters, indexes and wildcards read as 'every row')."""
    out, depth = [], 0
    for ch in expr:
        if ch == "[":
            depth += 1
            if depth == 1:
                out.append("[]")
            continue
        if ch == "]":
            depth -= 1
            continue
        if depth == 0:
            out.append(ch)
    return "".join(out)


def array_path(expr, parent: str | None = None) -> str | None:
    """The array a rows/each expression reads, as $.A.B (an @. path read under the enclosing tab's array, `parent`)."""
    if not isinstance(expr, str):
        return None
    m = re.search(r"([$@])((?:\.[A-Za-z_][A-Za-z0-9_]*)+)", _strip_filters(expr).replace("[]", ""))
    if not m:
        return None
    if m.group(1) == "@":
        return parent + m.group(2) if parent else None
    return "$" + m.group(2)


def structure(doc: dict) -> dict:
    """What a Sutra shows, fragments expanded: panel kinds, the arrays shown as rows and charted, the strip's fields, title, keys."""
    kinds, tables, charts = set(), set(), set()

    def walk(panels, parent: str | None):
        for p in panels or []:
            if "use" in p:
                walk(_fragment(p["use"]), parent)
                continue
            kind = p.get("kind")
            kinds.add(kind)
            arr = array_path(p.get("each") if kind == "tabs" else p.get("rows"), parent)
            if arr and kind in TABLES:
                tables.add(arr)
            if arr and kind in CHARTS:
                charts.add(arr)
            child = array_path(p.get("children"), arr) if arr else None
            if child:
                tables.add(child)
            if kind == "tabs":
                walk(p.get("panels"), arr)

    walk(doc.get("panels"), None)
    strip = []
    for s in doc.get("strip") or []:
        bind = str(s.get("bind", ""))
        leaves = {m.group(1) for m in re.finditer(r"\.([A-Za-z_][A-Za-z0-9_]*)\b(?![.\[()])", re.sub(r"'[^']*'", "''", bind))}
        if "currency" not in str(s.get("label", "")).lower():
            leaves.discard("CURRENCY")
        strip.append((s.get("label"), leaves - {"TRADE_VERSION", "PRICE_UNIT"}))
    title = doc.get("title") or {}
    keys = " ".join(str(v) for v in (doc.get("keys") or {}).values())
    return {"kinds": kinds, "tables": {_norm(t) for t in tables}, "charts": {_norm(c) for c in charts}, "strip": strip,
            "title-id": str(title.get("id", "")).strip('"'), "title-with": str(title.get("with") or ""), "keys": keys}


def _norm(path: str) -> str:
    return re.sub(r"\[\]", "", path)


def compare(hand: dict, made: dict) -> tuple[int, int, list[str]]:
    """(matched, total, what is missing) of the hand-built structure in the generated one."""
    got, missing = 0, []

    def item(ok: bool, what: str):
        nonlocal got
        got += ok
        if not ok:
            missing.append(what)

    item(hand["title-id"] == made["title-id"], f"title id {hand['title-id']}")
    want = re.search(r"link\(\$\.(\w+)", hand["title-with"])
    item(bool(want) and f"$.{want.group(1)}" in made["title-with"], f"title link {hand['title-with']}")
    for k in sorted(hand["kinds"]):
        item(k in made["kinds"], f"panel kind {k}")
    for t in sorted(hand["tables"]):
        item(t in made["tables"], f"rows of {t}")
    for c in sorted(hand["charts"]):
        item(c in made["charts"], f"chart of {c}")
    made_leaves = set().union(*(lv for _, lv in made["strip"])) if made["strip"] else set()
    for label, leaves in hand["strip"]:
        item(bool(leaves & made_leaves), f"strip {label}")
    want = re.search(r"link\(\$\.(\w+)", hand["keys"])
    item(bool(want) and f"$.{want.group(1)}" in made["keys"], "a key opening the counterparty")
    total = 2 + len(hand["kinds"]) + len(hand["tables"]) + len(hand["charts"]) + len(hand["strip"]) + 1
    return got, total, missing


# ---------------------------------------------------------------------------------------------------------- the run

def generate(work: pathlib.Path = WORK, say=lambda *_: None) -> tuple[int, pathlib.Path]:
    """Writes the fixtures and the pack; (the generator's exit code: 0 when lint and test pass, the pack folder)."""
    import pack_from_jsonl as PFJ
    if work.exists():
        shutil.rmtree(work)
    write_fixtures(work / "in")
    out = work / "sutragen-quality"            # the folder is named as the pack (sutra lint refuses another name)
    opts = PFJ.build_parser().parse_args([str(work / "in"), "--name", "sutragen-quality", "--kind", "trade", "--key", "TRADE_ID",
                                          "--match", "SOURCE_SYSTEM,PRODUCT_TYPE", "--date", "none", "--out", str(out), "--force"])
    return PFJ.run(opts, say), out


def score(pack: pathlib.Path) -> tuple[float, list[tuple[str, int, int, list[str]]]]:
    rows, got, total = [], 0, 0
    for system, product, hand, _ in PRODUCTS:
        name = f"trade-{system.lower()}-{re.sub('[^a-z0-9]+', '-', product.lower()).strip('-')}"
        made = yaml.safe_load((pack / "sutras" / "trade" / f"{name}.v1.sutra.yaml").read_text(encoding="utf-8"))
        h = yaml.safe_load((HAND / f"{hand}.v1.sutra.yaml").read_text(encoding="utf-8"))
        g, t, miss = compare(structure(h), structure(made))
        rows.append((hand, g, t, miss))
        got, total = got + g, total + t
    return got / total, rows


def report(rows, share: float, say=print) -> None:
    for hand, g, t, miss in rows:
        say(f"{hand:<24} {g:>3}/{t:<3} {g / t:6.1%}" + (f"   missing: {'; '.join(miss)}" if miss else ""))
    say(f"{'score':<24} {share:.1%} (bar {BAR:.0%})")


def _jar() -> bool:
    return bool(glob.glob(str(ROOT / "kernel/drishti-server/target/drishti-server-*-exec.jar")))


@unittest.skipUnless(_jar(), "needs the server jar (kernel/drishti-server/target/drishti-server-*-exec.jar)")
class GeneratedSutraQuality(unittest.TestCase):
    """One generation for the class: the fixtures, the pack, its lint and test, then the score."""

    @classmethod
    def setUpClass(cls):
        cls.rc, cls.pack = generate()

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(WORK, ignore_errors=True)

    def test_lint_and_test_pass(self):
        self.assertEqual(0, self.rc, "sutra lint or sutra test failed on the generated pack")

    def test_structure_matches_the_hand_built_screens(self):
        share, rows = score(self.pack)
        lines = []
        report(rows, share, lines.append)
        self.assertGreaterEqual(share, BAR, "\n".join(lines))

    def test_the_fixture_structure_reader(self):
        h = structure(yaml.safe_load((HAND / "endur/endur-comswap.v1.sutra.yaml").read_text(encoding="utf-8")))
        self.assertTrue({"kv", "tabs", "table", "line", "hbar", "timeline", "provenance", "links"} <= h["kinds"])
        self.assertTrue({"$.LEGS", "$.LEGS.CASH_FLOWS", "$.LEGS.CASH_FLOWS.RESETS", "$.EVENTS"} <= h["tables"], h["tables"])
        self.assertTrue({"$.LEGS.CASH_FLOWS", "$.EVENTS"} <= h["charts"], h["charts"])
        self.assertEqual(8, len(h["strip"]))


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    p.add_argument("--keep", action="store_true", help=f"keep {WORK.relative_to(ROOT)} (the fixtures and the generated pack)")
    a = p.parse_args(argv)
    rc, pack = generate(say=print)
    share, rows = score(pack)
    print()
    report(rows, share)
    if not a.keep:
        shutil.rmtree(WORK, ignore_errors=True)
    return 0 if rc == 0 and share >= BAR else 1


if __name__ == "__main__":
    sys.exit(main())
