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

"""Alerts on numbers in the console: the form (filled from what the person pressed *Alert me* on), saving, the list on /alerts with the older rules, the edit page with
the history, the actions, the preview, the administrators' overview, and the inbox tab. The server (Java) reads every number as the person and keeps every state;
here the console only builds the body, passes it on, and says in words what came back."""
from __future__ import annotations

import json
from urllib.parse import urlencode

FORM = {"Content-Type": "application/x-www-form-urlencoded"}


def _form(**kw) -> str:
    base = {"name": "Net", "title": "", "severity": "warn", "source_type": "visual", "report": "file:report:daily", "page": "main", "visual": "net", "measure": "", "reduce": "first",
            "condition_type": "threshold", "op": "above", "value": "1000", "trigger_mode": "schedule", "schedule": "hourly", "hysteresis": "50", "cooldown": "1h",
            "d_inbox": "on", "enabled": "on"}
    base.update(kw)
    return urlencode({k: v for k, v in base.items() if v is not None})


def test_the_form_is_filled_from_the_visual_the_person_pressed_alert_me_on(client):
    state = json.dumps({"slicers": {"ccy": ["USD"]}, "selections": [], "drill": []})
    page = client.get("/alerts/new", params={"source": "visual", "report": "file:report:daily", "page": "main", "visual": "net", "state": state, "title": "Daily risk: Net"}).text
    assert 'name="report" class="studio-in mono" value="file:report:daily"' in page and 'name="visual" class="studio-in mono" value="net"' in page
    assert 'name="name" class="studio-in" value="Daily risk - Net"' in page and "data-name-rule" in page and 'pattern="[A-Za-z0-9]' in page          # ':' is outside the server's name rule
    assert "With the filters you had set on the screen" in page and "&#34;ccy&#34;" in page                         # the state rides in a hidden field
    assert "The number is read as you" in page.replace("\n", " ").replace("  ", " ") or "The number is read as you" in page
    for word in ("crosses a limit", "changes by too much", "has no data by a time", "on a schedule", "after a data load", "when it changes", "my inbox and the bell",
                 "Hysteresis", "Cooldown", "Check now"):
        assert word in page
    field = client.get("/alerts/new", params={"source": "field", "kind": "trade", "id": "IRS-48213", "field": "mtm", "title": "MTM of IRS-48213"}).text
    assert '<option value="field" selected>' in field and 'name="kind" class="studio-in mono" value="trade"' in field and 'name="field" class="studio-in mono" value="mtm"' in field


def test_saving_builds_the_servers_body_from_the_form(client, backend):
    backend.alerts_kept.clear()
    r = client.post("/alerts/new", content=_form(state=json.dumps({"slicers": {"ccy": ["USD"]}})), headers=FORM, follow_redirects=False)
    assert r.status_code == 303 and "Alert%20saved%3A%20Net" in r.headers["location"]
    a = backend.alerts_kept["Net"]
    assert a["source"] == {"type": "visual", "report": "file:report:daily", "page": "main", "visual": "net", "measure": "", "reduce": "first", "state": {"slicers": {"ccy": ["USD"]}}}
    assert a["condition"] == {"type": "threshold", "op": "above", "value": 1000.0}
    assert a["trigger"] == {"mode": "schedule", "schedule": "hourly"} and a["hysteresis"] == 50.0 and a["cooldown"] == "1h"
    assert a["delivery"] == {"inbox": True, "email": False, "bridge": False, "webhooks": [], "sms": False} and a["enabled"] is True


def test_every_kind_of_source_condition_and_trigger_is_sent_in_the_servers_words(client, backend):
    backend.alerts_kept.clear()
    client.post("/alerts/new", content=_form(name="DS", source_type="dataset", dataset="file:dataset:trading", ds_measure="exposure", filters="desk eq FX, Rates\nccy ne USD",
                                              condition_type="change", change_mode="pct", change_op="falls", change_value="5", against="previous-day", trigger_mode="after-load",
                                              load_kind="TRD", d_email="on", d_bridge="on", hysteresis="", cooldown=""), headers=FORM)
    a = backend.alerts_kept["DS"]
    assert a["source"] == {"type": "dataset", "dataset": "file:dataset:trading", "measure": "exposure",
                           "filters": [{"field": "desk", "op": "eq", "values": ["FX", "Rates"]}, {"field": "ccy", "op": "ne", "values": ["USD"]}]}
    assert a["condition"] == {"type": "change", "mode": "pct", "op": "falls", "against": "previous-day", "value": 5.0}
    assert a["trigger"] == {"mode": "after-load", "kind": "TRD"} and a["delivery"] == {"inbox": True, "email": True, "bridge": True, "webhooks": [], "sms": False}
    assert "hysteresis" not in a or a["hysteresis"] == 0
    client.post("/alerts/new", content=_form(name="F", source_type="field", kind="trade", id="IRS-48213", field="mtm", condition_type="nodata", by="09:00", trigger_mode="live",
                                              debounce="5s"), headers=FORM)
    f = backend.alerts_kept["F"]
    assert f["source"] == {"type": "field", "kind": "trade", "id": "IRS-48213", "field": "mtm"} and f["condition"] == {"type": "nodata", "by": "09:00"}
    assert f["trigger"] == {"mode": "live", "debounce": "5s"}
    client.post("/alerts/new", content=_form(name="B", op="between", value="10", high="20"), headers=FORM)
    assert backend.alerts_kept["B"]["condition"] == {"type": "threshold", "op": "between", "value": 10.0, "high": 20.0}


def test_a_refusal_keeps_what_was_typed_and_says_why(client, backend):
    r = client.post("/alerts/new", content=_form(name="Bad", op="under"), headers=FORM)
    assert r.status_code == 422 and "DRS-9062" in r.text and "condition.op is above, below, between or outside" in r.text
    assert 'name="name" class="studio-in" value="Bad"' in r.text and 'value="file:report:daily"' in r.text and 'value="1000"' in r.text
    r = client.post("/alerts/new", content=_form(name="Bad2", value="lots"), headers=FORM)
    assert r.status_code == 422 and "condition.value is a number" in r.text
    assert "Bad" not in backend.alerts_kept and "Bad2" not in backend.alerts_kept


def test_the_alerts_page_lists_alerts_on_numbers_beside_the_older_rules(client, backend):
    backend.alerts_kept.clear()
    client.post("/alerts/new", content=_form(), headers=FORM)
    page = client.get("/alerts").text
    assert 'data-alert-row="Net"' in page and "above 1000" in page and "file:report:daily · net" in page and 'data-standing>armed<' in page
    assert "Rules on one entity" in page and "New rule" in page and "New alert on a number" in page and 'href="/alerts/new"' in page
    looked = client.post("/alerts/measure/Net/check", follow_redirects=True).text
    assert "Net: looked now. Fired:" in looked and 'data-standing>active<' in looked and "Recent alerts on numbers" in looked
    paused = client.post("/alerts/measure/Net/pause", follow_redirects=True).text
    assert "Paused Net" in paused and ">Resume<" in paused and 'data-standing>paused<' in paused and backend.alerts_kept["Net"]["enabled"] is False
    assert "Resumed Net" in client.post("/alerts/measure/Net/resume", follow_redirects=True).text
    assert "Deleted Net" in client.post("/alerts/measure/Net/delete", follow_redirects=True).text and "Net" not in backend.alerts_kept


def test_the_edit_page_has_the_form_filled_in_and_the_history(client, backend):
    backend.alerts_kept.clear()
    client.post("/alerts/new", content=_form(d_email="on"), headers=FORM)
    client.post("/alerts/measure/Net/check")
    page = client.get("/alerts/measure/Net").text
    assert "Alert: Net" in page and 'value="file:report:daily"' in page and 'value="1000.0"' in page and 'name="d_email" checked' in page and "readonly" in page
    assert 'data-history' in page and "fired" in page and "now 1,200 (above 1000)" in page and "inbox: ok" in page and "email: off" not in page
    saved = client.post("/alerts/measure/Net", content=_form(value="2000"), headers=FORM, follow_redirects=False)
    assert saved.status_code == 303 and backend.alerts_kept["Net"]["condition"]["value"] == 2000.0
    assert "armed afresh" in client.get(saved.headers["location"]).text
    missing = client.get("/alerts/measure/Nope")
    assert missing.status_code == 404 and "DRS-1001" in missing.text


def test_check_now_asks_the_server_what_the_alert_reads_and_never_saves(client, backend):
    backend.alerts_kept.clear()
    body = {k: v for k, v in dict(source_type="visual", report="r", visual="v", measure="", condition_type="threshold", op="above", value="1000", trigger_mode="schedule").items()}
    r = client.post("/alerts/preview", json=body)
    assert r.status_code == 200 and r.json()["status"] == "ok" and r.json()["value"] == 1234.5 and r.json()["holds"] is True
    masked = client.post("/alerts/preview", json={**body, "measure": "secret"}).json()
    assert masked["status"] == "masked" and masked["value"] is None
    assert backend.alerts_kept == {}


def test_administrators_see_everyones_alerts_and_the_board_tiles_that_notify(client, backend):
    backend.alerts_kept.clear()
    client.post("/alerts/new", content=_form(), headers=FORM)
    page = client.get("/alerts/all").text
    assert 'data-alert-row="' in page and "Net" in page and "Board tiles that notify" in page and 'data-board-row="desk-wall/t1"' in page
    assert "Desk wall · Net exposure" in page and "2 people" in page and "inbox, email" in page and "is now red" in page and "bob" in page
    assert "Paused ash/Net" in client.post("/alerts/all/ash/Net/pause", follow_redirects=True).text.replace("\n", " ") or backend.alerts_kept["Net"]["enabled"] is False
    assert "Deleted ash/Net" in client.post("/alerts/all/ash/Net/delete", follow_redirects=True).text and "Net" not in backend.alerts_kept
    assert client.post("/alerts/all/ash/Net/explode").status_code == 404


class _Inbox:
    """The collaboration calls the inbox page makes: one alert row, as the server renders it."""

    async def inbox(self, ident, type_="", unread=False, limit=50, before=0):
        return [{"seq": 4, "at": "2026-10-11T15:00:00Z", "type": "alert", "actor": "ash", "kind": "alert", "id": "/bi/r/file:report:daily/main", "read": False, "access": True,
                 "title": "Daily risk: Net exposure: now 1,200 (above 1000)"}]

    async def unread(self, ident):
        return 1


def test_the_inbox_has_an_alerts_tab_and_an_alert_row_opens_what_it_is_about(client):
    client.app.state.collab = _Inbox()
    try:
        page = client.get("/inbox").text
    finally:
        client.app.state.collab = None
    assert "/inbox?tab=alert" in page and ">Alerts<" in page
    assert 'href="/bi/r/file:report:daily/main"' in page and "now 1,200 (above 1000)" in page and "Alert" in page
    from routes.collab_routes import _row_href
    assert _row_href({"type": "alert", "id": "/bi/r/file:report:daily/main", "access": True}) == "/bi/r/file:report:daily/main"
    assert _row_href({"type": "alert", "id": "//evil.example/x", "access": True}) == ""                                   # a row only ever opens a path on this console
    assert _row_href({"type": "alert", "id": "https://evil.example/x", "access": True}) == ""


def test_the_reports_menu_and_the_view_page_lead_to_alerts(client):
    top = client.get("/reports").text
    assert 'href="/alerts#measure"' in top and "My alerts" in top and 'href="/alerts/all"' in top
    assert "/alerts/new?source=field" in (client.get("/static/js/history.js").text.replace(" ", "") .replace("'", "")) or "alerts/new?source=field" in client.get("/static/js/history.js").text
    assert "Alert me about this visual" in client.get("/static/js/bi-report.js").text
