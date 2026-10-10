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

"""Synthetic documents from a schema: deterministic (no randomness, so a pack is reproducible), valid against the schema's types, enums,
ranges and formats, with keys and links that agree across the kinds of a batch so the preview's links resolve."""
from __future__ import annotations

import datetime
import hashlib
import re
import uuid

from .plan import KindPlan, Plan, SutraPlan, humanize, norm
from .resolve import Node

BASE_DATE = datetime.date(2026, 1, 5)
TENORS = ["1M", "3M", "6M", "1Y", "2Y", "5Y", "10Y"]
WORDS = ["Alpha", "Bravo", "Cedar", "Delta", "Ember", "Falcon", "Granite", "Harbor"]


def key_value(kp: KindPlan, i: int) -> str:
    """The i-th synthetic key of a kind (0-based): MNEMONIC-0001."""
    return f"{kp.mnemonic or kp.kind[:3].upper()}-{i + 1:04d}"


def _pattern_value(pattern: str, i: int) -> str | None:
    """A value for the simple patterns people write (^[A-Z]{3}$, ^\\d{4}$, ^TRD-\\d+$); None when the pattern is beyond this."""
    p = pattern.strip("^$")
    out, pos = "", 0
    for m in re.finditer(r"\[([^\]]+)\](?:\{(\d+)(?:,(\d+))?\})?|\\d(?:\{(\d+)(?:,(\d+))?\}|\+)?|([A-Za-z0-9_-]+)", p):
        if m.start() != pos:
            return None
        pos = m.end()
        if m.group(6):
            out += m.group(6)
        elif m.group(1):
            cls = m.group(1)
            rng = re.match(r"([A-Za-z0-9])-([A-Za-z0-9])", cls)
            chars = [chr(c) for c in range(ord(rng.group(1)), ord(rng.group(2)) + 1)] if rng else list(cls)
            n = int(m.group(2) or 1)
            out += "".join(chars[(i + j) % len(chars)] for j in range(n))
        else:
            n = int(m.group(4) or 4) if m.group(4) else 4
            out += str((i + 1) * 7919).zfill(n)[-n:]
    return out if pos == len(p) and out else None


class Synth:
    def __init__(self, plan: Plan):
        self.plan = plan
        self.kinds = {k.kind: k for k in plan.kinds}

    # ---- scalars ----
    def string(self, n: Node, name: str, i: int, kp: KindPlan, path: str, link: str | None) -> str:
        if link and link in self.kinds:
            t = self.kinds[link]
            if t.samples and t.key:
                vals = [str(d.get(t.key)) for d in t.samples if d.get(t.key) is not None]
                if vals:
                    return vals[i % len(vals)]
            return key_value(t, i % max(1, int(self.plan.settings.get("samples", 5))))
        if n.examples and isinstance(n.examples[0], str):
            return n.examples[i % len(n.examples)]
        f = n.format
        if f == "date":
            return (BASE_DATE + datetime.timedelta(days=i)).isoformat()
        if f == "date-time":
            return datetime.datetime(2026, 1, 5, 9, 30, tzinfo=datetime.timezone.utc).replace(day=5 + i % 20).isoformat().replace("+00:00", "Z")
        if f == "time":
            return f"{9 + i % 8:02d}:30:00"
        if f == "uuid":
            return str(uuid.UUID(hashlib.md5(f"{kp.kind}{path}{i}".encode()).hexdigest()))
        if f in ("email", "idn-email"):
            return f"user{i + 1}@example.com"
        if f in ("uri", "url", "iri"):
            return f"https://example.com/{kp.kind}/{i + 1}"
        if f == "ipv4":
            return f"10.0.0.{i + 1}"
        if f == "hostname":
            return f"host{i + 1}.example.com"
        if n.pattern:
            v = _pattern_value(n.pattern, i)
            if v:
                return v
        low = norm(name)
        if kp.key and path == kp.key:
            return key_value(kp, i)
        if low.endswith("currency") or low == "ccy":
            return ["USD", "EUR", "GBP", "JPY"][i % 4]
        if low.endswith("country"):
            return ["US", "GB", "DE", "JP"][i % 4]
        if low == "name" or low.endswith("name") or low == "title":
            return f"{WORDS[i % len(WORDS)]} {humanize(kp.kind)}"
        if low in ("description", "notes", "note", "comment", "comments", "text"):
            return f"{humanize(name)} for sample {i + 1}."
        if re.search(r"(id|key|code|ref)$", low):
            return f"{(low[:3] or 'x').upper()}-{i + 1:04d}"
        if n.min_length:
            return (humanize(name) + " " + str(i + 1)).ljust(n.min_length, "x")
        return f"{humanize(name)} {i + 1}"

    def number(self, n: Node, name: str, i: int, integer: bool):
        low = norm(name)
        lo, hi = n.minimum, n.maximum
        if lo is not None and hi is not None and hi > lo:
            span = hi - lo
            v = lo + span * (0.15 + 0.2 * ((i * 3 + 1) % 4))
            return int(round(v)) if integer else round(v, 4 if span <= 1 else 2)
        if integer:
            v = (i + 1) * 3 + 7
            if lo is not None:
                v = max(v, int(lo))
            if hi is not None:
                v = min(v, int(hi))
            return v
        if re.search(r"(rate|yield|ratio|pct|percent|coupon|spread)", low):
            v = 0.02 + 0.005 * (i % 5) if not low.endswith("spread") else 12.5 + 2.5 * i
        elif re.search(r"(notional|amount|limit|exposure|balance|principal|value)", low):
            v = 1_000_000.0 * (i + 1)
        elif re.search(r"(mtm|pnl|pl|delta|change|diff|gain|loss)", low):
            v = (-1) ** i * (12_345.67 + 1_111.11 * i)
        elif re.search(r"(price|px|quote)", low):
            v = 99.5 + 0.25 * i
        else:
            v = 100.0 * (i + 2) + 3.7 * i
        if lo is not None:
            v = max(v, lo)
        if hi is not None:
            v = min(v, hi)
        return round(v, 4 if abs(v) < 1 else 2)

    # ---- trees ----
    def value(self, n: Node, name: str, i: int, kp: KindPlan, path: str, links: dict, depth: int = 0, force: dict | None = None):
        if n.has_const:
            return n.const
        if n.enum:
            return n.enum[i % len(n.enum)]
        if n.examples and not isinstance(n.examples[0], (dict, list)) and n.type != "string":
            return n.examples[i % len(n.examples)]
        t = n.type
        if t == "array":
            it = n.items or Node(type="string")
            count = 2 + (i % 2) if (it.props or it.type == "object") else 3
            lk = links.get(path)
            if lk and not it.props:
                return [self.string(it, name, i + j, kp, path + "[]", lk) for j in range(count)]
            return [self.value(it, name, i + j, kp, path + "[]", links, depth + 1) for j in range(count)]
        if t == "object" or n.props:
            if n.props:
                return {k: self.value(c, k, i, kp, f"{path}.{k}" if path else k, links, depth + 1, force) for k, c in n.props.items()
                        if depth < 8}
            if n.additional is not None:
                keys = n.key_examples or (TENORS if n.additional.type in ("number", "integer") else [f"key{j + 1}" for j in range(3)])
                out = {}
                for j, k in enumerate(keys):
                    c = n.additional
                    if c.type in ("number", "integer"):
                        out[k] = round(0.99 - 0.012 * j - 0.002 * (i % 3), 4) if not c.minimum and not c.maximum else self.number(c, name, i + j, c.type == "integer")
                    else:
                        out[k] = self.value(c, name, i + j, kp, f"{path}.{k}", links, depth + 1)
                return out
            return {}
        if t == "integer":
            return self.number(n, name, i, True)
        if t == "number":
            return self.number(n, name, i, False)
        if t == "boolean":
            return i % 2 == 0
        return self.string(n, name, i, kp, path, links.get(path))

    def document(self, kp: KindPlan, i: int, sp: SutraPlan | None = None) -> dict:
        links = {l.field: l.to for l in kp.links}
        top = kp.node
        if sp is not None and sp.variant is not None:
            top = _variant_node(kp.node, sp.variant)
        doc = {k: self.value(c, k, i, kp, k, links) for k, c in top.props.items()}
        if sp is not None:
            for f, v in sp.values.items():
                doc[f] = v
        if kp.key and kp.key in doc:
            doc[kp.key] = key_value(kp, i)
        return doc


def _variant_node(parent: Node, variant: Node) -> Node:
    eff = Node(type="object")
    for k in parent.props:
        if k in parent.base_props:
            eff.props[k] = parent.props[k]
    for k, v in variant.props.items():
        eff.props[k] = v
    eff.required = set(parent.base_required) | set(variant.required)
    return eff


def documents(kp: KindPlan, plan: Plan, n: int = 5, sp: SutraPlan | None = None, offset: int = 0) -> list[dict]:
    """``n`` synthetic documents of the kind (of one Sutra when ``sp`` is given: its match values and variant applied)."""
    s = Synth(plan)
    return [s.document(kp, offset + j, sp) for j in range(n)]


def examples_of(kp: KindPlan, sp: SutraPlan | None = None) -> list[dict]:
    """Whole-document ``examples`` from the schema, the ones that belong to this Sutra (they carry its match values)."""
    n = kp.node
    out = [e for e in (n.examples or []) if isinstance(e, dict)]
    if sp is not None and sp.values:
        out = [e for e in out if all(e.get(f) == v for f, v in sp.values.items())]
    return out
