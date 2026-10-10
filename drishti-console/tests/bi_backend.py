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

"""A stand-in for the server's /api/v1/bi/... for the console's route tests: canned answers, and a record of what the console asked."""
from conftest import FakeBackend
from core.backend import BackendError

ITEM = {"id": "bi-1", "workspace": "rates", "workspaceName": "Rates Desk", "personal": False, "kind": "report", "title": "Desk P&L", "description": "Daily P&L by book",
        "owner": "ann", "tags": ["pnl"], "status": "published", "publishedVersion": 2, "endorsement": "certified", "endorsedBy": "sue",
        "audience": {"roles": [], "users": [], "packs": []}, "publishedAt": "2026-10-01T10:00:00Z", "updatedAt": "2026-10-01T10:00:00Z", "proposedBy": "",
        "views": 7, "viewsDays": 30, "favourite": False, "shipped": False, "role": "viewer",
        "can": {"edit": False, "propose": False, "publish": False, "approve": False, "withdraw": False, "promote": False, "certify": False, "delete": False, "move": False}}


DATASET_ITEM = {"id": "file:dataset:trades-basic", "title": "Trades, the basics", "description": "One table", "endorsement": "promoted", "tags": ["sample"], "sample": True,
                "shipped": True, "valid": True, "errors": 0, "tables": 1, "measures": 4, "relationships": 0}
DATASET_MODEL = {"id": "file:dataset:trades-basic", "title": "Trades, the basics", "valid": True, "problems": [], "name": "trades-basic", "version": 1, "description": "One table",
                 "sample": True, "engine": "duckdb",
                 "tables": [{"name": "trade", "connector": "trading-store", "kind": "trade", "each": None, "mode": "cached", "onLoad": True, "schedule": "", "description": "", "access": "ok",
                             "columns": [{"name": "id", "path": "id", "type": "string", "key": True, "calculated": False, "expr": None, "masked": False, "description": "the entity id"},
                                         {"name": "desk", "path": "desk", "type": "string", "key": False, "calculated": False, "expr": None, "masked": False, "description": ""},
                                         {"name": "mtm", "path": "mtm", "type": "decimal", "key": False, "calculated": False, "expr": None, "masked": True, "description": ""}]}],
                 "relationships": [], "measures": [{"name": "MTM", "expr": "sum(trade.mtm)", "python": None, "format": "signed0", "description": "", "rollups": []}],
                 "hierarchies": {"Desk and book": ["trade.desk", "trade.book"]},
                 "security": {"rows": [{"role": "risk", "where": "true", "appliesToYou": True}], "masked": ["mtm"], "rowRulesEnforced": True},
                 "rollups": [], "layers": [{"table": "trade", "mode": "cached", "partitions": [{"date": "2026-09-30", "rows": 750, "how": "scan", "builtAt": "2026-10-10T10:00:00Z"}]}]}
DATASET_ANSWER = {"columns": [{"name": "trade.desk", "type": "string", "masked": False}, {"name": "MTM", "type": "decimal", "masked": True}], "rows": [["DESK-FX", None]], "rowCount": 1,
                  "meta": {"layer": "columns", "rollup": None, "rows": 1, "millis": 3.2, "cached": False, "dates": ["2026-09-30"], "tables": ["trade"], "masked": ["MTM"], "warnings": [], "generation": 0}}


class BiBackend(FakeBackend):
    """``powers`` are what /bi/me says the caller holds; ``enabled`` is the server's switch."""

    def __init__(self, powers=("bi", "bi-author", "bi-admin"), enabled=True):
        super().__init__()
        self.powers, self.enabled = list(powers), enabled
        self.bi_calls = []
        self.refuse = None                      # (code, status, detail) to answer every BI call with

    async def bi(self, method, path, ident, body=None, **params):
        self.bi_calls.append((method, path, body, params))
        if self.refuse:
            code, status, detail = self.refuse
            raise BackendError(status, code, detail)
        if path == "/me":
            return {"enabled": self.enabled, "powers": self.powers, "user": ident.user, "personalWorkspace": "u-ash-0000", "workspaceCreation": "authors",
                    "mayCreateWorkspace": "bi-author" in self.powers, "kinds": ["dataset", "report", "board", "notebook"], "roles": ["viewer", "contributor", "member", "admin"]}
        if path == "/home":
            return {"recent": [ITEM], "favourites": [], "certified": [ITEM], "shared": [], "counts": {"catalogue": 1, "workspaces": 1}}
        if path == "/catalogue":
            q = (params.get("q") or "").lower()
            items = [ITEM] if not q or q in ITEM["title"].lower() or q in ITEM["id"] else []
            return {"total": len(items), "items": items, "facets": {"kind": {"report": 1}, "workspace": {"rates": 1}, "owner": {"ann": 1}, "endorsement": {"certified": 1}, "tag": {"pnl": 1}, "pack": {}},
                    "workspaceNames": {"rates": "Rates Desk"}}
        if path == "/items/bi-1" or path == "/items/bi-1/open":
            return dict(ITEM, body="pages: []\n", bodyIs="published", draftChanges=False)
        if path == "/datasets":
            return {"datasets": [DATASET_ITEM]}
        if path == "/datasets/file:dataset:trades-basic":
            return DATASET_MODEL
        if path == "/datasets/file:dataset:trades-basic/query":
            return dict(DATASET_ANSWER, echo=body, explain=params.get("explain"))
        if path == "/model/kinds":
            return {"tables": [{"table": "trade", "pack": "trading", "kind": "trade", "connector": "trading-store", "data": True, "source": "promoted", "columns": [{"name": "desk", "path": "desk", "type": "string", "promoted": True}]}]}
        if path == "/model/start":
            return {"yaml": "dataset: x\n", "tables": [], "relationships": [], "proposals": [], "warnings": []}
        if path == "/engine":
            return {"engine": "duckdb", "queries": 3}
        if path == "/settings":
            return {"workspaceCreation": "authors", "workspaceCreationOptions": ["authors", "stewards", "admins"], "fourEyes": True, "dir": "./config/bi", "shippedItems": 0,
                    "powers": ["bi", "bi-author", "bi-steward", "bi-admin"], "roles": [{"name": "viewer", "builtIn": True, "bi": ["bi"]}, {"name": "desk", "builtIn": False, "bi": []}]}
        if path == "/workspaces":
            return [{"id": "u-ash-0000", "name": "My workspace", "description": "", "personal": True, "shipped": False, "owner": "ash", "reviewRequired": False, "role": "admin", "items": 0}]
        return {"ok": True, "echo": body}
