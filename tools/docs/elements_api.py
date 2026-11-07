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
"""The Drishti Elements API reference, written from the libraries' typed JSDoc (docs/guides/ELEMENTS_API.md).

    python3 tools/docs/elements_api.py            # write the reference
    python3 tools/docs/elements_api.py --check    # exit 1 when a public symbol has no typed JSDoc or the reference is stale

A public symbol is a name a library's entry exports. Each needs a JSDoc block: a description, and for a function or method every
parameter with a {type} and a @returns with a {type}; a constant or class its description. A class's public methods (not marked
@private, not starting with an underscore) are documented with it. @typedef blocks of the modules are listed as the library's types."""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ELEMENTS = ROOT / "drishti-console" / "web" / "static" / "elements"
OUT = ROOT / "docs" / "guides" / "ELEMENTS_API.md"
HEADER = (ROOT / "config" / "license-header.txt").read_text(encoding="utf-8").strip()

NAME = r"[A-Za-z_$][\w$]*"
DOC_DECL = re.compile(r"/\*\*((?:(?!\*/).)*?)\*/\s*\n\s*(export\s+)?((?:async\s+)?function\*?\s+(" + NAME + r")\s*\(([^)]*)\)|class\s+(" + NAME + r")|const\s+(" + NAME + r"))", re.S)
ANY_DECL = re.compile(r"^export\s+(?:(?:async\s+)?function\*?\s+(" + NAME + r")|class\s+(" + NAME + r")|const\s+(" + NAME + r"))", re.M)
METHOD = re.compile(r"/\*\*((?:(?!\*/).)*?)\*/\s*\n\s*(?:static\s+)?(?:async\s+)?(" + NAME + r")\s*\(([^)]*)\)\s*\{", re.S)
TYPEDEF = re.compile(r"/\*\*((?:(?!\*/).)*?@(?:typedef|callback)\s(?:(?!\*/).)*?)\*/", re.S)
EXPORT_LIST = re.compile(r"^export\s*\{([^}]*)\}\s*from\s*['\"]([^'\"]+)['\"]", re.M)


def clean(block: str) -> str:
    return "\n".join(re.sub(r"^\s*\* ?", "", ln) for ln in block.strip().splitlines()).strip()


def parse_doc(block: str) -> dict:
    text = re.sub(r"\s+(@(?:param|returns?|type|example|private|property)\b)", r"\n\1", clean(block))
    desc, tags = [], []
    for ln in text.splitlines():
        if ln.startswith("@"):
            tags.append(ln)
        elif tags:
            tags[-1] += " " + ln.strip()
        else:
            desc.append(ln)
    d = {"desc": " ".join(x.strip() for x in desc).strip(), "params": [], "returns": None, "type": None, "examples": [], "private": False,
         "props": [], "typedef": None}
    for t in tags:
        m = re.match(r"@param\s+(\{.*?\})?\s*(\[?[\w.$]+(?:=[^\]]*)?\]?)\s*(.*)", t)
        if t.startswith("@param") and m:
            d["params"].append({"type": (m.group(1) or "")[1:-1], "name": m.group(2), "desc": m.group(3).strip()})
        elif t.startswith(("@returns", "@return ")):
            m = re.match(r"@returns?\s+(\{.*\})?\s*(.*)", t)
            d["returns"] = {"type": (m.group(1) or "")[1:-1] if m else "", "desc": m.group(2).strip() if m else ""}
        elif t.startswith("@type"):
            m = re.match(r"@type\s+\{(.*)\}", t)
            d["type"] = m.group(1) if m else ""
        elif t.startswith("@example"):
            d["examples"].append(t[len("@example"):].strip())
        elif t.startswith("@private"):
            d["private"] = True
        elif t.startswith("@property"):
            m = re.match(r"@property\s+\{(.*?)\}\s*(\[?[\w.$]+(?:=[^\]]*)?\]?)\s*(.*)", t)
            if m:
                d["props"].append({"type": m.group(1), "name": m.group(2), "desc": m.group(3).strip()})
        elif t.startswith(("@typedef", "@callback")):
            m = re.match(r"@(?:typedef|callback)\s+(?:\{(.*?)\}\s*)?(" + NAME + ")", t)
            d["typedef"] = {"type": (m.group(1) or "") if m else "", "name": m.group(2) if m else ""}
    return d


def exports_of(entry: Path) -> list[tuple[str, Path]]:
    """(name, module) for every name the entry re-exports, in order; plus the entry's own declarations."""
    text = entry.read_text(encoding="utf-8")
    out = []
    for m in EXPORT_LIST.finditer(text):
        for part in m.group(1).split(","):
            part = part.strip()
            if part:
                out.append((part.split(" as ")[-1].strip(), (entry.parent / m.group(2)).resolve()))
    for m in ANY_DECL.finditer(text):
        out.append((m.group(1) or m.group(2) or m.group(3), entry))
    return out


def class_body(text: str, start: int) -> str:
    i = text.index("{", start)
    depth = 0
    for j in range(i, len(text)):
        if text[j] == "{":
            depth += 1
        elif text[j] == "}":
            depth -= 1
            if depth == 0:
                return text[i + 1:j]
    return text[i + 1:]


def symbol(name: str, module: Path, problems: list[str]) -> dict | None:
    text = module.read_text(encoding="utf-8")
    where = module.relative_to(ROOT)
    for m in DOC_DECL.finditer(text):
        fn, cls, const = m.group(4), m.group(6), m.group(7)
        if name not in (fn, cls, const):
            continue
        if m.group(1).count("/**"):
            continue
        d = parse_doc(m.group(1))
        s = {"name": name, "module": str(where), "doc": d}
        if not d["desc"]:
            problems.append(f"{where}: {name} has no description")
        if fn:
            s["kind"] = "function"
            s["args"] = [a.strip() for a in m.group(5).split(",") if a.strip()]
            check_fn(f"{where}: {name}", d, s["args"], problems)
        elif cls:
            s["kind"] = "class"
            s["methods"] = methods(text, m.end(), f"{where}: {name}", problems)
        else:
            s["kind"] = "constant"
        return s
    problems.append(f"{where}: the public {name} has no JSDoc block right above it")
    return None


def check_fn(what: str, d: dict, args: list[str], problems: list[str]) -> None:
    names = [re.sub(r"[\[\]]|=.*", "", p["name"]).split(".")[0] for p in d["params"]]
    for a in args:
        a = re.sub(r"=.*", "", a).strip()
        if a.startswith("..."):
            a = a[3:]
        if a not in names:
            problems.append(f"{what}: parameter {a} has no @param")
    for p in d["params"]:
        if not p["type"]:
            problems.append(f"{what}: @param {p['name']} has no {{type}}")
    if d["returns"] is None or not d["returns"]["type"]:
        problems.append(f"{what}: no typed @returns")


def methods(text: str, start: int, owner: str, problems: list[str]) -> list[dict]:
    body = class_body(text, start)
    out = []
    for m in METHOD.finditer(body):
        name = m.group(2)
        d = parse_doc(m.group(1))
        if d["private"] or name.startswith("_") or name in ("if", "for", "while", "switch", "catch", "function"):
            continue
        args = [a.strip() for a in m.group(3).split(",") if a.strip()]
        if name == "constructor":
            for a in args:
                if a not in [re.sub(r"[\[\]]|=.*", "", p["name"]).split(".")[0] for p in d["params"]]:
                    problems.append(f"{owner}.constructor: parameter {a} has no @param")
        else:
            if not d["desc"]:
                problems.append(f"{owner}.{name} has no description")
            check_fn(f"{owner}.{name}", d, args, problems)
        out.append({"name": name, "args": args, "doc": d, "static": bool(re.search(r"static\s+" + re.escape(name) + r"\s*\(", body))})
    undocumented = re.findall(r"^  (?:static\s+)?(?:async\s+)?([a-z]\w*)\s*\([^)]*\)\s*\{", body, re.M)
    documented = {x["name"] for x in out} | {m.group(2) for m in METHOD.finditer(body)}
    for n in undocumented:
        if n not in documented and n not in ("if", "for", "while", "switch", "catch"):
            problems.append(f"{owner}.{n} has no JSDoc (mark it @private if it is not public)")
    return out


def typedefs(src: Path) -> list[dict]:
    out = []
    for f in sorted(src.glob("*.js")):
        for m in TYPEDEF.finditer(f.read_text(encoding="utf-8")):
            d = parse_doc(m.group(1))
            if d["typedef"]:
                out.append({"name": d["typedef"]["name"], "type": d["typedef"]["type"], "doc": d, "module": str(f.relative_to(ROOT))})
    return out


def md_escape(s: str) -> str:
    return s.replace("|", "\\|").replace("<", "&lt;").replace(">", "&gt;")


def sig(name: str, args: list[str]) -> str:
    return f"{name}({', '.join(args)})"


def render_fn(d: dict, out: list[str]) -> None:
    if d["desc"]:
        out.append(md_escape(d["desc"]))
        out.append("")
    if d["params"]:
        out.append("| Parameter | Type | Meaning |")
        out.append("|---|---|---|")
        for p in d["params"]:
            out.append(f"| `{p['name']}` | `{md_escape(p['type'])}` | {md_escape(p['desc'])} |")
        out.append("")
    if d["returns"]:
        out.append(f"Returns `{md_escape(d['returns']['type'])}`" + (f": {md_escape(d['returns']['desc'])}" if d["returns"]["desc"] else "") + ".")
        out.append("")
    for e in d["examples"]:
        out.append("```js")
        out.append(e)
        out.append("```")
        out.append("")


def render(libs: list[tuple[str, dict, list[dict], list[dict]]]) -> str:
    out = ["<!--", *[f"  {ln}".rstrip() for ln in HEADER.splitlines()], "-->", "",
           "# Drishti Elements: the API reference", "",
           "Generated from the libraries' typed JSDoc by `python3 tools/docs/elements_api.py` (do not edit; `--check` refuses a stale copy or a",
           "public symbol without a typed comment). Every library is ES modules under `drishti-console/web/static/elements/<library>/src/` and",
           "one bundled file next to them; a page loads drishti-core and then `Drishti.elements.load('<library>')`.", ""]
    out.append("| Library | Version | What it is |")
    out.append("|---|---|---|")
    for name, spec, _, _ in libs:
        out.append(f"| [{name}](#{name}) | {spec['version']} | {md_escape(spec.get('title', ''))} |")
    out.append("")
    for name, spec, syms, types in libs:
        out.append(f"## {name}")
        out.append("")
        out.append(f"Version {spec['version']}. Bundle `static/elements/{name}/drishti_{name[len('drishti-'):].replace('-', '_')}.js`, global "
                   f"`Drishti.elements.{name[len('drishti-'):].replace('-', '_')}`" + (f"; requires {', '.join(spec['requires'])}" if spec.get("requires") else "") + ".")
        out.append("")
        by_module: dict[str, list[dict]] = {}
        for s in syms:
            by_module.setdefault(s["module"], []).append(s)
        for module, items in by_module.items():
            out.append(f"### {Path(module).name}")
            out.append("")
            for s in items:
                d = s["doc"]
                if s["kind"] == "function":
                    out.append(f"#### `{sig(s['name'], s['args'])}`")
                    out.append("")
                    render_fn(d, out)
                elif s["kind"] == "class":
                    out.append(f"#### class `{s['name']}`")
                    out.append("")
                    out.append(md_escape(d["desc"]))
                    out.append("")
                    for mth in s["methods"]:
                        label = "new " + s["name"] if mth["name"] == "constructor" else ("static " if mth["static"] else "") + s["name"] + "." + mth["name"]
                        out.append(f"##### `{label}({', '.join(mth['args'])})`")
                        out.append("")
                        render_fn(mth["doc"], out)
                else:
                    out.append(f"#### `{s['name']}`" + (f" : `{md_escape(d['type'])}`" if d["type"] else ""))
                    out.append("")
                    out.append(md_escape(d["desc"]))
                    out.append("")
        if types:
            out.append(f"### Types of {name}")
            out.append("")
            for t in types:
                out.append(f"#### `{t['name']}`" + (f" : `{md_escape(t['type'])}`" if t["type"] else ""))
                out.append("")
                if t["doc"]["desc"]:
                    out.append(md_escape(t["doc"]["desc"]))
                    out.append("")
                rows = t["doc"]["props"] or t["doc"]["params"]
                if rows:
                    out.append("| Name | Type | Meaning |")
                    out.append("|---|---|---|")
                    for p in rows:
                        out.append(f"| `{p['name']}` | `{md_escape(p['type'])}` | {md_escape(p['desc'])} |")
                    out.append("")
    return "\n".join(out).rstrip() + "\n"


def build() -> tuple[str, list[str]]:
    manifest = json.loads((ELEMENTS / "drishti_elements.json").read_text(encoding="utf-8"))
    problems: list[str] = []
    libs = []
    for name, spec in manifest["libraries"].items():
        entry = (ELEMENTS / name / spec["entry"]).resolve()
        syms = []
        for sym_name, module in exports_of(entry):
            s = symbol(sym_name, module, problems)
            if s:
                syms.append(s)
        libs.append((name, spec, syms, typedefs(entry.parent)))
    return render(libs), problems


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--check", action="store_true", help="exit 1 when a public symbol lacks typed JSDoc or the reference is stale")
    a = ap.parse_args(argv)
    text, problems = build()
    for p in problems:
        print("elements api: " + p, file=sys.stderr)
    if a.check:
        stale = not OUT.is_file() or OUT.read_text(encoding="utf-8") != text
        if stale:
            print(f"elements api: {OUT.relative_to(ROOT)} is stale: run python3 tools/docs/elements_api.py", file=sys.stderr)
        return 1 if problems or stale else 0
    OUT.write_text(text, encoding="utf-8")
    print(f"wrote {OUT.relative_to(ROOT)}")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
