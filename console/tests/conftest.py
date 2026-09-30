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

    async def raw(self, kind, id_):
        return {"ref": {"kind": kind, "id": id_}, "provenance": {"source": "aero-risk", "generation": 1742}, "data": {"tradeId": id_}}

    async def suggest(self, q, user, limit=10):
        self.calls.append(("suggest", q))
        return json.loads((FIXTURES / "suggest.json").read_text())

    async def command(self, text):
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
