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

"""The console's side of the dependency rules (docs/architecture/DOMAIN_MODULES.md section 4, rule 1): the core never imports a domain
module's code, never includes one of its templates and never names one of its static files; a module reaches the core only through the
hooks (core/plugins.py) and its plugin.yaml. A module's console part is what its plugins/<module>/plugin.yaml lists (routers, registers,
code, templates, assets); everything else of drishti-console is the core. Quick file checks: no server, console or browser."""
from __future__ import annotations

import ast
import fnmatch
import re

import pytest

from conftest import CONSOLE
from core import modules

pytestmark = pytest.mark.static

WEB = CONSOLE / "web"


def _module_code() -> dict[str, str]:
    """Every Python module of a domain module's console part, by dotted name -> the module id."""
    out = {}
    for p in modules.plugins():
        for name in p.routers + p.registers + p.code:
            out[name] = p.id
    return out


def _dotted(path) -> str:
    return ".".join(path.relative_to(CONSOLE).with_suffix("").parts)


def _imports(tree: ast.AST) -> set[str]:
    out = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            out |= {a.name for a in node.names}
        elif isinstance(node, ast.ImportFrom) and node.module:
            out.add(node.module)
            out |= {f"{node.module}.{a.name}" for a in node.names}
    return out


def test_every_plugin_names_files_that_exist():
    for p in modules.plugins():
        for name in p.routers + p.registers + p.code:
            assert (CONSOLE / (name.replace(".", "/") + ".py")).is_file(), f"{p.id}: {name}"
        for t in p.templates:                                   # a module's templates live in its own folder, never in the core's
            assert (modules.PLUGINS / p.id / "templates" / t).is_file() and not (WEB / "templates" / t).exists(), f"{p.id}: {t}"
        for g in p.assets:
            assert any(list(r.glob(g)) for r in [WEB / "static"] + modules.static_dirs()), f"{p.id}: no file matches {g}"
            assert not g.startswith(("js/", "css/")) or not list((WEB / "static").glob(g)), f"{p.id}: {g} is in the core's web/static"


def test_the_core_imports_no_module_code():
    own = _module_code()
    bad = []
    for f in sorted(list((CONSOLE / "core").glob("*.py")) + list((CONSOLE / "routes").glob("*.py"))):
        name = _dotted(f)
        if name in own:
            continue
        for imp in _imports(ast.parse(f.read_text(encoding="utf-8"))):
            hit = next((m for m in own if imp == m or imp.startswith(m + ".") or imp == "plugins." + own[m] or imp.startswith("plugins.")), None)
            if hit is not None:
                bad.append(f"{name} imports {imp}")
    assert not bad, "the core imports a module's code (use a hook of core/plugins.py):\n" + "\n".join(bad)


def test_the_modules_do_not_import_each_other():
    own = _module_code()
    bad = []
    for name, mid in own.items():
        f = CONSOLE / (name.replace(".", "/") + ".py")
        for imp in _imports(ast.parse(f.read_text(encoding="utf-8"))):
            other = next((own[m] for m in own if imp == m or imp.startswith(m + ".")), None)
            if other is not None and other != mid:
                bad.append(f"{name} ({mid}) imports {imp} ({other})")
    assert not bad, "\n".join(bad)


def _module_templates() -> set[str]:
    return {t for p in modules.plugins() for t in p.templates}


def test_no_core_template_includes_a_module_template_or_names_its_files():
    mine = _module_templates()
    globs = [g for p in modules.plugins() for g in p.assets]
    bad = []
    for f in sorted((WEB / "templates").rglob("*.html")):
        rel = f.relative_to(WEB / "templates").as_posix()
        if rel in mine:
            continue
        text = f.read_text(encoding="utf-8")
        for m in re.finditer(r'{%\s*(?:include|extends|import|from)\s+"([^"]+)"', text):
            if m.group(1) in mine:
                bad.append(f"{rel} includes {m.group(1)}")
        for m in re.finditer(r'/static/([A-Za-z0-9_./-]+)', text):
            if any(fnmatch.fnmatchcase(m.group(1), g) for g in globs):
                bad.append(f"{rel} names {m.group(1)}")
    assert not bad, "a core template reaches a module's part (its plugin.yaml screens: is the hook):\n" + "\n".join(bad)


def test_no_core_script_or_style_names_a_module_file():
    globs = [g for p in modules.plugins() for g in p.assets]
    names = {f.name for p in modules.plugins() for g in p.assets for r in [WEB / "static"] + modules.static_dirs() for f in r.glob(g)
             if f.is_file()}
    bad = []
    for f in sorted(list((WEB / "static" / "js").glob("*.js")) + list((WEB / "static" / "css").glob("*.css"))):
        rel = f.relative_to(WEB / "static").as_posix()
        if any(fnmatch.fnmatchcase(rel, g) for g in globs):
            continue
        text = f.read_text(encoding="utf-8")
        for n in names:
            if re.search(r"(?<![\w-])" + re.escape(n) + r"(?![\w-])", text):
                bad.append(f"{rel} names {n}")
    assert not bad, "a core script or style loads a module's file:\n" + "\n".join(bad)
