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

"""Markdown Sutras in the console: renders a Sutra's prose for Studio's Document tab and the help centre.

The text comes from authors, so raw HTML is not passed through (it is shown escaped) and only http(s),
mailto, relative and in-page links survive. The ```sutra block renders as a labelled code block."""
from __future__ import annotations

import html
import re

import markdown

_HREF = re.compile(r'(href|src)="(?!https?:|mailto:|/|#|\.{0,2}/?[\w-])[^"]*"', re.I)
_FENCE = re.compile(r'<pre><code class="language-sutra">')
_BLOCK = re.compile(r"^(```|~~~)[ \t]*sutra[ \t]*$(.*?)^\1[ \t]*$", re.M | re.S)


def render(text: str) -> dict:
    """HTML for the whole document, its outline (h1-h3) and the Sutra block's line span, if any."""
    md = markdown.Markdown(extensions=["tables", "fenced_code", "sane_lists", "toc"],
                           extension_configs={"toc": {"toc_depth": "1-3"}})
    md.preprocessors.deregister("html_block")
    md.inlinePatterns.deregister("html")
    body = md.convert(re.sub(r"\A\s*<!--.*?-->\s*", "", text, flags=re.S))  # the copyright header
    body = _HREF.sub(r'\1="#"', body)
    body = re.sub(r'(?i)(href|src)="\s*(javascript|data|vbscript):[^"]*"', r'\1="#"', body)
    body = _FENCE.sub('<div class="sutra-block-label mono">sutra</div><pre class="sutra-block"><code class="language-sutra">', body)
    body = body.replace("<table>", '<div class="tbl-wrap"><table class="tbl help-tbl">').replace("</table>", "</table></div>")

    def flat(tokens, out):
        for t in tokens:
            out.append({"level": t["level"], "id": t["id"], "name": html.unescape(t["name"])})
            flat(t.get("children", []), out)
        return out

    m = _BLOCK.search(text)
    block = None
    if m:
        first = text.count("\n", 0, m.start()) + 1
        block = {"from": first, "to": text.count("\n", 0, m.end()) + 1}
    return {"html": body, "outline": flat(md.toc_tokens, []), "block": block}
