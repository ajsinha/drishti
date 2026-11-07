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

"""The sitemap guard (2.9.11.e, config/sitemap.yaml): every page the console answers is reachable from the sitemap (a menu item, or an
in-context page reached from one), nothing is listed twice, every reference resolves, Help's sections are the sitemap's, and banking's
entries say so (the domain split moves them)."""
from __future__ import annotations

from collections import Counter
from urllib.parse import urlsplit

import pytest
import yaml

from conftest import CONSOLE
from core.app import create_app
from core.build_registry import loaded
from core.config import load_settings

SPEC = yaml.safe_load((CONSOLE / "config" / "sitemap.yaml").read_text(encoding="utf-8"))
HELP = yaml.safe_load((CONSOLE / "config" / "help.yaml").read_text(encoding="utf-8"))
BANKING_WORDS = {"VAR", "EXP", "FRSA", "SIMM", "SMAT", "MCUBE", "SWIN", "VRES", "VOFF", "HIER", "VLIM", "TVAL", "PNLV", "DPNL", "PNL",
                 "SENS", "CPTY", "NSET", "LIM", "XWIF", "CCUBE", "TRD", "CTRD", "PTRD", "LTRD", "BOOK", "DESK", "TRDR", "POS"}


def _items():
    """(menu, column, item) for every item, with the domain it inherits."""
    for m in SPEC["menus"]:
        for c in m.get("columns") or []:
            for it in c.get("items") or []:
                yield m, c, it


def _domain(m, c, it) -> str:
    return str(it.get("domain") or c.get("domain") or m.get("domain") or "core")


def _target(it) -> str:
    return str(it.get("href") or ("cmd:" + it["cmd"] if it.get("cmd") else "fill:" + str(it.get("fill") or "")))


ROUTES: set[str] = set()


@pytest.fixture(scope="module")
def pages() -> set[str]:
    """Every address the console answers with GET that a person could open: no path parameter, no /api, /static or /embed. Every
    GET route with its parameters is kept in ROUTES (an item's link must reach one)."""
    app = create_app(load_settings(CONSOLE / "config"))
    out = ROUTES

    def walk(routes, prefix=""):
        for r in routes:
            inner = getattr(r, "original_router", None)
            if inner is not None:
                walk(getattr(inner, "routes", []) or [], prefix + str(getattr(getattr(r, "include_context", None), "prefix", "") or ""))
            elif getattr(r, "path", None) is not None and "GET" in (getattr(r, "methods", None) or set()):
                out.add(prefix + r.path)

    walk(app.routes)
    return {p for p in out if "{" not in p and "/api/" not in p and not p.startswith(("/static/", "/embed", "/elements", "/v/", "/phone"))
            and not p.endswith((".xlsx", ".csv", ".json", ".zip", ".js", ".css"))}


def test_no_item_id_is_used_twice():
    counts = Counter(str(it.get("id")) for _m, _c, it in _items())
    assert not [i for i, n in counts.items() if n > 1], counts


def test_no_address_is_listed_twice_outside_the_lens_subject_menus():
    """A lens's subject menus (drawn only inside that lens, instead of Views) may repeat a command; the product's own menus may not."""
    drawn = [_target(it) for m, _c, it in _items() if not m.get("subject")]
    twice = [t for t, n in Counter(drawn).items() if n > 1]
    assert not twice, f"listed twice: {twice} (list it once; another menu draws it with also:)"


def test_each_lens_draws_no_address_twice():
    for f in sorted((CONSOLE.parent / "config" / "editions").glob("*.yaml")):
        top = ((yaml.safe_load(f.read_text(encoding="utf-8")) or {}).get("menus") or {}).get("top")
        if not top:
            continue
        drawn = [_target(it) for m, _c, it in _items() if m["id"] in top and not m.get("engines")]
        twice = [t for t, n in Counter(drawn).items() if n > 1]
        assert not twice, (f.name, twice)


def test_every_reference_resolves():
    ids = {str(it.get("id")) for _m, _c, it in _items()}
    for m in SPEC["menus"]:
        assert set(m.get("also") or []) <= ids, (m["id"], m.get("also"))
    assert {f["item"] for f in SPEC["footer"]} <= ids
    assert [s["id"] for s in SPEC["help-sections"]] == [s["id"] for s in HELP["sections"]], "Help's sections are the sitemap's, in its order"
    assert not any(k in s for s in HELP["sections"] for k in ("name", "icon", "blurb", "roles")), "a section's head lives in the sitemap only"


def test_every_page_is_reachable_from_the_sitemap(pages):
    listed = {urlsplit(str(it["href"])).path for _m, _c, it in _items() if it.get("href")}
    listed |= {str(it["match"]) for _m, _c, it in _items() if it.get("match") and (it.get("cmd") or it.get("fill"))}   # a command's page
    context = {str(k) for k in SPEC["in-context"]}
    not_pages = {str(p) for p in SPEC["not-pages"]}
    unreached = sorted(pages - listed - context - not_pages)
    assert not unreached, f"pages no menu reaches: {unreached} (list them in config/sitemap.yaml, or under in-context: with the page they open from)"
    stale = sorted((context | not_pages) - pages)
    assert not stale, f"in-context or not-pages addresses the console no longer answers: {stale}"
    assert not (context & listed), f"in-context pages also listed in a menu: {sorted(context & listed)}"


def test_every_item_links_to_a_page_the_console_answers(pages):
    import re

    patterns = [re.compile("^" + re.sub(r"\\{[^}]+\\}", "[^/]+", re.escape(r)) + "$") for r in ROUTES]
    dead = [str(it["href"]) for _m, _c, it in _items() if it.get("href")
            if not any(p.match(urlsplit(str(it["href"])).path) for p in patterns)]
    assert not dead, f"items whose page the console does not answer: {dead}"


def test_banking_entries_are_tagged_for_the_domain_split():
    for m, c, it in _items():
        needs = {str(r).split(":", 1)[1] for r in it.get("requires") or [] if str(r).startswith(("word:", "cmd:"))}
        if needs & BANKING_WORDS or c.get("engine"):
            assert _domain(m, c, it) == "banking", f"{it['id']}: a banking entry without domain: banking"
    assert next(s for s in SPEC["help-sections"] if s["id"] == "risk").get("domain") == "banking"


def test_the_build_registry_is_one_list_by_domain(pages):
    reg = loaded()
    assert len({t.id for t in reg.types()}) == len(reg.types())
    assert reg.domains()["banking"] == ["var-baskets"] and "banking" not in {t.domain for t in reg.types() if t.id != "var-baskets"}
    for t in reg.types():
        if t.new:
            assert urlsplit(t.new).path in pages, (t.id, t.new)
