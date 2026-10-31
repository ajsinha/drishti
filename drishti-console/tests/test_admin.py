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



def test_admins_see_and_purge_caches(client):
    page = client.get("/admin/caches").text
    assert "trading-stream" in page and 'data-purge="all"' in page and "diskMb" in page
    r = client.post("/admin/api/caches/trading-stream/purge")
    assert r.status_code == 200 and r.json()["purged"] == ["trading-stream"]


def test_the_caches_page_shows_totals_sizes_dates_and_purges_a_date(client, backend):
    """2.9.7 (O1, O2): memory and disk totals; per cache where it lives, size against its limit, hit rate, last purge; a purge of one
    date, on this server or the whole cluster; a cache's dates drawn below its row."""
    page = client.get("/admin/caches").text
    assert "data-cache-totals" in page and "caches 100.0 MB of 2.0 GB" in page and "guard 20.0 GB" in page
    assert 'data-cache-row="var.hot-files"' in page and "11.2 GB / 40.0 GB (28%)" in page and "33 entries / 60" in page
    assert "94%" in page and "ops-admin" in page and 'data-purge-date="var.hot-files"' in page and 'data-cache-dates="var.hot-files"' in page
    assert "data-purge-cluster" in page
    calls = []
    real = backend.admin

    async def spy(method, path, ident, body=None, **params):
        calls.append((method, path, params))
        return await real(method, path, ident, body, **params)

    backend.admin = spy
    r = client.post("/admin/api/caches/var.hot-files/purge?date=2026-10-29&scope=cluster")
    assert r.status_code == 200
    assert calls[-1] == ("POST", "/caches/var.hot-files/purge", {"date": "2026-10-29", "scope": "cluster"})
    frag = client.get("/admin/caches/var.hot-files/dates").text
    assert "2026-10-30" in frag and "open" in frag and "5.1 GB" in frag and "438361ce8f808055" in frag


def test_the_id_map_dates_say_why_a_map_was_dropped_and_warn_about_one_over_the_cap(client, backend, monkeypatch):
    """2.9.7: the connectors' id maps per date: mapped or on disk, a warning when one map alone is over id-map-disk.max-gb, and why a
    map left the folder (expired (ttl), evicted (max-gb), replaced (new version))."""
    rows = [{"connector": "risk-lake", "kind": "trade", "date": "2026-10-30", "version": "41@1761800000000", "state": "mapped",
             "sizeBytes": 1610612736, "lastUsedAt": "2026-10-30T10:00:00Z", "warning": True,
             "warningText": "the id map of trade 2026-10-30 (version 41@1761800000000) is 1.50 GB alone, more than id-map-disk.max-gb (1.0 MB): "
                            "it is kept while in use and the other dates are trimmed; raise id-map-disk.max-gb of risk-lake to at least 3 GB"},
            {"connector": "risk-lake", "kind": "trade", "date": "2026-10-01", "version": "12@1", "state": "dropped", "reason": "expired (ttl)",
             "sizeBytes": 1048576, "at": "2026-10-30T09:00:00Z"},
            {"connector": "risk-lake", "kind": "trade", "date": "2026-10-02", "state": "dropped", "reason": "evicted (max-gb)", "sizeBytes": 2048},
            {"connector": "risk-lake", "kind": "trade", "date": "2026-10-29", "state": "dropped", "reason": "replaced (new version)", "sizeBytes": 2048}]
    real = backend.admin

    async def admin(method, path, ident, body=None, **params):
        if path == "/caches/connectors.id-maps/dates":
            return rows
        return await real(method, path, ident, body, **params)

    monkeypatch.setattr(backend, "admin", admin)
    frag = client.get("/admin/caches/connectors.id-maps/dates").text
    assert "risk-lake" in frag and "trade" in frag and "mapped" in frag and "1.5 GB" in frag
    assert "data-id-map-warning" in frag and "raise id-map-disk.max-gb of risk-lake to at least 3 GB" in frag
    assert frag.count("data-id-map-warning") == 1
    for reason in ("expired (ttl)", "evicted (max-gb)", "replaced (new version)"):
        assert reason in frag
    assert "41@1761800000000" in frag and "2026-10-30 09:00:00" in frag


def test_the_health_page_shows_connectors_packs_and_live(client, backend, monkeypatch):
    data = {"status": "DEGRADED", "summary": {"sources": 3, "sourcesDown": 1, "failedToStart": 1, "packs": 2, "packsWithProblems": 1},
            "server": {"version": "1.9.0", "uptimeSeconds": 3725, "java": "21", "heapUsedMb": 300, "heapMaxMb": 4000, "threads": 60, "cpus": 8},
            "sources": [{"name": "trading-store", "status": "DOWN", "health": "DOWN: connection refused (reconnecting)", "kinds": ["trade"], "live": False,
                         "dated": True, "search": True, "reads": {"reads": 12, "found": 9, "notHeld": 1, "errors": 2, "lastError": "SQLException: refused",
                                                                   "lastErrorAt": "2026-09-30T20:01:02Z", "p50Ms": 1.2, "p99Ms": 9.5}, "cache": {},
                         "pool": {"type": "jdbc", "exempt": False, "max": 20, "open": 20, "inUse": 20, "idle": 0, "waiting": 3, "longestBorrowMillis": 41500,
                                  "leaksDetected": 1, "timeouts": 2}},
                        {"name": "demo", "status": "UP", "health": "UP", "kinds": ["trade"], "live": True, "dated": False, "search": True,
                         "reads": {"reads": 0}, "cache": {"entries": 36}}],
            "failedToStart": {"feed-fred": "no API key"},
            "identityPool": {"type": "identity", "exempt": False, "max": 20, "open": 3, "inUse": 1, "idle": 2, "waiting": 0, "longestBorrowMillis": -1,
                             "leaksDetected": 0, "timeouts": 0},
            "poolsAttention": ["trading-store: every connection is in use and callers are waiting (raise pool-size, or find what holds connections)"],
            "packs": [{"name": "trading", "title": "Trading", "version": "1.0.0", "requires": ["market-data"], "kinds": 1, "sutras": 125,
                       "sutraProblems": [{"file": "rates/x.v1.sutra.md", "problems": ["DRS-2021 unknown panel kind"]}], "connectors": ["trading-store"],
                       "connectorsDown": ["trading-store"], "connectorsOff": [], "status": "DEGRADED"}],
            "live": {"streams": 2, "topics": 3, "frames": 120, "droppedFrames": 0, "p50Ms": 3.1, "p99Ms": 11.0},
            "tmp": {"base": "/var/tmp/drishti", "path": "/var/tmp/drishti/drishti-4242", "bytes": 5242880, "files": 7}}

    async def admin(method, path, ident, body=None, **params):
        if path == "/connectors":                                       # the connector files; "demo" is a plugin's own source
            return {"connectors": [{"name": "trading-store"}, {"name": "feed-fred"}]}
        assert path == "/health"
        return data
    monkeypatch.setattr(backend, "admin", admin)
    page = client.get("/admin/health").text
    assert "DEGRADED" in page and "2 of 3 connectors up" in page.replace("<b>", "").replace("</b>", "")
    assert "trading-store" in page and "connection refused (reconnecting)" in page and "SQLException: refused" in page
    assert "feed-fred" in page and "no API key" in page and "unknown panel kind" in page and "open streams" in page
    assert 'href="/admin/connectors/trading-store"' in page and 'href="/admin/connectors/feed-fred"' in page   # names open their pages
    assert 'href="/admin/packs/trading"' in page
    assert 'href="/admin/connectors/demo"' not in page                                   # no page for a source that is not a connector
    flat = " ".join(__import__("re").sub(r"<[^>]+>", "", page).split())
    assert "20/20 in use · 0 idle · 3 waiting · longest borrow 41.5 s · 1 leak warning · 2 timeouts" in flat      # the connector's pool
    assert "Identity database pool (PostgreSQL): 1/20 in use · 2 idle · 0 waiting · longest borrow n/a" in flat
    assert "data-pool-attention" in page and "every connection is in use" in page
    assert "server /var/tmp/drishti/drishti-4242 · 5.0 MB · 7 files" in flat                    # the temp folders of the two processes (path, size)
    assert "data-tmp-folders" in page and "data-tmp-console" in page and "console " in flat
    assert "data-tmpdir-warning" not in page                                            # no warning field: none shown
    data["tmp"].update({"jvmTmpdir": "/tmp", "jvmTmpdirPassed": False, "nativeCopiesRemoved": 3,
                        "jvmTmpdirWarning": "java.io.tmpdir was not set when Java started ... Start Java with -Djava.io.tmpdir=/var/tmp/drishti"})
    page = client.get("/admin/health").text
    flat = " ".join(__import__("re").sub(r"<[^>]+>", "", page).split())
    assert "data-tmpdir-warning" in page and "Java temp folder java.io.tmpdir was not set" in flat and "-Djava.io.tmpdir=/var/tmp/drishti" in flat
    assert "3 stale native copies removed at start" in flat
    assert "data-health-disk" not in page                                              # a server before 2.9.7: no Disk section
    data["disk"] = {"path": "/srv/drishti/data", "totalBytes": 1099511627776, "freeBytes": 32212254720, "guardBytes": 21474836480, "state": "amber",
                    "folders": [{"name": "var/hot", "path": "/srv/drishti/data/var/hot", "usedBytes": 45097156608, "limitBytes": 42949672960, "state": "red",
                                 "oldest": "2026-09-01/inputs-0123.vhot (last used 2026-10-01T00:00:00Z)"},
                                {"name": "dumps", "path": "/srv/drishti/data/dumps", "usedBytes": 0, "limitBytes": 0, "state": "ok", "oldest": ""}]}
    page = client.get("/admin/health").text
    flat = " ".join(__import__("re").sub(r"<[^>]+>", "", page).split())
    assert "data-health-disk" in page and 'data-disk-folder="var/hot"' in page                 # 2.9.7 (O7): Health, Disk
    assert "Disk 30.0 GB free of 1024.0 GB · guard 20.0 GB" in flat and "amber" in flat and "42.0 GB / 40.0 GB" in flat and "red" in flat
    body = client.get("/admin/health", params={"partial": 1}).text
    assert "<html" not in body and "trading-store" in body
    assert "data-health-body" not in body and "setInterval" in client.get("/static/js/admin.js").text


def test_the_health_page_shows_direct_memory_and_open_files(client, backend, monkeypatch):
    """What a long run exhausts: direct memory against -XX:MaxDirectMemorySize (amber from 80%), mapped files, heap, threads, files."""
    server = {"version": "2.9.6", "uptimeSeconds": 60, "java": "21", "heapUsedMb": 300, "heapMaxMb": 4000, "threads": 60, "cpus": 8,
              "directUsedBytes": 50 * 1048576, "directCapacityBytes": 50 * 1048576, "directBuffers": 12, "directMaxBytes": 256 * 1048576,
              "directPercent": 19, "directWarning": False, "mappedUsedBytes": 80 * 1048576, "mappedBuffers": 4, "openFiles": 412,
              "maxFiles": 65536, "deletedOpenFiles": 3}
    data = {"status": "OK", "summary": {"sources": 0, "sourcesDown": 0, "failedToStart": 0, "packs": 0, "packsWithProblems": 0},
            "server": server, "sources": [], "packs": [], "live": {}, "tmp": {}}

    async def admin(method, path, ident, body=None, **params):
        return {"connectors": []} if path == "/connectors" else data
    monkeypatch.setattr(backend, "admin", admin)
    page = client.get("/admin/health").text
    flat = " ".join(__import__("re").sub(r"<[^>]+>", "", page).split())
    assert "data-process-memory" in page
    assert "direct 50.0/256 MB (19%) · 12 buffers" in flat and "mapped 80.0 MB · 4 files" in flat
    assert "heap 300/4000 MB" in flat and "60 live threads" in flat and "412/65536 open files · 3 on deleted files" in flat
    assert "data-direct-warning" not in page and 'class="mono t-warn" data-direct' not in page
    server.update(directUsedBytes=220 * 1048576, directPercent=85, directWarning=True)
    page = client.get("/admin/health").text
    assert 'class="mono t-warn" data-direct' in page and "data-direct-warning" in page and "85% of -XX:MaxDirectMemorySize" in page
    # a server older than 2.9.6 sends none of it: no row
    data["server"] = {k: server[k] for k in ("version", "uptimeSeconds", "java", "heapUsedMb", "heapMaxMb", "threads", "cpus")}
    assert "data-process-memory" not in client.get("/admin/health").text


def test_a_degraded_connector_is_shown_with_what_it_cannot_read(client, backend, monkeypatch):
    """DATA-18: a connector that serves but cannot read a table says which, in amber."""
    data = {"status": "DEGRADED", "summary": {"sources": 1, "sourcesDown": 0, "sourcesDegraded": 1, "failedToStart": 0, "packs": 0,
                                              "packsWithProblems": 0},
            "server": {"version": "1.13.0", "uptimeSeconds": 60, "java": "25", "heapUsedMb": 300, "heapMaxMb": 4000, "threads": 60, "cpus": 8},
            "sources": [{"name": "trading-store", "status": "DEGRADED", "health": "DEGRADED: cannot read trade 2026-09-30: LZ4 (engine: native)",
                         "kinds": ["trade"], "live": False, "dated": True, "search": True, "reads": {"reads": 0}, "cache": {}}],
            "failedToStart": {}, "packs": [], "overrides": [],
            "live": {"streams": 0, "topics": 0, "frames": 0, "droppedFrames": 0, "p50Ms": 0, "p99Ms": 0}}

    async def admin(method, path, ident, body=None, **params):
        return data
    monkeypatch.setattr(backend, "admin", admin)
    page = client.get("/admin/health").text
    assert "hl-warn" in page and "cannot read trade 2026-09-30" in page
    assert "1 degraded" in page.replace("<b>", "").replace("</b>", "")


def test_the_health_page_draws_activity_and_page_times(client, backend, monkeypatch):
    # requests a minute and the average response time, the last 30 minutes, drawn as SVG; the slowest kinds of pages first
    activity = [{"minute": f"2026-10-18T13:{m:02d}:00Z", "requests": m * 10, "avgMs": 20.0 + m, "maxMs": 90.0 + m, "errors": 1 if m == 29 else 0}
                for m in range(30)]
    data = {"status": "OK", "summary": {"sources": 0, "sourcesDown": 0, "failedToStart": 0, "packs": 0, "packsWithProblems": 0}, "server": {"version": "2.3.0", "uptimeSeconds": 60, "java": "21", "heapUsedMb": 300, "heapMaxMb": 8000, "threads": 60, "cpus": 8}, "sources": [], "packs": [], "live": {},
            "activity": activity, "pages": [{"kind": "counterparty-trades", "views": 277, "p50Ms": 70.3, "p95Ms": 1099.0, "p99Ms": 1430.0},
                                             {"kind": "trade", "views": 1678, "p50Ms": 17.6, "p95Ms": 53.7, "p99Ms": 84.5}]}

    async def admin(method, path, ident, body=None, **params):
        return {"connectors": []} if path == "/connectors" else data
    monkeypatch.setattr(backend, "admin", admin)
    page = client.get("/admin/health").text
    assert "Activity, last 30 minutes" in page and page.count('class="act-bar') == 30
    assert "act-err" in page and "<polyline" in page and "13:29 UTC: 290 requests, average 49 ms, slowest 119 ms, 1 errors" in page
    assert "most <b class=\"mono\">290</b>" in page
    assert "data-page-times" in page and page.index("counterparty-trades") < page.index(">trade<") and "t-warn" in page   # p95 over 1 s marked


def test_the_tokens_page_says_what_each_token_may_do_and_whose_clock_last_used_is_on(client, backend, monkeypatch):
    """QA2-08: write-scoped tokens exist, so the page never says tokens change nothing; a Scopes column tells read-only from writing
    tokens, and Last used is in the console's zone with its label (2026-10-20 12:54 UTC is 08:54 New York)."""
    rows = [{"id": "t1", "user": "wes", "name": "Publisher", "createdAt": "2026-10-19T10:00:00Z", "expiresAt": "2026-11-18T10:00:00Z",
             "lastUsedAt": "2026-10-20T12:54:00Z", "revokedAt": None, "active": True, "scopes": ["read", "bi:write"]},
            {"id": "t2", "user": "ana", "name": "Notebook", "createdAt": "2026-10-19T10:00:00Z", "expiresAt": None,
             "lastUsedAt": None, "revokedAt": None, "active": True, "scopes": ["read"]}]

    async def admin(method, path, ident, body=None, **params):
        assert path == "/tokens"
        return rows
    monkeypatch.setattr(backend, "admin", admin)
    page = client.get("/admin/tokens").text
    flat = " ".join(__import__("re").sub(r"<[^>]+>", "", page).split())
    assert "never changes anything" not in page
    assert "<th>Scopes</th>" in page
    assert "writes bi:write" in flat and "read only" in flat
    assert "2026-10-20 08:54 New York" in flat
