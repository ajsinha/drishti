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

"""Distribution routes of the console: file downloads for a BI report and a view, the Subscribe form, the merged list on /reports, the actions
on a subscription and the administrators' list. The server (Java) makes every file and renders every mail; here the console only passes things on,
and the tests check what it passes and what the pages say."""
from __future__ import annotations

import json
from urllib.parse import quote

FORM = {"Content-Type": "application/x-www-form-urlencoded"}


def _target(**kw):
    return json.dumps({"type": "report", "id": "file:report:daily", "page": "main", "visual": "", "view": "", **kw}, separators=(",", ":"))


def test_a_bi_download_passes_the_state_and_keeps_the_servers_file_name(client, backend):
    backend.exports_made.clear()
    state = json.dumps({"slicers": {"ccy": ["USD"]}, "dates": {"from": "2026-10-08", "to": "2026-10-08"}})
    r = client.get("/dist/bi/file:report:daily.xlsx", params={"page": "main", "visual": "by-desk", "state": state})
    assert r.status_code == 200 and r.content == b"file:xlsx:file:report:daily"
    assert r.headers["content-type"].startswith("application/vnd.openxmlformats")
    assert 'filename="Daily risk.xlsx"' in r.headers["content-disposition"] and r.headers["cache-control"] == "no-store"
    fmt, target, _ = backend.exports_made[-1]
    assert fmt == "xlsx" and target["type"] == "report" and target["id"] == "file:report:daily"
    assert target["page"] == "main" and target["visual"] == "by-desk" and target["state"]["slicers"] == {"ccy": ["USD"]}
    for f, kind in (("pdf", "application/pdf"), ("png", "image/png"), ("csv", "text/csv")):
        assert client.get(f"/dist/bi/file:report:daily.{f}").headers["content-type"].startswith(kind)
    assert "state" not in backend.exports_made[-1][1]                          # no state in the address: none sent


def test_a_download_with_a_bad_format_a_bad_state_or_a_refusal_says_so_in_words(client, backend):
    assert client.get("/dist/bi/file:report:daily.docx").status_code == 404
    huge = json.dumps({"x": "y" * 30000})
    client.get("/dist/bi/file:report:daily.csv", params={"state": huge})
    assert "state" not in backend.exports_made[-1][1]                          # too long for an address: the page is exported without it, not refused
    client.get("/dist/bi/file:report:daily.csv", params={"state": "not json"})
    assert "state" not in backend.exports_made[-1][1]
    r = client.get("/dist/bi/forbidden.pdf")
    assert r.status_code == 403 and r.headers["content-type"].startswith("text/plain") and "DRS-9002" in r.text and "may not open" in r.text


def test_a_view_download_carries_only_the_controls_and_filters_of_the_page(client, backend):
    r = client.get("/dist/view/trade/IRS-48213.pdf", params={"ctl.leg": "L2", "f.cashflows.rate": "gt:1m", "evil": "x", "asOf": "2026-10-01"})
    assert r.status_code == 200
    fmt, target, _ = backend.exports_made[-1]
    assert fmt == "pdf" and target["type"] == "view" and target["id"] == "trade/IRS-48213"
    assert sorted(target["view"].split("&")) == ["ctl.leg=L2", "f.cashflows.rate=gt%3A1m"]
    assert client.get("/dist/view/trade/IRS-48213.csv").headers["content-type"].startswith("text/csv")


def test_the_subscribe_form_describes_what_is_subscribed_and_saves_a_subscription(client, backend):
    state = json.dumps({"slicers": {"ccy": ["USD"]}})
    page = client.get("/reports/subscribe", params={"type": "report", "id": "file:report:daily", "page": "main", "state": state, "title": "Daily risk"}).text
    assert "The BI report “Daily risk” (file:report:daily), page main, with the filters you have set" in page
    assert 'name="name" class="studio-in" value="Daily risk"' in page and "business-days 07:30" in page and "after-load" in page and "weekly mon 07:30 business" in page
    assert 'name="recipients"' in page and "role:risk" in page and "rendered for each recipient" in page.replace("\n", " ").replace("  ", " ") or "Each recipient gets their own render" in page
    view = client.get("/reports/subscribe", params={"type": "view", "id": "trade/IRS-48213", "view": "ctl.leg=L2"}).text
    assert "The view trade/IRS-48213 with your controls and filters (ctl.leg=L2)" in view
    body = ("name=Morning&schedule=business-days+07%3A30&date=previous&format=pdf&recipients=ash%2C+role%3Arisk&enabled=on&target=" + quote(_target(state={"slicers": {"ccy": ["USD"]}})))
    r = client.post("/reports/subscribe", content=body, headers=FORM, follow_redirects=False)
    assert r.status_code == 303 and "Subscribed" in r.headers["location"]
    kept = backend.subs_kept["Morning"]
    assert kept["recipients"] == ["ash", "role:risk"] and kept["format"] == "pdf" and kept["date"] == "previous" and kept["target"]["state"] == {"slicers": {"ccy": ["USD"]}}


def test_the_form_keeps_what_was_typed_when_the_server_refuses(client, backend):
    r = client.post("/reports/subscribe", content="name=Bad&schedule=whenever&date=today&format=html&recipients=ash&target=" + quote(_target()), headers=FORM)
    assert r.status_code == 422 and "DRS-9061" in r.text and "not a schedule" in r.text and 'value="whenever"' in r.text and 'value="Bad"' in r.text
    assert "data-subscribe-err" in r.text          # the refusal also stands beside the Subscribe button, not only at the top
    r = client.post("/reports/subscribe", content="name=Out&schedule=daily+07%3A00&date=today&format=html&recipients=boss%40elsewhere.com&target=" + quote(_target()), headers=FORM)
    assert r.status_code == 422 and "not a user" in r.text and 'value="boss@elsewhere.com"' in r.text


def test_one_list_holds_the_scheduled_reports_and_the_subscriptions(client, backend):
    backend.reports_kept.clear()
    backend.subs_kept.clear()
    client.post("/reports", content="name=Losers&query=TRD+where+mtm+%3C+0&schedule=business-days+18%3A30&deliver=folder&date=today&enabled=on", headers=FORM)
    client.post("/reports/subscribe", content="name=Morning&schedule=after-load&date=load&format=html&recipients=ash&target=" + quote(_target()), headers=FORM)
    page = client.get("/reports").text
    assert page.count('data-row-kind="report"') == 1 and page.count('data-row-kind="subscription"') == 1
    assert "Losers" in page and "Morning" in page and "after a data load" in page or "after-load" in page
    assert 'href="/bi/r/file:report:daily/main"' in page and "Run now" in page and ">Pause<" in page
    assert page.index("Losers") < page.index("Morning")                         # one list, by name
    client.post("/reports/Losers/delete")


def test_run_pause_resume_and_delete_a_subscription(client, backend):
    backend.subs_kept.clear()
    client.post("/reports/subscribe", content="name=Morning&schedule=daily+07%3A00&date=today&format=html&recipients=ash&target=" + quote(_target()), headers=FORM)
    ran = client.post("/reports/subscriptions/Morning/run", follow_redirects=True).text
    assert "Morning: 2 sent, 1 skipped, 1 failed" in ran and "refused" in ran and "History" in ran and "attempt 3" in ran
    assert "partial" in ran
    paused = client.post("/reports/subscriptions/Morning/pause", follow_redirects=True).text
    assert "Paused Morning" in paused and ">Resume<" in paused and backend.subs_kept["Morning"]["enabled"] is False
    assert "Resumed Morning" in client.post("/reports/subscriptions/Morning/resume", follow_redirects=True).text
    assert "Deleted Morning" in client.post("/reports/subscriptions/Morning/delete", follow_redirects=True).text and "Morning" not in backend.subs_kept


def test_the_menu_has_my_subscriptions_and_the_admin_list_is_for_administrators(client, backend):
    backend.subs_kept.clear()
    client.post("/reports/subscribe", content="name=Morning&schedule=daily+07%3A00&date=today&format=xlsx&recipients=ash&target=" + quote(_target()), headers=FORM)
    page = client.get("/reports").text
    assert "My subscriptions" in page and 'href="#subscriptions"' in page and "data-rep-filter" in page   # the reports page links and finds
    everyone = client.get("/reports/all", follow_redirects=True).text
    assert "Everyone&#39;s subscriptions" in everyone or "Everyone's subscriptions" in everyone
    assert 'data-row-owner="' in everyone and "Morning" in everyone
    done = client.post("/reports/all/ash/Morning/pause", follow_redirects=True).text
    assert "Paused ash/Morning" in done
    assert "Deleted ash/Morning" in client.post("/reports/all/ash/Morning/delete", follow_redirects=True).text
    assert client.post("/reports/all/ash/Morning/frobnicate").status_code == 404
