#!/usr/bin/env python3
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

"""The PDF copies of the top-level guides: a Markdown file and its pictures as one printable PDF, with nothing fetched from the network.

The Markdown is rendered by Python-Markdown with the help centre's extensions (tables, fenced code, the same heading anchors), the pictures
are inlined as data: URLs, and Chromium (Playwright's, which the browser tests already install) prints it with page.pdf(). The PDF's title
carries a fingerprint of the source (the Markdown and every picture it shows), so a check needs no browser:

    drishti-console/.venv/bin/python tools/docs/md_to_pdf.py                    # every guide of PDFS, rewritten
    drishti-console/.venv/bin/python tools/docs/md_to_pdf.py --check            # exit 1 when a PDF is older than its Markdown or pictures
    drishti-console/.venv/bin/python tools/docs/md_to_pdf.py docs/X.md out.pdf  # any one file

The product name and the copyright line come from the console's settings (ui.product, ui.copyright), never from this file.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import html
import mimetypes
import os
import re
import sys
from pathlib import Path

import markdown
import yaml

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "drishti-console"))
from core.guides import GithubSlugger  # noqa: E402  (the help centre's heading anchors, so a link inside the PDF lands where Help's does)

PDFS = [ROOT / "docs" / "DEPLOYMENT_AND_QA_GUIDE.md", ROOT / "docs" / "QUICKSTART_DEVELOPERS.md", ROOT / "docs" / "guides" / "CLUSTER_GUIDE.md",
        ROOT / "docs" / "architecture" / "KERNEL_AND_MODULES.md", ROOT / "HOW_IT_FITS.md"]   # generated and checked together
PICTURE = re.compile(r"!\[[^\]]*\]\(([^)\s]+)\)")
MARK = "source "                                                    # the PDF's title ends with "source <fingerprint>"
COMMENT = re.compile(r"<!--.*?-->", re.S)

CSS = """
@page { size: A4; margin: 16mm 14mm 18mm 14mm; }
body { font-family: "DejaVu Sans", "Segoe UI", Arial, sans-serif; font-size: 9.6pt; line-height: 1.45; color: #1d2430; }
h1 { font-size: 20pt; margin: 0 0 6pt; color: #12324f; }
h2 { font-size: 14pt; margin: 18pt 0 6pt; padding-top: 4pt; border-top: 1px solid #c9d3de; color: #12324f; page-break-after: avoid; }
h3 { font-size: 11.5pt; margin: 12pt 0 4pt; color: #1b4a73; page-break-after: avoid; }
h4 { font-size: 10pt; margin: 10pt 0 4pt; page-break-after: avoid; }
p, li { orphans: 3; widows: 3; }
code { font-family: "DejaVu Sans Mono", Consolas, monospace; font-size: 8.4pt; background: #f1f4f7; padding: 0 2px; border-radius: 2px; }
pre { background: #f1f4f7; border: 1px solid #dde3ea; border-radius: 3px; padding: 6pt 8pt; white-space: pre-wrap; word-break: break-all;
      page-break-inside: avoid; }
pre code { background: none; padding: 0; font-size: 8pt; }
table { border-collapse: collapse; width: 100%; margin: 6pt 0 10pt; font-size: 8.4pt; page-break-inside: auto; }
tr { page-break-inside: avoid; }
th, td { border: 1px solid #c9d3de; padding: 3pt 5pt; vertical-align: top; text-align: left; }
th { background: #e8eef4; }
img { max-width: 100%; max-height: 225mm; border: 1px solid #c9d3de; margin: 4pt 0; page-break-inside: avoid; }
td img { max-height: 90mm; }
a { color: #1b5fa3; text-decoration: none; }
blockquote { margin: 6pt 0; padding: 2pt 10pt; border-left: 3px solid #9fb4c9; color: #3b4756; }
.cover { font-size: 8.5pt; color: #5b6878; margin-bottom: 12pt; }
"""


def branding() -> tuple[str, str]:
    """The product name and copyright line from the console's settings (local overrides win), as the console shows them."""
    product, copyright_ = "", ""
    for name in ("application.yaml", "application.local.yaml"):
        f = ROOT / "drishti-console" / "config" / name
        if f.is_file():
            ui = (yaml.safe_load(f.read_text(encoding="utf-8")) or {}).get("ui") or {}
            product = str(ui.get("product") or product)
            copyright_ = str(ui.get("copyright") or copyright_)
    env = re.compile(r"\$\{([A-Z0-9_]+)(?::([^}]*))?\}")                     # ${DRISHTI_PRODUCT:Drishti}: the environment, else the default
    product, copyright_ = (env.sub(lambda m: os.environ.get(m.group(1), m.group(2) or ""), v) for v in (product, copyright_))
    return product, copyright_


def title(md_path: Path) -> str:
    """The document's first-level heading."""
    m = re.search(r"^# (.+)$", COMMENT.sub("", md_path.read_text(encoding="utf-8")), re.M)
    return m.group(1).strip() if m else md_path.stem


def plain_link(md_path: Path, href: str, text: str) -> str:
    """A link to another file of the repository, which a PDF cannot follow: its text, and the file's path from the repository's root
    when the text does not name the file already."""
    target, _, anchor = html.unescape(href).partition("#")
    try:
        rel = (md_path.parent / target).resolve().relative_to(ROOT).as_posix()
    except ValueError:
        rel = target
    if Path(rel).name and Path(rel).name in re.sub(r"<[^>]+>", "", text):
        return text
    return f'{text} <span class="ref">({html.escape(rel)}{"#" + html.escape(anchor) if anchor else ""})</span>'


def pictures(md_path: Path) -> list[Path]:
    """The local pictures a Markdown file shows, in order, each once."""
    seen: list[Path] = []
    for rel in PICTURE.findall(COMMENT.sub("", md_path.read_text(encoding="utf-8"))):
        if "://" in rel:
            continue
        f = (md_path.parent / rel).resolve()
        if f not in seen:
            seen.append(f)
    return seen


def fingerprint(md_path: Path) -> str:
    """The SHA-256 of the Markdown, this tool's style and every picture shown (missing ones by name), first 16 hex characters."""
    h = hashlib.sha256(md_path.read_bytes())
    h.update(CSS.encode())
    for f in pictures(md_path):
        h.update(f.name.encode())
        h.update(f.read_bytes() if f.is_file() else b"missing")
    return h.hexdigest()[:16]


def to_html(md_path: Path) -> str:
    text = COMMENT.sub("", md_path.read_text(encoding="utf-8"))
    md = markdown.Markdown(extensions=["tables", "fenced_code", "sane_lists", "attr_list", "toc"],
                           extension_configs={"toc": {"toc_depth": "2-3", "slugify": GithubSlugger()}})
    body = md.convert(text)

    def inline(m: re.Match) -> str:
        src = html.unescape(m.group(2))
        f = (md_path.parent / src).resolve()
        if "://" in src or not f.is_file():
            return m.group(0)
        mime = mimetypes.guess_type(f.name)[0] or "image/jpeg"
        return f'{m.group(1)}data:{mime};base64,{base64.b64encode(f.read_bytes()).decode()}"'

    body = re.sub(r'(<img[^>]*?\bsrc=")([^"]+)"', inline, body)
    # a link to another document of the repository cannot be followed inside a PDF: keep its text, show where it points
    body = re.sub(r'<a href="(?!#|https?:|mailto:)([^"]+)">(.*?)</a>', lambda m: plain_link(md_path, m.group(1), m.group(2)), body)
    product, copyright_ = branding()
    name = title(md_path)
    cover = f'<p class="cover">{html.escape(product)} · {html.escape(md_path.relative_to(ROOT).as_posix())} · {html.escape(copyright_)}</p>'
    body = re.sub(r"(</h1>)", r"\1" + cover.replace("\\", "\\\\"), body, count=1)
    return (f"<!doctype html><html><head><meta charset='utf-8'><title>{html.escape(name)} · {MARK}{fingerprint(md_path)}</title>"
            f"<style>{CSS} .ref {{ color: #6b7785; font-size: 0.85em; }}</style></head><body>{body}</body></html>")


def render(md_path: Path, pdf_path: Path) -> int:
    from playwright.sync_api import sync_playwright
    page_html = to_html(md_path)
    product, _ = branding()
    footer = ("<div style='font-size:7pt;color:#6b7785;width:100%;padding:0 14mm;display:flex;justify-content:space-between'>"
              f"<span>{html.escape(product)} · {html.escape(title(md_path))}</span>"
              "<span><span class='pageNumber'></span> / <span class='totalPages'></span></span></div>")
    with sync_playwright() as p:
        browser = p.chromium.launch()
        page = browser.new_page()
        page.set_content(page_html, wait_until="load")
        pdf_path.parent.mkdir(parents=True, exist_ok=True)
        page.pdf(path=str(pdf_path), format="A4", print_background=True, display_header_footer=True,
                 header_template="<span></span>", footer_template=footer, margin={"top": "16mm", "bottom": "18mm", "left": "14mm", "right": "14mm"})
        browser.close()
    data = pdf_path.read_bytes()
    pages = len(re.findall(rb"/Type\s*/Page[^s]", data))
    print(f"wrote {pdf_path.relative_to(ROOT) if pdf_path.is_relative_to(ROOT) else pdf_path}: {pages} pages, {len(pictures(md_path))} pictures, "
          f"{len(data) // 1024} KB")
    return pages


def title_of(pdf_path: Path) -> str:
    """The PDF's /Title (Chromium writes it as a text string, or UTF-16 hex)."""
    data = pdf_path.read_bytes()
    m = re.search(rb"/Title\s*\((.*?)(?<!\\)\)", data, re.S)
    if m:
        raw = m.group(1)
        if raw.startswith(b"\xfe\xff"):
            return raw[2:].decode("utf-16-be", "replace")
        return raw.decode("latin-1")
    m = re.search(rb"/Title\s*<([0-9A-Fa-f\s]+)>", data)
    if m:
        b = bytes.fromhex(re.sub(rb"\s", b"", m.group(1)).decode())
        return b[2:].decode("utf-16-be", "replace") if b.startswith(b"\xfe\xff") else b.decode("latin-1")
    return ""


def stale(md_path: Path) -> str:
    """Why the PDF beside a Markdown file is not current ('' when it is)."""
    pdf = md_path.with_suffix(".pdf")
    if not pdf.is_file():
        return f"{pdf.relative_to(ROOT)} is missing"
    want = fingerprint(md_path)
    if not title_of(pdf).endswith(MARK + want):
        return f"{pdf.relative_to(ROOT)} is older than {md_path.relative_to(ROOT)} or its pictures (want {want}, the PDF says {title_of(pdf)!r})"
    return ""


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("source", nargs="?", help="one Markdown file (default: every guide of PDFS)")
    ap.add_argument("target", nargs="?", help="its PDF (default: beside it)")
    ap.add_argument("--check", action="store_true", help="exit 1 when a PDF is not current; no browser needed")
    a = ap.parse_args()
    sources = [Path(a.source).resolve()] if a.source else PDFS
    if a.check:
        bad = [why for s in sources if (why := stale(s))]
        for why in bad:
            print("stale:", why, "->", "drishti-console/.venv/bin/python tools/docs/md_to_pdf.py")
        return 1 if bad else 0
    for s in sources:
        render(s, Path(a.target).resolve() if a.target else s.with_suffix(".pdf"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
