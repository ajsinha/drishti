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

"""The console's boundary (owner decision, memory console-boundary.md): the console uses ONLY the server's published API.

It reads no file or folder of the server (its config, lakes, identity database, modules, data folder): it may read its own configuration,
its templates and static files, the docs it renders for Help (``docs/`` and the generated copies) and the few whitelisted shared files
below, each with its reason. It runs on a host that holds none of the server's folders (docs/admin/PRODUCTION_STANDARD.md, Console).

The scan reads the console's Python (``core/``, ``routes/``, ``plugins/``, ``run_drishti_web.py``) and ``config/application.yaml`` and reports
every place that reaches outside the console's own folder or names a server-internal location, as ``file:line: rule``:

- ``outside``: a path built from the file's own location that leaves the console folder (``parents[2]``, ``.parent.parent.parent``);
- ``server-path``: a string naming a server-internal location (a lake's ``_delta_log``, ``config/distributions``, the identity database, ...);
- ``env-root``: the environment variable of a server folder (``DRISHTI_DATA_DIR``, ``DRISHTI_MODULES_HOME``, ``DRISHTI_HOME``, ``DRISHTI_LIB_PATH``);
- ``up-default``: a setting whose default is a folder beside the console (``"../data"``, ``"../tools"``).

A finding is fine when ``ALLOWED`` names its file and rule with a reason. The rest is ``PENDING``: known violations, each with the lane
that fixes it; the list only shrinks (a fixed file must come off it). A new finding fails the test.
"""
import ast
import re
from pathlib import Path

import pytest

pytestmark = pytest.mark.static

CONSOLE = Path(__file__).resolve().parents[1]

SERVER_PATH = re.compile(r"_delta_log|config/distributions|config/lenses|under-construction\.yaml|identity\.db|\.sqlite|\bjournal/|kernel\.yaml|module\.yaml|/lake/|_drishti-lake")
ENV_ROOT = {"DRISHTI_DATA_DIR", "DRISHTI_MODULES_HOME", "DRISHTI_HOME", "DRISHTI_LIB_PATH", "DRISHTI_EXTERNAL_LIB"}

#: (file, rule) -> why this reach is not a server file. Everything here is the console's own, the docs it renders, or a tool it runs.
ALLOWED: dict[tuple[str, str], str] = {
    ("core/config.py", "outside"): "the install root (drishti.home) the console's own settings file and profiles are found under; its own configuration",
    ("core/config.py", "env-root"): "SHARED: the one settings file's drishti.home / drishti.data.dir, read as the console's own setting values (never to open a server file)",
    ("core/settings_ini.py", "outside"): "names the tools/drishti.py command the setup line prints (text), reads nothing of the server",
    ("core/settings_ini.py", "env-root"): "the list of variables the trust rule drops when they name a folder outside the install; reads no folder",
    ("core/tmpdir.py", "outside"): "the console's temp base under <data>/tmp/console when unset: its own scratch folder, never a server one",
    ("core/tmpdir.py", "env-root"): "the data folder only anchors the console's own <data>/tmp/console folder",
    ("core/app.py", "env-root"): "the same: <data>/console, the console's own subfolder",
    ("core/app.py", "up-default"): "studio.examples_dir default (and help.docs_dir): the docs the console renders for Help (allowed), not server files",
    ("core/external_lib.py", "outside"): "optional shared read-only Python libraries (external_lib/python), imported by the console's sandboxed workers; never written, absent is fine",
    ("core/external_lib.py", "env-root"): "DRISHTI_EXTERNAL_LIB names that same optional read-only library folder",
    ("run_drishti_web.py", "env-root"): "start-up only: the console's temp folder under <data>/tmp/console",
    ("run_drishti_web.py", "up-default"): "text of the pip command the console prints (the lock file's path), not a read",
    ("core/module_parts.py", "server-path"): "module.yaml of the console's OWN cache folder: the module's console part arrives from the server as a zip (GET /public/modules/console/<id>/<version>) and is unpacked there",
    ("routes/pack_routes.py", "up-default"): "builder.tools_dir: the Drishti Tools the Pack designer runs on the console host (a job in the console's own workspace; the result goes to the server by API)",
    ("routes/help_routes.py", "outside"): "ROOT is the folder the Help pages' docs and the generated copies are read from (docs/ and the root READMEs the Help renders)",
}

#: known violations: file -> what it reads and who fixes it. The list only shrinks; the lead schedules each (the lanes in flight own the files).
PENDING: dict[str, str] = {
    "core/lakes.py": "B4 table registry lane: reads <data>/delta lake folders (_delta_log) and the lake_roots to list tables, and writes uploads into them -> a server endpoint for the lake's tables and uploads",
    "core/lake_maint.py": "B4 table registry lane: keeps <data>/lakes/lake-maintenance.yaml and its history in the server's data folder -> the server's maintenance API",
    "routes/lake_routes.py": "B4 table registry lane: Admin -> Lakes table page reads <root>/_delta_log and <root>/_drishti-lake/runs/*.json of the server's lake",
}

#: settings defaults of config/application.yaml that name a folder beside the console, each fine: the docs the console renders
YAML_UP_ALLOWED = {"help.docs_dir", "features.examples_dir", "builder.tools_dir"}
YAML_UP_PENDING = {
    "lakes.data_dir": "B4 table registry lane (see core/lakes.py)",
    "packs.dir": "dead since the thin console: no console code reads packs.dir, installed_dir or extra_dirs (a pack's content comes from the server's answer); delete the three keys with their CONFIGURATION.md rows (B3 site-config lane owns that file)",
}


def _sources():
    for folder in ("core", "routes", "plugins"):
        yield from sorted((CONSOLE / folder).rglob("*.py"))
    yield CONSOLE / "run_drishti_web.py"


def _docstrings(tree) -> set[int]:
    """Ids of the string nodes that are docstrings (words, never paths)."""
    out = set()
    for node in ast.walk(tree):
        if isinstance(node, (ast.Module, ast.ClassDef, ast.FunctionDef, ast.AsyncFunctionDef)) and node.body:
            first = node.body[0]
            if isinstance(first, ast.Expr) and isinstance(first.value, ast.Constant) and isinstance(first.value.value, str):
                out.add(id(first.value))
    return out


def _chain_parents(node) -> int:
    n = 0
    while isinstance(node, ast.Attribute) and node.attr == "parent":
        n += 1
        node = node.value
    return n


def findings() -> list[tuple[str, int, str, str]]:
    """(file, line, rule, text) of every reach out of the console's folder."""
    found = []
    for path in _sources():
        rel = path.relative_to(CONSOLE).as_posix()
        if rel.startswith("core/schemakit/"):                    # the Pack designer's own build library: it writes the console workspace's pack folder
            continue
        tree = ast.parse(path.read_text(encoding="utf-8"))
        docs = _docstrings(tree)
        for node in ast.walk(tree):
            if isinstance(node, ast.Subscript) and isinstance(node.value, ast.Attribute) and node.value.attr == "parents":
                idx = node.slice.value if isinstance(node.slice, ast.Constant) else None
                if isinstance(idx, int) and idx >= 2:
                    found.append((rel, node.lineno, "outside", f"parents[{idx}]"))
            if isinstance(node, ast.Attribute) and node.attr == "parent" and _chain_parents(node) >= 3 and not isinstance(getattr(node, "_up", None), ast.Attribute):
                found.append((rel, node.lineno, "outside", ".parent" * _chain_parents(node)))
            if isinstance(node, ast.Constant) and isinstance(node.value, str) and id(node) not in docs:
                v = node.value
                if v in ENV_ROOT:
                    found.append((rel, node.lineno, "env-root", v))
                elif "\n" not in v and len(v) < 200 and SERVER_PATH.search(v):
                    found.append((rel, node.lineno, "server-path", v))
                elif re.match(r"^\.\./(data|tools|docs|config|modules|lib)\b", v):
                    found.append((rel, node.lineno, "up-default", v))
    # a chain of .parent is found once per link: keep the longest
    best = {}
    for f in found:
        key = (f[0], f[1], f[2])
        if key not in best or len(f[3]) > len(best[key][3]):
            best[key] = f
    return sorted(best.values())


def yaml_up_defaults() -> list[tuple[str, str]]:
    """(dotted key, default) of the console's application.yaml settings whose default is a folder beside the console."""
    import yaml
    doc = yaml.safe_load((CONSOLE / "config" / "application.yaml").read_text(encoding="utf-8")) or {}
    out = []

    def walk(prefix, node):
        if isinstance(node, dict):
            for k, v in node.items():
                walk(f"{prefix}.{k}" if prefix else str(k), v)
        elif isinstance(node, str) and re.search(r"(^|:)\.\./(data|tools|docs|config|modules|lib)\b", node):
            out.append((prefix, node))
    walk("", doc)
    return out


def test_no_new_reach_outside_the_console():
    new = [f"{f}:{n}: {rule} {text!r}" for f, n, rule, text in findings()
           if (f, rule) not in ALLOWED and f not in PENDING]
    assert not new, ("the console reads the server's files again; ask the server's published API instead (add a small endpoint, documented in "
                     "API_REFERENCE, when none exists), or, for the console's own file, add it to ALLOWED with the reason:\n  " + "\n  ".join(new))


def test_allowed_entries_are_all_still_used_and_pending_only_shrinks():
    used = {(f, rule) for f, _n, rule, _t in findings()}
    stale = sorted(set(ALLOWED) - used)
    assert not stale, f"take these off ALLOWED (the reach is gone): {stale}"
    hit = {f for f, _n, _r, _t in findings()}
    done = sorted(set(PENDING) - hit)
    assert not done, f"take these off PENDING (fixed): {done}"


def test_the_settings_defaults_beside_the_console_are_the_docs_and_the_tools_only():
    bad = [f"{k}: {v}" for k, v in yaml_up_defaults() if k not in YAML_UP_ALLOWED and k not in YAML_UP_PENDING]
    assert not bad, f"a console setting defaults to a folder of the server's install: {bad}"
    gone = sorted(set(YAML_UP_PENDING) - {k for k, _v in yaml_up_defaults()})
    assert not gone, f"take these off YAML_UP_PENDING (fixed): {gone}"


def test_every_whitelist_entry_has_a_reason():
    for table in (ALLOWED, PENDING, YAML_UP_PENDING):
        for k, why in table.items():
            assert len(why.split()) >= 4, f"{k}: say why"


def test_the_console_imports_nothing_of_the_server_tools_or_modules():
    """Console code imports the console's own packages (core, routes, plugins) and libraries; the shared Python of the tools folder is run as
    a command (a subprocess), never imported: a console deployed alone has no tools folder on its path."""
    bad = []
    for path in _sources():
        rel = path.relative_to(CONSOLE).as_posix()
        for node in ast.walk(ast.parse(path.read_text(encoding="utf-8"))):
            mods = [a.name for a in node.names] if isinstance(node, ast.Import) else [node.module or ""] if isinstance(node, ast.ImportFrom) and not node.level else []
            for m in mods:
                if m.split(".")[0] in ("tools", "modules", "drishti_tools"):
                    bad.append(f"{rel}:{node.lineno}: {m}")
    assert not bad, f"the console imports the tools or a module of the server's install: {bad}"
