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

"""The dataset wizard's console side (docs/guides/DATASETS.md, "Build a dataset in the browser"): the page is a shell with its entry points, and the two conversions
(a JSON document to YAML and back) keep every key, the order and the keys the wizard has no control for."""
from pathlib import Path

from test_bi import make

WEB = Path(__file__).resolve().parent.parent / "web"
DOC = {"rachana": 1, "dataset": "trades-by-desk", "version": 1, "title": "Trades by desk", "tables": {"trade": {"source": {"connector": "trading-store", "kind": "trade"}, "mode": "cached",
       "columns": {"desk": "string", "notional": {"type": "decimal", "format": "amount0", "description": "Gross"}}}},
       "measures": {"Notional": {"expr": "sum(trade.notional)", "format": "amount0"}}, "refresh": "daily 06:00", "future-key": {"kept": [1, 2]}}


def test_the_wizard_page_is_a_shell_with_its_steps_script():
    client, _ = make()
    r = client.get("/bi/datasets/new", params={"edit": "file:dataset:trades-basic", "workspace": "u-1"})
    assert r.status_code == 200
    assert 'data-edit="file:dataset:trades-basic"' in r.text and 'data-workspace-id="u-1"' in r.text
    assert "bi-wizard.js" in r.text and "bi-wizard-steps.js" in r.text and "bi-wizard.css" in r.text and "data-wiz-body" in r.text


def test_the_wizard_page_needs_the_bi_power():
    client, _ = make(powers=())
    assert client.get("/bi/datasets/new").status_code == 403


def test_the_entry_points_lead_to_the_wizard():
    client, _ = make()
    assert 'data-new-wizard href="/bi/datasets/new-layer"' in client.get("/bi/datasets").text          # one New dataset entry; the guided tables builder is its fifth source
    assert "/bi/datasets/new-layer" in client.get("/bi/designer").text
    assert 'data-guided href="/bi/datasets/new"' in client.get("/bi/datasets/new-layer").text
    assert "data-edit-wizard" in client.get("/bi/d/file:dataset:trades-basic").text and "data-set-schedule" in client.get("/bi/d/file:dataset:trades-basic").text
    assert "/bi/datasets/new-layer?workspace=" in (WEB / "static/js/bi.js").read_text() and "data-new-dataset-wizard" in (WEB / "templates/bi/workspace.html").read_text()


def test_a_document_is_written_as_yaml_in_the_order_given():
    client, _ = make()
    text = client.post("/bi/wizard/yaml", json={"doc": DOC}).json()["yaml"]
    assert text.index("rachana") < text.index("dataset:") < text.index("tables:") < text.index("measures:")
    assert "refresh: daily 06:00" in text and "future-key" in text


def test_yaml_round_trips_with_unknown_keys_kept():
    client, _ = make()
    text = client.post("/bi/wizard/yaml", json={"doc": DOC}).json()["yaml"]
    back = client.post("/bi/wizard/parse", json={"yaml": text}).json()
    assert back["doc"] == DOC and back["mode"] == "tables"
    again = client.post("/bi/wizard/yaml", json={"doc": back["doc"]}).json()["yaml"]
    assert again == text


def test_the_mode_says_composed_or_derived():
    client, _ = make()
    assert client.post("/bi/wizard/parse", json={"yaml": "dataset: a\nfrom:\n  x: {dataset: b}\njoin: []\n"}).json()["mode"] == "composed"
    assert client.post("/bi/wizard/parse", json={"yaml": "dataset: a\nderived:\n  transform: t\n"}).json()["mode"] == "derived"
    assert client.post("/bi/wizard/parse", json={"yaml": "dataset: a\ntables: {}\n"}).json()["mode"] == "tables"


def test_a_text_that_is_not_a_dataset_is_refused_with_a_code():
    client, _ = make()
    for body in ({"yaml": "a: [1, 2"}, {"yaml": "- just\n- a list\n"}):
        r = client.post("/bi/wizard/parse", json=body)
        assert r.status_code == 422 and r.json()["code"] == "DRS-9003"
    assert client.post("/bi/wizard/yaml", json={"doc": []}).status_code == 422
    assert client.post("/bi/wizard/yaml", json={}).json()["code"] == "DRS-9003"
    assert client.post("/bi/wizard/parse", json={"yaml": "x: " + "y" * 500_000}).status_code == 413


def test_the_designer_start_lets_the_author_choose_where_drafts_go_and_lists_samples():
    client, _ = make()
    page = client.get("/bi/designer").text
    for hook in ("data-new-workspace", "data-new-board-workspace", "data-samples", "data-no-samples"):
        assert hook in page
    script = (WEB / "static/js/bi-design.js").read_text()
    assert "data-sample-copy" in script and "askWorkspace" in script           # Copy and design, and Save a copy ask for the workspace
    assert "askWorkspace" in (WEB / "static/js/bi-design-board.js").read_text()


def test_the_workspace_page_offers_a_python_starter_and_known_people():
    client, _ = make()
    page = client.get("/bi/w/me").text
    assert "data-python-row" in page and "pyinterface" in page and "data-member-user" in page and "people.js" in page and "bi-extras.js" in page
    assert "bi-extras.js" in client.get("/bi/i/bi-1").text


def test_the_distribution_status_is_passed_on_for_the_report_bar():
    client, _ = make()
    st = client.get("/dist/status").json()
    assert st["export"]["enabled"] is True and st["subscriptions"]["perUser"] == 20
    assert "data-export-why" in client.get("/bi/r/bi-1").text
