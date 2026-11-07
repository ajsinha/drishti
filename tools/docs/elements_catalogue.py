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
"""The gallery's catalogue of Drishti Elements (static/gallery/drishti_catalogue.js), written by tools/docs/elements_api.py from the
same typed JSDoc as ELEMENTS_API.md: one home per fact, the gallery's options panel and API tab read it.

What it holds, per library of the manifest: its version, title, requirements and licence, whether it needs a server, its bundle's size,
its Node tests and browser file, each module's opening words, every public symbol (functions, classes with their methods, constants),
every typedef with its properties (an option's name, type, default, whether optional, its words: the options panel is made from them),
the string lists the code exports frozen (events, layouts, modes) and the library's lines of the product CHANGELOG. Besides: the
libraries ELEMENTS.md section 2 plans that are not built yet, and the AG Grid adapter's mapping (ELEMENTS.md section 6) with whether the
adapter is built. The file is a classic script (it runs from file:// too) and a CommonJS module under Node."""
from __future__ import annotations

import json
import re
from pathlib import Path

NAME = r"[A-Za-z_$][\w$]*"
FROZEN = re.compile(r"^export\s+const\s+(" + NAME + r")\s*=\s*Object\.freeze\(\[((?:[^\[\]]|\n)*?)\]\)", re.M)
STRING = re.compile(r"'((?:[^'\\]|\\.)*)'")
HEADER_COMMENT = re.compile(r"/\*(?!\*)((?:(?!\*/).)*?)\*/", re.S)
PROP_NAME = re.compile(r"^(\[)?([\w.$]+)(?:=(.*?))?(\])?$")
TEST_CALL = re.compile(r"^test\(", re.M)
LIB_IMPORT = re.compile(r"elements/(drishti-[a-z-]+)/")
BOLD = re.compile(r"\*\*([^*]+)\*\*")
LINK = re.compile(r"\[([^\]]+)\]\([^)]+\)")
VERSION = re.compile(r"\b(\d+\.\d+\.\d+)\b")

SERVER_WORDS = (("licen", "licence"), ("drishti server", "server"), ("not needed", "none"))


def plain(md: str) -> str:
    """Markdown to the gallery's text: links become their words, bold its words; code spans keep their backticks."""
    return re.sub(r"\s+", " ", BOLD.sub(r"\1", LINK.sub(r"\1", md))).strip()


def module_about(text: str) -> str:
    """A module's opening words: its first plain block comment after the licence header."""
    found = HEADER_COMMENT.findall(text)
    for body in found[1:2] if found and "Copyright" in found[0] else found[:1]:
        return re.sub(r"\s+", " ", body).strip()
    return ""


def prop(p: dict) -> dict:
    m = PROP_NAME.match(p["name"].strip())
    name, default, optional = (m.group(2), m.group(3), bool(m.group(1))) if m else (p["name"], None, False)
    return {"name": name, "type": p["type"], "optional": optional, "default": default, "desc": p["desc"]}


def doc_fn(d: dict) -> dict:
    return {"desc": d["desc"], "params": d["params"], "returns": d["returns"], "type": d["type"]}


def symbols(syms: list[dict]) -> list[dict]:
    out = []
    for s in syms:
        x = {"name": s["name"], "kind": s["kind"], "module": Path(s["module"]).name, **doc_fn(s["doc"])}
        if s["kind"] == "function":
            x["args"] = s["args"]
        if s["kind"] == "class":
            x["methods"] = [{"name": m["name"], "args": m["args"], "static": m["static"], **doc_fn(m["doc"])} for m in s["methods"]]
        out.append(x)
    return out


def constants(src: Path) -> dict[str, list[str]]:
    """The string lists a library exports frozen (its events, layouts, modes): the gallery listens to and offers exactly these."""
    out = {}
    for f in sorted(src.glob("*.js")):
        for m in FROZEN.finditer(f.read_text(encoding="utf-8")):
            values = STRING.findall(m.group(2))
            if values and not re.search(r"[{\[]", m.group(2)):
                out[m.group(1)] = values
    return out


def tests_of(console: Path) -> dict[str, dict]:
    """Each library's Node tests (a test file counts for every library it imports) and its browser file."""
    out: dict[str, dict] = {}
    for f in sorted((console / "tests" / "elements").glob("*.test.mjs")):
        text = f.read_text(encoding="utf-8")
        n = len(TEST_CALL.findall(text))
        for lib in sorted(set(LIB_IMPORT.findall(text))):
            t = out.setdefault(lib, {"node": 0, "files": [], "browser": []})
            t["node"] += n
            t["files"].append(f.name)
    for f in sorted((console / "tests").glob("test_elements_*_browser.py")):
        lib = "drishti-" + f.name[len("test_elements_"):-len("_browser.py")]
        out.setdefault(lib, {"node": 0, "files": [], "browser": []})["browser"].append(f.name)
    return out


def changelog(root: Path, names: list[str]) -> dict[str, list[dict]]:
    """Each library's entries of the product CHANGELOG: a release's bullets whose bold lead names the library (one home: the CHANGELOG)."""
    out: dict[str, list[dict]] = {n: [] for n in names}
    release, bullet = None, None

    def flush():
        if release and bullet:
            text = " ".join(x.strip() for x in bullet)
            lead = BOLD.search(text)
            if lead:
                for n in names:
                    if re.search(r"\b" + re.escape(n) + r"\b", lead.group(1)):
                        v = VERSION.search(lead.group(1))
                        out[n].append({"release": release, "version": v.group(1) if v else None, "text": plain(text)})
    for line in (root / "CHANGELOG.md").read_text(encoding="utf-8").splitlines():
        if line.startswith("## "):
            flush()
            release, bullet = line[3:].split(" ")[0], None
        elif line.startswith("- "):
            flush()
            bullet = [line[2:]]
        elif bullet is not None and line.startswith("  "):
            bullet.append(line)
        else:
            flush()
            bullet = None
    flush()
    return out


def section(md: str, number: str) -> str:
    m = re.search(r"^## " + re.escape(number) + r"\..*?$(.*?)(?=^## \d)", md, re.M | re.S)
    return m.group(1) if m else ""


def table_rows(text: str) -> list[list[str]]:
    rows = []
    for ln in text.splitlines():
        if ln.startswith("|") and not re.match(r"^\|[-| ]+\|$", ln):
            cells = [c.strip() for c in re.split(r"(?<!\\)\|", ln.strip())[1:-1]]
            rows.append([c.replace("\\|", "|") for c in cells])
    return rows[1:]


def planned(design: str, built: set[str]) -> list[dict]:
    """The libraries section 2 of ELEMENTS.md designs that the manifest does not hold yet."""
    out = []
    for r in table_rows(section(design, "2")):
        name = BOLD.sub(r"\1", r[1]).strip() if len(r) > 3 else ""
        if not name.startswith("drishti-") or name in built:
            continue
        words = r[3].lower()
        server = next((v for k, v in SERVER_WORDS if k in words), "none")
        out.append({"name": name, "number": r[0], "what": plain(r[2]), "server": server, "serverText": plain(r[3])})
    return out


def ag_mapping(design: str, grid_src: Path) -> dict:
    """The AG Grid adapter's mapping, ELEMENTS.md section 6's table (the adapter's own table once it is built), and what v1 leaves out."""
    s6 = section(design, "6")
    rows = [{"ag": plain(r[0]), "drishti": plain(r[1])} for r in table_rows(s6) if len(r) == 2]
    left = re.search(r"\*\*Not covered in version 1\*\*(.*?)(?:\n\n|$)", s6, re.S)
    index = grid_src / "drishti_index.js"
    built = index.is_file() and re.search(r"\bagGrid\b", index.read_text(encoding="utf-8")) is not None
    return {"built": built, "scope": "pivot, row grouping, the server-side row model and tree data (O5)", "mapping": rows,
            "notCovered": plain(left.group(1)) if left else ""}


def catalogue(root: Path, manifest: dict, libs: list[tuple[str, dict, list[dict], list[dict]]]) -> dict:
    console = root / "drishti-console"
    elements = console / "web" / "static" / "elements"
    design = (root / "docs" / "architecture" / "ELEMENTS.md").read_text(encoding="utf-8")
    tests = tests_of(console)
    logs = changelog(root, [n for n, *_ in libs])
    out = []
    for name, spec, syms, types in libs:
        folder = elements / name
        entry = (folder / spec["entry"]).resolve()
        short = name[len("drishti-"):].replace("-", "_")
        bundle = folder / f"drishti_{short}.js"
        text = bundle.read_text(encoding="utf-8").replace("\r\n", "\n") if bundle.is_file() else ""
        server = "licence" if spec.get("module") else ("server" if spec.get("server") else "none")
        out.append({
            "name": name, "version": spec["version"], "title": spec.get("title", ""), "requires": spec.get("requires", []),
            "module": spec.get("module"), "server": server, "global": f"Drishti.elements.{short}",
            "bundle": {"path": f"{name}/{bundle.name}", "bytes": len(text.encode("utf-8")), "lines": text.count("\n")},
            "tests": tests.get(name, {"node": 0, "files": [], "browser": []}),
            "modules": [{"file": f.name, "about": module_about(f.read_text(encoding="utf-8"))} for f in sorted(entry.parent.glob("*.js"))],
            "symbols": symbols(syms),
            "types": [{"name": t["name"], "type": t["type"], "module": Path(t["module"]).name, "desc": t["doc"]["desc"],
                       "props": [prop(p) for p in (t["doc"]["props"] or t["doc"]["params"])]} for t in types],
            "constants": constants(entry.parent),
            "changelog": logs.get(name, []),
        })
    return {"elements": manifest.get("elements"), "libraries": out, "planned": planned(design, {n for n, *_ in libs}),
            "agGrid": ag_mapping(design, elements / "drishti-grid" / "src")}


def script(cat: dict, header: str) -> str:
    """The catalogue as a classic script (window.Drishti.gallery.catalogue) that is also a CommonJS module."""
    lic = "/*\n" + "\n".join((" * " + ln).rstrip() for ln in header.splitlines()) + "\n */\n"
    one = lambda v: json.dumps(v, ensure_ascii=False, separators=(",", ":"))       # noqa: E731 - a value on one line
    parts = []
    for k, v in cat.items():                                  # a library or a planned one per line: a short file, a readable diff
        if isinstance(v, list):
            parts.append(one(k) + ":[\n    " + ",\n    ".join(one(x) for x in v) + "\n  ]")
        else:
            parts.append(one(k) + ":" + one(v))
    body = "{\n  " + ",\n  ".join(parts) + "\n  }"
    return (lic + "/* Generated by python3 tools/docs/elements_api.py from the libraries' typed JSDoc, the manifest, ELEMENTS.md and the CHANGELOG:\n"
            "   do not edit (--check refuses a stale copy). The gallery (drishti_gallery.html) reads it. */\n"
            "(function (root) {\n  'use strict';\n  const catalogue = " + body + ";\n"
            "  if (typeof module !== 'undefined' && module.exports) { module.exports = catalogue; }\n"
            "  if (root) { root.Drishti = root.Drishti || {}; root.Drishti.gallery = root.Drishti.gallery || {}; root.Drishti.gallery.catalogue = catalogue; }\n"
            "}(typeof window !== 'undefined' ? window : null));\n")
