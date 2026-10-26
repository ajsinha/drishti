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
        assert not re.search(r'<(?:td|th)[^>]*\sstyle=', r.text), slug                 # aligned columns are classes: the CSP drops inline styles
    assert len(slugs) == len(set(slugs)) >= 25


def test_guides_render_examples_boxes_toc_and_internal_links(client):
    page = client.get("/help/install-and-run").text
    assert 'class="help-example"' in page and "data-copy" in page
    assert "On this page" in page and 'href="/help/user-guide"' in page
    assert 'class="help-box tip"' in client.get("/help/docs-guides-nested-documents").text
    ref = client.get("/help/rachana-reference").text
    assert "DRS-2101" in ref and "panel kinds" in ref.lower()
    arch = client.get("/help/docs-architecture-architecture").text
    assert 'href="/help/rachana-reference"' in arch


def test_search_and_missing_guides(client):
    r = client.get("/help/search", params={"q": "lockout"})
    assert r.status_code == 200 and "/help/user-management" in r.text
    missing = client.get("/help/no-such-guide")
    assert missing.status_code == 404 and "There is no guide" in missing.text


def test_f1_and_the_short_addresses_of_the_240_and_250_pages_open_their_sections(client):
    """BI Studio, the theme designer, Analyse, Transforms and the Python console have their own F1 target, and the new pages a short help address."""
    for screen, page, anchor in (("bi-designer", "bi-guide", "26-bi-studio-authoring-as-one-product-wave-c-240"),
                                 ("bi-theme-designer", "reports", "52-themes"),
                                 ("bi-transform", "python-bi", "17-data-prep-the-transform-editor-240"),
                                 ("bi-pyconsole", "analyse", "16-the-browser-python-console"),
                                 ("bi-explore", "analyse", "")):
        target = client.get(f"/help/context/{screen}", follow_redirects=False).headers["location"]
        assert target == f"/help/{page}" + (f"#{anchor}" if anchor else ""), (screen, target)
        if anchor:
            assert f'id="{anchor}"' in client.get(f"/help/{page}").text, (screen, anchor)
    for slug in ("bi-studio", "transforms", "python-console", "themes", "drishti-net"):
        r = client.get(f"/help/{slug}", follow_redirects=False)
        assert r.status_code == 301, slug
        page, _, anchor = r.headers["location"].removeprefix("/help/").partition("#")
        final = client.get(f"/help/{page}")
        assert final.status_code == 200 and (not anchor or f'id="{anchor}"' in final.text), slug


def test_f1_context_and_panel_help(client):
    assert client.get("/help/context/view", follow_redirects=False).headers["location"] == "/help/user-guide"
    assert client.get("/help/context/studio", follow_redirects=False).headers["location"] == "/help/screen-designer"
    view = client.get("/v/trade/IRS-48213").text
    assert 'data-screen="view"' in view and 'href="/help/panels#table"' in view
    kinds = client.get("/help/panels").text
    for kind in ("kv", "table", "tabs", "line", "area", "hbar", "ladder", "links", "status", "provenance", "markdown", "gauge"):
        assert f'id="{kind}"' in kinds, kind


def test_about_page(client):
    r = client.get("/about")
    assert r.status_code == 200
    for text in ("1.2.0", 'id="what"', 'id="problem"', 'id="features"', 'id="performance"', 'id="capabilities"', 'id="competitive"',
                 'id="stack"', 'id="use-cases"', 'id="author"', 'id="legal"', "Ashutosh Sinha", "Documentation", "/help/third-party-notices",
                 "docs/admin/PERFORMANCE.md", "System details for admins", 'href="/admin/health"'):
        assert text in r.text, text
    # a product page: no Sutra, source or pack listing
    for text in ("irs-vanilla v3", "Sutras loaded", "Domain packs</dt>", "<dt>Java</dt>", "pnl-code"):
        assert text not in r.text, text
    assert client.get("/about/competitive").status_code == 200 and 'href="/about/competitive"' in r.text


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
        assert f"](PANELS.md#{kind})" in text, kind
    assert "/studio?" not in text and "Open Studio" not in text and "twenty panel" not in text
    assert "SCREEN_DESIGNER.md" in text
    assert len(text.splitlines()) < 1500


def test_the_retired_sutra_guides_are_gone_and_their_addresses_redirect(client):
    docs = SUTRA_GUIDE.parent
    for old in ("RACHANA_GUIDE.md", "SUTRA_CLI.md"):
        assert not (docs / old).exists(), old
    for old in ("sutra-guide", "rachana-guide"):
        r = client.get(f"/help/{old}", follow_redirects=False)
        assert r.status_code == 301 and r.headers["location"] == "/help/sutra-developer-guide", old
    assert not (SUTRA_GUIDE.parents[2] / "drishti-console" / "web" / "guides" / "sutra-guide.md").exists()


def test_no_sutra_guide_tells_the_reader_to_open_studio():
    root = SUTRA_GUIDE.parents[2]
    for f in (SUTRA_GUIDE, root / "docs" / "guides" / "RACHANA_REFERENCE.md", root / "docs" / "guides" / "NESTED_DOCUMENTS.md"):
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


# The 90 slugs of the help centre before it was consolidated into six sections (2.0.0): every one still opens something.
OLD_SLUGS = """quickstart windows ide-guide install-and-run impact sharing-and-discussion monitors-and-alerts workspaces layout-mode pivot-tab python-calc bi-guide
bi-tutorial datasets python-bi analyse reports bi-governance distribution boards using-the-terminal sutra-developer-guide screen-designer schema-to-pack
build-workbench-tutorial screen-builder panel-kinds panel-developer-guide examples nested-data rachana-reference inference live performance scale-benchmark
connector-developer-guide connector-files connector-tls connector-proxy connector-demo demo-data connector-file connector-delta connector-iceberg
connector-duckdb connector-postgres jdbc-queries connector-mongodb connector-redis connector-aerospike connector-s3 connector-rest connector-feeds
connector-news connector-yfinance connector-kafka connector-activemq connector-rabbitmq packs pack-developer-guide operationalising cli-guide data-loads
real-data user-management operations configuration troubleshooting runbook-source-down runbook-sutra-broken runbook-live-latency runbook-sign-in clients
developer-guide api how-it-fits elements-guide elements ai-providers mcp-client rupaka about-this-page-design collaboration architecture quant-service
decisions plan release-notes changelog notices sutra-guide rachana-guide plugins build-a-pack sutra-studio""".split()


def test_the_help_centre_has_six_sections_and_about_forty_pages():
    catalogue = yaml.safe_load((CONSOLE / "config" / "help.yaml").read_text())
    assert [c["id"] for c in catalogue["categories"]] == ["start", "use", "bi", "data", "build", "operate"]
    total = sum(len(c["guides"]) for c in catalogue["categories"])
    # 47: the 45 pages plus VaR's two (the VaR guide and tutorial 25), the product owner's request of 2026-10-26: VaR help is two pages.
    assert 30 <= total <= 47, total


def test_every_old_help_address_still_opens_something(client):
    """A retired slug answers 301 to the page, and the section of it, that took the subject over; a slug still listed answers 200."""
    catalogue = yaml.safe_load((CONSOLE / "config" / "help.yaml").read_text())
    listed = {g["slug"] for c in catalogue["categories"] for g in c["guides"]}
    moved = 0
    for slug in OLD_SLUGS:
        r = client.get(f"/help/{slug}", follow_redirects=False)
        if slug in listed or slug == "changelog":
            assert r.status_code == 200, slug
            continue
        assert r.status_code == 301, (slug, r.status_code)
        moved += 1
        target = r.headers["location"]
        page, _, anchor = target.removeprefix("/help/").partition("#")
        final = client.get(f"/help/{page}")
        assert final.status_code == 200 and "data-unavailable" not in final.text, target
        if anchor:
            assert f'id="{anchor}"' in final.text, f"{slug} -> {target}: no such section"
    assert moved >= 50


def test_the_tutorials_page_renders_the_tutorials_index_and_each_tutorial_opens_with_its_pictures(client):
    page = client.get("/help/tutorials")
    assert page.status_code == 200 and "Your first 15 minutes" in page.text and "Run it in production" in page.text
    links = sorted(set(re.findall(r'href="(/help/docs-tutorials-[0-9a-z-]+)"', page.text)))
    assert len(links) == 25, links
    shots = 0
    for href in links:
        t = client.get(href)
        assert t.status_code == 200 and "data-unavailable" not in t.text, href
        shots += len(re.findall(r'src="/help/files/tutorials/img/', t.text))
    assert shots >= 100
