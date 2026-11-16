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

"""Console write routes that had no test of their own: Make-it-a-model's rebind, the Transform editor's snippet and parse, saving a monitor, and
deleting an alert rule. Each: a valid request with its effect read back (the saved item, the stored monitor, the rule gone), and an invalid one
refused with its DRS code."""
from __future__ import annotations

import pytest

pytest.importorskip("pyarrow")
pytest.importorskip("pandas")

from core.backend import BackendError  # noqa: E402
from test_bi_model_layer import make as make_layer  # noqa: E402
from test_transform_editor import SPEC, PrepBackend  # noqa: E402,F401


def test_rebind_points_each_report_at_the_model_and_refuses_what_names_nothing():
    client, backend = make_layer()
    first = client.post("/bi/model-api/report", json={"dataset": "pack.trading.trade", "title": "Trades"}).json()["id"]
    second = client.post("/bi/model-api/report", json={"dataset": "pack.trading.trade", "title": "Trades two"}).json()["id"]
    ref = "trading-risk@1"
    r = client.post("/bi/model-api/rebind", json={"ref": ref, "reports": [first, second]})
    assert r.status_code == 200 and r.json() == {"rebound": [first, second], "failed": []}
    for rid in (first, second):
        assert f"model: {ref}" in backend.items[rid]["body"] and "dataset:pack" not in backend.items[rid]["body"]           # the effect: the saved items
    again = client.post("/bi/model-api/rebind", json={"ref": ref, "reports": [first]}).json()                              # the same again: unchanged
    assert again["rebound"] == [first] and backend.items[first]["body"].count("model:") == 1
    real = backend.bi

    async def bi(method, path, ident, body=None, **params):                                  # the server refuses a report the person may not edit
        if path == "/items/bi-nope":
            raise BackendError(404, "DRS-9001", "no such item")
        return await real(method, path, ident, body, **params)
    backend.bi = bi
    mixed = client.post("/bi/model-api/rebind", json={"ref": ref, "reports": [first, "bi-nope"]}).json()
    assert mixed["rebound"] == [first] and [f["id"] for f in mixed["failed"]] == ["bi-nope"]                             # one bad id does not stop the rest
    for body in ({}, {"ref": ref}, {"ref": ref, "reports": []}, {"reports": [first]}):
        refused = client.post("/bi/model-api/rebind", json=body)
        assert refused.status_code == 422 and refused.json()["code"] == "DRS-9140"


@pytest.fixture()
def editor():
    from fastapi.testclient import TestClient
    from conftest import CONSOLE
    from core.app import create_app
    from core.config import load_settings
    app = create_app(load_settings(CONSOLE / "config"))
    app.state.backend = PrepBackend()
    return TestClient(app)


def test_a_snippet_renders_its_step_and_an_unknown_one_is_refused(editor):
    listed = editor.get("/bi/transform/snippets").json()["snippets"]
    sid = next(s["id"] for s in listed if s["id"] == "type-fix-number")
    r = editor.post("/bi/transform/snippet", json={"id": sid, "params": {"column": "NOTIONAL"}, "inputs": ["trades"]})
    assert r.status_code == 200
    step = r.json()
    assert step["snippet"] == sid and "NOTIONAL" in step["code"] and step["label"]
    assert editor.post("/bi/transform/snippet", json={"id": sid, "params": {"column": "NOTIONAL"}, "inputs": ["trades"]}).json() == step      # repeated: the same step
    for body in ({"id": "no-such-snippet", "params": {}, "inputs": []}, {"params": {}}, {"id": sid, "params": {}, "inputs": ["trades"]}):
        bad = editor.post("/bi/transform/snippet", json=body)
        assert bad.status_code == 422 and bad.json()["code"] == "DRS-9070"


def test_the_yaml_tab_reads_a_python_item_back_as_steps_and_refuses_what_is_not_one(editor):
    made = editor.post("/bi/transform/compose", json={"spec": SPEC}).json()
    parsed = editor.post("/bi/transform/parse", json={"python": made["python"], "dataset": made["dataset"]})
    assert parsed.status_code == 200
    spec = parsed.json()["spec"]
    assert [s["label"] for s in spec["steps"]] == [s["label"] for s in SPEC["steps"]]          # the way back to steps gives the steps that were written
    assert spec["inputs"][0]["name"] == "trades"
    bad = editor.post("/bi/transform/parse", json={"python": "this: [is not python item yaml"})
    assert bad.status_code == 422 and bad.json()["code"] == "DRS-9070"


def test_a_monitor_is_saved_and_read_back_and_a_body_that_is_not_json_is_refused(client, backend):
    entities = [{"kind": "netting-set", "id": "NS-NORTH-01"}]
    name = "Desk watch"
    saved = client.post(f"/m/api/{name}", json={"entities": entities})
    assert saved.status_code == 200 and saved.json() == {"entities": entities}
    assert backend.monitors[name] == {"entities": entities}                                      # the effect: kept for the person
    assert client.get(f"/m/{name}").status_code == 200 and "NS-NORTH-01" in client.get(f"/m/{name}").text
    client.post(f"/m/api/{name}", json={"entities": entities})                                  # saving the same again leaves one monitor
    assert list(backend.monitors).count(name) == 1
    bad = client.post(f"/m/api/{name}", content=b"{nope", headers={"content-type": "application/json"})
    assert bad.status_code == 400 and "DRS-5001" in bad.text and backend.monitors[name] == {"entities": entities}
    assert client.post(f"/m/api/{name}", content=b"x", headers={"content-type": "text/plain"}).status_code == 415


def test_deleting_an_alert_rule_removes_it_and_a_second_delete_is_harmless(client, backend):
    name = "Near limit"
    ok = client.post(f"/alerts/api/{name}", json={"kind": "netting-set", "id": "NS-NORTH-01", "when": "$.utilisation > 0.8"})
    assert ok.status_code == 200 and name in backend.alerts_kept
    gone = client.post(f"/alerts/api/{name}/delete")
    assert gone.status_code == 200 and gone.json() == {"ok": True} and name not in backend.alerts_kept          # the effect: the rule is gone
    assert name not in client.get("/alerts").text
    assert client.post(f"/alerts/api/{name}/delete").json() == {"ok": True}                                      # again: still gone
    assert client.post(f"/alerts/api/{name}/delete", headers={"origin": "https://evil.example"}).status_code == 403
