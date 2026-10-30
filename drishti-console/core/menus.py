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

"""The top bar's menus, the footer and the command line's placeholder, as data (config/menus.yaml; docs/architecture/EDITIONS.md,
section 5.4): each item's ``requires:`` and the edition's (or the person's lens's) ``menus:`` lists decide what one request shows.
Read again when the file changes. The lens items ("Open Drishti Risk", "Leave Drishti Risk") are added here, from the lenses the server
offers."""
from __future__ import annotations

from pathlib import Path

import yaml

from core.edition import Edition
from core.edition_gate import feature_on

_cache: dict[str, tuple[int, dict]] = {}


def _spec(console_dir: Path) -> dict:
    f = console_dir / "config" / "menus.yaml"
    try:
        stamp = f.stat().st_mtime_ns
    except OSError:
        return {}
    hit = _cache.get(str(f))
    if hit and hit[0] == stamp:
        return hit[1]
    data = yaml.safe_load(f.read_text(encoding="utf-8")) or {}
    _cache[str(f)] = (stamp, data)
    return data


def _holds(request, edition: Edition, need: str, ctx: dict) -> bool:
    me = ctx.get("me")
    bi_me = ctx.get("bi_me") or {}
    if need == "admin":
        return bool(me and me.is_admin)
    if need == "bi":
        return bool(bi_me.get("enabled"))
    if need == "bi-admin":
        return "bi-admin" in (bi_me.get("powers") or [])
    if need == "designer":
        return bool(request.app.state.settings.get("ui.designer", False))
    if need == "edition":
        return edition.active
    kind, _, value = need.partition(":")
    if kind == "feature":
        return feature_on(request, edition, value)
    if kind == "word":
        return edition.word_on(value)
    if kind == "admin-page":
        return edition.admin_page_shown(value)
    if kind == "pack":
        return value in {p.get("name") for p in (ctx.get("pack_switcher") or []) if p.get("active", True)}
    return True


def _text(s, fill: dict) -> str:
    out = str(s or "")
    for k, v in fill.items():
        out = out.replace("{" + k + "}", str(v))
    return out


def _edition(request) -> Edition:
    return getattr(request.state, "edition", None) or request.app.state.editions.instance


def lens_links(request) -> list[dict]:
    """The lens links for this person: "Open <title>" for each lens the server offers (drishti.lenses: risk,world gives two); inside one,
    "Leave <title>" first, then "Switch to <title>" for each other lens (opening it switches); none with an edition."""
    editions = request.app.state.editions
    if editions.instance.active or getattr(request.state, "identity", None) is None:
        return []
    _hook = request.app.state.settings.get("lens.roles") or {}   # who sees the link: role-based access later (read, not wired)
    del _hook
    current = _edition(request)
    out = [{"href": "/lens/leave", "label": f"Leave {current.title}", "leave": True, "name": current.name}] if current.lens else []
    verb = "Switch to" if current.lens else "Open"
    out += [{"href": f"/lens/{n}", "label": f"{verb} {e.title}", "leave": False, "name": n} for n, e in sorted(editions.lenses.items())
            if n != current.name or not current.lens]
    return out


def _lens_desc(lens) -> str:
    """A lens link's line under its name: what the edition is about (its description), so two lenses read apart in the menu."""
    about = str((lens.spec.get("description") if lens is not None else "") or "").strip()
    return f"{about}: your own view; nothing is secured by it" if about else "Focus your own view on this edition; nothing is secured by it"


def top_bar(request, ctx: dict) -> list:
    """The menus as the template draws them: ``[(label, icon, [(column title, [(href, icon, label, desc, match)])])]``."""
    edition = _edition(request)
    g = request.app.state.templates.env.globals
    fill = {"product": ctx.get("PRODUCT") or g.get("PRODUCT", ""), "tutorials": g.get("TUTORIALS_WORD", "")}
    out = []
    for menu in _spec(_console_dir(request)).get("menus") or []:
        mid = str(menu.get("id") or "")
        columns = []
        for col in menu.get("columns") or []:
            items = []
            for it in col.get("items") or []:
                if not all(_holds(request, edition, str(n), ctx) for n in it.get("requires") or []):
                    continue
                if not edition.menu_shown(mid, str(it.get("id") or "")):
                    continue
                items.append((it.get("href"), it.get("icon"), _text(it.get("label"), fill), _text(it.get("desc"), fill), it.get("match")))
            if mid == "views" and col is (menu.get("columns") or [None])[-1]:
                for link in lens_links(request):                # the lenses: one link each, in Views (and on Home)
                    items.append((link["href"], "box-arrow-left" if link["leave"] else "eye", link["label"],
                                  "Back to the full product" if link["leave"] else
                                  _lens_desc(request.app.state.editions.lenses.get(link["name"])), None))
            if items:
                columns.append((_text(col.get("title"), fill), items))
        if columns:
            out.append((_text(menu.get("label"), fill), menu.get("icon"), columns))
    return out


def footer(request) -> list[dict]:
    edition = _edition(request)
    return [i for i in _spec(_console_dir(request)).get("footer") or [] if edition.menu_shown("footer", str(i.get("id") or ""))]


def placeholder(request) -> str:
    edition = _edition(request)
    if edition.lens and edition.placeholder:
        return edition.placeholder
    return str(request.app.state.settings.get("ui.placeholder") or _spec(_console_dir(request)).get("placeholder") or "")


def _console_dir(request) -> Path:
    return Path(__file__).resolve().parent.parent
