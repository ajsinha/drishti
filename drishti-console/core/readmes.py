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

"""A pack's or a connector type's README (the README standard) as HTML for the Admin pages: the server sends the markdown after the
front matter; this renders it the way the help centre renders a guide (tables in ``tbl-wrap``, fenced code), with raw HTML shown as
text (a deployed pack's README is not trusted markup) and links that only go where they should: http(s) and mail links as they are,
a link to a repository document the help centre holds to that help page, any other relative link as plain text."""
from __future__ import annotations

import html
import re
from pathlib import Path

import markdown

_LINK = re.compile(r'<a href="([^"]*)"([^>]*)>(.*?)</a>', re.S)
_SAFE = re.compile(r"^(?:https?:|mailto:)", re.I)


def _md() -> markdown.Markdown:
    md = markdown.Markdown(extensions=["tables", "fenced_code", "sane_lists"])
    md.preprocessors.deregister("html_block")         # raw HTML in the text is shown as text, never run
    md.inlinePatterns.deregister("html")
    return md


def render(text: str, source: Path | None = None, library=None) -> str:
    """``text``: the README's markdown (no front matter). ``source``: where the README sits in the repository, to resolve its
    relative links; ``library``: the help centre (core.guides.Library), whose pages those links may open."""
    body = _md().convert(text or "")
    body = re.sub(r"<h1[^>]*>.*?</h1>", "", body, count=1, flags=re.S)           # the page writes its own heading
    body = re.sub(r"<(/?)h2\b", r"<\1h3", re.sub(r"<(/?)h3\b", r"<\1h4", body))   # under the page's own h2
    body = body.replace("<table>", '<div class="tbl-wrap"><table class="tbl help-tbl">').replace("</table>", "</table></div>")
    body = re.sub(r"<img\b[^>]*>", "", body)

    def link(m: re.Match) -> str:
        href = html.unescape(m.group(1))
        if _SAFE.match(href):
            return f'<a href="{html.escape(href)}" rel="noopener noreferrer" target="_blank">{m.group(3)}</a>'
        if href.startswith("#"):
            return f'<span class="help-repo">{m.group(3)}</span>'
        target = href.partition("#")[0]
        if source is not None and library is not None and target:
            path = (source.parent / target).resolve()
            if path.is_dir() and (path / "README.md").exists():
                path = path / "README.md"
            slug = getattr(library, "by_file", {}).get(path)
            if slug:
                return f'<a href="/help/{html.escape(slug)}">{m.group(3)}</a>'
        return f'<span class="help-repo" title="In the repository: {html.escape(target)}">{m.group(3)}</span>'
    return _LINK.sub(link, body)


def source_of(docs_dir: Path, kind: str, name: str, meta: dict | None = None) -> Path:
    """Where a README sits in the repository: config/packs/<pack>/README.md; plugins/<x>/README.md; docs/connectors/types/<t>/README.md."""
    root = Path(docs_dir).parent
    if kind == "pack":
        return root / "config" / "packs" / name / "README.md"
    if (meta or {}).get("category") == "built-in":
        return root / "docs" / "connectors" / "types" / name / "README.md"
    return root / "plugins" / f"drishti-plugin-{name}" / "README.md"
