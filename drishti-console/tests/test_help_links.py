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

"""Every link in the help centre goes somewhere (DOC-18), and headings get GitHub's anchors (DOC-19).

The crawl starts at /help, follows every in-help link to every guide it reaches, and fails on a link the help
centre cannot open, a guide that does not render, or an anchor the target page does not have.
"""
import html
import re

_HREF = re.compile(r'<a\b[^>]*\bhref="([^"]*)"[^>]*>')
_UNAVAILABLE = re.compile(r'data-unavailable="([^"]*)"')
_ID = re.compile(r'\bid="([^"]+)"')


def _crawl(client):
    pages, todo, dead = {}, ["/help"], []
    while todo:
        path = todo.pop()
        if path in pages:
            continue
        r = client.get(path)
        pages[path] = r
        if r.status_code != 200:
            continue
        for tag in _HREF.finditer(r.text):
            gone = _UNAVAILABLE.search(tag.group(0))
            if gone:
                dead.append(f"{path}: {html.unescape(gone.group(1))}")
                continue
            target = html.unescape(tag.group(1)).split("#")[0]
            if target.startswith("/help/") and not target.startswith(("/help/search", "/help/context")) and target not in pages:
                todo.append(target)
    return pages, dead


def test_every_link_in_the_help_centre_opens_a_guide_and_every_anchor_exists(client):
    pages, dead = _crawl(client)
    assert not dead, f"{len(dead)} in-help links go nowhere:\n" + "\n".join(sorted(dead))
    bad = [p for p, r in pages.items() if r.status_code != 200]
    assert not bad, f"guides that do not open: {bad}"
    ids = {p: set(_ID.findall(r.text)) for p, r in pages.items()}
    broken = []
    for p, r in pages.items():
        for tag in _HREF.finditer(r.text):
            href = html.unescape(tag.group(1))
            if "#" not in href:
                continue
            target, anchor = href.split("#", 1)
            target = target or p
            if anchor and target.startswith("/help/") and target in ids and anchor not in ids[target]:
                broken.append(f"{p}: {href}")
    assert not broken, f"{len(broken)} anchors the target guide does not have:\n" + "\n".join(sorted(broken))
    assert len(pages) >= 40


def test_headings_get_the_same_anchors_as_on_github():
    from core.guides import github_slug

    assert github_slug("Delta — Delta Lake") == "delta--delta-lake"
    assert github_slug("<code>drishti.pivot</code> — the Pivot tab") == "drishtipivot--the-pivot-tab"
    assert github_slug("Step 13 · (optional) Turn on sign-in") == "step-13--optional-turn-on-sign-in"
    assert github_slug("Alt+C · Calc, Python on a view") == "altc--calc-python-on-a-view"
    assert github_slug("Waves 12–21 · capability roadmap after 1.10") == "waves-1221--capability-roadmap-after-110"
    assert github_slug("Rates &amp; FX") == "rates--fx"
    assert github_slug("Ünïcode Straße_x") == "ünïcode-straße_x"


def test_a_double_hyphen_anchor_from_a_repository_document_works_in_the_app(client):
    assert 'id="delta--delta-lake"' in client.get("/help/configuration").text          # the heading "`delta` — Delta Lake"
    linked = 0
    for slug in ("windows", "delta-connector"):
        page = client.get(f"/help/{slug}")
        if page.status_code != 200:
            continue
        hrefs = set(re.findall(r'href="(/help/[^"#]+)#delta--delta-lake"', page.text))
        for h in hrefs:
            linked += 1
            assert 'id="delta--delta-lake"' in client.get(h).text, h
    assert linked, "a repository document's link to the Delta section opens in the app"
