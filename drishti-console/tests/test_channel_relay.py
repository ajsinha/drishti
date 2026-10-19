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

"""Live-channel calls across the console's workers (core/channel_relay.py): a channel's id names the worker that holds it, and a
call another worker receives is passed to that worker with the console's relay key. No key (one process): no relay, ids as before."""
import asyncio
import json
from types import SimpleNamespace

from core import channel_relay
from core.channel import ChannelSession
from routes import api_routes


def run(coro):
    return asyncio.run(coro)


def test_one_process_has_no_relay(monkeypatch):
    monkeypatch.delenv(channel_relay.KEY_ENV, raising=False)

    async def go():
        await channel_relay.start(None)
        try:
            cid = ChannelSession(None, SimpleNamespace(user="ann"), lambda e, d: d, limit=4).cid
            return channel_relay.suffix(), cid, await channel_relay.forward(cid + ".1234", "console-change", {})
        finally:
            await channel_relay.stop()

    suffix, cid, got = run(go())
    assert suffix == "" and "." not in cid and got is None


def test_a_call_reaches_the_worker_named_in_the_id_and_a_stranger_is_refused(monkeypatch):
    monkeypatch.setenv(channel_relay.KEY_ENV, "relay-test-key")
    seen = []
    monkeypatch.setitem(channel_relay.HANDLERS, "echo", lambda app, cid, payload: seen.append((app, cid, payload)) or {"ok": True, "n": payload["n"]})

    async def go():
        await channel_relay.start("the-app")
        port = channel_relay._state["port"]
        try:
            cid = ChannelSession(None, SimpleNamespace(user="ann"), lambda e, d: d, limit=4).cid
            own = await channel_relay.forward(cid, "echo", {"n": 1})           # this worker's own channel: nothing to pass on
            channel_relay._state["port"] = 0                                  # now the caller is "another worker"
            other = await channel_relay.forward(cid, "echo", {"n": 2})
            unknown = await channel_relay.forward(cid, "no-such-op", {})
            reader, writer = await asyncio.open_connection("127.0.0.1", port)
            writer.write((json.dumps({"key": "wrong", "op": "echo", "cid": cid, "payload": {"n": 3}}) + "\n").encode())
            await writer.drain()
            stranger = json.loads(await reader.readline())
            writer.close()
            channel_relay._state["port"] = port
            return cid, port, own, other, unknown, stranger
        finally:
            await channel_relay.stop()

    cid, port, own, other, unknown, stranger = run(go())
    assert cid.endswith(f".{port}") and channel_relay.owner_port(cid) == port
    assert own is None and other == {"ok": True, "n": 2} and unknown == {"ok": False} and stranger == {"ok": False}
    assert seen == [("the-app", cid, {"n": 2})]                                  # the stranger's message never reached the handler


def test_a_relayed_change_applies_only_to_its_owners_channel(monkeypatch):
    calls = []
    entry = {"user": "ann", "app": "", "add": lambda s: calls.append(("add", s)), "remove": lambda s: calls.append(("remove", s))}
    monkeypatch.setitem(api_routes.CHANNELS, "c1.4000", entry)
    assert api_routes._relayed_change(None, "c1.4000", {"user": "bob", "add": ["view:trade/1"]}) == {"ok": False}
    assert api_routes._relayed_change(None, "c1.4001", {"user": "ann", "add": ["view:trade/1"]}) == {"ok": False}
    assert api_routes._relayed_change(None, "c1.4000", {"user": "ann", "add": ["view:a/1"], "remove": ["view:b/2"], "limit": 4}) == {"ok": True}
    assert calls == [("remove", "view:b/2"), ("add", "view:a/1")]
