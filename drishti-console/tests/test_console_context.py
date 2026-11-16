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

"""No reference crosses between sessions (core/request_context.py; ADR-016 › As built).

A live channel, a relayed call and a pack job act for the request that started them: its server above all (a fresh context named
the console's FIRST server, so a person signed in on another server had their alerts and views opened there, under their token),
and its session's pin, lens, share and business date. Ownership is (server, user). A console channel re-checks its session and
ends at sign-out.
"""
import asyncio
import contextvars
import json
import re
import sys
from pathlib import Path
from types import SimpleNamespace

import pytest

CONSOLE = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(CONSOLE))

from core import asof, cluster, collab, edition, servers  # noqa: E402
from core.channel import ChannelSession  # noqa: E402
from core.servers import Switch  # noqa: E402
from routes import api_routes  # noqa: E402
from tests.test_servers import FORM, LOGIN, two  # noqa: E402,F401  (the two-server fixture)


class Rec:
    """One server's stand-in: records every stream it opens with the scope it was opened in, yields one event, then waits (live)."""

    def __init__(self, name):
        self.name, self.opened = name, []

    def _seen(self, what, me):
        self.opened.append({"what": what, "user": getattr(me, "user", None), "server": servers.current(), "lens": edition.current_lens(),
                            "share": collab._share.get(), "session": cluster.SESSION.get(), "asof": asof.current(), "known": asof.known_at()})

    async def sse(self, path, me, opened=None, **kw):
        self._seen(path, me)
        yield "alert", json.dumps({"from": self.name})
        await asyncio.sleep(30)

    async def stream(self, kind, id_, me, opened=None, **kw):
        self._seen(f"{kind}/{id_}", me)
        yield "frame", json.dumps({"from": self.name})
        await asyncio.sleep(30)


def _as_desk():
    """The request scope of a person signed in on server "desk": the middleware's values."""
    servers.CURRENT.set("desk")
    cluster.SESSION.set("pin-of-desk-session")
    edition.set_lens("var")
    collab.set_share("sh_share0001")
    asof.set_current("2026-10-30")
    asof.set_known("2026-10-30T12:00:00Z")


def _registry(app):
    return api_routes.channels(app) if hasattr(api_routes, "channels") else api_routes.CHANNELS


async def _stop(session):
    session.end()
    await asyncio.sleep(0.7)


def test_a_channel_reads_the_server_its_person_signed_in_on_with_the_whole_scope():
    fakes = {"open": Rec("open"), "desk": Rec("desk")}

    async def go():
        _as_desk()
        s = ChannelSession(Switch(fakes, "open"), SimpleNamespace(user="ann"), lambda e, d: d, limit=4)
        s.add("alerts")
        s.add("view:trade/T-1")
        await asyncio.sleep(0.2)
        await _stop(s)

    contextvars.Context().run(asyncio.run, go())
    assert fakes["open"].opened == []                                   # the first server never saw desk's person
    assert sorted(o["what"] for o in fakes["desk"].opened) == ["/alerts/stream", "trade/T-1"]
    for o in fakes["desk"].opened:
        assert o == {**o, "user": "ann", "server": "desk", "lens": "var", "share": "sh_share0001", "session": "pin-of-desk-session",
                     "asof": "2026-10-30", "known": "2026-10-30T12:00:00Z"}


def test_a_subscription_added_from_another_context_still_acts_for_the_channel():
    """A relayed change runs in the relay listener's context (the lifespan's: the default server): what it adds acts for the channel."""
    fakes = {"open": Rec("open"), "desk": Rec("desk")}

    async def go():
        made = {}

        def open_it():
            _as_desk()
            made["s"] = ChannelSession(Switch(fakes, "open"), SimpleNamespace(user="ann"), lambda e, d: d, limit=4)
        contextvars.Context().run(open_it)                              # the request that opened it
        made["s"].add("alerts")                                         # another context: CURRENT is the default here
        await asyncio.sleep(0.2)
        await _stop(made["s"])

    contextvars.Context().run(asyncio.run, go())
    assert fakes["open"].opened == [] and [o["server"] for o in fakes["desk"].opened] == ["desk"]


def test_a_change_relayed_from_another_worker_reaches_the_channel_server_and_checks_the_owner_on_it(monkeypatch):
    from core import channel_relay

    monkeypatch.setenv(channel_relay.KEY_ENV, "relay-test-key")
    fakes = {"open": Rec("open"), "desk": Rec("desk")}
    holder = SimpleNamespace(state=SimpleNamespace(servers=None))     # the worker that holds the channel
    caller = SimpleNamespace(state=SimpleNamespace(servers=None))     # the worker the browser's POST reached

    async def go():
        await channel_relay.start(holder)
        try:
            made = {}

            def open_it():
                _as_desk()
                made["s"] = ChannelSession(Switch(fakes, "open"), SimpleNamespace(user="ann"), lambda e, d: d, limit=4,
                                           registry=api_routes.channels(holder), cid_suffix=channel_relay.suffix(holder))
                api_routes.channels(holder)[made["s"].cid] = {"server": "desk", "user": "ann", "app": "", "add": made["s"].add,
                                                              "remove": made["s"].remove, "session": made["s"]}
            contextvars.Context().run(open_it)
            cid = made["s"].cid

            async def ask(server, user):
                servers.CURRENT.set(server)
                return await channel_relay.forward(caller, cid, "console-change", {"user": user, "add": ["alerts"], "limit": 4})
            other_server = await asyncio.create_task(ask("open", "ann"), context=contextvars.Context())
            other_user = await asyncio.create_task(ask("desk", "bob"), context=contextvars.Context())
            owner = await asyncio.create_task(ask("desk", "ann"), context=contextvars.Context())
            await asyncio.sleep(0.2)
            await _stop(made["s"])
            return other_server, other_user, owner
        finally:
            await channel_relay.stop(holder)

    other_server, other_user, owner = asyncio.run(go())
    assert other_server == {"ok": False} and other_user == {"ok": False} and owner == {"ok": True}
    assert fakes["open"].opened == [] and [o["server"] for o in fakes["desk"].opened] == ["desk"]


def test_a_relayed_call_names_a_known_server_or_is_refused():
    from core import request_context

    catalogue = servers.Servers(_Settings({"servers": [{"id": "open", "url": "http://o"}, {"id": "desk", "url": "http://d"}]}))
    app = SimpleNamespace(state=SimpleNamespace(servers=catalogue))
    snap = request_context.from_wire({"server": "desk", "session": "p", "lens": "var", "share": "sh_share0001", "asof": "2026-10-30"}, app)
    assert snap[servers.CURRENT] == "desk" and snap[edition._lens] == "var" and snap[asof._current] == "2026-10-30"
    for bad in ({}, {"server": ""}, {"server": "elsewhere"}):
        with pytest.raises(ValueError):
            request_context.from_wire(bad, app)


class _Settings(dict):
    def get(self, k, d=None):
        return super().get(k, d)


def test_every_request_scoped_context_variable_is_carried():
    """A ContextVar added to core/ and not listed in core/request_context.VARS would be lost again in every detached task."""
    from core import request_context

    found = set()
    for f in (CONSOLE / "core").rglob("*.py"):
        for m in re.finditer(r"^(\w+)\s*:\s*[\w.]*ContextVar\[", f.read_text(), re.M):
            found.add(f"{f.stem}.{m.group(1)}")
    listed = {f"{v.name}" for v in request_context.VARS}
    names = {"servers.CURRENT": "drishti_server", "cluster.SESSION": "drishti_cluster_session", "edition._lens": "drishti_lens",
             "collab._share": "drishti_share", "asof._current": "drishti_asof", "asof._known": "drishti_knownat",
             "engines._current": "drishti_console_engines",
             # the split's module state (core/modules.py): the app's modules, and the person's answer, set per request (never shared)
             "modules._current": "drishti_console_modules", "modules._person": "drishti_console_modules_person"}
    assert found == set(names), found                                   # a new one: add it to VARS (and to the wire if it must cross workers)
    assert listed == set(names.values())


def test_a_console_channel_ends_at_sign_out_and_only_that_session(two):  # noqa: F811
    c, app, fakes = two
    c.get("/connect/desk")
    c.post("/login", content=LOGIN, headers=FORM, follow_redirects=False)
    me = app.state.auth._by_server["desk"].identity(c.cookies.get("drishti_session_desk"))
    assert me is not None and me.session

    async def go():
        made = {}

        def open_it(server, who):
            servers.CURRENT.set(server)
            s = ChannelSession(app.state.backend, who, lambda e, d: d, limit=4, registry=_registry(app))
            _registry(app)[s.cid] = {"server": server, "user": who.user, "app": "", "add": s.add, "remove": s.remove, "session": s}
            made[server] = s
        contextvars.Context().run(open_it, "desk", me)
        contextvars.Context().run(open_it, "open", SimpleNamespace(user=me.user, session="another-session"))
        return made

    made = asyncio.run(go())
    c.post("/logout")
    assert made["desk"].ended and not made["open"].ended                # the same name on the other server keeps its channel


def test_a_console_channel_ends_when_its_session_recheck_fails(two):  # noqa: F811
    c, app, fakes = two
    c.get("/connect/desk")
    c.post("/login", content=LOGIN, headers=FORM, follow_redirects=False)
    cookie = c.cookies.get("drishti_session_desk")
    desk_auth = app.state.auth._by_server["desk"]
    desk_auth.recheck = 0.0

    async def go():
        servers.CURRENT.set("desk")
        me = desk_auth.identity(cookie)
        s = ChannelSession(app.state.backend, me, lambda e, d: d, limit=4)
        request = SimpleNamespace(app=app, cookies={"drishti_session_desk": cookie})
        guard = api_routes._guard(request, s)
        guard.start()
        await asyncio.sleep(1.3)
        alive_while_valid = not s.ended
        fakes["desk"].sessions.clear()                                  # signed out elsewhere (another console, an administrator)
        await asyncio.sleep(1.6)
        guard.stop()
        out = []
        while not s.queue.empty():
            out.append(s.queue.get_nowait())
        return alive_while_valid, s.ended, out

    alive, ended, out = contextvars.Context().run(asyncio.run, go())
    assert alive and ended and any(e == "gone" and "DRS-5010" in d for _, e, d in out)


def test_ai_limits_count_per_server_and_user():
    from core.ai.policy import DefaultPolicy, PolicySettings
    from core.ai.types import CallContext, ChatRequest, LlmError, ModelInfo

    p = DefaultPolicy(PolicySettings(per_user_per_day=1))
    model = ModelInfo("m", "p", "m")
    p.before(CallContext("ann", frozenset(), "chat", None, "open"), model, ChatRequest.of("x"))
    p.before(CallContext("ann", frozenset(), "chat", None, "desk"), model, ChatRequest.of("x"))     # another person: their own limit
    with pytest.raises(LlmError):
        p.before(CallContext("ann", frozenset(), "chat", None, "desk"), model, ChatRequest.of("x"))
