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
"""A change made through one console worker is seen at once by the others (QA3A-02, 03, 04, 06). Four workers are simulated in one
process: four caches of each kind, each with its own ``UserStamps`` over one shared folder, as four processes over the console's data
folder. Before the stamps, three workers in four kept the old packs, layout, pivots, session check or theme for up to a minute."""
import asyncio
import time
from pathlib import Path
from types import SimpleNamespace

from core.auth import Auth
from core.bi import BiMe
from core.layouts import Layouts
from core.packs import Packs
from core.pivots import Pivots
from core.settings import UserSettings
from core.user_stamps import UserStamps

CONSOLE = Path(__file__).resolve().parent.parent
ME = SimpleNamespace(user="trader1")
OTHER = SimpleNamespace(user="trader2")


def run(coro):
    return asyncio.run(coro)


class Server:
    """What the Drishti server says about a user now."""

    def __init__(self):
        self.theme = "blue"
        self.active = ["core", "trading"]
        self.layout = []
        self.saved_pivots = []
        self.enabled = True
        self.calls = 0

    async def settings(self, ident):
        self.calls += 1
        return {"theme": self.theme}

    async def packs(self, ident=None):
        self.calls += 1
        return [{"name": n, "active": n in self.active, "assigned": True} for n in ("core", "trading", "market-data")]

    async def layouts(self, ident):
        self.calls += 1
        return {"allowed": True, "enabled": True, "layouts": list(self.layout)}

    async def pivots(self, ident):
        self.calls += 1
        return {"enabled": True, "promote": False, "review": True, "pivots": list(self.saved_pivots)}

    async def bi(self, method, path, ident, body=None, wait=None, **params):
        self.calls += 1
        return {"enabled": True, "powers": [], "theme": self.theme} if path == "/me" else {"recent": []}

    async def session(self, session_id, service):
        self.calls += 1
        if not self.enabled:
            from core.backend import BackendError

            raise BackendError(401, "DRS-5010", "session ended")
        return {"user": {"username": "trader1", "roles": ["trader"]}}


def workers(tmp_path, n=4):
    """n workers' stamps over one folder (each its own instance, as each process has)."""
    return [UserStamps(tmp_path / "user-stamps") for _ in range(n)]


class Settings(dict):
    def get(self, key, default=None):
        return super().get(key, default)


def test_a_theme_changed_through_one_worker_shows_on_every_worker_and_in_a_second_browser(tmp_path):
    server = Server()
    caches = [UserSettings(30.0, s) for s in workers(tmp_path)]
    assert [run(c.get(server, ME))["theme"] for c in caches] == ["blue"] * 4         # every worker holds the old theme
    assert run(caches[0].get(server, OTHER))["theme"] == "blue"
    server.theme = "crimson"
    time.sleep(0.01)
    request = SimpleNamespace(state=SimpleNamespace(), cookies={})
    caches[2].forget("trader1", request)                                              # the change, made through worker 3
    # browser B carries no cookie stamp: every worker still reads again (QA3A-06)
    assert [run(c.get(server, ME))["theme"] for c in caches] == ["crimson"] * 4
    calls = server.calls
    assert [run(c.get(server, ME))["theme"] for c in caches] == ["crimson"] * 4 and server.calls == calls   # cached again
    assert request.state.settings_changed_at > 0                                      # the cookie stamp stays as the fast path


def test_the_pack_switcher_follows_a_choice_made_through_another_worker(tmp_path):
    server = Server()
    settings = Settings({"packs.dir": str(CONSOLE.parent / "config" / "packs"), "packs.enabled": "core,trading"})
    caches = [Packs(settings, CONSOLE, s) for s in workers(tmp_path)]
    names = lambda c, who=ME: [p["name"] for p in run(c.current(server, who))]  # noqa: E731
    assert [names(c) for c in caches] == [["core", "trading"]] * 4
    server.active = ["core"]
    time.sleep(0.01)
    caches[1].forget(ME)                                                                # POST /api/packs on worker 2 (QA3A-02)
    assert [names(c) for c in caches] == [["core"]] * 4
    # an administrator switching a pack for everyone: every user's copy on every worker
    assert names(caches[0], OTHER) == ["core"]
    server.active = ["core", "market-data"]
    time.sleep(0.01)
    caches[3].forget_all()
    assert [names(c, OTHER) for c in caches] == [["core", "market-data"]] * 4


def test_a_saved_or_reset_layout_and_pivot_show_on_every_worker(tmp_path):
    server = Server()
    stamps = workers(tmp_path)
    layouts = [Layouts(Settings(), s) for s in stamps]
    pivots = [Pivots(s, 30.0) for s in stamps]
    assert all(run(c.state(server, ME))["layouts"] == [] for c in layouts)
    assert all(run(c.state(server, ME))["pivots"] == [] for c in pivots)
    saved = {"sutra": "endur-comswap", "kind": "trade", "panels": [{"id": "p1", "hidden": True}]}
    server.layout, server.saved_pivots = [saved], [{"scope": "search", "kind": "trade", "rows": ["book"]}]
    time.sleep(0.01)
    layouts[0].forget("trader1")                                                        # PUT /api/layout on worker 1 (QA3A-03)
    assert all(Layouts.find(run(c.state(server, ME)), "endur-comswap", "trade") == saved for c in layouts)
    assert all(Pivots.saved_search(run(c.state(server, ME)), "trade") == {"rows": ["book"]} for c in pivots)   # the same user's stamp
    server.layout = []
    time.sleep(0.01)
    layouts[3].forget("trader1")                                                        # reset on worker 4
    assert all(Layouts.find(run(c.state(server, ME)), "endur-comswap", "trade") is None for c in layouts)


def test_bi_powers_follow_a_change_through_another_worker(tmp_path):
    server = Server()
    caches = [BiMe(30.0, s) for s in workers(tmp_path)]
    assert all(run(c.get(server, ME))["theme"] == "blue" for c in caches)
    server.theme = "green"
    time.sleep(0.01)
    caches[1].forget("trader1")
    assert all(run(c.get(server, ME))["theme"] == "green" for c in caches)


class RoleServer(Server):
    """BI powers come from the role a user holds (carl and dan hold qa-nobi)."""

    def __init__(self):
        super().__init__()
        self.role_powers = []

    async def bi(self, method, path, ident, body=None, wait=None, **params):
        self.calls += 1
        return {"enabled": True, "powers": list(self.role_powers)} if path == "/me" else {"recent": []}


def test_bi_powers_changed_on_a_role_reach_every_holder_on_every_worker_at_once(tmp_path):
    """QA3B-06: Admin, BI settings (or Roles) changes a role's powers through one worker; before, only the administrator's own copy was forgotten,
    so carl's menu was wrong on 26 of 64 pages for up to 6 s. Now every worker re-reads every holder's powers at once, and the other per-user copies
    (settings here) too."""
    server = RoleServer()
    stamps = workers(tmp_path)
    caches = [BiMe(30.0, s) for s in stamps]
    settings = [UserSettings(30.0, s) for s in stamps]
    carl, dan = SimpleNamespace(user="carl"), SimpleNamespace(user="dan")
    for c in caches:
        assert run(c.get(server, carl))["enabled"] is False                         # no bi power: no BI menu, on every worker
        assert run(c.get(server, dan))["enabled"] is False
    assert all(run(s.get(server, carl))["theme"] == "blue" for s in settings)
    server.role_powers, server.theme = ["bi", "bi-author"], "crimson"
    time.sleep(0.01)
    caches[1].forget_all()                                                          # PUT /bi/api-roles/qa-nobi on worker 2 (the admin's)
    for c in caches:
        assert run(c.get(server, carl))["enabled"] is True and "bi-author" in run(c.get(server, carl))["powers"]
        assert run(c.get(server, dan))["enabled"] is True
    assert all(run(s.get(server, carl))["theme"] == "crimson" for s in settings)    # what else the role decides is read again too
    calls = server.calls
    assert all(run(c.get(server, carl))["enabled"] for c in caches) and server.calls == calls    # then cached again
    server.role_powers = []                                                         # revoked
    time.sleep(0.01)
    caches[3].forget_all()
    assert [run(c.get(server, carl))["enabled"] for c in caches] == [False] * 4


def test_a_user_disabled_through_one_worker_is_signed_out_on_every_worker_at_once(tmp_path):
    server = Server()
    settings = Settings({"auth.enabled": True, "auth.session_secret": "s" * 40, "auth.token_secret": "t" * 40, "auth.recheck_seconds": 60})
    auths = [Auth(settings, stamps=s) for s in workers(tmp_path)]
    assert all(run(a._check("sess-1", server))["username"] == "trader1" for a in auths)   # each worker keeps the check for 60 s
    server.enabled = False                                                              # the administrator disables trader1
    time.sleep(0.01)
    auths[0].forget(user="trader1")                                                     # on worker 1 (QA3A-04)
    assert [run(a._check("sess-1", server)) for a in auths] == [None] * 4


def test_a_sign_out_through_one_worker_ends_the_check_on_the_others(tmp_path):
    server = Server()
    settings = Settings({"auth.enabled": True, "auth.session_secret": "s" * 40, "auth.token_secret": "t" * 40, "auth.recheck_seconds": 60})
    auths = [Auth(settings, stamps=s) for s in workers(tmp_path)]
    assert all(run(a._check("sess-1", server)) for a in auths)
    server.enabled = False                                                              # the session ended on the server
    time.sleep(0.01)
    auths[2].forget(user="trader1", session="sess-1")
    assert [run(a._check("sess-1", server)) for a in auths] == [None] * 4


def test_without_a_folder_copies_live_for_their_ttl(tmp_path):
    server = Server()
    a, b = UserSettings(30.0, UserStamps(None)), UserSettings(30.0, UserStamps(None))
    assert run(b.get(server, ME))["theme"] == "blue"
    server.theme = "green"
    a.forget("trader1")
    assert run(b.get(server, ME))["theme"] == "blue"                                    # another console: within its TTL only
    assert not (tmp_path / "user-stamps").exists()


def test_a_stamp_is_one_small_file_per_user_with_a_visible_name(tmp_path):
    s = UserStamps(tmp_path / "user-stamps")
    s.touch("default\x1ftrader1")
    s.touch("default\x1ftrader1")
    files = sorted(p.name for p in (tmp_path / "user-stamps").iterdir())
    assert len(files) == 1 and files[0].endswith(".stamp") and not files[0].startswith(".")
    assert 0 < s.changed_at("default\x1ftrader1") <= time.time()
    assert s.changed_at("default\x1fnobody") == 0.0
