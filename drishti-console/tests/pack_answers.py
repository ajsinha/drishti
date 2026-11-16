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

"""The stand-in server's pack answers (``GET /api/v1/me/console``, ``/packs/{pack}/console`` and its files), made from a pack folder for
the console's unit tests. The console reads no folder (docs/architecture/THIN_CONSOLE_DESIGN.md); a test's stand-in server does, as the
real one does (ConsoleAnswers, its contract test ConsoleAnswersServerTest). The answers' shape is the server's; the rules (which packs a
person has) are each test's own."""
from __future__ import annotations

import hashlib
import json
import re
from pathlib import Path

import yaml

_IMAGE = re.compile(r'!\[[^\]]*]\(\s*<?([^)\s>]+)>?(?:\s+"[^"]*")?\s*\)|<img\b[^>]*\bsrc="([^"]+)"')


def folder(name: str, row: dict | None = None) -> Path | None:
    """The pack's folder: the row's ``dir`` (a stand-in row may name one), else where the checkout's resolver finds it."""
    if row and row.get("dir"):
        d = Path(str(row["dir"]))
        return d if (d / "pack.yaml").is_file() else None
    from core import resolution
    d = resolution.current().packs.dir(name)
    return d if d is not None and (d / "pack.yaml").is_file() else None


def _yaml(f: Path) -> dict:
    return (yaml.safe_load(f.read_text(encoding="utf-8")) or {}) if f.is_file() else {}


def _digest(doc) -> str:
    return hashlib.sha256(json.dumps(doc, sort_keys=True, default=str).encode()).hexdigest()[:16]


def pack_console(name: str, d: Path) -> tuple[dict, dict[str, Path]]:
    """The pack's console answer and its allowed files (path in the pack -> file)."""
    m = _yaml(d / "pack.yaml")
    console = m.get("console") or {}
    workspaces = (_yaml(d / console["workspaces"]).get("templates") or {}) if console.get("workspaces") else {}
    h = _yaml(d / console["help"]) if console.get("help") else {}
    files: dict[str, Path] = {}
    guides = []
    for g in h.get("guides") or []:
        f = (d / str(g.get("file") or "")).resolve()
        if not g.get("slug") or not f.is_file() or not f.is_relative_to(d.resolve()):
            continue
        guides.append(dict(g))
        files[str(g["file"])] = f
        for mm in _IMAGE.finditer(f.read_text(encoding="utf-8")):
            ref = mm.group(1) or mm.group(2)
            if not ref or ":" in ref or ref.startswith(("/", "#")):
                continue
            pic = (f.parent / ref).resolve()
            if pic.is_file() and pic.is_relative_to(d.resolve()):
                files[pic.relative_to(d.resolve()).as_posix()] = pic
    facts = _yaml(d / "config" / "facts.yaml") if (d / "config" / "facts.yaml").is_file() else None
    sources = []
    readme = d / "README.md"
    if readme.is_file():
        front = re.search(r"(?ms)^---\s*\n(.*?)^---\s*$", readme.read_text(encoding="utf-8"))
        meta = (yaml.safe_load(front.group(1)) or {}) if front else {}
        sources = [str(s["name"]) for s in meta.get("sources") or [] if isinstance(s, dict) and s.get("name")]
    doc = {"schema": 1, "pack": name, "version": str(m.get("version", "")), "workspaces": workspaces,
           "help": {"guides": guides, "contextual": h.get("contextual") or {}}, "facts": facts, "sources": sources,
           "files": [{"path": p, "sha": hashlib.sha256(f.read_bytes()).hexdigest()} for p, f in sorted(files.items())]}
    return {"digest": _digest(doc), **doc}, files


def me_row(row: dict) -> dict:
    """A pack row of the person's answer: the server's packs row and what the console draws from the pack's manifest."""
    name = str(row["name"])
    d = folder(name, row)
    m = _yaml(d / "pack.yaml") if d is not None else {}
    console = m.get("console") or {}
    out = {k: v for k, v in row.items() if k not in ("dir", "origin", "console")}
    for k in ("title", "version", "description"):        # the real server's row is the manifest's; a stand-in row's made-up words give way
        out[k] = m.get(k) if m.get(k) is not None else row.get(k, name if k == "title" else "")
    out["kinds"] = row.get("kinds") or m.get("kinds") or []
    out["examples"] = (row.get("console") or {}).get("examples") or console.get("examples") or []
    out["monitors"] = console.get("monitors") or {}
    out["alerts"] = m.get("alerts") or []
    out["mnemonics"] = {str(c).upper(): str(v.get("kind")) for c, v in (m.get("mnemonics") or {}).items() if isinstance(v, dict) and v.get("kind")}
    out["forms"] = {str(k): len(v or []) for k, v in (m.get("form") or {}).items() if isinstance(v, list)}
    out["console"] = {"digest": pack_console(name, d)[0]["digest"] if d is not None else ""}
    return out


def me_console(rows: list[dict], user: str = "ash", roles=(), admin: bool | None = None) -> dict:
    """The person's answer around the stand-in server's pack rows, with the stand-in's decisions about the person (the engines their
    roles or assigned packs open, by the recorded catalogue's ``opens:``; an administrator opens every engine)."""
    import server_answers
    packs = [me_row(r) for r in rows if folder(str(r["name"]), r) is not None]
    roles = set(roles or ())
    admin = bool({"*", "admin"} & roles) if admin is None else admin
    assigned = {str(r["name"]) for r in rows if r.get("assigned", True)}
    opened = []
    for e in server_answers.bootstrap("all").get("engines") or []:
        rules = e.get("opens") or []
        if admin or not rules or any((k == "role" and v in roles) or (k == "pack" and v in assigned)
                                     for k, _, v in (str(x).partition(":") for x in rules)):
            opened.append(e["id"])
    doc = {"person": {"user": user, "roles": sorted(roles), "admin": admin}, "packs": packs, "installation": "test",
           "engines-opened": opened, "grants": ["admin"] if admin else []}
    return {"stamp": "p-" + _digest(doc), **doc}


def dirs_of(rows: list[dict]) -> dict[str, Path]:
    """The folders of the rows' packs, for the console answers that follow the person's (by name)."""
    return {str(r["name"]): d for r in rows if (d := folder(str(r["name"]), r)) is not None}
