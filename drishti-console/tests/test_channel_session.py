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

"""The live channel as a class (ELEMENTS.md step 4): the same logic under /api/channel and /embed/v1/channel, with the
identity passed in. Two users on two channels each see frames built for themselves only; the owner is user plus application."""
import asyncio
import json
from types import SimpleNamespace

from core.channel import ChannelSession


class FakeBackend:
    """stream() yields one tick naming the user it ran as, then waits (a live view)."""

    def __init__(self):
        self.opened = []

    async def stream(self, kind, id_, me, opened=None):
        self.opened.append((kind, id_, me.user))
        yield "frame", json.dumps({"as": me.user, "key": f"{kind}/{id_}"})
        await asyncio.sleep(30)

    async def sse(self, path, me, opened=None):
        yield "alert", json.dumps({"as": me.user, "path": path})
        await asyncio.sleep(30)


async def never():
    return False


async def read(session, asked, count):
    out = []
    agen = session.events(asked, never)
    try:
        async for chunk in agen:
            out.append(chunk)
            if len(out) >= count:
                break
    finally:
        await agen.aclose()
    return out


def frames(chunks):
    got = {}
    for c in chunks[1:]:
        data = c.split("\n")[1]
        body = json.loads(data[6:])
        got[body["ch"]] = body["d"]
    return got


def make(backend, user, **kw):
    return ChannelSession(backend, SimpleNamespace(user=user), lambda event, data: data, limit=kw.pop("limit", 4), **kw)


def test_each_user_gets_frames_built_for_themselves():
    async def go():
        backend, registry = FakeBackend(), {}
        a, b = make(backend, "alice", registry=registry), make(backend, "bob", registry=registry)
        ra, rb = await asyncio.gather(read(a, ["view:trade/T-1", "alerts"], 3), read(b, ["view:trade/T-1"], 2))
        return ra, rb, backend
    ra, rb, backend = asyncio.run(go())
    assert json.loads(ra[0].split("data: ")[1])["d"]["subs"] == ["view:trade/T-1", "alerts"]
    assert {k: v["as"] for k, v in frames(ra).items()} == {"view:trade/T-1": "alice", "alerts": "alice"}
    assert frames(rb) == {"view:trade/T-1": {"as": "bob", "key": "trade/T-1"}}
    assert sorted(backend.opened) == [("trade", "T-1", "alice"), ("trade", "T-1", "bob")]


def test_the_owner_is_the_user_and_the_application():
    s = make(FakeBackend(), "alice", app="crm")
    assert s.owned_by("alice", "crm") and not s.owned_by("alice") and not s.owned_by("bob", "crm") and not s.owned_by("alice", "other")
    assert make(FakeBackend(), "alice").owned_by("alice")


def test_the_subscription_limit_is_told_not_silent_and_a_closed_channel_leaves_the_registry():
    async def go():
        registry = {}
        s = make(FakeBackend(), "alice", limit=1, registry=registry)
        chunks = await read(s, ["view:trade/T-1", "view:trade/T-2"], 3)
        await asyncio.sleep(0.8)
        return chunks, registry, s
    chunks, registry, s = asyncio.run(go())
    got = frames(chunks)
    assert got["view:trade/T-2"]["code"] == "DRS-5003" and "live.max_subscriptions" in got["view:trade/T-2"]["detail"]
    assert s.ended and registry == {}


def test_change_adds_and_removes_within_the_limit():
    async def go():
        s = make(FakeBackend(), "alice", limit=2)
        s.change(add=["view:trade/A", "view:trade/B"])
        s.change(remove=["view:trade/A"], add=["view:trade/C"])
        keys = sorted(s.tasks)
        s.end()
        await asyncio.sleep(0.8)
        return keys
    assert asyncio.run(go()) == ["view:trade/B", "view:trade/C"]
