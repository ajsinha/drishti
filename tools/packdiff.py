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

"""`drishti.py pack diff OLD NEW`: what changes between two versions of a pack, and what it breaks.

OLD and NEW are pack folders, `pack make` output folders or bundles (.tar.gz). Findings come in four levels:

    breaking    a kind or a mnemonic removed or renamed: monitors, workspaces and alerts refer to them by name
    selection   a Sutra added or removed, or its match where / priority changed: other documents get other screens
    layout      the data layout changed (connectors, routes, ingest, columns)
    change      Sutra content, about text or glossary changed

Exit: 1 when a finding of the --fail-on level (breaking by default; `any` for any difference; `none` never) exists, else 0; 2 on usage errors.
"""
from __future__ import annotations

import difflib
import json
import pathlib
import tempfile

LEVELS = ("breaking", "selection", "layout", "change")
SKIP_DIRS = {".generated", ".git", "__pycache__"}


class DiffError(Exception):
    def __init__(self, message: str, code: int = 2):
        super().__init__(message)
        self.code = code


def open_pack(source: str, cli, tmp: pathlib.Path) -> pathlib.Path:
    p = pathlib.Path(source)
    if not p.exists():
        raise DiffError(f"{source}: no such file or folder")
    if p.is_file():
        try:
            return cli.pack_bundle().safe_extract(p, tmp / p.name.replace(".", "_"))
        except Exception as e:  # BundleError, tar errors
            raise DiffError(f"{source}: not a readable bundle ({e})") from None
    if (p / "pack.yaml").is_file():
        return p
    inner = sorted((p / "pack").glob("*/pack.yaml")) if (p / "pack").is_dir() else []
    if len(inner) == 1:
        return inner[0].parent
    raise DiffError(f"{source}: not a pack folder (no pack.yaml)")


def load_pack(root: pathlib.Path, yaml) -> dict:
    def ld(p):
        try:
            return yaml.safe_load(p.read_text(encoding="utf-8")) or {}
        except (OSError, yaml.YAMLError) as e:
            raise DiffError(f"{p}: {e}") from None
    meta = ld(root / "pack.yaml")
    sutras = {}
    for f in sorted(root.glob(f"{meta.get('sutras', 'sutras')}/**/*.sutra.yaml")):
        text = f.read_text(encoding="utf-8")
        d = ld(f)
        name = str(d.get("sutra") or f.name)
        sutras[name] = {"file": f.relative_to(root).as_posix(), "text": text, "doc": d}
    about = ld(root / "config" / "about.yaml") if (root / "config" / "about.yaml").is_file() else {}
    return {"root": root, "meta": meta, "sutras": sutras, "about": about}


def _match(s: dict) -> dict:
    m = s["doc"].get("match") or {}
    return {"kind": m.get("kind"), "where": m.get("where"), "priority": m.get("priority")}


def _panels(s: dict) -> list[str]:
    return [str(p.get("id")) for p in (s["doc"].get("panels") or []) if isinstance(p, dict)]


def diff_packs(old: dict, new: dict, unified: bool = False) -> list[dict]:
    out: list[dict] = []

    def add(level, what, name, detail="", diff=None):
        out.append({"level": level, "what": what, "name": name, "detail": detail, **({"diff": diff} if diff else {})})

    om, nm = old["meta"], new["meta"]
    if str(om.get("version")) != str(nm.get("version")):
        add("change", "version", str(nm.get("pack")), f"{om.get('version')} -> {nm.get('version')}")

    # kinds and mnemonics
    ok, nk = list(om.get("kinds") or []), list(nm.get("kinds") or [])
    omn, nmn = om.get("mnemonics") or {}, nm.get("mnemonics") or {}
    renamed_kinds: dict[str, str] = {}
    for m, o in omn.items():
        n = nmn.get(m)
        if n and o.get("kind") != n.get("kind") and o.get("kind") not in nk and n.get("kind") not in ok:
            renamed_kinds[o["kind"]] = n["kind"]
    for k in ok:
        if k in renamed_kinds:
            add("breaking", "kind renamed", k, f"{k} -> {renamed_kinds[k]} (monitors, workspaces and alerts that name '{k}' stop matching)")
        elif k not in nk:
            add("breaking", "kind removed", k, "monitors, workspaces and alerts that name it stop working")
    for k in nk:
        if k not in ok and k not in renamed_kinds.values():
            add("change", "kind added", k)
    kind_of_old = {v.get("kind"): m for m, v in omn.items()}
    kind_of_new = {v.get("kind"): m for m, v in nmn.items()}
    for m, v in omn.items():
        k = v.get("kind")
        if m in nmn and nmn[m].get("kind") == k:
            if nmn[m].get("label") != v.get("label"):
                add("change", "mnemonic label", m, f"{v.get('label')!r} -> {nmn[m].get('label')!r}")
            continue
        if k in kind_of_new and kind_of_new[k] != m:
            add("breaking", "mnemonic renamed", m, f"{m} -> {kind_of_new[k]} for kind {k} (typed commands and saved links use it)")
        elif m not in nmn and renamed_kinds.get(k) in kind_of_new and k in renamed_kinds:
            pass
        elif m not in nmn:
            add("breaking", "mnemonic removed", m, f"kind {k}")
    for m, v in nmn.items():
        if m not in omn and v.get("kind") not in kind_of_old and v.get("kind") not in renamed_kinds.values():
            add("change", "mnemonic added", m, f"kind {v.get('kind')}")

    # sutras
    osu, nsu = old["sutras"], new["sutras"]
    for n in sorted(set(osu) - set(nsu)):
        add("selection", "sutra removed", n, f"{osu[n]['file']}: its documents fall to another Sutra (match {_match(osu[n])['where'] or 'any'})")
    for n in sorted(set(nsu) - set(osu)):
        add("selection", "sutra added", n, f"{nsu[n]['file']}: match {_match(nsu[n])['where'] or 'any'}, priority {_match(nsu[n])['priority']}")
    for n in sorted(set(osu) & set(nsu)):
        a, b = osu[n], nsu[n]
        if a["text"] == b["text"]:
            continue
        ma, mb = _match(a), _match(b)
        if ma != mb:
            parts = [f"{k} {ma[k]!r} -> {mb[k]!r}" for k in ("kind", "where", "priority") if ma[k] != mb[k]]
            add("selection", "screen selection changes", n, "; ".join(parts))
        pa, pb = _panels(a), _panels(b)
        detail = []
        if set(pa) - set(pb):
            detail.append("panels removed " + ", ".join(sorted(set(pa) - set(pb))))
        if set(pb) - set(pa):
            detail.append("panels added " + ", ".join(sorted(set(pb) - set(pa))))
        if not detail:
            detail.append("content changed")
        ud = None
        if unified:
            ud = "".join(difflib.unified_diff(a["text"].splitlines(True), b["text"].splitlines(True), f"old/{a['file']}", f"new/{b['file']}"))
        add("change", "sutra changed", n, "; ".join(detail), ud)

    # about and glossary
    oab, nab = (old["about"].get("kinds") or {}), (new["about"].get("kinds") or {})
    for k in sorted(set(oab) | set(nab)):
        a, b = oab.get(k), nab.get(k)
        if a is None or b is None:
            add("change", "about " + ("added" if a is None else "removed"), k)
            continue
        for fld in ("title", "about"):
            if a.get(fld) != b.get(fld):
                add("change", f"about {fld}", k, "text changed")
        ga, gb = a.get("glossary") or {}, b.get("glossary") or {}
        for f in sorted(set(ga) | set(gb)):
            if f not in ga:
                add("change", "glossary added", f"{k}.{f}")
            elif f not in gb:
                add("change", "glossary removed", f"{k}.{f}")
            elif ga[f] != gb[f]:
                add("change", "glossary changed", f"{k}.{f}")
        if (a.get("panels") or {}) != (b.get("panels") or {}):
            add("change", "panel notes", k, "changed")

    # data layout
    for key in ("connectors", "connector-templates", "routes", "ingest", "columns"):
        a, b = om.get(key) or {}, nm.get(key) or {}
        a, b = ({x: x for x in v} if isinstance(v, list) else v for v in (a, b))      # connectors: a list of names, or a mapping of definitions
        for n in sorted(set(a) | set(b)):
            if n not in a:
                add("layout", f"{key} added", n)
            elif n not in b:
                add("layout", f"{key} removed", n)
            elif a[n] != b[n]:
                detail = ""
                if key == "columns":
                    gone, new_ = sorted(set(a[n]) - set(b[n])), sorted(set(b[n]) - set(a[n]))
                    detail = "; ".join(x for x in ((f"columns removed {', '.join(gone)}" if gone else ""), (f"columns added {', '.join(new_)}" if new_ else "")) if x) or "order changed"
                add("layout", f"{key} changed", n, detail)
    return out


def run(a, cli) -> int:
    yaml = cli.need_yaml()
    with tempfile.TemporaryDirectory(prefix="drishti-diff-") as t:
        tmp = pathlib.Path(t)
        old = load_pack(open_pack(a.old, cli, tmp / "old"), yaml)
        new = load_pack(open_pack(a.new, cli, tmp / "new"), yaml)
        found = diff_packs(old, new, a.unified)
    counts = {lv: sum(1 for f in found if f["level"] == lv) for lv in LEVELS}
    fail = {"none": False, "breaking": counts["breaking"] > 0, "any": bool(found)}[a.fail_on]
    ov, nv = old["meta"].get("version"), new["meta"].get("version")
    if a.json:
        cli.say_json({"old": a.old, "new": a.new, "oldVersion": str(ov), "newVersion": str(nv), "counts": counts, "findings": found, "failOn": a.fail_on, "failed": fail})
        return 1 if fail else 0
    print(f"pack diff: {old['meta'].get('pack')} {ov} -> {new['meta'].get('pack')} {nv}")
    for lv in LEVELS:
        rows = [f for f in found if f["level"] == lv]
        if not rows:
            continue
        print(f"\n{lv.upper()} ({len(rows)})")
        for f in rows:
            print(f"  {f['what']:<26} {f['name']}" + (f"   {f['detail']}" if f["detail"] else ""))
            if f.get("diff"):
                print("".join("      " + ln for ln in f["diff"].splitlines(True)))
    print("\nsummary: " + (", ".join(f"{n} {lv}" for lv, n in counts.items() if n) or "no differences") + f"  (fail-on {a.fail_on}: {'FAIL' if fail else 'ok'})")
    return 1 if fail else 0
