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
        return "sutra: irs-vanilla\nversion: 3\n"

    async def studio_settings(self, ident=None):
        return {"save": False}

    async def preview(self, yaml_text, kind, id_, ident=None):
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
        if path == "/status":
            return {"defaultAdminPasswordInUse": True, "users": 1, "forceChangeOnCreate": False}
        if path == "/audit":
            return [{"at": "2026-09-30T12:00:00Z", "actor": "system", "action": "user-seeded", "subject": "drishti-dev-admin", "detail": ""}]
        if path == "/users" and method == "POST":
            if body.get("username") in self.users:
                raise BackendError(409, "DRS-6002", "exists")
            return {**body, "enabled": True}
        return {"ok": True}

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

    async def about(self, ident=None):
        return {"product": "Drishti", "version": "1.2.0", "built": "2026-09-30T12:00:00Z", "java": "21.0.12 (Ubuntu)",
                "uptimeSeconds": 3725, "sutras": ["irs-vanilla v3"], "securityEnabled": False,
                "sources": [{"name": "demo", "version": "1.0", "kinds": [], "live": True, "search": True, "reverseLookup": True, "health": "UP"}]}

    async def command(self, text, ident=None):
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
