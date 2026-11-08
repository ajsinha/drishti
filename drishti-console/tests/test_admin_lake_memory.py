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
"""Admin → Lakes and Admin → Health for the in-memory lake (docs/guides/DATA_LAKE_GUIDE.md section 9): the stores and tables the
server holds in memory only, drawn from GET /api/v1/admin/lakes/memory; nothing when the server has no memory store; and
drishti.py lake memory's text.

    drishti-console/.venv/bin/python -m pytest drishti-console/tests/test_admin_lake_memory.py -q
"""
import importlib.util
from pathlib import Path

import pytest

from core.lakes import Lakes, LakeSettings

REPO = Path(__file__).resolve().parents[2]

MEMORY = {
    "stores": [{"name": "news", "owner": True, "contract": "VX-2026-07", "capBytes": 256 << 20, "usedBytes": 3 << 20, "files": 22, "refusals": 0}],
    "tables": [{"name": "news", "store": "news", "path": "mem://news/news", "source": "url https://feeds.example.com/news.jsonl", "mode": "replace",
                "state": "ready", "stateWords": "ready", "bytes": 1 << 20, "ratio": 4.2, "rows": 71, "version": 3, "lastRefill": "2026-11-02T14:00:00Z",
                "nextRefill": "2026-11-02T14:15:00Z", "pinned": True, "evictions": 0, "savedTotalBytes": 0},
               {"name": "quote", "store": "news", "path": "mem://news/quote", "source": "file q.jsonl.gz", "mode": "replace", "state": "evicted",
                "stateWords": "evicted to make room; a refill is queued", "words": "quote: held in memory only; evicted at 14:02", "bytes": 0,
                "rows": 0, "version": -1, "pinned": False, "evictions": 1, "savedTotalBytes": 0}],
    "health": {"state": "amber", "problems": ["memory store news refused 1 write(s) since the start"]},
}


@pytest.fixture
def lakes(client, tmp_path):
    lk = Lakes(LakeSettings(roots=[tmp_path / "lake"], tools_dir=REPO / "tools", engine="deltalake"))
    client.app.state.lakes = lk
    yield lk
    del client.app.state.lakes


def fake(backend, monkeypatch, memory):
    async def admin(method, path, ident, body=None, timeout=None, headers=None, **params):
        if path == "/lakes/memory":
            return memory
        return {}
    monkeypatch.setattr(backend, "admin", admin)


def test_the_lakes_page_lists_memory_stores_and_tables(client, backend, monkeypatch, lakes):
    fake(backend, monkeypatch, MEMORY)
    page = client.get("/admin/lakes").text
    for text in ("Held in memory only", "mem://news", "VX-2026-07", "256.0 MB", "evicted to make room; a refill is queued", "4.2x",
                 "drishti.py lake memory refill", "only a download from Export data leaves the server", "amber"):
        assert text in page, text


def test_no_memory_section_without_stores(client, backend, monkeypatch, lakes):
    fake(backend, monkeypatch, {"stores": [], "tables": []})
    assert "Held in memory only" not in client.get("/admin/lakes").text


def test_the_cli_text_names_stores_tables_and_runs():
    spec = importlib.util.spec_from_file_location("memory_cli", REPO / "tools/lake/memory_cli.py")
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    out = []
    m.status_text(MEMORY, out.append)
    text = "\n".join(out)
    assert "MEMORY STORES (1)" in text and "contract VX-2026-07" in text and "TABLES (2)" in text and "pinned" in text
    assert "HEALTH  amber" in text and "quote: held in memory only" in text
    out = []
    m.jobs_text([{"at": "2026-11-02T14:00:00Z", "table": "news", "trigger": "warm-up", "outcome": "ok", "rows": 71, "bytes": 140000, "version": 0,
                  "millis": 120, "message": ""}], out.append)
    assert "RUNS (1)" in out[0] and "warm-up" in out[1] and "ok" in out[1]
