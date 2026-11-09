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
"""Drishti Elements distributed: the files of each library, the themes, the integrity record and the three forms other applications
take them in (docs/architecture/ELEMENTS.md section 8.7; the guide docs/guides/EMBED_GUIDE.md). Offline, Python only: no npm,
no network, nothing published anywhere.

* **the tree**: the console's static layout (``elements/<library>/…``, ``css/``, ``vendor/``) plus ``themes/``, each library's
  ``drishti-<name>.d.ts``, README and licence notice, and ``drishti-elements.json`` (what is in it, with a SHA-384 SRI hash of every
  file). The loader of drishti-core works in it unchanged: the folder above ``elements/`` is its static root;
* ``drishti-elements-<version>.zip``: the tree (the release archive);
* ``@drishti/elements-<name>`` npm tarballs (``npm pack``'s layout, written with tarfile) for an internal registry;
* the WebJar's tree under ``META-INF/resources/webjars/drishti-elements/<version>/``, which the Maven module ``drishti-elements``
  (profile ``elements``) jars.

The licence: a library with a ``module`` in the manifest (drishti-risk: ``banking``) is left out unless its domain is included
(``include_domains``); until the licence check (2.9.11.j) that is the only gate. Every output is reproducible: the same sources make
the same bytes (fixed timestamps, sorted entries)."""
from __future__ import annotations

import base64
import gzip
import hashlib
import io
import json
import re
import tarfile
import zipfile
from pathlib import Path

import dts

ROOT = Path(__file__).resolve().parents[2]
STATIC = ROOT / "drishti-console" / "web" / "static"
ELEMENTS = STATIC / "elements"
MANIFEST = ELEMENTS / "drishti_elements.json"
TOKENS = "css/drishti_tokens.css"
HEADER_TEXT = (ROOT / "config" / "license-header.txt").read_text(encoding="utf-8").strip()
NPM_SCOPE = "@drishti"
WEBJAR = "META-INF/resources/webjars/drishti-elements"
TGZ_MTIME = 499162500                     # npm pack's fixed timestamp (1985-10-26 08:15 UTC): reproducible tarballs
ZIP_TIME = (1980, 1, 1, 0, 0, 0)
THIRD_PARTY = {"vendor/echarts/": "Apache ECharts, Apache License 2.0 (vendor/echarts/LICENSE and NOTICE)",
               "vendor/codemirror/": "CodeMirror 5, MIT licence (vendor/codemirror/LICENSE)",
               "vendor/echarts/maps/": "Natural Earth map shapes, public domain"}


class DistError(Exception):
    """A request the distribution refuses, in words (an unknown library, a licensed one without its domain, a version mismatch)."""


def css_comment(text: str) -> str:
    return "/*\n" + "".join(f" * {ln}".rstrip() + "\n" for ln in text.splitlines()) + " */\n"


HEADER_CSS = css_comment(HEADER_TEXT)


def sri(data: bytes) -> str:
    return "sha384-" + base64.b64encode(hashlib.sha384(data).digest()).decode("ascii")


def short(lib: str) -> str:
    return lib[len("drishti-"):] if lib.startswith("drishti-") else lib


def bundle_name(lib: str) -> str:
    return "drishti_" + short(lib).replace("-", "_") + ".js"


def product_version() -> str:
    m = re.search(r"<artifactId>drishti-parent</artifactId>\s*<version>([^<]+)</version>", (ROOT / "pom.xml").read_text(encoding="utf-8"))
    return m.group(1) if m else "unknown"


# -- the train and the libraries a request names ----------------------------------------------------------------------------------------
class Train:
    """The release train (``drishti_elements.json``): every library, its version, requirements, files and domain module."""

    def __init__(self, manifest_path: Path = MANIFEST):
        self.raw = json.loads(manifest_path.read_text(encoding="utf-8"))
        self.version = self.raw["elements"]
        self.libs: dict[str, dict] = self.raw["libraries"]

    def name(self, given: str) -> str:
        n = given.strip()
        n = n if n.startswith("drishti-") else "drishti-" + n
        if n not in self.libs:
            raise DistError(f"there is no library {given} in this build (built: {', '.join(short(x) for x in self.libs)})")
        return n

    def domain(self, lib: str) -> str | None:
        return self.libs[lib].get("module")

    def allowed(self, include_domains: set[str]) -> list[str]:
        """Every library the licence allows: the product's, and a domain's when that domain is included."""
        return [n for n in self.libs if not self.domain(n) or self.domain(n) in include_domains]

    def resolve(self, names: list[str], include_domains: set[str] = frozenset()) -> list[str]:
        """The libraries named and every one they require, deepest first, each once; a domain library without its domain is refused."""
        out: list[str] = []

        def visit(n: str, trail: list[str]) -> None:
            if n in trail:
                raise DistError("libraries require each other: " + " -> ".join(trail + [n]))
            d = self.domain(n)
            if d and d not in include_domains:
                raise DistError(f"{n} is licensed with the {d} module: it is packed only with --include-domain {d} "
                                "(until the licence check of 2.9.11.j, the only gate)")
            for r in self.libs[n].get("requires") or []:
                visit(r, trail + [n])
            if n not in out:
                out.append(n)

        for g in names:
            if g.strip() == "all":
                for n in self.allowed(set(include_domains)):
                    visit(n, [])
            elif g.strip():
                visit(self.name(g), [])
        if not out:
            raise DistError("no library named (for example --libs core,grid,chart)")
        return out

    def server(self, lib: str) -> str:
        spec = self.libs[lib]
        return "licence" if spec.get("module") else ("server" if spec.get("server") else "none")


# -- the themes: drishti_tokens.css read into blocks ------------------------------------------------------------------------------------
def css_blocks(text: str) -> list[tuple[str, str]]:
    """The top-level blocks of a style sheet: (prelude, body), comments dropped; an @media block's body is its inside."""
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    out, i, n = [], 0, len(text)
    while i < n:
        j = text.find("{", i)
        if j < 0:
            break
        depth, k = 0, j
        while k < n:
            if text[k] == "{":
                depth += 1
            elif text[k] == "}":
                depth -= 1
                if depth == 0:
                    break
            k += 1
        out.append((" ".join(text[i:j].split()), text[j + 1:k]))
        i = k + 1
    return out


THEME_SELECTOR = re.compile(r'^(?::root(?:\[data-theme="[\w-]+"\])?\s*,?\s*)+$')


def declarations(body: str) -> dict[str, str]:
    out = {}
    for d in body.split(";"):
        if ":" in d:
            k, v = (x.strip() for x in d.split(":", 1))
            if k:
                out[k] = " ".join(v.split())
    return out


def themes(tokens_css: str | None = None) -> dict[str, dict]:
    """Every theme of drishti_tokens.css (data-theme on <html>) with its full set of declarations (the default's, then its own), the
    default first; and the blocks every theme shares (the fixed colours, the scales, print) under the key ``""``."""
    css = tokens_css if tokens_css is not None else (STATIC / TOKENS).read_text(encoding="utf-8")
    named: dict[str, dict[str, str]] = {}
    default = None
    shared: list[str] = []
    for prelude, body in css_blocks(css):
        if THEME_SELECTOR.match(prelude) and "data-theme" in prelude:
            names = re.findall(r'data-theme="([\w-]+)"', prelude)
            if re.search(r"(^|,)\s*:root\s*(,|$)", prelude):
                default = default or names[0]
            for t in names:
                named.setdefault(t, {}).update(declarations(body))
        elif prelude.startswith("@media (prefers-color-scheme"):
            continue                                             # the no-theme fallback: a page that picks a theme does not need it
        else:
            shared.append(f"{prelude} {{{body.rstrip()}\n}}")
    if not named:
        raise DistError(f"{TOKENS} defines no theme")
    default = default or next(iter(named))
    base = named[default]
    out = {default: {"default": True, "decls": dict(base)}}
    for t, d in named.items():
        if t != default:
            out[t] = {"default": False, "decls": {**base, **d}}
    for t in out:
        out[t]["scheme"] = out[t]["decls"].get("color-scheme", "dark")
    out[""] = {"shared": shared}
    return out


def theme_names(table: dict | None = None) -> list[str]:
    return [t for t in (table or themes()) if t]


def theme_alias(name: str, table: dict | None = None) -> str:
    """``dark`` is the default theme (or the first dark one), ``light`` the first light one; any other name is a theme of the file."""
    table = table or themes()
    names = theme_names(table)
    if name in names:
        return name
    if name in ("dark", "light"):
        for t in names:
            if (table[t]["scheme"] == name) and (name == "light" or table[t]["default"]):
                return t
        for t in names:
            if table[t]["scheme"] == name:
                return t
    raise DistError(f"there is no theme {name} (themes: {', '.join(['dark', 'light'] + names)})")


def theme_css(name: str, table: dict | None = None) -> str:
    """One theme as a complete token sheet: every token on :root (so no data-theme is needed), then the shared scales and print."""
    table = table or themes()
    t = table[theme_alias(name, table)]
    lines = [HEADER_CSS, f"/* Drishti Elements theme {theme_alias(name, table)} ({t['scheme']}): every design token of drishti-core on :root.\n"
             "   An application overrides a token by setting it after this sheet (see the guide, Theming). Generated from drishti_tokens.css. */\n",
             f':root,\n:root[data-theme="{theme_alias(name, table)}"] {{\n']
    lines += [f"  {k}: {v};\n" for k, v in t["decls"].items()]
    lines.append("}\n\n")
    lines += [b + "\n\n" for b in table[""]["shared"]]
    return "".join(lines).rstrip("\n") + "\n"


# -- the files of a library, laid out as the console's static root ----------------------------------------------------------------------
def owner(rel: str) -> str | None:
    m = re.match(r"elements/(drishti-[\w-]+)/", rel)
    return m.group(1) if m else None


def folder_files(rel_dir: str) -> dict[str, bytes]:
    base = STATIC / rel_dir
    if not base.is_dir():
        raise DistError(f"{rel_dir} is missing under the static root")
    return {f.relative_to(STATIC).as_posix(): f.read_bytes() for f in sorted(base.rglob("*")) if f.is_file()}


def licence_notice(train: Train, libs: list[str], extra: list[str] = ()) -> str:
    third = [w for prefix, w in THIRD_PARTY.items() if any(prefix in x for x in extra)]
    domain = [f"{n} is licensed with the {train.domain(n)} module: only customers licensed for that module may use it." for n in libs if train.domain(n)]
    return (HEADER_TEXT + "\n\nDrishti Elements " + train.version + " (Drishti " + product_version() + "): " + ", ".join(f"{n} {train.libs[n]['version']}" for n in libs)
            + ".\nLicensed to the customer under the Drishti licence; not for redistribution outside the licensed organisation. "
            "Nothing of it is published to any public registry or CDN.\n"
            + ("".join("\n" + d for d in domain) + "\n" if domain else "")
            + ("\nThird-party software inside:\n" + "".join(f"- {w}\n" for w in third) if third else ""))


def changelog_lines(libs: list[str]) -> dict[str, list[dict]]:
    import sys
    sys.path.insert(0, str(ROOT / "tools" / "docs"))
    import elements_catalogue
    return elements_catalogue.changelog(ROOT, libs)


def readme(train: Train, lib: str, npm: bool) -> str:
    spec = train.libs[lib]
    s = short(lib)
    pkg = f"{NPM_SCOPE}/elements-{s}"
    needs = {"none": "No server is needed.", "server": "It needs a Drishti server.",
             "licence": f"It needs a Drishti server with the {spec.get('module')} module and that module's licence."}[train.server(lib)]
    req = ", ".join(spec.get("requires") or []) or "nothing"
    imp = f"import {{ ... }} from '{pkg}';" if npm else f"import {{ ... }} from './elements/{lib}/src/drishti_index.js';"
    return (f"<!--\n{HEADER_TEXT}\n-->\n# {lib} {spec['version']}\n\n{spec.get('title', '')}.\n\n{needs} It requires {req}.\n\n"
            "## Use it\n\n"
            f"- As ES modules (tree-shakable): `{imp}`\n"
            f"- As one classic script: `{'' if npm else 'elements/' + lib + '/'}{bundle_name(lib)}` registers `Drishti.elements.{s.replace('-', '_')}`"
            " (load the libraries it requires first).\n"
            + (f"- Its style sheet{'s' if len(spec.get('css') or []) > 1 else ''}: " + ", ".join(f"`{Path(c).name if npm else c}`" for c in spec.get("css") or []) + ".\n" if spec.get("css") else "")
            + f"- Types: `drishti-{s}.d.ts`, generated from the typed JSDoc.\n\n"
            "The guide: *Using Drishti Elements in your app* (docs/guides/EMBED_GUIDE.md); the API reference: ELEMENTS_API.md.\n"
            "The licence: LICENSE-NOTICE.txt.\n")


def library_tree(train: Train, lib: str, spec_of=None) -> dict[str, bytes]:
    """One library's files under static-root paths: its ES modules, bundle, style sheets (its own), vendored engine and assets with
    their licences, its declarations, README and licence notice; drishti-core also the token sheet and every theme."""
    spec = train.libs[lib]
    out: dict[str, bytes] = {}
    out.update(folder_files(f"elements/{lib}/src"))
    out[f"elements/{lib}/{bundle_name(lib)}"] = (ELEMENTS / lib / bundle_name(lib)).read_bytes()
    for c in spec.get("css") or []:
        if owner(c) in (None, lib):
            out[c] = (STATIC / c).read_bytes()
    for s in spec.get("scripts") or []:
        out[s] = (STATIC / s).read_bytes()
        for lic in ("LICENSE", "NOTICE"):
            p = STATIC / Path(s).parent / lic
            if p.is_file():
                out[(Path(s).parent / lic).as_posix()] = p.read_bytes()
    for a in spec.get("assets") or []:
        out.update(folder_files(a.rstrip("/")))
    if lib == "drishti-core":
        table = themes()
        for t in theme_names(table):
            out[f"themes/drishti-theme-{t}.css"] = theme_css(t, table).encode("utf-8")
    spec_of = spec_of or (lambda other: f"../{other}/drishti-{short(other)}")
    d = dts.declarations(lib, (ELEMENTS / lib / spec["entry"]).resolve(), spec_of)
    out[f"elements/{lib}/drishti-{short(lib)}.d.ts"] = (HEADER_CSS + f"/* {lib} {spec['version']}: TypeScript declarations, generated from the typed JSDoc "
                                                        "by tools/elements/dts.py. */\n" + d).encode("utf-8")
    out[f"elements/{lib}/README.md"] = readme(train, lib, npm=False).encode("utf-8")
    out[f"elements/{lib}/LICENSE-NOTICE.txt"] = licence_notice(train, [lib], list(out)).encode("utf-8")
    return out


def integrity(files: dict[str, bytes]) -> dict[str, dict]:
    return {p: {"bytes": len(b), "integrity": sri(b)} for p, b in sorted(files.items())}


def full_tree(train: Train, include_domains: set[str] = frozenset()) -> dict[str, bytes]:
    """Every library the licence allows, the themes, the train, the changelog lines, the notice, and ``drishti-elements.json``."""
    libs = train.resolve(["all"], set(include_domains))
    files: dict[str, bytes] = {}
    for lib in libs:
        files.update(library_tree(train, lib))
    kept = dict(train.raw)
    kept["libraries"] = {n: s for n, s in train.libs.items() if n in libs}
    files["elements/drishti_elements.json"] = (json.dumps(kept, indent=2, ensure_ascii=False) + "\n").encode("utf-8")
    logs = changelog_lines(libs)
    cl = [f"<!--\n{HEADER_TEXT}\n-->\n# Drishti Elements {train.version}: the libraries' changelog\n",
          "Each library's lines of the product CHANGELOG, newest release first.\n"]
    for lib in libs:
        cl.append(f"\n## {lib} {train.libs[lib]['version']}\n")
        cl += [f"\n- {e['release']}: {e['text']}" for e in logs.get(lib, [])] or ["\n- (no lines yet)"]
        cl.append("\n")
    files["CHANGELOG.md"] = "".join(cl).encode("utf-8")
    files["LICENSE-NOTICE.txt"] = licence_notice(train, libs, list(files)).encode("utf-8")
    files["README.md"] = (f"<!--\n{HEADER_TEXT}\n-->\n# Drishti Elements {train.version}\n\nDrishti {product_version()}. Libraries: "
                          + ", ".join(f"{n} {train.libs[n]['version']}" for n in libs) + ".\n\n"
                          "Serve this folder as it is: `elements/` holds the libraries (each with its ES modules under `src/`, one bundled "
                          "script, its `.d.ts` and README), `css/` the token sheet and the components' style sheets, `themes/` one sheet per "
                          "theme, `vendor/` the engines (ECharts, CodeMirror) and the map shapes. `drishti-elements.json` lists every file "
                          "with its SHA-384 integrity hash for `<script integrity>`.\n\nThe guide: docs/guides/EMBED_GUIDE.md.\n").encode("utf-8")
    files["drishti-elements.json"] = dist_manifest(train, libs, files, include_domains)
    return files


def dist_manifest(train: Train, libs: list[str], files: dict[str, bytes], include_domains) -> bytes:
    body = {"name": "drishti-elements", "version": train.version, "drishti": product_version(),
            "domains": sorted(include_domains), "excluded": [n for n in train.libs if n not in libs],
            "libraries": {n: {"version": train.libs[n]["version"], "title": train.libs[n].get("title", ""),
                              "requires": train.libs[n].get("requires") or [], "module": train.libs[n].get("module"),
                              "server": train.server(n), "bundle": f"elements/{n}/{bundle_name(n)}",
                              "entry": f"elements/{n}/{train.libs[n]['entry']}", "types": f"elements/{n}/drishti-{short(n)}.d.ts",
                              "css": train.libs[n].get("css") or []} for n in libs},
            "themes": [p[len("themes/drishti-theme-"):-4] for p in sorted(files) if p.startswith("themes/")],
            "files": integrity({p: b for p, b in files.items() if p != "drishti-elements.json"})}
    return (json.dumps(body, indent=2, ensure_ascii=False) + "\n").encode("utf-8")


# -- the forms --------------------------------------------------------------------------------------------------------------------------
def write_zip(files: dict[str, bytes], path: Path, top: str) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for p in sorted(files):
            info = zipfile.ZipInfo(f"{top}/{p}", date_time=ZIP_TIME)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            z.writestr(info, files[p])
    return path


def write_tgz(files: dict[str, bytes], path: Path) -> Path:
    """An ``npm pack`` tarball: every file under ``package/``, sorted, with npm's fixed time, mode 644, no owner."""
    path.parent.mkdir(parents=True, exist_ok=True)
    raw = io.BytesIO()
    with gzip.GzipFile(fileobj=raw, mode="wb", mtime=0, compresslevel=9) as gz:
        with tarfile.open(fileobj=gz, mode="w", format=tarfile.USTAR_FORMAT) as tar:
            for p in sorted(files):
                info = tarfile.TarInfo("package/" + p)
                info.size, info.mtime, info.mode, info.uid, info.gid, info.uname, info.gname = len(files[p]), TGZ_MTIME, 0o644, 0, 0, "", ""
                tar.addfile(info, io.BytesIO(files[p]))
    path.write_bytes(raw.getvalue())
    return path


CROSS_IMPORT = re.compile(r"""(from\s+['"])\.\./\.\./(drishti-[\w-]+)/src/drishti_index\.js(['"])""")
CROSS_MODULE = re.compile(r"""(['"])\.\./\.\./(drishti-[\w-]+)/src/""")


def npm_files(train: Train, lib: str) -> dict[str, bytes]:
    """The npm package of one library: its tree flattened (``elements/<lib>/`` and ``css/`` to the package root, ``vendor/`` and
    ``themes/`` kept), another library imported by its package name, the bundle's Node requirements by package name; package.json
    and integrity.json."""
    spec = train.libs[lib]
    s = short(lib)
    pkg = lambda other: f"{NPM_SCOPE}/elements-{short(other)}"                     # noqa: E731
    tree = library_tree(train, lib, spec_of=pkg)
    out: dict[str, bytes] = {}
    for p, b in tree.items():
        if p.startswith(f"elements/{lib}/"):
            q = p[len(f"elements/{lib}/"):]
        elif p.startswith("css/"):
            q = p[len("css/"):]
        else:
            q = p
        if q.startswith("src/") and q.endswith(".js"):
            text = CROSS_IMPORT.sub(lambda m: m.group(1) + pkg(m.group(2)) + m.group(3), b.decode("utf-8"))
            text = CROSS_MODULE.sub(lambda m: m.group(1) + pkg(m.group(2)) + "/src/", text)     # a JSDoc import('…') of another library's type
            b = text.encode("utf-8")
        if q == bundle_name(lib):
            text = b.decode("utf-8")
            for r in spec.get("requires") or []:
                text = text.replace(json.dumps(f"../{r}/{bundle_name(r)}"), json.dumps(pkg(r)))
            b = text.encode("utf-8")
        out[q] = b
    out["README.md"] = readme(train, lib, npm=True).encode("utf-8")
    out["src/package.json"] = b'{\n  "type": "module"\n}\n'           # src/ holds ES modules; the bundle beside it is a classic script and CommonJS
    css = [Path(c).name for c in spec.get("css") or [] if owner(c) in (None, lib)]
    exports = {".": {"types": f"./drishti-{s}.d.ts", "import": "./src/drishti_index.js", "require": f"./{bundle_name(lib)}"},
               "./bundle": f"./{bundle_name(lib)}", "./src/*": "./src/*", "./package.json": "./package.json"}
    for c in css:
        exports["./" + c] = "./" + c
    if lib == "drishti-core":
        exports["./themes/*"] = "./themes/*"
    if any(q.startswith("vendor/") for q in out):
        exports["./vendor/*"] = "./vendor/*"
    package = {"name": pkg(lib), "version": spec["version"], "description": f"Drishti Elements: {spec.get('title', lib)}",
               "license": "SEE LICENSE IN LICENSE-NOTICE.txt", "private": False, "type": "commonjs",
               "main": f"./{bundle_name(lib)}", "module": "./src/drishti_index.js", "types": f"./drishti-{s}.d.ts", "exports": exports,
               "sideEffects": ["*.css", f"./{bundle_name(lib)}", "./src/drishti_index.js"],
               "dependencies": {pkg(r): "^" + train.libs[r]["version"] for r in spec.get("requires") or []},
               "drishti": {"library": lib, "train": train.version, "product": product_version(), "module": spec.get("module"),
                           "global": f"Drishti.elements.{s.replace('-', '_')}"}}
    out["package.json"] = (json.dumps(package, indent=2, ensure_ascii=False) + "\n").encode("utf-8")
    out["integrity.json"] = (json.dumps({"name": pkg(lib), "version": spec["version"], "files": integrity(out)}, indent=2) + "\n").encode("utf-8")
    return out


def tgz_name(train: Train, lib: str) -> str:
    return f"drishti-elements-{short(lib)}-{train.libs[lib]['version']}.tgz"         # npm pack's name for @drishti/elements-<name>


def webjar_tree(train: Train, version: str | None = None) -> dict[str, bytes]:
    """The WebJar's files: the tree under META-INF/resources/webjars/drishti-elements/<train version>/ (the product's libraries only:
    a WebJar is not domain-licensed). ``version``: the Maven module's own version, which must be the train's."""
    if version is not None and version != train.version:
        raise DistError(f"the WebJar module's version {version} is not the Elements train's {train.version}: set drishti-elements/pom.xml's "
                        "<version> to the train (drishti_elements.json, \"elements\")")
    return {f"{WEBJAR}/{train.version}/{p}": b for p, b in full_tree(train).items()}


def write_tree(files: dict[str, bytes], out: Path) -> Path:
    for p, b in files.items():
        f = out / p
        f.parent.mkdir(parents=True, exist_ok=True)
        f.write_bytes(b)
    return out


def build_all(out: Path, include_domains: set[str] = frozenset(), forms=("zip", "npm")) -> list[tuple[Path, int]]:
    """The release's distribution forms into ``out``: the zip and the npm tarballs (the WebJar is Maven's: ``mvn -P elements``)."""
    train = Train()
    made: list[Path] = []
    tag = "".join(f"+{d}" for d in sorted(include_domains))
    if "zip" in forms:
        made.append(write_zip(full_tree(train, include_domains), out / f"drishti-elements-{train.version}{tag}.zip", f"drishti-elements-{train.version}"))
    if "npm" in forms:
        for lib in train.resolve(["all"], set(include_domains)):
            made.append(write_tgz(npm_files(train, lib), out / "npm" / tgz_name(train, lib)))
    return [(p, p.stat().st_size) for p in made]
