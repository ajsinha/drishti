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

"""The sitemap (config/sitemap.yaml, 2.9.11.f): the top bar's menus, the Admin pages' tab strip, the BI rail, Help's sections, the footer
and the command line's placeholder, as data (docs/admin/EDITIONS.md › The design as built, section 5.4): each item's ``requires:`` and
the edition's (or the person's lens's) ``menus:`` lists decide what one request shows; nothing is kept per person.
Read again when the file changes. The lens items ("Open Drishti Risk", "Leave Drishti Risk") are added here, from the lenses the server
offers. The Engines menu (``engines: true``) has a column per compute engine, drawn for the engines running in the cluster that the person's
role opens (``opens:``); :func:`engine_refused` applies the same rule to the engine's command words (type-ahead and command line)."""
from __future__ import annotations

from pathlib import Path
from urllib.parse import quote

import yaml

from core import engines, under_construction
from core.edition import Edition
from core.edition_gate import feature_on

_cache: dict[str, tuple[int, dict]] = {}


def _spec(console_dir: Path) -> dict:
    f = console_dir / "config" / "sitemap.yaml"
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
    if kind == "cmd":
        return edition.typed_shown(value)
    if kind == "route":
        return _has_route(request, value)
    if kind == "engine":                                         # requires: engine:frtb-sa (COMPUTE_ENGINES.md 5.2)
        return engines.engine_available(value)
    return True


def holds(request, need: str, ctx: dict) -> bool:
    """Whether one sitemap requirement word holds for this request (the Build home's object types use the same words)."""
    return _holds(request, _edition(request), need, ctx)


def _has_route(request, path: str) -> bool:
    """Whether the server answers a page at ``path`` (a menu item for a page this build does not hold is not drawn)."""
    paths = getattr(request.app.state, "menu_route_paths", None)
    if paths is None:
        paths = _route_paths(request.app.routes)
        request.app.state.menu_route_paths = paths
    return path in paths


def _route_paths(routes, prefix: str = "") -> set[str]:
    """Every path the app answers. A FastAPI that keeps an included router whole (0.142: ``_IncludedRouter``, no ``path`` of its own)
    is walked into, with its prefix; an older one lists each route with its path."""
    out: set[str] = set()
    for r in routes:
        inner = getattr(r, "original_router", None)
        if inner is not None:
            ctx = getattr(r, "include_context", None)
            out |= _route_paths(getattr(inner, "routes", []) or [], prefix + str(getattr(ctx, "prefix", "") or "") + str(getattr(inner, "prefix", "") or ""))
        elif getattr(r, "path", None) is not None:
            out.add(prefix + str(r.path))
    return out


def _href(it: dict) -> str:
    """An item's link: its ``href``; else its ``cmd`` run as typed (``/go?q=VAR BANK <GO>``); else its ``fill`` put in the command line for
    the person to finish (``/t?cmd=EXP CPTY``, the top bar's script fills the line in place)."""
    if it.get("href"):
        return str(it["href"])
    if it.get("cmd"):
        return "/go?q=" + quote(f"{it['cmd']} <GO>")
    if it.get("fill"):
        return "/t?cmd=" + quote(str(it["fill"]))
    return "#"


def _menu_order(edition: Edition, menus: list) -> list:
    """The top menus in the order drawn: an edition's (or a lens's) ``menus.top`` names them by id, in order, and a menu it does not name is
    not drawn; without one, every menu of the file in its order except the subject menus (``subject: true``), which only a ``top`` draws."""
    top = edition.menu("top")
    if top is None:
        return [m for m in menus if not m.get("subject") and not m.get("personal")]
    by_id = {str(m.get("id") or ""): m for m in menus}
    return [by_id[i] for i in top if i in by_id]


def _columns(menu: dict, menus: list) -> list:
    """A menu's columns: those of the menus it gathers (``gather: [ids]``, in order: one Markets menu over Curves, Volatility, FX and
    Credit without a second copy of their items), then its own."""
    by_id = {str(m.get("id") or ""): m for m in menus}
    out = [c for g in menu.get("gather") or [] for c in (by_id.get(str(g)) or {}).get("columns") or []]
    return out + list(menu.get("columns") or [])


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
            if (n != current.name or not current.lens) and not engines.lens_hidden(n)]   # a lens whose engine runs nowhere (MR cube)
    return out


def _lens_desc(lens) -> str:
    """A lens link's line under its name: what the edition is about (its description), so two lenses read apart in the menu."""
    about = str((lens.spec.get("description") if lens is not None else "") or "").strip()
    return f"{about}: your own view; nothing is secured by it" if about else "Focus your own view on this edition; nothing is secured by it"


def _engines_menu(request) -> dict:
    """The Engines menu's entry of config/sitemap.yaml (``engines: true``), or {}."""
    return next((m for m in _spec(_console_dir(request)).get("menus") or [] if m.get("engines")), {})


_SIGNED_IN = object()                  # the person of the request (request.state.identity)


def _person_opens(request, rules: list, ident=_SIGNED_IN) -> bool:
    """Whether the person opens an engine whose column says ``opens: rules``: an administrator always; no rules, everyone signed in;
    else any rule holds: ``role:<name>`` (they hold the role) or ``pack:<name>`` (the pack is assigned to them, shown or not). Nobody
    signed in opens none."""
    me = getattr(request.state, "identity", None) if ident is _SIGNED_IN else ident
    if me is None:
        return False
    if me.is_admin or not rules:
        return True
    roles = set(getattr(me, "roles", ()) or ())
    assigned = {str(p.get("name")) for p in getattr(request.state, "pack_switcher", None) or []}
    for rule in rules:
        kind, _, value = str(rule).partition(":")
        if (kind == "role" and value in roles) or (kind == "pack" and value in assigned):
            return True
    return False


def engine_opened(request, engine_id: str, ident=_SIGNED_IN) -> bool:
    """Whether this person's role opens the engine (the Engines menu's ``opens:`` of its column): the one rule of the Engines menu and of
    the engine's command words (:func:`engine_refused`). An engine without a column there opens for everyone. ``ident``:
    the person asked about (default: the request's)."""
    col = next((c for c in _engines_menu(request).get("columns") or [] if str(c.get("engine") or "") == engine_id), None)
    if col is None:
        return True
    return _person_opens(request, list(col.get("opens") or []), ident)


def engine_refused(request, text: str) -> dict | None:
    """The engine (its catalogue entry) whose command words start the typed text and that this person's role does not open, or None:
    the command line refuses it and the type-ahead leaves it out, by the same rule as the Engines menu (:func:`engine_opened`)."""
    e = engines.engine_of(text)
    return e if e is not None and not engine_opened(request, str(e.get("id") or "")) else None


def refusal_for_role(e: dict) -> str:
    """The calm words the command line says when a person types the command of an engine their role does not open."""
    return f"{e.get('title') or e.get('id')} is not opened for your role; ask an administrator."


def suggestions_opened(request, items: list) -> list:
    """The type-ahead's suggestions without those of an engine the person's role does not open (a suggestion's ``complete`` or
    ``mnemonic`` starting with the engine's words)."""
    if not isinstance(items, list):
        return items
    return [s for s in items if not (isinstance(s, dict) and engine_refused(request, str(s.get("complete") or s.get("mnemonic") or "")))]


def _engine_columns(request, edition: Edition, menu: dict, ctx: dict, fill: dict) -> list:
    """The Engines menu's columns for this person: one per engine that runs in the cluster and that their role opens, with the items its
    switches allow; ``[(title, items, word, engine id)]``."""
    out = []
    for col in menu.get("columns") or []:
        eid = str(col.get("engine") or "")
        if not eid or not engines.engine_available(eid) or not _person_opens(request, list(col.get("opens") or [])):
            continue
        items = _items(request, edition, "engines", col, ctx, fill)
        if items:
            out.append((_text(col.get("title"), fill) or engines.title(eid), items, str(col.get("word") or ""), eid))
    return out


def _items(request, edition: Edition, mid: str, col: dict, ctx: dict, fill: dict) -> list:
    items = []
    for it in col.get("items") or []:
        if not all(_holds(request, edition, str(n), ctx) for n in it.get("requires") or []):
            continue
        if not edition.menu_shown(mid, str(it.get("id") or "")) or engines.menu_hidden(str(it.get("id") or "")):
            continue
        uc = under_construction.of(request)                        # docs/admin/UNDER_CONSTRUCTION.md: hidden, or with the chip
        if uc.menu_hidden(str(it.get("id") or "")):
            continue
        items.append((_href(it), it.get("icon"), uc.menu_label(str(it.get("id") or ""), _text(it.get("label"), fill)), _text(it.get("desc"), fill),
                      it.get("match"), str(it.get("cmd") or it.get("fill") or "")))
    return items


def engines_panel(request, ctx: dict) -> dict | None:
    """What the Engines menu's panel draws beyond its columns (mock-up engines-menu/A): the columns with their command words and engine
    ids, how many pages, the search box (more engines than ``search-over``), the Admin -> Engines link and, for an administrator, the
    engines not running (greyed, never links). None when the person opens no running engine (the menu is not drawn)."""
    menu = _engines_menu(request)
    if not menu:
        return None
    edition = _edition(request)
    g = request.app.state.templates.env.globals
    fill = {"product": ctx.get("PRODUCT") or g.get("PRODUCT", ""), "tutorials": g.get("TUTORIALS_WORD", "")}
    columns = _engine_columns(request, edition, menu, ctx, fill)
    if not columns:
        return None
    link = menu.get("admin-link") or {}
    admin = bool(link) and all(_holds(request, edition, str(n), ctx) for n in link.get("requires") or [])
    me = getattr(request.state, "identity", None)
    off = []
    if me is not None and me.is_admin:
        shown = {c[3] for c in columns}
        off = [(engines.title(str(e.get("id"))), str((e.get("words") or [""])[0])) for e in engines.off()
               if e.get("installed") and e.get("words") and str(e.get("id")) not in shown]
    return {"label": _text(menu.get("label"), fill), "columns": [{"title": t, "links": i, "word": w, "engine": e} for t, i, w, e in columns],
            "pages": sum(len(c[1]) for c in columns), "search": len(columns) > int(menu.get("search-over", 4) or 4),
            "admin": {"href": str(link.get("href") or ""), "label": _text(link.get("label"), fill)} if admin else None, "off": off}


def top_bar(request, ctx: dict) -> list:
    """The menus as the template draws them: ``[(label, icon, [(column title, [(href, icon, label, desc, match, cmd)])])]``; ``cmd`` is the
    command shown beside an item's label (its ``cmd`` or ``fill``), blank for an item without one. The Engines menu's columns are its
    running engines that the person opens (:func:`engines_panel` adds the rest of its panel)."""
    edition = _edition(request)
    g = request.app.state.templates.env.globals
    fill = {"product": ctx.get("PRODUCT") or g.get("PRODUCT", ""), "tutorials": g.get("TUTORIALS_WORD", "")}
    out = []
    every = _spec(_console_dir(request)).get("menus") or []
    for menu in _menu_order(edition, every):
        mid = str(menu.get("id") or "")
        if menu.get("engines"):
            columns = [(t, items) for t, items, _w, _e in _engine_columns(request, edition, menu, ctx, fill)]
            if columns:
                out.append((_text(menu.get("label"), fill), menu.get("icon"), columns))
            continue
        columns = []
        for col in _columns(menu, every):                       # an item of a gathered column is filtered as the gathering menu's
            items = _items(request, edition, mid, col, ctx, fill)
            if mid == "views" and col is (menu.get("columns") or [None])[-1]:
                for link in lens_links(request):                # the lenses: one link each, in Views (and on Home)
                    items.append((link["href"], "box-arrow-left" if link["leave"] else "eye", link["label"],
                                  "Back to the full product" if link["leave"] else
                                  _lens_desc(request.app.state.editions.lenses.get(link["name"])), None, ""))
            if items:
                columns.append((_text(col.get("title"), fill), items))
        if columns:
            out.append((_text(menu.get("label"), fill), menu.get("icon"), columns))
    return out


def lens_menu(request, menus: list, product: str = "") -> dict | None:
    """The lens menus' phone form (LX1 option C, docs/lenses/README.md): on a phone the edition's (or lens's) subject menus fold into one
    menu named after the lens, a column per subject, so the bar keeps a few finger-sized buttons; on a wider screen the subject menus
    stand in the bar as before (static/css/drishti_phone.css switches them at 640 px). ``menus`` is :func:`top_bar`'s answer; None without
    subject menus. ``{"label", "subjects": [labels], "columns": [(title, items)]}``."""
    edition = _edition(request)
    if not edition.active or edition.menu("top") is None:
        return None
    subject = {str(m.get("label") or "") for m in _spec(_console_dir(request)).get("menus") or [] if m.get("subject")}
    folded = [(label, [it for _t, items in columns for it in items]) for label, _icon, columns in menus if label in subject]
    folded = [(label, items) for label, items in folded if items]
    if not folded:
        return None
    label = edition.title                                      # "Drishti Risk" -> "Risk": the product's name is already the brand beside it
    words, brand = label.split(), (product or "").split()
    if len(words) > 1 and brand and words[0].lower() == brand[0].lower():
        label = " ".join(words[1:])
    return {"label": label, "title": edition.title, "subjects": [s for s, _ in folded], "columns": folded}


def footer(request) -> list[dict]:
    """The footer's links: each names an item of the menus (``item:``), drawn with that item's address and icon and its own shorter label."""
    edition = _edition(request)
    spec = _spec(_console_dir(request))
    every = item_index(spec)
    out = []
    for f in spec.get("footer") or []:
        it = every.get(str(f.get("item") or ""))
        if it is not None and edition.menu_shown("footer", str(f.get("id") or "")):
            out.append({"id": f.get("id"), "href": _href(it), "icon": it.get("icon"), "label": f.get("label") or it.get("label")})
    return out


# ---- the sitemap's other surfaces: the Admin strip, the BI rail, Help's sections ------------------------------------------------------

def item_index(spec: dict) -> dict[str, dict]:
    """Every item of every menu by its id (the guard holds the ids unique)."""
    return {str(it.get("id")): it for m in spec.get("menus") or [] for c in m.get("columns") or [] for it in c.get("items") or [] if it.get("id")}


def _strip(request, ctx: dict, menu_id: str, rail_only: bool) -> list[dict]:
    """A menu drawn as a strip (the Admin tabs, the BI rail): its items in order, then its ``also:`` items, each as the top bar would
    filter it (requires, the edition's lists, the under-construction registry, engines off). ``[{id, tab, href, icon, label, desc}]``."""
    spec = _spec(_console_dir(request))
    menu = next((m for m in spec.get("menus") or [] if m.get("id") == menu_id), None)
    if menu is None:
        return []
    edition = _edition(request)
    g = request.app.state.templates.env.globals
    fill = {"product": ctx.get("PRODUCT") or g.get("PRODUCT", ""), "tutorials": g.get("TUTORIALS_WORD", "")}
    every = item_index(spec)
    own = [it for c in menu.get("columns") or [] for it in c.get("items") or [] if not (rail_only and it.get("rail") is False)]
    out = []
    for it in own + [every[i] for i in menu.get("also") or [] if i in every]:
        drawn = _items(request, edition, menu_id, {"items": [it]}, ctx, fill)
        if drawn:
            href, icon, label, desc, match, _cmd = drawn[0]
            out.append({"id": str(it.get("id")), "tab": str(it.get("tab") or it.get("id")), "href": href, "icon": icon, "label": label,
                        "desc": desc, "match": match or ""})
    return out


def admin_tabs(request, ctx: dict) -> list[dict]:
    """The tab strip of every Admin page: the Admin menu's items, then its ``also:`` (BI settings, My account)."""
    return _strip(request, ctx, "admin", rail_only=False)


def bi_rail(request, ctx: dict) -> list[dict]:
    """The BI rail: the BI menu's items but those with ``rail: false``, then its ``also:`` (the Pivot, the Build home)."""
    return _strip(request, ctx, "bi", rail_only=True)


def help_sections(console_dir: Path) -> list[dict]:
    """Help's sections in order (``help-sections:``): ``{id, name, icon, blurb, roles, domain}``."""
    return [dict(s) for s in _spec(console_dir).get("help-sections") or [] if isinstance(s, dict) and s.get("id")]


def spec_of(console_dir: Path) -> dict:
    """The sitemap as read (for the guard and the Build home)."""
    return _spec(console_dir)


def placeholder(request) -> str:
    edition = _edition(request)
    if edition.lens and edition.placeholder:
        return edition.placeholder
    return str(request.app.state.settings.get("ui.placeholder") or _spec(_console_dir(request)).get("placeholder") or "")


def _console_dir(request) -> Path:
    return Path(__file__).resolve().parent.parent
