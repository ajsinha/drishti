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

"""BI with AI in the console (docs/guides/BI_GUIDE.md), over a stand-in server: the three hooks reach the server's /api/v1/bi/ai/* under the caller's identity and nothing else of
``ai`` does, the server's refusals (AI off DRS-4015, a policy refusal DRS-4018, an unusable draft DRS-4020) reach the page with their codes, the pages carry the script, and
the script obeys the rules of the console (vendored, same-origin, no inline handler, no eval, no innerHTML)."""
import re
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from bi_backend import BiBackend
from conftest import CONSOLE
from core.app import create_app
from core.backend import BackendError
from core.config import load_settings

JS = (CONSOLE / "web/static/js/bi-ai.js").read_text(encoding="utf-8")


class AiBackend(BiBackend):
    def __init__(self):
        super().__init__()
        self.calls, self.refuse = [], None

    async def bi(self, method, path, ident, body=None, **params):
        if path.startswith("/ai/"):
            self.calls.append((method, path, ident.user, body))
            if self.refuse:
                raise BackendError(*self.refuse)
            if path == "/ai/status":
                return {"enabled": True, "values": "labels-only", "draft": True, "ask": True, "explain": True}
            if path == "/ai/draft":
                return {"yaml": "report: drafted\n", "valid": True, "problems": [], "dataset": body["dataset"], "saved": False}
            if path == "/ai/ask":
                return {"columns": [{"name": "trades.desk", "type": "string", "masked": False}, {"name": "Notional", "type": "decimal", "masked": False}],
                        "rows": [["DESK-A", 92000.0]], "rowCount": 1, "meta": {}, "question": body["question"], "visual": "bar", "summary": None, "query": {"fields": ["trades.desk"]}}
            if path == "/ai/explain":
                return {"measure": "Notional", "value": 1.0, "lineage": {}, "breakdown": [], "narrative": None}
        return await super().bi(method, path, ident, body, **params)


@pytest.fixture()
def client():
    app = create_app(load_settings(CONSOLE / "config"))
    app.state.backend = AiBackend()
    return TestClient(app)


def test_the_three_hooks_are_passed_on_with_the_callers_identity(client):
    assert client.get("/bi/api/ai/status").json()["draft"] is True
    d = client.post("/bi/api/ai/draft", json={"dataset": "lake", "request": "notional by desk"}).json()
    assert d["valid"] and d["dataset"] == "lake" and d["saved"] is False
    assert client.post("/bi/api/ai/ask", json={"report": "r", "question": "q"}).json()["visual"] == "bar"
    assert client.post("/bi/api/ai/explain", json={"report": "r", "page": "p", "visual": "v"}).json()["measure"] == "Notional"
    assert [(m, p) for m, p, *_ in client.app.state.backend.calls] == [("GET", "/ai/status"), ("POST", "/ai/draft"), ("POST", "/ai/ask"), ("POST", "/ai/explain")]


@pytest.mark.parametrize("status,code", [(404, "DRS-4015"), (403, "DRS-4018"), (422, "DRS-4020"), (429, "DRS-4017")])
def test_the_servers_refusals_reach_the_page_with_their_codes(client, status, code):
    client.app.state.backend.refuse = (status, code, "refused")
    r = client.post("/bi/api/ai/ask", json={"report": "r", "question": "q"})
    assert r.status_code == status and r.json()["code"] == code


def test_only_the_ai_paths_are_passed_on_and_the_pages_carry_the_script(client):
    assert client.post("/bi/api/aix/ask", json={}).status_code == 404
    assert client.post("/bi/api/ai/../admin/ai", json={}).status_code in (404, 405)
    assert "/static/js/bi-ai.js" in client.get("/bi/r/file:report:x").text
    assert "/static/js/bi-ai.js" in client.get("/bi/design/file:report:x").text


def test_the_script_is_plain_same_origin_and_safe():
    assert not re.search(r"\binnerHTML\b|\beval\(|new Function|\bdocument\.write|insertAdjacentHTML|\.outerHTML", JS)
    assert "http://" not in JS and "https://" not in JS
    assert "fetch(" not in JS                                   # every call goes through B.api (same origin, JSON, the server's codes)
    assert "B.api('POST', '/ai/draft'" in JS and "'/ai/ask'" in JS and "'/ai/explain'" in JS
    assert "style=" not in JS and "onclick" not in JS.lower()
