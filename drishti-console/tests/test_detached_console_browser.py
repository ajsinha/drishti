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

"""The detached console (docs/architecture/THIN_CONSOLE_DESIGN.md step S0, section 5.2): ``drishti-console/`` copied with what ships beside it
(Help's documents) into a folder that holds nothing of the server's, started as a real console process in front of a real server (tests/detached_console.py), serves the
sitemap's pages without a fault, learns the installation's answer and the server's version, and reads no file outside its own folders but
the remaining readers each later step deletes (``KNOWN_READERS``, by console file; the list only shrinks: a reader not on it fails; the
test prints every reader it saw, the checklist of the steps). From Drill A it is a permanent test of the drill.

Skipped when Playwright, Chromium, the built server jar or a JDK is missing."""
import json
import urllib.request

import pytest

import detached_console
from page_walk import dedupe, new_page, open_page, report, sitemap_addresses
from test_workbench_browser import browser  # noqa: F401 - the fixture
from wb_live import _stack

#: the console files that still read outside the console's folder, each with the step that ends it (THIN_CONSOLE_DESIGN.md section 5)
#: Help's documents are the console's own (section 4.1): they ship beside it (detached_console.SHIPPED) and are no reader.
KNOWN_READERS = {
    "core/config_editor.py": "S6: the settings documents for Admin -> Configuration",
    "core/lakes.py": "S8: the lake build on the console's machine (a console elsewhere says where it runs and reads nothing)",
    "core/lake_maint.py": "S8: lake maintenance on the console's machine (a console elsewhere says where it runs and reads nothing)",
    "core/external_lib.py": "S6: the external library folder beside the repository (to become an explicit setting)",
    "core/guides.py": "S6: Help's library lists the install folder (help.docs_dir's parent) for the root documents shipped beside docs/",
}


@pytest.fixture(scope="module")
def detached(tmp_path_factory):
    for st in _stack(tmp_path_factory, {"DRISHTI_DEMO_ENABLED": "false", "DRISHTI_OFFLINE": "true"}):
        yield from detached_console.start(tmp_path_factory.mktemp("detached"), st.server)


def _json(url: str):
    with urllib.request.urlopen(url, timeout=30) as r:
        return json.load(r)


def test_the_detached_console_serves_the_sitemap_s_pages_without_a_fault(browser, detached):  # noqa: F811 - the fixture
    pg = new_page(browser)
    try:
        results = [open_page(pg, detached.url, h) for h in dedupe(sitemap_addresses())]
    finally:
        pg.context.close()
    bad, _ = report(results)
    assert not bad, "pages of the detached console with faults:\n  " + "\n  ".join(bad)


def test_the_detached_console_shows_the_server_s_version_and_holds_its_installation_answer(detached):
    page = urllib.request.urlopen(detached.url + "/t", timeout=60).read().decode()
    assert _json(detached.url + "/healthz/backend").get("reachable", True)
    footer = page[page.index('class="site-footer"'):]
    assert "data-server-version>" in footer, "the footer shows the server's version (the console has none of its own to show)"


def test_the_detached_console_reads_nothing_outside_its_folder_but_the_known_readers(detached):
    found: dict[str, list[str]] = {}
    for r in detached.readers():
        found.setdefault(r["where"].split(":")[0], []).append(f"{r['event']} {r['path']} ({r['where']})")
    print("remaining readers (the checklist of the thin console's steps):")
    for f, lines in sorted(found.items()):
        print(f"  {f} [{KNOWN_READERS.get(f, 'NEW')}]: {len(lines)}")
        for line in lines[:5]:
            print("    " + line)
    new = {f: v for f, v in found.items() if f not in KNOWN_READERS}
    assert not new, "console files that read outside the console's folder (ask the server instead):\n  " + "\n  ".join(
        f"{f}: {v[0]}" for f, v in sorted(new.items()))
