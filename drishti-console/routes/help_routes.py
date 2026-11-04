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

"""Help centre (catalogue, guides, search) and the About page."""
from __future__ import annotations

from pathlib import Path

from fastapi import APIRouter, Request
from fastapi.responses import RedirectResponse, Response
from markupsafe import Markup

from core import under_construction
from core.backend import BackendError, drs_message
from routes.common import ident, library, render

router = APIRouter(include_in_schema=False)
ROOT = Path(__file__).resolve().parent.parent.parent


def _edition(request):
    return getattr(request.state, "edition", None) or request.app.state.editions.instance


def shown(edition, guide, aliases: dict | None = None) -> bool:
    """Whether the edition (or the person's lens) keeps a guide (EDITIONS.md › The design as built, section 5.8): its section listed (help.categories,
    a section's id or an old name in help.yaml's aliases:), a Knowledge page or pack guide about one of its packs (help.knowledge for Knowledge
    pages, packs.enabled for pack guides), and an unlisted document only when the edition does not refuse them (help.unlisted-slugs: refuse).
    Without an edition: every guide."""
    if not edition.active:
        return True
    cats = edition.help_categories()
    if cats is not None and guide.category not in {(aliases or {}).get(c, c) for c in cats}:
        return False
    if not guide.listed and edition.refuse_unlisted:
        return False
    if guide.packs:
        wanted = edition.knowledge_packs() if guide.category == "knowledge" else edition._get("packs.enabled")
        if isinstance(wanted, list) and not set(guide.packs) & {str(p) for p in wanted}:
            return False
    return True


def _me(request):
    return getattr(request.state, "identity", None)


def _aliases(request) -> dict:
    """help.yaml's aliases: (old section names), from the library in use."""
    lib = next(iter(getattr(request.app.state, "libraries", {}).values()), None)
    return getattr(lib, "aliases", {}) or {}


def visible(request, guide) -> bool:
    """Whether this person, in this edition, sees a guide: the edition keeps it (:func:`shown`), the guide's ``roles:`` let them
    (help.yaml; ``roles: [admin]`` for an internal page such as the competitive analysis) and its engine runs in the cluster (help.yaml
    ``engine:``). Help is documentation, not data: a running engine's pages are open to anyone who can open Help, whatever the Engines
    menu's ``opens:`` say. A guide they may not see is not listed, not found by search and not reached by its address (404)."""
    return guide.permits(_me(request)) and shown(_edition(request), guide, _aliases(request)) and _engine_on(guide)


def _engine_on(guide) -> bool:
    """A guide about a compute engine that runs on no server of the cluster is not shown (help.yaml engine:, the catalogue's help:)."""
    from core import engines
    return (not guide.engine or engines.engine_available(guide.engine)) and not engines.help_hidden(guide.slug)


def status(request, guide) -> str:
    """How a section's page draws a guide: ``on`` (a link), ``off`` (an administrator's greyed row: the guide's engine runs on no server;
    everyone else never sees it) or blank (left out)."""
    if visible(request, guide):
        return "on"
    me = _me(request)
    if (me is not None and me.is_admin and not _engine_on(guide) and guide.permits(me)
            and shown(_edition(request), guide, _aliases(request))):
        return "off"
    return ""


def _section_open(request, section: dict) -> bool:
    """A section with ``roles:`` (Internal) only for an administrator or a person holding one of them."""
    roles = section.get("roles") or ()
    me = _me(request)
    return not roles or (me is not None and (me.is_admin or bool(set(roles) & set(getattr(me, "roles", ()) or ()))))


def sections_for(request, lib) -> list:
    """The sections this person sees, each with only the guides they see (``guides``, ``groups``) and the count of them; a section with
    none left is not drawn (Risk measures while no engine of it runs, for anyone but an administrator)."""
    out = []
    for c in lib.categories:
        if not _section_open(request, c):
            continue
        groups = [{**grp, "guides": [g for g in grp["guides"] if visible(request, g)]} for grp in c["groups"]]
        guides = [g for grp in groups for g in grp["guides"]]
        if guides:
            out.append({**c, "groups": [grp for grp in groups if grp["guides"]], "guides": guides})
    return out


def _engines_line(request, guides) -> dict:
    """The engines a section's pages are about: those running and, for an administrator, those not (its subtitle)."""
    from core import engines
    ids = list(dict.fromkeys(g.engine for g in guides if g.engine))
    me = _me(request)
    on = [engines.title(e) for e in ids if engines.engine_available(e)]
    off = [engines.title(e) for e in ids if not engines.engine_available(e)] if (me is not None and me.is_admin) else []
    return {"on": on, "off": off}


@router.get("/help")
async def help_index(request: Request):
    """The help centre's front door (mock-up help-hub/A): one search box over all of Help, the most used pages and a tile per section
    with its one line and how many pages this person can open in it."""
    from core import engines

    lib = await library(request)
    secs = sections_for(request, lib)
    most = []
    for m in lib.most_used:
        g = lib.guides.get(str(m.get("slug")))
        if g is not None and visible(request, g):
            most.append({"slug": g.slug, "label": str(m.get("label") or g.title), "icon": g.icon})
    hidden = any(lib.guides.get(s) is not None for e in engines.off() for s in e.get("help") or []) or any(
        g.engine and not engines.engine_available(g.engine) for g in lib.guides.values())
    names = {c["id"]: c["id"] for c in lib.categories}
    names.update({k: lib.section_id(k) for k in lib.aliases})
    return render(request, "help/index.html", sections=secs, count=sum(len(c["guides"]) for c in secs), most=most, engines_hidden=hidden,
                  section_names=names, screen="help", competitive=_competitive(request, lib))


@router.get("/help/section/{sid}")
async def help_section(request: Request, sid: str):
    """A section's own page: its pages grouped, every guide beside its tutorial, a search of this section only; an administrator sees
    the pages of an engine that runs nowhere greyed. An old section name leads to the section that took it over."""
    lib = await library(request)
    if sid != lib.section_id(sid) and lib.section(sid) is not None:
        return RedirectResponse(f"/help/section/{lib.section_id(sid)}", status_code=301)
    sec = lib.section(sid)
    if sec is None or not _section_open(request, sec):
        return render(request, "help/search.html", status_code=404, q=sid.replace("-", " "), hits=[], missing=sid, screen="help")
    groups = []
    for grp in sec["groups"]:
        rows = _rows(request, grp["guides"])
        if rows:
            groups.append({**grp, "rows": rows})
    if not groups:
        return render(request, "help/search.html", status_code=404, q=sid.replace("-", " "), hits=[], missing=sid, screen="help")
    count = sum(1 for grp in groups for r in grp["rows"] for x in (r["guide"], r["pair"]) if x is not None and r["status"] == "on")
    return render(request, "help/section.html", section=sec, groups=groups, count=count,
                  paired=any(r["pair"] for grp in groups for r in grp["rows"]),
                  engines_line=_engines_line(request, sec["guides"]), screen="help")


def _rows(request, guides) -> list:
    """A group's rows: each guide with its tutorial beside it (``pair:``); a tutorial drawn beside its guide has no row of its own."""
    st = {g.slug: status(request, g) for g in guides}
    paired = {g.pair for g in guides if g.pair and st.get(g.slug) and st.get(g.pair)}
    by_slug = {g.slug: g for g in guides}
    rows = []
    for g in guides:
        if not st[g.slug] or g.slug in paired:
            continue
        pair = by_slug.get(g.pair) if g.pair in paired else None
        rows.append({"guide": g, "pair": pair, "status": st[g.slug]})
    return rows


@router.get("/help/search")
async def help_search(request: Request, q: str = "", section: str = ""):
    """Search all of Help, or with ``section`` that section's pages only (its page's search box)."""
    lib = await library(request)
    sec = lib.section(section) if section else None
    if sec is not None and not _section_open(request, sec):
        sec = None
    hits = [h for h in lib.search(q, section=sec["id"] if sec else "") if visible(request, h["guide"])]
    return render(request, "help/search.html", q=q, hits=hits, section=sec, screen="help")


@router.get("/help/configuration")
async def help_configuration(request: Request, q: str = "", area: str = ""):
    """Help → Configuration: docs/admin/CONFIGURATION.md, searchable by key, environment variable or words, filtered by area; an
    administrator also sees the values in effect on the server and in this console, and where each came from (core/settings_page.py)."""
    from core.settings_page import areas, console_effective, searchable, server_effective

    lib = await library(request)
    g = lib.guides.get("configuration")
    if g is None or not visible(request, g):
        return await help_guide(request, "configuration")
    page = lib.render("configuration")
    me = ident(request)
    admin = bool(me is not None and me.is_admin)
    server = console = None
    server_error = None
    if admin:
        try:
            server = server_effective(await request.app.state.backend.admin("GET", "/settings", me))
        except BackendError as e:
            server, server_error = {}, drs_message(e.code, e.detail, " ")
        console = console_effective(request.app.state.settings, ROOT / "drishti-console" / "config")
    body, matched, total = searchable(page["html"], q, area, server, console)
    category = _siblings(request, lib, g)
    return render(request, "help/configuration.html", guide=g, page={**page, "html": body}, category=category, q=q, area=area,
                  areas=areas(page["html"]), matched=matched, total=total, admin=admin, server_error=server_error, screen="help")


@router.get("/what")
async def what(request: Request, q: str = ""):
    """``WHAT`` (routes/terminal_routes.py): alone the Knowledge home; with a term (its long name or a synonym, any case) the term's
    glossary entry on its Knowledge page; with other words a search of the Knowledge pages (docs/knowledge/README.md)."""
    from core.knowledge import CATEGORY, HOME

    lib = await library(request)
    folder = (request.app.state.docs_dir / "knowledge").resolve()
    home = lib.by_file.get(folder / HOME)
    words = " ".join(q.split())
    if not words:
        return RedirectResponse(f"/help/{home}" if home else "/help", status_code=303)
    entry = request.app.state.glossary.find(words)
    slug = lib.by_file.get(folder / entry.page) if entry else None
    if entry and slug:
        return RedirectResponse(f"/help/{slug}#{entry.anchor}", status_code=303)
    hits = [h for h in lib.search(words) if h["guide"].category == CATEGORY and visible(request, h["guide"])]
    return render(request, "help/search.html", q=words, hits=hits, knowledge=home or "", screen="help")


@router.get("/help/context/{screen}")
async def help_context(request: Request, screen: str):
    lib = await library(request)
    slug = lib.contextual.get(screen) or lib.contextual.get("help") or "using-the-terminal"
    return RedirectResponse(f"/help/{slug}", status_code=303)


@router.get("/help/examples")
async def help_examples(request: Request):
    """The Rachana examples: each one's note, YAML and JSON (collapsible) and a link that opens it in Studio."""
    lib = await library(request)
    g = lib.guides.get("examples")
    if g is None:
        return await help_guide(request, "examples")
    items = [{"name": e.name, "title": e.title, "yaml": e.yaml, "json": e.json,
              "note": lib.fragment(request.app.state.examples.directory / f"{e.name}.md")} for e in request.app.state.examples.all()]
    body = request.app.state.templates.get_template("help/_examples.html").render(examples=items)
    page = {"html": body, "toc": [{"id": e["name"], "name": e["title"], "children": []} for e in items]}
    category = _siblings(request, lib, g)
    return render(request, "help/guide.html", guide=g, page=page, category=category, screen="help")


_PICTURES = {".png": "image/png", ".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".svg": "image/svg+xml", ".gif": "image/gif", ".webp": "image/webp"}


@router.get("/help/files/{path:path}")
async def help_file(request: Request, path: str):
    """A picture a guide in ``docs/`` shows (``docs/guides/img/designer/...``), served from the docs folder and nothing else: only picture
    types, only inside the folder, so a guide can carry screenshots without the console serving the repository."""
    docs = request.app.state.docs_dir.resolve()
    target = (docs / path).resolve()
    if target.suffix.lower() not in _PICTURES or docs not in target.parents or not target.is_file():
        return Response(status_code=404)
    return Response(target.read_bytes(), media_type=_PICTURES[target.suffix.lower()], headers={"Cache-Control": "public, max-age=300"})


@router.get("/help/developer")
async def help_developer(request: Request):
    """Help -> Developer (IMPLEMENTATION_PLAN row 2g): the REST API (OpenAPI), the renderer's API, and the Java and Python API references
    built by tools/api_docs.py (core/api_reference.py), each version the folder holds; the guide web/guides/developer.md below them."""
    from core import api_reference
    from core import render_dist

    lib = await library(request)
    g = lib.guides.get("developer")
    if g is None or not visible(request, g):
        return await help_guide(request, "developer")
    folder = request.app.state.api_docs_dir
    built = api_reference.versions(folder)
    rel = folder.relative_to(ROOT) if ROOT in folder.parents else folder
    openapi = str(request.app.state.settings.get("help.openapi_url", "") or "")
    top = request.app.state.templates.get_template("help/_developer.html").render(
        built=built, folder=str(rel), openapi=openapi, renderer=render_dist.VERSION)
    page = lib.render("developer")
    category = _siblings(request, lib, g)
    return render(request, "help/guide.html", guide=g, page={**page, "html": top + page["html"]}, category=category, screen="help")


@router.get("/help/reference/{path:path}")
async def help_api_file(request: Request, path: str):
    """A file of a built API reference (``/help/reference/<version>/java/drishti-api/index.html``), from the references' folder and nothing else."""
    from core import api_reference

    found = api_reference.file(request.app.state.api_docs_dir, path)
    if found is None:
        return Response("Not built: run python3 tools/api_docs.py build (see Help -> Developer).", status_code=404, media_type="text/plain")
    body, media = found
    return Response(body, media_type=media, headers={"Cache-Control": "public, max-age=300"})


@router.get("/help/{slug}")
async def help_guide(request: Request, slug: str):
    lib = await library(request)
    if slug not in lib.guides and lib.moved.get(slug, "").partition("#")[0] in lib.guides:
        return RedirectResponse(f"/help/{lib.moved[slug]}", status_code=301)       # permanent: the address was retired, the target may carry an #anchor
    uc = under_construction.of(request)                              # docs/admin/UNDER_CONSTRUCTION.md: a hidden page is not reached either
    if slug not in lib.guides or not visible(request, lib.guides[slug]) or uc.help_hidden(slug):   # a guide the edition or the person's roles hide is not reached by its address either
        return render(request, "help/search.html", status_code=404, q=slug.replace("-", " "),
                      hits=[h for h in lib.search(slug.replace("-", " ")) if visible(request, h["guide"])], missing=slug, screen="help")
    g = lib.guides[slug]
    page = lib.render(slug)
    if uc.help_marked(slug):
        page = {**page, "html": Markup('<p class="drishti_uc-banner">') + under_construction.CHIP
                + Markup(" What this page describes depends on work not built yet; its figures are not shown in the console.</p>") + page["html"]}
    return render(request, "help/guide.html", guide=g, page=page, category=_siblings(request, lib, g), screen="help")


def _siblings(request, lib, guide) -> dict:
    """A guide's section for its breadcrumb and side list: the section with only the pages this person sees."""
    sec = next(c for c in lib.categories if c["id"] == guide.category)
    return {**sec, "guides": [g for g in sec["guides"] if visible(request, g)]}


def _competitive(request, lib) -> bool:
    """The one competitive analysis (help.yaml slug competitive-analysis) is linked only for those who may open it: administrators."""
    g = lib.guides.get("competitive-analysis")
    return bool(g and visible(request, g))


@router.get("/about/competitive")
async def competitive(request: Request):
    """One competitive analysis: the Help guide docs/strategy/COMPETITIVE_ANALYSIS.md (administrators only); the old address leads there."""
    return RedirectResponse("/help/competitive-analysis", status_code=301)


@router.get("/about")
async def about(request: Request):
    info = {}
    try:
        info = await request.app.state.backend.about(getattr(request.state, "identity", None) or request.app.state.auth.service())
    except BackendError as e:
        info = {"error": drs_message(e.code, e.detail, " ")}
    lib = await library(request)
    return render(request, "help/about.html", info=info, notices=lib.guides.get("third-party-notices"), screen="about",
                  competitive=_competitive(request, lib))
