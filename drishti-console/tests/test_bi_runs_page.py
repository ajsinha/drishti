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

"""BI runs, console lane R1c, R3c and R5c (docs/architecture/BI_DESIGN.md › BI runs: every BI query as a bounded, visible job (built in 2.6.7), sections 7, 14.1 and 17.2): the workspace's three tabs and the Runs tab's hooks,
the rail's Runs link with its count, BI RUNS <GO>, the runs paths passed on to the server (and nothing else), the event stream relayed, a run's export
passed through, /bi/run/<id> opening where the run's result is drawn, the bi-run notice in the inbox, and Admin → BI → Runs for bi-admins only."""
import re

import pytest
from fastapi.testclient import TestClient

from bi_runs_backend import REPORT_ID, RunsBackend
from conftest import CONSOLE
from core.app import create_app
from core.config import load_settings


def make(powers=("bi", "bi-author", "bi-admin")):
    app = create_app(load_settings(CONSOLE / "config"))
    backend = RunsBackend(seed=True)
    backend.powers = list(powers)
    app.state.backend = backend
    return TestClient(app), backend


@pytest.fixture()
def bi():
    return make()


def test_the_workspace_has_three_tabs_and_the_runs_panel(bi):
    client, _ = bi
    r = client.get("/bi/w/me?tab=runs")
    assert r.status_code == 200
    tabs = re.findall(r'data-ws-tab="([a-z]+)"', r.text)
    assert tabs == ["items", "runs", "members"]
    for hook in ('role="tablist"', 'data-ws-panel="runs"', "data-runs-filters", "data-runs-table", "data-run-drawer", 'aria-modal="true"', "data-runs-say", "data-runs-storage"):
        assert hook in r.text, hook
    for name in ("state", "source", "who", "since", "q"):
        assert f'name="{name}"' in r.text, name
    scripts = re.findall(r"/static/js/([a-z-]+)\.js", r.text)
    assert scripts.index("bi-lib") < scripts.index("bi-runs") < scripts.index("bi-runs-tab")
    assert "bi-runs.css" in r.text


def test_the_rail_links_to_my_runs_with_a_count_and_g_r_is_listed(bi):
    client, _ = bi
    r = client.get("/bi")
    assert 'href="/bi/w/me?tab=runs"' in r.text and "data-runs-count" in r.text
    assert "<kbd>g</kbd> <kbd>r</kbd>" in r.text


@pytest.mark.parametrize("typed", ["BI RUNS <GO>", "bi runs", "Rupaka runs"])
def test_bi_runs_go_opens_my_runs(bi, typed):
    client, _ = bi
    r = client.get("/go", params={"q": typed}, follow_redirects=False)
    assert r.status_code == 303 and r.headers["location"] == "/bi/w/me?tab=runs"


def test_the_runs_paths_reach_the_server_and_answer_the_run_object(bi):
    client, backend = bi
    listed = client.get("/bi/api/runs", params={"mine": "true", "shared": "true", "state": "done"})
    assert listed.status_code == 200 and listed.headers["cache-control"] == "no-store"
    runs = listed.json()["runs"]
    assert runs and all(r["state"] == "done" for r in runs)
    one = runs[0]
    for field in ("id", "title", "source", "runner", "asOf", "data", "tier", "state", "estimate", "actual", "can", "links"):
        assert field in one, field
    got = client.get("/bi/api/runs/" + one["id"])
    assert got.json()["id"] == one["id"]
    assert ("GET", "/runs/" + one["id"], None, {}) in backend.bi_calls


def test_a_missing_run_says_its_code_and_its_words(bi):
    client, _ = bi
    r = client.get("/bi/api/runs/r-nope")
    assert r.status_code == 404 and r.json()["code"] == "DRS-9080"


def test_cancel_share_keep_and_delete_pass_on_and_change_the_run(bi):
    client, backend = bi
    run = backend._new("A long one")
    rid = run["id"]
    c = client.post(f"/bi/api/runs/{rid}/cancel", json={})
    assert c.status_code == 200 and c.json()["state"] == "cancelled" and c.json()["reason"] == "cancelled by you"
    done = next(r for r in backend.runs.values() if r["state"] == "done")
    s = client.post(f"/bi/api/runs/{done['id']}/share", json={"to": ["bob", "carol"]})
    assert {x["recipient"]: x["mode"] for x in s.json()["shares"]} == {"bob": "re-masked", "carol": "recomputed"}
    assert client.post(f"/bi/api/runs/{done['id']}/keep", json={"days": 90}).json()["expiresAt"].startswith("2027")
    assert client.delete(f"/bi/api/runs/{done['id']}/result").status_code == 204
    assert backend.runs[done["id"]]["state"] == "expired"


def test_an_expired_result_is_410_with_the_way_back(bi):
    client, backend = bi
    gone = next(r for r in backend.runs.values() if r["state"] == "expired")
    r = client.get(f"/bi/api/runs/{gone['id']}/result", params={"format": "json"})
    assert r.status_code == 410 and r.json()["code"] == "DRS-9084"


def test_the_export_is_passed_through_as_a_file(bi):
    client, backend = bi
    done = next(r for r in backend.runs.values() if r["state"] == "done")
    r = client.post(f"/bi/api/runs/{done['id']}/export", json={"format": "csv"})
    assert r.status_code == 200 and r.content.startswith(b"DESK,MTM") and f'{done["id"]}.csv' in r.headers["content-disposition"]


def test_the_event_stream_is_relayed_with_hello_and_run_events(bi):
    client, backend = bi
    asked = []

    async def finite(path, ident=None, opened=None):               # the server's stream, ended after three events so the relay ends too
        asked.append(path)
        yield "hello", '{"runs": []}'
        yield "run", '{"id": "r-1", "state": "running", "progress": 0.5, "phase": "scanning"}'
        yield "run", '{"id": "r-1", "state": "done", "progress": 1.0, "rowsOut": 4}'
    backend.sse = finite
    r = client.get("/bi/api/runs/events")
    assert r.status_code == 200 and r.headers["content-type"].startswith("text/event-stream") and r.headers["cache-control"] == "no-cache"
    events = [line.split(":", 1)[1].strip() for line in r.text.splitlines() if line.startswith("event:")]
    assert events == ["hello", "run", "run"] and '"state": "done"' in r.text and asked == ["/bi/runs/events"]


def test_a_run_opens_on_its_report_or_in_the_runs_tab(bi):
    client, backend = bi
    report_run = next(r for r in backend.runs.values() if r["source"]["type"] == "report" and r["state"] == "cancelled")
    r = client.get("/bi/run/" + report_run["id"], follow_redirects=False)
    assert r.status_code == 303 and r.headers["location"] == f"/bi/r/{REPORT_ID}/overview?run={report_run['id']}"
    mq_run = next(r for r in backend.runs.values() if r["source"]["type"] == "mq")
    mq_run["source"] = dict(mq_run["source"], href="/bi/runs/" + mq_run["id"])       # what the real server says for an MQ run: not a console page
    r = client.get("/bi/run/" + mq_run["id"], follow_redirects=False)
    assert r.headers["location"] == "/bi/w/me?tab=runs&run=" + mq_run["id"]
    r = client.get("/bi/run/r-nope", follow_redirects=False)
    assert r.headers["location"] == "/bi/w/me?tab=runs&run=r-nope"


def test_a_source_href_that_is_not_a_console_path_is_never_followed(bi):
    client, backend = bi
    run = backend._new("Odd", "done")
    run["source"] = dict(run["source"], href="https://elsewhere.example/x")
    r = client.get("/bi/run/" + run["id"], follow_redirects=False)
    assert r.headers["location"].startswith("/bi/w/me?tab=runs")


def test_a_run_notice_in_the_inbox_says_run_and_links_to_it(bi):
    client, backend = bi
    run = backend._new("MTM by desk and month", "done")
    backend._notice(run, "Run finished: MTM by desk and month")
    page = client.get("/inbox?tab=run")
    assert page.status_code == 200
    assert f'href="/bi/run/{run["id"]}"' in page.text and ">Run<" in page.text and "Bi-run" not in page.text


def test_admin_runs_is_for_bi_admins_and_its_api_passes_on():
    client, _ = make()
    page = client.get("/admin/bi/runs")
    assert page.status_code == 200 and "data-bi-runs-admin" in page.text and "bi-runs-admin.js" in page.text
    d = client.get("/bi/api/admin/runs").json()
    assert d["now"]["slots"]["background"] == {"used": 2, "cap": 2} and d["storage"]["results"] == 1904
    viewer, _ = make(("bi",))
    assert viewer.get("/admin/bi/runs").status_code == 403


def test_admin_cancel_answers_202(bi):
    client, backend = bi
    run = backend._new("Someone's")
    r = client.post(f"/bi/api/admin/runs/{run['id']}/cancel", json={})
    assert r.status_code == 202 and r.json()["reason"].startswith("cancelled by an administrator")


def test_the_settings_page_links_to_runs(bi):
    client, _ = bi
    assert 'href="/admin/bi/runs"' in client.get("/admin/bi").text


def test_every_runs_script_has_the_licence_and_no_markup_strings():
    for name in ("bi-runs.js", "bi-runs-tab.js", "bi-runs-admin.js"):
        text = (CONSOLE / "web/static/js" / name).read_text(encoding="utf-8")
        assert "PROPRIETARY AND CONFIDENTIAL" in text[:600], name
        assert "innerHTML" not in text and "insertAdjacentHTML" not in text, name            # text only, never markup (the strict policy)
        assert len(text.splitlines()) < 1500, name


def test_share_answers_each_mode_a_role_expanded_and_a_refusal_and_the_recipient_sees_it(bi):
    client, backend = bi
    done = next(r for r in backend.runs.values() if r["state"] == "done")
    s = client.post(f"/bi/api/runs/{done['id']}/share", json={"to": ["bob", "carol", "eve", "role:risk-team"]}).json()["shares"]
    modes = {x["recipient"]: x["mode"] for x in s}
    assert modes == {"bob": "re-masked", "carol": "recomputed", "eve": None, "dave": "same", "erin": "same"}
    assert next(x for x in s if x["recipient"] == "carol")["recipientRun"] and next(x for x in s if x["recipient"] == "eve")["code"] == "DRS-9021"
    backend.user = "bob"
    mine = client.get("/bi/api/runs", params={"mine": "false", "shared": "true"}).json()["runs"]
    shared = next(r for r in mine if r["id"] == done["id"])
    assert shared["shared"] == {"from": "ajsinha", "with": []} and shared["can"]["export"] and not shared["can"]["share"]
    backend.user = "ajsinha"
    assert client.delete(f"/bi/api/runs/{done['id']}/share/bob").status_code == 204
    backend.user = "bob"
    assert client.get(f"/bi/api/runs/{done['id']}").status_code == 404


def test_schedules_are_made_listed_edited_and_removed_in_the_subscription_grammar(bi):
    client, backend = bi
    done = next(r for r in backend.runs.values() if r["state"] == "done")
    bad = client.post(f"/bi/api/runs/{done['id']}/schedule", json={"schedule": "after-load TRD", "data": "latest"})
    assert bad.status_code == 422 and bad.json()["code"] == "DRS-9061"
    made = client.post(f"/bi/api/runs/{done['id']}/schedule", json={"schedule": "weekly mon 08:00", "data": "same"}).json()
    assert made["schedule"] == "weekly mon 08:00" and made["nextAt"] and made["runId"] == done["id"]
    assert client.get(f"/bi/api/runs/{done['id']}").json()["schedule"]["id"] == made["id"]
    assert [x["id"] for x in client.get("/bi/api/runs/schedules").json()["schedules"]] == [made["id"]]
    edited = client.put(f"/bi/api/runs/schedules/{made['id']}", json={"schedule": "daily 06:30", "enabled": False}).json()
    assert edited["schedule"] == "daily 06:30" and edited["enabled"] is False
    assert client.delete(f"/bi/api/runs/schedules/{made['id']}").status_code == 204
    assert client.get(f"/bi/api/runs/{done['id']}").json()["schedule"] is None


def test_export_is_csv_or_excel(bi):
    client, backend = bi
    done = next(r for r in backend.runs.values() if r["state"] == "done")
    x = client.post(f"/bi/api/runs/{done['id']}/export", json={"format": "xlsx"})
    assert x.status_code == 200 and x.content.startswith(b"PK") and x.headers["content-disposition"].endswith('.xlsx"')
    assert client.post(f"/bi/api/runs/{done['id']}/export", json={"format": "pdf"}).status_code == 400


def test_suggestions_are_accepted_or_dismissed_through_the_console():
    client, backend = make()
    page = client.get("/admin/bi/runs")
    assert 'id="suggestions"' in page.text and "data-admin-suggest-msg" in page.text
    listed = client.get("/bi/api/admin/runs").json()["suggestions"]
    assert {s["id"] for s in listed} == {"sg-1", "sg-2"} and listed[0]["yaml"].startswith("rollups:")
    a = client.post("/bi/api/admin/runs/suggestions/sg-1/accept", json={})
    assert a.status_code == 200 and a.json()["status"] == "accepted" and a.json()["item"] == "bi-model-trading"
    assert client.post("/bi/api/admin/runs/suggestions/sg-1/dismiss", json={}).status_code == 409
    assert client.post("/bi/api/admin/runs/suggestions/sg-2/dismiss", json={}).json()["status"] == "dismissed"
    assert client.post("/bi/api/admin/runs/suggestions/sg-2/other", json={}).status_code == 404
    assert ("POST", "/admin/runs/suggestions/sg-1/accept", {}, {}) in backend.bi_calls


def test_the_query_panel_asks_sql_with_the_inline_wait_when_runs_are_on_the_page():
    text = (CONSOLE / "web/static/js/bi-query-panel.js").read_text(encoding="utf-8")
    assert "B.ask('/sql', body, 2)" in text and "B.runs.settle(" in text
    page = (CONSOLE / "web/templates/bi/design.html").read_text(encoding="utf-8")
    scripts = re.findall(r"/static/js/([a-z-]+)\.js", page)
    assert scripts.index("bi-lib") < scripts.index("bi-runs") < scripts.index("bi-query-panel") and "bi-runs.css" in page


def test_the_model_designer_links_to_open_rollup_suggestions():
    text = (CONSOLE / "web/static/js/bi-model-designer.js").read_text(encoding="utf-8")
    assert "function rollupNotice(model)" in text and "'/admin/bi/runs?model=' + encodeURIComponent(model) + '#suggestions'" in text and "data-md-suggest" in text
