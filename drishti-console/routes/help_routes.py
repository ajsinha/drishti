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


@router.get("/help")
async def help_index(request: Request):
    lib = await library(request)
    return render(request, "help/index.html", categories=lib.categories, count=lib.listed, screen="help")


@router.get("/help/search")
async def help_search(request: Request, q: str = ""):
    return render(request, "help/search.html", q=q, hits=(await library(request)).search(q), screen="help")


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
    hits = [h for h in lib.search(words) if h["guide"].category == CATEGORY]
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


@router.get("/help/{slug}")
async def help_guide(request: Request, slug: str):
    lib = await library(request)
    if slug not in lib.guides and lib.moved.get(slug, "").partition("#")[0] in lib.guides:
        return RedirectResponse(f"/help/{lib.moved[slug]}", status_code=301)       # permanent: the address was retired, the target may carry an #anchor
    if slug not in lib.guides:
        return render(request, "help/search.html", status_code=404, q=slug.replace("-", " "),
                      hits=lib.search(slug.replace("-", " ")), missing=slug, screen="help")
    g = lib.guides[slug]
    page = lib.render(slug)
    siblings = next(c for c in lib.categories if c["id"] == g.category)
    return render(request, "help/guide.html", guide=g, page=page, category=siblings, screen="help")


@router.get("/about/competitive")
async def competitive(request: Request):
    import yaml

    data = yaml.safe_load((ROOT / "drishti-console" / "config" / "competitive.yaml").read_text(encoding="utf-8"))
    return render(request, "help/competitive.html", c=data, screen="about")


@router.get("/about")
async def about(request: Request):
    info = {}
    try:
        info = await request.app.state.backend.about(getattr(request.state, "identity", None) or request.app.state.auth.service())
    except BackendError as e:
        info = {"error": drs_message(e.code, e.detail, " ")}
    return render(request, "help/about.html", info=info, notices=(await library(request)).guides.get("third-party-notices"), screen="about")
