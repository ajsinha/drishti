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

"""The Sutra designer's Grid mode (SCREEN_DESIGNER.md section 31): the screen as a spreadsheet over the SAME Sutra YAML the other modes
edit. Columns A, B, C... and rows 1, 2, 3... are the cells of ``layout.areas`` (an area is a cell or a merged range); a screen without
areas (the automatic flow, or sections) is one column, a panel a row. Each cell shows its panels as formulas::

    A2 = row-picker@1(id: trades, rows: $.TRADES)
    B2 = field-list@1(id: terms, rows: $src; source: {kind: trade, id: "@A2.open.trade"})
    C1 = table(id: flows, title: Flows, rows: $.CASHFLOWS)

A formula is the fragment (``name@version``, its ``with:`` parameters; after ``;`` the panel's own settings) or a panel kind (its
settings). ``@A2.open.trade`` is ``@<the panel in A2>.open.trade``. What a formula cannot say in one line (a table's columns, emits,
children, a fragment's nested parameters) is kept as it is when a formula is typed, and the cell says so; the grid keys (area, span,
order, section, height, collapsible) belong to the cell. :func:`grid` reads a Sutra into cells and :func:`rebuild` writes the cells back:
the round trip is exact on every pack Sutra. The steps (set a formula, merge, split, insert or delete a row or column, move a cell) change
the document; the server checks the result."""
from __future__ import annotations

import copy
import json
import re

from core.designer import DesignerError, entry_title

GRID_KEYS = ("area", "span", "order", "section", "height", "collapsible")
LONG = ("columns", "emits", "children", "panels", "form")      # never in a formula: edited in Customize, Outline or the form editor
CELL = re.compile(r"[A-Z]{1,2}[1-9]\d{0,2}")
NAME = re.compile(r"[a-z][a-z0-9-]{0,39}")
_INT, _FLOAT = re.compile(r"-?\d+"), re.compile(r"-?(\d+\.\d*|\.\d+)([eE][-+]?\d+)?")
_OPEN, _CLOSE = "([{", ")]}"


class FormulaError(DesignerError):
    """A formula that cannot be read: the message in plain words and the part to fix (``start``, ``end`` in the formula's text)."""

    def __init__(self, message: str, start: int = 0, end: int = 0):
        super().__init__(message)
        self.start, self.end = start, max(end, start + 1)


# ---- cell names -------------------------------------------------------------------------------------------------------------------

def col_name(c: int) -> str:
    return (chr(65 + c // 26 - 1) if c >= 26 else "") + chr(65 + c % 26)


def cell_name(r: int, c: int) -> str:
    return f"{col_name(c)}{r + 1}"


def cell_at(name: str) -> tuple[int, int]:
    m = re.fullmatch(r"([A-Z]{1,2})(\d{1,3})", str(name or "").strip().upper())
    if not m:
        raise DesignerError(f"'{name}' is not a cell: a letter and a number, such as B2")
    letters, c = m.group(1), 0
    for ch in letters:
        c = c * 26 + ord(ch) - 64
    return int(m.group(2)) - 1, c - 1


# ---- values: one line, read back exactly ------------------------------------------------------------------------------------------

class _Reader:
    def __init__(self, text: str, base: int = 0):
        self.t, self.i, self.base = text, 0, base

    def err(self, msg: str, start: int | None = None, end: int | None = None):
        s = self.i if start is None else start
        raise FormulaError(msg, self.base + s, self.base + (s + 1 if end is None else end))

    def ws(self):
        while self.i < len(self.t) and self.t[self.i] in " \t\r\n":
            self.i += 1

    def peek(self) -> str:
        self.ws()
        return self.t[self.i] if self.i < len(self.t) else ""

    def value(self, stops: str):
        ch = self.peek()
        if ch == '"':
            return self.quoted()
        if ch == "'" and self._whole_quote(stops):
            return self.quoted()
        if ch == "[":
            return self.seq()
        if ch == "{":
            return self.mapping()
        return self.bare(stops)

    def _whole_quote(self, stops: str) -> bool:
        """A '...' that is the whole value (else it is part of a bare expression, such as 'TENOR' in lookup(...))."""
        j = self.t.find("'", self.i + 1)
        if j < 0:
            return False
        k = j + 1
        while k < len(self.t) and self.t[k] in " \t":
            k += 1
        return k >= len(self.t) or self.t[k] in stops

    def quoted(self):
        q, start = self.t[self.i], self.i
        if q == '"':
            j, esc = self.i + 1, False
            while j < len(self.t):
                if esc:
                    esc = False
                elif self.t[j] == "\\":
                    esc = True
                elif self.t[j] == '"':
                    break
                j += 1
            if j >= len(self.t):
                self.err("a quote is not closed: add the closing \"", start, len(self.t))
            try:
                v = json.loads(self.t[start:j + 1])
            except ValueError:
                self.err("this quoted text has a bad \\ escape", start, j + 1)
            self.i = j + 1
            return v
        j = self.t.find("'", self.i + 1)
        self.i = j + 1
        return self.t[start + 1:j].replace("''", "'")

    def seq(self):
        start = self.i
        self.i += 1
        out = []
        if self.peek() == "]":
            self.i += 1
            return out
        while True:
            out.append(self.value(",]"))
            ch = self.peek()
            if ch == ",":
                self.i += 1
            elif ch == "]":
                self.i += 1
                return out
            else:
                self.err("a list is not closed: add ]", start, self.i)

    def mapping(self):
        start = self.i
        self.i += 1
        out: dict = {}
        if self.peek() == "}":
            self.i += 1
            return out
        while True:
            k = self.key()
            out[k] = self.value(",}")
            ch = self.peek()
            if ch == ",":
                self.i += 1
            elif ch == "}":
                self.i += 1
                return out
            else:
                self.err("a { … } is not closed: add }", start, self.i)

    def key(self) -> str:
        self.ws()
        m = re.compile(r"[A-Za-z_$][\w.$-]*").match(self.t, self.i)
        if not m:
            self.err("a name is wanted here, then a colon: name: value")
        self.i = m.end()
        if self.peek() != ":":
            self.err(f"a colon is wanted after {m.group(0)}: {m.group(0)}: value", m.start(), m.end())
        self.i += 1
        return m.group(0)

    def bare(self, stops: str):
        self.ws()
        start, depth, j = self.i, [], self.i
        while j < len(self.t):
            ch = self.t[j]
            if ch in "\"'":
                k = self.t.find(ch, j + 1)
                if k < 0:
                    self.i = j
                    self.err("a quote is not closed", j, len(self.t))
                j = k + 1
                continue
            if ch in _OPEN:
                depth.append(_CLOSE[_OPEN.index(ch)])
            elif ch in _CLOSE:
                if not depth:
                    if ch in stops:
                        break
                    self.i = j
                    self.err(f"this {ch} has nothing to close", j, j + 1)
                if depth.pop() != ch:
                    self.i = j
                    self.err(f"this {ch} closes the wrong bracket", j, j + 1)
            elif not depth and ch in stops:
                break
            j += 1
        if depth:
            self.i = start
            self.err(f"a bracket opened here is not closed: add {depth[-1]}", start, j)
        text = self.t[start:j].strip()
        self.i = j
        if not text:
            self.err("a value is missing here", start, start + 1)
        return _scalar(text)


def _scalar(text: str):
    if text in ("true", "false"):
        return text == "true"
    if text in ("null", "~"):
        return None
    if _INT.fullmatch(text):
        return int(text)
    if _FLOAT.fullmatch(text):
        return float(text)
    return text


def _same(a, b) -> bool:
    return type(a) is type(b) and (a == b if not isinstance(a, (list, dict)) else json.dumps(a, sort_keys=False, default=str) == json.dumps(b, sort_keys=False, default=str))


def emit(v) -> str:
    """A value in one line, so that reading it gives the same value (else the caller keeps it out of the formula)."""
    if isinstance(v, dict):
        return "{" + ", ".join(f"{k}: {emit(x)}" for k, x in v.items()) + "}"
    if isinstance(v, list):
        return "[" + ", ".join(emit(x) for x in v) + "]"
    if isinstance(v, bool):
        return "true" if v else "false"
    if v is None:
        return "null"
    if isinstance(v, (int, float)):
        return repr(v)
    s = str(v)
    if s and s == s.strip() and "\n" not in s and s[0] not in "\"'[{" and not any(ch in s for ch in ",;}]"):
        try:
            r = _Reader(s)
            got = r.bare(",;)]}")
            if r.i == len(s) and _same(got, s):
                return s
        except FormulaError:
            pass
    return json.dumps(s, ensure_ascii=False)


def readable(v) -> bool:
    """Whether a value reads back exactly from its formula text."""
    if isinstance(v, (dict, list)) and any(isinstance(x, (dict, list)) for x in (v.values() if isinstance(v, dict) else v)):
        return False
    if isinstance(v, dict) and not all(re.fullmatch(r"[A-Za-z_$][\w.$-]*", str(k)) for k in v):
        return False
    if not isinstance(v, (str, int, float, bool, list, dict, type(None))):
        return False
    try:
        r = _Reader(emit(v))
        got = r.value(",;)")
        return r.peek() == "" and _same(got, v)
    except FormulaError:
        return False


# ---- formulas ---------------------------------------------------------------------------------------------------------------------

def _refs(v, swap):
    if isinstance(v, str):
        return swap(v)
    if isinstance(v, list):
        return [_refs(x, swap) for x in v]
    if isinstance(v, dict):
        return {k: _refs(x, swap) for k, x in v.items()}
    return v


def to_cells(names: dict):
    """``@<panel>.`` written ``@<cell>.`` for the panels that are alone in a cell (``names``: panel id → cell)."""
    def swap(s: str) -> str:
        return re.sub(r"@([A-Za-z_][\w-]*)\.", lambda m: "@" + names[m.group(1)] + "." if m.group(1) in names else m.group(0), s)
    return swap


def to_panels(cells: dict, ids: set):
    """``@<cell>.`` read back as ``@<panel>.`` (``cells``: cell → panel id); a cell holding no single panel is said."""
    def swap(s: str) -> str:
        def one(m):
            c = m.group(1)
            if c in ids:
                return m.group(0)
            if c not in cells:
                raise FormulaError(f"@{c} names a cell that holds no single panel: point at a cell with one panel, or at the panel's id")
            return "@" + cells[c] + "."
        return re.sub(r"@(" + CELL.pattern + r")\.", one, s)
    return swap


def panel_id(e) -> str:
    return str(e.get("id") or (e.get("with") or {}).get("id") or "") if isinstance(e, dict) else ""


def shown(e: dict) -> tuple[dict, dict]:
    """What a panel's formula shows: (the fragment's parameters, the panel's own settings) for a fragment; ({}, settings) for a kind."""
    def pick(d: dict, skip=()) -> dict:
        return {k: v for k, v in d.items() if k not in skip and k not in GRID_KEYS and k not in LONG and readable(v)}
    if e.get("use"):
        w = e.get("with") if isinstance(e.get("with"), dict) else {}
        return {k: v for k, v in w.items() if k not in LONG and readable(v)}, pick(e, ("use", "with"))
    return {}, pick(e, ("kind",))


def kept(e: dict) -> list:
    """What the formula leaves out and a typed formula keeps: names in words, for the cell."""
    args, opts = shown(e)
    out = [k for k in e if k not in ("use", "with", "kind") and k not in GRID_KEYS and k not in opts]
    if e.get("use") and isinstance(e.get("with"), dict):
        out += [f"with.{k}" for k in e["with"] if k not in args]
    return out


def formula(e: dict, names: dict | None = None) -> str:
    """One panel as a formula (``names``: panel id → cell, for ``@A2.`` references)."""
    swap = to_cells(names or {})
    args, opts = shown(e)
    def items(d):
        return ", ".join(f"{k}: {emit(_refs(v, swap))}" for k, v in d.items())
    if e.get("use"):
        inner = items(args) + ("; " + items(opts) if opts else "")
        return f"{e['use']}({inner})"
    return f"{e.get('kind') or 'panel'}({items(opts)})"


def parse(text: str) -> list[dict]:
    """A cell's formulas, one panel a line (a leading ``B2 =`` or ``=`` is allowed): a list of ``{head, use, args, opts, at}``. Raises
    :class:`FormulaError` with the part to fix."""
    calls, pos = [], 0
    for line in (text or "").split("\n"):
        base, pos = pos, pos + len(line) + 1
        body = line
        m = re.match(r"\s*(?:[A-Z]{1,2}\d{1,3}(?::[A-Z]{1,2}\d{1,3})?\s*=)?\s*", body)
        lead = m.end() if m else 0
        if not body[lead:].strip():
            continue
        if body[lead] == "=":
            lead += 1
        calls.append(_call(body, lead, base))
    return calls


def _call(line: str, lead: int, base: int) -> dict:
    r = _Reader(line, base)
    r.i = lead
    r.ws()
    m = re.compile(r"[a-z][a-z0-9-]*(?:/[a-z0-9-]+)?(?:@\d+)?").match(line, r.i)
    if not m:
        r.err("a formula starts with a fragment (such as field-list@1) or a panel kind (such as table)", r.i, len(line))
    head, r.i = m.group(0), m.end()
    if r.peek() != "(":
        r.err(f"a ( is wanted after {head}: {head}( … )", r.i, len(line))
    opened = r.i
    r.i += 1
    use = "@" in head or "/" in head
    args: dict = {}
    opts: dict = {}
    into = args if use else opts
    if r.peek() == ")":
        r.i += 1
    else:
        while True:
            if r.peek() == "":
                r.err("the formula is not closed: add )", opened, len(line))
            k = r.key()
            if k in into:
                r.err(f"{k} is given twice", r.i - len(k) - 1, r.i)
            into[k] = r.value(",;)")
            ch = r.peek()
            if ch == ",":
                r.i += 1
            elif ch == ";":
                if into is opts:
                    r.err("only one ; : the fragment's parameters, then the panel's own settings")
                r.i += 1
                into = opts
            elif ch == ")":
                r.i += 1
                break
            elif ch == "":
                r.err("the formula is not closed: add )", opened, len(line))
            else:
                r.err("a comma or ) is wanted here", r.i, len(line))
    if r.peek():
        r.err("nothing may follow the closing )", r.i, len(line))
    return {"head": head, "use": use, "args": args, "opts": opts, "at": base + lead}


def resolve(head: str, kit_refs: list | None) -> str:
    """``cashflow-ladder`` (no version) becomes the kit's ``cashflow-ladder@1``."""
    if "@" in head or not kit_refs:
        return head
    for ref in kit_refs:
        if ref.split("@")[0] in (head,) or ref.split("@")[0].split("/")[-1] == head:
            return ref
    return head


def apply_call(old: dict | None, call: dict, back=None) -> dict:
    """The panel a formula says, keeping what the formula does not show of the panel it replaces (its grid keys, columns, emits,
    children...). Key order is the old panel's, new keys after."""
    back = back or (lambda s: s)
    args, opts = _refs(call["args"], back), _refs(call["opts"], back)
    old = old if isinstance(old, dict) else {}
    if old and (old.get("use") or old.get("kind")) != call["head"]:     # another fragment or kind: only the cell's grid keys stay
        old = {k: v for k, v in old.items() if k in GRID_KEYS}
    o_args, o_opts = shown(old)
    new: dict = {}
    if call["use"]:
        w_old = old.get("with") if isinstance(old.get("with"), dict) else {}
        w = {}
        for k, v in w_old.items():
            if k in args:
                w[k] = args[k]
            elif k not in o_args:
                w[k] = v                                            # a nested parameter the formula leaves out
        w.update({k: v for k, v in args.items() if k not in w})
        body = {"use": call["head"], **({"with": w} if w or "with" in old else {})}
    else:
        body = {"kind": call["head"]}
    for k, v in old.items():                                        # the old panel's order
        if k in ("use", "with", "kind"):
            if k in body:
                new[k] = body[k]
            elif k == "with" and "use" in body and "with" in body:
                new[k] = body[k]
            continue
        if k in opts:
            new[k] = opts[k]
        elif k not in o_opts:
            new[k] = v                                              # grid keys and what the formula does not show
    for k, v in body.items():
        new.setdefault(k, v)
    for k, v in opts.items():
        new.setdefault(k, v)
    if call["use"]:
        new.pop("kind", None)
    else:
        new.pop("use", None)
        new.pop("with", None)
    return new


# ---- the grid ---------------------------------------------------------------------------------------------------------------------

def _layout(doc: dict) -> dict:
    return doc.get("layout") if isinstance(doc.get("layout"), dict) else {}


def matrix(doc: dict) -> list[list[str]]:
    return [str(r).split() for r in _layout(doc).get("areas") or [] if isinstance(r, str)]


def _names(m: list) -> list:
    return list(dict.fromkeys(c for row in m for c in row if c != "."))


def place_of(e: dict, names: list) -> str:
    """The area a panel is drawn in, as the server places it: its ``area:``, else main or right onto the picture."""
    a = e.get("area") if isinstance(e, dict) else None
    if a in names:
        return a
    if a == "right" and "right" not in names:
        return names[-1]
    return "main" if "main" in names else names[0]


def _rect(m: list, name: str) -> tuple[int, int, int, int]:
    cells = [(r, c) for r, row in enumerate(m) for c, x in enumerate(row) if x == name]
    rs, cs = [r for r, _ in cells], [c for _, c in cells]
    return min(rs), min(cs), max(rs), max(cs)


def grid(doc: dict) -> dict:
    """The screen as cells: ``mode`` areas (the picture) or flow (one column, a panel a row); each cell with its range, area, panels
    (their indexes) and formulas; the column widths."""
    m = matrix(doc)
    panels = doc["panels"]
    if m and all(len(row) == len(m[0]) for row in m):
        names = _names(m)
        by_area: dict = {n: [] for n in names}
        for i, e in enumerate(panels):
            if isinstance(e, dict):
                by_area[place_of(e, names)].append(i)
        cells, seen = [], set()
        for r, row in enumerate(m):
            for c, x in enumerate(row):
                if x == ".":
                    cells.append({"ref": cell_name(r, c), "range": cell_name(r, c), "r": r, "c": c, "rs": 1, "cs": 1, "area": "", "panels": []})
                elif x not in seen:
                    seen.add(x)
                    r0, c0, r1, c1 = _rect(m, x)
                    rng = cell_name(r0, c0) + (":" + cell_name(r1, c1) if (r1, c1) != (r0, c0) else "")
                    cells.append({"ref": cell_name(r0, c0), "range": rng, "r": r0, "c": c0, "rs": r1 - r0 + 1, "cs": c1 - c0 + 1,
                                  "area": x, "panels": by_area[x]})
        lay = _layout(doc)
        width = len(m[0])
        cols = lay.get("columns") if isinstance(lay.get("columns"), list) and len(lay.get("columns")) == width else None
        out = {"mode": "areas", "rows": len(m), "cols": width, "widths": cols or [round(12 / width, 2)] * width, "cells": cells}
    else:
        cells = [{"ref": cell_name(i, 0), "range": cell_name(i, 0), "r": i, "c": 0, "rs": 1, "cs": 1, "area": "", "panels": [i]}
                 for i in range(len(panels))]
        out = {"mode": "flow", "rows": len(panels), "cols": 1, "widths": [12], "cells": cells}
    names = cell_names(out, panels)
    for cell in out["cells"]:
        cell["formulas"] = [formula(panels[i], names) for i in cell["panels"]]
        cell["formula"] = "\n".join(cell["formulas"])
        cell["kept"] = [kept(panels[i]) for i in cell["panels"]]
        cell["ids"] = [panel_id(panels[i]) for i in cell["panels"]]
        cell["titles"] = [entry_title(panels[i]) for i in cell["panels"]]
        cell["grid"] = [{k: panels[i][k] for k in GRID_KEYS if k in panels[i] and k != "area"} for i in cell["panels"]]
        cell["children"] = [[panel_id(c) for g in panels[i].get("children") or [] if isinstance(g, dict) for c in g.get("panels") or []]
                            for i in cell["panels"]]
    return out


def cell_names(g: dict, panels: list) -> dict:
    """Panel id → the cell it is alone in (the ones a formula may name as ``@B2.``)."""
    out = {}
    for cell in g["cells"]:
        if len(cell["panels"]) == 1:
            pid = panel_id(panels[cell["panels"][0]])
            if pid:
                out[pid] = cell["ref"]
    return out


def reader(doc: dict, g: dict):
    """What reads a formula's ``@<cell>.`` back as ``@<panel>.`` on this grid."""
    return to_panels({v: k for k, v in cell_names(g, doc["panels"]).items()}, {panel_id(e) for e in doc["panels"]})


def rebuild(doc: dict, g: dict) -> dict:
    """The Sutra the cells say: the picture written back (each row as it was when its cells did not change) and each cell's
    formulas applied to its panels. ``grid(doc)`` then ``rebuild`` gives the same document."""
    out = copy.deepcopy(doc)
    panels = out["panels"]
    back = reader(doc, g)
    for cell in g["cells"]:
        calls = parse(cell.get("formula") or "")
        for i, call in zip(cell["panels"], calls):
            panels[i] = apply_call(panels[i], call, back)
    if g["mode"] == "areas":
        m = [["."] * g["cols"] for _ in range(g["rows"])]
        for cell in g["cells"]:
            if cell["area"]:
                for r in range(cell["r"], cell["r"] + cell["rs"]):
                    for c in range(cell["c"], cell["c"] + cell["cs"]):
                        m[r][c] = cell["area"]
        _write(out, m)
    return out


def _write(doc: dict, m: list) -> None:
    lay = doc.setdefault("layout", {})
    old = [str(r) for r in lay.get("areas") or []]
    lay["areas"] = [old[r] if r < len(old) and old[r].split() == row else " ".join(row) for r, row in enumerate(m)]


# ---- steps ------------------------------------------------------------------------------------------------------------------------

def _find(g: dict, ref: str) -> dict:
    r, c = cell_at(ref)
    for cell in g["cells"]:
        if cell["r"] <= r < cell["r"] + cell["rs"] and cell["c"] <= c < cell["c"] + cell["cs"]:
            return cell
    if g["mode"] == "flow" and c == 0 and r >= 0:
        return {"ref": cell_name(r, 0), "r": r, "c": 0, "rs": 1, "cs": 1, "area": "", "panels": []}
    raise DesignerError(f"there is no cell {ref} on this screen")


def _slug(base: str, taken: set) -> str:
    s = re.sub(r"[^a-z0-9]+", "-", str(base).lower()).strip("-")[:30] or "area"
    if not s[0].isalpha():
        s = "a-" + s
    out, n = s, 2
    while out in taken:
        out, n = f"{s}-{n}", n + 1
    return out


def set_formula(doc: dict, ref: str, text: str, kit_refs: list | None = None) -> dict:
    """A cell's formulas typed: each line one panel of the cell (a line more adds a panel, a line fewer removes one; an empty cell gets
    an area of its own). The document is changed only when every line reads."""
    g = grid(doc)
    cell = _find(g, ref)
    calls = parse(text)
    back = reader(doc, g)
    for call in calls:                                               # a kit fragment named without its version: the kit's
        if not call["use"] and kit_refs and any(r.split("@")[0].split("/")[-1] == call["head"] for r in kit_refs):
            call["use"] = True
            call["args"], call["opts"] = call["opts"], {}
        call["head"] = resolve(call["head"], kit_refs)
    olds = [doc["panels"][i] for i in cell["panels"]]
    news = [apply_call(olds[k] if k < len(olds) else None, call, back) for k, call in enumerate(calls)]
    area = cell["area"]
    if g["mode"] == "areas" and news and not area:                   # an empty cell: an area of its own
        m = matrix(doc)
        area = _slug(panel_id(news[0]) or news[0].get("use", news[0].get("kind", "area")).split("@")[0].split("/")[-1], set(_names(m)))
        m[cell["r"]][cell["c"]] = area
        _write(doc, m)
    if g["mode"] == "areas":
        for e in news[len(olds):]:
            e["area"] = area
    keep = sorted(cell["panels"])
    at = (keep[-1] + 1) if keep else (len(doc["panels"]) if g["mode"] == "areas" else min(cell["r"], len(doc["panels"])))
    for k, i in enumerate(keep):
        if k < len(news):
            doc["panels"][i] = news[k]
    extra = news[len(keep):]
    for k, e in enumerate(extra):
        doc["panels"].insert(at + k, e)
    for i in reversed(keep[len(news):]):
        doc["panels"].pop(i)
    if g["mode"] == "areas" and not news and area:                  # the cell emptied: its area goes from the picture
        _drop_area(doc, area)
    return doc


def _drop_area(doc: dict, area: str) -> None:
    lay = _layout(doc)
    for k in ("tabs", "phone"):
        if isinstance(lay.get(k), list) and area in lay[k]:
            lay[k] = [x for x in lay[k] if x != area]
            if not lay[k]:
                lay.pop(k)
    if lay.get("sticky") == area:
        lay.pop("sticky")
    if isinstance(lay.get("split"), dict) and area in (lay["split"].get("between") or []):
        lay.pop("split")
    _write(doc, [["." if x == area else x for x in row] for row in matrix(doc)])


def _need_areas(doc: dict) -> list:
    m = matrix(doc)
    if not m:
        raise DesignerError("this screen flows in one column: give it areas first (Make it a grid)")
    return m


def make_grid(doc: dict) -> dict:
    """A flowing screen laid out in areas: each panel a row of one column, its own area (the start of a spreadsheet)."""
    if matrix(doc):
        raise DesignerError("this screen is a grid already")
    lay = doc.setdefault("layout", {})
    if lay.get("sections"):
        raise DesignerError("this screen arranges its panels in sections: remove them in Outline first")
    taken: set = set()
    rows = []
    for e in doc["panels"]:
        if not isinstance(e, dict):
            continue
        name = _slug(panel_id(e) or (e.get("use") or e.get("kind") or "area").split("@")[0].split("/")[-1], taken)
        taken.add(name)
        e["area"] = name
        rows.append(name)
    lay.pop("columns", None)
    lay["areas"] = rows
    return doc


def merge(doc: dict, a: str, b: str) -> dict:
    """The range from cell ``a`` to ``b`` becomes one area: the first area in it (reading order), with the panels of every other."""
    m = _need_areas(doc)
    (r0, c0), (r1, c1) = cell_at(a), cell_at(b)
    r0, r1, c0, c1 = min(r0, r1), max(r0, r1), min(c0, c1), max(c0, c1)
    if r1 >= len(m) or c1 >= len(m[0]):
        raise DesignerError("that range runs off the grid")
    inside = list(dict.fromkeys(m[r][c] for r in range(r0, r1 + 1) for c in range(c0, c1 + 1) if m[r][c] != "."))
    for n in inside:
        q0, p0, q1, p1 = _rect(m, n)
        if q0 < r0 or p0 < c0 or q1 > r1 or p1 > c1:
            raise DesignerError(f"the range cuts the area of {cell_name(q0, p0)} in two: pick a range that covers it whole")
    if not inside:
        raise DesignerError("those cells are empty: type a formula in one first")
    keep = inside[0]
    for e in doc["panels"]:
        if isinstance(e, dict) and place_of(e, _names(m)) in inside[1:]:
            e["area"] = keep
    for n in inside[1:]:
        _drop_area(doc, n)
    m = matrix(doc)
    for r in range(r0, r1 + 1):
        for c in range(c0, c1 + 1):
            m[r][c] = keep
    _write(doc, m)
    return doc


def split(doc: dict, ref: str) -> dict:
    """A merged cell back to its first cell; the others empty."""
    m = _need_areas(doc)
    r, c = cell_at(ref)
    if r >= len(m) or c >= len(m[0]) or m[r][c] == ".":
        raise DesignerError("that cell is empty: there is nothing to split")
    name = m[r][c]
    r0, c0, r1, c1 = _rect(m, name)
    if (r0, c0) == (r1, c1):
        raise DesignerError("that cell is one cell already")
    for q in range(r0, r1 + 1):
        for p in range(c0, c1 + 1):
            if (q, p) != (r0, c0):
                m[q][p] = "."
    _write(doc, m)
    return doc


def _widths(n: int) -> list:
    return [12 // n + (1 if k < 12 % n else 0) for k in range(n)]


def insert(doc: dict, axis: str, at: int) -> dict:
    """An empty row or column before ``at`` (0-based); an area across the gap grows over it, so every area stays a rectangle."""
    m = _need_areas(doc)
    at = int(at)
    if axis == "row":
        if not 0 <= at <= len(m):
            raise DesignerError("there is no such row")
        new = [m[at - 1][c] if 0 < at < len(m) and m[at - 1][c] == m[at][c] else "." for c in range(len(m[0]))]
        m.insert(at, new)
    elif axis == "col":
        if not 0 <= at <= len(m[0]):
            raise DesignerError("there is no such column")
        if len(m[0]) >= 12:
            raise DesignerError("a screen has at most 12 columns")
        for row in m:
            row.insert(at, row[at - 1] if 0 < at < len(row) and row[at - 1] == row[at] else ".")
        lay = _layout(doc)
        if "columns" in lay:
            lay["columns"] = _widths(len(m[0]))
    else:
        raise DesignerError("insert a row or a column")
    _write(doc, m)
    return doc


def delete(doc: dict, axis: str, at: int) -> dict:
    """A row or column removed; an area only in it must be empty first (its panels moved or cleared)."""
    m = _need_areas(doc)
    at = int(at)
    size = len(m) if axis == "row" else len(m[0])
    if not 0 <= at < size:
        raise DesignerError(f"there is no such {'row' if axis == 'row' else 'column'}")
    if size == 1:
        raise DesignerError("a grid keeps at least one row and one column")
    rest = [row for r, row in enumerate(m) if r != at] if axis == "row" else [[x for c, x in enumerate(row) if c != at] for row in m]
    gone = set(_names(m)) - set(_names(rest))
    names = _names(m)
    busy = [n for n in gone if any(isinstance(e, dict) and place_of(e, names) == n for e in doc["panels"])]
    if busy:
        r0, c0, _, _ = _rect(m, busy[0])
        raise DesignerError(f"{cell_name(r0, c0)} is only in that {'row' if axis == 'row' else 'column'}: move or clear its panels first")
    for n in gone:
        _drop_area(doc, n)
    _write(doc, rest)
    if axis == "col" and "columns" in _layout(doc):
        _layout(doc)["columns"] = _widths(len(rest[0]))
    return doc


def move(doc: dict, src: str, dst: str) -> dict:
    """A cell dragged onto another: their panels change places (onto an empty cell, the panels move into an area of their own there;
    in a one-column screen, the panel moves to that row)."""
    g = grid(doc)
    a, b = _find(g, src), _find(g, dst)
    if a is b or a["ref"] == b["ref"]:
        return doc
    if g["mode"] == "flow":
        if not a["panels"]:
            raise DesignerError("that row is empty")
        e = doc["panels"].pop(a["panels"][0])
        doc["panels"].insert(min(b["r"], len(doc["panels"])), e)
        return doc
    if not a["panels"]:
        raise DesignerError("that cell is empty: there is nothing to move")
    if not b["area"]:                                               # onto an empty cell: the area moves there, one cell
        m = [["." if x == a["area"] else x for x in row] for row in matrix(doc)]
        m[b["r"]][b["c"]] = a["area"]
        _write(doc, m)
        return doc
    target = b["area"]
    for i in a["panels"]:
        doc["panels"][i]["area"] = target
    for i in b["panels"]:
        doc["panels"][i]["area"] = a["area"]
    return doc


# ---- what the formula bar offers --------------------------------------------------------------------------------------------------

def suggestions(doc: dict, g: dict, kit: list) -> dict:
    """Autocomplete: the kit's fragments (with their one-line docs and parameters), the panels on the page by cell, and what each emits
    (the events and their payload fields: ``@A2.open.trade``)."""
    frags = []
    for f in kit or []:
        d = f.get("doc") or {}
        frags.append({"ref": f.get("ref", ""), "summary": d.get("summary") or f.get("summary") or "",
                      "params": list((f.get("params") or {}).keys()) or list((d.get("params") or {}).keys())})
    names = cell_names(g, doc["panels"])
    docs = {f.get("ref", ""): (f.get("doc") or {}) for f in kit or []}
    docs.update({k.split("@")[0]: v for k, v in list(docs.items())})
    panels = []
    for i, e in enumerate(doc["panels"]):
        if not isinstance(e, dict):
            continue
        pid = panel_id(e)
        emits = e.get("emits") or []
        if e.get("use") and not emits:                               # a fragment's events: its doc says them (event: renames one)
            d = docs.get(str(e["use"])) or docs.get(str(e["use"]).split("@")[0]) or {}
            w = e.get("with") if isinstance(e.get("with"), dict) else {}
            emits = [{**x, "name": w.get("event") or x.get("name")} if isinstance(x, dict) and len(d.get("emits") or []) == 1 else x
                     for x in d.get("emits") or []]
        ev = [{"name": x.get("name"), "fields": list((x.get("payload") or {}).keys())} for x in emits if isinstance(x, dict)]
        panels.append({"index": i, "id": pid, "cell": names.get(pid, ""), "title": entry_title(e), "emits": ev,
                       "table": e.get("kind") == "table" and not e.get("use")})
    return {"fragments": frags, "panels": panels,
            "kinds": ["table", "kv", "metric", "line", "area", "hbar", "ladder", "markdown", "timeline", "status", "pivot", "tabs"]}
