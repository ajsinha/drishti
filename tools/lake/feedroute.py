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

"""Where a feed file goes, for the command-line loaders (``lake build``, ``data ingest``): the one place that pairs a file with its
``.ready`` file and maps a table name (a file name's first segment, a tar's folder) to a pack and kind
(docs/guides/DATA_LAKE_GUIDE.md sections 5 and 5). The data inbox does the same in drishti-dataload (``Routes``, ``ReadyNote``).

* **Pairing**: ``<file>.ready`` (the data file's full name), or the name without its data extension (``trades.2026-11-03.ready``,
  ``eod.tar.ready``). A short name that could pair with two data files releases neither.
* **Keys** (all optional; YAML or JSON; empty is fine): business-date, table, pack, kind, connector, mode, new-table, key, folders,
  sha256, rows, hold, lake (the lake the file goes to: a lake of config/lakes/). An unknown key refuses the file. The command line's own
  options win over the marker's (``--lake`` over ``lake:``).
* **The pack of a table**: the pack given (``--pack``, ``pack:``, a tar's ``folders:``); else the pack that already has a kind or table
  of that name (compared without case); else a pack of that name with a single kind. Two candidates or none: refused, listing them."""
from __future__ import annotations

import hashlib
import pathlib
import sys
from dataclasses import dataclass
from datetime import date

_CONSOLE = pathlib.Path(__file__).resolve().parents[2] / "drishti-console"
if str(_CONSOLE) not in sys.path:
    sys.path.insert(0, str(_CONSOLE))
from core.pack_paths import pack_dirs  # noqa: E402  (the one rule for where a pack's folder is)

READY = ".ready"
#: the keys of a .ready file, in the documentation's order: the same list as the inbox's (drishti-dataload ReadyNote.KEYS; a test compares them)
READY_KEYS = ("business-date", "table", "pack", "kind", "connector", "mode", "new-table", "key", "folders", "sha256", "rows", "hold", "lake")
MODES = {"append": "append", "replace": "overwrite-dates", "overwrite-dates": "overwrite-dates"}
DATA_EXTENSIONS = (".tar.gz", ".tar.zip", ".jsonl.gz", ".jsonl.zip", ".jsonl.zst", ".ndjson.gz", ".ndjson.zst", ".json.gz", ".tgz", ".tar",
                   ".jsonl", ".ndjson", ".json", ".gz", ".zip", ".zst")


class RouteError(ValueError):
    """A file that cannot be routed, and why."""


def short_name(name: str) -> str:
    """The data file's name without its data extension (``trades.2026-11-03.jsonl.gz`` -> ``trades.2026-11-03``)."""
    low = name.lower()
    for e in DATA_EXTENSIONS:
        if low.endswith(e) and len(name) > len(e):
            return name[: len(name) - len(e)]
    return name


def marker(path) -> pathlib.Path | None:
    """The ``.ready`` file of ``path``, None without one; refuses a short marker that two data files could claim."""
    p = pathlib.Path(path)
    full = p.with_name(p.name + READY)
    if full.is_file():
        return full
    short = p.with_name(short_name(p.name) + READY)
    if short.is_file() and short != full:
        rivals = [f for f in p.parent.iterdir() if f != p and f.is_file() and not f.name.endswith(READY) and short_name(f.name) == short_name(p.name)]
        if rivals:
            raise RouteError(f"{short.name} could release {p.name} and {', '.join(sorted(r.name for r in rivals))}: it releases neither; "
                             f"name it after its data file in full ({p.name}{READY})")
        return short
    return None


def read_marker(path) -> dict:
    """The marker's keys ({} without a marker, or an empty one); refuses an unknown key, a bad value, or a sha256/rows that does not match."""
    m = marker(path)
    if m is None:
        return {}
    import yaml
    text = m.read_text(encoding="utf-8").strip()
    try:
        doc = yaml.safe_load(text) if text else {}
    except yaml.YAMLError as e:
        raise RouteError(f"{m.name}: not YAML or JSON ({e})") from None
    if doc is None:
        return {}
    if not isinstance(doc, dict):
        raise RouteError(f"{m.name}: a mapping of {', '.join(READY_KEYS)} (or empty)")
    bad = sorted(k for k in doc if k not in READY_KEYS)
    if bad:
        raise RouteError(f"{m.name}: {', '.join(bad)} {'is not a key' if len(bad) == 1 else 'are not keys'} of a .ready file "
                         f"({', '.join(READY_KEYS)})")
    out = dict(doc)
    if out.get("business-date") is not None:
        try:
            out["business-date"] = date.fromisoformat(str(out["business-date"]))
        except ValueError:
            raise RouteError(f"{m.name}: business-date {out['business-date']}: give YYYY-MM-DD") from None
    if out.get("mode") is not None:
        mode = str(out["mode"]).lower()
        if mode not in MODES:
            raise RouteError(f"{m.name}: mode {out['mode']}: append or replace")
        out["mode"] = MODES[mode]
    if out.get("table") is not None:
        out["table"] = str(out["table"]).lower()
    if out.get("lake") is not None:
        import re
        out["lake"] = str(out["lake"]).strip()
        if not re.fullmatch(r"[a-z0-9][a-z0-9_-]*", out["lake"]):
            raise RouteError(f"{m.name}: lake {out['lake']}: a lake's name, lower-case letters, digits, '-' and '_' (risklake); its tables go to "
                             "<data>/delta/<lake>/<domain>/<table>")
    if out.get("folders") is not None and not isinstance(out["folders"], dict):
        raise RouteError(f"{m.name}: folders: a mapping of a tar's folder to a pack ({{trades: core}})")
    if out.get("sha256"):
        h = hashlib.sha256()
        with open(path, "rb") as fh:
            for block in iter(lambda: fh.read(1 << 20), b""):
                h.update(block)
        if h.hexdigest().lower() != str(out["sha256"]).lower():
            raise RouteError(f"{m.name}: sha256 {out['sha256']} does not match {pathlib.Path(path).name} ({h.hexdigest()}): a file cut short?")
    out["_marker"] = m.name
    return out


@dataclass(frozen=True)
class Target:
    """A table's pack (its folder), kind and Delta connector (None: the first that serves the kind)."""
    pack: pathlib.Path
    kind: str
    connector: str | None


def _pack_doc(pack: pathlib.Path) -> dict:
    import yaml
    return yaml.safe_load((pack / "pack.yaml").read_text(encoding="utf-8")) or {}


def pack_tables(pack: pathlib.Path) -> list[tuple[str, str, str | None]]:
    """(name, kind, connector) for every kind of the pack and every table a Delta connector names for one (``table.<kind>``)."""
    m = _pack_doc(pack)
    defs = m.get("connectors") if isinstance(m.get("connectors"), dict) else (m.get("connector-templates") or {})
    out: list[tuple[str, str, str | None]] = []
    kinds = list((m.get("ingest") or {}).keys())
    for name, c in (defs or {}).items():
        if not isinstance(c, dict) or c.get("plugin") != "delta":
            continue
        st = c.get("settings") or {}
        for k in c.get("kinds") or []:
            if k not in kinds:
                kinds.append(k)
            named = (st.get("table") or {}).get(k) if isinstance(st.get("table"), dict) else st.get(f"table.{k}")
            if named:
                out.append((str(named), k, name))
    out += [(k, k, None) for k in kinds]
    return out


def _packs(root) -> list[pathlib.Path]:
    root = pathlib.Path(root)
    return list(pack_dirs(root).values()) if root.is_dir() else []


def resolve(table: str, packs_root, pack=None, kind: str | None = None, connector: str | None = None) -> Target:
    """The pack and kind ``table`` names (module doc). ``pack``: the pack given; ``kind``, ``connector``: given too."""
    if pack is not None:
        p = pathlib.Path(pack)
        if kind:
            return Target(p, kind, connector)
        return _one(table, [p], connector, given=True)
    packs = _packs(packs_root)
    try:
        return _one(table, packs, connector)
    except RouteError as e:
        if "more than one" in str(e):
            raise
        named = [p for p in packs if p.name.lower() == table.lower()]
        if named:
            kinds = [n for n, _k, c in pack_tables(named[0]) if c is None]
            if kind or len(kinds) == 1:
                return Target(named[0], kind or kinds[0], connector)
            raise RouteError(f"{table}: the pack {named[0].name} has the kinds {', '.join(kinds)}: give the kind (--kind, kind: in the .ready file)") from None
        raise


def _one(table: str, packs, connector, given: bool = False) -> Target:
    hits, names = [], []
    for p in packs:
        for n, k, c in pack_tables(p):
            names.append(f"{p.name}/{n}")
            if n.lower() == table.lower() and (connector is None or c in (None, connector)):
                t = Target(p, k, c or connector)
                if t not in hits and not any(h.pack == p and h.kind == k and h.connector == (c or connector) for h in hits):
                    hits.append(t)
    if len(hits) == 1:
        return hits[0]
    if not hits:
        raise RouteError(f"{table}: no {'kind or table of that name in the pack' if given else 'pack, kind or table of that name'} "
                         f"(known: {', '.join(sorted(set(names), key=str.lower)) or 'none'})")
    raise RouteError(f"{table}: more than one table of that name ({', '.join(f'{t.pack.name}/{t.kind}' + (f' ({t.connector})' if t.connector else '') for t in hits)}): "
                     "give the pack and kind (--pack, --kind, or pack: and kind: in the .ready file)")
