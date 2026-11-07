#!/usr/bin/env python3
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
"""TypeScript declarations (``.d.ts``) of a Drishti Elements library, written from its typed JSDoc: the same parse as the API
reference (``tools/docs/elements_api.py``: the names the library's entry exports, their JSDoc blocks, the modules' ``@typedef`` and
``@callback`` blocks), so a type is written once, in the module, and both the reference and the declarations follow it.

Used by the distribution (``tools/elements/dist.py``): each library's package carries ``drishti-<name>.d.ts``. A JSDoc type this
converter cannot read becomes ``any`` (never an error: the reference's ``--check`` is the gate on missing types). A type named from
another library is ``import('<that library>').Name``; the caller says how a library is named (a package name, or a relative path)."""
from __future__ import annotations

import re
import sys
from pathlib import Path
from typing import Callable

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "docs"))
import elements_api as ref  # noqa: E402

# TypeScript's own and the DOM's types a JSDoc type may name as they are
GLOBALS = {
    "string", "number", "boolean", "void", "undefined", "null", "never", "unknown", "any", "object", "symbol", "bigint",
    "Promise", "Map", "Set", "WeakMap", "WeakSet", "Date", "Error", "RegExp", "ArrayBuffer", "Uint8Array", "Int32Array", "Float64Array",
    "Float32Array", "DataView", "Iterable", "AsyncIterable", "Record", "Partial", "Readonly",
    "Element", "HTMLElement", "HTMLDialogElement", "HTMLInputElement", "HTMLCanvasElement", "HTMLTableElement", "HTMLSelectElement",
    "HTMLTextAreaElement", "HTMLButtonElement", "HTMLDivElement", "SVGElement", "Node", "ParentNode", "DocumentFragment", "Document",
    "Window", "Event", "CustomEvent", "KeyboardEvent", "MouseEvent", "PointerEvent", "DragEvent", "FocusEvent", "WheelEvent",
    "ClipboardEvent", "EventTarget", "AbortSignal", "AbortController", "Blob", "File", "Response", "Request", "Headers", "URL",
    "CustomElementRegistry", "MutationObserver", "ResizeObserver", "IntersectionObserver", "WebSocket", "EventSource", "Storage",
    "DOMRect", "CSSStyleDeclaration", "ShadowRoot", "MessageEvent", "TextEncoder", "TextDecoder", "ReadableStream",
}
RENAME = {"String": "string", "Number": "number", "Boolean": "boolean", "Symbol": "symbol", "Object": "object"}


class TypeReader:
    """A JSDoc (Closure) type, read into TypeScript: unions, ``?T``, ``T[]``, ``Array<T>``, ``Object<K, V>``, ``{a: T}``,
    ``function(A, B): R``, ``import('./x.js').Name``, literals. ``known(name)`` gives a local or another library's type its spelling,
    or None for ``any``."""

    def __init__(self, text: str, known: Callable[[str], str | None]):
        self.s, self.i, self.known = text, 0, known

    def ws(self) -> None:
        while self.i < len(self.s) and self.s[self.i].isspace():
            self.i += 1

    def peek(self, word: str) -> bool:
        self.ws()
        return self.s.startswith(word, self.i)

    def eat(self, word: str) -> bool:
        if self.peek(word):
            self.i += len(word)
            return True
        return False

    def need(self, word: str) -> None:
        if not self.eat(word):
            raise ValueError(f"expected {word!r} at {self.i} in {self.s!r}")

    def name(self) -> str:
        self.ws()
        m = re.compile(r"[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*").match(self.s, self.i)
        if not m:
            raise ValueError(f"a name expected at {self.i} in {self.s!r}")
        self.i = m.end()
        return m.group(0)

    def read(self) -> str:
        t = self.union()
        self.ws()
        if self.i != len(self.s):
            raise ValueError(f"left over: {self.s[self.i:]!r}")
        return t

    def union(self) -> str:
        parts = [self.postfix()]
        while self.eat("|"):
            parts.append(self.postfix())
        return parts[0] if len(parts) == 1 else " | ".join(parts)

    def postfix(self) -> str:
        nullable = self.eat("?")
        if nullable and (self.peek(",") or self.peek(")") or self.peek(">") or self.peek("}") or self.peek("|") or self.i >= len(self.s)):
            return "any"                                    # a bare ? is "anything"
        self.eat("!")
        t = self.primary()
        while self.eat("[]"):
            t = f"({t})[]" if (" | " in t or "=>" in t) else t + "[]"
        self.eat("=")                                       # optional: said by the parameter, not the type
        return f"{t} | null" if nullable and t != "any" else t

    def primary(self) -> str:
        self.ws()
        c = self.s[self.i:self.i + 1]
        if c == "*":
            self.i += 1
            return "any"
        if c == "(":
            self.i += 1
            t = self.union()
            self.need(")")
            return f"({t})" if " | " in t else t
        if c in "'\"":
            j = self.s.index(c, self.i + 1)
            lit = self.s[self.i + 1:j]
            self.i = j + 1
            return "'" + lit.replace("'", "\\'") + "'"
        if c == "{":
            return self.record()
        m = re.compile(r"-?\d+(\.\d+)?").match(self.s, self.i)
        if m:
            self.i = m.end()
            return m.group(0)
        if self.peek("import("):
            self.need("import(")
            self.ws()
            q = self.s[self.i]
            j = self.s.index(q, self.i + 1)
            self.i = j + 1
            self.need(")")
            self.need(".")
            n = self.name()
            return self.known(n) or "any"
        if self.peek("function") and not re.match(r"function[\w$]", self.s[self.i:]):
            self.need("function")
            if not self.eat("("):
                return "(...args: any[]) => any"
            params, k = [], 0
            while not self.eat(")"):
                if self.eat("this:") or self.eat("new:"):
                    self.union()
                elif self.eat("..."):
                    params.append(f"...rest: {self.wrap(self.postfix())}[]")
                else:
                    t = self.union()
                    params.append(f"a{k}: {t}")
                    k += 1
                self.eat(",")
            ret = self.union() if self.eat(":") else "void"
            return "((" + ", ".join(params) + ") => " + ret + ")"
        n = self.name()
        args: list[str] = []
        if self.eat(".<") or self.eat("<"):
            args.append(self.union())
            while self.eat(","):
                args.append(self.union())
            self.need(">")
        if n in ("Object", "object"):
            if len(args) == 2:
                return f"Record<{args[0]}, {args[1]}>"
            return "Record<string, any>"
        if n == "Array":
            return f"{self.wrap(args[0])}[]" if args else "any[]"
        if n == "Function":
            return "((...args: any[]) => any)"
        if n in RENAME:
            return RENAME[n]
        if n in GLOBALS:
            return n + (f"<{', '.join(args)}>" if args else ("<any>" if n == "Promise" else ""))
        return self.known(n) or "any"

    @staticmethod
    def wrap(t: str) -> str:
        return f"({t})" if (" | " in t or "=>" in t) else t

    def record(self) -> str:
        self.need("{")
        fields = []
        while not self.eat("}"):
            self.ws()
            if self.s[self.i] in "'\"":
                q = self.s[self.i]
                j = self.s.index(q, self.i + 1)
                key = self.s[self.i:j + 1]
                self.i = j + 1
            else:
                key = self.name()
            t = self.union() if self.eat(":") else "any"
            fields.append(f"{key}: {t}")
            self.eat(",")
        return "{ " + "; ".join(fields) + " }" if fields else "Record<string, any>"


def ts_type(jsdoc: str, known: Callable[[str], str | None]) -> str:
    """A JSDoc type in TypeScript; ``any`` when it cannot be read."""
    if not jsdoc or not jsdoc.strip():
        return "any"
    try:
        return TypeReader(jsdoc.strip(), known).read()
    except (ValueError, IndexError):
        return "any"


# -- the tags, read with balanced braces (a type may hold {…} records) ------------------------------------------------------------------
def braced(text: str, at: int) -> tuple[str, int]:
    """The {type} starting at ``at`` (text[at] == '{'): its inside and the index after it."""
    depth = 0
    for j in range(at, len(text)):
        if text[j] == "{":
            depth += 1
        elif text[j] == "}":
            depth -= 1
            if depth == 0:
                return text[at + 1:j], j + 1
    return text[at + 1:], len(text)


def tags(block: str) -> dict:
    """A JSDoc block: its description, params [(name, type, optional)], returns, type, properties, typedef/callback, private."""
    text = ref.clean(block)
    out = {"desc": "", "params": [], "returns": None, "type": None, "props": [], "typedef": None, "callback": None, "private": False}
    parts = re.split(r"(?m)(?=^@|\s@(?=param\b|returns?\b|type\b|property\b|typedef\b|callback\b|private\b|example\b))", text)
    out["desc"] = " ".join(parts[0].split()) if parts and not parts[0].lstrip().startswith("@") else ""
    for p in parts:
        p = p.strip()
        m = re.match(r"@(\w+)\s*", p)
        if not m:
            continue
        tag, rest = m.group(1), p[m.end():]
        typ = None
        if rest.startswith("{"):
            typ, k = braced(rest, 0)
            rest = rest[k:].strip()
        if tag in ("param", "property"):
            nm = re.match(r"(\[)?\s*([\w$.]+)\s*(=[^\]]*)?(\])?", rest)
            if nm:
                (out["params"] if tag == "param" else out["props"]).append((nm.group(2), typ or "", bool(nm.group(1)) or (typ or "").endswith("=")))
        elif tag in ("returns", "return"):
            out["returns"] = typ or ""
        elif tag == "type":
            out["type"] = typ or ""
        elif tag in ("typedef", "callback"):
            nm = re.match(r"([\w$]+)", rest)
            if nm:
                out[tag] = (nm.group(1), typ or "")
        elif tag == "private":
            out["private"] = True
    return out


def comment(desc: str, indent: str = "") -> list[str]:
    if not desc:
        return []
    return [f"{indent}/** {desc.replace('*/', '* /')} */"]


def params_ts(params: list, args: list[str], known) -> str:
    """The signature's parameters: the source's names, each with its @param type (an @param a.b is a member, not a parameter)."""
    by_name = {}
    for name, typ, opt in params:
        if "." not in name:
            by_name[name] = (typ, opt)
    out = []
    for a in args:
        rest = a.startswith("...")
        plain = re.sub(r"=.*", "", a[3:] if rest else a).strip()
        if not re.fullmatch(r"[\w$]+", plain):
            plain = f"arg{len(out)}"
        typ, opt = by_name.get(plain, ("", False))
        t = ts_type(typ, known)
        if rest:
            out.append(f"...{plain}: {TypeReader.wrap(t)}[]")
        else:
            out.append(f"{plain}{'?' if (opt or '=' in a) else ''}: {t}")
    return ", ".join(out)


def declarations(name: str, entry: Path, spec_of: Callable[[str], str]) -> str:
    """The ``.d.ts`` text of the library ``name`` whose entry module is ``entry``.
    ``spec_of(library)``: how another library is imported in the declarations (a package name or a relative path)."""
    exported = ref.exports_of(entry)
    src = entry.parent
    typedefs: dict[str, tuple[dict, str]] = {}
    for f in sorted(src.glob("*.js")):
        for m in ref.TYPEDEF.finditer(f.read_text(encoding="utf-8")):
            t = tags(m.group(1))
            td = t["typedef"] or t["callback"]
            if td and td[0] not in typedefs:
                typedefs[td[0]] = (t, "callback" if t["callback"] else "typedef")
    local = {n for n, _ in exported} | set(typedefs)
    others: dict[str, str] = {}                                      # a name another library exports -> its library
    for lib_entry in sorted(src.parent.parent.glob("drishti-*/src/drishti_index.js")):
        lib = lib_entry.parent.parent.name
        if lib == name:
            continue
        for n, _ in ref.exports_of(lib_entry):
            others.setdefault(n, lib)
        for f in sorted(lib_entry.parent.glob("*.js")):
            for m in ref.TYPEDEF.finditer(f.read_text(encoding="utf-8")):
                td = tags(m.group(1))
                td = td["typedef"] or td["callback"]
                if td:
                    others.setdefault(td[0], lib)

    def known(n: str) -> str | None:
        n = n.split(".")[-1]
        if n in local:
            return n
        if n in others:
            return f"import('{spec_of(others[n])}').{n}"
        return None

    out: list[str] = []
    for tname, (t, kind) in typedefs.items():
        out += comment(t["desc"])
        if kind == "callback":
            ret = ts_type(t["returns"] or "", known) if t["returns"] is not None else "void"
            ps = [(n, ty, o) for n, ty, o in t["params"] if "." not in n]
            sig = ", ".join(n + ("?" if o else "") + ": " + ts_type(ty, known) for n, ty, o in ps)
            out.append(f"export type {tname} = ({sig}) => {ret};")
        elif t["props"]:
            out.append(f"export interface {tname} {{")
            for pn, pt, opt in t["props"]:
                if "." in pn:
                    continue
                out.append(f"  {pn}{'?' if opt else ''}: {ts_type(pt, known)};")
            out.append("}")
        else:
            body = ts_type(t["typedef"][1], known)
            out.append(f"export type {tname} = {'Record<string, any>' if body == tname else body};")
    for sym, module in exported:
        if sym in typedefs:
            continue
        text = module.read_text(encoding="utf-8") if module.is_file() else ""
        found = None
        for m in ref.DOC_DECL.finditer(text):
            if sym in (m.group(4), m.group(6), m.group(7)) and not m.group(1).count("/**"):
                found = m
                break
        if not found:
            if re.search(r"\bclass\s+" + re.escape(sym) + r"\b", text):
                out.append(f"export declare class {sym} {{ constructor(...args: any[]); [member: string]: any; }}")
            elif re.search(r"\bfunction\s*\*?\s*" + re.escape(sym) + r"\b", text):
                out.append(f"export declare function {sym}(...args: any[]): any;")
            else:
                out.append(f"export declare const {sym}: any;")
            continue
        t = tags(found.group(1))
        fn, cls, const = found.group(4), found.group(6), found.group(7)
        out += comment(t["desc"])
        if fn:
            args = [a.strip() for a in found.group(5).split(",") if a.strip()]
            ret = ts_type(t["returns"], known) if t["returns"] is not None else "void"
            out.append(f"export declare function {sym}({params_ts(t['params'], args, known)}): {ret};")
        elif cls:
            ext = re.search(r"\bclass\s+" + re.escape(sym) + r"\s+extends\s+([\w$.]+)", text)
            base = known(ext.group(1)) if ext else None
            out.append(f"export declare class {sym}{' extends ' + base if base and not base.startswith('import(') else ''} {{")
            body = ref.class_body(text, found.end())
            seen = set()
            for mm in ref.METHOD.finditer(body):
                mn = mm.group(2)
                mt = tags(mm.group(1))
                if mt["private"] or mn.startswith("_") or mn in ("if", "for", "while", "switch", "catch", "function") or mn in seen:
                    continue
                seen.add(mn)
                args = [a.strip() for a in mm.group(3).split(",") if a.strip()]
                static = bool(re.search(r"static\s+(?:async\s+)?" + re.escape(mn) + r"\s*\(", body))
                if mn == "constructor":
                    out.append(f"  constructor({params_ts(mt['params'], args, known)});")
                    continue
                out += comment(mt["desc"], "  ")
                ret = ts_type(mt["returns"], known) if mt["returns"] is not None else "void"
                out.append(f"  {'static ' if static else ''}{mn}({params_ts(mt['params'], args, known)}): {ret};")
            out.append("  [member: string]: any;")
            out.append("}")
        else:
            if t["type"]:
                ty = ts_type(t["type"], known)
            else:
                decl = text[found.end():found.end() + 200]
                ty = "readonly string[]" if re.match(r"\s*=\s*Object\.freeze\(\[\s*['\"]", decl) else \
                     "string" if re.match(r"\s*=\s*['\"]", decl) else "number" if re.match(r"\s*=\s*-?\d", decl) else "any"
            out.append(f"export declare const {sym}: {ty};")
    return "\n".join(out) + "\n"
