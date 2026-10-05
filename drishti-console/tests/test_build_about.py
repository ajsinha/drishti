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

"""The workbench's About tab (CONTEXT_HELP.md step 7), console side: the page carries the tab, its pane and its script; the routes
under /build/designs/{id}/about pass the text, the sample and the revision to the server and its answers back (a stale revision is the
server's 409); the Problems pane lists the lint it is given. The server's own rules are tested in DesignAboutApiTest."""
import re

import pytest

from conftest import CONSOLE
from test_build_designs import _doc, _files, _new, app_client  # noqa: F401 - the fixture

JS = CONSOLE / "web" / "static" / "js" / "build"


@pytest.fixture(autouse=True)
def _fresh(backend):
    backend.about_calls = []


def _design(c):
    d = _new(c, "About", kind="trade", sutra="rachana: 1\nsutra: about\nversion: 1\n")
    c.post(f"/build/designs/{d['id']}/files", json=_files(("a.json", _doc(1))))
    return d["id"]


def test_the_page_has_the_about_tab_with_its_editor_card_and_script(app_client):
    id_ = _design(app_client)
    html = app_client.get(f"/build/d/{id_}").text
    assert 'role="tab" id="wbTabAbout" data-tab="about"' in html and 'id="wbPaneAbout"' in html
    for hook in ("data-about-src", "data-about-card", "data-about-lint", "data-about-starter", "data-about-status"):
        assert hook in html
    assert re.search(r'/static/js/build/about\.js\?v=', html) and html.index("build/problems.js") < html.index("build/about.js")
    assert "#the-about-tab" in html                      # the link to the guide section


def test_the_saved_text_reaches_the_page_as_the_editor_start_value(app_client):
    id_ = _design(app_client)
    app_client.put(f"/build/designs/{id_}/about", json={"baseRev": 1, "text": "about: 1\n"})
    html = app_client.get(f"/build/d/{id_}").text
    assert '"about": "about: 1\\n"' in html


def test_preview_sends_the_text_and_sample_and_saves_nothing(app_client, backend):
    id_ = _design(app_client)
    r = app_client.post(f"/build/designs/{id_}/about/preview", json={"text": "about: 1\nkinds: {}\n", "sample": "a.json", "stray": 1})
    assert r.status_code == 200 and r.json()["card"]["about"]["text"].startswith("card of")
    assert backend.about_calls[-1][:3] == ("POST", "about/preview", {"text": "about: 1\nkinds: {}\n", "sample": "a.json"})
    assert app_client.get(f"/build/designs/{id_}").json().get("about", "") == ""        # not kept
    assert r.json()["lint"][0]["code"] == "DRS-2047"


def test_save_is_a_new_revision_and_a_stale_revision_is_the_servers_409(app_client):
    id_ = _design(app_client)
    rev = app_client.get(f"/build/designs/{id_}").json()["rev"]
    ok = app_client.put(f"/build/designs/{id_}/about", json={"baseRev": rev, "text": "about: 1\n"})
    assert ok.status_code == 200 and ok.json()["rev"] == rev + 1 and ok.json()["about"] == "about: 1\n"
    stale = app_client.put(f"/build/designs/{id_}/about", json={"baseRev": rev, "text": "about: 1\n# again\n"})
    assert stale.status_code == 409 and stale.json()["code"] == "DRS-5007"
    assert app_client.get(f"/build/designs/{id_}").json()["about"] == "about: 1\n"


def test_the_sample_is_a_query_parameter_on_read(app_client, backend):
    id_ = _design(app_client)
    assert app_client.get(f"/build/designs/{id_}/about?sample=a.json").status_code == 200
    assert backend.about_calls[-1][0] == "GET" and backend.about_calls[-1][3] == {"sample": "a.json"}


def test_another_users_design_is_not_found(app_client):
    assert app_client.get("/build/designs/nosuchdesign/about").status_code in (404, 500)


def test_the_scripts_wire_the_tab_into_the_store_and_the_problems_pane():
    about, ops, problems, wb = [(JS / f).read_text() for f in ("about.js", "ops.js", "problems.js", "workbench.js")]
    assert "/about/preview" in about and "'PUT'" in about and "store.exclusive" in about
    assert "adoptAbout" in ops and "about: st.about" in ops and "exclusive" in ops
    assert "'aboutlint'" in about and "'aboutlint'" in problems and "gotoAbout" in problems and "gotoAbout" in wb
    assert "about.flush()" in wb and "about.pending()" in wb                  # an edit waiting for its pause is sent before the page is left
    assert len(about.splitlines()) < 300
    assert "Tab: false" in about                                              # the editor does not trap the keyboard
