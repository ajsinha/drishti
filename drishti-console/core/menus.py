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

"""The sitemap (config/sitemap.yaml, 2.9.11.e): the top bar's menus, the Admin pages' tab strip, the BI rail, Help's sections, the footer
and the command line's placeholder, as data (docs/guides/ADMIN_GUIDE.md › The design): each item's ``requires:`` and
the edition's (or the person's lens's) ``menus:`` lists decide what one request shows; nothing is kept per person.
Read again when the file changes. The lens items ("Open Drishti Risk", "Leave Drishti Risk") are added here, from the lenses the server
offers. An engine's pages live in their task's menu (Risk: VaR, exposure, FRTB-SA, SIMM) with ``requires: engine:<id>``: drawn when the
engine runs in the cluster and the person's role opens it (the catalogue's ``opens:``); :func:`engine_refused` applies the same rule to
the engine's command words (type-ahead and command line). Switches and status: Admin -> Engines only."""
from __future__ import annotations

import os
from pathlib import Path
from urllib.parse import quote

import yaml

from core import engines, modules, under_construction
from core.edition import Edition
from core.edition_gate import feature_on

_cache: dict[str, tuple[tuple, dict]] = {}


def modules_dir(console_dir: Path) -> Path:
    """The modules' manifests folder (``DRISHTI_MODULE_MANIFESTS_DIR``, else ``../modules`` beside the console, as its settings say)."""
    d = Path(os.environ.get("DRISHTI_MODULE_MANIFESTS_DIR") or "../modules")
    return d if d.is_absolute() else (console_dir / d).resolve()


def _merged(kernel: list, extra: list) -> list:
    """The kernel's menus with the modules' (their manifests' ``console.menus``) after Views: a module's menu of a new id joins; one of
    an id already there adds its columns to it (the one Markets menu: market-data's, with capital-markets' and economy's columns)."""
    out = [dict(m) for m in kernel]
    at = next((i + 1 for i, m in enumerate(out) if m.get("id") == "views"), 0)
    for m in [m for m in extra if m.get("label")] + [m for m in extra if not m.get("label")]:   # each menu itself, then the columns others add
        have = next((x for x in out if x.get("id") == m.get("id")), None)
        if have is None:
            out.insert(at, dict(m))
            at += 1
        else:
            have["columns"] = list(have.get("columns") or []) + list(m.get("columns") or [])
    return out


def module_active(request, module_id: str | None, domain: str | None = None) -> bool:
    """Whether a module's menu is drawn for this request: the module is in the running distribution, installed (the server lists it, once
    it has answered), loaded and switched on at this site and for the person (core/modules.py ``module_on``, by its domain module). Read per
    request, so a module that is unloaded, removed or switched off takes its menus with it at once, and one loaded or switched on brings them."""
    if not module_id:
        return True
    eds = request.app.state.editions
    d = eds.distribution
    if not d.all and module_id not in d.modules:
        return False
    if eds.server_modules is not None and module_id not in eds.server_modules:
        return False
    return modules.module_on(domain or module_id)


def _active(request, tagged: dict) -> bool:
    return module_active(request, tagged.get("_module"), tagged.get("_domain"))


def _spec(console_dir: Path) -> dict:
    """The sitemap: the kernel's own pages (config/sitemap.yaml) and the modules' menus from their manifests; read again on a change."""
    from core import distribution as _dist
    f = console_dir / "config" / "sitemap.yaml"
    mods = modules_dir(console_dir)
    manifests = sorted(mods.glob("*/module.yaml")) + sorted(mods.glob("*/dmod/module.yaml")) + sorted(mods.glob("*/src/dmod/module.yaml"))
    try:
        stamp = (f.stat().st_mtime_ns, tuple((str(p), p.stat().st_mtime_ns) for p in manifests))
    except OSError:
        return {}
    hit = _cache.get(str(f))
    if hit and hit[0] == stamp:
        return hit[1]
    data = yaml.safe_load(f.read_text(encoding="utf-8")) or {}
    data["menus"] = _merged(data.get("menus") or [], _dist.module_menus(mods))
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
    if kind == "engine":                                         # requires: engine:frtb-sa (ADMIN_GUIDE.md 5.2): it runs and the role opens it
        return engines.engine_available(value) and engine_opened(request, value)
    if kind == "module":                                         # requires: module:banking (ACCESS_MODEL.md: on for this person)
        return modules.module_on(value)
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
    """The top menus in the order drawn. The distribution's ``menus.top`` names them by id, in order (a menu it does not name is not
    drawn); without one, every menu of the file in its order except the subject menus (``subject: true``) and the personal ones (unless
    ``without-lens: true``: Risk, the home of the engines' pages, is in the full product's bar too). A lens only arranges: the menus its
    ``menus.top`` names come first, in its order (a subject menu it names is drawn too, where the distribution names no list), then the
    rest as they were; it never takes a menu out."""
    by_id = {str(m.get("id") or ""): m for m in menus}
    top = edition.filtering.menu("top")
    if top is None:
        base = [m for m in menus if not m.get("personal")]
    else:
        base = [by_id[i] for i in top if i in by_id]
    return [m for m in base if not _in_another(m)]


def _in_another(menu: dict) -> bool:
    """Whether a menu is drawn inside another (never as a top menu of its own): a module's menu is a column of Domains, and a menu
    with ``folded: true`` (BI, Build) is a part of Create."""
    return bool(menu.get("folded") or menu.get("_module"))


def _domain_order(edition: Edition, menus: list) -> list:
    """The module menus of the sitemap in the order Domains draws them: in a lens, those its ``menus.top`` names first, in its order
    (a lens only arranges and never takes a menu out), then the rest as the manifests gave them."""
    gathered = {str(g) for m in menus for g in m.get("gather") or []}          # drawn once, as columns of the menu that gathers them
    mine = [m for m in menus if m.get("_module") and m.get("label") and m.get("id") not in gathered]
    named = (edition.menu("top") or []) if edition.lens else []
    first = [m for i in named for m in mine if m.get("id") == i]
    return first + [m for m in mine if m not in first]


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
    "Leave <title>" first (not for the distribution's starting lens: leaving another returns to it), then "Switch to <title>" for each
    other lens (opening it switches)."""
    editions = request.app.state.editions
    if getattr(request.state, "identity", None) is None:
        return []
    _hook = request.app.state.settings.get("lens.roles") or {}   # who sees the link: role-based access later (read, not wired)
    del _hook
    current = _edition(request)
    out = [{"href": "/lens/leave", "label": f"Leave {current.title}", "leave": True, "name": current.name}] \
        if current.lens and current.name != editions.default_lens else []
    verb = "Switch to" if current.lens else "Open"
    out += [{"href": f"/lens/{n}", "label": f"{verb} {e.title}", "leave": False, "name": n} for n, e in sorted(editions.lenses.items())
            if (n != current.name or not current.lens) and not engines.lens_hidden(n)   # a lens whose engine runs nowhere (MR cube)
            and not _lens_gone(request, n)]                     # or whose module went (KERNEL_AND_MODULES.md 12.3)
    return out


def _lens_gone(request, name: str) -> bool:
    parts = getattr(request.app.state, "module_parts", None)
    return parts is not None and parts.gone_lens(name) is not None


def _lens_desc(lens) -> str:
    """A lens link's line under its name: what the edition is about (its description), so two lenses read apart in the menu."""
    about = str((lens.spec.get("description") if lens is not None else "") or "").strip()
    return f"{about}: your own view; nothing is secured by it" if about else "Focus your own view on this edition; nothing is secured by it"


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
    """Whether this person's role opens the engine (its catalogue entry's ``opens:``, plugins/<module>/engines.yaml): the one rule of the
    engine's pages in the menus (``requires: engine:<id>``) and of its command words (:func:`engine_refused`). An engine without
    ``opens:`` opens for everyone signed in. ``ident``: the person asked about (default: the request's)."""
    e = engines.entry(engine_id)
    return _person_opens(request, list((e or {}).get("opens") or []), ident)


def engine_refused(request, text: str) -> dict | None:
    """The engine (its catalogue entry) whose command words start the typed text and that this person's role does not open, or None:
    the command line refuses it and the type-ahead leaves it out, by the same rule as the engine's pages in the menus (:func:`engine_opened`)."""
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


def _domain_hidden(*tagged: dict) -> bool:
    """The innermost ``domain:`` tag of an item, its column and its menu (core when none says), and whether that module is off."""
    for t in tagged:
        if isinstance(t, dict) and t.get("domain"):
            return modules.tag_hidden(str(t["domain"]))
    return False


def _items(request, edition: Edition, mid: str, col: dict, ctx: dict, fill: dict, menu: dict | None = None) -> list:
    """A column's items this request shows; inside a lens, those its ``menus.<menu>`` names first, in its order (nothing left out)."""
    by_id = {str(it.get("id") or ""): it for it in col.get("items") or []}
    order = edition.arranged(mid, list(by_id)) if edition.lens else list(by_id)
    items = []
    for it in [by_id[i] for i in order]:
        if _domain_hidden(it, col, menu or {}):                    # a domain module that is off: its entries are not drawn
            continue
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


def top_bar(request, ctx: dict) -> list:
    """The menus as the template draws them: ``[(label, icon, [(column title, [(href, icon, label, desc, match, cmd)])])]``; ``cmd`` is the
    command shown beside an item's label (its ``cmd`` or ``fill``), blank for an item without one."""
    edition = _edition(request)
    g = request.app.state.templates.env.globals
    fill = {"product": ctx.get("PRODUCT") or g.get("PRODUCT", ""), "tutorials": g.get("TUTORIALS_WORD", "")}
    out = []
    every = _spec(_console_dir(request)).get("menus") or []
    hosted = _hosted_menus(request)                             # the hosted modules' entries (plugin.yaml menus:), by menu
    for menu in _menu_order(edition, every):
        mid = str(menu.get("id") or "")
        if menu.get("domains"):                                 # Domains: the module menus, each a column of it
            drawn = _domains(request, edition, menu, every, hosted, ctx, fill)
            if drawn is not None:
                out.append(drawn)
            continue
        columns = list(hosted.pop(mid, []))
        if _domain_hidden(menu) or not _active(request, menu):  # a module's menu: only while its module is active (absent otherwise)
            continue
        for col in _columns(menu, every):                       # an item of a gathered column is filtered as the gathering menu's
            if not _active(request, col):                       # a column another module adds to this menu: only while it is active
                continue
            items = _items(request, edition, mid, col, ctx, fill, menu)
            if mid == "views" and col is (menu.get("columns") or [None])[-1]:
                for link in lens_links(request):                # the lenses: one link each, in Views (and on Home)
                    items.append((link["href"], "box-arrow-left" if link["leave"] else "eye", link["label"],
                                  "Back to the full product" if link["leave"] else
                                  _lens_desc(request.app.state.editions.lenses.get(link["name"])), None, ""))
            if items:
                columns.append((_text(col.get("title"), fill), items))
        if columns:
            out.append((_text(menu.get("label"), fill), menu.get("icon"), columns))
    for mid, columns in hosted.items():                         # a module's own menu: its title, after the product's
        out.append((columns[0][0], "puzzle", columns))
    return out


def _domains(request, edition: Edition, menu: dict, every: list, hosted: dict, ctx: dict, fill: dict):
    """The Domains menu, from the manifests alone: a column for each menu a module brought (Blotter, Risk, Markets, Economies ...), drawn
    while its module is active and the person's role and the sitemap's ``requires:`` allow its items. A menu of several columns keeps
    each as ``<menu> · <column>``; one with a single column is titled with the menu. With one domain active the menu is named after it
    (``console.domain-label`` of its manifest, else the domain's name: Risk, Genomics ...), and with none it is not drawn."""
    columns: list = []
    by_title: dict[str, int] = {}
    domains: dict[str, str] = {}
    for m in _domain_order(edition, every):
        if _domain_hidden(m) or not _active(request, m):
            continue
        mid = str(m.get("id") or "")
        own = [(str(c.get("title") or ""), c) for c in _columns(m, every) if _active(request, c)]
        drawn = [(t, _items(request, edition, mid, c, ctx, fill, m)) for t, c in own]
        drawn = [(t, items) for t, items in drawn if items]
        extra = hosted.pop(mid, [])
        label = _text(m.get("label"), fill)
        for t, items in drawn + list(extra):
            title = label if len(drawn) + len(extra) == 1 or not t or t == label else f"{label} · {t}"
            if title in by_title:                                # two modules add a column of one title to a menu: one column
                columns[by_title[title]][1].extend(items)
            else:
                by_title[title] = len(columns)
                columns.append((title, list(items)))
        if drawn or extra:
            d = str(m.get("domain") or m.get("_domain") or mid)
            domains[d] = str(m.get("_domain_label") or domains.get(d) or "")
    for hid in list(hosted):                                    # a hosted module's own menu (plugin.yaml menus:): a column of Domains too
        for title, items in hosted.pop(hid):
            columns.append((title, items))
            domains[hid] = title
    if not columns:
        return None
    if len(domains) == 1:
        (domain, name), = domains.items()
        return (name or domain.replace("-", " ").replace("_", " ").title(), menu.get("icon"), columns)
    return (_text(menu.get("label"), fill), menu.get("icon"), columns)


def _hosted_menus(request) -> dict[str, list]:
    """The hosted modules' menu entries (core/module_parts.py; ``menus:`` of a plugin.yaml format 2), each module a column of the menu its
    entries name (``menu:``; a menu the product lacks is the module's own): ``{menu id: [(title, [item...])]}``. Only modules on are listed,
    so a module that goes takes its entries with it."""
    parts = getattr(request.app.state, "module_parts", None)
    out: dict[str, list] = {}
    for p in parts.snapshot.parts.values() if parts is not None else []:
        if not p.menus or not modules.module_on(p.id):
            continue
        by_menu: dict[str, list] = {}
        for m in p.menus:
            by_menu.setdefault(str(m.get("menu") or p.id), []).append(
                (parts.href(m), m.get("icon") or "puzzle", str(m["label"]), str(m.get("desc") or ""), None, str(m.get("cmd") or "")))
        for mid, items in by_menu.items():
            out.setdefault(mid, []).append((p.title, items))
    return out


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
    from core import modules
    return modules.help_heads([dict(s) for s in _spec(console_dir).get("help-sections") or [] if isinstance(s, dict) and s.get("id")])


def spec_of(console_dir: Path) -> dict:
    """The sitemap as read (for the guard and the Build home)."""
    return _spec(console_dir)


def placeholder(request) -> str:
    edition = _edition(request)
    if edition.lens and edition.placeholder:
        text = edition.placeholder
    else:
        text = str(request.app.state.settings.get("ui.placeholder") or _spec(_console_dir(request)).get("placeholder") or "")
    return shown_examples(text, edition.typed_shown)


def shown_examples(text: str, shown) -> str:
    """The command line's placeholder with only the examples this person may type (``TRD MX-1, ECON CAN …  then <GO>`` with banking
    off: ``ECON CAN …  then <GO>``); none left: blank."""
    head, dots, tail = text.partition("…")
    items = [x.strip() for x in head.split(",") if x.strip()]
    kept = [x for x in items if shown(x)]
    if len(kept) == len(items):
        return text
    return (", ".join(kept) + " " + dots + tail) if kept else ""


def _console_dir(request) -> Path:
    return Path(__file__).resolve().parent.parent
