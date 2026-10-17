"""Admin → Data loads → Lakes: upload (streamed to the temp folder), scan with suggestions, targets, a build through the CLI's
engine (tools/lake/build.py, deltalake) on a small synthetic file, then the connector bound and the load announced as the admin.

    uv run --with deltalake --with pyarrow -r drishti-console/requirements.txt python -m pytest drishti-console/tests/test_admin_lakes.py -q
"""
import importlib.util
import json
import time
from pathlib import Path

import pytest
import yaml

from core.lakes import Lakes, LakeSettings, connector_text

REPO = Path(__file__).resolve().parents[2]
needs_deltalake = pytest.mark.skipif(importlib.util.find_spec("deltalake") is None, reason="the build test needs deltalake and pyarrow")
OCTET = {"Content-Type": "application/octet-stream"}


def book(n=60):
    return [{"TRADE_ID": 500 + i, "TRADE_DATE": ["2026-10-01", "2026-10-02"][i % 2], "PRODUCT_TYPE": ["Swap", "Future", "Option"][(i // 2) % 3],
             "NOTIONAL": 10.0 * i, "LEGS": [{"LEG_ID": 1, "CASH_FLOWS": [{"AMOUNT": i}]}]} for i in range(n)]


@pytest.fixture
def lakes(client, tmp_path):
    lk = Lakes(LakeSettings(roots=[tmp_path / "lake"], tools_dir=REPO / "tools", engine="deltalake"))
    client.app.state.lakes = lk
    yield lk
    del client.app.state.lakes


def fake(backend, monkeypatch, calls, connector=None):
    async def admin(method, path, ident, body=None, timeout=None, headers=None, **params):
        calls.append((method, path, body, headers, params))
        if path == "/status":
            return {}
        if path == "/connectors" and method == "GET":
            return {"connectors": [{"name": "book-store", "plugin": "delta", "kinds": ["deal"], "state": "RUNNING", "origin": "pack",
                                    "usedBy": [{"pack": "synth-book", "kinds": ["deal"]}]},
                                   {"name": "quotes", "plugin": "file", "kinds": ["quote"], "usedBy": []}]}
        if path == "/connectors/book-store" and method == "GET":
            return connector or {"text": None, "packDefault": "plugin: delta\nkinds: [deal]\nsettings:\n  root: x\n  domain: book\n", "etag": None}
        return {}

    async def announce_load(pack, body, ident):
        calls.append(("ANNOUNCE", pack, body, None, {}))
        return {"verified": "verified"}
    monkeypatch.setattr(backend, "admin", admin)
    monkeypatch.setattr(backend, "announce_load", announce_load, raising=False)


def upload(client, docs, name="book.json"):
    r = client.post(f"/admin/api/lakes/upload?name={name}", content=json.dumps(docs, indent=1).encode(), headers=OCTET)
    assert r.status_code == 200, r.text
    return r.json()


def test_the_page_and_the_data_loads_link(client, backend, monkeypatch, lakes):
    fake(backend, monkeypatch, [])
    page = client.get("/admin/lakes").text
    for text in ("Load a file into a lake", "admin-lakes.js", "admin-lakes.css", "data-file", "Id field", "Business date", "Classifier", "Promote",
                 "Replace those dates", "Bind the lake to the pack", "Recent runs", 'aria-live="polite"', str(lakes.cfg.roots[0])):
        assert text in page, text
    assert 'href="/admin/lakes"' in client.get("/admin/loads").text


def test_upload_scan_and_targets(client, backend, monkeypatch, lakes):
    fake(backend, monkeypatch, [])
    u = upload(client, book())
    assert u["size"] > 0 and u["name"] == "book.json"
    s = client.post(f"/admin/api/lakes/uploads/{u['id']}/scan").json()
    assert s["documents"] == 60
    assert s["suggest"]["key"][0] == "TRADE_ID" and s["suggest"]["date"][0] == "TRADE_DATE" and "PRODUCT_TYPE" in s["suggest"]["partitionBy"]
    t = client.get("/admin/api/lakes/targets").json()
    assert t["roots"] == [str(lakes.cfg.roots[0])] and t["tables"] == []
    assert t["connectors"] == [{"name": "book-store", "kinds": ["deal"], "state": "RUNNING", "origin": "pack", "packs": ["synth-book"]}]


def test_refusals(client, backend, monkeypatch, lakes):
    fake(backend, monkeypatch, [])
    assert client.post("/admin/api/lakes/upload?name=a.json", content=b"[]", headers={"Content-Type": "text/plain"}).status_code == 415
    assert client.post("/admin/api/lakes/upload?name=a.csv", content=b"a,b", headers=OCTET).status_code == 415
    assert client.post("/admin/api/lakes/upload?name=a.json", content=b"", headers=OCTET).status_code == 400
    lakes.cfg.max_upload_mb = 0
    assert client.post("/admin/api/lakes/upload?name=a.json", content=b"[{}]", headers=OCTET).status_code == 413
    lakes.cfg.max_upload_mb = 10
    u = upload(client, book(4))
    base = {"upload": u["id"], "root": str(lakes.cfg.roots[0]), "domain": "book", "kind": "deal", "key": "TRADE_ID", "date": "TRADE_DATE"}
    for bad, why in (({"root": "/etc"}, "lake roots"), ({"domain": "../x"}, "short name"), ({"date": None}, "business-date"),
                     ({"partitionBy": ["a", "b", "c", "d", "e"]}, "at most 4"), ({"bind": True}, "pack"), ({"upload": "nope"}, "no such upload")):
        r = client.post("/admin/api/lakes/jobs", json={**base, **bad})
        assert r.status_code in (400, 404) and why in r.json()["detail"], (bad, r.text)


def test_connector_text_binds_the_lake_and_keeps_the_rest():
    text = connector_text("plugin: delta\ndescription: the book\nkinds: [trade]\nsettings:\n  root: /old\n  domain: old\n  cache-mb: 64\n",
                          Path("/srv/lake"), "book", "deal", {"promoted": {"NOTIONAL": "double", "BOOK__NAME": "string"}})
    d = yaml.safe_load(text)
    assert d["kinds"] == ["trade", "deal"] and d["description"] == "the book"
    assert d["settings"] == {"root": "/srv/lake", "domain": "book", "cache-mb": 64, "layout": {"deal": {"columns": ["NOTIONAL", "BOOK.NAME"]}}}


@needs_deltalake
def test_a_build_binds_the_connector_and_announces_each_date(client, backend, monkeypatch, lakes):
    calls = []
    fake(backend, monkeypatch, calls)
    u = upload(client, book() + [{"TRADE_DATE": "2026-10-01"}])
    body = {"upload": u["id"], "root": str(lakes.cfg.roots[0]), "domain": "book", "kind": "deal", "key": "TRADE_ID", "date": "TRADE_DATE",
            "partitionBy": ["PRODUCT_TYPE"], "promote": ["NOTIONAL"], "mode": "overwrite-dates", "pack": "synth-book", "connector": "book-store",
            "bind": True, "signal": True}
    r = client.post("/admin/api/lakes/jobs", json=body)
    assert r.status_code == 202, r.text
    jid = r.json()["id"]
    deadline = time.time() + 120
    while True:
        j = client.get(f"/admin/api/lakes/jobs/{jid}").json()
        if j["state"] != "running" or time.time() > deadline:
            break
        time.sleep(0.3)
    assert j["state"] == "done", j
    s = j["summary"]
    assert (s["status"], s["accepted"], s["rejected"], s["engine"]) == ("partial", 60, 1, "deltalake")
    assert s["rows"] == {"2026-10-01": 30, "2026-10-02": 30} and s["sampleId"] and s["kind"] == "deal"
    assert (lakes.cfg.roots[0] / "book" / "deal" / "business_date=2026-10-01" / "PRODUCT_TYPE=Swap").is_dir()
    put = [c for c in calls if c[0] == "PUT" and c[1] == "/connectors/book-store"]
    assert len(put) == 1 and put[0][4] == {"confirm": "true"}
    bound = yaml.safe_load(put[0][2]["text"])
    assert bound["settings"]["root"] == str(lakes.cfg.roots[0]) and bound["settings"]["domain"] == "book" and bound["kinds"] == ["deal"]
    assert bound["settings"]["layout"] == {"deal": {"columns": ["NOTIONAL"]}}
    assert j["bind"]["ok"] and j["bind"]["created"]
    sent = sorted(c[2]["businessDate"] for c in calls if c[0] == "ANNOUNCE")
    assert sent == ["2026-10-01", "2026-10-02"] and all(x["ok"] for x in j["signal"])
    assert "lake-build partial" in client.get("/admin/lakes").text          # the run log is listed
    t = client.get("/admin/api/lakes/targets").json()
    assert t["tables"] == [{"root": str(lakes.cfg.roots[0]), "domain": "book", "kind": "deal", "partitionBy": ["PRODUCT_TYPE"], "promoted": ["NOTIONAL"]}]
