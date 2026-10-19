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

"""Wave D in the console (docs/guides/BI_AI.md sections 8 to 11, DISTRIBUTION.md, ELEMENTS.md): the designer copilot, the check explanations and the dataset helper reach the server as
the caller and their scripts obey the console's rules; the Subscribe and alert forms offer narrative, webhooks and texts only when the server has them and send them in the
server's words; ``<drishti-search>`` and ``<drishti-about>`` are in the library, served, and ``/embed/v1/search`` passes the embed caller to the server and its refusals back."""
from __future__ import annotations

import json
import re
from urllib.parse import quote, urlencode

import pytest
from fastapi.testclient import TestClient

from bi_backend import BiBackend
from conftest import CONSOLE
from core import element_dist as dist
from core.app import create_app
from core.backend import BackendError
from core.config import load_settings
from test_embed import HOST, make_client, token

FORM = {"Content-Type": "application/x-www-form-urlencoded"}
JS = lambda name: (CONSOLE / "web" / name).read_text(encoding="utf-8")  # noqa: E731


def _target():
    return json.dumps({"type": "report", "id": "file:report:daily", "page": "main", "visual": "", "view": ""}, separators=(",", ":"))


# ---- the designer copilot, the check explanations, the dataset helper ----------------------------------------------------------------------------

class AssistBackend(BiBackend):
    def __init__(self):
        super().__init__()
        self.ai_calls, self.refuse = [], None

    async def bi(self, method, path, ident, body=None, **params):
        if path.startswith("/ai/"):
            self.ai_calls.append((method, path, ident.user, body))
            if self.refuse:
                raise BackendError(*self.refuse)
            if path == "/ai/status":
                return {"enabled": True, "values": "labels-only", "draft": True, "ask": True, "explain": True, "assist": True}
            if path == "/ai/suggest":
                return {"dataset": body["dataset"], "applied": False, "suggestions": [{"id": "by-desk", "kind": "visual", "type": "bar", "title": "Notional by desk", "why": "which desks",
                                                                                       "visual": {"id": "by-desk", "type": "bar"}, "yaml": "- id: by-desk\n  type: bar\n"}]}
            if path == "/ai/explain-checks":
                return {"valid": False, "problems": [{"level": "error", "code": "DRS-9034", "path": "pages[0]", "message": "m", "explain": "plain", "fix": "do this"}], "explained": 1}
            if path == "/ai/dataset-helper":
                return {"dataset": body["dataset"], "applied": False, "items": [{"id": "measure:Average notional", "kind": "measure", "label": "Average notional", "why": "", "yaml": "Average notional: { expr: \"avg(trades.notional)\" }", "data": {}}]}
        return await super().bi(method, path, ident, body, **params)


@pytest.fixture()
def assist():
    app = create_app(load_settings(CONSOLE / "config"))
    app.state.backend = AssistBackend()
    return TestClient(app)


def test_the_three_helpers_are_passed_on_with_the_callers_identity_and_their_refusals_reach_the_page(assist):
    s = assist.post("/bi/api/ai/suggest", json={"dataset": "lake", "goal": "g", "yaml": "x"}).json()
    assert s["applied"] is False and s["suggestions"][0]["id"] == "by-desk"
    assert assist.post("/bi/api/ai/explain-checks", json={"yaml": "x"}).json()["problems"][0]["explain"] == "plain"
    assert assist.post("/bi/api/ai/dataset-helper", json={"dataset": "lake"}).json()["items"][0]["kind"] == "measure"
    assert [(m, p) for m, p, *_ in assist.app.state.backend.ai_calls] == [("POST", "/ai/suggest"), ("POST", "/ai/explain-checks"), ("POST", "/ai/dataset-helper")]
    assist.app.state.backend.refuse = (403, "DRS-4018", "the AI policy does not open bi-suggest to you")
    r = assist.post("/bi/api/ai/suggest", json={"dataset": "lake", "goal": "g"})
    assert r.status_code == 403 and r.json()["code"] == "DRS-4018"


def test_the_pages_carry_the_helper_scripts_and_the_scripts_are_plain_same_origin_and_safe(assist):
    assert "/static/js/bi-design-copilot.js" in assist.get("/bi/design/file:report:x").text
    model = assist.get("/bi/m/file:dataset:x").text
    assert "data-ai-helper" in model and "data-ai-box" in model and "/static/css/bi-ai.css" in model
    for name in ("static/js/bi-design-copilot.js", "static/js/bi-model.js"):
        js = JS(name)
        assert not re.search(r"\binnerHTML\b|\beval\(|new Function|\bdocument\.write|insertAdjacentHTML|\.outerHTML", js), name
        assert "style=" not in js and "onclick" not in js.lower(), name
        assert name.endswith("bi-model.js") or ("http://" not in js and "https://" not in js), name              # bi-model.js has the SVG namespace, which is a name, not an address
    copilot = JS("static/js/bi-design-copilot.js")
    assert "fetch(" not in copilot and "B.api('POST', '/ai/suggest'" in copilot and "'/ai/explain-checks'" in copilot       # every call through B.api
    assert "D.change(" in copilot, "a suggestion is added as an undoable edit of the draft, only on Add"
    assert "'/ai/dataset-helper'" in JS("static/js/bi-model.js")


# ---- the Subscribe form and the alert form ------------------------------------------------------------------------------------------------------

def test_the_subscribe_form_offers_narrative_webhooks_and_texts_only_when_the_server_has_them(client, backend):
    backend.ai_status = {"enabled": False, "values": "labels-only", "assist": False}
    backend.notify_kept = {"webhooks": [], "sms": {"configured": False, "maxChars": 160, "phone": ""}}
    bare = client.get("/reports/subscribe", params={"type": "report", "id": "file:report:daily"}).text
    assert "data-narrative" not in bare and "data-webhooks" not in bare and "data-sms" not in bare
    backend.ai_status = {"enabled": True, "values": "labels-only", "assist": True}
    backend.notify_kept = {"webhooks": ["ops", "risk-room"], "sms": {"configured": True, "maxChars": 160, "phone": "**********67"}}
    page = client.get("/reports/subscribe", params={"type": "report", "id": "file:report:daily"}).text
    assert "data-narrative" in page and "labels only" in page                              # the policy here shows labels only: the form says the summary would be left out
    assert 'name="webhooks" value="ops"' in page and 'name="webhooks" value="risk-room"' in page
    assert 'name="sms"' in page and 'name="phone"' in page and "**********67" in page and "never a figure" in page
    backend.ai_status["values"] = "aggregates"
    assert "labels only" not in client.get("/reports/subscribe", params={"type": "report", "id": "file:report:daily"}).text


def test_a_subscription_is_saved_with_its_narrative_webhooks_and_texts_and_the_number_is_kept_first(client, backend):
    backend.subs_kept.clear()
    backend.phones_set.clear()
    body = urlencode([("name", "Out"), ("schedule", "daily 07:00"), ("date", "today"), ("format", "html"), ("recipients", "ash"), ("narrative", "on"), ("webhooks", "ops"),
                      ("webhooks", "risk-room"), ("sms", "on"), ("phone", " +44 20 7946 0000 "), ("enabled", "on"), ("target", _target())])
    r = client.post("/reports/subscribe", content=body, headers=FORM, follow_redirects=False)
    assert r.status_code == 303
    kept = backend.subs_kept["Out"]
    assert kept["narrative"] is True and kept["webhooks"] == ["ops", "risk-room"] and kept["sms"] is True
    assert backend.phones_set == ["+44 20 7946 0000"]
    client.post("/reports/subscribe", content="name=Plain&schedule=daily+07%3A00&date=today&format=html&recipients=ash&target=" + quote(_target()), headers=FORM)
    plain = backend.subs_kept["Plain"]
    assert plain["narrative"] is False and plain["webhooks"] == [] and plain["sms"] is False and backend.phones_set == ["+44 20 7946 0000"]      # no number typed: none sent


def test_the_history_shows_the_narrative_outcome_and_the_channel_of_each_delivery(client, backend):
    backend.subs_kept.clear()
    client.post("/reports/subscribe", content="name=Hist&schedule=daily+07%3A00&date=today&format=html&recipients=ash&target=" + quote(_target()), headers=FORM)
    backend.subs_kept["Hist"]["runs"] = [{"id": "r1", "at": "2026-10-01T09:00:00Z", "trigger": "by hand", "status": "ok", "sent": 2, "skipped": 0, "failed": 0, "retrying": 0,
                                          "deliveries": [{"to": "a@x.test", "status": "sent", "attempts": 1, "narrative": "written"},
                                                         {"to": "webhook:ops", "channel": "webhook", "status": "sent", "attempts": 2},
                                                         {"to": "a@x.test", "status": "sent", "attempts": 1, "narrative": "skipped: the AI policy lets the model see labels only"}]}]
    page = client.get("/reports").text
    assert "summary: written" in page and "webhook:ops · sent (attempt 2)" in page and "summary: skipped: the AI policy lets the model see labels only" in page


def test_the_alert_form_offers_webhooks_and_texts_and_sends_them(client, backend):
    backend.alerts_kept.clear()
    backend.phones_set.clear()
    backend.notify_kept = {"webhooks": ["ops"], "sms": {"configured": True, "maxChars": 160, "phone": ""}}
    page = client.get("/alerts/new", params={"source": "visual", "report": "file:report:daily", "page": "main", "visual": "net"}).text
    assert 'name="w_ops"' in page and 'name="d_sms"' in page and 'name="phone"' in page and "no number" in page
    base = {"name": "Net", "severity": "warn", "source_type": "visual", "report": "file:report:daily", "page": "main", "visual": "net", "reduce": "first", "condition_type": "threshold",
            "op": "above", "value": "1000", "trigger_mode": "schedule", "schedule": "hourly", "d_inbox": "on", "enabled": "on", "w_ops": "on", "d_sms": "on", "phone": "+15551234567"}
    r = client.post("/alerts/new", content=urlencode(base), headers=FORM, follow_redirects=False)
    assert r.status_code == 303
    a = backend.alerts_kept["Net"]
    assert a["delivery"]["webhooks"] == ["ops"] and a["delivery"]["sms"] is True and backend.phones_set == ["+15551234567"]
    backend.notify_kept = {"webhooks": [], "sms": {"configured": False, "maxChars": 160, "phone": ""}}
    bare = client.get("/alerts/new", params={"source": "visual", "report": "file:report:daily"}).text
    assert 'name="d_sms"' not in bare and 'name="w_' not in bare


# ---- <drishti-search> and <drishti-about> ------------------------------------------------------------------------------------------------------------------

def test_the_library_defines_the_two_new_elements_and_serves_their_modules(backend):
    c = make_client(backend)
    entry = JS("embed/drishti-elements.js")
    assert "./m/about.js" in entry and "./m/search.js" in entry and "DrishtiAbout" in entry and "DrishtiSearch" in entry and "version: '" + dist.VERSION + "'" in entry
    for name, cls in (("about", "DrishtiAbout"), ("search", "DrishtiSearch")):
        r = c.get(f"/embed/v1/m/{name}.js", headers={"Origin": HOST})
        assert r.status_code == 200 and f"class {cls} extends HTMLElement" in r.text and r.headers["access-control-allow-origin"] == HOST
        v = c.get(f"/embed/v1/elements/{dist.VERSION}/m/{name}.js", headers={"Origin": HOST})
        assert v.status_code == 200 and v.headers["cache-control"].endswith("immutable") and v.text == r.text
    assert tuple(int(p) for p in dist.VERSION.split(".")) >= (1, 4, 1)          # the about and search elements are in 1.4.1 and later


def test_the_new_elements_draw_text_only_and_never_markup_of_their_own_making():
    search = JS("embed/search.js")
    assert not re.search(r"\binnerHTML\b|\beval\(|new Function|insertAdjacentHTML|\.outerHTML|document\.write", search)           # results are text nodes (textContent), no markup from the server
    assert "setHtml" not in search and "sanitize" not in search
    assert "min-height:44px" in search                                                                                             # results are 44 px targets
    about = JS("embed/about.js")
    assert "setHtml(" in about and not re.search(r"\binnerHTML\b|\beval\(|new Function", about)                                    # the About body goes through the sanitiser and the Trusted Types policy
    assert "rel', 'noopener'" in about


def test_the_search_passes_the_embed_caller_to_the_server_and_its_refusals_back(backend):
    c = make_client(backend)
    h = {"Authorization": f"Bearer {token()}", "Origin": HOST}
    ok = c.get("/embed/v1/search", params={"q": "MX-2000", "limit": 500}, headers=h)
    assert ok.status_code == 200 and ok.headers["access-control-allow-origin"] == HOST and ok.json()["results"][0]["id"] == "MX-20000001"
    assert ok.headers["cache-control"] == "no-store"
    assert backend.calls[-1][:2] == ("embed_search", "MX-2000") and backend.calls[-1][3] == 50                   # the console caps what it asks for; the server caps again
    assert c.get("/embed/v1/search", params={"q": "x" * 500}, headers=h).status_code == 200 and len(backend.calls[-1][1]) == 200
    assert c.get("/embed/v1/search", params={"q": "MX"}, headers={"Origin": HOST}).json()["code"] == "DRS-8001"           # no token
    assert c.get("/embed/v1/search", params={"q": "MX"}, headers={"Authorization": f"Bearer {token()}", "Origin": "http://evil.example"}).status_code == 403
    denied = c.get("/embed/v1/search", params={"q": "boom"}, headers=h)
    assert denied.status_code == 403 and denied.json()["code"] == "DRS-5002"                                      # the scope was not granted: the server says so
