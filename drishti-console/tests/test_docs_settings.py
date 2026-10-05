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

"""Every setting the code reads is in docs/admin/CONFIGURATION.md (DOC-16).

Three sources are compared with the document: every key of the two application.yaml files, every setting a
@ConfigurationProperties class of the server binds, and every console setting the Python code reads by name. A setting
counts as documented when its key (or its last segment) appears in backticks.
"""
from __future__ import annotations

import re
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
DOC = (ROOT / "docs" / "admin" / "CONFIGURATION.md").read_text(encoding="utf-8")
# Not Drishti settings (Spring's own, documented by Spring), and the per-connector tables documented plugin by plugin.
SKIPPED_PREFIXES = ("spring.", "server.compression", "server.tomcat", "logging.", "management.", "springdoc.", "drishti.sources.")


def _kebab(name: str) -> str:
    return re.sub(r"([a-z0-9])([A-Z])", r"\1-\2", name).lower()


def _mentioned(word: str) -> bool:
    return re.search(rf"(?<![\w-]){re.escape(word)}(?![\w-])", DOC) is not None


def _documented(key: str) -> bool:
    """The whole key, its last two segments (`oidc.scopes`) or its last one (`scopes`) is written in the document."""
    parts = key.split(".")
    return _mentioned(key) or _mentioned(".".join(parts[-2:])) or _mentioned(parts[-1])


def _flatten(node, prefix=""):
    if isinstance(node, dict):
        for k, v in node.items():
            yield from _flatten(v, f"{prefix}{k}.")
    else:
        yield prefix[:-1]


def _yaml_keys(rel: str) -> list[str]:
    keys = _flatten(yaml.safe_load((ROOT / rel).read_text(encoding="utf-8")))
    return [k for k in keys if not k.startswith(SKIPPED_PREFIXES)]


def test_every_key_of_the_server_application_yaml_is_documented():
    missing = [k for k in _yaml_keys("drishti-server/src/main/resources/application.yaml") if not _documented(k)]
    assert not missing, f"not in CONFIGURATION.md: {missing}"


def test_every_key_of_the_console_application_yaml_is_documented():
    missing = [k for k in _yaml_keys("drishti-console/config/application.yaml") if not _documented(k)]
    assert not missing, f"not in CONFIGURATION.md: {missing}"


def test_every_property_a_configuration_properties_class_binds_is_documented():
    missing = []
    for path in ROOT.rglob("*Properties.java"):
        text = path.read_text(encoding="utf-8")
        rel = path.relative_to(ROOT).parts
        if rel[0].startswith(".") or "target" in rel:   # .claude/worktrees (other branches' copies), build output
            continue
        if "/src/main/" not in str(path) or "@ConfigurationProperties" not in text:
            continue
        names = set(re.findall(r"^\s+private\s+(?:final\s+)?[\w.]+(?:<[^>]*>)?\s+(\w+)\s*(?:=|;)", text, re.M))
        names = {n for n in names if n[0].islower()}
        record = re.search(r"public record \w+\(([^)]*)\)", text, re.S)
        if record:
            names |= {part.split()[-1] for part in re.split(r",(?![^<]*>)", record.group(1)) if part.split()}
        missing += [f"{path.name}:{_kebab(n)}" for n in sorted(names) if n.isidentifier() and not _mentioned(_kebab(n))]
    assert not missing, f"not in CONFIGURATION.md: {missing}"


def test_every_setting_the_console_code_reads_by_name_is_documented():
    read = set()
    for path in (ROOT / "drishti-console").rglob("*.py"):
        if "tests" in path.parts or ".venv" in path.parts:
            continue
        read |= set(re.findall(r"settings\.get\(\s*\"([a-z_]+\.[a-z_.]+)\"", path.read_text(encoding="utf-8")))
    missing = sorted(k for k in read if not _documented(k))
    assert not missing, f"console settings read by the code and not in CONFIGURATION.md: {missing}"
