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

"""Reads JSON Schema (draft 2020-12 and 07) into a small normalised tree: $ref/$defs followed, allOf merged, oneOf/anyOf kept as variants."""
from __future__ import annotations

import json
import pathlib
import re
from dataclasses import dataclass, field

MAX_DEPTH = 14
ANN_PREFIX = "x-drishti-"


class SchemaError(Exception):
    """A schema that cannot be read; the message says which file and what is wrong."""


@dataclass
class Node:
    """One schema position, normalised. ``props`` keep the schema's order; ``variants`` are the branches of a oneOf/anyOf of objects."""
    type: str = ""                                   # string integer number boolean object array null "" (unknown)
    format: str = ""
    title: str = ""
    description: str = ""
    enum: list = field(default_factory=list)
    const: object = None
    has_const: bool = False
    examples: list = field(default_factory=list)
    default: object = None
    minimum: float | None = None
    maximum: float | None = None
    min_length: int | None = None
    pattern: str = ""
    nullable: bool = False
    props: dict = field(default_factory=dict)        # name -> Node
    required: set = field(default_factory=set)
    items: "Node | None" = None
    additional: "Node | None" = None                 # a map: additionalProperties / patternProperties value
    key_examples: list = field(default_factory=list) # the map's keys, from propertyNames.enum or x-drishti-keys
    variants: list = field(default_factory=list)     # [(label, Node)] of a oneOf/anyOf of objects
    discriminator: str = ""                          # the property that tells the variants apart
    ann: dict = field(default_factory=dict)          # x-drishti-* (prefix removed), and x-drishti: {role: ..} flattened
    ref_name: str = ""                               # the $defs name or file stem this node was read from
    base_props: set = field(default_factory=set)     # with variants: the properties outside the branches
    base_required: set = field(default_factory=set)

    @property
    def scalar(self) -> bool:
        return self.type in ("string", "integer", "number", "boolean")


@dataclass
class SchemaDoc:
    name: str                                        # file name as given
    data: dict
    stem: str = ""
    ident: str = ""

    def __post_init__(self):
        base = pathlib.PurePosixPath(self.name.replace("\\", "/")).name
        self.stem = re.sub(r"(\.schema)?\.(json|ya?ml)$", "", base, flags=re.I)
        self.ident = str(self.data.get("$id") or "")


def parse_text(text: str, name: str) -> dict:
    """JSON or YAML text -> dict; SchemaError (naming the file) when it is neither or not an object."""
    try:
        data = json.loads(text)
    except ValueError as e:
        if name.lower().endswith((".yaml", ".yml")) or not text.lstrip().startswith(("{", "[")):
            try:
                import yaml
                data = yaml.safe_load(text)
            except Exception as ye:                  # noqa: BLE001 - any YAML error is a bad file
                raise SchemaError(f"{name}: not JSON or YAML ({ye})") from ye
        else:
            raise SchemaError(f"{name}: not valid JSON ({e})") from e
    if not isinstance(data, dict):
        raise SchemaError(f"{name}: a JSON Schema is an object, this is a {type(data).__name__}")
    return data


def _types(s: dict) -> tuple[str, bool]:
    t = s.get("type")
    if isinstance(t, list):
        non_null = [x for x in t if x != "null"]
        return (non_null[0] if non_null else "null"), len(non_null) != len(t)
    return (t or ""), False


def _flat_ann(s: dict) -> dict:
    out = {}
    blob = s.get("x-drishti")
    if isinstance(blob, dict):
        out.update({k: v for k, v in blob.items() if k != "reason"})
    for k, v in s.items():
        if isinstance(k, str) and k.startswith(ANN_PREFIX):
            out[k[len(ANN_PREFIX):]] = v
    return out


def _num(s: dict, *keys):
    for k in keys:
        v = s.get(k)
        if isinstance(v, (int, float)) and not isinstance(v, bool):
            return v
    return None


class Resolver:
    """Follows references inside one document and between the documents of a batch (by file name, by ``$id``, or ``file#/$defs/x``)."""

    def __init__(self, docs: list[SchemaDoc]):
        self.docs = docs
        self.warnings: list[str] = []

    # ---- references ----
    def _pointer(self, doc: SchemaDoc, frag: str):
        cur = doc.data
        for part in [p for p in frag.lstrip("#").split("/") if p]:
            part = part.replace("~1", "/").replace("~0", "~")
            if isinstance(cur, dict) and part in cur:
                cur = cur[part]
            elif isinstance(cur, list) and part.isdigit() and int(part) < len(cur):
                cur = cur[int(part)]
            else:
                return None
        return cur

    def _find_doc(self, target: str, here: SchemaDoc) -> SchemaDoc | None:
        base = pathlib.PurePosixPath(target.replace("\\", "/")).name
        stem = re.sub(r"(\.schema)?\.(json|ya?ml)$", "", base, flags=re.I)
        for d in self.docs:
            if target and (d.ident == target or d.name == target or pathlib.PurePosixPath(d.name.replace("\\", "/")).name == base):
                return d
        for d in self.docs:
            if d.stem == stem and d is not here:
                return d
        return None

    def follow(self, s: dict, doc: SchemaDoc) -> tuple[dict, SchemaDoc, str]:
        """The schema a ``$ref`` chain ends at, its document and the last name in the pointer. Unresolvable -> ({}, doc, '') and a warning."""
        label = ""
        for _ in range(MAX_DEPTH):
            ref = s.get("$ref") if isinstance(s, dict) else None
            if not isinstance(ref, str):
                break
            target, _, frag = ref.partition("#")
            tdoc = doc
            if target:
                tdoc = self._find_doc(target, doc)
                if tdoc is None:
                    self.warnings.append(f"{doc.name}: $ref {ref} points at a schema that is not in this batch; treated as an open object")
                    return {}, doc, label
            node = self._pointer(tdoc, "#" + frag) if frag else tdoc.data
            if not isinstance(node, dict):
                self.warnings.append(f"{doc.name}: $ref {ref} points at nothing; treated as an open object")
                return {}, doc, label
            label = frag.rstrip("/").split("/")[-1] if frag else tdoc.stem
            rest = {k: v for k, v in s.items() if k != "$ref"}
            s = {**node, **rest} if rest else node   # siblings of $ref win (draft 2020-12 allows them)
            doc = tdoc
        return s, doc, label

    # ---- the tree ----
    def node(self, s, doc: SchemaDoc, depth: int = 0, trail: tuple = ()) -> Node:
        if not isinstance(s, dict):
            return Node(type="object" if s is True else "")
        s, doc, label = self.follow(s, doc)
        n = Node(ref_name=label)
        if depth > MAX_DEPTH:
            n.type = "object"
            return n
        ident = (doc.name, id(s))
        if ident in trail:                           # a recursive $ref: stop with an open object
            n.type = "object"
            n.description = str(s.get("description") or "")
            return n
        trail = trail + (ident,)
        n.type, n.nullable = _types(s)
        n.format = str(s.get("format") or "")
        n.title = str(s.get("title") or "")
        n.description = str(s.get("description") or "")
        n.pattern = str(s.get("pattern") or "")
        n.minimum = _num(s, "minimum", "exclusiveMinimum")
        n.maximum = _num(s, "maximum", "exclusiveMaximum")
        n.min_length = s.get("minLength") if isinstance(s.get("minLength"), int) else None
        if isinstance(s.get("enum"), list):
            n.enum = list(s["enum"])
        if "const" in s:
            n.const, n.has_const = s["const"], True
        ex = s.get("examples")
        n.examples = list(ex) if isinstance(ex, list) else ([s["example"]] if "example" in s else [])
        n.default = s.get("default")
        n.ann = _flat_ann(s)
        req = s.get("required")
        n.required = set(req) if isinstance(req, list) else set()
        pr = s.get("properties")
        if isinstance(pr, dict):
            for k, v in pr.items():
                n.props[k] = self.node(v, doc, depth + 1, trail)
        it = s.get("items")
        if isinstance(it, dict):
            n.items = self.node(it, doc, depth + 1, trail)
        elif isinstance(it, list) and it:
            n.items = self.node(it[0], doc, depth + 1, trail)
        elif isinstance(s.get("prefixItems"), list) and s["prefixItems"]:
            n.items = self.node(s["prefixItems"][0], doc, depth + 1, trail)
        ap = s.get("additionalProperties")
        pp = s.get("patternProperties")
        if isinstance(ap, dict):
            n.additional = self.node(ap, doc, depth + 1, trail)
        elif isinstance(pp, dict) and pp:
            n.additional = self.node(next(iter(pp.values())), doc, depth + 1, trail)
        pn = s.get("propertyNames")
        if isinstance(pn, dict) and isinstance(pn.get("enum"), list):
            n.key_examples = [str(x) for x in pn["enum"]]
        if isinstance(n.ann.get("keys"), list):
            n.key_examples = [str(x) for x in n.ann["keys"]]
        for part in (s.get("allOf") or []):
            self._merge(n, self.node(part, doc, depth + 1, trail))
        for kw in ("oneOf", "anyOf"):
            if isinstance(s.get(kw), list):
                self._variants(n, s, kw, doc, depth, trail)
        if not n.type:
            n.type = "object" if (n.props or n.additional or n.variants) else "array" if n.items else _enum_type(n.enum)
        return n

    def _merge(self, n: Node, o: Node) -> None:
        """allOf: the parts' properties and requirements are added to ``n``; scalars fill what ``n`` leaves empty."""
        for k, v in o.props.items():
            n.props.setdefault(k, v)
        n.required |= o.required
        for a in ("type", "format", "title", "description", "pattern", "ref_name"):
            if not getattr(n, a) and getattr(o, a):
                setattr(n, a, getattr(o, a))
        for a in ("enum", "examples", "variants"):
            if not getattr(n, a) and getattr(o, a):
                setattr(n, a, getattr(o, a))
        for a in ("items", "additional", "minimum", "maximum", "min_length"):
            if getattr(n, a) is None and getattr(o, a) is not None:
                setattr(n, a, getattr(o, a))
        if o.has_const and not n.has_const:
            n.const, n.has_const = o.const, True
        n.discriminator = n.discriminator or o.discriminator
        for k, v in o.ann.items():
            n.ann.setdefault(k, v)

    def _variants(self, n: Node, s: dict, kw: str, doc: SchemaDoc, depth: int, trail: tuple) -> None:
        n.base_props, n.base_required = set(n.props), set(n.required)
        branches = [self.node(b, doc, depth + 1, trail) for b in s[kw] if isinstance(b, dict)]
        branches = [b for b in branches if b.type != "null"]
        if len(branches) == 1:                       # anyOf [X, null]: just X
            self._merge(n, branches[0])
            return
        objs = [b for b in branches if b.type == "object" or b.props]
        if len(objs) != len(branches) or not objs:   # scalars or mixed: the first branch speaks for the field
            if branches:
                self._merge(n, branches[0])
                if all(b.type in ("string", "integer", "number", "boolean") and b.has_const for b in branches):
                    n.enum = [b.const for b in branches]          # oneOf of consts is an enum
            return
        disc = ""
        d = s.get("discriminator")
        if isinstance(d, dict) and d.get("propertyName"):
            disc = str(d["propertyName"])
        disc = str(s.get("x-drishti-discriminator") or disc)
        if not disc:
            disc = _guess_discriminator(objs)
        n.discriminator = disc
        for b in objs:
            label = b.title or b.ref_name or (str(_disc_value(b, disc)) if disc else "") or f"variant {len(n.variants) + 1}"
            n.variants.append((label, b))
        common = set.intersection(*[set(b.props) for b in objs])
        for b in objs:
            for k, v in b.props.items():
                n.props.setdefault(k, v)
        req_all = set.intersection(*[b.required for b in objs]) if objs else set()
        n.required |= req_all & common
        if disc and disc in n.props:
            vals = [_disc_value(b, disc) for b in objs]
            if all(v is not None for v in vals) and not n.props[disc].enum:
                n.props[disc].enum = list(dict.fromkeys(vals))
        n.type = "object"


def _enum_type(values: list) -> str:
    if not values:
        return ""
    v = values[0]
    return "boolean" if isinstance(v, bool) else "integer" if isinstance(v, int) else "number" if isinstance(v, float) else "string"


def _disc_value(b: Node, disc: str):
    p = b.props.get(disc) if disc else None
    if p is None:
        return None
    if p.has_const:
        return p.const
    if len(p.enum) == 1:
        return p.enum[0]
    return None


DISC_NAMES = ("type", "kind", "producttype", "product_type", "category", "variant", "class")


def _guess_discriminator(objs: list) -> str:
    """A property every branch fixes to a different single value (const, or a one-value enum)."""
    common = set.intersection(*[set(b.props) for b in objs])
    ok = [p for p in common if all(_disc_value(b, p) is not None for b in objs)
          and len({str(_disc_value(b, p)) for b in objs}) == len(objs)]
    if not ok:
        return ""
    for want in DISC_NAMES:
        for p in ok:
            if p.lower() == want:
                return p
    return sorted(ok)[0]
