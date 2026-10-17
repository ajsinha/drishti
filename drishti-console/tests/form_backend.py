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

"""A stand-in server for query by form (FORM <pack>): a trading pack with a form of every field type, a compile that writes
the command the way the real one does for the simple cases, and a search over three trades. The real compiler is tested in Java."""
from __future__ import annotations

from conftest import BackendError, FakeBackend

FIELDS = [
    {"path": "tradeId", "label": "Trade id", "type": "id", "operators": ["in"], "group": "Identity", "indexed": True},
    {"path": "counterparty.name", "label": "Counterparty", "type": "text", "operators": ["equals", "contains", "starts", "regex"], "group": "Identity",
     "indexed": False, "help": "The legal name."},
    {"path": "book", "label": "Book", "type": "enum", "operators": ["in"], "values": ["BOOK-FI-1", "BOOK-FI-2", "BOOK-CREDIT-1"], "group": "Where", "indexed": True},
    {"path": "productType", "label": "Product type", "type": "enum", "operators": ["in"], "group": "Where", "indexed": True,
     "values": ["ABS", "BOND", "CDS", "FRA", "FUTURE", "IRS", "PUTTABLE_BOND", "REPO", "SWAPTION"]},
    {"path": "mtm", "label": "Mark to market", "type": "amount", "operators": ["=", "between", ">", "<"], "group": "Money", "indexed": True},
    {"path": "maturityDate", "label": "Maturity", "type": "date", "operators": ["on", "between", "relative", "before", "after"], "group": "Money", "indexed": True},
    {"path": "isActive", "label": "Active", "type": "bool", "operators": ["is"], "group": "Money", "indexed": True},
    {"path": "internalNote", "label": "Note", "type": "text", "operators": ["equals"], "hidden": True, "indexed": False},
]
# the production-shaped trade of three source systems (FormBackend.systems): a product sub type that follows the product type
SYSTEM_FIELDS = [
    {"path": "SOURCE_SYSTEM", "label": "Source system", "type": "enum", "operators": ["in"], "values": ["CALYPSO", "ENDUR", "MUREX"], "indexed": True},
    {"path": "PRODUCT_TYPE", "label": "Product type", "type": "enum", "operators": ["in"], "indexed": True,
     "values": ["CallNot", "Cash", "ComFut", "ComSwap", "Commodity", "FX_SPOT", "IRS_FIXFLOAT", "OIS"]},
    {"path": "PRODUCT_SUB_TYPE", "label": "Product sub type", "type": "enum", "operators": ["in"], "indexed": True, "depends": "PRODUCT_TYPE",
     "values": ["CASH", "COMM-WARRANTS-INV", "ENGY-B-SWAP", "ENGY-EXCH-FUT", "ENGY-SWAP", "FX spot", "Interest rate swap (fixed/float)", "NGX-COMM-PHYS",
                "NOSTRO", "Overnight index swap"],
     "parents": {"CASH": ["Cash"], "COMM-WARRANTS-INV": ["Commodity"], "ENGY-B-SWAP": ["ComSwap"], "ENGY-EXCH-FUT": ["ComFut"], "ENGY-SWAP": ["ComSwap"],
                 "FX spot": ["FX_SPOT"], "Interest rate swap (fixed/float)": ["IRS_FIXFLOAT"], "NGX-COMM-PHYS": ["Commodity"], "NOSTRO": ["CallNot"],
                 "Overnight index swap": ["OIS"]}},
]
SYSTEM_ROWS = [("1000001", "ENDUR", "ComSwap"), ("1000003", "ENDUR", "ComSwap"), ("1000004", "ENDUR", "ComFut"), ("CLY-3000001", "CALYPSO", "FX_SPOT"),
               ("MX-20000001", "MUREX", "IRS_FIXFLOAT")]
ROWS = [("IRS-48213", "Meridian Bank", "BOOK-FI-1", -412580.5), ("IRS-47102", "Northbridge", "BOOK-FI-2", 1250000.0), ("CFT-77120", "Meridian Bank", "BOOK-CREDIT-1", 99000.0)]


class FormBackend(FakeBackend):
    """The server's forms API and a search, for the console's tests."""

    def __init__(self):
        super().__init__()
        self.compiled = []                 # the (kind, body) of each compile
        self.asked = []                    # each search command run
        self.settings_patched = []
        self.saved = []
        self.declared = True
        self.systems = False               # the production-shaped trade form (SYSTEM_FIELDS) instead of FIELDS

    async def packs(self, ident=None):
        return [{"name": "trading", "version": "1.0.0", "title": "Trading", "description": "Trades.", "assigned": True, "active": True, "kinds": ["trade", "desk-pnl"]},
                {"name": "empty", "version": "1.0.0", "title": "Empty", "description": "", "assigned": True, "active": True, "kinds": []}]

    async def form(self, pack, kind="", ident=None):
        if pack != "trading":
            raise BackendError(400, "DRS-5001", f"no pack '{pack}' is switched on for you")
        kinds = [{"kind": "trade", "mnemonic": "TRD", "label": "Trade", "declared": self.declared},
                 {"kind": "desk-pnl", "mnemonic": "DPNL", "label": "Desk P&L", "declared": False}]
        k = kind or "trade"
        fields = (SYSTEM_FIELDS if self.systems else FIELDS) if k == "trade" else [{"path": "desk", "label": "Desk", "type": "text", "operators": ["equals"], "indexed": False}]
        return {"pack": "trading", "code": "TRDS", "title": "Trading", "kinds": kinds, "kind": k, "mnemonic": "TRD" if k == "trade" else "DPNL",
                "form": {"kind": k, "inferred": not self.declared, "fields": fields}}

    async def form_compile(self, pack, kind, body, ident=None):
        self.compiled.append((kind, body))
        parts = []
        for c in body["criteria"]:
            v = c["values"]
            if c["op"] == "regex":
                if "(?=" in v[0] or "\\1" in v[0]:
                    raise BackendError(400, "DRS-4010", f"DRS-4010 look-around is not offered in '{v[0]}'")
                parts.append(f"matches({c['path']}, '{v[0]}')")
            elif c["op"] == "between":
                parts.append(f"({c['path']} >= {v[0]} and {c['path']} <= {v[1]})")
            elif c["op"] == "in":
                parts.append("(" + " or ".join(f"{c['path']} = '{x}'" for x in v) + ")")
            else:
                parts.append(f"{c['path']} {c['op']} {v[0]}")
        cmd = "TRD" + (" where " + " and ".join(parts) if parts else "")
        if body.get("orderBy"):
            cmd += f" order by {body['orderBy']}" + (" desc" if body.get("descending") else "")
        return {"command": cmd + f" limit {body.get('limit') or 100}"}

    async def search(self, q, ident=None):
        self.asked.append(q)
        if self.systems:                   # the trades whose system and product type the command names
            rows = [r for r in SYSTEM_ROWS if ("SOURCE_SYSTEM" not in q or f"SOURCE_SYSTEM = '{r[1]}'" in q) and ("PRODUCT_TYPE" not in q or f"PRODUCT_TYPE = '{r[2]}'" in q)]
            return {"kind": "trade", "mnemonic": "TRD", "columns": ["$.SOURCE_SYSTEM", "$.PRODUCT_TYPE"],
                    "labels": {"$.SOURCE_SYSTEM": "Source system", "$.PRODUCT_TYPE": "Product type"},
                    "rows": [{"ref": {"kind": "trade", "id": r[0]}, "title": r[0], "values": {"$.SOURCE_SYSTEM": r[1], "$.PRODUCT_TYPE": r[2]}} for r in rows],
                    "scanned": 9000, "matched": len(rows), "partial": False, "failed": [], "elapsedMs": 3.1}
        rows = [r for r in ROWS if "Meridian" not in q or r[1] == "Meridian Bank"]
        return {"kind": "trade", "mnemonic": "TRD", "columns": ["$.counterparty.name", "$.book", "$.mtm"],
                "labels": {"$.counterparty.name": "Counterparty name", "$.book": "Book", "$.mtm": "MTM"},
                "rows": [{"ref": {"kind": "trade", "id": r[0]}, "title": r[0], "values": {"$.counterparty.name": r[1], "$.book": r[2], "$.mtm": r[3]}} for r in rows],
                "scanned": 36, "matched": len(rows), "partial": False, "failed": [], "elapsedMs": 3.1}

    async def settings(self, ident=None):
        return {"savedSearches": self.saved}

    async def patch_settings(self, body, ident=None):
        self.settings_patched.append(body)
        self.saved = body.get("savedSearches", self.saved)
        return {"savedSearches": self.saved}
