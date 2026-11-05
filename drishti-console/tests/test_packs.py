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
    assert "/help/pack-developer-guide" in client.get("/help").text and client.get("/help/pack-developer-guide").status_code == 200
    assert client.get("/help/packs", follow_redirects=False).headers["location"] == "/help/pack-developer-guide#part-v-domain-packs-the-packs-that-ship-turning-them-on-who-sees-which"
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


def test_a_pack_installed_by_the_server_brings_its_guide_examples_and_workspaces(tmp_path):
    """Packs installed from the registry or by Deploy archive land in the server's installed-dir; the console reads it first, as the
    server does, so the help centre finds the pack's guide (the screenshot run: 'There is no guide called helpdesk-pack', 2026-10-27)."""
    import shutil
    from pathlib import Path
    from core.packs import Packs
    console = Path(__file__).resolve().parent.parent
    example = console.parent / "docs" / "guides" / "examples" / "pack" / "helpdesk"
    installed = tmp_path / "packs" / "installed"
    settings = {"packs.dir": str(console.parent / "config" / "packs"), "packs.installed_dir": str(installed)}
    packs = Packs(settings, console)
    assert packs._load("helpdesk") is None                          # nothing installed yet: the folder need not exist
    shutil.copytree(example, installed / "helpdesk")
    p = packs._load("helpdesk")
    assert p is not None and p["dir"] == (installed / "helpdesk").resolve()
    assert [g["slug"] for g in p["guides"]] == ["helpdesk-pack"] and p["guides"][0]["path"].exists()
    assert p["contextual"] == {"terminal": "helpdesk-pack"} and p["examples"]
    default = Packs({"packs.dir": settings["packs.dir"], "lakes.data_dir": str(tmp_path)}, console)
    assert default.installed == installed.resolve() and default._load("helpdesk")["dir"] == (installed / "helpdesk").resolve()


def test_a_pack_s_files_are_parsed_once_while_unchanged_and_again_when_one_changes(tmp_path):
    """The console parsed every enabled pack's YAML on each page whose per-user cache was cold (about a second of its event loop):
    it keeps what it made of a pack while its pack.yaml and the console files it names are unchanged, and reads them again when one
    changes. Each caller gets its own copy."""
    import os
    import shutil
    from pathlib import Path
    from unittest import mock
    from core import packs as P
    console = Path(__file__).resolve().parent.parent
    shutil.copytree(console.parent / "docs" / "guides" / "examples" / "pack" / "helpdesk", tmp_path / "packs" / "helpdesk")
    packs = P.Packs({"packs.dir": str(tmp_path / "packs"), "lakes.data_dir": str(tmp_path)}, console)
    with mock.patch.object(P.yaml, "safe_load", wraps=P.yaml.safe_load) as parse:
        first = packs._load("helpdesk")
        n = parse.call_count
        first["examples"].append("changed by a caller")
        second = packs._load("helpdesk")
        assert parse.call_count == n, "unchanged files are not parsed again"
        assert "changed by a caller" not in second["examples"], "each caller its own copy"
        help_file = next((tmp_path / "packs" / "helpdesk").rglob("help.yaml"))
        st = help_file.stat()
        help_file.write_text(help_file.read_text() + "\n# touched\n")
        os.utime(help_file, ns=(st.st_atime_ns, st.st_mtime_ns + 10**9))
        packs._load("helpdesk")
        assert parse.call_count > n, "a changed help file reads the pack again"
