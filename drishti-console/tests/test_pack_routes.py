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

"""Build -> New pack, the console's routes (/build/pack/...): the page, the plan, preview jobs (progress, cancel), one Sutra rendered, opening one in
the workbench, the bundle download (checked with the same tools `pack verify` uses), the author gate, and drafts kept as designs. The stand-in
server answers the auto-designer with a canned draft, so these need no Java."""
import io
import json
import pathlib
import tarfile
import time

import pytest

FIX = pathlib.Path(__file__).resolve().parents[2] / "docs" / "guides" / "examples" / "schemas"
TOOLS = pathlib.Path(__file__).resolve().parents[2] / "tools"
JSON = {"Content-Type": "application/json"}


def schemas(*names):
    return [{"name": f.name, "text": f.read_text()} for f in sorted(FIX.glob("*.json")) if not names or f.stem.split(".")[0] in names]


def trades(n=12):
    return [{"tradeId": f"T-{i:03d}", "counterpartyId": f"CPT-{i % 3 + 1:03d}", "bookId": "BK-1", "status": ["LIVE", "PENDING"][i % 2], "productType": ["IRS", "FXS", "FUT"][i % 3],
             "notional": 1000.0 * i, "currency": "USD", "tradeDate": f"2026-03-0{1 + i % 2}", "mtm": 10.5 * i, "extra": i} for i in range(n)]


def body(**kw):
    return {"schemas": schemas(), "data": [], **kw}


@pytest.fixture(autouse=True)
def author(backend, monkeypatch):
    async def settings(ident=None):
        return {"save": True, "author": True, "review": False, "approve": False}
    monkeypatch.setattr(backend, "studio_settings", settings)


@pytest.fixture
def post(client):
    def go(url, payload=None):
        return client.post(url, content=json.dumps(payload if payload is not None else {}), headers=JSON)
    return go


def run_job(client, post, payload):
    r = post("/build/pack/api/jobs", payload)
    assert r.status_code == 202, r.text
    jid = r.json()["id"]
    for _ in range(100):
        v = client.get(f"/build/pack/api/jobs/{jid}").json()
        if v["state"] not in ("queued", "running"):
            return jid, v
        time.sleep(0.05)
    raise AssertionError("the job did not finish")


# ---- the page ---------------------------------------------------------------------------------------------------------------------

def test_page_and_menu(client):
    r = client.get("/build/pack/new")
    assert r.status_code == 200
    for word in ("New pack", "1. Bring your sources", "2. Kinds", "3. The pack", "4. Preview every Sutra", "5. Output", "data-pack-new", "/help/schema-to-pack"):
        assert word in r.text
    assert 'data-read-rows="500000"' in r.text and 'data-sample-docs="200"' in r.text
    menu = client.get("/build").text
    assert 'href="/build/pack/new"' in menu and "New pack" in menu
    assert "/help/schema-to-pack" in menu


def test_page_tells_a_reader_without_the_author_power(client, backend, monkeypatch):
    async def settings(ident=None):
        return {"save": False, "author": False}
    monkeypatch.setattr(backend, "studio_settings", settings)
    assert "does not have the author power" in client.get("/build/pack/new").text
    assert 'data-can-author="false"' in client.get("/build/pack/new").text


def test_help_guide_is_in_the_help_centre(client):
    assert client.get("/help/schema-to-pack").status_code == 200


# ---- the plan ---------------------------------------------------------------------------------------------------------------------

def test_plan_from_schemas_alone(post):
    r = post("/build/pack/api/plan", body())
    assert r.status_code == 200, r.text
    p = r.json()
    assert [k["kind"] for k in p["kinds"]] == ["book", "counterparty", "instrument", "trade"]
    assert p["sutraCount"] == 9
    t = next(k for k in p["kinds"] if k["kind"] == "trade")
    assert t["key"] == "tradeId" and t["match"] == ["productType"] and t["synthetic"] is True and t["origKind"] == "trade"
    assert {(e["from"], e["to"]) for e in p["edges"]} == {("trade", "counterparty"), ("trade", "book"), ("counterparty", "counterparty")}
    assert p["conflicts"] == {"packName": False, "mnemonics": [], "nameValid": True,
                              "kinds": {"book": "finance", "counterparty": "finance", "trade": "finance"}}      # the repository's finance pack defines them
    assert p["pack"]["name"] == "my-pack" and p["limits"]["sampleDocs"] == 200


def test_plan_with_data_assigns_files_to_kinds_and_real_samples_win(post):
    r = post("/build/pack/api/plan", body(data=[{"name": "data/trades.jsonl", "rows": 12000, "bytes": 99999, "docs": trades()}]))
    p = r.json()
    assert p["files"] == [{"name": "data/trades.jsonl", "rows": 12000, "sampled": 12, "bytes": 99999, "kind": "trade"}]
    t = next(k for k in p["kinds"] if k["kind"] == "trade")
    assert t["samples"] == 12 and t["synthetic"] is False and t["template"] == "delta" and t["date"] == "tradeDate"
    assert any("do not describe" in w or "does not describe" in w for w in t["warnings"])


def test_data_alone_and_a_file_assigned_by_the_user(post):
    r = post("/build/pack/api/plan", {"schemas": [], "data": [{"name": "zebras.jsonl", "rows": 4, "bytes": 10, "docs": [{"zebraId": f"Z{i}", "stripes": i} for i in range(4)]}]})
    p = r.json()
    assert [k["kind"] for k in p["kinds"]] == ["zebras"] and p["kinds"][0]["key"] == "zebraId"
    r2 = post("/build/pack/api/plan", {**body(), "data": [{"name": "zebras.jsonl", "rows": 4, "bytes": 10, "docs": trades(4)}], "kindOf": {"zebras.jsonl": "trade"}})
    assert next(k for k in r2.json()["kinds"] if k["kind"] == "trade")["samples"] == 4


def test_overrides_change_the_plan(post):
    r = post("/build/pack/api/plan", body(overrides={"trade": {"key": "bookId", "match": [], "kind": "deal", "mnemonic": "DL", "links": {"bookId": None}}}))
    d = next(k for k in r.json()["kinds"] if k["kind"] == "deal")
    assert d["origKind"] == "trade" and d["key"] == "bookId" and d["match"] == [] and len(d["sutras"]) == 1 and d["mnemonic"] == "DL"
    assert [l["field"] for l in d["links"]] == ["counterpartyId"]


def test_pack_fields_the_user_typed_win(post):
    p = post("/build/pack/api/plan", body(pack={"name": "my-bank", "code": "MB", "title": "My bank", "version": "2.0.1", "connector": "bank-lake"})).json()
    assert (p["pack"]["name"], p["pack"]["code"], p["pack"]["title"], p["pack"]["version"], p["pack"]["connector"]) == ("my-bank", "MB", "My bank", "2.0.1", "bank-lake")
    assert post("/build/pack/api/plan", body(pack={"name": "Bad Name"})).json()["conflicts"]["nameValid"] is False


def test_mnemonic_and_name_conflicts_with_installed_packs(post, backend, monkeypatch):
    async def admin(method, path, ident, body=None, **params):
        return [{"name": "finance", "mnemonics": ["TRA", "BOO"], "kinds": ["trade", "swap"]}] if path == "/packs" else {}
    monkeypatch.setattr(backend, "admin", admin)
    p = post("/build/pack/api/plan", body(pack={"name": "finance"})).json()
    trade = next(k for k in p["kinds"] if k["kind"] == "trade")
    assert trade["mnemonic"] == "TRA"                     # a new version of the same pack keeps its own mnemonics
    assert p["conflicts"]["packName"] is True
    assert p["conflicts"]["kinds"] == {}                  # ... and its kinds
    q = post("/build/pack/api/plan", body(pack={"name": "my-bank"})).json()
    assert next(k for k in q["kinds"] if k["kind"] == "trade")["mnemonic"] != "TRA"      # another pack's: a free one was chosen
    other = post("/build/pack/api/plan", body(pack={"name": "my-bank"}, overrides={"trade": {"kind": "deal"}})).json()["conflicts"]["kinds"]
    assert other == {"book": "finance", "counterparty": "finance"}      # two unrelated packs may not define the same kind; the renamed one is free


def test_a_renamed_kind_keeps_its_links(post):
    p = post("/build/pack/api/plan", body(overrides={"trade": {"kind": "deal"}, "counterparty": {"kind": "party"}, "book": {"kind": "ledger"}})).json()
    deal = next(k for k in p["kinds"] if k["kind"] == "deal")
    assert {(l["field"], l["to"]) for l in deal["links"]} == {("counterpartyId", "party"), ("bookId", "ledger")}
    assert deal["origKind"] == "trade"


def test_plan_refuses_what_is_over_a_cap_or_not_json(client, post):
    assert post("/build/pack/api/plan", {"schemas": [], "data": []}).status_code == 400
    big = {"schemas": [{"name": "x.json", "text": " " * (600 * 1024)}]}
    r = post("/build/pack/api/plan", big)
    assert r.status_code == 413 and r.json()["code"] == "DRS-5005"
    many = {"schemas": [{"name": f"s{i}.json", "text": "{}"} for i in range(201)]}
    assert post("/build/pack/api/plan", many).status_code == 413
    assert client.post("/build/pack/api/plan", content="not json", headers={"Content-Type": "text/plain"}).status_code == 415
    bad = post("/build/pack/api/plan", {"schemas": [{"name": "broken.json", "text": "{nope"}], "data": []})
    assert bad.status_code == 200 and any("broken.json" in w for w in bad.json()["warnings"])


def test_documents_are_capped_per_file(post):
    docs = [{"id": f"D{i}"} for i in range(500)]
    p = post("/build/pack/api/plan", {"schemas": [], "data": [{"name": "d.jsonl", "rows": 500, "bytes": 1, "docs": docs}]}).json()
    assert p["files"][0]["sampled"] == 200 and p["files"][0]["rows"] == 500


# ---- jobs -------------------------------------------------------------------------------------------------------------------------

def test_job_drafts_every_sutra_and_reports(client, post):
    jid, v = run_job(client, post, body())
    assert v["state"] == "done" and v["done"] == v["total"] == 9
    names = [s["name"] for s in v["sutras"]]
    assert "trade-irs" in names and "instrument-bond" in names
    s = next(x for x in v["sutras"] if x["name"] == "trade-irs")
    assert "sutra: trade-irs" in s["yaml"] and s["synthetic"] is True and s["samples"][0]["source"] == "synthetic"
    assert s["about"]["title"] == "Trade" and s["check"]["panels"] is not None


def test_job_needs_a_key_and_a_valid_name(post):
    assert post("/build/pack/api/jobs", body(pack={"name": "Bad Name"})).status_code == 400
    s = [{"name": "t.json", "text": json.dumps({"title": "Thing", "type": "object", "properties": {"label": {"type": "string"}}})}]
    r = post("/build/pack/api/jobs", {"schemas": s, "data": []})
    assert r.status_code == 400 and "key" in r.json()["detail"]


def test_one_sutra_rendered_on_a_sample(client, post):
    jid, v = run_job(client, post, body())
    r = client.get(f"/build/pack/api/jobs/{jid}/preview", params={"sutra": "trade-irs"})
    assert r.status_code == 200 and r.json()["sample"] == "sample-synthetic-1" and r.json()["source"] == "synthetic" and r.json()["previewHtml"]
    r2 = client.get(f"/build/pack/api/jobs/{jid}/preview", params={"sutra": "trade-irs", "sample": "sample-synthetic-3"})
    assert r2.json()["sample"] == "sample-synthetic-3"
    assert client.get(f"/build/pack/api/jobs/{jid}/preview", params={"sutra": "nope"}).status_code == 404


def test_open_in_the_workbench_makes_a_design(client, post, backend):
    jid, v = run_job(client, post, body(pack={"name": "my-bank"}))
    r = post(f"/build/pack/api/jobs/{jid}/open", {"sutra": "trade-irs"})
    assert r.status_code == 200, r.text
    did = r.json()["id"]
    assert r.json()["url"] == f"/build/d/{did}"
    d = client.get(f"/build/d/{did}")
    assert d.status_code == 200
    assert post(f"/build/pack/api/jobs/{jid}/open", {"sutra": "nope"}).status_code == 404


def test_cancel_a_job(client, post, backend, monkeypatch):
    import asyncio

    async def slow(samples, kind, ident=None):
        await asyncio.sleep(5)
    monkeypatch.setattr(backend, "builder_design", slow)
    r = post("/build/pack/api/jobs", body())
    jid = r.json()["id"]
    c = client.delete(f"/build/pack/api/jobs/{jid}")
    assert c.status_code == 200 and c.json()["state"] == "cancelled"
    assert client.get(f"/build/pack/api/jobs/{jid}").json()["state"] == "cancelled"


def test_a_server_refusal_is_reported_per_sutra(client, post, backend, monkeypatch):
    from core.backend import BackendError

    async def refuse(samples, kind, ident=None):
        raise BackendError(403, "DRS-5002", "not an author")
    monkeypatch.setattr(backend, "builder_design", refuse)
    jid, v = run_job(client, post, body())
    assert v["state"] == "failed" and "not an author" in v["error"]


def test_jobs_belong_to_their_owner(client, post):
    jid, _ = run_job(client, post, body())
    assert client.get("/build/pack/api/jobs/nope-nope").status_code == 404
    client.app.state.pack_jobs.jobs[jid].owner = "someone-else"
    assert client.get(f"/build/pack/api/jobs/{jid}").status_code == 404
    assert client.delete(f"/build/pack/api/jobs/{jid}").status_code == 404


def test_too_many_running_jobs(client, post):
    from core.packwizard import Job
    jid, _ = run_job(client, post, body())
    jobs = client.app.state.pack_jobs
    owner = jobs.jobs[jid].owner
    for n in range(3):
        jobs.jobs[f"busy{n}"] = Job(f"busy{n}", owner, None, [], "x", state="running")
    r = post("/build/pack/api/jobs", body())
    assert r.status_code == 429 and r.json()["code"] == "DRS-5005"
    for n in range(3):
        jobs.jobs.pop(f"busy{n}")


# ---- the bundle -------------------------------------------------------------------------------------------------------------------

def test_bundle_is_the_pack_bundle_format(client, post, tmp_path):
    jid, v = run_job(client, post, body(pack={"name": "my-bank", "version": "1.2.0"}))
    s = client.get(f"/build/pack/api/jobs/{jid}/summary").json()
    assert s["file"] == "my-bank-1.2.0.tar.gz" and s["canAuthor"] is True and len(s["sha256"]) == 64 and len(s["synthetic"]) == 9
    r = client.get(f"/build/pack/api/jobs/{jid}/bundle")
    assert r.status_code == 200 and r.headers["content-disposition"] == 'attachment; filename="my-bank-1.2.0.tar.gz"'
    assert r.headers["x-drishti-sha256"] == s["sha256"]
    archive = tmp_path / "my-bank-1.2.0.tar.gz"
    archive.write_bytes(r.content)
    sha = client.get(f"/build/pack/api/jobs/{jid}/bundle", params={"part": "sha256"}).text
    assert sha.startswith(s["sha256"]) and sha.strip().endswith("my-bank-1.2.0.tar.gz")
    man = json.loads(client.get(f"/build/pack/api/jobs/{jid}/bundle", params={"part": "manifest"}).text)
    assert man["pack"] == "my-bank" and man["version"] == "1.2.0" and man["kinds"] == ["book", "counterparty", "instrument", "trade"]
    with tarfile.open(archive) as t:
        names = t.getnames()
    assert "my-bank/MANIFEST.json" in names and "my-bank/pack.yaml" in names and "my-bank/sutras/trade/trade-irs.v1.sutra.yaml" in names
    assert "my-bank/tests/trade-irs/sample-synthetic-1.json" in names and "my-bank/config/about.yaml" in names
    # the checks `pack verify` runs offline: the archive opens, the manifest's checksums match, the pack.yaml schema is accepted
    import importlib.util
    spec = importlib.util.spec_from_file_location("pb", TOOLS / "packbundle.py")
    pb = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(pb)
    op = pb.Opened(archive)
    try:
        man2, problems = pb.check_manifest(op.pack)
        assert problems == [] and man2 is not None
        assert pb.check_schema(op.pack) == []
    finally:
        op.cleanup()


def test_bundle_needs_the_author_power_and_a_finished_job(client, post, backend, monkeypatch):
    jid, _ = run_job(client, post, body())

    async def settings(ident=None):
        return {"save": False, "author": False}
    monkeypatch.setattr(backend, "studio_settings", settings)
    r = client.get(f"/build/pack/api/jobs/{jid}/bundle")
    assert r.status_code == 403 and r.json()["code"] == "DRS-5002"


def test_a_job_with_a_refused_sutra_has_no_bundle(client, post, backend, monkeypatch):
    jid, _ = run_job(client, post, body())
    client.app.state.pack_jobs.jobs[jid].drafts.pop("trade-irs")
    r = client.get(f"/build/pack/api/jobs/{jid}/bundle")
    assert r.status_code == 409


# ---- drafts -----------------------------------------------------------------------------------------------------------------------

def test_drafts_are_designs_you_can_resume(client, post):
    state = {"v": 1, "step": 2, "schemas": schemas("trade"), "files": [{"name": "t.jsonl", "rows": 10, "bytes": 5}], "overrides": {"trade": {"key": "bookId"}}, "pack": {"name": "my-bank"}}
    made = post("/build/pack/api/drafts", {"name": "my-bank", "state": state})
    assert made.status_code == 200, made.text
    did = made.json()["id"]
    listed = client.get("/build/pack/api/drafts").json()
    assert [d["name"] for d in listed["drafts"]] == ["my-bank"]
    opened = client.get(f"/build/pack/api/drafts/{did}").json()
    assert opened["state"] == state and opened["name"] == "my-bank"
    state["step"] = 3
    again = post("/build/pack/api/drafts", {"id": did, "name": "my-bank", "state": state})
    assert again.json()["id"] == did
    assert client.get(f"/build/pack/api/drafts/{did}").json()["state"]["step"] == 3
    assert "New pack: my-bank" in client.get("/build").text              # it is one of My designs
    assert client.delete(f"/build/pack/api/drafts/{did}").status_code == 200
    assert client.get("/build/pack/api/drafts").json()["drafts"] == []


def test_a_design_that_is_not_a_draft_is_refused(client, post):
    made = client.post("/build/designs", content=json.dumps({"name": "plain", "kind": "trade", "empty": True}), headers=JSON).json()
    assert client.get(f"/build/pack/api/drafts/{made['id']}").status_code == 422
    assert post("/build/pack/api/drafts", {"name": "", "state": {}}).status_code == 400


def test_a_draft_over_the_notes_limit_is_refused_with_its_size(client, post, backend):
    backend.designs_limits = {**backend.designs_limits, "maxNotesKb": 1}
    state = {"schemas": [{"name": "a.json", "text": "x" * 40000}]}
    import random
    state["schemas"][0]["text"] = "".join(random.choice("abcdefghijklmnopqrstuvwxyz0123456789") for _ in range(40000))
    r = post("/build/pack/api/drafts", {"name": "big", "state": state})
    assert r.status_code == 413 and "KB" in r.json()["detail"]
