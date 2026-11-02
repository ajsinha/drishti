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

from core.backend import BackendError, drs_message
from routes.common import ident, library, render

router = APIRouter(include_in_schema=False)
ROOT = Path(__file__).resolve().parent.parent.parent


def _edition(request):
    return getattr(request.state, "edition", None) or request.app.state.editions.instance


def shown(edition, guide) -> bool:
    """Whether the edition (or the person's lens) keeps a guide (EDITIONS.md › The design as built, section 5.8): its category listed (help.categories), a
    Knowledge page or pack guide about one of its packs (help.knowledge for Knowledge pages, packs.enabled for pack guides), and an
    unlisted document only when the edition does not refuse them (help.unlisted-slugs: refuse). Without an edition: every guide."""
    if not edition.active:
        return True
    cats = edition.help_categories()
    if cats is not None and guide.category not in cats:
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


def visible(request, guide) -> bool:
    """Whether this person, in this edition, sees a guide: the edition keeps it (:func:`shown`) and the guide's ``roles:`` let them
    (help.yaml; ``roles: [admin]`` for an internal page such as the competitive analysis). A guide they may not see is not listed, not
    found by search and not reached by its address (404)."""
    return guide.permits(_me(request)) and shown(_edition(request), guide)


def categories_for(request, lib) -> list:
    ed = _edition(request)
    me = _me(request)
    if not ed.active and all(g.permits(me) for c in lib.categories for g in c["guides"]):
        return lib.categories
    out = []
    for c in lib.categories:
        guides = [g for g in c["guides"] if g.permits(me) and (not ed.active or shown(ed, g))]
        if guides:
            out.append({**c, "guides": guides})
    return out


@router.get("/help")
async def help_index(request: Request):
    lib = await library(request)
    cats = categories_for(request, lib)
    return render(request, "help/index.html", categories=cats, count=sum(len(c["guides"]) for c in cats),
                  screen="help", competitive=_competitive(request, lib))


@router.get("/help/search")
async def help_search(request: Request, q: str = ""):
    return render(request, "help/search.html", q=q, hits=[h for h in (await library(request)).search(q) if visible(request, h["guide"])], screen="help")


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
    category = next(c for c in lib.categories if c["id"] == g.category)
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
    category = next(c for c in lib.categories if c["id"] == g.category)
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
    category = next(c for c in lib.categories if c["id"] == g.category)
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
    if slug not in lib.guides or not visible(request, lib.guides[slug]):   # a guide the edition or the person's roles hide is not reached by its address either
        return render(request, "help/search.html", status_code=404, q=slug.replace("-", " "),
                      hits=[h for h in lib.search(slug.replace("-", " ")) if visible(request, h["guide"])], missing=slug, screen="help")
    g = lib.guides[slug]
    page = lib.render(slug)
    siblings = next(c for c in lib.categories if c["id"] == g.category)
    return render(request, "help/guide.html", guide=g, page=page, category=siblings, screen="help")


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
