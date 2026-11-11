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

"""Help -> Developer (IMPLEMENTATION_PLAN row 2g): the API references a build wrote, served from their folder and nothing else, and the
contract of BI Python items held to the runner (drishti_compute.items, on the server's compute pool)."""
import json

import pytest

import sys

from conftest import CONSOLE

sys.path.insert(0, str(CONSOLE.parent / "compute/drishti-compute" / "src" / "main" / "python"))
from drishti_compute import items  # noqa: E402  (the BI Python items' contract, on the server's compute pool)


@pytest.fixture()
def refs(client, tmp_path):
    """A references folder with two built versions (tools/api_docs.py's layout), swapped in for the test."""
    for ver in ("2.9.6", "2.10.0"):
        root = tmp_path / ver
        (root / "java" / "drishti-api" / "com").mkdir(parents=True)
        (root / "python").mkdir()
        (root / "java" / "drishti-api" / "index.html").write_text(f"<html>Javadoc {ver}</html>")
        (root / "java" / "drishti-api" / "script.js").write_text("var x = 1;")
        (root / "python" / "drishti_compute.html").write_text(f"<html>pdoc {ver}</html>")
        (root / "index.html").write_text(f"<html>reference {ver}</html>")
        (root / "manifest.json").write_text(json.dumps({
            "version": ver, "built": "2026-10-31T00:00:00Z",
            "java": [{"module": "drishti-api", "title": "Plugin and connector SPI", "summary": "s", "href": "java/drishti-api/index.html"}],
            "python": [{"name": "compute-sdk", "title": "Compute worker SDK", "summary": "s", "modules": ["drishti_compute"], "href": "python/drishti_compute.html"}]}))
    (tmp_path / "building-2.11.0").mkdir()                      # a build in progress is not a version
    (tmp_path / "secret.txt").write_text("no")
    old = client.app.state.api_docs_dir
    client.app.state.api_docs_dir = tmp_path
    yield tmp_path
    client.app.state.api_docs_dir = old


def test_the_developer_page_links_the_newest_reference_and_the_other_apis(client, refs):
    page = client.get("/help/developer")
    assert page.status_code == 200 and 'class="help-article"' in page.text
    assert 'href="/help/reference/2.10.0/java/drishti-api/index.html" data-api-java="drishti-api"' in page.text      # newest first, by number
    assert 'href="/help/reference/2.10.0/python/drishti_compute.html" data-api-python="compute-sdk"' in page.text
    assert 'href="/help/reference/2.9.6/index.html"' in page.text                                                   # the other version built
    assert "/api/docs/ui" in page.text and "drishti-render.d.ts" in page.text and "data-api-missing" not in page.text


def test_the_reference_files_are_served_from_their_folder_only(client, refs):
    r = client.get("/help/reference/2.9.6/java/drishti-api/index.html")
    assert r.status_code == 200 and "Javadoc 2.9.6" in r.text and r.headers["content-type"].startswith("text/html")
    assert client.get("/help/reference/2.9.6/java/drishti-api/script.js").headers["content-type"].startswith("text/javascript")
    assert "Javadoc 2.10.0" in client.get("/help/reference/latest/java/drishti-api/index.html").text
    assert "reference 2.10.0" in client.get("/help/reference/2.10.0/").text                                         # a folder answers its index
    for bad in ("secret.txt", "2.9.6/../secret.txt", "..%2F..%2Fpom.xml", "building-2.11.0/index.html", "2.9.6/nope.html"):
        assert client.get(f"/help/reference/{bad}").status_code == 404, bad


def test_without_a_build_the_page_says_how_to_make_one(client, tmp_path):
    old = client.app.state.api_docs_dir
    client.app.state.api_docs_dir = tmp_path / "none"
    try:
        page = client.get("/help/developer").text
        assert "data-api-missing" in page and "tools/api_docs.py build" in page and "data-api-java" not in page
        r = client.get("/help/reference/latest/index.html")
        assert r.status_code == 404 and "tools/api_docs.py build" in r.text
    finally:
        client.app.state.api_docs_dir = old


def test_the_python_items_contract_names_the_runners_entries():
    for interface, entry in items.INTERFACES.items():
        assert items.ENTRIES[interface] == entry and interface in items.__doc__ and entry in items.__doc__, interface
    authored = {k for k, v in items.ENTRIES.items() if v and k != "steps"}             # steps and profile are the editor's own runs
    assert authored == set(items.INTERFACES)
