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

"""The Rachana examples (docs/guides/examples): the set covers every panel kind, a nested pivot and a tree table, each
parses, each has its JSON and note; Studio opens them (by default, by name, from a file) and the help centre lists them."""
import json
from conftest import open_studio  # noqa: E402
from pathlib import Path

import yaml

from core.examples import Examples

EXAMPLES = Path(__file__).resolve().parents[2] / "docs" / "guides" / "examples"
KINDS = {"kv", "table", "tabs", "line", "area", "hbar", "ladder", "links", "status", "provenance", "markdown", "gauge", "surface",
         "metric", "waterfall", "histogram", "scatter", "candlestick", "graph", "timeline", "pivot"}


def _panels(node):
    """Every panel mapping in a Sutra: the panels, and the body of a tabs panel."""
    for p in node.get("panels", []):
        yield p
        if isinstance(p.get("body"), dict):
            yield p["body"]


def _sutras():
    return {p.name[: -len(".sutra.yaml")]: yaml.safe_load(p.read_text(encoding="utf-8")) for p in sorted(EXAMPLES.glob("*.sutra.yaml"))}


def test_every_example_has_its_three_files_and_parses():
    sutras = _sutras()
    assert len(sutras) >= 10
    index = (EXAMPLES / "README.md").read_text(encoding="utf-8")
    for name, sutra in sutras.items():
        assert sutra["rachana"] == 1 and sutra["sutra"] == name, name
        assert isinstance(json.loads((EXAMPLES / f"{name}.json").read_text(encoding="utf-8")), dict), name
        assert (EXAMPLES / f"{name}.json").stat().st_size < 60_000, f"{name}.json is not small"
        assert any(ln.startswith("# ") for ln in (EXAMPLES / f"{name}.md").read_text(encoding="utf-8").splitlines()), name
        assert f"]({name}.md)" in index, f"{name} is not in the index"


def test_the_set_covers_every_panel_kind_a_nested_pivot_and_a_tree_table():
    panels = [p for s in _sutras().values() for p in _panels(s)]
    assert {p["kind"] for p in panels} == KINDS
    assert any(p["kind"] == "pivot" and isinstance(p.get("by"), list) and len(p["by"]) > 1 for p in panels), "no nested pivot"
    assert any(p["kind"] in ("table", "ladder") and p.get("children") for p in panels), "no tree table"
    showcase = {p["kind"] for p in _panels(_sutras()["all-panels-showcase"])}
    assert showcase == KINDS, "the showcase must hold all twenty-one kinds"


def test_only_the_linked_sources_example_reads_other_entities():
    for name, sutra in _sutras().items():
        sourced = any("source" in p for p in _panels(sutra))
        assert sourced == (name == "linked-sources"), name


def test_names_are_a_whitelist_of_files_present(tmp_path):
    ex = Examples(EXAMPLES, "all-panels-showcase")
    assert ex.names()[0] == "all-panels-showcase" and "tree-table" in ex.names()
    for bad in ("../README", "..", "../../etc/passwd", "tree-table/../tree-table", "tree-table.json", "TREE-TABLE", "", None, "no-such"):
        assert ex.get(bad) is None, bad
    got = ex.get("tree-table")
    assert got.kind == "organisation" and "children" in got.yaml and got.json.lstrip().startswith("{") and got.title == "Tree table"
    (tmp_path / "half.sutra.yaml").write_text("rachana: 1\n")
    assert Examples(tmp_path).names() == [] and Examples(tmp_path / "missing").names() == []


def test_example_route_serves_names_and_nothing_else(client):
    ok = client.get("/studio/example/pivot-row-groups")
    assert ok.status_code == 200 and ok.json()["kind"] == "portfolio" and "by: [desk, book, family]" in ok.json()["yaml"]
    for bad in ("..%2FREADME", "..%2F..%2F..%2Fetc%2Fpasswd", "%2e%2e%2fREADME", "tree-table.json", "nope", "TREE-TABLE"):
        assert client.get(f"/studio/example/{bad}").status_code == 404, bad
    assert client.get("/studio/example/../../etc/passwd").status_code == 404


def _land(client, url):
    r = open_studio(client, url)
    assert r.status_code in (302, 303) and r.headers["location"].startswith("/build/d/"), (url, r.status_code)
    return r.headers["location"], client.get(r.headers["location"]).text


def test_studio_opens_the_showcase_by_default_and_a_named_example_on_request(client):
    loc, page = _land(client, "/studio")
    assert "sutra: all-panels-showcase" in page and "all-panels-showcase.json" in page and loc.endswith("?tab=split")
    loc, page = _land(client, "/studio?example=tree-table")
    assert "sutra: tree-table" in page and "sutra: all-panels-showcase" not in page and 'data-kind="organisation"' in page
    assert "exposure-profile" in page, "the File menu lists the examples"
    assert 'data-file-input' in page and ".yaml,.yml,.json" in page


def test_studio_falls_back_when_the_example_is_not_there(client):
    keep = client.app.state.examples.default
    try:
        for bad in ("", "no-such-example", "../README"):
            client.app.state.examples.default = bad
            page = _land(client, "/studio")[1]
            assert "data-workbench" in page and "sutra: all-panels-showcase" not in page and "all-panels-showcase.json" not in page, bad
        assert "sutra: all-panels-showcase" not in _land(client, "/studio?example=../README")[1]
    finally:
        client.app.state.examples.default = keep


def test_studio_with_a_sutra_or_entity_asked_for_does_not_open_the_example(client):
    assert "sutra: all-panels-showcase" not in _land(client, "/studio?kind=trade&id=IRS-48213")[1]


def test_the_help_centre_lists_every_example_with_note_yaml_json_and_a_studio_link(client):
    page = client.get("/help/examples")
    assert page.status_code == 200 and 'class="help-article"' in page.text
    for name in Examples(EXAMPLES).names():
        assert f'id="{name}"' in page.text and f'data-example-copy="{name}"' in page.text, name
        assert f"{name}.sutra.yaml" in page.text and f"{name}.json" in page.text, name
    assert "<details>" in page.text and "children:" in page.text and "&#34;exposure&#34;" in page.text
    assert "data-unavailable" not in page.text, "dead link in the examples page"
    assert 'href="/help/rachana-guide"' in page.text
    assert "/help/examples" in client.get("/help").text


def test_the_examples_readme_has_no_dead_links_in_help(client):
    lib = client.app.state.libraries
    page = client.get("/help/examples")
    assert page.status_code == 200
    guide = next(iter(lib.values())).guides["examples"]
    assert "data-unavailable" not in next(iter(lib.values())).render(guide.slug)["html"]
