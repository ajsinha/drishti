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

"""`drishti.py pack regenerate`: new data, new Sutras, without losing a single edit.

`pack new` / `pack make` store a baseline of what they generated in <pack>/.generated/ (a copy of every generated file plus the options used).
`pack regenerate PACK INPUTS` generates again into a scratch folder (theirs) and merges it into the pack (ours) against the baseline (base):

    only theirs changed        refresh the file          only ours changed          keep it (your edit)
    both changed, same result  nothing to do             both changed, differently  line-level three-way merge, conflict markers
    new in theirs              add it                    unchanged but gone in theirs  remove it (edited: kept, reported)

A file with conflict markers is listed and the exit code is 1. Nothing is overwritten unless the baseline proves it was untouched.
"""
from __future__ import annotations

import argparse
import contextlib
import difflib
import io
import json
import pathlib
import shutil
import tempfile

GEN = ".generated"
SUFFIX = ".base"  # so no tool (sutra lint, bundle globs) mistakes a baseline copy for a real file
OPTIONS = ".options.json"
STORED = ("name", "title", "description", "version", "mnemonic", "key", "date", "match", "kind", "store", "priority", "min_docs", "fallback",
          "name_prefix", "samples", "catalog_samples", "skip_missing", "recursive")
MARK_OURS, MARK_MID, MARK_THEIRS = "<<<<<<< yours (the pack)", "=======", ">>>>>>> regenerated from the new data"
NOTE = ("What `pack new`/`pack make`/`pack regenerate` last generated: the base of the three-way merge in `pack regenerate`.\n"
        "Keep it with the pack (commit it); without it the next regenerate cannot tell your edits from generated text.\n")


class RegenError(Exception):
    def __init__(self, message: str, code: int = 2):
        super().__init__(message)
        self.code = code


# ------------------------------------------------------------------------------------------------------ baseline

def pack_files(root: pathlib.Path) -> dict[str, bytes]:
    """rel path -> bytes of every file of the pack except the baseline itself."""
    out = {}
    for p in sorted(root.rglob("*")):
        rel = p.relative_to(root)
        if p.is_file() and not p.is_symlink() and rel.parts[0] != GEN and "__pycache__" not in rel.parts:
            out[rel.as_posix()] = p.read_bytes()
    return out


def snapshot(pack: pathlib.Path, opts, source: pathlib.Path | None = None) -> None:
    """Writes <pack>/.generated/: a copy of the files as just generated (those of `source`, default the pack itself) and the options used."""
    base = pack / GEN
    files = pack_files(source or pack)
    if base.exists():
        shutil.rmtree(base)
    for rel, data in files.items():
        dst = base / (rel + SUFFIX)
        dst.parent.mkdir(parents=True, exist_ok=True)
        dst.write_bytes(data)
    stored = {k: getattr(opts, k) for k in STORED if isinstance(getattr(opts, k, None), (str, int, float, bool))}
    (base / OPTIONS).write_text(json.dumps(stored, indent=1, sort_keys=True) + "\n", encoding="utf-8")
    (base / "README.txt").write_text(NOTE, encoding="utf-8")


def read_baseline(pack: pathlib.Path) -> tuple[dict[str, bytes] | None, dict]:
    base = pack / GEN
    if not base.is_dir():
        return None, {}
    files = {}
    for p in sorted(base.rglob("*")):
        rel = p.relative_to(base).as_posix()
        if p.is_file() and rel.endswith(SUFFIX):
            files[rel[:-len(SUFFIX)]] = p.read_bytes()
    opts = json.loads((base / OPTIONS).read_text(encoding="utf-8")) if (base / OPTIONS).is_file() else {}
    return files, opts


# ------------------------------------------------------------------------------------------------------ merge

def _regions(base: list[str], other: list[str]):
    sm = difflib.SequenceMatcher(None, base, other, autojunk=False)
    return [(i1, i2, other[j1:j2]) for tag, i1, i2, j1, j2 in sm.get_opcodes() if tag != "equal"]


def _apply(base: list[str], s: int, e: int, regions) -> list[str]:
    out, pos = [], s
    for i1, i2, new in regions:
        out += base[pos:i1] + new
        pos = i2
    return out + base[pos:e]


def _nl(lines: list[str]) -> list[str]:
    return lines[:-1] + [lines[-1] + "\n"] if lines and not lines[-1].endswith("\n") else lines


def merge3(base: str, ours: str, theirs: str) -> tuple[str, int]:
    """Line-level three-way merge; (text, conflicts). Edits to the same or adjacent base lines on both sides conflict unless identical."""
    b, o, t = base.splitlines(True), ours.splitlines(True), theirs.splitlines(True)
    items = sorted([(r, "o") for r in _regions(b, o)] + [(r, "t") for r in _regions(b, t)], key=lambda x: (x[0][0], x[0][1]))
    out: list[str] = []
    pos, conflicts, i = 0, 0, 0
    while i < len(items):
        (s, e, _), _side = items[i]
        cluster = [items[i]]
        i += 1
        while i < len(items) and items[i][0][0] <= e and (items[i][1] != cluster[-1][1] or items[i][0][0] < e):
            e = max(e, items[i][0][1])
            cluster.append(items[i])
            i += 1
        out += b[pos:s]
        o_reg = [r for r, sd in cluster if sd == "o"]
        t_reg = [r for r, sd in cluster if sd == "t"]
        mine, theirs_ = _apply(b, s, e, o_reg), _apply(b, s, e, t_reg)
        if not t_reg or mine == theirs_:
            out += mine
        elif not o_reg:
            out += theirs_
        else:
            conflicts += 1
            out += [MARK_OURS + "\n"] + _nl(mine) + [MARK_MID + "\n"] + _nl(theirs_) + [MARK_THEIRS + "\n"]
        pos = e
    out += b[pos:]
    return "".join(out), conflicts


# ------------------------------------------------------------------------------------------------------ plan

def _text(b: bytes) -> str | None:
    try:
        return b.decode("utf-8")
    except UnicodeDecodeError:
        return None


def plan(base: dict[str, bytes] | None, ours: dict[str, bytes], theirs: dict[str, bytes]) -> list[dict]:
    """One action per file: add, refresh, remove, keep, merge, conflict, same, stale, deleted, no-baseline. `data` is what to write (None: nothing)."""
    acts = []
    for rel in sorted(set(ours) | set(theirs) | set(base or {})):
        b, o, t = (base or {}).get(rel), ours.get(rel), theirs.get(rel)
        a = {"path": rel, "action": "same", "data": None, "note": ""}
        if o is None and t is not None:
            if b is None:
                a.update(action="add", data=t, note="new in the regenerated pack")
            else:
                a.update(action="deleted", note="you deleted it" + ("" if b == t else " (the generator changed it since)") + ": left deleted")
        elif t is None and o is not None:
            if b is None:
                a.update(action="keep", note="yours alone")
            elif b == o:
                a.update(action="remove", note="no longer generated and you never edited it")
            else:
                a.update(action="stale", note="no longer generated but you edited it: kept")
        elif o is not None and t is not None and o != t:
            if b is None:
                a.update(action="no-baseline", note="differs, and the pack has no .generated/ baseline: yours kept, nothing merged")
            elif o == b:
                a.update(action="refresh", data=t, note="untouched, refreshed")
            elif t == b:
                a.update(action="keep", note="your edit kept; the generated text did not change")
            else:
                bt, ot, tt = _text(b), _text(o), _text(t)
                if None in (bt, ot, tt):
                    a.update(action="conflict", note="binary file edited on both sides: yours kept")
                else:
                    merged, n = merge3(bt, ot, tt)
                    if n:
                        a.update(action="conflict", data=merged.encode("utf-8"), note=f"{n} conflict(s): markers written, resolve them by hand")
                    else:
                        a.update(action="merge", data=merged.encode("utf-8"), note="your edit and the new text merged cleanly")
        acts.append(a)
    return acts


# ------------------------------------------------------------------------------------------------------ command

def given_options(argv, parser_actions) -> set[str]:
    out = set()
    for act in parser_actions:
        for opt in act.option_strings:
            if any(tok == opt or tok.startswith(opt + "=") for tok in argv or []):
                out.add(act.dest)
    return out


def regenerate(a, cli, parser_actions=()) -> int:
    pack = pathlib.Path(a.pack).resolve()
    if not (pack / "pack.yaml").is_file():
        raise RegenError(f"{pack} is not a pack folder (no pack.yaml)")
    yaml = cli.need_yaml()
    meta = yaml.safe_load((pack / "pack.yaml").read_text(encoding="utf-8")) or {}
    base, stored = read_baseline(pack)
    given = given_options(getattr(a, "_argv", None), parser_actions)
    for k, v in stored.items():
        if k not in given and hasattr(a, k):
            setattr(a, k, v)
    a.name = meta.get("pack") or pack.name
    ingest = meta.get("ingest") if isinstance(meta.get("ingest"), dict) else {}
    if not a.key and ingest:
        a.key = ",".join(f"{k}={v['key']}" for k, v in ingest.items() if v.get("key"))
    if not a.date and ingest:
        a.date = ",".join(f"{k}={v['date']}" for k, v in ingest.items() if v.get("date"))
    a.jar, a.java = cli.find_jar(a.jar), cli.find_java(a.java)
    pfj = cli.load_module("pack_from_jsonl", "packgen/pack_from_jsonl.py")
    scratch = pathlib.Path(tempfile.mkdtemp(prefix="drishti-regen-"))
    try:
        gen = scratch / a.name
        ns = argparse.Namespace(**{k: v for k, v in vars(a).items() if k != "func"})
        ns.out, ns.force, ns.lake, ns.files_root = gen, True, None, None
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            rc = pfj.run(ns)
        if not (gen / "pack.yaml").is_file():
            raise RegenError("generation failed:\n" + buf.getvalue()[-1500:], 1)
        acts = plan(base, pack_files(pack), pack_files(gen))
        if not a.dry_run:
            for x in acts:
                p = pack / x["path"]
                if x["action"] in ("add", "refresh", "merge", "conflict") and x["data"] is not None:
                    p.parent.mkdir(parents=True, exist_ok=True)
                    p.write_bytes(x["data"])
                elif x["action"] == "remove":
                    p.unlink()
            for d in sorted((p for p in pack.rglob("*") if p.is_dir() and GEN not in p.parts), reverse=True):
                if not any(d.iterdir()):
                    d.rmdir()
            snapshot(pack, ns, source=gen)
        counts: dict[str, int] = {}
        for x in acts:
            counts[x["action"]] = counts.get(x["action"], 0) + 1
        problems = [x for x in acts if x["action"] in ("conflict", "no-baseline")]
        if a.json:
            cli.say_json({"pack": str(pack), "dryRun": bool(a.dry_run), "baseline": base is not None, "counts": counts, "generatedChecksPassed": rc == 0,
                          "files": [{"path": x["path"], "action": x["action"], "note": x["note"]} for x in acts if x["action"] != "same"]})
        else:
            print(f"pack regenerate{' --dry-run' if a.dry_run else ''}: {pack}" + ("" if base is not None else "  (no .generated/ baseline yet)"))
            for act in ("add", "refresh", "merge", "conflict", "keep", "stale", "remove", "deleted", "no-baseline"):
                rows = sorted((x for x in acts if x["action"] == act), key=lambda x: x["path"].startswith("samples/"))
                quiet = act in ("refresh", "add", "remove")
                for x in rows[:6] if quiet else rows:
                    print(f"  {act:<11} {x['path']}" + ("" if (quiet and act != "remove") or not x["note"] else f"   ({x['note']})"))
                if quiet and len(rows) > 6:
                    print(f"  {act:<11} ... and {len(rows) - 6} more")
            print("summary: " + (", ".join(f"{n} {k}" for k, n in sorted(counts.items()) if k != "same") or "nothing to change") + f"; {counts.get('same', 0)} unchanged")
            if problems:
                print(f"attention: {len(problems)} file(s) need you (search for '<<<<<<<'), then  drishti.py pack check {pack}")
            elif not a.dry_run:
                print(f"next: drishti.py pack check {pack}")
        return 1 if problems else 0
    finally:
        shutil.rmtree(scratch, ignore_errors=True)
