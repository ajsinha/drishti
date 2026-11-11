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
"""The Drishti Elements build: each library's ES modules into one bundled file, offline, with nothing but Python (no npm, no CDN).

    python3 tools/elements/build.py            # write every library's bundle and the generated modules
    python3 tools/elements/build.py --check    # exit 1 when anything written by the build is out of date (a static test asks the same)

The libraries live under ``drishti-console/web/static/elements/<library>/`` and are listed, each with its version, in the manifest
``drishti-console/web/static/elements/drishti_elements.json``. A library is plain ES modules under ``src/`` (usable as they are with
``import``) and one bundled file, ``drishti_<name>.js``, which this build writes: a classic script that registers the library on
``window.Drishti.elements`` (``Drishti.elements.core``, ``Drishti.elements.libs['drishti-core']``) and is also a CommonJS module under Node.

The build also writes the generated modules of drishti-core, each from its one home, so nothing is kept twice:

* ``src/drishti_manifest.js``      the manifest (libraries, versions, files, requirements) the loader reads;
* ``src/drishti_formats-table.js`` the server's named formats (``compute/drishti-rachana/src/main/resources/formats.yaml``);
* ``src/drishti_theme-table.js``   the themes and their colour tokens (``static/css/drishti_tokens.css``).

The module grammar the bundler accepts (anything else is refused with the file and line):

* ``import { a, b as c } from './x.js';`` and ``import * as ns from './x.js';`` (relative paths; into another library only through that
  library's entry file, which becomes the other library's bundle at run time);
* ``export function f`` / ``export async function f`` / ``export class C`` / ``export const x``;
* ``export { a, b as c };``, ``export { a } from './x.js';`` and ``export * from './x.js';``.

No ``export default``, no ``export let`` (a live binding would not survive bundling), no cycles."""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
ELEMENTS = ROOT / "drishti-console" / "web" / "static" / "elements"
MANIFEST = ELEMENTS / "drishti_elements.json"
FORMATS_YAML = ROOT / "compute/drishti-rachana" / "src" / "main" / "resources" / "formats.yaml"
TOKENS_CSS = ROOT / "drishti-console" / "web" / "static" / "css" / "drishti_tokens.css"
HEADER = "/*\n" + "".join(f" * {line}".rstrip() + "\n" for line in (ROOT / "config" / "license-header.txt").read_text(encoding="utf-8").strip().splitlines()) + " */\n"

NAME = r"[A-Za-z_$][\w$]*"
IMPORT = re.compile(r"^import\s+(\{[^}]*\}|\*\s+as\s+" + NAME + r")\s+from\s+(['\"])([^'\"]+)\2\s*;?[ \t]*$", re.M)
EXPORT_DECL = re.compile(r"^export\s+((?:async\s+)?function\*?\s+|class\s+|const\s+)(" + NAME + ")", re.M)
EXPORT_LIST = re.compile(r"^export\s*\{([^}]*)\}\s*(?:from\s*(['\"])([^'\"]+)\2)?\s*;?[ \t]*$", re.M)
EXPORT_STAR = re.compile(r"^export\s*\*\s*from\s*(['\"])([^'\"]+)\1\s*;?[ \t]*$", re.M)
ANY_TOP = re.compile(r"^(import|export)\b.*$", re.M)


class BuildError(Exception):
    """A module the bundler cannot take, in words, with the file and line."""


def manifest() -> dict:
    return json.loads(MANIFEST.read_text(encoding="utf-8"))


def short(lib: str) -> str:
    """drishti-core -> core: the library's name on ``Drishti.elements`` and in its bundle's file name."""
    return lib[len("drishti-"):] if lib.startswith("drishti-") else lib


def bundle_name(lib: str) -> str:
    return "drishti_" + short(lib).replace("-", "_") + ".js"


def line_of(text: str, pos: int) -> int:
    return text.count("\n", 0, pos) + 1


def specifiers(body: str) -> list[tuple[str, str]]:
    """``a, b as c`` -> [(a, a), (b, c)]: (the name in the other module, the name here)."""
    out = []
    for part in body.split(","):
        part = re.sub(r"/\*.*?\*/|//[^\n]*", "", part, flags=re.S).strip()
        if not part:
            continue
        m = re.fullmatch(r"(" + NAME + r")(?:\s+as\s+(" + NAME + r"))?", part)
        if not m:
            raise BuildError(f"cannot read the import or export name '{part}'")
        out.append((m.group(1), m.group(2) or m.group(1)))
    return out


class Module:
    """One ES module of a library, read into what the bundle needs: its imports, its exports and its body without the module syntax."""

    def __init__(self, lib: str, path: Path, libroot: Path):
        self.lib, self.path, self.libroot = lib, path, libroot
        self.rel = path.relative_to(libroot).as_posix()
        self.text = path.read_text(encoding="utf-8")
        self.imports: list[tuple[str, object]] = []          # (target, [(theirs, ours)] or a namespace name)
        self.exports: dict[str, str] = {}                    # exported name -> local name
        self.reexports: list[tuple[str, list[tuple[str, str]]]] = []
        self.stars: list[str] = []
        self.body = self._read()

    def where(self, pos: int) -> str:
        return f"{self.path.relative_to(ROOT)}:{line_of(self.text, pos)}"

    def resolve(self, spec: str, pos: int) -> str:
        if not spec.startswith("."):
            raise BuildError(f"{self.where(pos)}: '{spec}' is not a relative path (a library imports only its own modules and other libraries' entries)")
        target = (self.path.parent / spec).resolve()
        if not target.is_file():
            raise BuildError(f"{self.where(pos)}: '{spec}' does not exist")
        return str(target)

    def _read(self) -> str:
        text = self.text
        cuts: list[tuple[int, int, str]] = []
        for m in IMPORT.finditer(text):
            target = self.resolve(m.group(3), m.start())
            what = m.group(1)
            self.imports.append((target, what.split()[-1] if what.startswith("*") else specifiers(what[1:-1])))
            cuts.append((m.start(), m.end(), ""))
        for m in EXPORT_LIST.finditer(text):
            names = specifiers(m.group(1))
            if m.group(3):
                self.reexports.append((self.resolve(m.group(3), m.start()), names))
            else:
                for local, exported in names:
                    self.exports[exported] = local
            cuts.append((m.start(), m.end(), ""))
        for m in EXPORT_STAR.finditer(text):
            self.stars.append(self.resolve(m.group(2), m.start()))
            cuts.append((m.start(), m.end(), ""))
        for m in EXPORT_DECL.finditer(text):
            self.exports[m.group(2)] = m.group(2)
            cuts.append((m.start(), m.start() + len("export "), ""))
        covered = sorted(cuts)
        for m in ANY_TOP.finditer(text):
            if not any(a <= m.start() < b for a, b, _ in covered):
                raise BuildError(f"{self.where(m.start())}: the bundler does not take this module syntax: {m.group(0).strip()[:80]} "
                                 "(no export default, no export let; see tools/elements/build.py)")
        if re.search(r"\bimport\.meta\b", text):
            raise BuildError(f"{self.path.relative_to(ROOT)}: import.meta does not survive bundling")
        out, at = [], 0
        for a, b, rep in covered:
            out.append(text[at:a])
            out.append(rep)
            at = b
        out.append(text[at:])
        body = "".join(out)
        body = re.sub(r"^/\*\n( \*[^\n]*\n)+? \*/\n", "", body, count=1)      # the licence header: once, at the bundle's top
        # the bundle runs, the modules document: comments on lines of their own and blank lines are left out of it (the API reference
        # and the modules keep them)
        body = re.sub(r"(?ms)^[ \t]*/\*(?:(?!\*/).)*\*/[ \t]*\n", "", body)
        body = re.sub(r"(?m)^[ \t]*//[^\n]*\n", "", body)
        body = re.sub(r"\n[ \t]*\n+", "\n", body)
        return body.strip("\n")


class Library:
    def __init__(self, name: str, spec: dict, all_specs: dict):
        self.name, self.spec = name, spec
        self.root = ELEMENTS / name
        self.entry = (self.root / spec["entry"]).resolve()
        self.requires = list(spec.get("requires") or [])
        for r in self.requires:
            if r not in all_specs:
                raise BuildError(f"{name} requires {r}, which the manifest does not list")
        self.modules: dict[str, Module] = {}
        self._load(str(self.entry))
        self.order = self._order()

    def owner(self, target: str) -> str | None:
        p = Path(target)
        for lib in manifest()["libraries"]:
            if (ELEMENTS / lib) in p.parents:
                return lib
        return None

    def _load(self, target: str) -> None:
        if target in self.modules:
            return
        m = Module(self.name, Path(target), self.root)
        self.modules[target] = m
        for t in [t for t, _ in m.imports] + [t for t, _ in m.reexports] + m.stars:
            lib = self.owner(t)
            if lib == self.name:
                self._load(t)
            elif lib is None:
                raise BuildError(f"{m.path.relative_to(ROOT)} imports {t}, outside every library")
            elif lib not in self.requires:
                raise BuildError(f"{m.path.relative_to(ROOT)} imports from {lib}, which {self.name}'s manifest entry does not require")
            elif Path(t) != (ELEMENTS / lib / manifest()["libraries"][lib]["entry"]).resolve():
                raise BuildError(f"{m.path.relative_to(ROOT)} imports {Path(t).relative_to(ELEMENTS)}: another library only through its entry")

    def _order(self) -> list[str]:
        done: list[str] = []
        state: dict[str, int] = {}

        def visit(t: str, trail: list[str]) -> None:
            if state.get(t) == 2:
                return
            if state.get(t) == 1:
                raise BuildError("a cycle of imports: " + " -> ".join(self.modules[x].rel for x in trail + [t]))
            state[t] = 1
            m = self.modules[t]
            for d in [x for x, _ in m.imports] + [x for x, _ in m.reexports] + m.stars:
                if d in self.modules:
                    visit(d, trail + [t])
            state[t] = 2
            done.append(t)

        for t in sorted(self.modules):
            visit(t, [])
        return done

    def ref(self, target: str) -> str:
        if target in self.modules:
            return f"__m[{json.dumps(self.modules[target].rel)}]"
        return f"__lib({json.dumps(self.owner(target))})"

    def export_names(self, target: str, seen: set | None = None) -> list[str]:
        """Every name a module of this library exports, re-exports included."""
        m = self.modules[target]
        names = list(m.exports)
        for _, specs in m.reexports:
            names += [exported for _, exported in specs]
        for s in m.stars:
            names += self.export_names(s) if s in self.modules else []
        return names

    def render(self) -> str:
        parts = []
        for t in self.order:
            m = self.modules[t]
            lines = [f"  /* ---- {m.rel} ---- */", f"  __m[{json.dumps(m.rel)}] = (function () {{"]
            for target, what in m.imports:
                if isinstance(what, str):
                    lines.append(f"    var {what} = {self.ref(target)};")
                else:
                    lines.append("    var " + ", ".join(f"{ours} = {self.ref(target)}.{theirs}" for theirs, ours in what) + ";")
            lines.append("\n".join(("    " + ln) if ln.strip() else "" for ln in m.body.split("\n")))
            ret = [f"{json.dumps(k)}: {v}" for k, v in m.exports.items()]
            for target, specs in m.reexports:
                ret += [f"{json.dumps(exported)}: {self.ref(target)}.{theirs}" for theirs, exported in specs]
            obj = "{ " + ", ".join(ret) + " }"
            if m.stars:
                obj = "Object.assign({}, " + ", ".join(self.ref(s) for s in m.stars) + ", " + obj + ")"
            lines.append(f"    return {obj};")
            lines.append("  })();")
            parts.append("\n".join(lines))
        entry_rel = self.modules[str(self.entry)].rel
        version = self.spec["version"]
        node_paths = {r: "../" + r + "/" + bundle_name(r) for r in self.requires}
        return (HEADER +
                f"/* {self.name} {version}: Drishti Elements. GENERATED by tools/elements/build.py from {self.name}/src/ (edit the modules, then\n"
                f"   run the build; a static test refuses a stale bundle). A classic script: it registers the library as\n"
                f"   window.Drishti.elements.{short(self.name).replace('-', '_')} (and .libs[{json.dumps(self.name)}]); under Node it is the CommonJS module of the same API. */\n"
                "(function (root, factory) {\n"
                "  'use strict';\n"
                "  var D = root.Drishti = root.Drishti || {}, E = D.elements = D.elements || {}, libs = E.libs = E.libs || {};\n"
                "  var cs = typeof document !== 'undefined' && document.currentScript;\n"
                "  if (!E.base && cs && cs.src) { E.base = cs.src.replace(/[^/]*\\/[^/]*(\\?.*)?$/, ''); }   /* .../elements/ */\n"
                f"  var needs = {json.dumps(node_paths)};\n"
                "  var lib = function (name) {\n"
                "    if (libs[name]) { return libs[name]; }\n"
                "    if (typeof module === 'object' && module && typeof require === 'function' && needs[name]) { return require(needs[name]); }\n"
                f"    throw new Error({json.dumps(self.name)} + ' needs ' + name + ': load it first (Drishti.elements.load)');\n"
                "  };\n"
                "  var api = factory(lib);\n"
                f"  libs[{json.dumps(self.name)}] = api; E[{json.dumps(short(self.name).replace('-', '_'))}] = api;\n"
                "  if (typeof module === 'object' && module && module.exports) { module.exports = api; }\n"
                "  if (api.install) { api.install(root); }\n"
                "})(typeof window !== 'undefined' ? window : globalThis, function (__lib) {\n"
                "  'use strict';\n"
                "  var __m = {};\n"
                + "\n".join(parts) + "\n"
                f"  return Object.freeze(Object.assign({{ library: {json.dumps(self.name)}, version: {json.dumps(version)} }}, __m[{json.dumps(entry_rel)}]));\n"
                "});\n")


# -- the generated modules of drishti-core -------------------------------------------------------------------------------------------
def gen_header(what: str, source: str) -> str:
    return HEADER + f"/* {what}. GENERATED by tools/elements/build.py from {source}: do not edit (a static test refuses a stale copy). */\n"


def gen_manifest(m: dict) -> str:
    libs = {}
    for name, spec in m["libraries"].items():
        libs[name] = {"version": spec["version"], "title": spec.get("title", ""), "bundle": name + "/" + bundle_name(name),
                      "entry": name + "/" + spec["entry"], "global": short(name).replace("-", "_"),
                      "requires": list(spec.get("requires") or []), "css": list(spec.get("css") or []),
                      "scripts": list(spec.get("scripts") or []), "module": str(spec.get("module") or "core")}
    body = json.dumps({"elements": m["elements"], "libraries": libs}, indent=2, ensure_ascii=False)
    return (gen_header("The Elements manifest: every library, its version, its files and what it requires", "static/elements/drishti_elements.json")
            + "\n/** The libraries of Drishti Elements by name: version, bundle and entry (relative to the elements folder), global name, the\n"
            " *  libraries each requires, the style sheets and the vendored engine's scripts (both relative to the static root) it needs, and the\n"
            " *  module it ships with (core: every installation; a domain module's library is loaded only where that module is on for the person).\n"
            " *  @type {{elements: string, libraries: Object<string, {version: string, title: string, bundle: string, entry: string, global: string, requires: string[], css: string[], scripts: string[], module: string}>}} */\n"
            f"export const MANIFEST = Object.freeze({body});\n")


def gen_formats() -> str:
    raw = yaml.safe_load(FORMATS_YAML.read_text(encoding="utf-8"))["formats"]
    table = {}
    for name in sorted(raw):
        f = raw[name] or {}
        table[name] = {"type": f.get("type", "number"), "decimals": int(f.get("decimals", 0)), "grouping": bool(f.get("grouping", True)),
                       "sign": f.get("sign", "auto"), "scale": float(f.get("scale", 1)), "suffix": f.get("suffix"), "pattern": f.get("pattern")}
    body = json.dumps(table, indent=2, ensure_ascii=False)
    return (gen_header("The server's named formats (fmt: amount0, pct2, compact, dmy, ...)", "compute/drishti-rachana/src/main/resources/formats.yaml")
            + "\n/** The bundled named formats, exactly as the server reads them (Formats.java): a pack's or a site's formats are added at run\n"
            " *  time with defineFormats().\n"
            " *  @type {Object<string, {type: string, decimals: number, grouping: boolean, sign: string, scale: number, suffix: ?string, pattern: ?string}>} */\n"
            f"export const SERVER_FORMATS = Object.freeze({body});\n")


THEME_BLOCK = re.compile(r"((?::root(?:\[data-theme=\"[\w-]+\"\])?\s*,?\s*)+)\{([^}]*)\}", re.S)


def gen_themes() -> str:
    css = re.sub(r"/\*.*?\*/", "", TOKENS_CSS.read_text(encoding="utf-8"), flags=re.S)
    themes: dict[str, dict] = {}
    order: list[str] = []
    for m in THEME_BLOCK.finditer(css):
        sel, decl = m.group(1), m.group(2)
        names = re.findall(r'data-theme="([\w-]+)"', sel)
        if not names:
            continue
        tokens, scheme = {}, None
        for d in decl.split(";"):
            if ":" not in d:
                continue
            k, v = (x.strip() for x in d.split(":", 1))
            if k.startswith("--d-"):
                tokens[k] = " ".join(v.split())
            elif k == "color-scheme":
                scheme = v
        for n in names:
            if n not in themes:
                themes[n] = {"scheme": None, "tokens": {}}
                order.append(n)
            themes[n]["tokens"].update(tokens)
            themes[n]["scheme"] = scheme or themes[n]["scheme"]
    base = dict(themes["terminal"]["tokens"])
    for n in order:                              # a theme names only what it changes; the rest is the default's (:root)
        themes[n]["tokens"] = {**base, **themes[n]["tokens"]}
        themes[n]["scheme"] = themes[n]["scheme"] or "dark"
    body = json.dumps({n: themes[n] for n in order}, indent=2, ensure_ascii=False)
    return (gen_header("The themes and their colour tokens", "static/css/drishti_tokens.css, the one home of every colour")
            + "\n/** Every theme (data-theme on <html>): light or dark, and each --d-* token's value. The first is the default.\n"
            " *  @type {Object<string, {scheme: string, tokens: Object<string, string>}>} */\n"
            f"export const THEME_TABLE = Object.freeze({body});\n")


def outputs() -> dict[Path, str]:
    m = manifest()
    core = ELEMENTS / "drishti-core" / "src"
    out = {core / "drishti_manifest.js": gen_manifest(m), core / "drishti_formats-table.js": gen_formats(), core / "drishti_theme-table.js": gen_themes()}
    for name, spec in m["libraries"].items():
        out[ELEMENTS / name / bundle_name(name)] = None  # rendered after the generated modules are in place
    return out


def build(check: bool) -> int:
    planned = outputs()
    stale = []
    for p, text in planned.items():
        if text is None:
            continue
        if not p.is_file() or p.read_text(encoding="utf-8") != text:
            stale.append(p)
            if not check:
                p.write_text(text, encoding="utf-8")
    m = manifest()
    for name, spec in m["libraries"].items():
        p = ELEMENTS / name / bundle_name(name)
        text = Library(name, spec, m["libraries"]).render()
        if not p.is_file() or p.read_text(encoding="utf-8") != text:
            stale.append(p)
            if not check:
                p.write_text(text, encoding="utf-8")
    for p in stale:
        print(("stale: " if check else "wrote: ") + str(p.relative_to(ROOT)))
    if check and stale:
        print("elements: run python3 tools/elements/build.py", file=sys.stderr)
        return 1
    return 0


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--check", action="store_true", help="exit 1 when a bundle or a generated module is out of date; write nothing")
    a = ap.parse_args(argv)
    try:
        return build(a.check)
    except BuildError as e:
        print(f"elements: {e}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
