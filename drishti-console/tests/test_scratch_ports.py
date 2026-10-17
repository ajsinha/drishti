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

"""The guard on the scratch ports of the browser tests (tests/scratch_ports.py): under ``pytest -n 4 --dist loadfile`` two files run at once on two
workers, so a port written as a bare number, or one base number written in two files, lets one file stop the stack the other is using. Every
port assignment in a test file or a stack module goes through the worker offset, and no two files write the same base number."""
from __future__ import annotations

import ast
from collections import defaultdict
from pathlib import Path

TESTS = Path(__file__).resolve().parent
LOW, HIGH = 17000, 19999


def _numbers(node) -> list:
    out = []
    for n in ast.walk(node):
        if isinstance(n, ast.Constant) and isinstance(n.value, (int, str)) and not isinstance(n.value, bool):
            text = str(n.value)
            if text.isdigit() and LOW <= int(text) <= HIGH:
                out.append(int(text))
    return out


def _offset(node) -> bool:
    """True when the expression adds the pytest-xdist worker offset (worker_port(s) or a ..._WORKER name)."""
    for n in ast.walk(node):
        if isinstance(n, ast.Call):
            f = n.func
            name = f.id if isinstance(f, ast.Name) else f.attr if isinstance(f, ast.Attribute) else ""
            if name in ("worker_port", "worker_ports"):
                return True
        if isinstance(n, ast.Name) and "WORKER" in n.id:
            return True
    return False


def _port_target(t) -> bool:
    if isinstance(t, ast.Tuple):
        return any(_port_target(e) for e in t.elts)
    name = t.id if isinstance(t, ast.Name) else t.attr if isinstance(t, ast.Attribute) else ""
    return "PORT" in name.upper()


def _scan():
    """(file, line, numbers, has offset) for every port assignment and every port= keyword."""
    for path in sorted(TESTS.glob("*.py")):
        if path.name.endswith("_shots.py") or path.name == Path(__file__).name:      # the picture scripts run alone, with the ports they are given
            continue
        for node in ast.walk(ast.parse(path.read_text(), str(path))):
            if isinstance(node, (ast.Assign, ast.AnnAssign)) and node.value is not None:
                targets = node.targets if isinstance(node, ast.Assign) else [node.target]
                if any(_port_target(t) for t in targets) and _numbers(node.value):
                    yield path.name, node.lineno, _numbers(node.value), _offset(node.value)
            elif isinstance(node, ast.keyword) and node.arg == "port" and _numbers(node.value):
                yield path.name, node.value.lineno, _numbers(node.value), _offset(node.value)


def test_every_scratch_port_adds_the_worker_offset():
    bare = [f"{name}:{line} {nums}" for name, line, nums, offset in _scan() if not offset]
    assert not bare, "ports written without the pytest-xdist worker offset (use scratch_ports.worker_port/worker_ports): " + "; ".join(bare)


def test_no_two_files_write_the_same_base_port():
    files = defaultdict(set)
    for name, _line, nums, _offset in _scan():
        for n in nums:
            files[n].add(name)
    shared = {port: sorted(names) for port, names in files.items() if len(names) > 1}
    assert not shared, f"one base port in two files (two pytest workers would share one stack): {shared}"


def test_the_guard_sees_the_known_stacks():
    found = {name for name, *_ in _scan()}
    assert {"bi_report_stack.py", "test_w1_model_designer_browser.py", "test_bi_languages_browser.py", "test_embed_elements_browser.py"} <= found, found
