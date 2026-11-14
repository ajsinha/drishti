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

"""No Python reader goes its own way to configuration (docs/architecture/KERNEL_AND_MODULES.md section 18.3): only
drishti-console/core/resolution.py joins a path to the site's packs folder (config/packs), lists module or pack manifests
(``*/module.yaml``, ``*/pack.yaml``), or reads the site's connector files (config/connectors) or inbox rules (config/dataload.yaml).
Everything else asks the resolver (``from core.resolution import ...``). The checks read the code, not its comments or docstrings: a
path joined below ``"config" / "packs"`` or ``"config" / "connectors"`` (a pack's or a connector's file by hand) or to
``"config" / "dataload.yaml"``, a path text below those folders, and a ``glob``/``rglob`` of a manifest pattern. Naming the folder
itself is a setting (the folder handed to the resolver's helpers or to a process a harness starts), not a read. The allowlists below name
each exception and why."""
from __future__ import annotations

import ast
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
RESOLVER = "drishti-console/core/resolution.py"

#: The files that may do it anyway, each with its reason. Authoring tools write a NEW pack or module the user asked for into a folder
#: the user names; a test builds a scratch site (a tree of its own, never the checkout's files) to hand to the resolver.
ALLOWED = {
    RESOLVER: "the one resolver",
    "tools/test_config_readers_guard.py": "this guard names the patterns it looks for",
    "drishti-console/core/schemakit/build.py": "authoring: writes a new pack into its work folder",
    "drishti-console/core/packdesigner.py": "authoring: the pack designer writes the pack the user designs",
    "tools/packmake.py": "authoring: pack new writes the pack the user names",
    "tools/packgen/module_from_jsonl.py": "authoring: writes a new module (its connectors/, dataload.yaml) the user asked for",
    "tools/packgen/source_module.py": "authoring: writes a new source module the user asked for",
    "tools/packgen/pack_from_jsonl.py": "authoring: writes a new pack the user asked for",
    "tools/packs_to_modules.py": "the one-off move of the shipped packs into their modules (history; it only writes)",
    "tools/connector_upgrade_fixture.py": "builds the old releases' connector files from git for the upgrade test (writes a scratch site)",
    "tools/settings_catalogue.py": "the settings document names the site's rules file and connector examples (docs, no reading of packs)",
    "tools/packdiff.py": "compares two pack archives the user names (their own pack.yaml files, not the site's)",
}
#: Harnesses and tests build scratch sites (a tree of their own, never the checkout's files) and start servers with settings: they may
#: join below a configuration folder of the scratch site, but never list manifests.
SCRATCH = ("drishti-console/tests/", "tools/docs/shots/", "tools/drillkit/", "tools/bench/", "tools/dev/", "bin/lib/")
SKIP_DIRS = {".venv", "node_modules", "target", "data", ".git", ".claude", "build", "dist", "__pycache__"}
NAMES = {"packs", "connectors", "dataload.yaml"}
TEXTS = ("config/packs", "config/connectors", "config/dataload.yaml")
MANIFESTS = ("module.yaml", "pack.yaml")


def _files() -> list[Path]:
    out = []
    for f in ROOT.rglob("*.py"):
        rel = f.relative_to(ROOT)
        if SKIP_DIRS & set(rel.parts[:-1]):
            continue
        out.append(f)
    return sorted(out)


def _docstrings(tree: ast.AST) -> set[int]:
    ids = set()
    for node in ast.walk(tree):
        body = getattr(node, "body", None)
        if isinstance(body, list) and body and isinstance(body[0], ast.Expr) and isinstance(body[0].value, ast.Constant) \
                and isinstance(body[0].value.value, str):
            ids.add(id(body[0].value))
    return ids


def _chain(node: ast.AST) -> list:
    """A path joined with ``/``, as its parts (each constant's text, else None)."""
    if isinstance(node, ast.BinOp) and isinstance(node.op, ast.Div):
        return _chain(node.left) + _chain(node.right)
    return [node.value if isinstance(node, ast.Constant) and isinstance(node.value, str) else None]


def _pathlike(s: str) -> bool:
    """A path text below a configuration folder (``config/packs/core/gleif``, ``config/connectors/x.yaml``) or the site's rules file."""
    s = s.strip()
    return " " not in s and ("config/dataload.yaml" in s or any(f"{t}/" in s and not s.endswith(f"{t}/") for t in TEXTS[:2]))


def _scratch(rel: str) -> bool:
    name = rel.rsplit("/", 1)[-1]
    return rel.startswith(SCRATCH) or name.startswith("test_") or name == "conftest.py"


def violations(f: Path, scratch: bool = False) -> list[str]:
    try:
        tree = ast.parse(f.read_text(encoding="utf-8"))
    except (SyntaxError, UnicodeDecodeError):
        return []
    docs = _docstrings(tree)
    out = []
    for node in ast.walk(tree):
        if isinstance(node, ast.BinOp) and isinstance(node.op, ast.Div) and not scratch:
            parts = _chain(node)
            for i, (a, b) in enumerate(zip(parts, parts[1:])):
                if a == "config" and b in NAMES and (b == "dataload.yaml" or i + 2 < len(parts)):
                    out.append(f"{node.lineno}: joins below config / {b}")
        elif isinstance(node, ast.Constant) and isinstance(node.value, str) and id(node) not in docs and not scratch and _pathlike(node.value):
            out.append(f"{node.lineno}: names {node.value!r}")
        elif isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute) and node.func.attr in ("glob", "rglob") and node.args:
            a = node.args[0]
            if isinstance(a, ast.Constant) and isinstance(a.value, str) and a.value.split("/")[-1] in MANIFESTS:
                out.append(f"{node.lineno}: {node.func.attr}({a.value!r})")
    return sorted(set(out), key=lambda s: int(s.split(":")[0]))


@pytest.mark.static
def test_only_the_resolver_reads_the_configuration_folders():
    found = {}
    for f in _files():
        rel = f.relative_to(ROOT).as_posix()
        if rel in ALLOWED:
            continue
        v = violations(f, _scratch(rel))
        if v:
            found[rel] = v
    assert not found, "read through drishti-console/core/resolution.py (KERNEL_AND_MODULES.md 18.3):\n" + "\n".join(
        f"  {k}: {'; '.join(v)}" for k, v in found.items())


@pytest.mark.static
def test_the_allowlist_names_files_that_exist():
    assert not [f for f in ALLOWED if not (ROOT / f).is_file()]
