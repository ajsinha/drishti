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
"""A setting changed through one console worker shows on the next page any other worker serves (QA 2026-10-20: a theme picked
on a netting set came back as the old theme on the SIMM page, served by another of the console's four workers)."""
import asyncio
import time
from types import SimpleNamespace

from core.settings import CHANGED_COOKIE, UserSettings, changed_at


class Backend:
    def __init__(self):
        self.theme, self.calls = "blue", 0

    async def settings(self, ident):
        self.calls += 1
        return {"theme": self.theme}


ME = SimpleNamespace(user="ash")


def test_a_worker_reads_the_settings_again_when_the_browser_says_they_changed():
    backend = Backend()
    worker_a, worker_b = UserSettings(), UserSettings()                 # two worker processes, each with its own cache
    assert asyncio.run(worker_b.get(backend, ME))["theme"] == "blue"   # worker B has the old theme cached
    backend.theme = "green"                                             # the user picks Green through worker A
    time.sleep(0.01)
    request = SimpleNamespace(state=SimpleNamespace(), cookies={})
    worker_a.forget("ash", request)
    stamp = request.state.settings_changed_at
    assert stamp <= time.time()
    # without the stamp worker B would still answer blue for up to its ttl; with it, it reads again
    assert asyncio.run(worker_b.get(backend, ME))["theme"] == "blue"
    browser = SimpleNamespace(cookies={CHANGED_COOKIE: f"{stamp:.6f}"})
    assert asyncio.run(worker_b.get(backend, ME, changed_at(browser)))["theme"] == "green"
    calls = backend.calls
    assert asyncio.run(worker_b.get(backend, ME, changed_at(browser)))["theme"] == "green" and backend.calls == calls   # cached again


def test_a_bad_stamp_is_ignored():
    assert changed_at(SimpleNamespace(cookies={CHANGED_COOKIE: "garbage"})) == 0.0
    assert changed_at(SimpleNamespace(cookies={})) == 0.0


def test_patching_settings_stamps_the_browser(client, backend, monkeypatch):
    async def patch_settings(body, ident=None):
        return {"theme": body.get("theme")}
    monkeypatch.setattr(backend, "patch_settings", patch_settings, raising=False)
    r = client.patch("/api/settings", json={"theme": "green"})
    assert r.status_code < 400, r.text
    assert CHANGED_COOKIE in r.cookies or CHANGED_COOKIE in r.headers.get("set-cookie", "")
