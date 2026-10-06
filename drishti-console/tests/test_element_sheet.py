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

"""The element stylesheet (ELEMENTS.md step 8): generated, content-versioned, only the icons the macros use, never stale.
Regenerate with ``python3 tools/elements_sheet.py``."""
import gzip
import hashlib
import re

import pytest
from fastapi.testclient import TestClient

from conftest import CONSOLE
from core import element_sheet as es
from core.app import create_app
from core.config import load_settings
from test_embed import HOST, make_client

REGEN = "regenerate it: python3 tools/elements_sheet.py"


def test_the_committed_sheet_is_not_stale():
    text, manifest = es.build()
    have, have_manifest = es.committed()
    assert have == text and have_manifest == manifest, f"the element sheet is stale; {REGEN}"


def test_every_icon_a_macro_uses_is_in_the_sheet_and_nothing_else_is():
    sheet, _ = es.committed()
    in_sheet = set(re.findall(r"^\.bi-([a-z0-9-]+)::before", sheet, re.M))
    macros = es.WEB / "templates" / "_macros"
    used = set()
    for name in ("panels.html", "view.html"):
        used |= set(es.ICON_NAME.findall((macros / name).read_text(encoding="utf-8")))
    assert used, "the macros name no icon at all: the scan is broken"
    assert used <= in_sheet, f"a macro uses an icon the element sheet lacks: {sorted(used - in_sheet)}; {REGEN}"
    assert in_sheet == set(es.icons_used()), "the sheet holds glyphs nothing uses"
    assert len(in_sheet) < 40                                           # a subset, not the 2000 of Bootstrap Icons


def test_the_scan_catches_an_icon_added_to_a_macro(monkeypatch, tmp_path):
    macro = tmp_path / "x.html"
    macro.write_text('<i class="bi bi-lock"></i><i class="bi bi-rocket-takeoff"></i>')
    monkeypatch.setattr(es, "WEB", tmp_path)
    monkeypatch.setattr(es, "ICON_SOURCES", ("x.html",))
    assert set(es.icons_used()) == {"lock", "rocket-takeoff"}
    monkeypatch.setattr(es, "ICON_SOURCES", ("x.html", "missing.js"))
    assert "rocket-takeoff" in es.icons_used()


def test_a_name_that_is_not_a_glyph_fails_the_build(monkeypatch):
    monkeypatch.setattr(es, "icons_used", lambda: {"not-a-glyph": ["templates/_macros/panels.html"]})
    with pytest.raises(ValueError, match="bi-not-a-glyph"):
        es.build()


def test_the_sheet_is_for_a_shadow_root_small_and_has_no_font_face():
    sheet, manifest = es.committed()
    assert "@font-face" not in sheet                                    # the element registers the font through FontFace
    assert ":host" in sheet and ":root" not in sheet and not re.search(r"(?m)^\s*(html|body)\b", sheet)
    assert "bootstrap-icons" in sheet and ".bi::before" in sheet
    assert manifest["gzip"] == len(gzip.compress(sheet.encode(), 9, mtime=0)) and manifest["gzip"] <= es.GZIP_TARGET
    assert "PROPRIETARY AND CONFIDENTIAL" in sheet[:600]


def test_the_sheet_is_served_under_its_hash_for_ever_and_a_wrong_hash_is_refused(backend):
    client = make_client(backend)
    _, manifest = es.committed()
    ok = client.get(f"/embed/v1/elements/{manifest['version']}/drishti-view.css", headers={"Origin": HOST})
    assert ok.status_code == 200 and "immutable" in ok.headers["cache-control"] and ok.headers["access-control-allow-origin"] == HOST
    assert ok.text == es.committed()[0]
    assert client.get("/embed/v1/elements/deadbeef0000/drishti-view.css", headers={"Origin": HOST}).status_code == 404
    assert client.get("/embed/v1/drishti-view.css", headers={"Origin": HOST}).text == ok.text


def test_no_width_media_rule_is_left_in_the_element_sheet():
    """Inside a host page a width @media reacts to the host's viewport; the element must answer to its own width."""
    sheet, _ = es.committed()
    assert not re.findall(r"@media[^{]*\((?:max|min)-width", sheet), f"a width @media rule is in the element sheet; {REGEN}"
    assert "@container drishti (max-width: 640px)" in sheet
    assert "@media print" in sheet and "prefers-color-scheme" in sheet          # non-width media stay as they are
    assert "container:drishti/inline-size" in (es.WEB / "embed" / "assets.js").read_text(encoding="utf-8")


def test_container_queries_rewrites_width_rules_only():
    src = ("@media (max-width: 640px) {\n  :host { font-size: 15px; }\n  .about { width: 100vw; }\n}\n"
           "@media (min-width: 700px) and (max-width: 900px) { .a { b: c; } }\n@media print { .x { y: z; } }\n@media (prefers-color-scheme: dark) { .q { r: s; } }\n")
    out = es.container_queries(src)
    assert "@container drishti (max-width: 640px) {" in out and ".view { font-size: 15px; }" in out and "width: 100cqw" in out
    assert "@container drishti (min-width: 700px) and (max-width: 900px) {" in out
    assert "@media print {" in out and "@media (prefers-color-scheme: dark) {" in out and "@media (max-width" not in out
