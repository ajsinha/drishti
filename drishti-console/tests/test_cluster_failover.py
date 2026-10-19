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

"""The console and several servers (DRISHTI_NET.md section 11.1, core/cluster.py): stand-in servers that stop, hang or answer 503; reads tried
once more on another server and writes never; the member list pulled, kept, saved and read again after a restart with the configured server
down; a server that comes back used again only for new sessions; a broken live stream counted against its server."""
import asyncio
import json
import time

import httpx
import pytest

from core import cluster
from core.backend import BackendClient, BackendError
from core.cluster import ServerPool

A, B, C = "http://a.test:18783", "http://b.test:18784", "http://c.test:18785"


class Settings(dict):
    def get(self, key, default=None):
        return super().get(key, default)


def pool(tmp_path, urls=A, **more):
    s = Settings({"backend.urls": urls, "backend.fail_after": 2, "backend.discover_every": "60s", "backend.check_every": "5s", **more})
    return ServerPool(s, tmp_path)


class Stand:
    """Stand-in servers: each answers, refuses, hangs or says 503, as the test sets it."""

    def __init__(self):
        self.mode = {A: "ok", B: "ok", C: "ok"}
        self.calls = []
        self.members = []

    def handler(self, request: httpx.Request):
        base = f"{request.url.scheme}://{request.url.host}:{request.url.port}"
        self.calls.append((base, request.method, request.url.path))
        mode = self.mode.get(base, "down")
        if mode == "down":
            raise httpx.ConnectError("connection refused", request=request)
        if mode == "hang":
            raise httpx.ReadTimeout("timed out", request=request)
        if mode == "503":
            return httpx.Response(503, json={"code": "DRS-5003", "detail": "service unavailable"})
        if mode == "peer-down":                       # the server answers: the node that holds the data is down
            return httpx.Response(502, json={"code": "DRS-5052", "detail": "the node drishti-c that holds trade failed reading trade/MX-1"})
        if request.url.path == "/api/v1/net/members":
            return httpx.Response(200, json={"running": True, "members": self.members})
        if request.url.path.endswith("/stream"):
            return httpx.Response(200, headers={"content-type": "text/event-stream"}, content=b"event: view\ndata: {}\n\n")
        return httpx.Response(200, json={"served_by": base, "path": request.url.path})


def client(stand, p):
    c = BackendClient(A, servers=p)
    c._client = httpx.AsyncClient(transport=httpx.MockTransport(stand.handler))
    return c


def run(coro):
    return asyncio.new_event_loop().run_until_complete(coro)


def test_without_urls_nothing_changes(tmp_path):
    p = ServerPool(Settings({"backend.url": A}), tmp_path)
    assert not p.enabled
    assert not (tmp_path / "net" / "servers.json").exists()


def test_a_read_that_fails_is_tried_once_on_another_server_and_a_write_never(tmp_path):
    stand, p = Stand(), pool(tmp_path, f"{A},{B}")
    c = client(stand, p)
    assert run(c._get("/views/trade/X"))["served_by"] == A
    stand.mode[A] = "down"
    # the read fails on A and is answered by B: the person sees the page
    assert run(c._get("/views/trade/X"))["served_by"] == B
    # a write is never tried again: it says it did not save
    with pytest.raises(BackendError) as e:
        run(c._send("PUT", "/me/layouts/x", None, json={}))
    assert e.value.status == 503 and "did not save" in e.value.detail
    assert [m for _, m, _ in stand.calls].count("PUT") == 1
    # two failures in a row: A is set aside, and the session is on B from now on
    assert not p.entries[A].healthy
    assert p.current() == B
    assert p.snapshot()["failovers"][-1]["from"] == A


def test_a_server_that_hangs_or_answers_503_is_set_aside_too(tmp_path):
    stand, p = Stand(), pool(tmp_path, f"{A},{B}")
    c = client(stand, p)
    stand.mode[A] = "hang"
    assert run(c._get("/search", q="TRD"))["served_by"] == B
    stand.mode[A] = "503"
    assert run(c._get("/search", q="TRD"))["served_by"] == B
    assert p.current() == B


def test_a_502_about_another_node_is_the_servers_answer_not_its_failure(tmp_path):
    stand, p = Stand(), pool(tmp_path, f"{A},{B}")
    c = client(stand, p)
    stand.mode[A] = "peer-down"
    for _ in range(4):                                # every MX trade while the node holding them is down: the console stays on A
        with pytest.raises(BackendError) as e:
            run(c._get("/views/trade/MX-1"))
        assert e.value.status == 502 and "drishti-c" in e.value.detail
    assert p.entries[A].healthy and p.current() == A and not p.snapshot()["failovers"]
    assert {b for b, _, _ in stand.calls} == {A}      # never retried elsewhere: another server would say the same


def test_sessions_keep_their_server_and_a_recovered_one_is_used_only_by_new_sessions(tmp_path, monkeypatch):
    stand, p = Stand(), pool(tmp_path, f"{A},{B}")
    c = client(stand, p)
    cluster.SESSION.set("s1")
    stand.mode[A] = "down"
    run(c._get("/x"))
    run(c._get("/x"))
    assert p.current() == B
    stand.mode[A] = "ok"
    p.entries[A].fails = 0
    monkeypatch.setattr(p, "probe", lambda url: _ok())
    run(p.check(p.entries[A]))
    assert p.entries[A].healthy
    cluster.SESSION.set("s2")
    assert p.current() == B, "back for less than a minute: not used yet"
    p.entries[A].healthy_since = time.time() - 61
    cluster.SESSION.set("s3")
    assert p.current() == A, "a new session goes to the configured server again"
    cluster.SESSION.set("s1")
    assert p.current() == B, "a running session is not moved"
    cluster.SESSION.set("")


async def _ok():
    return 1.0


def test_the_member_list_is_pulled_kept_saved_and_read_after_a_restart_with_the_configured_server_down(tmp_path, monkeypatch):
    stand, p = Stand(), pool(tmp_path, A)
    stand.members = [{"nodeId": "n-a", "url": A, "state": "alive"}, {"nodeId": "n-b", "url": B, "state": "alive"},
                     {"nodeId": "n-c", "url": C, "state": "alive"}]
    real = httpx.AsyncClient

    def fake_client(*a, **kw):
        kw["transport"] = httpx.MockTransport(stand.handler)
        return real(*a, **kw)
    monkeypatch.setattr(httpx, "AsyncClient", fake_client)
    run(p.discover_once({}))
    assert [e.url for e in p.ordered()] == [A, B, C]
    saved = json.loads((tmp_path / "net" / "servers.json").read_text())
    assert {s["url"] for s in saved["servers"]} == {A, B, C}
    assert not any(f.name.endswith(".tmp") for f in (tmp_path / "net").iterdir())
    # a member the cluster reports left is dropped; the configured one never is
    stand.members = [{"nodeId": "n-a", "url": A, "state": "left"}, {"nodeId": "n-b", "url": B, "state": "alive"},
                     {"nodeId": "n-c", "url": C, "state": "dead"}]
    run(p.discover_once({}))
    assert [e.url for e in p.ordered()] == [A, B]
    # the console restarts while A is down: it still knows B from the file, and goes there
    monkeypatch.setattr(httpx, "AsyncClient", real)
    again = pool(tmp_path, A)
    assert [e.url for e in again.ordered()] == [A, B]
    stand.mode[A] = "down"
    c = client(stand, again)
    assert run(c._get("/views/trade/X"))["served_by"] == B


def test_a_broken_live_stream_counts_against_its_server_so_the_reconnect_goes_elsewhere(tmp_path):
    stand, p = Stand(), pool(tmp_path, f"{A},{B}")
    c = client(stand, p)

    async def first_event():
        gen = c.stream("trade", "X")
        try:
            async for ev in gen:
                return ev
        finally:
            await gen.aclose()
    assert run(first_event())[0] == "view"
    stand.mode[A] = "down"
    for _ in range(2):
        with pytest.raises(BackendError):
            run(first_event())
    assert not p.entries[A].healthy
    assert run(first_event())[0] == "view"                   # the browser's reconnect: on B
    assert stand.calls[-1][0] == B
