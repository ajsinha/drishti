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

"""The product documents Drishti ships with are in Help, offline (i7m): every bundled page renders, a GitHub link to a bundled
document opens the local page (anchor kept), a link to anything else stays as it is, and a relative link inside a copy that leaves
the copy goes to GitHub at the pinned tag."""
import html
import re
from pathlib import Path

import pytest
import yaml

ROOT = Path(__file__).resolve().parents[2]
DOCS = ROOT / "docs"
PINS = yaml.safe_load((ROOT / "tools" / "docs" / "product_docs.yaml").read_text(encoding="utf-8"))["products"]
GITHUB = re.compile(r"https://github\.com/drishti-platform/drishti-(?:rachana|elements)/blob/[^/#?\s)\"'>]+/[^#?\s)\"'>]+\.md(?:#[^\s)\"'>]*)?")


@pytest.fixture(scope="module")
def lib():
    from core.guides import Library
    console = ROOT / "drishti-console"
    return Library(console, console / "config" / "help.yaml", DOCS, [])


def _bundled():
    return [(n, p["repo"], p["tag"], f) for n, p in PINS.items() for f in p["files"]]


def test_a_github_link_to_a_bundled_document_opens_the_local_page(lib):
    assert lib.product_link("https://github.com/drishti-platform/drishti-rachana/blob/v1.1.1/docs/PANEL_KINDS.md#waterfall") \
        == "/help/rachana-panel-kinds#waterfall"
    assert lib.product_link("https://github.com/drishti-platform/drishti-rachana/blob/v1.1.1/spec/1/SPEC.md") == "/help/rachana-spec"
    assert lib.product_link("https://github.com/drishti-platform/drishti-elements/blob/v3.3.2-ide3/docs/USING.md#x") == "/help/elements-using#x"


def test_a_link_to_anything_else_is_left_alone(lib):
    for href in ("https://github.com/drishti-platform/drishti-rachana/blob/v1.0.0/docs/PANEL_KINDS.md",         # another tag
                 "https://github.com/drishti-platform/drishti-rachana/blob/v1.1.1/docs/DEVELOPING.md",    # not bundled
                 "https://github.com/drishti-platform/drishti-lake/blob/v0.9.1-ide3/docs/X.md",              # another product
                 "https://example.com/blob/v1/docs/USING.md", "/help/user-guide"):
        assert lib.product_link(href) is None, href


def test_a_relative_link_that_leaves_a_copy_goes_to_github_at_the_pinned_tag(lib):
    source = DOCS / "products" / "rachana" / "v1.1.1" / "docs" / "TUTORIAL.md"
    link = lib._link(source, re.search(r'<a href="([^"]*)"([^>]*)>(.*?)</a>', '<a href="../examples/README.md#static-sales">x</a>'))
    assert 'href="https://github.com/drishti-platform/drishti-rachana/blob/v1.1.1/examples/README.md#static-sales"' in link
    inside = lib._link(source, re.search(r'<a href="([^"]*)"([^>]*)>(.*?)</a>', '<a href="IN_YOUR_APP.md#4-editing">x</a>'))
    assert 'href="/help/rachana-in-your-app#4-editing"' in inside


@pytest.mark.parametrize("product,repo,tag,path", _bundled(), ids=lambda v: str(v))
def test_every_bundled_page_renders(client, lib, product, repo, tag, path):
    slug = lib.by_file.get((DOCS / "products" / product / tag / path).resolve())
    assert slug, f"{product}/{path} is not a Help page (help.yaml, Reference > Product documents)"
    r = client.get(f"/help/{slug}")
    assert r.status_code == 200 and "data-unavailable" not in r.text, slug


def test_every_drishti_link_to_a_bundled_product_document_resolves_locally(client, lib):
    found = 0
    for md in [*DOCS.rglob("*.md"), ROOT / "README.md"]:
        if "products" in md.relative_to(ROOT).parts[:2]:
            continue
        for url in set(GITHUB.findall(md.read_text(encoding="utf-8"))):
            if lib.product_link(url):
                found += 1
    assert found >= 40, found
    # in the rendered pages no link to a bundled document is left pointing at GitHub
    for slug in ("rachana-reference", "developer-guide", "docs-architecture-architecture", "integration-guide", "cli-guide"):
        r = client.get(f"/help/{slug}")
        if r.status_code != 200:
            continue
        left = [html.unescape(u) for u in GITHUB.findall(r.text) if lib.product_link(html.unescape(u))]
        assert not left, f"{slug}: {left[:3]}"
