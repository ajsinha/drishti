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
    {"path": "mtm", "label": "Mark to market", "type": "amount", "operators": ["=", "between", ">", "<"], "group": "Money", "indexed": True},
    {"path": "maturityDate", "label": "Maturity", "type": "date", "operators": ["on", "between", "relative", "before", "after"], "group": "Money", "indexed": True},
    {"path": "isActive", "label": "Active", "type": "bool", "operators": ["is"], "group": "Money", "indexed": True},
    {"path": "internalNote", "label": "Note", "type": "text", "operators": ["equals"], "hidden": True, "indexed": False},
]
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

    async def packs(self, ident=None):
        return [{"name": "trading", "version": "1.0.0", "title": "Trading", "description": "Trades.", "assigned": True, "active": True, "kinds": ["trade", "desk-pnl"]},
                {"name": "empty", "version": "1.0.0", "title": "Empty", "description": "", "assigned": True, "active": True, "kinds": []}]

    async def form(self, pack, kind="", ident=None):
        if pack != "trading":
            raise BackendError(400, "DRS-5001", f"no pack '{pack}' is switched on for you")
        kinds = [{"kind": "trade", "mnemonic": "TRD", "label": "Trade", "declared": self.declared},
                 {"kind": "desk-pnl", "mnemonic": "DPNL", "label": "Desk P&L", "declared": False}]
        k = kind or "trade"
        fields = FIELDS if k == "trade" else [{"path": "desk", "label": "Desk", "type": "text", "operators": ["equals"], "indexed": False}]
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
