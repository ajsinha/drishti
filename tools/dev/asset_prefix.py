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


"""Every static asset we wrote is named ``drishti_<name>``, and nothing of ours collides with another library or a host page: a re-runnable
script, not a hand edit, so it can run on any checkout (develop after a release, then each branch still to merge).

    python3 tools/dev/asset_prefix.py scan             # write tools/dev/asset_prefix_map.yaml from the tree (review it)
    python3 tools/dev/asset_prefix.py apply            # scan, then rename (git mv) and rewrite every reference
    python3 tools/dev/asset_prefix.py apply --dry-run  # what apply would do, nothing written
    python3 tools/dev/asset_prefix.py check            # exit 1 when anything is left to do (the guard test asks the same)

What ``apply`` does, in order (the scanner, ``asset_prefix_scan.py``, says what each name becomes):

1. renames our static files with ``git mv`` (``static/js/bi-runs-tab.js`` -> ``static/js/drishti_bi-runs-tab.js``);
2. rewrites every reference to them in the repository's text files (templates, routes, tests, tools, docs, config, Java comments) by
   their file name: a path into a static folder, a relative path from a static file, or the bare name where it can only mean ours;
3. moves our window globals into ``window.Drishti`` and starts every script that uses it with
   ``globalThis.Drishti = globalThis.Drishti || {};``;
4. renames custom events, the generic CSS classes and the third-party data- attributes the mapping marks ``rename``;
5. applies the mapping's ``patches`` (names built at run time, which a file-name rewrite cannot see) and the docs fragments in
   ``tools/dev/asset_prefix_docs.md``.

Every step is idempotent: a second run finds nothing to do. Frozen code (a renderer version under ``static/render/<version>/``, the
Elements in ``web/embed/``) is never touched, and every name it uses is kept: those versions are published and pinned by host pages.
History (CHANGELOG.md, RELEASE_NOTES.md) is never rewritten. What the script cannot prove safe is listed, not changed."""
from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
from collections import Counter, defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import asset_prefix_scan as sc  # noqa: E402

import yaml  # noqa: E402

MAP = Path(__file__).resolve().parent / "asset_prefix_map.yaml"
DOCS = Path(__file__).resolve().parent / "asset_prefix_docs.md"
BOOT = "(function (g) { g.Drishti = g.Drishti || {}; })(typeof window !== 'undefined' ? window : globalThis);"
BOOT_COMMENT = "/* one namespace for everything the console puts on window (tools/dev/asset_prefix.py) */"
SECTIONS = ("files", "globals", "events", "classes", "data_attrs", "storage", "elements", "anchors")


def camel(w: str) -> str:
    """data-tab-index -> tabIndex (the dataset property)."""
    parts = w.split("-")
    return parts[0] + "".join(p[:1].upper() + p[1:] for p in parts[1:])


# -- the mapping file --------------------------------------------------------------------------------------------------------------
HEADER = """\
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
#
# The asset-prefix mapping: written by `python3 tools/dev/asset_prefix.py scan`, read by `apply` (tools/dev/asset_prefix.py).
# A scan only ADDS entries: an entry once written is kept (so the names stay mapped after the rename, for the branches still to
# merge), and a hand edit wins: set status to manual to keep a name, to rename to have apply change it. `patches` are written by hand.
#   files       old -> new, relative to drishti-console/web/static
#   globals     window.<name> -> window.Drishti.<to>; keep = a frozen library reads it (a published contract)
#   events      custom event names; keep = a frozen library uses it
#   classes     the generic CSS classes our sheets define; rename = every use is one the rewriter recognises, manual = not proven
#   data_attrs  data- attributes other libraries also use; same statuses
#   storage, elements   checked only: every key starts drishti, every custom element drishti-
"""


def load_map(path: Path = MAP) -> dict:
    if not path.is_file():
        return {k: {} for k in SECTIONS} | {"patches": [], "after": []}
    m = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    for k in SECTIONS:
        m.setdefault(k, {})
        m[k] = m[k] or {}
    m["patches"] = m.get("patches") or []
    m["after"] = m.get("after") or []
    return m


def merge(old: dict, new: dict) -> dict:
    out = {k: dict(old.get(k) or {}) for k in SECTIONS}
    for k in SECTIONS:
        for name, v in (new.get(k) or {}).items():
            out[k].setdefault(name, v)
        out[k] = dict(sorted(out[k].items()))
    out["patches"] = old.get("patches") or []
    out["after"] = old.get("after") or []
    return out


def save_map(m: dict, path: Path = MAP) -> None:
    body = yaml.safe_dump({k: m[k] for k in SECTIONS + ("patches", "after")}, sort_keys=False, allow_unicode=True, width=160)
    path.write_text(HEADER + body, encoding="utf-8")


# -- string and comment spans ------------------------------------------------------------------------------------------------------
JS_TOKENS = re.compile(r"/\*.*?\*/|//[^\n]*|'(?:\\.|[^'\\\n])*'|\"(?:\\.|[^\"\\\n])*\"|`(?:\\.|[^`\\])*`", re.S)
PY_TOKENS = re.compile(r"#[^\n]*|'''.*?'''|\"\"\".*?\"\"\"|'(?:\\.|[^'\\\n])*'|\"(?:\\.|[^\"\\\n])*\"", re.S)
HTML_ATTR = re.compile(r"\s[\w:.@-]+\s*=\s*(\"[^\"]*\"|'[^']*')")
JINJA = re.compile(r"\{\{.*?\}\}|\{%.*?%\}", re.S)
QUOTED = re.compile(r"'(?:\\.|[^'\\\n])*'|\"(?:\\.|[^\"\\\n])*\"")


def strings_of(text: str, suffix: str) -> list[str]:
    """The string literals (JS, Python) or attribute values and Jinja literals (HTML) of a text: where a class name could hide."""
    if suffix in (".js", ".mjs", ".ts"):
        return [m.group(0) for m in JS_TOKENS.finditer(text) if m.group(0)[0] in "'\"`"]
    if suffix == ".py":
        out = []
        for m in PY_TOKENS.finditer(text):
            s = m.group(0)
            if s.startswith("#"):
                continue
            if s.startswith(('"""', "'''")):
                before = text[:m.start()].rstrip(" \t")
                if before.endswith((":", "\n")) or not before:     # a docstring: prose, never markup
                    continue
            out.append(s)
        return out
    if suffix == ".html":
        out = [m.group(1) for m in HTML_ATTR.finditer(text)]
        for j in JINJA.finditer(text):
            out += [q.group(0) for q in QUOTED.finditer(j.group(0))]
        return out
    return []


# -- CSS classes -------------------------------------------------------------------------------------------------------------------
CLASS_ATTR = re.compile(r"\bclass(?:Name)?\s*(?:=|:)\s*(\\?[\"'`])")
CLASS_LIST = re.compile(r"classList\.(?:add|remove|toggle|contains|replace)\(([^()]*)\)")
SELECTOR_CALL = re.compile(r"(?:querySelector(?:All)?|closest|matches|locator|query_selector(?:_all)?|wait_for_selector|eval_on_selector(?:_all)?|"
                           r"\$\$?eval|\$\$?|click|dblclick|hover|is_visible|is_hidden|inner_text|text_content|get_attribute|fill|check|press)"
                           r"\(\s*(f?)(['\"`])((?:\\.|(?!\2).)*)\2", re.S)
EXPR = re.compile(r"\$\{.*?\}|\{\{.*?\}\}|\{%.*?%\}", re.S)


def _sub_tokens(s: str, names: dict[str, str], mark: Counter, kind: str) -> str:
    """Class tokens in a class list (outside ${...}/{{...}} expressions; inside them, only in quoted literals)."""
    def plain(part: str) -> str:
        return re.sub(r"(?<![\w$.-])([A-Za-z][\w-]*)(?![\w-])", lambda m: _hit(m.group(1), names, mark, kind), part)

    out, last = [], 0
    for e in EXPR.finditer(s):
        out.append(plain(s[last:e.start()]))
        out.append(QUOTED.sub(lambda q: q.group(0)[0] + plain(q.group(0)[1:-1]) + q.group(0)[-1], e.group(0)))
        last = e.end()
    out.append(plain(s[last:]))
    return "".join(out)


def _hit(w: str, names: dict[str, str], mark: Counter, kind: str) -> str:
    if w in names:
        mark[(kind, w)] += 1
        return names[w]
    return w


def _selector(s: str, names: dict[str, str], mark: Counter) -> str:
    return re.sub(r"(?<![\d.])\.([A-Za-z][\w-]*)(?![\w-])", lambda m: "." + _hit(m.group(1), names, mark, "class"), s)


def rewrite_classes_code(text: str, names: dict[str, str], mark: Counter) -> str:
    """class="..." / className = '...' / class: '...' values, classList calls and selector strings."""
    if not names:
        return text
    out, pos = [], 0
    for m in CLASS_ATTR.finditer(text):
        if m.start() < pos:
            continue
        q = m.group(1)
        end = text.find(q, m.end())
        if end < 0 or end - m.end() > 600:
            continue
        out.append(text[pos:m.end()])
        out.append(_sub_tokens(text[m.end():end], names, mark, "class"))
        pos = end
    out.append(text[pos:])
    text = "".join(out)
    text = CLASS_LIST.sub(lambda m: "classList" + m.group(0)[9:m.start(1) - m.start()] +
                          QUOTED.sub(lambda q: q.group(0)[0] + _sub_tokens(q.group(0)[1:-1], names, mark, "class") + q.group(0)[-1], m.group(1)) + ")",
                          text)
    return SELECTOR_CALL.sub(lambda m: m.group(0)[:m.start(3) - m.start()] + _selector(m.group(3), names, mark) + m.group(0)[m.end(3) - m.start():], text)


CSS_KEEP = re.compile(r"(/\*.*?\*/|\"(?:\\.|[^\"\\])*\"|'(?:\\.|[^'\\])*'|url\([^)]*\)|\{[^{}]*\})", re.S)


def rewrite_classes_css(text: str, names: dict[str, str], mark: Counter) -> str:
    if not names:
        return text
    parts = CSS_KEEP.split(text)
    return "".join(p if i % 2 else _selector(p, names, mark) for i, p in enumerate(parts))


def uncovered_class_uses(root: Path, files: list[str], w: str) -> list[str]:
    """Where the word w appears in our code (a string, an attribute value) outside a context the rewriter changes: file:count."""
    names = {w: "\0"}
    out = []
    for f in files:
        if not f.startswith(sc.CONSOLE_CODE) or sc.frozen(f):
            continue
        suf = Path(f).suffix
        if suf not in (".js", ".html", ".py"):
            continue
        t = sc.read(root, f)
        if not re.search(r"(?<![\w-])" + re.escape(w) + r"(?![\w-])", t):
            continue
        done = rewrite_classes_code(t, names, Counter())
        left = sum(len(re.findall(r"(?<![\w$.-])" + re.escape(w) + r"(?![\w-])", s)) for s in strings_of(done, suf))
        if left:
            out.append(f"{Path(f).name}:{left}")
    return out


# -- data- attributes --------------------------------------------------------------------------------------------------------------
def uncovered_data_uses(root: Path, files: list[str], w: str) -> list[str]:
    out = []
    pat = re.compile(r"['\"`](?:" + re.escape(w) + "|" + re.escape(camel(w)) + r")['\"`]")
    for f in files:
        if f.startswith(sc.CONSOLE_CODE) and not sc.frozen(f) and f.endswith((".js", ".html")):
            n = len(pat.findall(sc.read(root, f)))
            if n:
                out.append(f"{Path(f).name}:{n}")
    return out


def rewrite_data(text: str, names: dict[str, str], mark: Counter) -> str:
    for w in names:
        new = names[w]
        text, n = re.subn(r"(?<![\w-])data-" + re.escape(w) + r"(?![\w-])", "data-" + new, text)
        c = camel(new)
        text, k = re.subn(r"(?<=\bdataset\.)" + re.escape(camel(w)) + r"(?![\w$])", c, text)
        text, j = re.subn(r"(?<=\bdataset\[)(['\"])" + re.escape(camel(w)) + r"\1", lambda m: m.group(1) + c + m.group(1), text)
        if n + k + j:
            mark[("data", w)] += n + k + j
    return text


# -- file references ---------------------------------------------------------------------------------------------------------------
class FileRefs:
    """Rewrites references to renamed static files by their name, deciding per occurrence whether it is one of ours."""

    # A line naming one of these talks about another file of the same name (a renderer module, the Elements, the UI kit, an example).
    ELSEWHERE = re.compile(r"render/|/m/|\bm/|embed/|EMBED|ui-kit|[Ee]lements|examples?/|elements-demo|renderer|\bdist\b")

    def __init__(self, files_map: dict[str, str], tracked: list[str]):
        self.old = set(files_map)                                  # relative to the static root
        self.base = {}                                             # old basename -> new basename
        self.dirs: dict[str, set[str]] = defaultdict(set)          # old basename -> its static folders
        for old, new in files_map.items():
            b = old.rsplit("/", 1)[-1]
            self.base[b] = new.rsplit("/", 1)[-1]
            self.dirs[b].add(old.rsplit("/", 1)[0] if "/" in old else "")
        ours = {sc.STATIC + "/" + p for p in set(files_map) | set(files_map.values())}
        self.ambiguous = {Path(f).name for f in tracked if f not in ours} & set(self.base)
        alts = "|".join(re.escape(b).replace(r"\.", r"\\?\.") for b in sorted(self.base, key=len, reverse=True))
        self.rx = re.compile(r"(?<![\w.\\?-])(" + alts + r")(?![\w-])") if alts else None
        self.skipped: list[tuple[str, int, str, str]] = []
        self._defs: dict[tuple[str, str], str | None] = {}
        self._mentions: tuple[str, str] = ("", "")

    @staticmethod
    def _norm(s: str) -> str:
        """Python path joins as slashes: WEB / "static" / "js" / " -> WEB / "static/js/"."""
        return re.sub(r"[\"']\s*/\s*[\"']", "/", s)

    def _var_dir(self, rel: str, text: str, var: str) -> str | None:
        """The path a module constant names (JS = WEB / "static" / "js"), normalised, or None."""
        key = (rel, var)
        if key not in self._defs:
            m = re.search(r"^\s*" + re.escape(var) + r"\s*(?::\s*[\w.\[\]]+\s*)?=\s*(.+)$", text, re.M)
            d = None
            if m:
                v = self._norm(m.group(1)).replace('"', "").replace("'", "")
                v = re.sub(r"\s*/\s*", "/", v).split("#")[0].strip().rstrip(")")
                d = v
            self._defs[key] = d
        return self._defs[key]

    def _folder(self, pre: str) -> str | None:
        """The static folder a path prefix ends in, or None."""
        for d in sorted((f for f in sc.FOLDERS if f), key=len, reverse=True):
            if pre in (d + "/", "/" + d + "/", "./" + d + "/") or pre.endswith("static/" + d + "/"):
                return d
        if pre in ("build/", "/build/", "./build/") or pre.endswith("js/build/"):
            return "js/build"
        if pre.endswith("static/"):
            return ""
        return None

    def ours(self, rel: str, text: str, i: int, b: str) -> str | None:
        """None when the name at i is ours; else why not."""
        ctx = self._norm(text[max(0, i - 300):i]).replace("\\/", "/")     # a path in a regular expression: \/static\/js\/
        pre = re.search(r"[\w./-]*$", ctx).group(0)
        if pre == "" and re.search(r"\w\s*/\s*[\"']$", ctx):           # VAR / "name"
            var = re.search(r"(\w+)\s*/\s*[\"']$", ctx).group(1)
            d = self._var_dir(rel, text, var)
            if d:
                pre = d.rstrip("/") + "/"
        elif pre == "" and re.search(r"[\"']$", ctx):                      # "static/js/" + "x.js" or ("js", "x.js"): plain bare
            pass
        if pre == "/" and not text.startswith(b.replace(".", "\\."), i):    # (in /name\.js/ the slash opens a regular expression)
            return "a root address (a route of its own: /favicon.ico, /manifest.webmanifest)"
        if pre in ("", "./", "/"):
            if rel.startswith(sc.OWN_WEB) or b not in self.ambiguous:
                return None
            line = text[text.rfind("\n", 0, i) + 1:text.find("\n", i) if text.find("\n", i) >= 0 else len(text)]
            if self._mentions[0] != rel:
                self._mentions = (rel, self._norm(text))
            mentions = self._mentions[1]
            if not self.ELSEWHERE.search(line) and any(("static/" + d) in mentions if d else "static/" in mentions for d in self.dirs[b]):
                return None
            return "ambiguous bare name"
        if pre.startswith(("./", "../")) and rel.startswith(sc.STATIC + "/"):
            target = os.path.normpath(os.path.join(os.path.dirname(rel), pre, b))
            sub = target[len(sc.STATIC) + 1:] if target.startswith(sc.STATIC + "/") else None
            return None if sub in self.old else "relative path elsewhere"
        d = self._folder(pre)
        if d is None:
            return "another folder (" + pre[-40:] + ")"
        return None if (f"{d}/{b}" if d else b) in self.old else "not a file of ours in " + (d or "static/")

    def rewrite(self, rel: str, text: str, mark: Counter) -> str:
        if not self.rx:
            return text

        def one(m: re.Match) -> str:
            found = m.group(1)
            b = found.replace("\\.", ".")
            why = self.ours(rel, text, m.start(), b)
            if why is None:
                mark[("file", b)] += 1
                new = self.base[b]
                if "\\." not in found:
                    return new
                if rel.startswith(sc.OWN_JS) and not b.startswith("drishti-"):   # a script finding itself by its address: its file name, or the
                    return "(?:" + sc.PREFIX + ")?" + found         # public one the embed routes serve it by
                return new.replace(".", "\\.")
            self.skipped.append((rel, text.count("\n", 0, m.start()) + 1, b, why))
            return found
        return self.rx.sub(one, text)


# -- globals and events ------------------------------------------------------------------------------------------------------------
def rewrite_globals(rel: str, text: str, moves: dict[str, str], java_names: set[str], mark: Counter, left: list) -> str:
    for n, to in moves.items():
        if n not in text:                                           # most files name none of them: skip the regular expressions
            continue
        own = rel.startswith(sc.OWN_JS) or rel.endswith(".md")
        script = rel.endswith((".js", ".mjs", ".ts", ".html"))
        umd = script and sc.is_umd(text)
        receivers = "window|globalThis" if script and n != "drishti" else "window"
        if umd:
            receivers += "|root"

        def recv(m: re.Match) -> str:
            r = m.group(1)
            s = m.string
            line = s[s.rfind("\n", 0, m.start()) + 1:s.find("\n", m.end()) if s.find("\n", m.end()) >= 0 else len(s)]
            write = re.match(r"\s*=(?!=)", s[m.end():m.end() + 4])
            if r == "window" and (own or not write or re.search(r"\bassert\b|\.count\(|\bin\s+[\w(]", line)):
                return f"window.{sc.NAMESPACE}.{to}"                # our scripts start with the bootstrap; a page has loaded one;
                                                                    # a test comparing source text needs the source's words
            if write and not script:                                # a stub in a test's string (maybe an f-string: no braces)
                return f"({r}.{sc.NAMESPACE} ??= Object()).{to}"
            if write:                                               # a UMD root: make the namespace first
                return f"({r}.{sc.NAMESPACE} = {r}.{sc.NAMESPACE} || {{}}).{to}"
            return f"({r}.{sc.NAMESPACE} || {{}}).{to}"             # a UMD root (a module in Node): undefined, as before
        text, k = re.subn(r"(?<![\w$.])(" + receivers + r")\." + re.escape(n) + r"(?![\w$])", recv, text)
        if k:
            mark[("global", n)] += k
        if n == "drishti":
            continue
        bare_ok = rel.startswith(sc.OWN_WEB) or (n not in java_names and (rel.startswith("drishti-console/tests/") or rel.endswith(".md")))
        if not bare_ok or re.search(r"\b(?:function\s*\*?\s*|var\s+|let\s+|const\s+|class\s+)" + re.escape(n) + r"\b", text):
            continue                                                # a local of the same name: the file's bare uses are the local
        rx = re.compile(r"(?<![\w$.#/-])" + re.escape(n) + r"(?![\w$])")

        def one(m: re.Match) -> str:
            before = text[max(0, m.start() - 20):m.start()]
            after = text[m.end():m.end() + 3]
            quotes = ("'", '"') if rel.endswith(".md") else ("'", '"', "`")             # in prose `drishtiX` is a use, named
            quoted = before[-1:] in quotes and after[:1] == before[-1:]                 # 'drishtiX' alone: a key or a name, not a use
            if quoted or re.search(r"\b(?:var|let|const|function|class)\s+$", before) or re.match(r"\s*:(?!:)", after) or \
                    after.startswith("="):
                left.append((rel, text.count("\n", 0, m.start()) + 1, n))
                return n
            mark[("global", n)] += 1
            return sc.NAMESPACE + "." + to
        text = rx.sub(one, text)
    return text


def rewrite_events(text: str, events: dict[str, str], mark: Counter) -> str:
    for n, to in events.items():
        if n not in text:
            continue
        text, k = re.subn(r"(['\"`])" + re.escape(n) + r"\1", lambda m: m.group(1) + to + m.group(1), text)
        if k:
            mark[("event", n)] += k
    return text


def add_boot(text: str) -> str:
    if BOOT in text or "Drishti." not in text:
        return text
    lead = re.match(r"\s*/\*.*?\*/\s*", text, re.S)          # keep the licence block first, then the bootstrap, then the file's own notes
    cut = lead.end() if lead else 0
    return text[:cut] + BOOT + " " + BOOT_COMMENT + "\n" + text[cut:]


# -- patches and docs --------------------------------------------------------------------------------------------------------------
def apply_patches(root: Path, patches: list[dict], dry: bool, report: list) -> int:
    n = 0
    for p in patches:
        f = root / p["file"]
        if not f.is_file():
            report.append(f"patch: {p['file']} is not here")
            continue
        t = f.read_text(encoding="utf-8")
        if p["new"] in t and p["old"] not in t:
            continue
        if p["old"] not in t:
            report.append(f"patch: anchor not found in {p['file']}: {p['old'][:60]!r}")
            continue
        n += t.count(p["old"])
        if not dry:
            f.write_text(t.replace(p["old"], p["new"]), encoding="utf-8")
    return n


def docs_fragments() -> list[tuple[str, dict, str]]:
    """(file, where, text) from asset_prefix_docs.md: a '<!-- fragment: <file> | <where>[ | else-before:.. | heading:..] -->' line starts each."""
    if not DOCS.is_file():
        return []
    out = []
    parts = re.split(r"^<!-- fragment: (.+?) -->\n", DOCS.read_text(encoding="utf-8"), flags=re.M)
    for i in range(1, len(parts), 2):
        bits = [b.strip() for b in parts[i].split(" | ")]
        where = {"at": bits[1] if len(bits) > 1 else "append"}
        for b in bits[2:]:
            k, _, v = b.partition(":")
            where[k] = v
        out.append((bits[0], where, parts[i + 1].strip("\n") + "\n"))
    return out


def _line_at(lines: list[str], start: str) -> int:
    return next((i for i, ln in enumerate(lines) if ln.startswith(start)), -1)


def place(text: str, where: dict, body: str) -> tuple[str, str | None]:
    """The text with body placed; a note when it fell back to appending."""
    lines = text.split("\n")
    at = where["at"]
    kind, _, start = at.partition(":")
    block = body.rstrip("\n").split("\n")
    if kind == "end-of" and _line_at(lines, start) >= 0:
        i = _line_at(lines, start)
        level = len(start) - len(start.lstrip("#"))
        j = next((k for k in range(i + 1, len(lines)) if re.match(r"#{1,%d} " % level, lines[k])), len(lines))
        while j > i + 1 and not lines[j - 1].strip():
            j -= 1
        return "\n".join(lines[:j] + block + lines[j:]), None
    if kind == "before" and _line_at(lines, start) >= 0:
        i = _line_at(lines, start)
        return "\n".join(lines[:i] + block + [""] + lines[i:]), None
    if where.get("else-before") and _line_at(lines, where["else-before"]) >= 0:
        i = _line_at(lines, where["else-before"])
        head = [where["heading"], ""] if where.get("heading") else []
        return "\n".join(lines[:i] + head + block + [""] + lines[i:]), None
    note = None if at == "append" else f"{at!r} not found: appended"
    return text.rstrip("\n") + "\n\n" + body, note


def apply_docs(root: Path, dry: bool, report: list) -> int:
    n = 0
    for target, where, body in docs_fragments():
        f = root / target
        if not f.is_file():
            report.append(f"docs: {target} is not here")
            continue
        t = f.read_text(encoding="utf-8")
        if body.splitlines()[0] in t:
            continue
        t, note = place(t, where, body)
        if note:
            report.append(f"docs: {target}: {note}")
        n += 1
        if not dry:
            f.write_text(t, encoding="utf-8")
    return n


def _slug(text: str) -> str:
    """A heading's anchor as GitHub writes it (core/guides.py github_slug): lower case, punctuation dropped, each space a hyphen."""
    v = re.sub(r"<[^>]+>|[`*]", "", text).strip().lower()
    return re.sub(r"[^\w\- ]", "", v).replace(" ", "-")


def heading_renames(before: str, after: str) -> dict[str, str]:
    """{old anchor: new anchor} for the headings a rewrite changed (a file name in a heading changes its anchor)."""
    a, b = before.split("\n"), after.split("\n")
    out = {}
    if len(a) == len(b):
        for x, y in zip(a, b):
            if x != y and x.startswith("#") and y.startswith("#"):
                hx, hy = re.match(r"#{1,6}\s+(.*?)\s*#*\s*$", x), re.match(r"#{1,6}\s+(.*?)\s*#*\s*$", y)
                if hx and hy and _slug(hx.group(1)) != _slug(hy.group(1)):
                    out[_slug(hx.group(1))] = _slug(hy.group(1))
    return out


def run_after(root: Path, commands: list[str], dry: bool, report: list) -> int:
    """The generators whose output names a renamed file (the settings catalogue): run once the rewrite is done."""
    n = 0
    for c in commands:
        if dry:
            continue
        r = subprocess.run([sys.executable] + c.split()[1:] if c.startswith("python3 ") else c.split(), cwd=root, capture_output=True, text=True)
        if r.returncode:
            report.append(f"after: {c} failed: {(r.stderr or r.stdout).strip()[-300:]}")
        else:
            n += 1
    return n


# -- the run -----------------------------------------------------------------------------------------------------------------------
def plan(root: Path = sc.ROOT, m: dict | None = None) -> dict:
    """The mapping merged with a fresh scan of the tree."""
    return merge(m if m is not None else load_map(), sc.scan(root))


def run(root: Path, m: dict, dry: bool) -> dict:
    files = sc.tracked(root)
    report: list[str] = []
    stats = Counter()
    # 1. files
    for old, new in m["files"].items():
        a, b = root / sc.STATIC / old, root / sc.STATIC / new
        if a.is_file() and not b.exists():
            stats["files renamed"] += 1
            if not dry:
                subprocess.run(["git", "mv", str(a), str(b)], cwd=root, check=True)
        elif a.is_file() and b.exists():
            report.append(f"file: both {old} and {new} exist; left alone")
    if not dry:
        files = sc.tracked(root)
    refs = FileRefs(m["files"], files)
    moves = {n: v["to"] for n, v in m["globals"].items() if v.get("status") == "move"}
    events = {n: v["to"] for n, v in m["events"].items() if v.get("status") == "rename"}
    classes = {n: v["to"] for n, v in m["classes"].items() if v.get("status") == "rename"}
    datas = {n: "drishti-" + n for n, v in m["data_attrs"].items() if v.get("status") == "rename"}
    java_names = set()
    if moves:
        java = "\n".join(sc.read(root, f) for f in files if f.endswith(".java"))
        java_names = {n for n in moves if re.search(r"(?<![\w$])" + re.escape(n) + r"(?![\w$])", java)}
    mark: Counter = Counter()
    global_left: list = []
    out: dict[str, tuple[str, str]] = {}                          # rel -> (before, after): written once the anchors are known
    for rel in files:
        if not sc.is_text(rel) or sc.frozen(rel) or rel in sc.HISTORY or rel.startswith("tools/dev/asset_prefix"):
            continue
        t0 = sc.read(root, rel)
        if not t0:
            continue
        t = refs.rewrite(rel, t0, mark)
        suf = Path(rel).suffix
        if moves and suf in (".js", ".html", ".py", ".md", ".mjs", ".ts"):
            t = rewrite_globals(rel, t, moves, java_names, mark, global_left)
        if events and rel.startswith(sc.CONSOLE_CODE + ("docs/",)) and suf in (".js", ".html", ".py", ".md"):
            t = rewrite_events(t, events, mark)
        if classes and rel.startswith(sc.CONSOLE_CODE):
            if suf == ".css":
                t = rewrite_classes_css(t, classes, mark)
            elif suf in (".js", ".html", ".py"):
                t = rewrite_classes_code(t, classes, mark)
        if datas and rel.startswith(sc.CONSOLE_CODE) and suf in (".js", ".html", ".py", ".css"):
            t = rewrite_data(t, datas, mark)
        if rel.startswith(sc.OWN_JS) and suf == ".js" and moves:
            t = add_boot(t)
        if suf == ".md" and t != t0:
            m["anchors"].update(heading_renames(t0, t))
        out[rel] = (t0, t)
    anchors = {a: b for a, b in m["anchors"].items() if a != b}
    if anchors:
        rx = re.compile(r"#(" + "|".join(re.escape(a) for a in sorted(anchors, key=len, reverse=True)) + r")(?![\w-])")
        for rel, (t0, t) in out.items():
            if "#" in t and Path(rel).suffix in (".md", ".html", ".py", ".yaml", ".yml", ".json", ".js"):
                t2, k = rx.subn(lambda x: "#" + anchors[x.group(1)], t)
                if k:
                    mark[("anchor", "links")] += k
                    out[rel] = (t0, t2)
    changed = 0
    for rel, (t0, t) in out.items():
        if t != t0:
            changed += 1
            if not dry:
                (root / rel).write_text(t, encoding="utf-8")
    stats["text files changed"] = changed
    for (kind, _), k in mark.items():
        stats[{"file": "file references rewritten", "global": "global references moved", "event": "event names rewritten",
               "class": "class uses renamed", "data": "data- attribute uses renamed", "anchor": "heading links rewritten"}[kind]] += k
    stats["globals moved (names)"] = len({n for (k, n) in mark if k == "global"})
    stats["events renamed (names)"] = len({n for (k, n) in mark if k == "event"})
    stats["classes renamed (names)"] = len({n for (k, n) in mark if k == "class"})
    stats["data- attributes renamed (names)"] = len({n for (k, n) in mark if k == "data"})
    stats["patches applied"] = apply_patches(root, m["patches"], dry, report)
    stats["docs fragments added"] = apply_docs(root, dry, report)
    stats["generated files refreshed"] = run_after(root, m["after"], dry, report)
    stats["storage keys checked"] = len(m["storage"])
    stats["storage keys unprefixed"] = sum(1 for v in m["storage"].values() if v != "ok")
    by = defaultdict(list)
    for rel, line, b, why in refs.skipped:
        by[why].append(f"{rel}:{line} {b}")
    return {"stats": dict(stats), "skipped": dict(by), "global_left": global_left, "report": report}


def residue(root: Path = sc.ROOT) -> dict:
    """What is still to do in this tree: the guard test's question. Empty lists = nothing."""
    files = sc.tracked(root)
    fresh = sc.scan(root)
    return {
        "files": sorted(fresh["files"]),
        "globals": sorted(n for n, v in fresh["globals"].items() if v["status"] == "move"),
        "events": sorted(n for n, v in fresh["events"].items() if v["status"] == "rename"),
        "storage": sorted(k for k, v in fresh["storage"].items() if v != "ok"),
        "elements": sorted(k for k, v in fresh["elements"].items() if v != "ok"),
        "tracked": len(files),
    }


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0], formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("command", choices=("scan", "apply", "check"))
    ap.add_argument("--dry-run", action="store_true", help="apply: report, write nothing")
    ap.add_argument("--report", help="apply: write the full list of skipped references to this file")
    ap.add_argument("--root", default=str(sc.ROOT), help="the checkout (default: this repository)")
    a = ap.parse_args(argv)
    root = Path(a.root).resolve()
    if a.command == "check":
        r = residue(root)
        left = {k: v for k, v in r.items() if k != "tracked" and v}
        for k, v in left.items():
            print(f"{k}: {len(v)} left: {', '.join(v[:10])}{' ...' if len(v) > 10 else ''}")
        print("nothing left to do" if not left else "run: python3 tools/dev/asset_prefix.py apply")
        return 1 if left else 0
    m = plan(root)
    if a.command == "scan" or not a.dry_run:
        save_map(m)
        print(f"wrote {MAP.relative_to(sc.ROOT) if MAP.is_relative_to(sc.ROOT) else MAP}")
    if a.command == "scan":
        for k in SECTIONS:
            st = Counter(v.get("status", "ok") if isinstance(v, dict) else ("ok" if k != "files" else "file") for v in m[k].values())
            print(f"  {k}: {len(m[k])} {dict(st) if st else ''}")
        return 0
    res = run(root, m, a.dry_run)
    if not a.dry_run:
        save_map(m)                                                 # with the heading anchors the rewrite renamed
    for k, v in res["stats"].items():
        print(f"  {k}: {v}")
    for why, items in sorted(res["skipped"].items()):
        print(f"  skipped ({why}): {len(items)}")
    if res["global_left"]:
        print(f"  globals left (a declaration or an object key: by hand): {len(res['global_left'])}")
    for line in res["report"]:
        print("  " + line)
    if a.report:
        with open(a.report, "w", encoding="utf-8") as fh:
            for why, items in sorted(res["skipped"].items()):
                fh.write(f"## skipped: {why} ({len(items)})\n" + "".join(f"{x}\n" for x in items))
            fh.write(f"## globals left ({len(res['global_left'])})\n" + "".join(f"{r}:{ln} {n}\n" for r, ln, n in res["global_left"]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
