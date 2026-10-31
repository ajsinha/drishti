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

"""The console and several servers (CLUSTER_DEPLOYMENT.md › Drishti*net: many servers, one Drishti, section 11.1, core/cluster.py): stand-in servers that stop, hang or answer 503; reads tried
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
        if mode == "joining":                         # just rejoined: it has not heard from its peers yet (SOAK3-01)
            return httpx.Response(502, json={"code": "DRS-5052", "detail": "no source here holds trade/MX-1, and this node is still joining "
                                             "Drishti*net and does not know yet which nodes hold trade; try again in a moment"})
        if mode == "peer-history":                    # histories while the node that holds the data dies or restarts (SOAK-02)
            if request.url.params.get("n") == "1":
                return httpx.Response(504, json={"code": "DRS-1014", "detail": "the node drishti-c did not answer the history of trade/MX-1"})
            if request.url.params.get("n") == "2":         # a server of 2.6.5 or older named the dying peer with DRS-1003
                return httpx.Response(502, json={"code": "DRS-1003", "detail": "DRS-1003 net:drishti-c failed reading the history of trade/MX-1: "
                                                 "ConnectException"})
            return httpx.Response(502, json={"code": "DRS-5052", "detail": "no node that answered holds the history of trade/MX-1, and the "
                                             "nodes that hold trade cannot be reached now: drishti-c (not answering: the connection failed)"})
        if mode == "gone" and (request.url.path.endswith("/events") or request.url.path.endswith("/children")):   # the selections' node left
            return httpx.Response(502, json={"code": "DRS-5052", "detail": "your selections were reset because server node-b went away: pick again"})
        if request.url.path == "/api/v1/net/members":
            return httpx.Response(200, json={"running": True, "members": self.members})
        if request.url.path.endswith("/stream") and mode == "breaks":   # opens, sends a frame, then the connection drops (a restart, a proxy)
            return httpx.Response(200, headers={"content-type": "text/event-stream"}, stream=Breaks())
        if request.url.path.endswith("/stream"):
            return httpx.Response(200, headers={"content-type": "text/event-stream"}, content=b"event: view\ndata: {}\n\n")
        return httpx.Response(200, json={"served_by": base, "path": request.url.path})


class Breaks(httpx.AsyncByteStream):
    """A live stream that delivers one frame and then breaks (ReadError), as an idle or proxy timeout or a server restart does."""

    async def __aiter__(self):
        yield b"event: view\ndata: {}\n\n"
        raise httpx.ReadError("connection reset")


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


def test_a_history_about_another_node_never_sets_aside_the_server_that_answered(tmp_path):
    # SOAK-02: DRS-5052 or DRS-1014 naming another node (and a DRS-1003 of a 2.6.5 server naming a peer) is the server's own answer
    stand, p = Stand(), pool(tmp_path, f"{A},{B}")
    c = client(stand, p)
    stand.mode[A] = "peer-history"
    for _ in range(3):
        for n in ("0", "1", "2"):
            with pytest.raises(BackendError) as e:
                run(c._get("/history/trade/~", id="MX-1", range="3M", n=n))
            assert e.value.status in (502, 504) and "drishti-c" in e.value.detail
    assert p.entries[A].healthy and p.current() == A and not p.snapshot()["failovers"]
    assert {b for b, _, _ in stand.calls} == {A}


def test_a_read_on_a_node_still_joining_is_tried_once_on_another_server_without_setting_it_aside(tmp_path):
    # SOAK3-01: a node just restarted answers DRS-5052 "still joining" for what only its peers hold; another server knows
    stand, p = Stand(), pool(tmp_path, f"{A},{B}")
    c = client(stand, p)
    stand.mode[A] = "joining"
    for _ in range(3):
        assert run(c._get("/views/trade/MX-1"))["served_by"] == B
    assert p.entries[A].healthy and p.current() == A and not p.snapshot()["failovers"]
    stand.calls.clear()
    with pytest.raises(BackendError):                  # a write is never tried elsewhere
        run(c._send("POST", "/views/trade/MX-1", None))
    assert {b for b, m, _ in stand.calls if m == "POST"} == {A}
    stand.mode[B] = "joining"                          # both joining: the answer is the 502, said once
    with pytest.raises(BackendError) as e:
        run(c._get("/views/trade/MX-1"))
    assert e.value.status == 502 and "still joining" in e.value.detail


def test_a_502_of_the_servers_own_failure_still_counts(tmp_path):
    stand, p = Stand(), pool(tmp_path, f"{A},{B}")
    c = client(stand, p)
    stand.mode[A] = "own-failure"
    orig = stand.handler

    def own(request):
        if stand.mode.get(f"{request.url.scheme}://{request.url.host}:{request.url.port}") == "own-failure":
            stand.calls.append((A, request.method, request.url.path))
            return httpx.Response(502, json={"code": "DRS-1003", "detail": "DRS-1003 trade-endur failed reading the history of trade/1"})
        return orig(request)

    c._client = httpx.AsyncClient(transport=httpx.MockTransport(own))
    assert run(c._get("/history/trade/~", id="1"))["served_by"] == B          # A's own source failed: tried once on B
    run(c._get("/history/trade/~", id="1"))
    assert not p.entries[A].healthy and p.current() == B


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


def test_a_loopback_advertisement_is_never_kept_and_a_member_that_moved_replaces_its_old_address(tmp_path, monkeypatch):
    (tmp_path / "net").mkdir()
    (tmp_path / "net" / "servers.json").write_text(json.dumps({"servers": [
        {"url": "http://127.0.0.1:18784", "origin": "discovered", "node_id": "n-b"},
        {"url": "http://0.0.0.0:18785", "origin": "discovered", "node_id": "n-c"}]}))
    stand, p = Stand(), pool(tmp_path, A)
    assert [e.url for e in p.ordered()] == [A]                           # a saved loopback or wildcard address is not read back
    stand.members = [{"nodeId": "n-a", "url": A, "state": "alive"}, {"nodeId": "n-b", "url": "http://localhost:18784", "state": "alive"},
                     {"nodeId": "n-c", "url": C, "state": "alive"}]
    real = httpx.AsyncClient

    def fake_client(*a, **kw):
        kw["transport"] = httpx.MockTransport(stand.handler)
        return real(*a, **kw)
    monkeypatch.setattr(httpx, "AsyncClient", fake_client)
    run(p.discover_once({}))
    assert [e.url for e in p.ordered()] == [A, C]
    stand.members = [{"nodeId": "n-a", "url": A, "state": "alive"}, {"nodeId": "n-c", "url": B, "state": "alive"}]
    run(p.discover_once({}))
    assert [e.url for e in p.ordered()] == [A, B]                        # n-c now advertised at B: C goes
    saved = json.loads((tmp_path / "net" / "servers.json").read_text())
    assert not any(cluster.loopback_url(s["url"]) for s in saved["servers"])
    assert cluster.loopback_url("http://[::1]:1") and cluster.loopback_url("http://127.3.2.1:1") and not cluster.loopback_url("http://192.168.2.77:1")


def test_a_discovered_member_on_any_spelling_of_this_machine_is_refused():
    """The server's rule (HostAddress, tools/hostaddr.py; QA1-09): 0, 127.x, 2130706433, 0x7f000001, localhost., ip6-localhost, link-local
    addresses, and a host name that resolves to a loopback address (this machine's own name is 127.0.1.1 on Debian and Ubuntu)."""
    import socket
    for url in ("http://0:18611", "http://127.0.1.1:1", "http://2130706433:1", "http://0x7f000001:1", "http://127.1:1", "http://localhost.:1",
                "http://ip6-localhost:1", "http://foo.localhost:1", "http://169.254.10.1:1", "http://[fe80::1]:1", "http://[::]:1", "http://0.0.0.0:1"):
        assert cluster.loopback_url(url), url
    for url in ("http://192.168.2.77:1", "http://10.1.2.3:18480", "http://no-such-host.invalid:1"):
        assert not cluster.loopback_url(url), url
    name = socket.gethostname()
    try:
        resolved = {i[4][0] for i in socket.getaddrinfo(name, None)}
    except OSError:
        resolved = set()
    if resolved and all(a.startswith("127.") or a == "::1" for a in resolved):
        assert cluster.loopback_url(f"http://{name}:1")


def _drain(c):
    async def go():
        gen = c.stream("trade", "X")
        try:
            async for _ in gen:
                pass
        finally:
            await gen.aclose()
    return go()


def test_a_live_stream_that_breaks_after_it_opened_does_not_set_its_server_aside(tmp_path, monkeypatch):
    stand, p = Stand(), pool(tmp_path, f"{A},{B}")
    c = client(stand, p)
    monkeypatch.setattr(p, "probe", lambda url: _ok())         # the server answers its health check: it is up
    cluster.SESSION.set("s1")
    assert p.current() == A
    stand.mode[A] = "breaks"
    for _ in range(5):
        with pytest.raises(BackendError):
            run(_drain(c))
    assert p.entries[A].healthy, "a stream that ended is not a failed request"
    assert p.current() == A, "the session stays on its server"
    assert not p.failovers
    # a server that really does not answer normal requests is still set aside
    stand.mode[A] = "down"
    run(c._get("/x"))
    run(c._get("/x"))
    assert not p.entries[A].healthy
    assert p.current() == B
    cluster.SESSION.set("")


def test_a_session_moved_after_its_streams_broke_returns_to_its_server_once_it_is_healthy(tmp_path, monkeypatch):
    stand, p = Stand(), pool(tmp_path, f"{A},{B}")
    c = client(stand, p)
    cluster.SESSION.set("s1")
    assert p.current() == A
    stand.mode[A] = "breaks"                                   # the server restarts: its stream breaks, then it refuses for a moment
    with pytest.raises(BackendError):
        run(_drain(c))
    stand.mode[A] = "down"
    run(c._get("/x"))
    run(c._get("/x"))
    assert p.current() == B
    stand.mode[A] = "ok"
    monkeypatch.setattr(p, "probe", lambda url: _ok())
    run(p.check(p.entries[A]))
    assert p.current() == B, "back for less than a minute: not yet"
    p.entries[A].healthy_since = time.time() - 61
    assert p.current() == A, "moved only because its server restarted: it returns there"
    assert p.failovers[-1]["to"] == A
    cluster.SESSION.set("")


def test_one_server_per_screen_a_screens_selections_go_to_the_node_that_issued_them(tmp_path):
    """SUTRA_NEXT.md 7: a click and a panel's children that carry selection tokens go to the node the tokens name, whatever server the
    session is on; when that node is gone the session's server says the selections were reset, and the page says so in words."""
    from routes.terminal_routes import _selection_problem
    stand, p = Stand(), pool(tmp_path, f"{A},{B}")
    p.entries[A].node_id, p.entries[B].node_id = "node-a", "node-b"
    c = client(stand, p)
    cluster.SESSION.set("screen")
    assert p.current() == A
    held = ["tok_node-b~abc", "tok_node-b~def"]
    run(c.view_event("trade", "T-1", None, {"panel": "trades", "event": "open", "row": 0, "selections": held}))
    run(c.view_children("trade", "T-1", None, "trades", held))
    run(c.view_event("trade", "T-1", None, {"panel": "trades", "event": "open", "row": 0, "selections": []}))
    sent = [(base, path) for base, _, path in stand.calls]
    assert sent[0][0] == B and sent[0][1].endswith("/events")
    assert sent[1][0] == B and sent[1][1].endswith("/children")
    assert sent[2][0] == A, "a first click (no selections yet) goes to the session's server"
    assert p.current() == A, "the session itself did not move"
    # the node that holds the selections goes away: the click goes to the session's server, which says they were reset
    stand.mode[B] = "down"
    stand.mode[A] = "gone"
    with pytest.raises(BackendError) as e:
        run(c.view_event("trade", "T-1", None, {"panel": "trades", "event": "open", "row": 1, "selections": held}))
    assert e.value.code == "DRS-5052"
    assert p.entries[A].healthy and p.entries[A].fails == 0, "the server's own answer about another node is not its failure"
    body = json.loads(_selection_problem(e.value, held).body)
    assert body["reset"] is True
    assert body["message"] == "Your selections were reset because server node-b went away. Pick again."
    # once the node is set aside the console does not try it first any more
    with pytest.raises(BackendError):
        run(c.view_event("trade", "T-1", None, {"panel": "trades", "event": "open", "row": 1, "selections": held}))
    assert not p.entries[B].healthy
    stand.calls.clear()
    with pytest.raises(BackendError):
        run(c.view_children("trade", "T-1", None, "trades", held))
    assert stand.calls[0][0] == A
    cluster.SESSION.set("")


def test_a_selection_token_names_its_node_or_none():
    assert cluster.token_node("tok_drishti-main~Zx-_9") == "drishti-main"
    assert cluster.token_node("tok_Zx-_9abc") == ""
    assert cluster.token_node("tok_bad node~x") == ""
    assert cluster.token_node(None) == ""
