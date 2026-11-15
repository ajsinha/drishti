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

"""Domain packs in the console: examples, workspace starters, help guides and About follow the enabled packs."""


def test_examples_starters_and_guides_come_from_packs(client, backend):
    home = client.get("/t").text
    assert "TRD IRS-48213" in home and "SHP SHP-10042" in home and "Logistics · ocean freight" in home
    starters = client.get("/w").text
    assert "Credit desk" in starters and "Shipment tracker" in starters
    assert client.get("/help/getting-started").status_code == 200
    assert client.get("/help/logistics-pack").status_code == 200
    # the packs page is Part V of the pack developer guide (2.9.7): listed, and its old address opens that part
    assert "/help/build-guide" in client.get("/help").text and client.get("/help/build-guide").status_code == 200
    assert client.get("/help/packs", follow_redirects=False).headers["location"] == "/help/build-guide#31-the-packs-that-ship-turning-them-on-who-sees-which"
    assert "Finance · capital markets" in client.get("/about").text


def test_without_packs_the_console_stays_neutral(client, backend):
    saved = backend.enabled_packs
    try:
        backend.enabled_packs = []
        client.app.state.packs._cache = {}
        home = client.get("/t").text
        assert "No domain pack is enabled" in home and "TRD IRS-48213" not in home
        assert "Credit desk" not in client.get("/w").text
        assert client.get("/help/getting-started").status_code == 404
        assert client.get("/help/context/terminal", follow_redirects=False).headers["location"] == "/help/user-guide"
    finally:
        backend.enabled_packs = saved
        client.app.state.packs._cache = {}


def test_pack_switcher_chooses_active_packs(client, backend):
    saved = backend.enabled_packs
    try:
        client.app.state.packs._cache = {}
        home = client.get("/t").text
        assert "data-packs" in home and 'value="logistics" checked' in home
        assert client.post("/api/packs", json={"active": ["logistics"]}).json()["active"] == ["logistics"]
        home = client.get("/t").text
        assert "SHP SHP-10042" in home and "TRD IRS-48213" not in home
        assert client.post("/api/packs", json={"active": []}).status_code == 403
    finally:
        backend.enabled_packs = saved
        client.app.state.packs._cache = {}



# ---------------------------------------------------------------------------------------------------------------------- the thin console
# The console asks the server for a person's packs and a pack's contributions; it reads no pack folder (THIN_CONSOLE_DESIGN.md 3.3, 3.4).

class _Server:
    """A stand-in server that counts what the console asks: the person's answer and each pack's console answer and files."""

    def __init__(self, pack_dir):
        import pack_answers
        self.answers = pack_answers
        self.dir = pack_dir
        self.rows = [{"name": "helpdesk", "title": "Helpdesk", "active": True, "assigned": True, "dir": str(pack_dir)}]
        self.asked = {"me": 0, "pack": 0, "file": 0}
        self.away = False

    async def packs(self, ident=None):
        return self.rows

    async def me_console(self, ident, etag=None):
        self.asked["me"] += 1
        if self.away:
            from core.backend import BackendError
            raise BackendError(503, "DRS-5003", "backend unreachable")
        doc = self.answers.me_console(self.rows)
        return (304, None) if etag == doc["stamp"] else (200, doc)

    async def pack_console(self, name, ident, etag=None):
        self.asked["pack"] += 1
        doc = self.answers.pack_console(name, self.dir)[0]
        return (304, None) if etag == doc["digest"] else (200, doc)

    async def pack_console_file(self, name, path, ident):
        self.asked["file"] += 1
        return self.answers.pack_console(name, self.dir)[1][path].read_bytes()


class _Me:
    user = "ana"


def _run(coro):
    import asyncio
    return asyncio.run(coro)


def test_a_pack_s_guides_examples_and_workspaces_come_from_the_server_s_answers_not_a_folder(tmp_path):
    """A pack the console has never seen on its disk (installed on the server only, the screenshot run's 'There is no guide called
    helpdesk-pack', 2026-10-27) brings its guide, examples, workspace starters and contextual help through the server's answers; the guide's
    file is kept in the console's own folder, under its digest."""
    import shutil
    from pathlib import Path
    from core.packs import Packs
    console = Path(__file__).resolve().parent.parent
    server_side = tmp_path / "server" / "helpdesk"
    shutil.copytree(console.parent / "docs" / "guides" / "examples" / "pack" / "helpdesk", server_side)
    server = _Server(server_side)
    packs = Packs({"ui.user_cache_seconds": 10}, files_dir=tmp_path / "console-files")
    [p] = _run(packs.current(server, _Me()))
    assert [g["slug"] for g in p["guides"]] == ["helpdesk-pack"]
    guide = p["guides"][0]["path"]
    assert guide.is_file() and guide.is_relative_to(tmp_path / "console-files"), "the guide is the console's copy, not the server's file"
    assert guide.read_bytes() == (server_side / p["guides"][0]["file"]).read_bytes()
    assert p["contextual"] == {"terminal": "helpdesk-pack"} and p["examples"] and "dir" not in p
    assert p["workspaces"], "the workspace starters, from the pack's answer"


def test_a_pack_s_answer_is_asked_once_per_digest_and_its_files_once_per_content(tmp_path):
    """The person's answer is revalidated (304) after the cache window; the pack's console answer and its files are asked again only when
    their digests change. Each caller gets its own copy."""
    import shutil
    from pathlib import Path
    from core.packs import Packs
    console = Path(__file__).resolve().parent.parent
    server_side = tmp_path / "server" / "helpdesk"
    shutil.copytree(console.parent / "docs" / "guides" / "examples" / "pack" / "helpdesk", server_side)
    server = _Server(server_side)
    packs = Packs({"ui.user_cache_seconds": 0}, files_dir=tmp_path / "console-files")      # no window: every call asks the server
    first = _run(packs.current(server, _Me()))
    asked = dict(server.asked)
    first[0]["examples"].append("changed by a caller")
    second = _run(packs.current(server, _Me()))
    assert server.asked["me"] == asked["me"] + 1, "the person's answer is asked again (a 304)"
    assert server.asked["pack"] == asked["pack"] and server.asked["file"] == asked["file"], "an unchanged pack is not asked again"
    assert "changed by a caller" not in second[0]["examples"], "each caller its own copy"
    help_file = next(server_side.rglob("help.yaml"))
    help_file.write_text(help_file.read_text() + "\n# touched\n")                         # the pack changes on the server
    _run(packs.current(server, _Me()))
    assert server.asked["pack"] == asked["pack"] + 1, "a changed pack (its digest) is asked again"
    assert server.asked["file"] == asked["file"], "a guide whose content did not change is not fetched again"


def test_while_the_server_is_away_the_last_answer_is_kept_for_a_while_and_never_a_folder(tmp_path):
    """Away is said, never guessed (THIN_CONSOLE_DESIGN.md 3, rule 5): the person's last answer is kept for six cache windows; with none,
    the console shows no pack (the old fallback drew every pack its own resolver found)."""
    import shutil
    from pathlib import Path
    from core.packs import Packs
    console = Path(__file__).resolve().parent.parent
    server_side = tmp_path / "server" / "helpdesk"
    shutil.copytree(console.parent / "docs" / "guides" / "examples" / "pack" / "helpdesk", server_side)
    server = _Server(server_side)
    packs = Packs({"ui.user_cache_seconds": 10}, files_dir=tmp_path / "console-files")
    assert [p["name"] for p in _run(packs.current(server, _Me()))] == ["helpdesk"]
    server.away = True
    packs.stamps = type("Changed", (), {"fresh": staticmethod(lambda key, at: False), "touch": staticmethod(lambda key: 0)})()
    assert [p["name"] for p in _run(packs.current(server, _Me()))] == ["helpdesk"], "the last answer, while the server is away"
    fresh = Packs({"ui.user_cache_seconds": 10}, files_dir=tmp_path / "other-files")
    assert _run(fresh.current(server, _Me())) == [], "no answer yet and the server away: no pack, never a folder's"
