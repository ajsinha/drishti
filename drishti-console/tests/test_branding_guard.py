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

"""Branding from configuration only (docs/admin/CONFIGURATION.md, ``ui``): no template or script writes the product's name; it comes from
``ui.product`` ({{ PRODUCT }} in a template, ``<body data-product>`` in a script), and its features follow it ("<product> SQL",
"<product> Elements", "<product>*net"). Allowed: the licence header, comments, code identifiers (``window.DrishtiChannel``,
``drishti-`` ids, ``DRISHTI_`` variables, ``data-drishti`` attributes), header names (``X-Drishti-…``) and paths."""
import re
from pathlib import Path

from fastapi.testclient import TestClient

from conftest import CONSOLE
from core.app import create_app
from core.config import load_settings

WEB = CONSOLE / "web"
NAME = re.compile(r"(?<![\w-])(Drishti|DRISHTI)(?![\w-])")     # the word on its own: not DrishtiChannel, X-Drishti-User or DRISHTI_PACKS
ALLOWED = ("Project Drishti",)                                 # the licence header's first line (build/notes.js strips it from a pasted file)


def _visible(path: Path) -> str:
    """The file without what nobody sees: comments (the licence header is one), line by line so line numbers stay."""
    text = path.read_text(encoding="utf-8")
    blank = lambda m: "\n" * m.group(0).count("\n")             # noqa: E731
    if path.suffix == ".html":
        text = re.sub(r"\{#.*?#\}", blank, text, flags=re.S)
        return re.sub(r"<!--.*?-->", blank, text, flags=re.S)
    text = re.sub(r"/\*.*?\*/", blank, text, flags=re.S)
    text = re.sub(r"(?m)^\s*//.*$", "", text)
    return re.sub(r"(?m)\s//\s.*$", "", text)                  # a trailing comment (never https://, which has no space before it)


def _literals(root: Path, pattern: str) -> list[str]:
    out = []
    for path in sorted(root.rglob(pattern)):
        for n, line in enumerate(_visible(path).split("\n"), 1):
            for m in NAME.finditer(line):
                if not any(a in line[max(0, m.start() - 8):m.end() + 8] for a in ALLOWED):
                    out.append(f"{path.relative_to(root.parent)}:{n}: {line.strip()[:160]}")
    return out


def test_no_template_writes_the_product_name():
    found = _literals(WEB / "templates", "*.html")
    assert not found, "write {{ PRODUCT }} (ui.product), not the name:\n" + "\n".join(found)


def test_no_script_writes_the_product_name():
    found = _literals(WEB / "static" / "js", "*.js") + _literals(WEB / "embed", "*.js")
    assert not found, "read the name from document.body.dataset.product (ui.product), not a literal:\n" + "\n".join(found)


def test_the_guard_sees_a_literal_and_lets_identifiers_through(tmp_path):
    (tmp_path / "a.html").write_text('{# Project Drishti · header #}\n<p>Drishti SQL</p>\n<i data-drishti-x class="drishti-y">{{ PRODUCT }}*net</i>\n')
    (tmp_path / "b.js").write_text("/* Drishti */\nwindow.DrishtiChannel = 1; // Drishti\nh('X-Drishti-User'); say('DRISHTI_PACKS');\nsay('Drishti*net');\n")
    found = _literals(tmp_path, "*.*")
    assert [f.split(":")[1] for f in found] == ["2", "4"], found


def test_a_rebranded_console_names_the_product_and_owner_from_configuration(backend):
    app = create_app(load_settings(CONSOLE / "config", ["--ui.product=Acme Insight", "--ui.owner=Jane Doe", "--ui.owner_email=jane@acme.test",
                                                       "--ui.product_meaning=", "--ui.product_native="]))
    app.state.backend = backend
    html = TestClient(app).get("/about").text
    assert "Acme Insight Elements" in html and "Acme Insight*net" in html and "<b>Jane Doe</b>" in html and "mailto:jane@acme.test" in html
    assert "Ashutosh Sinha</b>" not in html
    shown = re.sub(r"<script.*?</script>|<style.*?</style>", "", html, flags=re.S)
    assert not NAME.search(re.sub(r"<[^>]+>", " ", shown)), "a page of a rebranded console still names Drishti"


def test_the_wordmark_font_is_not_named_after_the_product():
    css = (WEB / "static" / "css" / "theme.css").read_text(encoding="utf-8")
    assert '"Product Wordmark"' in css and "Drishti Wordmark" not in css and "Drishti Tagline" not in css


def test_the_menus_and_the_edition_pages_never_write_the_product_name():
    """The menus and footer as data (config/menus.yaml) say {product}; the edition's landing, its script and the lens links take the
    product from configuration (EDITIONS.md section 5.10)."""
    import yaml

    menus = yaml.safe_load((CONSOLE / "config" / "menus.yaml").read_text(encoding="utf-8"))
    shown = [str(i.get(k, "")) for m in menus["menus"] for c in m["columns"] for i in c["items"] for k in ("label", "desc")]
    shown += [str(i.get("label", "")) for i in menus["footer"]]
    assert not [t for t in shown if NAME.search(t)]


def test_a_rebranded_console_has_no_drishti_in_its_manifest_title_or_menus(backend):
    app = create_app(load_settings(CONSOLE / "config", ["--ui.product=Acme Insight", "--ui.tagline=Risk, simply"]))
    app.state.backend = backend
    c = TestClient(app)
    assert c.get("/manifest.webmanifest").json()["name"] == "Acme Insight"
    page = c.get("/t").text
    nav = page[page.index('class="tbar-menus"'):page.index('class="tbar-tools"')]
    assert "Acme Insight*net" in nav and not NAME.search(re.sub(r"<[^>]+>", " ", nav))
