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

"""The trading pack's Sutra tests (config/packs/trading/tests/): one sample trade per deal Sutra (per PRODUCT_TYPE, from every source
system), each with an expect.yaml (no errors, the legs panel filled, the about text renders) and the help-coverage ratchet."""
from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
TESTS = ROOT / "config" / "packs" / "trading" / "tests"
HEADER = "".join(f"# {line}".rstrip() + "\n" for line in (ROOT / "config" / "license-header.txt").read_text(encoding="utf-8").strip().splitlines())
COVERAGE_NOTE = "# Lowest share of shown fields with a glossary entry, per Sutra (a ratchet: raise it when about.yaml grows, never lower it).\n"
EXPECT = "\nnoErrors: true\nnonEmpty: [legs, ticket]\nhelp: { about: true }\n"
MATCH = re.compile(r"\$\.PRODUCT_TYPE == '([^']+)'")


def files(trades: dict[str, dict]) -> dict[Path, str]:
    """{path: text}: for each deal Sutra of the pack, the first sample trade of its product type."""
    out: dict[Path, str] = {}
    by_type: dict[str, dict] = {}
    by_sub: dict[str, dict[str, dict]] = {}                 # a filled sample of every sub type, so each sub type's own panels are tested
    for _id, t in sorted(trades.items()):
        by_type.setdefault(t["PRODUCT_TYPE"], t)
        subs = by_sub.setdefault(t["PRODUCT_TYPE"], {})
        if t["SOURCE_SYSTEM"] == "ENDUR" and t.get("LEGAL_ENTITY") and t["PRODUCT_SUB_TYPE"] not in subs:
            subs[t["PRODUCT_SUB_TYPE"]] = t
    names = []
    for f in sorted((ROOT / "config" / "packs" / "trading" / "sutras").rglob("*.sutra.yaml")):
        text = f.read_text(encoding="utf-8")
        m = MATCH.search(text)
        if not m or m.group(1) not in by_type:
            continue
        name = re.search(r"^sutra: (\S+)", text, re.M).group(1)
        for t in [by_type[m.group(1)], *by_sub.get(m.group(1), {}).values()]:
            doc = {k: v for k, v in t.items() if k != "_meta"}
            out[TESTS / name / f"{doc['TRADE_ID']}.json"] = json.dumps(doc, indent=1, ensure_ascii=False) + "\n"
        out[TESTS / name / "expect.yaml"] = HEADER + EXPECT
        names.append(name)
    for kind, doc in derived_samples(trades).items():       # a page of each derived kind, as the derived connector computes it
        out[TESTS / kind / f"{doc['_id']}.json"] = json.dumps({k: v for k, v in doc.items() if k != "_id"}, indent=1, ensure_ascii=False) + "\n"
        out[TESTS / kind / "expect.yaml"] = HEADER + "\nnoErrors: true\nnonEmpty: [trades, by-product]\nhelp: { about: true }\n"
        names.append(kind)
    out[TESTS / "help-coverage.txt"] = COVERAGE_NOTE + "".join(f"{n}=1\n" for n in names)
    return out


def first_leg(t: dict) -> dict:
    return (t.get("LEGS") or [{}])[0]


def index_of(t: dict) -> str:
    return next((leg["INDEX"]["NAME"] for leg in (t.get("LEGS") or [])[:2] if (leg.get("INDEX") or {}).get("NAME")), "")


def derived_samples(trades: dict[str, dict]) -> dict[str, dict]:
    """One document per derived kind (the trades of the counterparty, portfolio, legal entity and index with most trades), shaped as the
    derived connector writes it (sutras.derived_connector): the key, the totals, TRADE_IDS and one row per trade in TRADES."""
    import sutras as TS
    keys = {"counterparty-trades": lambda t: t.get("COUNTERPARTY"), "portfolio-trades": lambda t: t.get("PORTFOLIO"),
            "legal-entity-trades": lambda t: t.get("LEGAL_ENTITY"), "index-trades": index_of}
    out = {}
    for kind, key in keys.items():
        groups: dict[str, list] = {}
        for _id, t in sorted(trades.items()):
            k = key(t)
            if k:
                groups.setdefault(k, []).append(t)
        best = max(sorted(groups), key=lambda k: len(groups[k]))
        members = groups[best]
        rows = [{"TRADE_ID": t["TRADE_ID"], "SOURCE_SYSTEM": t["SOURCE_SYSTEM"], "PRODUCT_TYPE": t["PRODUCT_TYPE"], "BUY_SELL_CODE": t["BUY_SELL_CODE"],
                 "CURRENCY": t["CURRENCY"], "NOTIONAL": first_leg(t).get("NOTIONAL"), "MATURITY_DATE": t["MATURITY_DATE"],
                 "TRADE_STATUS": t["TRADE_STATUS"], "INDEX_NAME": index_of(t), "COUNTERPARTY": t["COUNTERPARTY"], "PORTFOLIO": t["PORTFOLIO"],
                 "EVENTS": t["EVENTS"]} for t in members]
        doc = {"_id": best, TS.DERIVED[kind][3]: best, "TRADE_COUNT": len(members), "NOTIONAL": round(sum(r["NOTIONAL"] or 0 for r in rows), 2),
               "SOURCE_SYSTEMS": sorted({r["SOURCE_SYSTEM"] for r in rows}), "PRODUCT_TYPES": sorted({r["PRODUCT_TYPE"] for r in rows}),
               "CURRENCIES": sorted({r["CURRENCY"] for r in rows}), "LAST_MATURITY": max(r["MATURITY_DATE"] for r in rows),
               "TRADE_IDS": [str(r["TRADE_ID"]) for r in rows], "TRADES": rows}
        out[kind] = doc
    return out


def write(trades: dict[str, dict]) -> int:
    want = files(trades)
    for f in TESTS.rglob("*"):
        if f.is_file() and f not in want:
            f.unlink()
    for d in sorted((p for p in TESTS.rglob("*") if p.is_dir()), reverse=True):
        if not any(d.iterdir()):
            d.rmdir()
    for f, t in want.items():
        f.parent.mkdir(parents=True, exist_ok=True)
        if not f.exists() or f.read_text(encoding="utf-8") != t:
            f.write_text(t, encoding="utf-8")
    return len(want)


def stale(trades: dict[str, dict]) -> list[str]:
    want = files(trades)
    have = {f for f in TESTS.rglob("*") if f.is_file()}
    return [str(f) for f, t in want.items() if not f.exists() or f.read_text(encoding="utf-8") != t] + [str(f) for f in have - set(want)]
