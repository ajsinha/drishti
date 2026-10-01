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

"""The whole risk-pack taxonomy in one place, with the consistency checks every generator relies on."""
from __future__ import annotations

import market_data
import products_credit_equity_cmd
import products_other
import products_rates_fx
import risk_data
from risk_model import Kind

PRODUCTS = products_rates_fx.PRODUCTS + products_credit_equity_cmd.PRODUCTS + products_other.PRODUCTS
KINDS: list[Kind] = market_data.KINDS + risk_data.KINDS
KIND = {k.kind: k for k in KINDS}

# Trade-level reference fields (besides each product's market-data fields).
TRADE_LINKS = {"nettingSet": ("netting-set", "Netting set"), "book": ("book", "Book"), "trader": ("trader", "Trader"),
               "clearingAccount": ("clearing-account", "Clearing account"), "issuer": ("issuer", "Reference entity"),
               "counterparty": ("counterparty", "Counterparty")}
TRADE = Kind("trade", "TRD", "MX-", "Trade", "Trades", "A trade in any of the pack's products; its productType selects its Sutra.", "tradeId", [], [])   # ids carry their booking system's prefix (booking.py)


def graph_fields() -> dict[str, tuple[str, str]]:
    """Every reference field in the pack -> (kind, label). A field name must mean one kind."""
    out: dict[str, tuple[str, str]] = {}

    def claim(f, kind, label):
        prev = out.get(f)
        if prev and prev[0] != kind:
            raise SystemExit(f"field {f!r} means both {prev[0]} and {kind}")
        out.setdefault(f, (kind, label))

    for f, (k, lab) in TRADE_LINKS.items():
        claim(f, k, lab)
    for p in PRODUCTS:
        for f, k, _ in p.md:
            claim(f, k, f[0].upper() + "".join(" " + c.lower() if c.isupper() else c for c in f[1:]))
    for k in KINDS:
        for f, (kind, lab) in k.links.items():
            claim(f, kind, lab)
    unknown = {k for k, _ in out.values()} - set(KIND) - {"trade"}
    if unknown:
        raise SystemExit(f"links to unknown kinds: {unknown}")
    return out


def check() -> None:
    codes = [p.code for p in PRODUCTS]
    assert len(codes) == len(set(codes)), "duplicate product codes"
    for attr in ("kind", "mnemonic", "prefix"):
        vals = [getattr(k, attr) for k in KINDS + [TRADE]]
        assert len(vals) == len(set(vals)), f"duplicate {attr}"
    graph_fields()


if __name__ == "__main__":
    check()
    print(f"{len(PRODUCTS)} products, {len(KINDS) + 1} kinds, {len(graph_fields())} reference fields: consistent")
