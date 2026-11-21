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

from conftest import help_pages
from core import elements_pin  # noqa: E402  (the vendored Drishti Elements)

CONSOLE = Path(__file__).resolve().parent.parent


def _catalogue() -> dict:
    """help.yaml with every module's Help sections (plugins/<module>/help.yaml) in Help's order: what the help centre is built from."""
    from core import menus, modules
    data = yaml.safe_load((CONSOLE / "config" / "help.yaml").read_text())
    by_id = {s["id"]: s for s in [*data["sections"], *modules.help_pages()]}
    return {**data, "sections": [by_id[h["id"]] for h in menus.help_sections(CONSOLE) if h["id"] in by_id]}


def _pages(catalogue: dict) -> list:
    """Every page entry of help.yaml, in order: sections, then groups, then pages (2.9.11.a)."""
    return [g for s in catalogue["sections"] for grp in s["groups"] for g in grp["guides"]]


def test_every_catalogued_guide_exists_and_renders(client):
    catalogue = _catalogue()
    slugs = [g["slug"] for g in _pages(catalogue)]
    index = client.get("/help")
    assert index.status_code == 200 and "data-help-hub" in index.text and "data-help-tiles" in index.text
    for slug in slugs:
        r = client.get(f"/help/{slug}")
        assert r.status_code == 200, slug
        assert 'class="help-article"' in r.text, slug
        assert not re.search(r'<(?:td|th)[^>]*\sstyle=', r.text), slug                 # aligned columns are classes: the CSP drops inline styles
    assert len(slugs) == len(set(slugs)) >= 25


def test_guides_render_examples_boxes_toc_and_internal_links(client):
    page = client.get("/help/operations").text
    assert 'class="help-example"' in page and "data-copy" in page
    assert "On this page" in page and 'href="/help/user-guide"' in page
    assert 'class="help-box tip"' in client.get("/help/build-guide").text   # its deep-documents section (was NESTED_DOCUMENTS.md) has a tip box
    ref = client.get("/help/rachana-reference").text
    assert "DRS-2101" in ref and "panel kinds" in ref.lower()
    arch = client.get("/help/docs-architecture-architecture").text
    assert 'href="/help/rachana-reference"' in arch


def test_search_and_missing_guides(client):
    r = client.get("/help/search", params={"q": "lockout"})
    assert r.status_code == 200 and "/help/admin" in r.text
    missing = client.get("/help/no-such-guide")
    assert missing.status_code == 404 and "There is no guide" in missing.text


def test_f1_and_the_short_addresses_of_the_240_and_250_pages_open_their_sections(client):
    """BI Studio, the theme designer, the Notebook and Transforms have their own F1 target, and the new pages a short help address."""
    for screen, page, anchor in (("bi-designer", "bi-reports", "7-bi-studio-and-the-report-designer"),
                                 ("bi-theme-designer", "bi-report-file", "23-themes"),
                                 ("bi-transform", "bi-python-notebook", "5-data-prep-the-transform-editor"),
                                 ("bi-notebook", "bi-python-notebook", "2-the-notebook")):
        target = client.get(f"/help/context/{screen}", follow_redirects=False).headers["location"]
        assert target == f"/help/{page}" + (f"#{anchor}" if anchor else ""), (screen, target)
        if anchor:
            assert f'id="{anchor}"' in client.get(f"/help/{page}").text, (screen, anchor)


def test_f1_context_and_panel_help(client):
    assert client.get("/help/context/view", follow_redirects=False).headers["location"] == "/help/user-guide"
    assert client.get("/help/context/studio", follow_redirects=False).headers["location"] == "/help/rachana-reference#screen-designer-and-the-build-workbench"
    view = client.get("/v/trade/IRS-48213").text
    assert 'data-screen="view"' in view and 'href="/help/panels#table"' in view
    kinds = client.get("/help/rachana-reference").text
    for kind in ("kv", "table", "tabs", "line", "area", "hbar", "ladder", "links", "status", "provenance", "markdown", "gauge"):
        assert f'id="{kind}"' in kinds, kind


def test_about_page(client):
    r = client.get("/about")
    assert r.status_code == 200
    for text in ("1.2.0", 'id="what"', 'id="problem"', 'id="features"', 'id="performance"', 'id="capabilities"', 'id="competitive"',
                 'id="stack"', 'id="use-cases"', 'id="author"', 'id="legal"', "Ashutosh Sinha", "Documentation", "/help/third-party-notices",
                 "docs/guides/CLUSTER_GUIDE.md", "System details for admins", 'href="/admin/health"'):
        assert text in r.text, text
    # a product page: no Sutra, source or pack listing
    for text in ("irs-vanilla v3", "Sutras loaded", "Domain packs</dt>", "<dt>Java</dt>", "pnl-code"):
        assert text not in r.text, text
    assert 'href="/help/competitive-analysis"' in r.text                 # security off: the acting user is an administrator


def test_one_competitive_analysis(client):
    """One competitive analysis, the Help guide: the old landscape page's address leads to it, and About, the Help centre and the Help
    menu link it (for administrators; test_an_admin_only_page_is_shown_to_administrators_and_hidden_from_everyone_else for the rest)."""
    r = client.get("/about/competitive", follow_redirects=False)
    assert r.status_code == 301 and r.headers["location"] == "/help/competitive-analysis"
    assert 'href="/help/competitive-analysis"' in client.get("/about").text and 'href="/help/competitive-analysis"' in client.get("/help").text
    assert "/about/competitive" not in client.get("/help").text
    assert not (CONSOLE / "config" / "competitive.yaml").exists()


def test_mobile_ready(client):
    html = client.get("/v/trade/IRS-48213").text
    assert "viewport-fit=cover" in html and 'rel="manifest"' in html and "apple-touch-icon" in html
    m = client.get("/static/drishti_manifest.webmanifest")
    assert m.status_code == 200 and '"start_url": "/t"' in m.text
    css = client.get("/static/css/drishti_terminal.css").text
    assert "@media (max-width: 640px)" in css and "safe-area-inset-bottom" in css and "font-size: var(--d-fs-16)" in css   # 16px: iOS does not zoom
    assert "--d-fs-16: 16px;" in client.get(elements_pin.static_url("css/drishti_tokens.css")).text


SUTRA_GUIDE = Path(__file__).resolve().parents[2] / "docs" / "guides" / "BUILD_GUIDE.md"
KINDS = ("kv table tabs line area hbar ladder links status provenance markdown gauge surface waterfall histogram scatter candlestick graph "
         "timeline pivot metric").split()


def test_the_sutra_developer_guide_is_a_help_card_with_screenshots(client):
    import re
    index = help_pages(client)
    assert "Screen developer guide" in index and "/help/build-guide#11-what-a-sutra-is-and-when-one-applies" in index
    page = client.get("/help/build-guide#11-what-a-sutra-is-and-when-one-applies")
    assert page.status_code == 200 and "data-unavailable" not in page.text
    srcs = set(re.findall(r'<img[^>]*\bsrc="([^"]+)"', page.text))
    assert sum(s.startswith("/help/files/guides/img/sutras/") for s in srcs) >= 5 and all(s.startswith("/help/files/") for s in srcs), srcs
    for src in srcs:
        assert client.get(src).status_code == 200, src


def test_the_sutra_developer_guide_teaches_writing_end_to_end_and_links_the_catalogue():
    text = SUTRA_GUIDE.read_text()
    for s in ("What a Sutra is", "Matching several Sutras", "Reading other entities: `source:`", "Expandable row groups", "Dates, live data",
              "Sutra and inference together", "Testing: `expect.yaml`", "The Build workbench", "Checklist", "Common mistakes"):
        assert re.search(rf"^## \d+\. .*{re.escape(s)}", text, re.M), s
    for kind in KINDS:
        assert f"](RACHANA_REFERENCE.md#{kind})" in text, kind
    assert "/studio?" not in text and "Open Studio" not in text and "twenty panel" not in text
    assert "RACHANA_REFERENCE.md#screen-designer-and-the-build-workbench" in text
    assert len(text.splitlines()) < 5500     # the build guide holds packs, schema to pack, Sutras, forms and phones (2.9.11.h)


def test_the_retired_sutra_guides_are_gone():
    docs = SUTRA_GUIDE.parent
    for old in ("RACHANA_GUIDE.md", "SUTRA_CLI.md"):
        assert not (docs / old).exists(), old
    assert not (SUTRA_GUIDE.parents[2] / "drishti-console" / "web" / "guides" / "sutra-guide.md").exists()


def test_no_sutra_guide_tells_the_reader_to_open_studio():
    root = SUTRA_GUIDE.parents[2]
    for f in (SUTRA_GUIDE, root / "docs" / "guides" / "RACHANA_REFERENCE.md", root / "docs" / "guides" / "BUILD_GUIDE.md"):
        text = f.read_text()
        assert "Open Studio" not in text and "(`/studio`)" not in text and "twenty panel" not in text, f.name


def test_the_build_menu_learn_links_point_at_the_new_guides(client):
    html = client.get("/t").text
    for slug in ("rachana-reference#screen-designer-and-the-build-workbench", "build-guide#11-what-a-sutra-is-and-when-one-applies", "rachana-reference", "build-guide"):
        assert f'href="/help/{slug}"' in html, slug
    assert "/help/sutra-guide" not in html and "/help/build-a-pack" not in html


def test_guide_screenshots_fit_their_column_and_open_full_size(client):
    """UX-03: a guide's screenshots are wrapped in a link to the image itself, and drishti_help.css fits them to the column."""
    import re
    page = client.get("/help/build-guide#11-what-a-sutra-is-and-when-one-applies").text
    imgs = re.findall(r'(<a [^>]*>)?\s*<img [^>]*src="(/help/files/guides/img/sutras/[^"]+)"', page)
    assert len(imgs) >= 5
    css = client.get("/static/css/drishti_help.css").text
    assert re.search(r"\.help-article img \{[^}]*max-width: 100%;[^}]*height: auto;", css)


def test_a_help_page_has_one_h1_whatever_the_guide_headings_are(client):
    for slug in ("release-notes", "developer-guide"):
        html = client.get(f"/help/{slug}").text
        assert html.count("<h1") == 1, slug


def test_the_help_centre_has_eight_sections_and_internal_and_about_sixty_pages():
    """The decided structure (2.9.11.a, mock-up help-hub/A): eight sections and Internal for administrators; Risk measures is the banking
    module's (plugins/banking/help.yaml, placed after Get started)."""
    from core import menus, modules
    core_catalogue = yaml.safe_load((CONSOLE / "config" / "help.yaml").read_text())
    by_id = {s["id"]: s for s in [*core_catalogue["sections"], *modules.help_pages()]}
    heads = [h for h in menus.help_sections(CONSOLE) if not h["id"].startswith("module-")]   # the sitemap's sections (a hosted module's own Help section is its own)
    catalogue = {**core_catalogue, "sections": [by_id[h["id"]] for h in heads]}
    assert "risk" not in [c["id"] for c in core_catalogue["sections"]], "the risk section is the banking module's"
    assert next(h for h in heads if h["id"] == "risk")["domain"] == "banking"
    assert [c["id"] for c in catalogue["sections"]] == ["get-started", "risk", "analytics", "data", "build", "operate", "reference",
                                                         "knowledge", "internal"]
    assert [c["name"] for c in heads] == ["Get started", "Risk measures", "Analytics and BI", "Data", "Build", "Operate",
                                                           "Reference", "Knowledge", "Internal"]
    assert next(c for c in heads if c["id"] == "internal")["roles"] == ["admin"]
    total = len(_pages(catalogue))
    # 47: the 45 pages plus VaR's two (the VaR guide and tutorial 2, Part A), the product owner's request of 2026-10-26: VaR help is two pages.
    # 49: plus exposure's two (the exposure guide and tutorial 3), the same rule (each measure has exactly two documents and two entries).
    # 51: plus the groupings and analytics' two (the guide and tutorial 6, 2.9.2), the same rule.
    # 52: plus External data (docs/guides/WORLD_DATA_GUIDE.md, 2.9.1), the product owner's request of 2026-10-26: a separate help page for
    # external data extraction, with examples.
    # 54: plus the funds pack's two (the funds and ETFs guide and tutorial 13, Part C, 2.9.2), the same rule (two documents, two entries).
    # 62: plus Knowledge (the product owner's request of 2026-10-26), a section of its own at the top: the home with the glossary and
    # the seven domain pages (docs/knowledge/, WHAT <GO>).
    # 63: plus the eighth Knowledge page, Companies, funds and ETFs (2.9.3).
    # 64: plus Using Drishti with Databricks Delta Lake or Apache Iceberg (docs/guides/DATA_GUIDE.md, 2.9.5), the product owner's
    # request of 2026-10-28: one page, under Data beside the connector guides.
    # 66: plus the lake configuration's two (the lake guide and tutorial 16, Part B) and Data out's two (the Arrow Flight guide and tutorial 17),
    # 2.9.11.c, the same rule (a feature's guide and tutorial, two entries); Flight's reference and client pages are reached from its guide.
    # 2.9.11.b's data inbox, custom functions and MR cube tutorials raised the ceiling to 70; 2.9.11.c's lake and Flight pages to 72.
    # 73: plus Schema to pack (docs/guides/BUILD_GUIDE.md, 2.9.11.c), split from the data guide when it passed 1,500 lines.
    # 75: plus virtual tables' two (the tables guide and tutorial 44, 2.9.11.d), the same rule (a feature's guide and tutorial).
    # 77: plus the public sector's two (the Public sector guide and tutorial 46, 2.9.11.g), the same rule (a domain's guide and tutorial).
    # 76: the docs fold (2.9.11.g): the in-memory lake guide became a section of the data lake guide; the cap is the count, so a new
    # listed page needs a page folded first.
    # 80: the 2.9.11.h docs rewrite (builder D): six build and analyse guides fold into five (Reports, Notebook, Approvals, Build, Embed,
    # plus the Developer guide), each beside its tutorial (10, 14, 24, 5, 16) on its row; the tutorial fold of the same release brings it down.
    assert 30 <= total <= 80, total


def test_every_help_page_belongs_to_exactly_one_section():
    """help.yaml: each page once, in one section and one group; a pair names a tutorial of the same group; a section's id is unique
    and every document folder names a section."""
    catalogue = _catalogue()
    ids = [s["id"] for s in catalogue["sections"]]
    assert len(ids) == len(set(ids))
    seen: dict[str, str] = {}
    for s in catalogue["sections"]:
        for grp in s["groups"]:
            slugs = {g["slug"] for g in grp["guides"]}
            for g in grp["guides"]:
                assert g["slug"] not in seen, f"{g['slug']} in {seen.get(g['slug'])} and {s['id']}"
                seen[g["slug"]] = s["id"]
                if g.get("pair"):
                    assert g["pair"] in slugs, (g["slug"], g["pair"])
                    pair = next(x for x in grp["guides"] if x["slug"] == g["pair"])
                    assert pair.get("kind") == "tutorial" and pair.get("engine") == g.get("engine"), g["slug"]
    assert set(catalogue["documents"].values()) <= set(ids)
    for m in catalogue["most-used"]:
        assert m["slug"] in seen, m


def test_the_library_puts_every_page_and_pack_guide_in_one_section(client):
    import asyncio

    from routes.common import library

    class _Req:
        app = client.app
        state = type("S", (), {"identity": None})()

    lib = asyncio.run(library(_Req()))
    listed = [g for c in lib.categories for g in c["guides"]]
    assert len(listed) == len({g.slug for g in listed}), "a page in two sections"
    for c in lib.categories:
        assert all(g.category == c["id"] for g in c["guides"]), c["id"]
        assert [g for grp in c["groups"] for g in grp["guides"]] == c["guides"], c["id"]


def test_the_front_door_has_search_most_used_and_a_tile_per_section(client):
    hub = client.get("/help").text
    assert 'action="/help/search"' in hub and "data-help-q" in hub and "F1" in hub
    most = hub[hub.index("data-help-most"):hub.index("data-help-tiles")]
    assert re.findall(r'href="/help/([\w-]+)"', most) == ["user-guide", "docs-guides-var-guide", "tutorials", "release-notes"]
    assert "What&#39;s new" in most or "What's new" in most
    tiles = [t for t in re.findall(r'href="/help/section/([\w-]+)"', hub) if not t.startswith("module-")]    # a hosted module adds its own tile
    assert tiles == ["get-started", "risk", "analytics", "data", "build", "operate", "reference", "knowledge", "internal"]
    assert "data-help-sections=" in hub and "get-started" in hub[hub.index("data-help-sections="):hub.index("data-help-sections=") + 400]


def test_a_section_page_pairs_each_guide_with_its_tutorial_and_searches_only_itself(client):
    page = client.get("/help/section/risk").text
    assert "Value at risk" in page and "Counterparty" in page and "Capital" in page and ">VAR<" in page
    row = page[page.index('href="/help/docs-guides-var-guide"'):]
    row = row[:row.index("</li>")]
    assert 'href="/help/docs-tutorials-02-var-from-the-bank-to-a-trade"' in row and "data-help-pair" in row
    assert page.count('href="/help/docs-tutorials-02-var-from-the-bank-to-a-trade"') == 1      # the tutorial has no row of its own
    assert 'name="section" value="risk"' in page and "each guide beside its tutorial" in page
    hits = client.get("/help/search", params={"q": "backtesting", "section": "risk"}).text
    found = set(re.findall(r'<li><a href="/help/([\w-]+)"', hits))
    risk = {g["slug"] for g in _pages({"sections": [s for s in _catalogue()["sections"]
                                                    if s["id"] == "risk"]})}
    assert found and found <= risk, found
    assert "data-section-search" in hits and 'href="/help/section/risk"' in hits


def test_an_unknown_section_is_not_found_and_a_guide_links_its_section(client):
    assert client.get("/help/section/no-such-section").status_code == 404
    guide = client.get("/help/docs-guides-var-guide").text
    assert 'href="/help/section/risk">Risk measures</a>' in guide                          # the breadcrumb opens its section
    assert client.get("/help/context/var", follow_redirects=False).headers["location"] == "/help/docs-guides-var-guide"   # F1


def test_a_lens_hides_no_help_page():
    """A lens is a workspace (ADMIN_GUIDE.md 12): it hides nothing, so Help shows inside a lens what the full product shows."""
    from pathlib import Path as _P
    from core.edition import Edition
    from core.guides import Guide
    from routes.help_routes import shown
    page = Guide("p", "P", "", "x", "guide", "analytics", _P("x.md"))
    assert shown(Edition(), page)
    assert shown(Edition("risk", {}, lens=True), page)
    assert shown(Edition("world", {}, lens=True), page)
