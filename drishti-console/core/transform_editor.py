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

"""The Transform editor's documents (docs/guides/PYTHON_BI.md section 17; docs/archive/BI_PARITY_PLAN.md P1): a Transform built step by step, as the page holds it, to
and from the two YAML items it is saved as. Nothing here runs code.

The page's **spec** is ``{name, title, inputs: [{name, input}], steps: [{label, code}], params, keys, columns, prelude}``: each input is either form a
Python dataset's ``source.inputs`` takes (a model query, or a pack kind read through a connector), each step a few lines of pandas over ``df``. It is
written as

* a **Python item** (``interface: transform``) whose ``run(<inputs>, params)`` holds the steps, each after a ``# step: <label>`` comment, and returns ``df``;
* a **Python dataset** (``rachana: 2``, ``source: {python, inputs}``) that runs it as the reader, its columns from the last preview.

:func:`parse` reads the two back into a spec, so the YAML a person edits by hand (or a Transform written before the editor) opens as steps again."""
from __future__ import annotations

import ast
import keyword
import re
import textwrap

import yaml

IDENT = re.compile(r"[A-Za-z_][A-Za-z0-9_]{0,62}")
NAME = re.compile(r"[a-z0-9][a-z0-9-]{0,63}")
STEP = re.compile(r"^\s*#\s*step:\s*(.*?)\s*$")
IMPORTS = ("import re", "import numpy as np", "import pandas as pd")
TYPES = {"string", "integer", "decimal", "boolean", "date", "timestamp"}
MAX_STEPS = 80
MAX_CODE = 65536


class EditorError(ValueError):
    """The spec or the YAML cannot be used (DRS-9070, with what to fix)."""


def item_name(text: str) -> str:
    n = re.sub(r"[^a-z0-9-]+", "-", str(text or "").lower()).strip("-")[:64]
    return n or "transform"


def check(spec: dict) -> dict:
    """The spec, checked and tidied: names, inputs, steps and their sizes. Raises :class:`EditorError`."""
    if not isinstance(spec, dict):
        raise EditorError("send {spec: {name, title, inputs, steps}}")
    name = item_name(spec.get("name") or spec.get("title"))
    title = str(spec.get("title") or name)[:120]
    inputs = []
    for i in spec.get("inputs") or []:
        n = str((i or {}).get("name") or "")
        if not IDENT.fullmatch(n) or keyword.iskeyword(n) or n in ("df", "params", "pd", "np", "re"):
            raise EditorError(f"an input's name is a Python name (letters, digits and underscore, not df, params, pd, np or re): '{n}'")
        if any(x["name"] == n for x in inputs):
            raise EditorError(f"two inputs are named '{n}'")
        src = (i or {}).get("input")
        if not isinstance(src, dict) or not (src.get("kind") or src.get("model")):
            raise EditorError(f"the input '{n}' reads a pack kind ({{kind, connector?, fields?}}) or a model query ({{model, select}})")
        inputs.append({"name": n, "input": src})
    if not inputs:
        raise EditorError("a Transform reads at least one input: add one first")
    steps = []
    for s in spec.get("steps") or []:
        code = str((s or {}).get("code") or "").rstrip()
        label = re.sub(r"[\r\n]+", " ", str((s or {}).get("label") or "Step")).strip()[:120] or "Step"
        if code:
            steps.append({"label": label, "code": code, **({"snippet": s["snippet"]} if s.get("snippet") else {})})
    if len(steps) > MAX_STEPS:
        raise EditorError(f"{len(steps)} steps; the most is {MAX_STEPS}")
    if sum(len(s["code"]) for s in steps) > MAX_CODE:
        raise EditorError(f"the steps are over {MAX_CODE:,} characters")
    params = spec.get("params") or {}
    if not isinstance(params, dict):
        raise EditorError("params is a mapping of a name to a value")
    columns = [{"name": str(c.get("name")), "type": c.get("type") if c.get("type") in TYPES else "string"} for c in spec.get("columns") or []
               if isinstance(c, dict) and IDENT.fullmatch(str(c.get("name") or ""))]
    keys = [str(k) for k in spec.get("keys") or [] if any(c["name"] == k for c in columns)]
    return {"name": name, "title": title, "inputs": inputs, "steps": steps, "params": params, "columns": columns, "keys": keys,
            "prelude": str(spec.get("prelude") or "").strip("\n")}


def source(spec: dict) -> str:
    """The Python of the spec: the imports, any code before ``run`` it came with, and ``run(<inputs>, params)`` with each step after its ``# step:`` comment."""
    s = check(spec)
    out = list(IMPORTS) + [""]
    if s["prelude"]:
        out += [s["prelude"], ""]
    out += ["", f"def run({', '.join(i['name'] for i in s['inputs'])}, params):"]
    steps = s["steps"] or [{"label": f"Start from {s['inputs'][0]['name']}", "code": f"df = {s['inputs'][0]['name']}.copy()"}]
    for st in steps:
        out.append(f"    # step: {st['label']}")
        out += [("    " + ln) if ln.strip() else "" for ln in st["code"].splitlines()]
    out.append("    return df")
    return "\n".join(out) + "\n"


def python_yaml(spec: dict, timeout: int = 20, memory: int = 512) -> str:
    s = check(spec)
    doc = {"rachana": 1, "python": s["name"], "title": s["title"], "interface": "transform", "inputs": [i["name"] for i in s["inputs"]],
           "packages": ["pandas", "numpy"], "target": "server", "timeout": int(timeout), "memory": int(memory), "params": s["params"], "source": source(spec)}
    return dump_yaml(doc)


def dataset_yaml(spec: dict) -> str:
    """The Python dataset that runs the Transform as the reader: its inputs as the spec has them, its columns from the last preview (the grain's keys
    those the author ticked, else the first column that was unique in the preview, else the first column)."""
    s = check(spec)
    cols = s["columns"] or [{"name": "id", "type": "string"}]
    keys = s["keys"] or [cols[0]["name"]]
    columns = {}
    for c in cols:
        entry = {"type": c["type"]}
        if c["name"] in keys:
            entry["role"] = "key"
        elif c["type"] in ("integer", "decimal"):
            entry["aggregate"] = "sum"
        columns[c["name"]] = entry
    doc = {"rachana": 2, "dataset": s["name"], "title": s["title"], "version": "1.0.0",
           "source": {"python": s["name"], "inputs": {i["name"]: i["input"] for i in s["inputs"]}, **({"params": s["params"]} if s["params"] else {})},
           "grain": {"keys": keys}, "columns": columns}
    return dump_yaml(doc)


def dump_yaml(doc: dict) -> str:
    """YAML as people write it: block style for text of several lines (the source), flow style for short lists and mappings."""
    class Block(yaml.SafeDumper):
        pass

    def text(dumper, value):
        return dumper.represent_scalar("tag:yaml.org,2002:str", value, style="|" if "\n" in value else None)

    Block.add_representer(str, text)
    return yaml.dump(doc, Dumper=Block, sort_keys=False, default_flow_style=None, width=200, allow_unicode=True)


def compose(spec: dict, timeout: int = 20, memory: int = 512) -> dict:
    """``{spec, source, python, dataset}``: the checked spec and what it is saved as."""
    s = check(spec)
    return {"spec": s, "source": source(s), "python": python_yaml(s, timeout, memory), "dataset": dataset_yaml(s)}


def parse(python_text: str, dataset_text: str = "") -> dict:
    """A spec from a Python item's YAML (and, when there is one, the Python dataset's): the inputs from ``run``'s parameters (their sources from the dataset),
    the steps from the ``# step:`` comments (a body without them is one step), ``return df`` dropped (another return becomes ``df = ...``)."""
    try:
        item = yaml.safe_load(python_text or "") or {}
        ds = yaml.safe_load(dataset_text or "") or {}
    except yaml.YAMLError as e:
        raise EditorError(f"the YAML does not read: {str(e).splitlines()[0]}") from None
    if not isinstance(item, dict) or item.get("interface") != "transform":
        raise EditorError("this is not a Python Transform (interface: transform)")
    src = str(item.get("source") or "")
    try:
        tree = ast.parse(src)
    except SyntaxError as e:
        raise EditorError(f"the source does not parse: line {e.lineno}: {e.msg}") from None
    fn = next((n for n in tree.body if isinstance(n, ast.FunctionDef) and n.name == "run"), None)
    if fn is None:
        raise EditorError("the editor opens a Transform whose run(...) is a top-level function (a class Transform opens as YAML only)")
    lines = src.splitlines()
    names = [a.arg for a in fn.args.args if a.arg not in ("params", "self")]
    sources = ((ds.get("source") or {}).get("inputs") or {}) if isinstance(ds, dict) else {}
    inputs = [{"name": n, "input": sources.get(n) or {}} for n in names]
    prelude = [ln for i, ln in enumerate(lines[:fn.lineno - 1 - len(fn.decorator_list)]) if ln.strip() not in IMPORTS]
    body_start = fn.body[0].lineno if fn.body else fn.lineno + 1
    first = min([body_start] + [i + 1 for i in range(fn.lineno, body_start) if STEP.match(lines[i])])
    body = textwrap.dedent("\n".join(lines[first - 1:fn.end_lineno])).splitlines()
    steps: list[dict] = []
    for ln in body:
        m = STEP.match(ln)
        if m:
            steps.append({"label": m.group(1) or f"Step {len(steps) + 1}", "lines": []})
        else:
            if not steps:
                steps.append({"label": "Start", "lines": []})
            steps[-1]["lines"].append(ln)
    out = []
    for st in steps:
        code = "\n".join(st["lines"]).rstrip()
        code_lines = code.splitlines()
        if code_lines and re.fullmatch(r"return\s+df\s*", code_lines[-1]) and st is steps[-1]:
            code_lines = code_lines[:-1]
        elif code_lines and code_lines[-1].startswith("return ") and st is steps[-1]:
            code_lines[-1] = "df = " + code_lines[-1][len("return "):]
        code = "\n".join(code_lines).strip("\n")
        if code.strip():
            out.append({"label": st["label"], "code": code})
    cols = ds.get("columns") if isinstance(ds, dict) else None
    columns = [{"name": k, "type": (v or {}).get("type", "string") if isinstance(v, dict) else str(v)} for k, v in (cols or {}).items()]
    keys = list(((ds.get("grain") or {}).get("keys") or [])) if isinstance(ds, dict) else []
    return {"name": str(item.get("python") or ""), "title": str(item.get("title") or item.get("python") or ""), "inputs": inputs, "steps": out,
            "params": item.get("params") or {}, "columns": columns, "keys": keys, "prelude": "\n".join(prelude).strip("\n")}
