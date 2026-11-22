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

"""Wave D in the console (docs/guides/bi/README.md sections 29.8 to 29.11, bi/README.md, EMBED_GUIDE.md): the Subscribe and alert forms offer webhooks and texts only when the server has them and send them in the server's
words (no AI summary: AI is not in this release's console)."""
from __future__ import annotations

import json
import re
from urllib.parse import quote, urlencode


FORM = {"Content-Type": "application/x-www-form-urlencoded"}


def _target():
    return json.dumps({"type": "report", "id": "file:report:daily", "page": "main", "visual": "", "view": ""}, separators=(",", ":"))


# ---- the Subscribe form and the alert form ------------------------------------------------------------------------------------------------------

def test_the_subscribe_form_offers_webhooks_and_texts_only_when_the_server_has_them_and_no_ai_summary(client, backend):
    backend.notify_kept = {"webhooks": [], "sms": {"configured": False, "maxChars": 160, "phone": ""}}
    bare = client.get("/reports/subscribe", params={"type": "report", "id": "file:report:daily"}).text
    assert "data-narrative" not in bare and "data-webhooks" not in bare and "data-sms" not in bare
    backend.notify_kept = {"webhooks": ["ops", "risk-room"], "sms": {"configured": True, "maxChars": 160, "phone": "**********67"}}
    page = client.get("/reports/subscribe", params={"type": "report", "id": "file:report:daily"}).text
    assert "data-narrative" not in page                                                   # AI is not in this release's console
    assert 'name="webhooks" value="ops"' in page and 'name="webhooks" value="risk-room"' in page
    assert 'name="sms"' in page and 'name="phone"' in page and "**********67" in page and "never a figure" in page


def test_a_subscription_is_saved_with_its_webhooks_and_texts_and_the_number_is_kept_first(client, backend):
    backend.subs_kept.clear()
    backend.phones_set.clear()
    body = urlencode([("name", "Out"), ("schedule", "daily 07:00"), ("date", "today"), ("format", "html"), ("recipients", "ash"), ("webhooks", "ops"),
                      ("webhooks", "risk-room"), ("sms", "on"), ("phone", " +44 20 7946 0000 "), ("enabled", "on"), ("target", _target())])
    r = client.post("/reports/subscribe", content=body, headers=FORM, follow_redirects=False)
    assert r.status_code == 303
    kept = backend.subs_kept["Out"]
    assert "narrative" not in kept and kept["webhooks"] == ["ops", "risk-room"] and kept["sms"] is True
    assert backend.phones_set == ["+44 20 7946 0000"]
    client.post("/reports/subscribe", content="name=Plain&schedule=daily+07%3A00&date=today&format=html&recipients=ash&target=" + quote(_target()), headers=FORM)
    plain = backend.subs_kept["Plain"]
    assert plain["webhooks"] == [] and plain["sms"] is False and backend.phones_set == ["+44 20 7946 0000"]      # no number typed: none sent


def test_the_history_shows_the_channel_of_each_delivery(client, backend):
    backend.subs_kept.clear()
    client.post("/reports/subscribe", content="name=Hist&schedule=daily+07%3A00&date=today&format=html&recipients=ash&target=" + quote(_target()), headers=FORM)
    backend.subs_kept["Hist"]["runs"] = [{"id": "r1", "at": "2026-10-01T09:00:00Z", "trigger": "by hand", "status": "ok", "sent": 2, "skipped": 0, "failed": 0, "retrying": 0,
                                          "deliveries": [{"to": "a@x.test", "status": "sent", "attempts": 1, "narrative": "written"},
                                                         {"to": "webhook:ops", "channel": "webhook", "status": "sent", "attempts": 2},
                                                         {"to": "a@x.test", "status": "sent", "attempts": 1, "narrative": "skipped: the AI policy lets the model see labels only"}]}]
    page = client.get("/reports").text
    assert "webhook:ops · sent (attempt 2)" in page and "summary:" not in page


def _alert_schema(page: str) -> dict:
    """The alert form's schema and value as the page hands them to drishti-inputs' Form."""
    import html
    import json
    import re
    m = re.search(r'data-alert-fields data-schema="([^"]*)"', page)
    assert m, "the page carries the form's schema"
    return json.loads(html.unescape(m.group(1)))


def test_the_alert_form_offers_webhooks_and_texts_and_sends_them(client, backend):
    backend.alerts_kept.clear()
    backend.phones_set.clear()
    backend.notify_kept = {"webhooks": ["ops"], "sms": {"configured": True, "maxChars": 160, "phone": ""}}
    page = client.get("/alerts/new", params={"source": "visual", "report": "file:report:daily", "page": "main", "visual": "net"}).text
    to = _alert_schema(page)["schema"]["properties"]["deliver"]["properties"]                                # the form's schema (core/alert_form.py)
    assert to["w_ops"]["name"] == "w_ops" and to["d_sms"]["name"] == "d_sms" and to["phone"]["name"] == "phone" and "no number" in page
    base = {"name": "Net", "severity": "warn", "source_type": "visual", "report": "file:report:daily", "page": "main", "visual": "net", "reduce": "first", "condition_type": "threshold",
            "op": "above", "value": "1000", "trigger_mode": "schedule", "schedule": "hourly", "d_inbox": "on", "enabled": "on", "w_ops": "on", "d_sms": "on", "phone": "+15551234567"}
    r = client.post("/alerts/new", content=urlencode(base), headers=FORM, follow_redirects=False)
    assert r.status_code == 303
    a = backend.alerts_kept["Net"]
    assert a["delivery"]["webhooks"] == ["ops"] and a["delivery"]["sms"] is True and backend.phones_set == ["+15551234567"]
    backend.notify_kept = {"webhooks": [], "sms": {"configured": False, "maxChars": 160, "phone": ""}}
    bare = client.get("/alerts/new", params={"source": "visual", "report": "file:report:daily"}).text
    assert 'name="d_sms"' not in bare and 'name="w_' not in bare
