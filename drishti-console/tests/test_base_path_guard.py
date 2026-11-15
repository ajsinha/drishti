"""
Project Drishti · Any data. Any domain. One grammar.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
All rights reserved.

PROPRIETARY AND CONFIDENTIAL.

This file is the confidential and proprietary property of Ashutosh Sinha.
Unauthorised copying, use, modification, distribution or disclosure of this
file, via any medium, is strictly prohibited except with the express prior
written permission of the copyright holder.

See the LICENSE file in the root of this repository for the full terms.
"""
import re
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
CONSOLE = ROOT / "drishti-console"
STATIC = CONSOLE / "web" / "static"

pytestmark = pytest.mark.static

# The console behind a reverse proxy at a URL sub-path (server.base_path, core/base_path.py): every root-absolute URL of the console a script
# or a template writes goes through the one helper, drishti_url() (drishtiUrl in the Elements' modules), or {{ BASE }} / the filter u in a
# template. A literal "/api/...", "/static/...", "/embed/...", "/m/..." (any of the console's first path segments) elsewhere would reach the
# host's root instead of the console.
SEGMENTS = ("about", "account", "admin", "alerts", "api", "approvals", "asof", "auth", "bi", "build", "compare", "data", "datamap", "designer",
            "elements", "embed", "explain", "export", "exposure", "form", "frtb", "func", "go", "healthz", "help", "impact", "inbox", "lens",
            "live", "login", "logout", "m", "pin", "pivot", "play", "predeal", "pyodide", "readyz", "render", "reports", "review", "s", "search",
            "servers", "share", "simm", "smat", "static", "studio", "t", "v", "var", "w", "what")
URL = re.compile(r"^/(" + "|".join(re.escape(s) for s in SEGMENTS) + r")(?=[/?#]|$)")
WRAPPERS = ("drishti_url", "drishtiUrl", "basePath")
# a literal compared with a route (location's path through drishti_route(), a server answer's path): no URL is built from it
COMPARE_OPS = ("===", "!==", "==", "!=", "case")
COMPARE_CALLS = ("startsWith", "endsWith", "indexOf", "lastIndexOf", "includes", "split", "replace", "match", "test", "search")

# Not under the console's URL sub-path by design, each with its reason: (file suffix, text on the line).
ALLOWED: list[tuple[str, str]] = [
    ("drishti-renderer/src/drishti_screen.js", "const EMBED = '/embed/v2'"),      # joined to o.server, the console's root with its sub-path
    ("drishti-renderer/src/drishti_embed.js", "export const API = '/embed/v2'"),  # joined to serverOf(), the console's root with its sub-path
    ("drishti-renderer/src/drishti_signin.js", "const BASE = '/embed/v2/sign-in'"),   # joined to the console's root (this.server)
    ("drishti-renderer/src/drishti_elements.js", "views(r, '/about'"),           # a suffix of server + API + the view's path
    ("drishti-renderer/src/drishti_screen.js", "views(r, '/about'"),             # the same, in the screen
    ("drishti-core/src/drishti_loader.js", "url('/static/elements/')"),          # drishti-core's own url() (drishti_base.js)
    ("js/drishti_bi-design-model.js", "path.slice('/reports/'.length)"),        # a length, not a URL
]

# Helpers that take a path under their own API base, which they put under the console's base themselves: (file suffix or "*", the call).
# B.api('GET', '/reports/x') is /bi/api/reports/x; prefixing the path would put the base in the middle.
RELATIVE: list[tuple[str, str]] = [
    ("*", "B.api"),                                   # drishti_bi-lib.js: fetch(drishti_url('/bi/api' + path))
    ("*", "B.ask"),                                   # drishti_bi-lib.js: the same base, as a run
    ("js/drishti_bi.js", "api"),                      # '/bi/api' + path (a path that already starts /bi/ is taken as it is)
    ("js/drishti_bi-design-model.js", "api"),         # B.api, kept before the designer wraps it
    ("js/drishti_pivot.js", "call"),                  # drishti_url(API + path): the Pivot's own API
    ("js/drishti_pivot-switches.js", "call"),         # the Pivot screen's call (screen().call), the same
    ("drishti-renderer/src/drishti_about.js", "link"),   # (R.server || basePath()) + href: the console's root, sub-path included
]


# ---- a small JS tokenizer: strings, whole template literals, regex literals, words, punctuators --------------------------------------------
_KW_REGEX = {"return", "typeof", "case", "do", "else", "in", "of", "new", "delete", "void", "throw", "yield", "await", "instanceof"}
_OPS = sorted(["...", "===", "!==", "=>", "==", "!=", "<=", ">=", "&&", "||", "??", "?.", "++", "--", "+=", "-="], key=len, reverse=True)
_WORD = re.compile(r"[A-Za-z_$][\w$]*|\d[\w.]*")


def _template_end(src: str, i: int) -> int:
    j, n = i + 1, len(src)
    while j < n:
        if src[j] == "\\":
            j += 2
            continue
        if src[j] == "`":
            return j + 1
        if src.startswith("${", j):
            inner = tokens(src, j + 2, until_brace=True)
            j = inner[-1][2] if inner and inner[-1][3] == "}" else n
            continue
        j += 1
    return n


def tokens(src: str, i: int = 0, until_brace: bool = False) -> list[tuple[str, int, int, str]]:
    """(kind, start, end, text): kind is str, tpl, regex, word or punct."""
    out: list[tuple[str, int, int, str]] = []
    n, depth = len(src), 0
    while i < n:
        c = src[i]
        if c in " \t\r\n":
            i += 1
        elif src.startswith("//", i):
            j = src.find("\n", i)
            i = n if j < 0 else j
        elif src.startswith("/*", i):
            j = src.find("*/", i + 2)
            i = n if j < 0 else j + 2
        elif c in "'\"":
            j = i + 1
            while j < n and src[j] not in (c, "\n"):
                j += 2 if src[j] == "\\" else 1
            out.append(("str", i, j + 1, src[i:j + 1]))
            i = j + 1
        elif c == "`":
            j = _template_end(src, i)
            out.append(("tpl", i, j, src[i:j]))
            i = j
        elif c == "/" and (not out or (out[-1][0] == "punct" and out[-1][3] not in ")]}") or (out[-1][0] == "word" and out[-1][3] in _KW_REGEX)):
            j, cls = i + 1, False
            while j < n and src[j] != "\n" and (src[j] != "/" or cls):
                if src[j] == "\\":
                    j += 1
                elif src[j] in "[]":
                    cls = src[j] == "["
                j += 1
            j += 1
            while j < n and src[j].isalpha():
                j += 1
            out.append(("regex", i, j, src[i:j]))
            i = j
        else:
            m = _WORD.match(src, i)
            if m:
                out.append(("word", i, m.end(), m.group(0)))
                i = m.end()
                continue
            op = next((o for o in _OPS if src.startswith(o, i)), c)
            if until_brace and op in "{}":
                depth += 1 if op == "{" else -1
                if depth < 0:
                    out.append(("punct", i, i + 1, op))
                    return out
            out.append(("punct", i, i + len(op), op))
            i += len(op)
    return out


def _head(tok: tuple[str, int, int, str]) -> str:
    text = tok[3]
    if tok[0] == "str":
        return text[1:-1]
    k = text.find("${")
    return text[1:k] if k >= 0 else text[1:-1]


def _wrapped(toks: list, k: int) -> bool:
    """Whether token k sits inside the arguments of a wrapper call: drishti_url( ... ), drishtiUrl( ... ), (x.server || basePath()) + ..."""
    depth = 0
    for j in range(k - 1, -1, -1):
        t = toks[j][3]
        if toks[j][0] == "punct" and t in ")]}":
            depth += 1
        elif toks[j][0] == "punct" and t in "([{":
            if depth == 0:
                if t == "(" and j > 0 and toks[j - 1][3] in WRAPPERS:
                    return True
                if t != "(":
                    return False
            else:
                depth -= 1
        elif depth == 0 and toks[j][0] == "punct" and t in (";",):
            return False
    return False


def callee(toks: list, k: int) -> str:
    """The call token k is a direct argument of: "B.api" for B.api('GET', '/x'), "call" for screen().call('GET', '/x'); "" for none."""
    depth = 0
    for j in range(k - 1, -1, -1):
        kind, t = toks[j][0], toks[j][3]
        if kind == "punct" and t in ")]}":
            depth += 1
        elif kind == "punct" and t in "([{":
            if depth:
                depth -= 1
                continue
            if t != "(":
                return ""
            name, i = [], j - 1
            while i >= 0 and toks[i][0] == "word" and toks[i][3] not in _KW_REGEX and toks[i][3] != "return":
                name.insert(0, toks[i][3])
                if i >= 2 and toks[i - 1][3] == "." and toks[i - 2][0] == "word":
                    name.insert(0, ".")
                    i -= 2
                else:
                    break
            return "".join(name)
        elif depth == 0 and kind == "punct" and t in (";", "=", ":", "?"):
            return ""
    return ""


def js_offenders(src: str, path: str = "") -> list[tuple[int, str]]:
    """(line, literal) of every root-absolute console URL literal that does not go through the helper."""
    toks = tokens(src)
    relative = {name for suffix, name in RELATIVE if suffix == "*" or path.endswith(suffix)}
    found = []
    for k, tok in enumerate(toks):
        if tok[0] not in ("str", "tpl") or not URL.match(_head(tok)):
            continue
        prev = toks[k - 1] if k else None
        if prev is not None and prev[3] == "+":
            continue                                                         # the rest of a URL built before it
        if prev is not None and prev[3] in COMPARE_OPS:
            continue
        if prev is not None and prev[3] == "(" and k >= 2 and toks[k - 2][3] in COMPARE_CALLS + WRAPPERS:
            continue
        if _wrapped(toks, k):
            continue
        if callee(toks, k) in relative:
            continue                                                         # a path under the helper's own (prefixed) API base
        found.append((src.count("\n", 0, tok[1]) + 1, tok[3][:80]))
    return found


_TAG = re.compile(r"\{\{.*?\}\}|\{%.*?%\}|\{#.*?#\}", re.S)
_ATTR = re.compile(r"\s(?:href|src|action|formaction|poster|data-[a-z-]+)=([\"'])(/(?!/)[^\"'{]*)")


def template_offenders(src: str) -> list[tuple[int, str]]:
    """(line, attribute) of every URL attribute written as a root-absolute path ("/bi") instead of "{{ BASE }}/bi"."""
    spans = [(m.start(), m.end()) for m in _TAG.finditer(src)]
    found = []
    for m in _ATTR.finditer(src):
        if any(a <= m.start() < b for a, b in spans):
            continue
        found.append((src.count("\n", 0, m.start()) + 1, m.group(0).strip()[:80]))
    return found


def _js_files() -> list[Path]:
    files = [p for p in STATIC.rglob("*.js") if not ({"vendor", "render"} & set(p.relative_to(STATIC).parts))]
    files = [p for p in files if not (p.parent.parent.name == "elements" and p.name.startswith("drishti_"))]   # a library's bundle: its src/ is read
    files = [p for p in files if "harness" not in p.name and p.name != "drishti_catalogue.js"]    # the libraries' test pages; generated data
    files += list((CONSOLE / "plugins").glob("*/static/**/*.js"))
    files += [p for p in (ROOT / "modules").rglob("*.js") if "console" in p.parts and "static" in p.parts and "node_modules" not in p.parts]
    return sorted(files)


def _templates() -> list[Path]:
    files = list((CONSOLE / "web" / "templates").rglob("*.html")) + list((CONSOLE / "plugins").glob("*/templates/**/*.html"))
    files += [p for p in (ROOT / "modules").rglob("*.html") if "console" in p.parts and ("templates" in p.parts or "pages" in p.parts)]
    return sorted(files)


def _allowed(f: Path, line_text: str) -> bool:
    rel = f.as_posix()
    return any(rel.endswith(s) and t in line_text for s, t in ALLOWED)


def test_console_scripts_build_every_console_url_through_drishti_url():
    offenders = []
    for f in _js_files():
        src = f.read_text(encoding="utf-8")
        lines = src.splitlines()
        for n, lit in js_offenders(src, f.as_posix()):
            if not _allowed(f, lines[n - 1]):
                offenders.append(f"{f.relative_to(ROOT)}:{n}  {lit}")
    assert not offenders, ("root-absolute console URLs that skip the base path; write drishti_url('/api/...') (drishtiUrl in an Elements "
                           "module), or add the line to ALLOWED with its reason:\n" + "\n".join(offenders))


def test_console_templates_put_every_console_url_under_base():
    offenders = []
    for f in _templates():
        for n, attr in template_offenders(f.read_text(encoding="utf-8")):
            offenders.append(f"{f.relative_to(ROOT)}:{n}  {attr}")
    assert not offenders, "template URLs without {{ BASE }} (or | u for one from data):\n" + "\n".join(offenders)


def test_the_page_names_its_base_once():
    base = (CONSOLE / "web" / "templates" / "base.html").read_text(encoding="utf-8")
    assert base.count('<meta name="drishti-base" content="{{ BASE }}">') == 1
    helper = (STATIC / "elements" / "drishti-core" / "src" / "drishti_base.js").read_text(encoding="utf-8")
    assert "root.drishti_url = url" in helper and 'meta[name="drishti-base"]' in helper


def test_every_allowed_line_is_still_there():
    """An ALLOWED entry whose line went away is dropped, so the list never names what no longer exists."""
    texts = {f.as_posix(): f.read_text(encoding="utf-8") for f in _js_files() + list(STATIC.glob("elements/*/src/*.js"))}
    stale = [f"{s}: {t}" for s, t in ALLOWED if not any(p.endswith(s) and t in body for p, body in texts.items())]
    assert not stale, "ALLOWED entries that match nothing:\n" + "\n".join(stale)


@pytest.mark.parametrize("snippet,bad", [
    ("fetch('/api/x')", True),
    ("fetch(drishti_url('/api/x'))", False),
    ("fetch(drishti_url('/api/x?q=' + q))", False),
    ("var u = `/static/js/${name}.js`;", True),
    ("var u = drishti_url(`/static/js/${name}.js`);", False),
    ("B.api('GET', '/runs/' + id)", False),                     # not a console route of its own: a path under the helper's base
    ("return B.api('POST', '/reports/query', {})", False),      # a console route's name, but under B.api's own base (RELATIVE)
    ("api('POST', '/admin/api/x')", True),                      # a helper that fetches the URL as given: the literal goes through drishti_url
    ("location.assign('/bi')", True),
    ("if (drishti_route() === '/t') { go(); }", False),
    ("path.startsWith('/api/')", False),
    ("x = base + '/api/y'", False),
    ("// fetch('/api/in-a-comment')", False),
    ("var re = /\\/api\\/x/;", False),
    ("h('a', { href: '/help/x' })", True),
    ("h('a', { href: drishtiUrl('/help/x') })", False),
])
def test_the_guard_reads_scripts_as_meant(snippet, bad):
    assert bool(js_offenders(snippet)) is bad


@pytest.mark.parametrize("snippet,bad", [
    ('<a href="/bi">', True),
    ('<a href="{{ BASE }}/bi">', False),
    ('<a href="{{ item.href | u }}">', False),
    ('<img src="//cdn.example/x.png">', False),
    ('{% set x = "/bi" %}', False),
    ('<form action="/login" method="post">', True),
])
def test_the_guard_reads_templates_as_meant(snippet, bad):
    assert bool(template_offenders(snippet)) is bad
