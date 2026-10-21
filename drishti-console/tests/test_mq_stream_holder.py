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

"""A standing query's stream on a console of several servers (2.6.7: every visual said "Not updating · old: stream refused"): the stream goes
to the server of the node that holds the subscription (named in its id, ``sq-<node>-<hex>``) whichever console worker opens it; a refusal
reaches the browser with the server's own code and reason; on DRS-9180 (the server no longer holds it: it restarted) the browser subscribes
again once and reconnects; the foot says the reason in words, never "old:"."""
from __future__ import annotations

import asyncio
import json
from types import SimpleNamespace

import httpx

from conftest import CONSOLE
from core.backend import BackendClient, BackendError
from core.channel import ChannelSession, holder
from core.cluster import ServerPool

A, B = "http://10.1.1.1:18480", "http://10.1.1.2:18480"
SQ = "sq-drishti-main-0123456789abcdef"
JS = CONSOLE / "web/static/js"


def pool(tmp_path) -> ServerPool:
    p = ServerPool({"backend.urls": f"{A},{B}"}, tmp_path)
    p.entries[A].node_id, p.entries[B].node_id = "drishti-main", "drishti-2"
    return p


def client(p: ServerPool, handler) -> BackendClient:
    c = BackendClient(A, servers=p)
    c._client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    return c


def test_the_holder_is_read_from_the_subscription_id():
    assert holder(SQ) == "drishti-main"
    assert holder("sq-java-2-00000000000000ff") == "java-2"
    assert holder("sq_0123456789abcdef") is None and holder("") is None


def test_a_stream_goes_to_the_holders_server_whatever_the_workers_own_server(tmp_path):
    p = pool(tmp_path)
    seen = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(f"{request.url.scheme}://{request.url.netloc.decode()}")
        return httpx.Response(200, headers={"content-type": "text/event-stream"}, text='event:status\ndata:{"subscriptions":["%s"]}\n\n' % SQ)

    p.pins[""] = B                                            # the worker's session uses B

    async def go():
        c = client(p, handler)
        return [e async for e in c.sse(f"/bi/mq/stream?ids={SQ}", node=holder(SQ))]

    events = asyncio.run(go())
    assert seen == [A] and events[0][0] == "status"
    assert p.for_node("drishti-2") == B and p.for_node("gone-node") is None


def test_a_refused_stream_says_the_servers_code_and_reason(tmp_path):
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(404, headers={"content-type": "application/problem+json"},
                              json={"code": "DRS-9180", "detail": f"DRS-9180 you hold no standing query '{SQ}'", "status": 404})

    async def go():
        c = client(pool(tmp_path), handler)
        try:
            async for _ in c.sse(f"/bi/mq/stream?ids={SQ}"):
                pass
        except BackendError as e:
            return e
        return None

    e = asyncio.run(go())
    assert e is not None and e.status == 404 and e.code == "DRS-9180" and "you hold no standing query" in e.detail
    assert "stream refused" not in e.detail


def test_the_channel_passes_the_refusal_on_to_the_browser():
    class Backend:
        async def sse(self, path, ident=None, opened=None, node=None):
            raise BackendError(404, "DRS-9180", f"DRS-9180 you hold no standing query '{SQ}'")
            yield  # pragma: no cover - an async generator

    async def go():
        session = ChannelSession(Backend(), SimpleNamespace(user="rita"), lambda e, d: d, limit=4)

        async def never():
            return False

        out = []
        agen = session.events([f"mq:{SQ}"], never)
        async for chunk in agen:
            out.append(chunk)
            if len(out) >= 2:
                break
        await agen.aclose()
        return out

    out = asyncio.run(go())
    head, data = out[1].split("\n")[0], json.loads(out[1].split("\n")[1][6:])
    d = data["d"] if isinstance(data["d"], dict) else json.loads(data["d"])
    assert head == "event: gone" and data["ch"] == f"mq:{SQ}" and d["code"] == "DRS-9180" and "you hold no standing query" in d["detail"]


def test_the_browser_subscribes_again_once_on_drs_9180_and_says_why_in_words():
    consume = (JS / "bi-consume.js").read_text(encoding="utf-8")
    assert "d.code === 'DRS-9180' && !again" in consume and "subscribe();" in consume and "gone: lost" in consume
    assert "reconnecting: 'Reconnecting'" in consume and "C.reason = function" in consume
    css = (CONSOLE / "web/static/css/bi-board.css").read_text(encoding="utf-8")
    assert "old:" not in css
