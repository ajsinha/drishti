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

Every other Markdown document of the repository (``docs/**`` and the top-level ``*.md``) is reachable too,
unlisted, under a slug made from its path, so a link from one document to another always opens inside the
help centre (DOC-18). A link to a repository file the help centre cannot show (source, ``LICENSE``, a folder
without a README) is shown as its text with the path, not as a link that goes nowhere. Headings get GitHub's
anchors (:class:`GithubSlugger`), so ``#delta--delta-lake`` works on GitHub and in the app alike (DOC-19).
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
_MARKUP = re.compile(r"</?(?:code|em|strong|b|i|a|span|kbd|del|sub|sup)\b[^>]*>")   # inline markup only: "<kind>" in a heading is text
# A screenshot in a guide: fitted to its column by help.css and wrapped in a link that opens it full size (UX-03).
_RELATIVE_SRC = re.compile(r'(<img\b[^>]*?\b)src="((?![a-z][a-z0-9+.-]*:|/|#|data:)[^"]+)"', re.I)
_IMG = re.compile(r'(<a\b[^>]*>\s*)?(<img\b[^>]*?\bsrc="([^"]+)"[^>]*>)')
# A link written in a guide: relative links are resolved against the guide's file (DOC-18).
_LINK = re.compile(r'<a href="([^"]*)"([^>]*)>(.*?)</a>', re.S)
_EXTERNAL = re.compile(r"^(?:[a-z][a-z0-9+.-]*:|/|#)", re.I)
# Where an unlisted repository document sits in the help centre, by its top folder.
_DOC_CATEGORY = {"connectors": "connectors", "admin": "admin", "guides": "start", "architecture": "developers",
                 "qa": "developers", "": "releases"}


def github_slug(text: str) -> str:
    """A heading's anchor as GitHub writes it: lower case, punctuation dropped, each space a hyphen.

    Nothing is collapsed, so "Delta — Delta Lake" is ``delta--delta-lake`` (DOC-19). Letters of any script stay.
    """
    v = html.unescape(_MARKUP.sub("", text)).strip().lower()   # the toc hands over plain text, so "<kind>" stays
    v = re.sub(r"[^\w\- ]", "", v)
    return v.replace(" ", "-")


class GithubSlugger:
    """Unique anchors in one document the way GitHub numbers repeats: ``name``, ``name-1``, ``name-2``."""

    def __init__(self):
        self._seen: dict[str, int] = {}

    def __call__(self, text: str, _separator: str = "-") -> str:
        slug = base = github_slug(text)
        while slug in self._seen:
            self._seen[base] += 1
            slug = f"{base}-{self._seen[base]}"
        self._seen[slug] = 0
        return slug


@dataclass(frozen=True)
class Guide:
    slug: str
    title: str
    summary: str
    icon: str
    kind: str
    category: str
    path: Path
    listed: bool = True


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
        self.moved = dict(data.get("moved", {}))
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
        self.listed = sum(1 for g in self.guides.values() if g.listed)
        self._documents(cats)
        self._cache: dict[str, tuple[float, dict]] = {}

    def _documents(self, cats: dict) -> None:
        """Every repository document not in the catalogue, unlisted, at a slug made from its path (DOC-18)."""
        root = self.docs_dir.parent
        found = sorted(self.docs_dir.rglob("*.md")) + sorted(root.glob("*.md")) if self.docs_dir.is_dir() else []
        for path in found:
            path = path.resolve()
            if path in self.by_file:
                continue
            try:
                rel = path.relative_to(root.resolve())
            except ValueError:
                continue
            slug = re.sub(r"[^a-z0-9]+", "-", str(rel.with_suffix("")).lower()).strip("-")
            if slug in self.guides:
                continue
            top = rel.parts[1] if len(rel.parts) > 2 and rel.parts[0] == self.docs_dir.name else ""
            cat = cats.get(_DOC_CATEGORY.get(top, "developers")) or self.categories[0]
            title = next((ln[2:].strip() for ln in path.read_text(encoding="utf-8").splitlines() if ln.startswith("# ")),
                         rel.stem)
            self.guides[slug] = Guide(slug, title, "", "file-earmark-text", "document", cat["id"], path, listed=False)
            self.by_file[path] = slug

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
                               extension_configs={"toc": {"toc_depth": "2-3", "slugify": GithubSlugger()}})
        body = md.convert(path.read_text(encoding="utf-8"))
        body = _CODE.sub(_figure, body)
        body = re.sub(r'<div class="admonition (\w+)">\s*<p class="admonition-title">',
                      lambda m: f'<div class="help-box {_BOX.get(m.group(1), "concept")}">\n<p class="hb-title">', body)
        body = body.replace("<table>", '<div class="tbl-wrap"><table class="tbl help-tbl">').replace("</table>", "</table></div>")
        body = re.sub(r"<h1[^>]*>.*?</h1>", "", body, count=1, flags=re.S)       # the page template writes the one <h1>
        body = re.sub(r"<(/?)h1\b", r"<\1h2", body)                                  # any other becomes a section heading (UX-18)
        body = _RELATIVE_SRC.sub(lambda m: self._picture(path, m), body)         # a picture beside a guide in docs/ is served at /help/files/
        body = _IMG.sub(_full_size, body)
        body = _LINK.sub(lambda m: self._link(path, m), body)
        return body, md

    def _picture(self, source: Path, m: re.Match) -> str:
        """``<img src="img/x.png">`` in a guide under docs/: the file beside the guide, shown through ``/help/files/``. One that is not there is left
        alone with ``data-unavailable``, so the help-centre crawl and the screenshots test see it."""
        target = (source.parent / html.unescape(m.group(2))).resolve()
        try:
            rel = target.relative_to(self.docs_dir.resolve())
        except ValueError:
            return m.group(0)
        if not target.is_file():
            return f'{m.group(1)}data-unavailable="{m.group(2)}" src="{m.group(2)}"'
        return f'{m.group(1)}src="/help/files/{html.escape(rel.as_posix())}"'

    def fragment(self, path: Path) -> str:
        """One markdown note (an example's) as help HTML, its links resolved like any guide's."""
        return self._html(path)[0]

    def _link(self, source: Path, m: re.Match) -> str:
        """A link between documents opens inside the help centre (an example's note opens on the Examples page); a
        repository file it cannot show is named, not linked.

        A link that resolves to nothing at all keeps ``data-unavailable`` so the help-centre crawl test fails on it.
        """
        href = html.unescape(m.group(1))
        if not href or _EXTERNAL.match(href):
            return m.group(0)
        target, _, anchor = href.partition("#")
        anchor = f"#{anchor}" if anchor else ""
        path = (source.parent / target).resolve()
        if path.is_dir() and (path / "README.md").exists():
            path = path / "README.md"
        slug = self.by_file.get(path)
        if slug:
            return f'<a href="/help/{slug}{html.escape(anchor)}"{m.group(2)}>{m.group(3)}</a>'
        ex = self.guides.get("examples")
        if ex and path.parent == ex.path.parent.resolve() and path.suffix == ".md" and path.exists():
            return f'<a href="/help/examples#{html.escape(path.stem)}"{m.group(2)}>{m.group(3)}</a>'
        if not path.exists():
            if "/" not in target and "." not in target:
                return m.group(0)                    # a console guide's link to another guide by slug: /help/<slug>
            return f'<a href="#" data-unavailable="{html.escape(target)}"{m.group(2)}>{m.group(3)}</a>'
        try:
            shown = path.relative_to(self.docs_dir.parent.resolve())
        except ValueError:
            shown = Path(target)
        return f'<span class="help-repo" title="In the repository: {html.escape(str(shown))}">{m.group(3)}</span>'

    def search(self, q: str, limit: int = 20) -> list[dict]:
        words = [w for w in re.split(r"\W+", q.lower()) if len(w) > 1]
        if not words:
            return []
        hits = []
        for g in self.guides.values():
            if not g.listed and "qa" in g.path.parts:
                continue                             # the QA records are reachable by link, not search
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
