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

"""The Java and Python API references (IMPLEMENTATION_PLAN row 2g; docs/guides/CLI_REFERENCE.md › API references).

What is documented is in ``tools/api_docs.yaml``. Nothing this tool writes is committed: the references are built, checked and served.

    python3 tools/api_docs.py check-java     every public or protected member of the Javadoc built by ``mvn -P api-docs`` has a comment
    python3 tools/api_docs.py check-python   every public function, class and method of the Python surfaces has a docstring and type hints
    python3 tools/api_docs.py check          both
    python3 tools/api_docs.py build [--out DIR] [--maven]
                                             gathers the Javadoc (``--maven`` builds it first: ``./mvnw -o -P api-docs ... process-classes``)
                                             and runs pdoc into DIR/<version>/ (default drishti-console/web/apidocs, the console's
                                             ``help.api_docs_dir``), with an index page and manifest.json; Help -> Developer serves it
    python3 tools/api_docs.py zip [--out DIR] [--to FILE]
                                             the built version as one zip, attached to the GitHub release for readers without a console

Doclint (``-Xdoclint:all,-missing``, warnings fail) runs inside Maven: a broken link, bad HTML or a malformed tag fails the build there.
Doclint's ``missing`` group would also demand an ``@param`` and ``@return`` on every method, which the code base's prose comments do not
use; check-java keeps the half of it that matters, a public or protected type or member nobody described.
"""
from __future__ import annotations

import argparse
import ast
import html
import json
import os
import re
import shutil
import subprocess
import sys
import time
import zipfile
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parent.parent
CONFIG = ROOT / "tools" / "api_docs.yaml"
DEFAULT_OUT = ROOT / "drishti-console" / "web" / "apidocs"
APIDOCS = Path("target") / "reports" / "apidocs"
NOT_CLASS_PAGES = {"package-summary.html", "package-tree.html", "package-use.html"}


def config() -> dict:
    """``tools/api_docs.yaml``: the Java modules and Python surfaces."""
    return yaml.safe_load(CONFIG.read_text(encoding="utf-8"))


def version() -> str:
    """The product version, from the parent pom (the release the reference belongs to)."""
    pom = (ROOT / "pom.xml").read_text(encoding="utf-8")
    m = re.search(r"<artifactId>drishti-parent</artifactId>\s*<version>([^<]+)</version>", pom)
    if not m:
        raise SystemExit("api_docs: no version in pom.xml")
    return m.group(1).strip()


# ---- Java: every public or protected type and member has a comment ------------------------------------------------------------------

_DETAIL = re.compile(r'<section class="detail" id="([^"]+)">(.*?)</section>', re.S)
_DESCRIPTION = re.compile(r'<section class="(?:class|package)-description" id="(?:class|package)-description">(.*?)</section>', re.S)


def undocumented_in_page(page: str, name: str) -> list[str]:
    """The undocumented parts of one Javadoc page (JDK 21 and 25 standard doclet): the type or package without a description, and
    each member detail without a ``block`` (an overriding method's copied comment and the generated ones of records and enums count)."""
    out = []
    desc = _DESCRIPTION.search(page)
    if (desc is None and name.endswith(" (package)")) or (desc is not None and 'class="block"' not in desc.group(1)):
        out.append(name)                       # a package without package-info.java has no description section at all
    for member, body in _DETAIL.findall(page):
        if 'class="block"' not in body:
            out.append(f"{name}#{html.unescape(member)}")
    return out


def check_java(cfg: dict) -> list[str]:
    """Every documented module's built Javadoc: what lacks a comment, or that it was not built."""
    problems = []
    for m in cfg["java"]:
        folder = ROOT / m["module"] / APIDOCS
        if not (folder / "index.html").is_file():
            problems.append(f"{m['module']}: no Javadoc at {folder.relative_to(ROOT)} (build it: ./mvnw -o -P api-docs -pl {m['module']} -am process-classes)")
            continue
        for page in sorted(folder.rglob("*.html")):
            rel = page.relative_to(folder)
            if rel.parts[0] != "com" or "class-use" in rel.parts or (page.name in NOT_CLASS_PAGES and page.name != "package-summary.html"):
                continue
            name = ".".join(rel.with_suffix("").parts)
            name = name.removesuffix(".package-summary") + (" (package)" if page.name == "package-summary.html" else "")
            problems += [f"{m['module']}: {p} has no comment" for p in undocumented_in_page(page.read_text(encoding="utf-8"), name)]
    return problems


# ---- Python: every public function, class and method has a docstring and type hints -------------------------------------------------

def module_file(paths: list[str], module: str) -> Path:
    """The source file of ``module`` under one of the surface's paths (a package's ``__init__.py``)."""
    parts = module.split(".")
    for p in paths:
        base = ROOT / p
        for cand in (base.joinpath(*parts).with_suffix(".py"), base.joinpath(*parts) / "__init__.py"):
            if cand.is_file():
                return cand
    raise SystemExit(f"api_docs: module {module} is in none of {paths}")


def _public_names(tree: ast.Module) -> set[str] | None:
    for node in tree.body:
        if isinstance(node, ast.Assign) and any(isinstance(t, ast.Name) and t.id == "__all__" for t in node.targets):
            return set(ast.literal_eval(node.value))
    return None


def _function_problems(fn: ast.FunctionDef | ast.AsyncFunctionDef, where: str, method: bool) -> list[str]:
    out = []
    if fn.name != "__init__" and not ast.get_docstring(fn):
        out.append(f"{where}: no docstring")
    a = fn.args
    params = a.posonlyargs + a.args + a.kwonlyargs + [x for x in (a.vararg, a.kwarg) if x is not None]
    if method and params and params[0].arg in ("self", "cls") and not any(isinstance(d, ast.Name) and d.id == "staticmethod" for d in fn.decorator_list):
        params = params[1:]
    bare = [p.arg for p in params if p.annotation is None]
    if bare:
        out.append(f"{where}: no type hint for {', '.join(bare)}")
    if fn.returns is None and fn.name != "__init__":
        out.append(f"{where}: no return type")
    return out


def _class_problems(cls: ast.ClassDef, where: str) -> list[str]:
    out = [] if ast.get_docstring(cls) else [f"{where}: no docstring"]
    for node in cls.body:
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)) and (not node.name.startswith("_") or node.name == "__init__"):
            out += _function_problems(node, f"{where}.{node.name}", method=True)
        elif isinstance(node, ast.ClassDef) and not node.name.startswith("_"):
            out += _class_problems(node, f"{where}.{node.name}")
    return out


def python_problems(source: str, module: str) -> list[str]:
    """One module's public functions, classes and methods without a docstring or a type hint (``__all__`` says what is public)."""
    tree = ast.parse(source)
    out = [] if ast.get_docstring(tree) else [f"{module}: no module docstring"]
    public = _public_names(tree)
    for node in tree.body:
        name = getattr(node, "name", None)
        if name is None or not (name in public if public is not None else not name.startswith("_")):
            continue
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
            out += _function_problems(node, f"{module}.{name}", method=False)
        elif isinstance(node, ast.ClassDef):
            out += _class_problems(node, f"{module}.{name}")
    return out


def check_python(cfg: dict) -> list[str]:
    """Every Python surface's modules, read without being imported."""
    problems = []
    for s in cfg["python"]:
        for module in s["modules"]:
            path = module_file(s["paths"], module)
            problems += [f"{s['name']}: {p}" for p in python_problems(path.read_text(encoding="utf-8"), module)]
    return problems


# ---- build and zip ----------------------------------------------------------------------------------------------------------------

def _pdoc_command() -> list[str]:
    try:
        import pdoc  # noqa: F401
        return [sys.executable, "-m", "pdoc"]
    except ImportError:
        if not shutil.which("uv"):
            raise SystemExit("api_docs: pdoc is not installed and uv is not on the PATH (pip install pdoc, or install uv)")
        return ["uv", "run", "-q", "--no-project", "--with", f"pdoc=={config()['pdoc']}", "--with", "pyyaml", "python", "-m", "pdoc"]


def build_python(cfg: dict, dest: Path) -> None:
    """pdoc over every surface's modules at once (so they link to each other), into ``dest``."""
    paths = []
    for s in cfg["python"]:
        paths += [str(ROOT / p) for p in s["paths"] if str(ROOT / p) not in paths]
    modules = [m for s in cfg["python"] for m in s["modules"]]
    env = {**os.environ, "PYTHONPATH": os.pathsep.join(paths + [os.environ.get("PYTHONPATH", "")]).rstrip(os.pathsep), "PYTHONDONTWRITEBYTECODE": "1"}
    cmd = _pdoc_command() + ["--output-directory", str(dest), "--no-include-undocumented", "--footer-text", f"Drishti {version()}", *modules]
    subprocess.run(cmd, check=True, env=env, cwd=ROOT)


def build_java(cfg: dict, dest: Path, maven: bool) -> None:
    """Copies each module's Javadoc into ``dest/<module>`` (``maven``: builds it first)."""
    if maven:
        mods = ",".join(m["module"] for m in cfg["java"])
        subprocess.run([str(ROOT / "mvnw"), "-q", "-o", "-P", "api-docs", "-pl", mods, "-am", "process-classes"], check=True, cwd=ROOT)
    for m in cfg["java"]:
        src = ROOT / m["module"] / APIDOCS
        if not (src / "index.html").is_file():
            raise SystemExit(f"api_docs: no Javadoc for {m['module']} at {src.relative_to(ROOT)}: run with --maven, or ./mvnw -o -P api-docs "
                             f"-pl {m['module']} -am process-classes")
        shutil.copytree(src, dest / m["module"], ignore=shutil.ignore_patterns("options", "argfile", "packages", "files", "javadoc.sh"))


def _landing(cfg: dict, ver: str) -> str:
    def item(href: str, title: str, summary: str) -> str:
        return f'<li><a href="{html.escape(href)}">{html.escape(title)}</a><br><span>{html.escape(summary)}</span></li>'
    java = "".join(item(f"java/{m['module']}/index.html", f"{m['title']} ({m['module']})", m["summary"]) for m in cfg["java"])
    py = "".join(item(f"python/{s['modules'][0].replace('.', '/')}.html", s["title"], s["summary"]) for s in cfg["python"])
    return (f"<!DOCTYPE html>\n<html lang=\"en\"><head><meta charset=\"utf-8\"><title>Drishti {ver} API reference</title>"
            "<style>body{font:15px/1.5 system-ui,sans-serif;max-width:60rem;margin:2rem auto;padding:0 1rem}li{margin:.6rem 0}span{color:#555}</style>"
            f"</head><body><h1>Drishti {ver} API reference</h1><p>Generated at build time by tools/api_docs.py; the console serves it at Help &rarr; "
            f"Developer.</p><h2>Java (Javadoc)</h2><ul>{java}</ul><h2>Python (pdoc)</h2><ul>{py}</ul></body></html>\n")


def build(out: Path, maven: bool) -> Path:
    """The whole reference of this version into ``out/<version>``, replacing what was there."""
    cfg, ver = config(), version()
    target = out / ver
    work = out / f"building-{ver}"   # not a version folder: the console lists none until it is renamed
    shutil.rmtree(work, ignore_errors=True)
    work.mkdir(parents=True)
    try:
        build_java(cfg, work / "java", maven)
        build_python(cfg, work / "python")
        (work / "index.html").write_text(_landing(cfg, ver), encoding="utf-8")
        manifest = {"version": ver, "built": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
                    "java": [{k: m[k] for k in ("module", "title", "summary")} | {"href": f"java/{m['module']}/index.html"} for m in cfg["java"]],
                    "python": [{"name": s["name"], "title": s["title"], "summary": s["summary"], "modules": s["modules"],
                                "href": f"python/{s['modules'][0].replace('.', '/')}.html"} for s in cfg["python"]]}
        (work / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
        shutil.rmtree(target, ignore_errors=True)
        work.rename(target)
    finally:
        shutil.rmtree(work, ignore_errors=True)
    return target


def make_zip(out: Path, to: Path | None) -> Path:
    """``out/<version>`` as ``drishti-api-reference-<version>.zip`` (the GitHub release's attachment)."""
    ver = version()
    src = out / ver
    if not (src / "manifest.json").is_file():
        raise SystemExit(f"api_docs: nothing built at {src} (python3 tools/api_docs.py build first)")
    to = to or out / f"drishti-api-reference-{ver}.zip"
    with zipfile.ZipFile(to, "w", zipfile.ZIP_DEFLATED) as z:
        for f in sorted(src.rglob("*")):
            if f.is_file():
                z.write(f, Path(f"drishti-api-reference-{ver}") / f.relative_to(src))
    return to


def main(argv: list[str] | None = None) -> int:
    """The command line (see the module's docstring)."""
    ap = argparse.ArgumentParser(description="The Java and Python API references: check, build, zip.")
    ap.add_argument("command", choices=["check", "check-java", "check-python", "build", "zip"])
    ap.add_argument("--out", type=Path, default=DEFAULT_OUT, help="where the built references go (default: the console's web/apidocs)")
    ap.add_argument("--maven", action="store_true", help="build: run Maven's api-docs profile first")
    ap.add_argument("--to", type=Path, help="zip: the zip file to write")
    a = ap.parse_args(argv)
    cfg = config()
    if a.command.startswith("check"):
        problems = (check_java(cfg) if a.command in ("check", "check-java") else []) + (check_python(cfg) if a.command in ("check", "check-python") else [])
        for p in problems:
            print(p, file=sys.stderr)
        print(f"api_docs: {a.command}: {len(problems)} problem(s)" if problems else f"api_docs: {a.command}: every public member is documented")
        return 1 if problems else 0
    if a.command == "build":
        print(f"api_docs: built {build(a.out, a.maven)}")
        return 0
    print(f"api_docs: wrote {make_zip(a.out, a.to)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
