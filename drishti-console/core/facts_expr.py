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

"""The small, safe expression language of the Home page's facts (config/packs/<pack>/config/facts.yaml; core/facts.py).

An expression is Python's expression syntax cut down to reading data: names bound by the fact's steps, ``a.b`` and ``a['b']`` (a key
of an answer; a missing key is ``None``), ``a[0]``, numbers, strings, ``and or not``, comparisons, ``+ - * / // %``, ``x if c else y``,
lists and one-``for`` comprehensions (``[n for n in tree.nodes if n.limitUse > 1]``), and the functions in :data:`FUNCTIONS`. Nothing
else parses: no attribute of a Python object, no imports, no lambdas, no keyword arguments, no ``**``. ``None`` is forgiving: arithmetic
with it is ``None``, a comparison with it is false, a division by zero is ``None``. So a fact whose data is missing simply does not hold.

A template is text with ``{expression}`` or ``{expression|format}`` in it (``{{`` and ``}}`` are braces); :func:`fmt` lists the formats.
"""
from __future__ import annotations

import ast
import math
import operator
import re
from typing import Any

MAX_LENGTH = 2000              # an expression longer than this is refused (a config typo, not a program)


class ExprError(ValueError):
    """An expression or template that does not parse, or uses something the language does not have."""


def _num(x: Any) -> bool:
    return isinstance(x, (int, float)) and not isinstance(x, bool) and not (isinstance(x, float) and math.isnan(x))


def _items(xs: Any) -> list:
    return list(xs) if isinstance(xs, (list, tuple)) else []


def _get(obj: Any, key: Any) -> Any:
    if isinstance(obj, dict):
        return obj.get(key)
    if isinstance(obj, (list, tuple, str)) and isinstance(key, int) and not isinstance(key, bool):
        return obj[key] if -len(obj) <= key < len(obj) else None
    return None


def _path(obj: Any, dotted: str) -> Any:
    for part in str(dotted).split("."):
        obj = _get(obj, part)
    return obj


def f_find(xs: Any, key: str, value: Any) -> Any:
    """The first element of ``xs`` whose ``key`` (a dotted path) equals ``value``, else None."""
    return next((x for x in _items(xs) if _path(x, key) == value and value is not None), None)


def f_pluck(xs: Any, key: str) -> list:
    """``key`` (a dotted path) of every element."""
    return [_path(x, key) for x in _items(xs)]


def f_sum(xs: Any) -> Any:
    vals = [x for x in _items(xs) if _num(x)]
    return sum(vals) if vals else (0 if isinstance(xs, (list, tuple)) else None)


def f_min(*xs: Any) -> Any:
    vals = [x for x in (_items(xs[0]) if len(xs) == 1 else xs) if _num(x)]
    return min(vals) if vals else None


def f_max(*xs: Any) -> Any:
    vals = [x for x in (_items(xs[0]) if len(xs) == 1 else xs) if _num(x)]
    return max(vals) if vals else None


def f_len(x: Any) -> Any:
    return len(x) if isinstance(x, (list, tuple, dict, str)) else 0


def f_abs(x: Any) -> Any:
    return abs(x) if _num(x) else None


def f_round(x: Any, digits: int = 0) -> Any:
    return round(x, int(digits)) if _num(x) else None


def f_first(xs: Any) -> Any:
    items = _items(xs)
    return items[0] if items else None


def f_top(xs: Any, key: str, n: int = 1) -> list:
    """The ``n`` elements with the largest ``key`` (dotted path; elements without a number last)."""
    items = [x for x in _items(xs) if _num(_path(x, key))]
    return sorted(items, key=lambda x: _path(x, key), reverse=True)[: max(0, int(n))]


def f_ratio(a: Any, b: Any) -> Any:
    return a / b if _num(a) and _num(b) and b != 0 else None


def f_overlap(a: Any, b: Any, key: str, weight: str) -> Any:
    """Fund overlap: for each ``key`` both lists hold, the smaller of its two ``weight`` totals (a key's lines added up), summed."""
    def totals(xs):
        out: dict = {}
        for x in _items(xs):
            k, w = _path(x, key), _path(x, weight)
            if k not in (None, "") and _num(w):
                out[k] = out.get(k, 0) + w
        return out
    ta, tb = totals(a), totals(b)
    if not ta or not tb:
        return None
    return sum(min(w, tb[k]) for k, w in ta.items() if k in tb)


def f_common(a: Any, b: Any, key: str) -> int:
    """How many distinct ``key`` values the two lists share."""
    ka = {_path(x, key) for x in _items(a)} - {None, ""}
    kb = {_path(x, key) for x in _items(b)} - {None, ""}
    return len(ka & kb)


def f_pairs(xs: Any) -> list:
    """Every unordered pair of the list's elements, as ``[a, b]`` (at most 5,000 pairs)."""
    items = _items(xs)
    out = []
    for i in range(len(items)):
        for j in range(i + 1, len(items)):
            out.append([items[i], items[j]])
            if len(out) >= 5000:
                return out
    return out


def f_flat(xs: Any) -> list:
    """A list of lists made one list (``flat(pluck(tree.groups, 'children'))``: every counterparty of every group)."""
    out: list = []
    for x in _items(xs):
        out.extend(_items(x))
    return out


def f_index(xs: Any, value: Any) -> Any:
    items = _items(xs)
    return items.index(value) if value in items else None


def f_upper(x: Any) -> Any:
    return x.upper() if isinstance(x, str) else x


FUNCTIONS = {"len": f_len, "sum": f_sum, "min": f_min, "max": f_max, "abs": f_abs, "round": f_round, "first": f_first,
             "find": f_find, "pluck": f_pluck, "top": f_top, "ratio": f_ratio, "overlap": f_overlap, "common": f_common,
             "pairs": f_pairs, "flat": f_flat, "index": f_index, "upper": f_upper}

_BIN = {ast.Add: operator.add, ast.Sub: operator.sub, ast.Mult: operator.mul, ast.Div: operator.truediv,
        ast.FloorDiv: operator.floordiv, ast.Mod: operator.mod}
_CMP = {ast.Eq: operator.eq, ast.NotEq: operator.ne, ast.Lt: operator.lt, ast.LtE: operator.le, ast.Gt: operator.gt,
        ast.GtE: operator.ge}
_ALLOWED = (ast.Expression, ast.BoolOp, ast.And, ast.Or, ast.BinOp, ast.UnaryOp, ast.Not, ast.USub, ast.UAdd, ast.Compare,
            ast.IfExp, ast.Call, ast.Name, ast.Load, ast.Store, ast.Constant, ast.Attribute, ast.Subscript, ast.List, ast.Tuple,
            ast.ListComp, ast.comprehension, ast.In, ast.NotIn, *_BIN, *_CMP)


class Expr:
    """One compiled expression. ``Expr(text).eval(names)``."""

    def __init__(self, text: str):
        self.text = str(text).strip()
        if not self.text:
            raise ExprError("an empty expression")
        if len(self.text) > MAX_LENGTH:
            raise ExprError(f"an expression longer than {MAX_LENGTH} characters")
        try:
            tree = ast.parse(self.text, mode="eval")
        except SyntaxError as e:
            raise ExprError(f"cannot read {self.text!r}: {e.msg}") from None
        for node in ast.walk(tree):
            if not isinstance(node, _ALLOWED):
                raise ExprError(f"{self.text!r}: {type(node).__name__} is not part of the fact language")
            if isinstance(node, ast.Call):
                if not isinstance(node.func, ast.Name) or node.func.id not in FUNCTIONS:
                    raise ExprError(f"{self.text!r}: only these functions: {', '.join(sorted(FUNCTIONS))}")
                if node.keywords:
                    raise ExprError(f"{self.text!r}: functions take no keyword arguments")
            if isinstance(node, ast.ListComp) and (len(node.generators) != 1 or not isinstance(node.generators[0].target, ast.Name)
                                                   or node.generators[0].is_async):
                raise ExprError(f"{self.text!r}: a comprehension has one 'for <name> in'")
            if isinstance(node, ast.Constant) and not isinstance(node.value, (str, int, float, bool, type(None))):
                raise ExprError(f"{self.text!r}: constants are numbers, strings, True, False and None")
            if isinstance(node, ast.Attribute) and node.attr.startswith("_"):
                raise ExprError(f"{self.text!r}: a key cannot start with '_'")
        self.tree = tree.body

    def names(self) -> set[str]:
        """The free names the expression reads (for the config check: each must be bound by an earlier step)."""
        bound = {g.target.id for n in ast.walk(self.tree) if isinstance(n, ast.ListComp) for g in n.generators}
        return {n.id for n in ast.walk(self.tree) if isinstance(n, ast.Name) and isinstance(n.ctx, ast.Load)} - bound - set(FUNCTIONS) \
            - {"True", "False", "None"}

    def eval(self, names: dict) -> Any:
        return _eval(self.tree, names)

    def __repr__(self) -> str:
        return f"Expr({self.text!r})"


def _eval(node: ast.AST, env: dict) -> Any:  # noqa: C901 - one small interpreter
    if isinstance(node, ast.Constant):
        return node.value
    if isinstance(node, ast.Name):
        return env.get(node.id)
    if isinstance(node, ast.Attribute):
        return _get(_eval(node.value, env), node.attr)
    if isinstance(node, ast.Subscript):
        return _get(_eval(node.value, env), _eval(node.slice, env))
    if isinstance(node, ast.BoolOp):
        if isinstance(node.op, ast.And):
            out: Any = True
            for v in node.values:
                out = _eval(v, env)
                if not out:
                    return out
            return out
        out = False
        for v in node.values:
            out = _eval(v, env)
            if out:
                return out
        return out
    if isinstance(node, ast.UnaryOp):
        v = _eval(node.operand, env)
        if isinstance(node.op, ast.Not):
            return not v
        if not _num(v):
            return None
        return -v if isinstance(node.op, ast.USub) else v
    if isinstance(node, ast.BinOp):
        a, b = _eval(node.left, env), _eval(node.right, env)
        if isinstance(node.op, ast.Add) and isinstance(a, str) and isinstance(b, str):
            return a + b
        if isinstance(node.op, ast.Add) and isinstance(a, list) and isinstance(b, list):
            return a + b
        if not (_num(a) and _num(b)):
            return None
        if isinstance(node.op, (ast.Div, ast.FloorDiv, ast.Mod)) and b == 0:
            return None
        return _BIN[type(node.op)](a, b)
    if isinstance(node, ast.Compare):
        left = _eval(node.left, env)
        for op, right_node in zip(node.ops, node.comparators):
            right = _eval(right_node, env)
            if isinstance(op, (ast.In, ast.NotIn)):
                inside = isinstance(right, (list, tuple, dict, str)) and left is not None and (
                    left in right if not isinstance(right, str) or isinstance(left, str) else False)
                ok = inside if isinstance(op, ast.In) else not inside
            elif isinstance(op, (ast.Eq, ast.NotEq)):
                ok = _CMP[type(op)](left, right)
            else:
                comparable = (_num(left) and _num(right)) or (isinstance(left, str) and isinstance(right, str))
                ok = comparable and _CMP[type(op)](left, right)
            if not ok:
                return False
            left = right
        return True
    if isinstance(node, ast.IfExp):
        return _eval(node.body, env) if _eval(node.test, env) else _eval(node.orelse, env)
    if isinstance(node, ast.Call):
        args = [_eval(a, env) for a in node.args]
        try:
            return FUNCTIONS[node.func.id](*args)
        except (TypeError, ValueError):
            return None
    if isinstance(node, (ast.List, ast.Tuple)):
        return [_eval(e, env) for e in node.elts]
    if isinstance(node, ast.ListComp):
        gen = node.generators[0]
        out = []
        for item in _items(_eval(gen.iter, env)):
            inner = {**env, gen.target.id: item}
            if all(_eval(cond, inner) for cond in gen.ifs):
                out.append(_eval(node.elt, inner))
        return out
    raise ExprError(f"{type(node).__name__} is not part of the fact language")


# ---------------------------------------------------------------------------------------------------------------------- templates

_FIELD = re.compile(r"\{\{|\}\}|\{([^{}]+)\}")


class Template:
    """Text with ``{expression}`` and ``{expression|format}`` in it, compiled once."""

    def __init__(self, text: str):
        self.text = str(text or "")
        self.parts: list = []                  # str, or (Expr, format)
        pos = 0
        for m in _FIELD.finditer(self.text):
            self.parts.append(self.text[pos:m.start()])
            if m.group(0) in ("{{", "}}"):
                self.parts.append(m.group(0)[0])
            else:
                expr, _, form = m.group(1).partition("|")          # the language has no '|', so the first one starts the format
                form = form.strip()
                if form and form not in FORMATS:
                    raise ExprError(f"{self.text!r}: unknown format {form!r} (one of {', '.join(sorted(FORMATS))})")
                self.parts.append((Expr(expr), form))
            pos = m.end()
        self.parts.append(self.text[pos:])

    def names(self) -> set[str]:
        out: set[str] = set()
        for p in self.parts:
            if isinstance(p, tuple):
                out |= p[0].names()
        return out

    def render(self, env: dict) -> str | None:
        """The filled text, or None when a field is missing (a sentence with a hole in it is never shown)."""
        out = []
        for p in self.parts:
            if isinstance(p, str):
                out.append(p)
                continue
            value = p[0].eval(env)
            if value is None or value == "":
                return None
            out.append(fmt(value, p[1]))
        return "".join(out)


def _compact(x: float) -> str:
    a = abs(x)
    for limit, suffix in ((1e9, "bn"), (1e6, "m"), (1e3, "k")):
        if a >= limit:
            return f"{x / limit:,.1f}{suffix}"
    return f"{x:,.0f}"


def fmt(value: Any, form: str = "") -> str:
    """One value as text. Formats: ``money`` (compact: 3.5m, 1,043.7bn), ``int`` (1,234), ``num1`` / ``num2`` (decimals),
    ``pct`` / ``pct1`` / ``pct2`` (a ratio as a percent: 2.23 is 223%), ``pctv`` / ``pctv1`` / ``pctv2`` (a value already in percent: 21.19%),
    ``signpct`` (a ratio as a signed percent: +452%), ``times`` (a ratio as 17.8×)."""
    if not form or not _num(value):
        if isinstance(value, float) and _num(value):
            return f"{value:,.2f}"
        return str(value)
    return FORMATS[form](value)


FORMATS = {
    "money": _compact,
    "int": lambda x: f"{x:,.0f}",
    "num1": lambda x: f"{x:,.1f}",
    "num2": lambda x: f"{x:,.2f}",
    "pct": lambda x: f"{x * 100:,.0f}%",
    "pct1": lambda x: f"{x * 100:,.1f}%",
    "pct2": lambda x: f"{x * 100:,.2f}%",
    "pctv": lambda x: f"{x:,.0f}%",
    "pctv1": lambda x: f"{x:,.1f}%",
    "pctv2": lambda x: f"{x:,.2f}%",
    "signpct": lambda x: f"{x * 100:+,.0f}%",
    "times": lambda x: f"{x:,.1f}×",
}
