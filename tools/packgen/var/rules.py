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

"""The condition of a RULE portfolio (VAR_DATA_MODEL.md section 4.2): the small part of DEL the server's engine evaluates over a
position's columns (com.ash.drishti.compute.var.PositionRule), so a rule's members are the same here and there.

    PRODUCT_GROUP == 'Rates' and CURRENCY in ('USD', 'CAD')
    'hedge' in TAGS or not DIRECTION == 'LONG'
    MARKET_VALUE > 1000000

Comparisons ``== != < <= > >=`` of a field with a quoted text or a number, ``FIELD in ('a', 'b')``, ``'x' in FIELD`` (a list field holds
it), ``and``, ``or``, ``not`` and parentheses. A missing field compares unequal to everything."""
from __future__ import annotations

import re

TOKEN = re.compile(r"\s*(?:(\d+(?:\.\d+)?)|'((?:[^']|'')*)'|(==|!=|<=|>=|<|>|\(|\)|,)|([A-Za-z_][A-Za-z0-9_.]*))")


class RuleError(ValueError):
    pass


def tokens(text: str) -> list[tuple[str, object]]:
    out, i = [], 0
    text = text.strip()
    while i < len(text):
        m = TOKEN.match(text, i)
        if not m or m.end() == i:
            raise RuleError(f"cannot read the rule at: {text[i:i + 20]!r}")
        num, s, op, word = m.groups()
        if num is not None:
            out.append(("lit", float(num)))
        elif s is not None:
            out.append(("lit", s.replace("''", "'")))
        elif op is not None:
            out.append(("op", op))
        elif word.lower() in ("and", "or", "not", "in"):
            out.append(("kw", word.lower()))
        else:
            out.append(("field", word))
        i = m.end()
    return out


class Rule:
    def __init__(self, text: str):
        self.text = text
        self.t = tokens(text)
        self.i = 0
        self.tree = self._or()
        if self.i != len(self.t):
            raise RuleError(f"unexpected {self.t[self.i][1]!r} in the rule")

    def _peek(self, kind=None, value=None):
        if self.i < len(self.t):
            k, v = self.t[self.i]
            if (kind is None or k == kind) and (value is None or v == value):
                return self.t[self.i]
        return None

    def _take(self, kind, value=None):
        tok = self._peek(kind, value)
        if not tok:
            raise RuleError(f"expected {value or kind} in the rule")
        self.i += 1
        return tok[1]

    def _or(self):
        node = self._and()
        while self._peek("kw", "or"):
            self.i += 1
            node = ("or", node, self._and())
        return node

    def _and(self):
        node = self._not()
        while self._peek("kw", "and"):
            self.i += 1
            node = ("and", node, self._not())
        return node

    def _not(self):
        if self._peek("kw", "not"):
            self.i += 1
            return ("not", self._not())
        if self._peek("op", "("):
            self.i += 1
            node = self._or()
            self._take("op", ")")
            return node
        return self._cmp()

    def _cmp(self):
        if self._peek("lit"):
            lit = self._take("lit")
            self._take("kw", "in")
            return ("has", self._take("field"), lit)
        field = self._take("field")
        if self._peek("kw", "in"):
            self.i += 1
            self._take("op", "(")
            vals = [self._take("lit")]
            while self._peek("op", ","):
                self.i += 1
                vals.append(self._take("lit"))
            self._take("op", ")")
            return ("in", field, vals)
        op = self._take("op")
        if op not in ("==", "!=", "<", "<=", ">", ">="):
            raise RuleError(f"{op!r} is not a comparison")
        return ("cmp", op, field, self._take("lit"))

    def matches(self, doc: dict) -> bool:
        return self._eval(self.tree, doc)

    def _eval(self, n, doc) -> bool:
        tag = n[0]
        if tag == "or":
            return self._eval(n[1], doc) or self._eval(n[2], doc)
        if tag == "and":
            return self._eval(n[1], doc) and self._eval(n[2], doc)
        if tag == "not":
            return not self._eval(n[1], doc)
        if tag == "has":
            v = doc.get(n[1])
            return isinstance(v, list) and n[2] in v
        if tag == "in":
            return _norm(doc.get(n[1])) in [_norm(x) for x in n[2]]
        op, field, lit = n[1], n[2], n[3]
        v = doc.get(field)
        if v is None:
            return op == "!="
        if isinstance(lit, float):
            try:
                x = float(v)
            except (TypeError, ValueError):
                return op == "!="
            return {"==": x == lit, "!=": x != lit, "<": x < lit, "<=": x <= lit, ">": x > lit, ">=": x >= lit}[op]
        s = str(v)
        return {"==": s == lit, "!=": s != lit, "<": s < lit, "<=": s <= lit, ">": s > lit, ">=": s >= lit}[op]


def _norm(v):
    if isinstance(v, (int, float)) and not isinstance(v, bool):
        return float(v)
    return v
