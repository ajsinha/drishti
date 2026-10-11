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

"""Every endpoint of the server's controllers is in docs/guides/API_GUIDE.md, and the guide's tables list none that do not exist.

The controllers' `@GetMapping`, `@PostMapping`, `@PutMapping`, `@DeleteMapping` and `@PatchMapping` annotations (with the class's
`@RequestMapping` prefix) are read from the source. An endpoint counts as documented when a line of the guide names its path (path
variables compared by position, not by name) and, for anything but a GET, its method; a sibling written as a short form after a
neighbour (`/undo`, `/redo`; `/hide` · `/unhide`) counts. In the other direction every `| METHOD | /path |` table row must be an
endpoint some controller maps, in some HTTP method.
"""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
GUIDE = (ROOT / "docs" / "guides" / "API_GUIDE.md").read_text(encoding="utf-8")
ANNOTATION = re.compile(r"@(?:[\w.]*\.)?(Get|Post|Put|Delete|Patch)Mapping(?:\(([^)]*)\))?")
# Not under /api/v1 and not a controller of the product's API: nothing to list.
NOT_DOCUMENTED = set()


def _endpoints() -> set[tuple[str, str]]:
    found: set[tuple[str, str]] = set()
    for path in ROOT.glob("drishti-server/src/main/java/**/*.java"):
        text = path.read_text(encoding="utf-8")
        if not re.search(r"@(Rest)?Controller", text):
            continue
        base = re.search(r'@RequestMapping\(\s*(?:value\s*=\s*|path\s*=\s*)?(?:\{\s*)?"([^"]*)"', text)
        prefix = base.group(1) if base else ""
        for m in ANNOTATION.finditer(text):
            quoted = re.search(r'"([^"]*)"', m.group(2) or "")
            found.add((m.group(1).upper(), prefix + (quoted.group(1) if quoted else "")))
    return found


def _norm(path: str) -> str:
    path = re.sub(r"^/api/v1", "", path.split("?")[0])
    return re.sub(r"\{[^}]*\}", "{}", path).rstrip("/") or "/"


LINES = [re.sub(r"\{[^}]*\}", "{}", ln) for ln in GUIDE.splitlines()]


def _mentions(path: str, prefix: bool = False) -> list[str]:
    pattern = re.compile(re.escape(path) + (r"(?![\w{}\-])" if prefix else r"(?![\w{}\-/]|\.\w)"))
    return [ln for ln in LINES if pattern.search(ln)]


def _documented(method: str, path: str) -> bool:
    for ln in _mentions(path):
        if method == "GET" or re.search(rf"\b{method}\b", ln):
            return True
    parent, _, last = path.rpartition("/")                      # `/undo`, `/redo`: the short form after a neighbour
    if parent and last:
        short = re.compile(r"`/" + re.escape(last) + r"`|[`,·] */" + re.escape(last) + r"(?![\w{}\-/])")
        for ln in _mentions(parent, prefix=True):
            if short.search(ln) and (method == "GET" or re.search(rf"\b{method}\b", ln)):
                return True
        grand, _, mid = parent.rpartition("/")                  # `/versions`, `/versions/{n}`
        if grand and mid:
            for ln in _mentions(grand + "/" + mid):
                if re.search(r"/" + re.escape(mid) + r"/" + re.escape(last), ln):
                    return True
    return False


def test_every_controller_endpoint_is_in_the_api_guide():
    missing = sorted(f"{m} {p}" for m, p in ((m, _norm(p)) for m, p in _endpoints()) if not _documented(m, p) and f"{m} {p}" not in NOT_DOCUMENTED)
    assert not missing, "endpoints the controllers map that API_GUIDE.md does not list:\n  " + "\n  ".join(missing)


def test_every_endpoint_row_of_the_api_guide_exists():
    mapped = {_norm(p) for _, p in _endpoints()}
    patterns = [re.compile("^" + re.escape(p).replace(r"\{\}", "[^/]+") + "$") for p in mapped]
    unknown = []
    for ln in GUIDE.splitlines():
        m = re.match(r"\|\s*`(GET|POST|PUT|PATCH|DELETE)`(?:\s*[·/]\s*`?(?:GET|POST|PUT|PATCH|DELETE)`?)*\s*\|\s*`([^`]+)`", ln)
        if not m:
            continue
        path = _norm(m.group(2).split()[0].replace("[", "?"))
        if path.startswith(("/embed/v1", "/auth/login", "/auth/sessions")) and not any(p.match(path) for p in patterns):
            continue
        if not any(p.match(path) for p in patterns):
            unknown.append(f"{m.group(1)} {m.group(2)}")
    assert not unknown, "API_GUIDE.md rows with no controller behind them:\n  " + "\n  ".join(unknown)
