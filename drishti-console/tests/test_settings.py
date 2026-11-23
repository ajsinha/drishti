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


"""W21: personal settings shape every page, are saved through the server, and pins and landing pages work."""


def _settings(backend, monkeypatch, **over):
    s = {"theme": "crimson", "landing": "/w/Rates", "clockZone": "Europe/London", "density": "compact", "flash": False, "searchLimit": 25,
         "pinned": [{"kind": "trade", "id": "IRS-48213"}]}
    s.update(over)
    patched = []

    async def settings(ident):
        return dict(s)

    async def patch_settings(changes, ident):
        patched.append(changes)
        s.update(changes)
        return dict(s)
    monkeypatch.setattr(backend, "settings", settings, raising=False)
    monkeypatch.setattr(backend, "patch_settings", patch_settings, raising=False)
    return patched


def test_settings_shape_every_page(client, backend, monkeypatch):
    _settings(backend, monkeypatch)
    client.app.state.user_settings.forget("ash")
    page = client.get("/v/trade/IRS-48213").text
    assert 'data-theme="crimson"' in page and 'data-user-theme="crimson"' in page
    assert "density-compact" in page and "no-flash" in page
    assert 'data-tz="Europe/London"' in page and 'data-tz-label="London"' in page
    assert "Pinned" in page and "pin-on" in page
    home = client.get("/t").text
    assert "bi-pin-angle" in home and 'href="/v/trade/IRS-48213"' in home
    client.app.state.user_settings.forget("ash")


def test_saving_settings_pinning_and_the_search_default(client, backend, monkeypatch):
    patched = _settings(backend, monkeypatch, pinned=[])
    client.app.state.user_settings.forget("ash")
    r = client.post("/account/settings", data={"theme": "light", "clockZone": "Asia/Tokyo", "density": "comfortable", "flash": "on",
                                                "landing": "/t", "searchLimit": "40"}, follow_redirects=False)
    assert r.status_code == 303 and r.headers["location"] == "/account?saved=1#settings"
    assert patched[-1] == {"theme": "light", "clockZone": "Asia/Tokyo", "locale": None, "density": "comfortable", "flash": True, "landing": "/t", "searchLimit": 40}
    r = client.post("/pin/trade/IRS-48213", follow_redirects=False)
    assert r.headers["location"] == "/v/trade/IRS-48213" and patched[-1] == {"pinned": [{"kind": "trade", "id": "IRS-48213"}]}
    client.post("/pin/trade/IRS-48213")
    assert patched[-1] == {"pinned": []}                                           # a second press unpins
    asked = {}

    async def search(q, ident=None):
        asked["q"] = q
        return {"kind": "trade", "columns": [], "labels": {}, "rows": [], "scanned": 0, "matched": 0}
    monkeypatch.setattr(backend, "search", search, raising=False)
    client.app.state.user_settings.forget("ash")
    client.get("/s", params={"q": "TRD where mtm > 0"})
    assert asked["q"] == "TRD where mtm > 0 limit 40"
    client.get("/s", params={"q": "TRD where mtm > 0 limit 5"})
    assert asked["q"] == "TRD where mtm > 0 limit 5"
    assert "Save settings" in client.get("/account").text
    client.app.state.user_settings.forget("ash")


def test_the_theme_menu_saves_to_the_account(client, backend, monkeypatch):
    patched = _settings(backend, monkeypatch)
    r = client.patch("/api/settings", json={"theme": "blue"})
    assert r.status_code == 200 and patched[-1] == {"theme": "blue"}
    assert "data-signed-in" in client.get("/static/js/drishti_app.js").text
    client.app.state.user_settings.forget("ash")


def test_the_settings_option_reads_the_file_below_the_environment_and_the_command_line(tmp_path, monkeypatch):
    """--settings /abs/drishti.ini: same format as the server's; only console.* keys are the console's; the file beats the defaults, the
    command line beats the file, a later file beats an earlier one; relative refused."""
    from pathlib import Path
    from core.config import load_settings
    ini = tmp_path / "drishti.ini"
    ini.write_text("# c\n[server]\nserver.port=18480\n[console.server]\nport=17999\n[roots]\nconsole.backend.url=http://file.example:1\n"
                   "console.ui.title=one\n", encoding="utf-8")
    second = tmp_path / "second.ini"
    second.write_text("[console.ui]\ntitle=two\n", encoding="utf-8")
    cfg_dir = Path(__file__).resolve().parents[1] / "config"
    s = load_settings(cfg_dir, ["--settings", f"{ini},{second}", "--backend.url=http://cli.example:2"])
    assert s.get("server.port") == 17999 and s.get("backend.url") == "http://cli.example:2" and s.get("ui.title") == "two"
    import pytest
    with pytest.raises(SystemExit, match="absolute"):
        load_settings(cfg_dir, ["--settings", "drishti.ini"])
    with pytest.raises(SystemExit, match="not a file"):
        load_settings(cfg_dir, ["--settings=" + str(tmp_path / "nope.ini")])


def test_the_settings_file_beats_a_stale_environment_for_the_roots(tmp_path, monkeypatch, capsys):
    """The Java order (LaunchRoots), as the tools: the file's drishti.home / drishti.data.dir beat an old setx DRISHTI_HOME /
    DRISHTI_DATA_DIR naming another folder (a WARNING says so); the distribution still fills only where unset."""
    import os
    from pathlib import Path
    from core.config import load_settings
    home = Path(__file__).resolve().parents[2]
    ini = tmp_path / "dev.ini"
    ini.write_text(f"drishti.home={home.as_posix()}\ndrishti.data.dir=${{drishti.home}}/data\ndrishti.distribution=finance\n", encoding="utf-8")
    monkeypatch.setenv("DRISHTI_HOME", str(tmp_path / "drishti-orig"))
    monkeypatch.setenv("DRISHTI_DATA_DIR", str(tmp_path / "drishti-orig" / "data"))
    monkeypatch.setenv("DRISHTI_DISTRIBUTION", "risk")
    load_settings(Path(__file__).resolve().parents[1] / "config", ["--settings", str(ini)])
    assert os.environ["DRISHTI_HOME"] == home.as_posix() and os.environ["DRISHTI_DATA_DIR"] == f"{home.as_posix()}/data"
    assert os.environ["DRISHTI_DISTRIBUTION"] == "risk"
    err = capsys.readouterr().err
    assert "DRISHTI_HOME=" in err and "DRISHTI_DATA_DIR=" in err and "is ignored: your settings file wins" in err
    assert not (tmp_path / "drishti-orig").exists()
