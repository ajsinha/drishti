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

"""The demo's system baskets (docs/guides/VAR_GUIDE.md section 5.7): definitions only, of the three types, overlapping on purpose.
They are not data: the generator writes them as the market-risk pack's seed (seeds/system-baskets.yaml, imported once into the basket
store's system scope at a demo's first start) and their VaR limits as var-limit rows (HIERARCHY PORTFOLIO).

- **HIERARCHY**: one per desk (``PF-<DESK>-DESK`` on ``MGMT~DESK-<x>``) and one per legal entity (``PF-LE-<x>`` on ``LEGAL~LE-<x>``),
  and a book (``PF-COMM-BOOK-1``);
- **RULE**: conditions over the derived positions' columns (``PositionRule``, the same language in rules.py);
- **BASKET**: named positions and trades, some inside a desk portfolio, one trade of a fungible position (a partial position).

A system basket's VaR, ES and contributions are never stored: its page and the explorer compute them (``PORT:<id>``). The VaR
limit is an input a risk manager sets; the demo sets it from today's VaR times a headroom, two significant figures."""
from __future__ import annotations

import factors as F
import rules as R
import varmath as VM

DESK_NAMES = {"DESK-RATES": "Rates desk (New York)"}
#: the RULE portfolios: id -> (name, owner, rule, rolls up to)
RULES = {
    "PF-USD-RATES": ("USD rates exposure", "group-risk", "PRODUCT_GROUP in ('Rates', 'Fixed income', 'Money market') and CURRENCY == 'USD'",
                     ("division", "DIV-MARKETS")),
    "PF-EUR-ALL": ("Everything in EUR", "group-risk", "CURRENCY == 'EUR'", ("bank", "BANK")),
    "PF-SHORT-RISK": ("Short positions, credit and rates", "group-risk", "DIRECTION == 'SHORT' and PRODUCT_GROUP in ('Credit', 'Rates')",
                      ("division", "DIV-MARKETS")),
    "PF-GOVT-BONDS": ("Government bonds", "rates-risk", "PRODUCT_TYPE in ('GOVT_BOND', 'T_BILL', 'MUNI_BOND')", ("division", "DIV-MARKETS")),
    # the pack's listed rule's product types (a position its source system sends whole has POSITION_RULE supplied: the rule reads the product)
    "PF-LISTED": ("Listed futures and options", "markets-coo", "PRODUCT_TYPE in ('BOND_FUTURE', 'STIR_FUTURE', 'STIR_FUT_OPT', 'EQ_FUTURE_SINGLE', "
                  "'EQ_INDEX_FUTURE', 'EQ_OPTION_LISTED', 'EQ_INDEX_OPTION', 'CMD_FUTURE', 'CMD_FUT_OPTION', 'ComFut', 'EMISSIONS_FUTURE') "
                  "and PRODUCT_GROUP != 'Fixed income'", ("bank", "BANK")),
    "PF-OPTIONS": ("Every option position", "group-risk", "'option' in TAGS", ("bank", "BANK")),
    "PF-FX-CROSSES": ("FX away from the dollar", "fx-risk", "PRODUCT_GROUP == 'FX' and CURRENCY in ('JPY', 'GBP', 'CAD', 'CHF', 'EUR')",
                      ("desk", "DESK-FX")),
    "PF-LARGE-LONGS": ("Large long holdings", "group-risk", "DIRECTION == 'LONG' and MARKET_VALUE > 5000000", ("bank", "BANK")),
}


def specs(demo) -> list[dict]:
    """Every portfolio's definition (no figures), in id order."""
    pos = sorted(demo.batch.positions, key=lambda p: p["POSITION_ID"])
    out = []
    desks = sorted({n["REF"]["ID"] for n in demo.nodes.values() if n["HIERARCHY"] == "MGMT" and n["LEVEL"] == "desk"})
    for d in desks:
        name = DESK_NAMES.get(d) or f"{demo.desks.get(d, {}).get('NAME') or d} desk"
        out.append(_spec(f"PF-{d[5:]}-DESK", name, d[5:].lower() + "-risk", "HIERARCHY", {"NODE": f"MGMT~{d}"}, ("desk", d)))
    for n in sorted((x for x in demo.nodes.values() if x["HIERARCHY"] == "LEGAL" and x["LEVEL"] == "legal-entity"), key=lambda x: x["NODE_ID"]):
        le = n["REF"]["ID"]
        out.append(_spec(f"PF-{le}", f"Legal entity {le}", "legal-entity-risk", "HIERARCHY", {"NODE": n["NODE_ID"]}, ("legal-entity", le)))
    out.append(_spec("PF-COMM-BOOK-1", "Commodities book 1", "comm-risk", "HIERARCHY", {"NODE": "MGMT~BOOK-COMM-1"}, ("book", "BOOK-COMM-1")))
    for pid, (name, owner, rule, up) in RULES.items():
        out.append(_spec(pid, name, owner, "RULE", {"RULE": rule}, up))
    by_desk = {d: [p for p in pos if p["DESK"] == d] for d in desks}
    mixed = [p for p in pos if p["LONG_TRADES"] and p["SHORT_TRADES"]]
    fungible = [p for p in pos if p["TRADE_COUNT"] > 2]
    securities = sorted((p for p in pos if p["POSITION_RULE"] == "securities"), key=lambda p: (-abs(p["MARKET_VALUE"]), p["POSITION_ID"]))
    rates, fx, credit = by_desk.get("DESK-RATES", []), by_desk.get("DESK-FX", []), by_desk.get("DESK-CREDIT", [])
    baskets = {
        "PF-ASHA-UNWIND": ("Unwind candidates (Asha)", "asha", [{"POSITION_ID": p["POSITION_ID"]} for p in rates[:4] + fx[:3]]
                           + [{"TRADE_ID": p["TRADE_IDS"][0]} for p in credit[:2]], ("division", "DIV-MARKETS")),
        "PF-NETTED-FLOW": ("Two-way client flow", "markets-coo", [{"POSITION_ID": p["POSITION_ID"]} for p in mixed[:6]]
                           + [{"TRADE_ID": t} for p in rates[4:6] for t in p["TRADE_IDS"][:1]], ("bank", "BANK")),
        "PF-HEDGE-PAIRS": ("Hedge pairs under review", "rates-risk", [{"POSITION_ID": p["POSITION_ID"]} for p in mixed[6:12]],
                           ("desk", "DESK-RATES")),
        "PF-TOP-SECURITIES": ("Largest security holdings", "group-risk", [{"POSITION_ID": p["POSITION_ID"]} for p in securities[:12]],
                              ("bank", "BANK")),
        # one trade of each of a few fungible positions: the basket holds part of each position
        "PF-PARTIAL-FILLS": ("Single fills of fungible positions", "asha", [{"TRADE_ID": p["TRADE_IDS"][0]} for p in fungible[:8]],
                             ("bank", "BANK")),
    }
    for pid, (name, owner, members, up) in baskets.items():
        out.append(_spec(pid, name, owner, "BASKET", {"MEMBERS": members}, up))
    return sorted(out, key=lambda s: s["PORTFOLIO_ID"])


def _spec(pid, name, owner, typ, definition, up) -> dict:
    return {"PORTFOLIO_ID": pid, "NAME": name, "OWNER": owner, "TYPE": typ, "DEFINITION": definition,
            "ROLLS_UP_TO": {"KIND": up[0], "ID": up[1]}, "LIMIT_VAR": None}


def members(demo, spec: dict) -> dict[str, list[str]]:
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
            if m["TRADE_ID"] not in have:
                have.append(m["TRADE_ID"])
    return out


def rows(demo) -> list[dict]:
    """The portfolio documents of the date: definitions and a VaR limit (an input), never a figure. A definition names what the date's
    records are: a basket's members are positions when the demo sends every system per position (:func:`position_members`)."""
    tv = demo.batch.trade_vectors
    out = []
    for spec in specs(demo):
        mem = members(demo, spec)
        trades = {t for ts in mem.values() for t in ts}
        if spec["TYPE"] == "BASKET" and demo.batch.supplied_parts:         # held whole at position grain (position_members): the limit too
            trades |= {t for p in demo.batch.positions if trades & set(p["TRADE_IDS"]) for t in p["TRADE_IDS"]}
        trades = sorted(trades)
        total = VM.add(tv[t][:demo.n] for t in trades) if trades else [0.0] * demo.n
        limit = demo_limit(spec["PORTFOLIO_ID"], VM.var(total))
        out.append({**position_members(demo, spec), "LIMIT_VAR": limit, "BUSINESS_DATE": demo.cob, "VALID_FROM": "2026-01-02", "VALID_TO": None})
    return out


def position_members(demo, spec: dict) -> dict:
    """A basket whose date comes per position (every member a position the batch sends whole): named trades become their positions, and a
    position split between limit letters becomes its parts (``<position>@<division>/<letter>``). A trade of a fungible position cannot be held on its
    own at position grain: its whole position is held instead (PF-PARTIAL-FILLS, renamed for it)."""
    b = demo.batch
    if spec["TYPE"] != "BASKET" or not b.supplied_parts:
        return spec
    parts: dict[str, list[str]] = {}
    for pid, _, _rows in b.supplied_parts:
        parts.setdefault(pid.split("@", 1)[0], []).append(pid)
    of = {t: p["POSITION_ID"] for p in b.positions for t in p["TRADE_IDS"]}
    seen, out = set(), []
    for m in spec["DEFINITION"]["MEMBERS"]:
        whole = m.get("POSITION_ID") or of.get(m.get("TRADE_ID"))
        for pid in parts.get(whole, []):
            if pid not in seen:
                seen.add(pid)
                out.append({"POSITION_ID": pid})
    named = {"PF-PARTIAL-FILLS": "Fungible positions under review"}.get(spec["PORTFOLIO_ID"], spec["NAME"])
    return {**spec, "NAME": named, "DEFINITION": {**spec["DEFINITION"], "MEMBERS": out}}


def nice(x: float) -> int:
    """A limit as a desk would set it: two significant figures."""
    if x <= 0:
        return 0
    mag = 10 ** max(0, len(str(int(x))) - 2)
    return int(round(x / mag) * mag)


def demo_limit(pid: str, var: float) -> int:
    return nice(var * (1.3 + 0.4 * F.seeded("pf-limit", pid).random()))


SEED_NOTE = ("The demo's system baskets (VAR_GUIDE.md section 5.7): the bank's own baskets the VaR engine resolves PORT:<id> by.",
             "Imported once into an empty system scope of the basket store at the first start while drishti.var.system-baskets.seed is on",
             "(it follows DRISHTI_DEMO_ENABLED); from then on they are managed in Drishti (Construct your basket, the permission",
             "var:system-baskets) and this file is never read again. Same file shape as drishti.py var baskets export --scope system.",
             "Their VaR limits are var-limit rows (HIERARCHY PORTFOLIO, NODE_ID the id).",
             "Generated by tools/packgen/var/make_var_data.py (portfolios.py): edit the generator, not this file.")


def _q(s) -> str:
    """A YAML scalar: plain when safe, else single-quoted."""
    s = str(s)
    plain = s and all(c.isalnum() or c in "-_~. ()" for c in s) and s[0] not in "-?:,[]{}#&*!|>'\"%@`" and not s.startswith(" ")
    if plain and s.lower() not in ("true", "false", "null", "yes", "no", "on", "off") and not s.replace(".", "").isdigit():
        return s
    return "'" + s.replace("'", "''") + "'"


def seed_yaml(portfolios: list[dict], license_lines: list[str]) -> str:
    """The pack's seed file: ``system-baskets: [...]`` in the form ``drishti.py var baskets export --scope system`` writes."""
    out = [f"# {ln}".rstrip() for ln in license_lines] + ["#"] + [f"# {ln}" for ln in SEED_NOTE] + ["system-baskets:"]
    for r in sorted(portfolios, key=lambda p: p["PORTFOLIO_ID"]):
        d, t = r["DEFINITION"], r["TYPE"].lower()
        out += [f"  - id: {r['PORTFOLIO_ID']}", f"    name: {_q(r['NAME'])}", f"    owner: {_q(r['OWNER'])}", f"    type: {t}"]
        if t == "hierarchy":
            out.append(f"    node: {_q(d['NODE'])}")
        elif t == "rule":
            out.append(f"    rule: {_q(d['RULE'])}")
        else:
            out.append("    members:")
            out += [f"      - position: {_q(m['POSITION_ID'])}" if "POSITION_ID" in m else f"      - trade: {_q(m['TRADE_ID'])}" for m in d["MEMBERS"]]
        up = r["ROLLS_UP_TO"]
        out += [f"    rolls-up-to: {{kind: {up['KIND']}, id: {up['ID']}}}", f"    valid-from: '{r['VALID_FROM']}'"]
        if r.get("VALID_TO"):
            out.append(f"    valid-to: '{r['VALID_TO']}'")
    return "\n".join(out) + "\n"


def page_document(r: dict) -> dict:
    """A system basket as its page reads it (the system-basket kind the var-system-baskets connector serves, PORT <id>)."""
    return {"PORTFOLIO_ID": r["PORTFOLIO_ID"], "NAME": r["NAME"], "DESCRIPTION": "", "OWNER": r["OWNER"], "TYPE": r["TYPE"],
            "DEFINITION": r["DEFINITION"], "ROLLS_UP_TO": r["ROLLS_UP_TO"], "BUSINESS_DATE": r["BUSINESS_DATE"], "VALID_FROM": r["VALID_FROM"],
            "VALID_TO": r["VALID_TO"], "VERSION": 1}
