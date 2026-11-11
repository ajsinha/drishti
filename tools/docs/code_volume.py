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
"""The README's "By the numbers" section, generated from the files git tracks (docs/guides/CLI_REFERENCE.md): lines and files by language,
and what the product holds (modules, lenses, functions, routes, Elements libraries, packs by domain, Sutras, connectors, feeds, lakes,
tests by kind, documents). Written between the README's markers, dated, refreshed with each release:

    python3 tools/docs/code_volume.py                      # rewrite the section
    python3 tools/docs/code_volume.py --check              # fail when a line count is more than 10% off the code (the drill's gate)
    python3 tools/docs/code_volume.py --compare v2.9.11.d  # the console's screens against the Elements libraries, at a tag and now

Sample data (data/) and vendored libraries (static/vendor) are not counted.
"""
from __future__ import annotations

import datetime
import os
import re
import subprocess
import sys
from collections import Counter
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
if str(ROOT / "drishti-console") not in sys.path:
    sys.path.insert(0, str(ROOT / "drishti-console"))
from core.pack_paths import pack_dirs  # noqa: E402  (the one rule for where a pack's folder is)
README = ROOT / "README.md"
BEGIN, END = "<!-- code-volume:begin -->", "<!-- code-volume:end -->"
TOLERANCE = 0.10
ROWS = [("java", "Java, product"), ("java-test", "Java, tests"), ("py", "Python, product (console, tools, loaders, feeds)"),
        ("py-test", "Python, tests"), ("js", "JavaScript, product (console and Elements)"), ("js-test", "JavaScript, tests"),
        ("css", "CSS"), ("html", "HTML (templates and pages)"), ("yaml", "YAML: configuration, Sutras, data contracts"),
        ("md", "Markdown: guides, tutorials, design")]
PRODUCT, TESTS = ("java", "py", "js", "css", "html"), ("java-test", "py-test", "js-test")
ELEMENTS = "drishti-console/web/static/elements/"
ROUTE = re.compile(r"^\s*@\w*router\.(?:get|post|put|delete|patch|api_route|websocket)\(", re.M)
HANDLER = re.compile(r"^\s*@(?:Get|Post|Put|Delete|Patch)Mapping\b", re.M)


def kind(f: str) -> str | None:
    if f.startswith("data/") or "/vendor/" in f:
        return None
    e = os.path.splitext(f)[1].lower()
    if e == ".java":
        return "java-test" if "/src/test/" in f else "java"
    if e == ".py":
        return "py-test" if ("/tests/" in f or os.path.basename(f).startswith("test_")) else "py"
    if e in (".js", ".mjs"):
        return "js-test" if ("/tests/" in f or f.endswith((".test.js", ".test.mjs"))) else "js"
    if e in (".css", ".html"):
        return e[1:]
    if e in (".yaml", ".yml"):
        return "yaml"
    if e == ".md":
        return "md"
    return None


def tracked(ref: str | None = None) -> list[str]:
    cmd = ["git", "ls-tree", "-r", "--name-only", ref] if ref else ["git", "ls-files"]
    return [f for f in subprocess.run(cmd, capture_output=True, text=True, cwd=ROOT, check=True).stdout.split("\n") if f]


def measure() -> dict:
    files = tracked()
    lines: dict[str, int] = {k: 0 for k, _ in ROWS}
    count: dict[str, int] = {k: 0 for k, _ in ROWS}
    for f in files:
        k = kind(f)
        if not k:
            continue
        try:
            with open(ROOT / f, "rb") as h:
                lines[k] += sum(1 for _ in h)
            count[k] += 1
        except OSError:
            continue
    pom = (ROOT / "pom.xml").read_text(encoding="utf-8")
    return {"lines": lines, "files": count, "inventory": inventory(files, pom),
            "tutorials": len(list((ROOT / "docs" / "tutorials").glob("[0-9][0-9]-*.md")))}


def _read(f: str) -> str:
    try:
        return (ROOT / f).read_text(encoding="utf-8")
    except (OSError, UnicodeDecodeError):
        return ""


def _manifests(files: list[str], name: str) -> list[dict]:
    return [yaml.safe_load(_read(f)) or {} for f in files if f.endswith(f"/src/main/resources/META-INF/drishti/{name}")]


def inventory(files: list[str], pom: str) -> dict:
    """What the product holds, each figure counted from the files (never typed in)."""
    poms = re.findall(r"<module>([^<]+)</module>", pom)
    # a module's package manifest: modules/<id>/module.yaml, modules/<id>/dmod/module.yaml or a module project's src/dmod/module.yaml
    package = re.compile(r"modules/[^/]+/(?:dmod/|src/dmod/)?module\.yaml")
    modules = [doc for doc in (yaml.safe_load(_read(f)) or {} for f in files if package.fullmatch(f)) if doc.get("id")]
    functions = {m["id"]: len((m.get("contributes") or m).get("functions") or []) for m in modules}
    lenses = [lens["id"] for doc in _manifests(files, "lenses.yaml") for lens in doc.get("lenses") or []]
    libs = sorted({f[len(ELEMENTS):].split("/")[0] for f in files if f.startswith(ELEMENTS) and "/src/" in f})
    lib_modules = sum(1 for f in files if f.startswith(ELEMENTS) and "/src/" in f and f.endswith(".js"))
    console = [f for f in files if f.startswith("drishti-console/") and f.endswith(".py") and "/tests/" not in f]
    server = [f for f in files if f.endswith(".java") and "/src/main/" in f]
    packs = Counter(p.parent.name if p.parent.name != "packs" else "core" for p in pack_dirs().values())
    pack_root = "config/packs/"
    tests = Counter()
    for f in files:
        base = os.path.basename(f)
        if f.endswith(".java") and "/src/test/" in f and re.search(r"(Test|Tests|IT)\.java$", base):
            tests["java"] += 1
        elif f.endswith(".py") and base.startswith("test_") and not f.startswith("data/"):
            tests["browser" if base.endswith("_browser.py") else ("console" if f.startswith("drishti-console/") else "tools")] += 1
        elif f.endswith((".test.mjs", ".test.js")):
            tests["js"] += 1
    docs = [f for f in files if f.startswith("docs/") and f.endswith(".md")]
    return {
        "maven": len(poms), "plugins": sum(1 for p in poms if p.startswith("plugins/drishti-plugin-")),
        "folders": sorted({p.split("/")[0] for p in poms if "/" in p and not p.startswith("docs/")}),
        "built_ins": sum(1 for d in (ROOT / "docs" / "connectors" / "types").iterdir() if (d / "README.md").is_file()),
        "domains": sorted(m["id"] for m in modules), "functions": functions, "lenses": lenses,
        "routes": sum(len(ROUTE.findall(_read(f))) for f in console),
        "handlers": sum(len(HANDLER.findall(_read(f))) for f in server),
        "libraries": libs, "lib_modules": lib_modules, "packs": dict(sorted(packs.items())),
        "sutras": sum(1 for f in files if f.startswith(pack_root) and "/sutras/" in f and f.endswith((".yaml", ".yml"))),
        "feeds": sum(1 for f in files if re.fullmatch(r"config/feeds/[^/]+\.yaml", f)),
        "lakes": sum(1 for f in files if f.startswith("config/lakes/") and f.endswith(".yaml")),
        "tests": dict(tests), "docs": len(docs), "guides": sum(1 for f in docs if f.startswith("docs/guides/") and f.count("/") == 2),
    }


def k(n: int) -> str:
    return f"{round(n / 1000):,}k" if n >= 1000 else str(n)


def render(m: dict) -> str:
    L, inv = m["lines"], m["inventory"]
    product = sum(L[x] for x in PRODUCT)
    tests = sum(L[x] for x in TESTS)
    t = inv["tests"]
    fn = ", ".join(f"{n} {d}" for d, n in sorted(inv["functions"].items()))
    rows = [("Maven modules", f"{inv['maven']} in {len(inv['folders'])} subject folders: " + ", ".join(f"`{d}/`" for d in inv["folders"])),
            ("Modules (`.dmod`)", f"{len(inv['domains'])}: {', '.join(inv['domains'])}"),
            ("Lenses", f"{len(inv['lenses'])}: {', '.join(inv['lenses'])}"),
            ("Functions a module declares", f"{sum(inv['functions'].values())} ({fn}); every other command word is the kernel's"),
            ("Console routes", f"{inv['routes']}"),
            ("Server REST handlers", f"{inv['handlers']}"),
            ("Drishti Elements", f"{len(inv['libraries'])} libraries, {inv['lib_modules']} ES modules"),
            ("Domain packs", f"{sum(inv['packs'].values())}: " + ", ".join(f"{d} {n}" for d, n in inv["packs"].items())),
            ("Sutras in the shipped packs", f"{inv['sutras']}"),
            ("Connectors", f"{inv['plugins']} plugins (a driver or native library each) and {inv['built_ins']} built-in types"),
            ("Feeds", f"{inv['feeds']}"),
            ("Lake files", f"{inv['lakes']}"),
            ("Test files", f"{sum(t.values()):,}: Java {t.get('java', 0)}, console {t.get('console', 0)}, browser {t.get('browser', 0)}, "
                           f"tools {t.get('tools', 0)}, JavaScript {t.get('js', 0)}"),
            ("Documents under docs/", f"{inv['docs']} ({inv['guides']} guides, {m['tutorials']} tutorials)")]
    out = [BEGIN, "", f"*Measured {datetime.date.today().isoformat()} from the files the repository tracks "
           "(`python3 tools/docs/code_volume.py`; sample data and vendored libraries not counted).*", "",
           "| Language | Lines | Files |", "|---|---|---|"]
    out += [f"| {label} | {k(L[key])} | {m['files'][key]:,} |" for key, label in ROWS]
    out += ["", f"About **{k(product)} lines of product code** and **{k(tests)} lines of tests** ({round(100 * tests / product)}% of the product "
            f"code), with **{k(L['md'])} lines of documentation**.", "", "| What | Count |", "|---|---|"]
    out += [f"| {a} | {b} |" for a, b in rows]
    out += ["", END]
    return "\n".join(out)


def figures(text: str) -> list[int]:
    block = text[text.index(BEGIN):text.index(END)]
    return [int(x.replace(",", "")) * 1000 for x in re.findall(r"\| ([\d,]+)k \|", block)]


def web_split(ref: str | None) -> dict[str, int]:
    """Lines of the console's own screen code (JS, CSS, HTML outside the libraries) and of the Elements libraries' sources at a ref."""
    out = {"screens": 0, "libraries": 0}
    for f in tracked(ref):
        if not f.startswith("drishti-console/") or "/tests/" in f or "/vendor/" in f or not f.endswith((".js", ".css", ".html")):
            continue
        if "/static/render/" in f or "/elements/package/" in f or "harness" in f:
            continue                                            # frozen published copies, the retired npm package, test pages
        if f.startswith(ELEMENTS):
            rest = f[len(ELEMENTS):].split("/")
            if len(rest) < 2 or (rest[1] != "src" and not f.endswith(".css")):
                continue                                        # the bundles tools/elements/build.py writes
            part = "libraries"
        else:
            part = "screens"
        blob = subprocess.run(["git", "show", f"{ref}:{f}" if ref else f":{f}"], capture_output=True, cwd=ROOT).stdout
        out[part] += blob.count(b"\n")
    return out


def main(argv: list[str]) -> int:
    if "--compare" in argv:
        ref = argv[argv.index("--compare") + 1]
        before, after = web_split(ref), web_split(None)
        for part in ("screens", "libraries"):
            print(f"{part}: {before[part]:,} at {ref}, {after[part]:,} now ({after[part] - before[part]:+,})")
        return 0
    text = README.read_text(encoding="utf-8")
    if BEGIN not in text or END not in text:
        print("README.md has no code-volume markers", file=sys.stderr)
        return 1
    m = measure()
    if "--check" in argv:
        now = [m["lines"][key] for key, _ in ROWS]
        shown = figures(text)
        off = [(label, s, n) for (_, label), s, n in zip(ROWS, shown, now) if n and abs(s - n) / n > TOLERANCE]
        if len(shown) != len(ROWS) or off:
            for label, s, n in off:
                print(f"README code volume: {label} shows {s:,}, the code has {n:,} (over {int(TOLERANCE * 100)}% off)", file=sys.stderr)
            print("run: python3 tools/docs/code_volume.py", file=sys.stderr)
            return 1
        print("README code volume is current (within 10%)")
        return 0
    a, b = text.index(BEGIN), text.index(END) + len(END)
    README.write_text(text[:a] + render(m) + text[b:], encoding="utf-8")
    print("wrote README.md's code volume")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
