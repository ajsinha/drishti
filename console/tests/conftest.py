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

"""Shared fixtures: a console app wired to the default configuration and a fake backend that serves
ViewModels captured from the real server (tests/fixtures)."""
import json
import sys
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

CONSOLE = Path(__file__).resolve().parent.parent
FIXTURES = Path(__file__).resolve().parent / "fixtures"
sys.path.insert(0, str(CONSOLE))

from core.app import create_app  # noqa: E402
from core.backend import BackendError  # noqa: E402
from core.config import load_settings  # noqa: E402


class FakeBackend:
    """Stands in for the Drishti server."""

    def __init__(self):
        self.calls = []

    async def view(self, kind, id_, user):
        self.calls.append(("view", kind, id_, user))
        f = FIXTURES / f"view_{kind}_{id_}.json"
        if not f.exists():
            raise BackendError(404, "DRS-1001", f"no source holds {kind}/{id_}")
        return json.loads(f.read_text())

    async def raw(self, kind, id_, ident=None):
        return {"ref": {"kind": kind, "id": id_}, "provenance": {"source": "aero-risk", "generation": 1742}, "data": {"tradeId": id_}}

    async def suggest(self, q, user, limit=10):
        self.calls.append(("suggest", q))
        return json.loads((FIXTURES / "suggest.json").read_text())

    async def sutras(self, ident=None):
        return [{"name": "irs-vanilla", "latest": 3, "versions": [3], "domain": "rates", "kind": "trade", "where": None, "priority": 10}]

    async def sutra_source(self, name, version, ident=None):
        return "rachana: 1\nsutra: irs-vanilla\nversion: 3\n"

    async def rachana_schema(self, ident=None):
        return {"$schema": "https://json-schema.org/draft/2020-12/schema", "type": "object", "required": ["rachana", "sutra", "version", "match"],
                "additionalProperties": False,
                "properties": {"rachana": {"const": 1}, "sutra": {"type": "string"}, "version": {"type": "integer"},
                               "match": {"type": "object", "properties": {"kind": {"enum": ["trade"]}}},
                               "panels": {"type": "array", "items": {"$ref": "#/$defs/panel"}}},
                "$defs": {"panel": {"type": "object", "required": ["id", "kind"], "properties": {"id": {}, "kind": {"enum": ["table"]}, "rows": {}},
                                    "allOf": [{"if": {"properties": {"kind": {"const": "table"}}},
                                               "then": {"required": ["rows"], "x-rachana-options": ["rows"]}}]}},
                "x-rachana-functions": {"size": {"min": 1, "max": 1}}}

    async def settings(self, ident=None):
        return {}

    async def studio_settings(self, ident=None):
        return {"save": False}

    async def inferred_from(self, kind, id_, name, document, ident=None):
        return f"sutra: {name}\nversion: 1\n# fields: {','.join(sorted(document))}\n"

    async def preview(self, yaml_text, kind, id_, ident=None, document=None):
        if document is not None:
            v = await self.view("trade", "IRS-48213", ident)
            v["title"]["id"] = document.get("tradeId", "SAMPLE")
            return v
        if "BROKEN" in yaml_text:
            e = BackendError(422, "DRS-2002", "1 problem(s)")
            e.problems = [{"code": "DRS-2101", "message": "bad expression", "location": {"file": "studio.yaml", "line": 4, "column": 3}}]
            raise e
        return await self.view(kind, id_, ident)

    async def inferred(self, kind, id_, name, ident=None):
        return f"sutra: {name}\nversion: 1\n"

    users = {"drishti-dev-admin": {"username": "drishti-dev-admin", "displayName": "Drishti dev admin", "desk": "Administration",
                                   "roles": ["admin"], "enabled": True, "mustChangePassword": False, "locked": False, "email": "",
                                   "lastLoginAt": None}}

    async def login(self, username, password, service):
        assert service.roles == ("service",)
        if username == "drishti-dev-admin" and password == "drishti-dev-admin123":
            return self.users[username]
        raise BackendError(401, "DRS-6004", "unknown user or wrong password")

    async def me(self, ident):
        return self.users.get(ident.user, {"username": ident.user, "displayName": ident.display, "desk": ident.desk, "roles": list(ident.roles)})

    async def change_password(self, current, new, ident):
        if current != "drishti-dev-admin123":
            raise BackendError(401, "DRS-6004", "current password is wrong")
        return self.users["drishti-dev-admin"]

    async def admin(self, method, path, ident, body=None, **params):
        self.calls.append(("admin", method, path, body))
        if path == "/users" and method == "GET":
            return list(self.users.values())
        if path == "/roles":
            return ["admin", "author", "risk", "trader"]
        if path == "/role-definitions":
            return [{"name": "admin", "description": "", "kinds": ["*"], "raw": True, "author": True, "approve": False, "admin": True,
                     "builtIn": True, "updatedAt": None, "updatedBy": "", "users": 1},
                    {"name": "credit-analyst", "description": "Reads credit", "kinds": ["counterparty", "credit-curve"], "raw": False,
                     "author": False, "approve": False, "admin": False, "builtIn": False, "updatedAt": "2026-09-30T12:00:00Z",
                     "updatedBy": "drishti-dev-admin", "users": 0}]
        if path.startswith("/role-definitions/") and method == "PUT":
            if not body.get("kinds"):
                raise BackendError(400, "DRS-5001", "a role opens at least one kind (or * for all)")
            return {"name": path.rsplit("/", 1)[1], **body}
        if path == "/role-definitions/admin" and method == "DELETE":
            raise BackendError(400, "DRS-5001", "'admin' is a built-in role")
        if path == "/status":
            return {"defaultAdminPasswordInUse": True, "users": 1, "forceChangeOnCreate": False}
        if path == "/caches":
            return [{"name": "engine", "type": "Layouts and shape fingerprints", "stats": {"layouts": 12, "fingerprints": 40}},
                    {"name": "trading-stream", "type": "Connector", "stats": {"memoryEntries": 3, "diskMb": 1.5}}]
        if path.endswith("/purge"):
            return {"purged": [path.split("/")[2]], "elapsedMs": 0.4}
        if path == "/audit":
            return [{"at": "2026-09-30T12:00:00Z", "actor": "system", "action": "user-seeded", "subject": "drishti-dev-admin", "detail": ""}]
        if path == "/users" and method == "POST":
            if body.get("username") in self.users:
                raise BackendError(409, "DRS-6002", "exists")
            return {**body, "enabled": True}
        return {"ok": True}

    enabled_packs = ["finance", "logistics"]
    monitors = {}
    rules = {}

    async def mine(self, method, path, ident, body=None, **params):
        from urllib.parse import unquote
        parts = [unquote(p) for p in path.strip("/").split("/")]
        if parts[0] == "monitors":
            if len(parts) == 1:
                return sorted(self.monitors)
            name = parts[1]
            if method == "PUT":
                self.monitors[name] = body
                return body
            if method == "DELETE":
                self.monitors.pop(name, None)
                return None
            if name not in self.monitors:
                raise BackendError(404, "DRS-1001", "no monitor")
            rows = []
            for e in self.monitors[name]["entities"]:
                try:
                    v = await self.view(e["kind"], e["id"], ident)
                    rows.append({"ref": e, "title": v["title"], "strip": v["strip"], "live": True})
                except BackendError as err:
                    rows.append({"ref": e, "error": err.code})
            return rows
        if parts[0] == "alerts":
            if parts[-1] == "rules" and method == "GET":
                return list(self.rules.values())
            if len(parts) == 3 and method == "PUT":
                if body.get("when", "").endswith("<"):
                    raise BackendError(422, "DRS-2101", "alert expression: unexpected end")
                self.rules[parts[2]] = {"name": parts[2], "ref": {"kind": body["kind"], "id": body["id"]},
                                        "when": body["when"], "severity": body.get("severity", "warn")}
                return self.rules[parts[2]]
            if len(parts) == 3 and method == "DELETE":
                self.rules.pop(parts[2], None)
                return None
            return [{"seq": 1, "at": "2026-09-30T12:00:05Z", "user": "ash", "rule": "Deep", "kind": "trade",
                     "id": "IRS-48213", "severity": "warn", "message": "IRS-48213: MTM -412,580", "generation": 1}]
        raise BackendError(404, "DRS-1001", path)

    async def sse(self, path, ident=None, opened=None):
        yield "hello", "{}"
        if path.endswith("/alerts/stream"):
            yield "alert", json.dumps({"seq": 2, "kind": "trade", "id": "IRS-48213", "severity": "critical", "message": "x"})
        else:
            yield "row", json.dumps({"kind": "trade", "id": "IRS-48213", "patches": [], "p99Ms": 3})

    async def packs(self, ident=None):
        return [{"name": n, "version": "1.0.0", "title": n.title(), "description": "", "console": {}, "assigned": True,
                 "active": n in self.enabled_packs} for n in ["finance", "logistics"] if n in self.enabled_packs or self.enabled_packs]

    chosen = None

    async def choose_packs(self, active, ident):
        if not active:
            raise BackendError(403, "DRS-5002", "choose one or more")
        self.enabled_packs = active
        return {"assigned": ["finance", "logistics"], "active": active}

    saved_workspaces = {}

    async def workspaces(self, ident):
        return sorted(self.saved_workspaces)

    async def workspace(self, name, ident):
        if name not in self.saved_workspaces:
            raise BackendError(404, "DRS-1001", "no workspace")
        return self.saved_workspaces[name]

    async def save_workspace(self, name, body, ident):
        if not body.get("panes"):
            raise BackendError(400, "DRS-5001", "a workspace has 1 to 4 panes")
        self.saved_workspaces[name] = body
        return body

    async def delete_workspace(self, name, ident):
        self.saved_workspaces.pop(name, None)

    async def business_date(self, ident=None):
        from core import asof
        sel = asof.current()
        return {"current": "2026-09-30", "selected": "2026-09-30" if sel == "live" else ("2026-09-25" if sel == "2026-09-26" else sel),
                "live": sel == "live", "previous": "2026-09-29", "earliest": "2021-09-30", "calendar": "USNY", "zone": "America/New_York",
                "holidays": ["2026-10-12", "2026-11-11", "2026-11-26"]}

    async def about(self, ident=None):
        return {"product": "Drishti", "version": "1.2.0", "built": "2026-09-30T12:00:00Z", "java": "21.0.12 (Ubuntu)",
                "uptimeSeconds": 3725, "sutras": ["irs-vanilla v3"], "securityEnabled": False,
                "sources": [{"name": "demo", "version": "1.0", "kinds": [], "live": True, "search": True, "reverseLookup": True, "health": "UP"}]}

    aliases_saved = {"MYBOOK": "BOOK BOOK-RATES-1"}
    tests_saved = {}

    async def studio_tests(self, sutra, ident=None):
        return list(self.tests_saved.get(sutra, []))

    async def set_studio_tests(self, sutra, entities, ident=None):
        self.tests_saved[sutra] = list(entities)
        return list(entities)

    async def command_history(self, ident=None):
        return ["TRD T-10001", "MKT"]

    async def aliases(self, ident=None):
        return dict(self.aliases_saved)

    async def set_aliases(self, aliases, ident=None):
        if "TRD" in {k.upper() for k in aliases}:
            raise BackendError(400, "DRS-5001", "'TRD' is already a mnemonic or a pack code; choose another name")
        self.aliases_saved = {k.upper(): v for k, v in aliases.items()}
        return dict(self.aliases_saved)

    async def pack_overview(self, name, ident=None):
        if name.upper() not in ("MKT", "MARKET-DATA"):
            raise BackendError(400, "DRS-5001", f"no pack '{name}' is switched on")
        return {"name": "market-data", "code": "MKT", "title": "Market data", "description": "Curves and surfaces.", "version": "1.0.0",
                "extends": ["banking-core"], "kinds": [
                    {"kind": "fx-vol-surface", "mnemonic": "FXV", "label": "FX volatility surface", "count": 12, "more": False,
                     "example": "FXV-EURUSD", "columns": []},
                    {"kind": "ir-curve", "mnemonic": "CRV", "label": "Interest-rate curve", "count": 1234, "more": False,
                     "example": "CRV-USD-SOFR", "columns": ["currency", "index"]}]}

    async def command(self, text, ident=None):
        if text.strip().upper() == "MKT":
            return {"ref": None, "pack": "market-data"}
        if "IRS-48213" in text.upper():
            return {"ref": {"kind": "trade", "id": "IRS-48213"}, "mnemonic": "TRD"}
        raise BackendError(400, "DRS-4001", f"cannot read command '{text}'")


@pytest.fixture(scope="session")
def backend():
    return FakeBackend()


@pytest.fixture(scope="session")
def client(backend):
    app = create_app(load_settings(CONSOLE / "config"))
    app.state.backend = backend
    return TestClient(app)
