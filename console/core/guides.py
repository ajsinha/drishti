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

"""The help centre's content: a config-driven catalogue (``config/help.yaml``) of guides.

A guide is either a console guide (``web/guides/<slug>.md``: tutorials and how-tos) or a repository
document (``docs/*.md``) rendered as-is, so the in-app reference and the repository docs are the same
text and cannot drift apart. Rendering follows MAYA's help language: copyable captioned examples,
``!!! tip | warning | note`` boxes, reference tables and a table of contents. Rendered guides are cached
until their file changes; search scores every guide's text.
"""
from __future__ import annotations

import html
import re
from dataclasses import dataclass
from pathlib import Path

import markdown
import yaml

_CODE = re.compile(r'<pre><code(?: class="language-([\w+-]+)")?>(.*?)</code></pre>', re.S)
_BOX = {"tip": "tip", "warning": "warn", "danger": "warn", "note": "concept", "info": "concept", "example": "concept"}
_TAG = re.compile(r"<[^>]+>")
# A screenshot in a guide: fitted to its column by help.css and wrapped in a link that opens it full size (UX-03).
_IMG = re.compile(r'(<a\b[^>]*>\s*)?(<img\b[^>]*?\bsrc="([^"]+)"[^>]*>)')


@dataclass(frozen=True)
class Guide:
    slug: str
    title: str
    summary: str
    icon: str
    kind: str
    category: str
    path: Path


class Library:
    """Loads the catalogue once; renders and searches guides on demand. Thread-safe for reads."""

    def __init__(self, console_dir: Path, catalogue: Path, docs_dir: Path, packs: list | None = None):
        self.docs_dir = docs_dir
        data = yaml.safe_load(catalogue.read_text(encoding="utf-8"))
        self.categories = []
        self.guides: dict[str, Guide] = {}
        for cat in data.get("categories", []):
            items = []
            for g in cat.get("guides", []):
                src = g.get("doc")
                path = (docs_dir / src) if src else (console_dir / "web" / "guides" / f"{g['slug']}.md")
                guide = Guide(g["slug"], g["title"], g.get("summary", ""), g.get("icon", "journal-text"),
                              g.get("kind", "reference" if src else "guide"), cat["id"], path)
                if path.exists():
                    self.guides[guide.slug] = guide
                    items.append(guide)
            self.categories.append({"id": cat["id"], "name": cat["name"], "icon": cat.get("icon", "book"),
                                    "blurb": cat.get("blurb", ""), "guides": items})
        cats = {c["id"]: c for c in self.categories}
        self.contextual = dict(data.get("contextual", {}))
        for pack in packs or []:
            for g in pack.get("guides", []):
                if not g["path"].exists() or g["slug"] in self.guides:
                    continue
                cat = cats.get(g.get("category")) or cats.get("start")
                guide = Guide(g["slug"], g["title"], g.get("summary", ""), g.get("icon", "journal-text"),
                              g.get("kind", "guide"), cat["id"], g["path"])
                self.guides[guide.slug] = guide
                if g["slug"] == "getting-started":
                    cat["guides"].insert(0, guide)
                else:
                    cat["guides"].append(guide)
            self.contextual.update({k: v for k, v in pack.get("contextual", {}).items() if v in self.guides})
        self.by_file = {g.path.resolve(): g.slug for g in self.guides.values()}
        self._cache: dict[str, tuple[float, dict]] = {}

    def render(self, slug: str) -> dict:
        g = self.guides[slug]
        mtime = g.path.stat().st_mtime
        hit = self._cache.get(slug)
        if hit and hit[0] == mtime:
            return hit[1]
        body, md = self._html(g.path)
        toc = [{"id": t["id"], "name": t["name"], "children": [{"id": c["id"], "name": c["name"]} for c in t.get("children", [])]}
               for t in md.toc_tokens]
        text = html.unescape(_TAG.sub(" ", body))
        out = {"html": body, "toc": toc, "text": re.sub(r"\s+", " ", text)}
        self._cache[slug] = (mtime, out)
        return out

    def _html(self, path: Path) -> tuple[str, markdown.Markdown]:
        """A markdown file as help HTML: examples, boxes, tables, screenshots and links to other documents."""
        md = markdown.Markdown(extensions=["tables", "fenced_code", "sane_lists", "admonition", "attr_list", "toc"],
                               extension_configs={"toc": {"toc_depth": "2-3"}})
        body = md.convert(path.read_text(encoding="utf-8"))
        body = _CODE.sub(_figure, body)
        body = re.sub(r'<div class="admonition (\w+)">\s*<p class="admonition-title">',
                      lambda m: f'<div class="help-box {_BOX.get(m.group(1), "concept")}">\n<p class="hb-title">', body)
        body = body.replace("<table>", '<div class="tbl-wrap"><table class="tbl help-tbl">').replace("</table>", "</table></div>")
        body = re.sub(r"<h1[^>]*>.*?</h1>", "", body, count=1, flags=re.S)
        body = _IMG.sub(_full_size, body)
        body = re.sub(r'href="([^"#:]+\.md)(#[^"]*)?"', lambda m: self._link(path.parent, m.group(1), m.group(2)), body)
        return body, md

    def fragment(self, path: Path) -> str:
        """One markdown note (an example's) as help HTML, its links resolved like any guide's."""
        return self._html(path)[0]

    def _link(self, base: Path, target: str, anchor: str | None) -> str:
        """Links between documents open inside the help centre; others point at the repository file."""
        path = (base / target).resolve()
        slug = self.by_file.get(path)
        if slug:
            return f'href="/help/{slug}{anchor or ""}"'
        ex = self.guides.get("examples")
        if ex and path.parent == ex.path.parent.resolve() and path.suffix == ".md" and path.exists():
            return f'href="/help/examples#{path.stem}"'
        return f'href="#" data-unavailable="{html.escape(target)}"'

    def search(self, q: str, limit: int = 20) -> list[dict]:
        words = [w for w in re.split(r"\W+", q.lower()) if len(w) > 1]
        if not words:
            return []
        hits = []
        for g in self.guides.values():
            text = self.render(g.slug)["text"]
            low = text.lower()
            score = sum(low.count(w) for w in words) + sum(10 for w in words if w in g.title.lower())
            if score and all(w in low or w in g.title.lower() for w in words):
                i = min((low.find(w) for w in words if low.find(w) >= 0), default=0)
                snippet = text[max(0, i - 80): i + 160].strip()
                hits.append({"guide": g, "score": score, "snippet": snippet})
        hits.sort(key=lambda h: -h["score"])
        return hits[:limit]


def _full_size(m: re.Match) -> str:
    """An image not already inside a link opens full size in a new tab; the guide shows it fitted to the column."""
    if m.group(1):
        return m.group(0)
    return f'<a class="help-shot" href="{m.group(3)}" target="_blank" rel="noopener" title="Open full size">{m.group(2)}</a>'


def _figure(m: re.Match) -> str:
    lang = m.group(1) or "text"
    body = m.group(2)
    first = html.unescape(body).strip().splitlines()[0] if body.strip() else ""
    caption = first[2:].strip() if first.startswith(("# ", "// ")) and len(first) < 80 else lang
    return (f'<figure class="help-example"><figcaption><span><i class="bi bi-terminal" aria-hidden="true"></i> '
            f'{html.escape(caption)}</span><span class="lang">{html.escape(lang)}</span><button type="button" class="copy" '
            f'data-copy aria-label="Copy this example"><i class="bi bi-clipboard" aria-hidden="true"></i> Copy</button>'
            f"</figcaption><pre><code>{body}</code></pre></figure>")
