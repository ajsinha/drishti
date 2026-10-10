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

"""Rupaka phase 2 (docs/architecture/RUPAKA.md sections 6 and 7), the console's routes for datasets: the list and one dataset's page are shells
drawn by bi-dataset.js, the same-origin JSON reaches the server's datasets, model and engine paths under the caller's identity and nothing else
does, and the server's refusals come back with their DRS codes."""
from pathlib import Path

from test_bi import make

WEB = Path(__file__).resolve().parent.parent / "web"
DATASET = "file:dataset:trades-basic"


def test_the_datasets_page_is_a_real_page_now_not_a_placeholder():
    client, _ = make()
    r = client.get("/bi/datasets")
    assert r.status_code == 200
    assert "Start a model from the packs" in r.text and "bi-dataset.js" in r.text and "Not built yet" not in r.text
    assert 'aria-current="page"' in r.text and "phase 2" not in r.text          # the rail says so no more


def test_one_dataset_has_its_own_page_with_the_preview_form():
    client, _ = make()
    r = client.get("/bi/d/" + DATASET)
    assert r.status_code == 200
    assert f'data-item="{DATASET}"' in r.text and "data-preview" in r.text and "data-relationships" in r.text and "data-rules" in r.text
    assert "bi-dataset.js" in r.text and "bi.js" in r.text


def test_the_pages_need_the_bi_power():
    client, _ = make(powers=())
    assert client.get("/bi/datasets").status_code == 403
    assert client.get("/bi/d/" + DATASET).status_code == 403


def test_the_json_for_the_pages_is_passed_on_under_the_callers_identity():
    client, backend = make()
    assert client.get("/bi/api/datasets").json()["datasets"][0]["id"] == DATASET
    assert client.get(f"/bi/api/datasets/{DATASET}").json()["tables"][0]["name"] == "trade"
    body = {"fields": ["trade.desk"], "measures": ["MTM"], "filters": [], "limit": 5}
    answer = client.post(f"/bi/api/datasets/{DATASET}/query", json=body, params={"explain": "true"}).json()
    assert answer["rowCount"] == 1 and answer["echo"] == body and answer["explain"] == "true"
    assert client.get("/bi/api/model/kinds").json()["tables"][0]["kind"] == "trade"
    assert client.post("/bi/api/model/start", json={"kinds": ["trade"]}).json()["yaml"].startswith("dataset:")
    assert client.get("/bi/api/engine").json()["engine"] == "duckdb"
    assert [c[1] for c in backend.bi_calls if c[0] == "POST"][:2] == [f"/datasets/{DATASET}/query", "/model/start"]


def test_other_server_paths_are_not_reachable_and_writes_check_the_origin():
    client, _ = make()
    assert client.get("/bi/api/admin/users").status_code == 404
    assert client.get("/bi/api/datasets/../../admin/users").status_code in (404, 400)
    assert client.post(f"/bi/api/datasets/{DATASET}/query", json={"measures": ["MTM"]}, headers={"origin": "https://evil.example"}).status_code == 403
    assert client.post(f"/bi/api/datasets/{DATASET}/query", content="x=1", headers={"content-type": "text/plain"}).status_code in (400, 415, 422)


def test_the_servers_refusals_come_back_with_their_codes():
    client, backend = make()
    backend.refuse = ("DRS-9021", 403, "'trades.mtm' is masked for you: it cannot be filtered on")
    r = client.post(f"/bi/api/datasets/{DATASET}/query", json={"measures": ["MTM"]})
    assert r.status_code == 403 and r.json()["code"] == "DRS-9021" and "masked for you" in r.json()["detail"]
    backend.refuse = ("DRS-9022", 413, "the answer has more than 100000 rows")
    assert client.post(f"/bi/api/datasets/{DATASET}/query", json={"measures": ["MTM"]}).json()["code"] == "DRS-9022"


def test_the_script_draws_with_the_dom_only_and_the_pages_carry_no_inline_code():
    js = (WEB / "static" / "js" / "bi-dataset.js").read_text(encoding="utf-8")
    assert "innerHTML" not in js and "eval(" not in js and "document.write" not in js
    for name in ("datasets.html", "dataset.html"):
        html = (WEB / "templates" / "bi" / name).read_text(encoding="utf-8")
        assert " style=" not in html and " onclick=" not in html and "<script>" not in html
