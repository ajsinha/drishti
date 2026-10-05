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

"""Help centre and About: catalogue, rendering, in-help links, search, F1 context, and the About page."""
from pathlib import Path
import re

import yaml

CONSOLE = Path(__file__).resolve().parent.parent


def test_every_catalogued_guide_exists_and_renders(client):
    catalogue = yaml.safe_load((CONSOLE / "config" / "help.yaml").read_text())
    slugs = [g["slug"] for c in catalogue["categories"] for g in c["guides"]]
    index = client.get("/help")
    assert index.status_code == 200 and "Help &amp; documentation" in index.text
    for slug in slugs:
        r = client.get(f"/help/{slug}")
        assert r.status_code == 200, slug
        assert 'class="help-article"' in r.text, slug
    assert len(slugs) == len(set(slugs)) >= 25


def test_guides_render_examples_boxes_toc_and_internal_links(client):
    page = client.get("/help/getting-started").text
    assert 'class="help-example"' in page and "data-copy" in page
    assert 'class="help-box tip"' in page and "On this page" in page
    assert 'href="/help/using-the-terminal"' in page
    ref = client.get("/help/rachana-reference").text
    assert "DRS-2101" in ref and "panel kinds" in ref.lower()
    arch = client.get("/help/architecture").text
    assert 'href="/help/rachana-reference"' in arch


def test_search_and_missing_guides(client):
    r = client.get("/help/search", params={"q": "lockout"})
    assert r.status_code == 200 and "/help/user-management" in r.text
    missing = client.get("/help/no-such-guide")
    assert missing.status_code == 404 and "There is no guide" in missing.text


def test_f1_context_and_panel_help(client):
    assert client.get("/help/context/view", follow_redirects=False).headers["location"] == "/help/using-the-terminal"
    assert client.get("/help/context/studio", follow_redirects=False).headers["location"] == "/help/screen-designer"
    view = client.get("/v/trade/IRS-48213").text
    assert 'data-screen="view"' in view and 'href="/help/panel-kinds#table"' in view
    kinds = client.get("/help/panel-kinds").text
    for kind in ("kv", "table", "tabs", "line", "area", "hbar", "ladder", "links", "status", "provenance", "markdown", "gauge"):
        assert f'id="{kind}"' in kinds, kind


def test_about_page(client):
    r = client.get("/about")
    assert r.status_code == 200
    for text in ("1.2.0", "21.0.12", "1h 2m", "irs-vanilla v3", "demo", "DRISHTI SOFTWARE LICENCE", "Copyright", "/help/notices"):
        assert text in r.text, text


def test_competitive_landscape(client):
    r = client.get("/about/competitive")
    assert r.status_code == 200
    for text in ("Competitive landscape", "Market data terminals", "Low-code internal tools", "Categories, not vendors",
                 "cmp cmp-yes", "cmp cmp-no", 'id="no-coded-screens"', "Market data content and analytics"):
        assert text in r.text, text
    assert "/about/competitive" in client.get("/about").text and "/about/competitive" in client.get("/help").text


def test_mobile_ready(client):
    html = client.get("/v/trade/IRS-48213").text
    assert "viewport-fit=cover" in html and 'rel="manifest"' in html and "apple-touch-icon" in html
    m = client.get("/static/manifest.webmanifest")
    assert m.status_code == 200 and '"start_url": "/t"' in m.text
    css = client.get("/static/css/terminal.css").text
    assert "@media (max-width: 640px)" in css and "safe-area-inset-bottom" in css and "font-size: 16px" in css


SUTRA_GUIDE = Path(__file__).resolve().parents[2] / "docs" / "guides" / "SUTRA_DEVELOPER_GUIDE.md"
KINDS = ("kv table tabs line area hbar ladder links status provenance markdown gauge surface waterfall histogram scatter candlestick graph "
         "timeline pivot metric").split()


def test_the_sutra_developer_guide_is_a_help_card_with_screenshots(client):
    import re
    index = client.get("/help").text
    assert "Sutra developer guide" in index and "/help/sutra-developer-guide" in index
    page = client.get("/help/sutra-developer-guide")
    assert page.status_code == 200 and "data-unavailable" not in page.text
    srcs = set(re.findall(r'<img[^>]*\bsrc="([^"]+)"', page.text))
    assert len(srcs) >= 5 and all(s.startswith("/help/files/guides/img/sutras/") for s in srcs), srcs
    for src in srcs:
        assert client.get(src).status_code == 200, src


def test_the_sutra_developer_guide_teaches_writing_end_to_end_and_links_the_catalogue():
    text = SUTRA_GUIDE.read_text()
    for s in ("What a Sutra is", "Matching several Sutras", "Reading other entities: `source:`", "Expandable row groups", "Dates, live data",
              "Sutra and inference together", "Testing: `expect.yaml`", "The Build workbench", "Checklist", "Common mistakes"):
        assert re.search(rf"^## \d+\. .*{re.escape(s)}", text, re.M), s
    for kind in KINDS:
        assert f"](PANEL_KINDS.md#{kind})" in text, kind
    assert "/studio?" not in text and "Open Studio" not in text and "twenty panel" not in text
    assert "BUILD_WORKBENCH_TUTORIAL.md" in text and "SCREEN_DESIGNER.md" in text
    assert len(text.splitlines()) < 1500


def test_the_moved_sutra_guides_are_stubs_and_the_old_in_app_copy_is_gone():
    docs = SUTRA_GUIDE.parent
    for old in ("RACHANA_GUIDE.md", "SUTRA_CLI.md"):
        stub = (docs / old).read_text()
        assert "SUTRA_DEVELOPER_GUIDE.md" in stub and len(stub.split("-->")[-1].strip().splitlines()) <= 6, old
    assert not (SUTRA_GUIDE.parents[2] / "drishti-console" / "web" / "guides" / "sutra-guide.md").exists()


def test_no_sutra_guide_tells_the_reader_to_open_studio():
    root = SUTRA_GUIDE.parents[2]
    for f in (SUTRA_GUIDE, root / "docs" / "guides" / "RACHANA_REFERENCE.md", root / "drishti-console" / "web" / "guides" / "nested-data.md"):
        text = f.read_text()
        assert "Open Studio" not in text and "(`/studio`)" not in text and "twenty panel" not in text, f.name


def test_the_build_menu_learn_links_point_at_the_new_guides(client):
    html = client.get("/t").text
    for slug in ("screen-designer", "sutra-developer-guide", "rachana-reference", "pack-developer-guide"):
        assert f'href="/help/{slug}"' in html, slug
    assert "/help/sutra-guide" not in html and "/help/build-a-pack" not in html


def test_guide_screenshots_fit_their_column_and_open_full_size(client):
    """UX-03: a guide's screenshots are wrapped in a link to the image itself, and help.css fits them to the column."""
    import re
    page = client.get("/help/sutra-developer-guide").text
    imgs = re.findall(r'(<a [^>]*>)?\s*<img [^>]*src="(/help/files/guides/img/sutras/[^"]+)"', page)
    assert len(imgs) >= 5
    css = client.get("/static/css/help.css").text
    assert re.search(r"\.help-article img \{[^}]*max-width: 100%;[^}]*height: auto;", css)


def test_a_help_page_has_one_h1_whatever_the_guide_headings_are(client):
    for slug in ("release-notes", "plugins"):
        html = client.get(f"/help/{slug}").text
        assert html.count("<h1") == 1, slug
