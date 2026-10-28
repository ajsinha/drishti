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

"""The data-prep snippets library of the Transform editor (docs/guides/PYTHON_BI.md section 17; docs/architecture/BI_DESIGN.md section 11.1, item P2).

A snippet is one step of a Transform: a few lines of pandas over ``df`` (the frame so far) with parameters a person fills in a form. The library is
``config/prep-snippets.yaml`` (and the file ``bi.transform.snippets_file`` names, if any). :meth:`SnippetLibrary.render` writes each value into the code as a
Python literal (``repr``), so a value is always data: a column called ``x"]; import os`` is a column of that odd name, never code. An ``input`` parameter
must be one of the Transform's own input names (written as that name) and an ``operator`` one of its options. Nothing here runs code: the rendered step
runs where every step runs, in the sandbox (``core/pyrunner.py``). :func:`run_example` runs a snippet's own example in plain pandas, for its tests."""
from __future__ import annotations

import keyword
import math
import re
from pathlib import Path
from string import Template

import yaml

KINDS = {"column", "columns", "input", "text", "number", "bool", "choice", "values", "mapping", "operator"}
IDENT = re.compile(r"[A-Za-z_][A-Za-z0-9_]{0,62}")
MAX_TEXT = 500
MAX_ITEMS = 1000


class SnippetError(ValueError):
    """A snippet or its parameters are not usable (the editor shows the message; DRS-9070)."""


class SnippetLibrary:
    """The snippets, read once from the YAML files (a console restart reads them again)."""

    def __init__(self, files: list[Path]):
        self.snippets: dict[str, dict] = {}
        for f in files:
            if not f or not Path(f).is_file():
                continue
            doc = yaml.safe_load(Path(f).read_text(encoding="utf-8")) or {}
            for s in doc.get("snippets") or []:
                self._add(s, Path(f).name)

    def _add(self, s: dict, where: str) -> None:
        sid = str(s.get("id") or "")
        if not re.fullmatch(r"[a-z0-9][a-z0-9-]{0,63}", sid):
            raise SnippetError(f"{where}: a snippet needs an id of lower-case letters, digits and '-' (got {sid!r})")
        for p in s.get("params") or []:
            if p.get("kind") not in KINDS or not IDENT.fullmatch(str(p.get("name") or "")):
                raise SnippetError(f"{where}: snippet {sid}: parameter {p.get('name')!r} has no usable name or kind ({', '.join(sorted(KINDS))})")
            if p["kind"] in ("choice", "operator") and not p.get("options"):
                raise SnippetError(f"{where}: snippet {sid}: parameter {p['name']} lists no options")
        Template(str(s.get("code") or "")).substitute({p["name"]: "x" for p in s.get("params") or []})   # every placeholder is a parameter
        self.snippets[sid] = s

    def list(self) -> list[dict]:
        """What the picker shows: each snippet's id, group, title, description and parameters (no code is needed to fill the form)."""
        return [{"id": s["id"], "group": s.get("group") or "Other", "title": s.get("title") or s["id"], "description": s.get("description") or "",
                 "params": [{k: p[k] for k in ("name", "label", "kind", "options", "default") if k in p} for p in s.get("params") or []]}
                for s in self.snippets.values()]

    def get(self, sid: str) -> dict:
        s = self.snippets.get(sid)
        if s is None:
            raise SnippetError(f"no snippet '{sid}' (there are {len(self.snippets)}: {', '.join(list(self.snippets)[:8])}, ...)")
        return s

    def render(self, sid: str, values: dict, inputs: list[str]) -> dict:
        """The step a snippet makes with these values: ``{label, code, snippet}``. ``inputs`` are the Transform's input names (an ``input`` parameter
        must be one of them). Raises :class:`SnippetError` naming the parameter that is wrong."""
        s = self.get(sid)
        literals = {}
        shown = []
        for p in s.get("params") or []:
            name = p["name"]
            v = values.get(name, p.get("default")) if isinstance(values, dict) else p.get("default")
            if v is None or (v == "" and p["kind"] not in ("text",)) or (v == [] and "default" not in p):
                if "default" not in p:
                    raise SnippetError(f"{s.get('title')}: fill in '{p.get('label') or name}'")
                v = p["default"]
            literals[name] = literal(p, v, inputs)
            if p["kind"] in ("column", "input") or (p["kind"] == "columns" and v):
                shown.append(", ".join(v) if isinstance(v, list) else str(v))
        code = Template(str(s["code"])).substitute(literals).rstrip("\n")
        label = (s.get("title") or sid) + (f" ({'; '.join(shown[:3])})" if shown else "")
        return {"label": label[:120], "code": code, "snippet": sid}


def literal(p: dict, v, inputs: list[str]) -> str:
    """One parameter's value as the text that goes into the code: a Python literal of the right kind, or (input, operator) a checked name or symbol."""
    kind, label = p["kind"], p.get("label") or p["name"]

    def text(x) -> str:
        if isinstance(x, bool) or not isinstance(x, (str, int, float)):
            raise SnippetError(f"'{label}' is text")
        x = str(x)
        if len(x) > MAX_TEXT or "\x00" in x:
            raise SnippetError(f"'{label}' is too long")
        return x

    if kind in ("column", "text"):
        return repr(text(v))
    if kind in ("columns", "values"):
        if isinstance(v, str):
            v = [x.strip() for x in v.split(",") if x.strip()]
        if not isinstance(v, list) or len(v) > MAX_ITEMS:
            raise SnippetError(f"'{label}' is a list")
        return repr([text(x) for x in v])
    if kind == "number":
        try:
            n = float(v)
        except (TypeError, ValueError):
            raise SnippetError(f"'{label}' is a number") from None
        if not math.isfinite(n):
            raise SnippetError(f"'{label}' is a number")
        return repr(int(n)) if n.is_integer() else repr(n)
    if kind == "bool":
        if isinstance(v, str):
            v = v.strip().lower() in ("true", "yes", "1", "on")
        return "True" if bool(v) else "False"
    if kind == "choice":
        if text(v) not in [str(o) for o in p["options"]]:
            raise SnippetError(f"'{label}' is one of {', '.join(map(str, p['options']))}")
        return repr(str(v))
    if kind == "operator":
        if str(v) not in [str(o) for o in p["options"]]:
            raise SnippetError(f"'{label}' is one of {' '.join(map(str, p['options']))}")
        return str(v)
    if kind == "mapping":
        if not isinstance(v, dict) or len(v) > MAX_ITEMS:
            raise SnippetError(f"'{label}' is pairs of a value and its replacement")
        return repr({text(k): (x if isinstance(x, (int, float)) and not isinstance(x, bool) else text(x)) for k, x in v.items()})
    if kind == "input":
        name = text(v)
        if name not in inputs or not IDENT.fullmatch(name) or keyword.iskeyword(name):
            raise SnippetError(f"'{label}' is one of the Transform's inputs ({', '.join(inputs) or 'none yet'})")
        return name
    raise SnippetError(f"unknown parameter kind {kind}")


# ---- the examples: each snippet's own test -------------------------------------------------------------------------------------------

def frame(spec: dict):
    import pandas as pd
    return pd.DataFrame([list(r) for r in spec.get("rows") or []], columns=list(spec.get("columns") or []))


def plain(v):
    """A cell as the examples write it: numbers, text, None for empty, dates as ISO text."""
    if v is None:
        return None
    if hasattr(v, "item") and not isinstance(v, (str, bytes)):
        try:
            v = v.item()
        except (ValueError, TypeError):
            pass
    if isinstance(v, float) and math.isnan(v):
        return None
    if type(v).__name__ in ("NAType", "NaTType"):
        return None
    if hasattr(v, "isoformat"):
        return v.isoformat()[:10] if not getattr(v, "hour", 0) and not getattr(v, "minute", 0) else v.isoformat()
    return v


def run_example(library: SnippetLibrary, sid: str):
    """Runs a snippet's example in plain pandas: the rendered step over its input frames. Returns (columns, rows) as the example's ``expect`` writes them."""
    import numpy as np
    import pandas as pd
    s = library.get(sid)
    ex = s["example"]
    frames = {name: frame(spec) for name, spec in (ex.get("inputs") or {}).items()}
    step = library.render(sid, ex.get("params") or {}, [n for n in frames if n != "df"])
    ns = {"pd": pd, "np": np, "re": re, **{k: v.copy() for k, v in frames.items()}}
    exec(compile(step["code"], f"<snippet {sid}>", "exec"), ns)   # noqa: S102 - the library's own tested code, in a test
    out = ns["df"]
    return [str(c) for c in out.columns], [[plain(v) for v in r] for r in out.astype(object).itertuples(index=False, name=None)]
