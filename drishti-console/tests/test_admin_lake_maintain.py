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

"""Admin → Lakes → Maintain through the console's API: the tables with their owner (a table another system published is read only), a dry
run and a run on a tiny lake made by lake build (progress, history, the data-load signal as the admin), settings saved to the shared YAML,
the CLI line, schedules created, paused and deleted, and the scheduler starting a due schedule (with the catch-up after a stop).

    drishti-console/.venv/bin/python -m pytest drishti-console/tests/test_admin_lake_maintain.py -q
"""
import importlib.util
import io
import json
import sys
import time
from contextlib import redirect_stderr, redirect_stdout
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo

import pytest
import yaml

from core.lake_maint import maintenance
from core.lakes import Lakes, LakeSettings

REPO = Path(__file__).resolve().parents[2]
pytestmark = pytest.mark.skipif(importlib.util.find_spec("deltalake") is None, reason="needs deltalake and pyarrow")
NY = ZoneInfo("America/New_York")


def make_lake(root: Path) -> None:
    """trading/trade built by lake build (two small builds a day for three days), risk/published written by another system."""
    sys.path.insert(0, str(REPO / "tools" / "lake"))
    import build
    src = root.parent / "trade.jsonl"
    for d in ("2026-10-14", "2026-10-15", "2026-10-16", "2026-10-14", "2026-10-15", "2026-10-16"):
        src.write_text("\n".join(json.dumps({"TRADE_ID": f"T-{d}-{i}-{time.monotonic_ns()}", "TRADE_DATE": d, "QUANTITY": i}) for i in range(20)))
        with redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
            assert build.main(["--from", str(src), "--lake", str(root), "--domain", "trading", "--kind", "trade", "--key", "TRADE_ID",
                               "--date", "TRADE_DATE", "--engine", "deltalake", "--pack-name", "trading", "--mode", "append", "-q"]) == 0
    import pyarrow as pa
    from datetime import date
    from deltalake import write_deltalake
    write_deltalake(str(root / "risk" / "published"), pa.table({"id": ["A"], "doc": ["{}"], "business_date": pa.array([date(2026, 10, 16)], pa.date32())}),
                    partition_by=["business_date"])


@pytest.fixture
def lake(client, backend, monkeypatch, tmp_path):
    root = tmp_path / "lake"
    make_lake(root)
    lk = Lakes(LakeSettings(roots=[root], tools_dir=REPO / "tools", engine="deltalake", data_dir=tmp_path / "data"))
    client.app.state.lakes = lk
    sent = []

    async def announce_load(pack, body, ident):
        sent.append((pack, body, ident.user))
        return {"verified": "verified"}
    monkeypatch.setattr(backend, "announce_load", announce_load, raising=False)
    yield root, tmp_path / "data", sent
    del client.app.state.lakes
    client.app.state.__dict__.pop("lake_maint", None)


def wait(client, rid):
    deadline = time.time() + 60
    while True:
        r = client.get(f"/admin/api/lakes/maintain/runs/{rid}").json()
        if r["state"] != "running" or time.time() > deadline:
            return r
        time.sleep(0.2)


def test_the_page_lists_tables_with_owner_and_a_foreign_table_read_only(client, lake):
    root, _, _ = lake
    page = client.get("/admin/lakes").text
    for text in ("data-mt-dry", "Maintain now", "Maintained by its publisher", "admin-lake-maintain.js", "Schedules", "Maintenance history",
                 "lake maintain --config"):
        assert text in page, text
    o = client.get("/admin/api/lakes/maintain").json()
    by = {t["table"]: t for t in o["tables"]}
    assert by["trading/trade"]["owned"] and by["trading/trade"]["owner"]["pack"] == "trading"
    assert not by["risk/published"]["owned"] and "maintained by its publisher" in by["risk/published"]["reason"]
    assert o["configFile"].endswith("lakes/lake-maintenance.yaml") and o["history"] == []
    r = client.post("/admin/api/lakes/maintain/runs", json={"tables": {str(root): ["risk/published"]}, "dryRun": True})
    assert r.status_code == 403 and "publisher" in r.json()["detail"]
    r = client.post("/admin/api/lakes/maintain/runs", json={"tables": {"/etc": ["trading/trade"]}})
    assert r.status_code == 400


def test_dry_run_then_maintain_signals_and_keeps_history(client, lake):
    root, data, sent = lake
    sel = {str(root): ["trading/trade"]}
    r = client.post("/admin/api/lakes/maintain/runs", json={"tables": sel, "dryRun": True})
    assert r.status_code == 202, r.text
    dry = wait(client, r.json()["id"])
    assert dry["state"] == "done" and dry["entry"]["outcome"] == "ok"
    t = dry["results"][0]
    assert t["before"]["files"] == 6 and t["estimate"]["files"] == 3 and t["dryRun"]              # two small files a date become one
    real = wait(client, client.post("/admin/api/lakes/maintain/runs", json={"tables": sel}).json()["id"])
    assert real["state"] == "done", real
    assert real["results"][0]["after"]["files"] == 3
    assert [(p, b["kind"], b["businessDate"], b["rows"], b["source"], u) for p, b, u in sent] == [("trading", "trade", "2026-10-16", 40, "lake maintain", "ash")]
    assert real["results"][0]["signal"][0]["ok"]
    hist = client.get("/admin/api/lakes/maintain").json()["history"]
    assert [h["dryRun"] for h in hist] == [False, True] and hist[0]["by"] == "ash"
    assert (data / "lakes" / "maintenance-history.jsonl").is_file()
    cmd = client.post("/admin/api/lakes/maintain/command", json={"tables": sel, "dryRun": True}).json()["command"]
    assert "lake maintain --config" in cmd and "--tables trading/trade --dry-run" in cmd


def test_settings_are_saved_in_the_shared_yaml(client, lake):
    root, data, _ = lake
    sel = {str(root): ["trading/trade"]}
    r = client.put("/admin/api/lakes/maintain/settings", json={"tables": sel, "settings": {"keep-business-days": "260", "vacuum-hours": "24"}})
    assert r.status_code == 200 and r.json()["warnings"] and "168" in r.json()["warnings"][0]
    doc = yaml.safe_load((data / "lakes" / "lake-maintenance.yaml").read_text())
    assert doc["lakes"][0]["root"] == str(root)
    assert doc["lakes"][0]["tables"]["trading/trade"] == {"keep-business-days": 260, "vacuum-hours": 24}
    s = {t["table"]: t for t in client.get("/admin/api/lakes/maintain").json()["tables"]}["trading/trade"]["settings"]
    assert (s["keep-business-days"], s["vacuum-hours"], s["target-file-mb"]) == (260, 24, 128)
    assert client.put("/admin/api/lakes/maintain/settings", json={"tables": sel, "settings": {"keep-business-days": "-3"}}).status_code == 400
    assert client.put("/admin/api/lakes/maintain/settings", json={"tables": {str(root): ["risk/published"]}, "settings": {"checkpoint": False}}).status_code == 403


def test_schedules_created_paused_deleted_and_the_scheduler_starts_them(client, lake):
    root, data, _ = lake
    sel = {str(root): ["trading/trade"]}
    r = client.post("/admin/api/lakes/maintain/schedules", json={"name": "nightly", "tables": sel, "days": ["tue", "wed", "thu", "fri", "sat"], "at": "02:30"})
    assert r.status_code == 201, r.text
    assert (r.json()["when"], r.json()["friendly"]) == ("30 2 * * 2,3,4,5,6", "Tue–Sat 02:30")
    assert client.post("/admin/api/lakes/maintain/schedules", json={"name": "nightly", "tables": sel, "afterBuild": True}).status_code == 409
    assert client.post("/admin/api/lakes/maintain/schedules", json={"name": "bad", "tables": sel, "cron": "99 * * * *"}).status_code == 400
    assert client.post("/admin/api/lakes/maintain/schedules", json={"name": "after", "tables": sel, "afterBuild": True}).status_code == 201
    doc = yaml.safe_load((data / "lakes" / "lake-maintenance.yaml").read_text())
    assert [s["name"] for s in doc["lakes"][0]["schedules"]] == ["nightly", "after"] and doc["lakes"][0]["schedules"][0]["by"] == "ash"
    o = client.get("/admin/api/lakes/maintain").json()
    assert o["schedules"][0]["next"] and o["schedules"][1]["friendly"] == "after every build of the table"
    assert client.post("/admin/api/lakes/maintain/schedules/pause", json={"root": str(root), "name": "after", "paused": True}).json()["paused"]

    m = maintenance(client.app)
    ident = lambda s: None                                                  # noqa: E731 - signals are not under test here
    assert m.tick(ident, datetime(2026, 10, 15, 12, 0, tzinfo=NY)) == []  # new schedules: remembered, run from their next time
    m.scheduler.started = False
    runs = m.tick(ident, datetime(2026, 10, 16, 2, 30, 30, tzinfo=NY))    # Friday 02:30: nightly is due
    assert [r.schedule for r in runs] == ["nightly"]
    done = wait(client, runs[0].id)
    assert done["state"] == "done" and done["by"] == "ash"
    m.scheduler = None                                                       # the console stops, then starts on Sunday
    again = m.tick(ident, datetime(2026, 10, 18, 9, 0, tzinfo=NY))
    assert [r.schedule for r in again] == ["nightly"] and again[0].note.startswith("catch-up")
    wait(client, again[0].id)
    hist = client.get("/admin/api/lakes/maintain").json()["history"]
    assert hist[0]["schedule"] == "nightly" and hist[0]["note"].startswith("catch-up")
    assert client.post("/admin/api/lakes/maintain/schedules/delete", json={"root": str(root), "name": "nightly"}).json()["deleted"]
    assert [s["name"] for s in client.get("/admin/api/lakes/maintain").json()["schedules"]] == ["after"]


def test_an_iceberg_table_is_listed_with_its_format(client, lake):
    """2.9.5: an Iceberg table under a lake root (lake build --format iceberg) shows Format Iceberg; Delta stays the default."""
    root, _, _ = lake
    t = root / "risk" / "pnl-vector"
    (t / "metadata").mkdir(parents=True)
    (t / "metadata" / "v1.metadata.json").write_text(json.dumps({"format-version": 2, "table-uuid": "u", "current-snapshot-id": 1,
                                                                 "snapshots": [{"snapshot-id": 1, "timestamp-ms": 1, "summary": {"total-records": "3"}}]}))
    (t / "metadata" / "version-hint.text").write_text("1")
    o = client.get("/admin/api/lakes/maintain").json()
    by = {x["table"]: x for x in o["tables"]}
    assert by["risk/pnl-vector"]["format"] == "iceberg" and by["trading/trade"]["format"] == "delta"
    page = client.get("/admin/lakes").text
    assert 'data-mt-format="iceberg"' in page and 'data-mt-format="delta"' in page and ">Format</th>" in page

