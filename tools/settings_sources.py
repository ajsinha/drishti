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

"""Readers for tools/settings_catalogue.py: every key of a commented YAML file with its comment, the connector settings catalogue,
the shared tls.* and proxy.* specs of drishti-api, and the keys a content file really uses (to check a schema covers them).

A key's meaning is its own comment: the text after ``#`` on its line, else the comment lines directly above it (no blank line between).
A ``${NAME:default}`` value gives the environment variable and the default. Nothing here imports the server or the console.
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from pathlib import Path

import yaml

_KEY = re.compile(r"""^(\s*)("[^"]+"|'[^']+'|[A-Za-z0-9_\-.\[\]<>*/$@]+[A-Za-z0-9_\-.\]>*]?)\s*:(?:\s+(.*))?$""")
_ENV = re.compile(r"\$\{([A-Z][A-Z0-9_]*)(?::|\})")
_SPRING_REF = re.compile(r"\$\{(drishti\.data\.dir)(?::[^{}]*)?\}")
_VERSION = re.compile(r"(?<![\w.])([12]\.\d{1,2}\.\d{1,2})(?![\w.])")
_LIVE = re.compile(r"no restart|without a restart|picked up with no restart|follow a settings reload|read again when it changes|"
                   r"\(live\)|is watched|takes effect at once", re.I)
_RESTART = re.compile(r"needs a restart|restart applies|a restart applies|\(restart\)", re.I)


@dataclass
class Entry:
    """One key of a YAML file: its dotted path, the raw value text, its own comment and the comments of its sections."""
    path: tuple[str, ...]
    raw: str
    comment: str
    line: int
    section: bool = False
    kind: str = "scalar"            # scalar | list | map | block
    parents: list[str] = field(default_factory=list)   # the comments of the enclosing sections, nearest first

    @property
    def key(self) -> str:
        return ".".join(self.path)


def split_comment(text: str) -> tuple[str, str]:
    """``value  # comment`` -> (value, comment); a ``#`` inside quotes, or not after whitespace, is part of the value."""
    quote = None
    for i, ch in enumerate(text):
        if quote:
            if ch == quote:
                quote = None
        elif ch in "\"'" and (i == 0 or text[i - 1] in " ,[{:"):
            quote = ch
        elif ch == "#" and (i == 0 or text[i - 1] in " \t"):
            return text[:i].rstrip(), text[i + 1:].strip()
    return text.rstrip(), ""


def _indent(line: str) -> int:
    return len(line) - len(line.lstrip(" "))


def _balance(text: str) -> int:
    depth, quote = 0, None
    for ch in text:
        if quote:
            if ch == quote:
                quote = None
        elif ch in "\"'":
            quote = ch
        elif ch in "[{":
            depth += 1
        elif ch in "]}":
            depth -= 1
    return depth


def _unquote(key: str) -> str:
    return key[1:-1] if len(key) > 1 and key[0] == key[-1] and key[0] in "\"'" else key


def read_commented_yaml(path: Path) -> list[Entry]:
    """Every key of a block-style YAML file, in file order, with its comment. A list (block or flow) and a mapping written in flow
    style are one key each; their items are not keys. Comment lines directly above a key are its comment when it has none on its line;
    they also describe a section (a key whose children follow)."""
    lines = path.read_text(encoding="utf-8").splitlines()
    out: list[Entry] = []
    stack: list[tuple[int, str, str]] = []      # (indent, key, comment)
    pending: list[str] = []
    i, n = 0, len(lines)
    while i < n:
        line = lines[i]
        s = line.strip()
        if not s:
            pending = []
            i += 1
            continue
        if s.startswith("#"):
            pending.append(s.lstrip("#").strip())
            i += 1
            continue
        if s.startswith("- ") or s == "-":
            pending = []
            i += 1
            continue
        m = _KEY.match(line)
        if not m:
            pending = []
            i += 1
            continue
        ind, key = len(m.group(1)), _unquote(m.group(2))
        value, comment = split_comment(m.group(3) or "")
        while stack and stack[-1][0] >= ind:
            stack.pop()
        above = " ".join(p for p in pending if p)
        pending = []
        # what follows: a section, a block list, a block scalar, a multi-line flow value or nothing
        j = i + 1
        while j < n and (not lines[j].strip() or lines[j].strip().startswith("#")):
            j += 1
        nxt = lines[j] if j < n else ""
        kind, section, skip_to = "scalar", False, i + 1
        if value in ("|", ">", "|-", ">-", "|+", ">+"):
            kind = "block"
            k = i + 1
            while k < n and (not lines[k].strip() or _indent(lines[k]) > ind):
                k += 1
            skip_to = k
        elif value and value[0] in "[{" and _balance(value) > 0:
            kind = "list" if value[0] == "[" else "map"
            depth, k = _balance(value), i + 1
            while k < n and depth > 0:
                depth += _balance(split_comment(lines[k].strip())[0])
                k += 1
            skip_to = k
        elif value.startswith("["):
            kind = "list"
        elif value.startswith("{"):
            kind = "map"
        elif not value and nxt.strip().startswith("- ") and _indent(nxt) >= ind:
            kind = "list"
            k = j
            flush = _indent(nxt) == ind                     # items at the key's own indent: its comments may sit there too
            while k < n and (not lines[k].strip() or _indent(lines[k]) > ind
                             or (_indent(lines[k]) == ind and lines[k].strip().startswith(("- ", "#") if flush else "- "))):
                k += 1
            skip_to = k
        elif not value and nxt and _indent(nxt) > ind and _KEY.match(nxt):
            section = True
            kind = "map"
        parents = [c for (_, _, c) in reversed(stack) if c]
        path = tuple(k for (_, k, _) in stack) + (key,)
        own = f"{above} {comment}" if comment and above and len(comment) < 60 and not section else (comment or above)
        out.append(Entry(path, value, own, i + 1, section, kind, parents))
        if kind == "list" and not value:
            out += _item_fields(lines[j:skip_to], j, path, [comment or above] + parents)
        if section:
            stack.append((ind, key, comment or above))
        i = skip_to
    return out


def _item_fields(lines: list[str], first: int, path: tuple[str, ...], parents: list[str]) -> list[Entry]:
    """The fields of a block list's block-style items (``- name: fast  # comment`` and the keys under it), once each: ``models[].name``.
    Items written as flow mappings (``- { id: x }``) are data; the list's own comment describes them."""
    found: dict[str, Entry] = {}
    item_indent, pending = None, []
    for off, line in enumerate(lines):
        s = line.strip()
        if not s:
            pending = []
            continue
        if s.startswith("#"):
            pending.append(s.lstrip("#").strip())
            continue
        body, ind = line, _indent(line)
        if s.startswith("- "):
            item_indent = ind + 2
            body = " " * item_indent + s[2:]
            ind = item_indent
        if item_indent is None or ind != item_indent:
            pending = []
            continue
        m = _KEY.match(body)
        if not m:
            pending = []
            continue
        key = _unquote(m.group(2))
        value, comment = split_comment(m.group(3) or "")
        text = comment or " ".join(pending)
        pending = []
        k = path[:-1] + (path[-1] + "[]", key)
        old = found.get(".".join(k))
        if old is None or (not old.comment and text):
            kind = "list" if value.startswith("[") else ("map" if value.startswith("{") else "scalar")
            found[".".join(k)] = Entry(k, value, text, first + off + 1, False, kind, parents)
    return list(found.values())


def env_names(raw: str) -> list[str]:
    """The environment variables a value's placeholders read, outermost first."""
    return list(dict.fromkeys(_ENV.findall(raw)))


def placeholder_default(raw: str) -> str | None:
    """The value when no environment variable is set: ``${A:${B:x}}`` -> ``x``; ``${A}`` -> None (no default); ``${drishti.data.dir}``
    reads as ``./data``, the data folder's default."""
    text = _SPRING_REF.sub("./data", raw)
    prev = None
    while prev != text:
        prev = text
        text = re.sub(r"\$\{[A-Za-z][A-Za-z0-9_.\-]*:((?:[^{}]|\{[^{}]*\})*)\}", lambda m: m.group(1), text)
    if re.search(r"\$\{[A-Z][A-Z0-9_]*\}", text):
        if text.strip().startswith("${") and text.strip().endswith("}"):
            return None
        text = re.sub(r"\$\{([A-Z][A-Z0-9_]*)\}", r"<\1>", text)
    return text


def infer_type(value: str, kind: str) -> str:
    if kind in ("list", "map"):
        return kind
    if kind == "block":
        return "text"
    v = value.strip().strip("\"'")
    if v.lower() in ("true", "false", "on", "off"):
        return "boolean"
    if re.fullmatch(r"-?\d+", v):
        return "integer"
    if re.fullmatch(r"-?\d+\.\d+", v):
        return "number"
    if re.fullmatch(r"\d+(ms|s|m|h|d|w|y)", v) or re.fullmatch(r"P(T)?\d+[YMWDHS](\d+[MDHS])?", v):
        return "duration"
    if re.fullmatch(r"\d+(B|KB|MB|GB|TB)", v):
        return "size"
    if v in ("[]",):
        return "list"
    if v in ("{}",):
        return "map"
    return "string"


def since(*texts: str) -> str:
    for t in texts:
        m = _VERSION.search(t or "")
        if m:
            return m.group(1)
    return ""


def live_or_restart(text: str, default: str) -> str:
    if _LIVE.search(text or ""):
        return "live"
    if _RESTART.search(text or ""):
        return "restart"
    return default


# ---- the connector settings catalogue and the shared specs -----------------------------------------------------------------------

def read_plugin_catalogue(path: Path) -> dict:
    return yaml.safe_load(path.read_text(encoding="utf-8")) or {}


_SPEC = re.compile(r'new SettingSpec\(\s*(?:prefix \+ )?"([^"]+)",\s*"([^"]+)",\s*(true|false),\s*(null|"[^"]*"),\s*((?:"(?:[^"\\]|\\.)*"\s*\+?\s*)+)', re.S)


def read_java_specs(path: Path) -> list[dict]:
    """``new SettingSpec(prefix + "name", "type", required, default, "desc" + "more", ...)`` of a drishti-api class."""
    out = []
    for m in _SPEC.finditer(path.read_text(encoding="utf-8")):
        desc = "".join(re.findall(r'"((?:[^"\\]|\\.)*)"', m.group(5))).replace('\\"', '"')
        default = None if m.group(4) == "null" else m.group(4)[1:-1]
        out.append({"name": m.group(1), "type": m.group(2), "required": m.group(3) == "true", "default": default, "desc": desc})
    return out


# ---- the keys a content file uses --------------------------------------------------------------------------------------------------

def used_keys(node, prefix: str = "", maps: tuple[str, ...] = ()) -> set[str]:
    """The keys a parsed file uses: ``a.b``, a list's items as ``a[]``, and the keys of a user-named mapping as ``<name>`` when the
    mapping's path (with ``<name>`` for those above it) is in ``maps``."""
    out: set[str] = set()
    if isinstance(node, dict):
        named = prefix.rstrip(".") in maps
        for k, v in node.items():
            seg = "<name>" if named else str(k)
            path = f"{prefix}{seg}"
            out.add(path)
            out |= used_keys(v, path + ".", maps)
    elif isinstance(node, list):
        for item in node:
            if isinstance(item, dict):
                out |= used_keys(item, prefix.rstrip(".") + "[].", maps)
    return out
