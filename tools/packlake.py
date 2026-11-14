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

"""A pack's lake block and its connector templates (docs/guides/DATA_LAKE_GUIDE.md section 12).

A pack ships its recommended table entries in a ``lake:`` block of ``pack.yaml`` (the shape of a lake file's ``tables:``), and its
Delta and Iceberg connector templates name the lake (``lake: demo``) and their entries (``lake-tables:``), nothing of the tables'
layout. Two functions, one each way, so the pack generators and the tools that read a pack agree:

* :func:`lake_form` moves the layout of a pack's templates into its ``lake:`` block (the generators call it; it ran once over the
  hand-written packs);
* :func:`settings_view` gives a template's settings as the tools read them (``root``, ``domain``, ``table.<kind>``,
  ``layout.<kind>.*``), filled from the pack's own ``lake:`` block for a template in the lake form.

This reads ``pack.yaml`` only. A lake file (``config/lakes/*.yaml``) is read by the one lake reader (tools/lake/lakeconfig.py)."""
from __future__ import annotations

import copy
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent / "lake"))
import lakeconfig  # noqa: E402  (where the lakes are: the one place the tools spell it)

DEMO_LAKE = "demo"
DEMO_ROOT = lakeconfig.DEMO_ROOT_REF            # config/lakes/demo.yaml: the demo lake's own folder
#: the official results' lakes keep the publisher's root (config/lakes/banking/var-official.yaml, ccr-official.yaml): <data>/delta itself
OFFICIAL_ROOT = lakeconfig.OFFICIAL_ROOT_REF
#: the layout keys that belong to the lake entry (the rest of a kind's layout, type-ahead, stays with the connector: a reading concern)
ENTRY_KEYS = {"columns": "promote", "vectors": "vectors", "sort-by": "sort-by", "file-rows": "file-rows", "row-group-rows": "row-group-rows",
              "flatten": "flatten"}
#: the official results' readers (OfficialReaders): each kind's own lake, whose root and format are its publisher's settings
#: (config/lakes/banking/var-official.yaml, ccr-official.yaml); the server makes the connector Iceberg when the publisher writes Iceberg
OFFICIAL_LAKES = {"var-official": "var-official", "exposure-official": "ccr-official", "exposure-official-cpty": "ccr-official"}
#: the lakes whose root a tool knows without a lake file: the demo lake's own, and the official results' (unless a server setting says otherwise)
DEMO_ROOTED = {DEMO_LAKE, "var-official", "ccr-official"}


def _list(v) -> list[str]:
    if v is None:
        return []
    if isinstance(v, (list, tuple)):
        return [str(x).strip() for x in v if str(x).strip()]
    return [x.strip() for x in str(v).split(",") if x.strip()]


def _layout(settings: dict, kind: str) -> dict:
    """A kind's layout, nested (``layout: {kind: {...}}``) or dotted (``layout.<kind>.columns``)."""
    out = dict(((settings.get("layout") or {}) if isinstance(settings.get("layout"), dict) else {}).get(kind) or {})
    prefix = f"layout.{kind}."
    for k, v in settings.items():
        if k.startswith(prefix):
            out[k[len(prefix):]] = v
    return out


def _table(settings: dict, kind: str) -> str | None:
    t = settings.get("table")
    if isinstance(t, dict):
        return t.get(kind)
    return settings.get(f"table.{kind}")


def lake_form(manifest: dict, lake: str = DEMO_LAKE, partitions: dict[str, list[str]] | None = None) -> dict:
    """``manifest`` (a pack.yaml mapping) with every Delta or Iceberg template's tables moved into its ``lake:`` block: an entry
    ``<domain>.<folder>`` per kind (``kind:`` when the folder is not the kind), the promoted columns, vectors, sort, file layout and
    flattened fields; ``partitions`` adds a partition after business_date (``{"trading.trade_endur": ["PRODUCT_TYPE"]}``). Each template
    then names ``lake`` and ``lake-tables`` (an official results reader names its publisher's lake, :data:`OFFICIAL_LAKES`). A template
    already in that form is left as it is."""
    m = copy.deepcopy(manifest)
    key = "connectors"
    if not isinstance(m.get("lake"), dict):
        m["lake"] = {}
    tables = m["lake"].setdefault("tables", {})
    for name, c in (m.get(key) or {}).items():
        if not isinstance(c, dict) or c.get("plugin") not in ("delta", "iceberg"):
            continue
        st = dict(c.get("settings") or {})
        if "lake" in st:
            continue
        official = {OFFICIAL_LAKES[k] for k in (c.get("kinds") or []) if k in OFFICIAL_LAKES}
        domain = st.get("domain") or m.get("pack")
        names = []
        for kind in [str(k) for k in (c.get("kinds") or [])]:
            folder = _table(st, kind) or kind
            entry_name = f"{domain}.{folder}"
            names.append(entry_name)
            lay = _layout(st, kind)
            entry: dict = {}
            if folder != kind:
                entry["kind"] = kind
            if partitions and partitions.get(entry_name):
                entry["partition-by"] = list(partitions[entry_name])
            for src, dst in ENTRY_KEYS.items():
                if src in lay and lay[src] not in (None, "", [], {}):
                    v = lay[src]
                    entry[dst] = v if isinstance(v, dict) else (int(v) if dst in ("file-rows", "row-group-rows") else _list(v))
            tables.setdefault(entry_name, entry or None)
        kept = {k: v for k, v in st.items()
                if k not in ("root", "domain", "table") and not k.startswith("table.") and k != "layout" and not k.startswith("layout.")}
        reading = {kind: {"type-ahead": _layout(st, kind)["type-ahead"]} for kind in (c.get("kinds") or []) if "type-ahead" in _layout(st, kind)}
        new = {"lake": next(iter(official)) if len(official) == 1 else lake, "lake-tables": ",".join(names)}
        new.update(kept)
        if reading:
            new["layout"] = reading
        c["settings"] = new
    if not tables:
        m.pop("lake", None)
    return m


def settings_view(manifest: dict, connector: dict) -> dict:
    """A template's settings as the tools read them: unchanged for the older form; for a template naming ``lake`` and ``lake-tables``,
    the root of the demo lake, the domain, ``table`` and ``layout`` filled from the pack's own ``lake:`` block."""
    st = dict(connector.get("settings") or {})
    if "lake" not in st:
        return st
    entries = ((manifest.get("lake") or {}).get("tables") or {})
    names = _list(st.get("lake-tables")) or [n for n, e in entries.items() if (e or {}).get("kind", n.split(".", 1)[1]) in (connector.get("kinds") or [])]
    out = {k: v for k, v in st.items() if k not in ("lake", "lake-tables", "layout")}
    out["root"] = DEMO_ROOT if st["lake"] == DEMO_LAKE else OFFICIAL_ROOT if st["lake"] in DEMO_ROOTED else st["lake"]
    layout: dict = {k: dict(v) for k, v in ((st.get("layout") or {}) if isinstance(st.get("layout"), dict) else {}).items()}
    table: dict = {}
    for n in names:
        domain, _, folder = n.partition(".")
        e = entries.get(n) or {}
        kind = e.get("kind") or folder
        out["domain"] = domain
        if folder != kind:
            table[kind] = folder
        lay = layout.setdefault(kind, {})
        for src, dst in ENTRY_KEYS.items():
            if dst in e:                                # sort-by as the older form wrote it: one text (id, or A,B)
                lay[src] = ",".join(_list(e[dst])) if src == "sort-by" else e[dst]
    if table:
        out["table"] = table
    if layout:
        out["layout"] = layout
    return out


def with_modules(manifest: dict | None) -> dict:
    """The pack's manifest with the connectors of every source module that requires it (modules/source-<system>/connectors/*.yaml:
    delta.yaml is connector ``<kind>-<system>``, any other file the connector of its own name) added where the pack does not declare that
    name itself. A source system is a module of its own."""
    m = dict(manifest or {})
    if not m.get("pack") or not isinstance(m.get("connectors"), dict):
        return m
    import yaml
    conns = dict(m["connectors"])
    generic = {"iceberg", "files", "file", "kafka", "cassandra", "s3tables", "delta-s3"}
    for f in sorted((pathlib.Path(__file__).resolve().parents[1] / "modules").glob("source-*/connectors/*.yaml")):
        mod = f.parents[1]
        requires = (yaml.safe_load((mod / "module.yaml").read_text(encoding="utf-8")) or {}).get("requires") or {}
        doc = yaml.safe_load(f.read_text(encoding="utf-8")) or {}
        kind = (doc.get("kinds") or [""])[0]
        if m["pack"] not in requires or not kind or f.stem in generic:
            continue
        # delta.yaml is the system's read template (connector <kind>-<system>); any other file is a connector of its own name
        name = f"{kind}-{mod.name[len('source-'):]}" if f.stem == "delta" else f.stem
        if name not in conns:
            conns[name] = doc
    m["connectors"] = conns
    return m


def view(manifest: dict | None, modules: bool = True) -> dict:
    """``manifest`` with every template in the lake form shown in the older form (:func:`settings_view`): what a tool that reads a
    template's root, domain, table and layout loads instead of the file as it is. The ``lake:`` block stays."""
    m = with_modules(manifest) if modules else dict(manifest or {})
    key = "connectors"
    if not isinstance(m.get(key), dict):
        return m
    conns = {}
    for name, c in m[key].items():
        if isinstance(c, dict) and isinstance(c.get("settings"), dict) and "lake" in c["settings"]:
            c = {**c, "settings": settings_view(m, c)}
        conns[name] = c
    m[key] = conns
    return m


def entry_of(manifest: dict, connector: dict, kind: str) -> dict:
    """The pack's ``lake:`` entry a template reads for ``kind`` (with ``name`` and ``domain``), or {} for the older form."""
    st = connector.get("settings") or {}
    if "lake" not in st:
        return {}
    entries = ((manifest.get("lake") or {}).get("tables") or {})
    for n in _list(st.get("lake-tables")):
        e = entries.get(n) or {}
        if (e.get("kind") or n.partition(".")[2]) == kind:
            return {"name": n, "domain": n.partition(".")[0], **e}
    return {}
