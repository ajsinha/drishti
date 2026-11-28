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

"""docs/admin/TABLE_CATALOGUE.md: every table of every lake file, by its symbolic name (owner rule 2026-11-27).

    python3 tools/docs/table_catalogue.py            write the document
    python3 tools/docs/table_catalogue.py --check    fail when the document is stale

Built from the lake files (config/lakes/*.yaml and config/lakes/*/*.yaml: the table catalogue, where each table's location, format and
contract are configured), the kinds' contracts (what a table holds: purpose, key, grain, main columns), and the places that name a table
(connectors, pack manifests, engine inputs), so the list of who uses a table is found, never written by hand. The location shown is the
reference installation's (the demo and owner lakes under the data folder): a bank's lake file says where its own tables are.
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
DOC = ROOT / "docs" / "admin" / "TABLE_CATALOGUE.md"
SKIP = {"target", "node_modules", ".git", ".venv", "__pycache__"}


def _yaml(p: Path):
    try:
        return yaml.safe_load(p.read_text(encoding="utf-8"))
    except (yaml.YAMLError, OSError, UnicodeDecodeError):
        return None


def _files(base: Path, pattern: str):
    for p in sorted(base.rglob(pattern)):
        if not any(part in SKIP for part in p.relative_to(base).parts):
            yield p


def reference(text: str) -> str:
    """A lake file's value with its placeholders as the reference installation has them: the data folder is ``<data>``, a default is taken."""
    prev = None
    s = str(text)
    while prev != s:
        prev = s
        s = re.sub(r"\$\{(?:lake\.data\.dir|drishti\.data\.dir)[^${}]*(?:\$\{[^{}]*\})?\}", "<data>", s)
        s = re.sub(r"\$\{[A-Za-z0-9_.-]+:([^${}]*)\}", lambda m: m.group(1), s)
        s = re.sub(r"\$\{[A-Za-z0-9_.-]+\}", "<data>", s) if "$" in s and prev == s else s
    return s.replace("<data><data>", "<data>")


def lakes() -> list[dict]:
    out = []
    for p in _files(ROOT / "config" / "lakes", "*.yaml"):
        d = _yaml(p)
        if not isinstance(d, dict) or not d.get("lake"):
            continue
        d["_file"] = str(p.relative_to(ROOT))
        out.append(d)
    return sorted(out, key=lambda d: d["lake"])


def contracts() -> dict[str, dict]:
    """kind -> its contract (the first found; a kind named in several packs has the same shape)."""
    found: dict[str, dict] = {}
    for base in (ROOT / "modules", ROOT / "config" / "packs", ROOT / "examples"):
        for p in _files(base, "*.yaml"):
            if p.parent.name != "contracts":
                continue
            d = _yaml(p)
            if isinstance(d, dict) and d.get("kind"):
                found.setdefault(str(d["kind"]), d)
    return found


def _sentence(s: str, n: int = 170) -> str:
    s = " ".join(str(s or "").split())
    if len(s) <= n:
        return s
    cut = s[:n].rsplit(" ", 1)[0]
    return cut.rstrip(",;:") + " ..."


def holds(c: dict | None) -> tuple[str, str, str]:
    """(purpose, key and grain, main columns) from a kind's contract."""
    if not c:
        return ("", "", "")
    purpose = c.get("title") and c.get("description") and f"{c['title']}: {c['description']}" or c.get("description") or c.get("title") or c.get("binding-hint") or ""
    fields = [f for f in (c.get("fields") or []) if isinstance(f, dict) and f.get("name")]
    key = c.get("key")
    if isinstance(key, str):
        key = [key]
    if not key:
        key = [f["name"] for f in fields if f.get("key")]
    grain = c.get("grain") or ""
    kg = ", ".join(str(k) for k in key or [])
    keygrain = (f"`{kg}`" if kg else "") + (("; " if kg else "") + _sentence(grain, 120) if grain else "")
    cols = ", ".join(f"`{f['name']}`" for f in fields[:6]) + (f" (+{len(fields) - 6})" if len(fields) > 6 else "")
    return (_sentence(purpose), keygrain, cols)


def owner(path: Path) -> tuple[str, str]:
    """(module, pack) a file belongs to by where it sits."""
    parts = path.relative_to(ROOT).parts
    mod = pack = ""
    if parts[0] == "modules" and len(parts) > 1:
        mod = parts[1]
        if len(parts) > 3 and parts[2] == "packs":
            pack = parts[3]
    elif parts[0] == "examples" and len(parts) > 2 and parts[1] == "packs":
        pack = parts[2]
        mod = "examples"
    elif parts[0] == "config" and len(parts) > 2 and parts[1] == "packs":
        pack = parts[2]
        mod = "kernel"
    return mod, pack


def _walk(node):
    if isinstance(node, dict):
        yield node
        for v in node.values():
            yield from _walk(v)
    elif isinstance(node, list):
        for v in node:
            yield from _walk(v)


def users(lake_docs: list[dict]) -> dict[tuple[str, str], dict[str, set]]:
    """(lake, table name) -> {"packs": {...}, "modules": {...}, "engines": {...}} from connectors, pack manifests and engine inputs."""
    by_lake = {d["lake"]: d for d in lake_docs}
    kinds: dict[str, dict[str, list[str]]] = {}      # lake -> kind -> table names
    for ln, d in by_lake.items():
        for tn, e in (d.get("tables") or {}).items():
            kind = (e or {}).get("kind") or str(tn).split(".", 1)[1]
            kinds.setdefault(ln, {}).setdefault(kind, []).append(tn)
    out: dict[tuple[str, str], dict[str, set]] = {}

    def add(lake: str, table: str, what: str, who: str) -> None:
        if who:
            out.setdefault((lake, table), {"packs": set(), "modules": set(), "engines": set()})[what].add(who)

    for base in (ROOT / "modules", ROOT / "config", ROOT / "examples"):
        for p in _files(base, "*.yaml"):
            rel = p.relative_to(ROOT).parts
            if rel[:2] == ("config", "lakes") or rel[:2] == ("config", "lakes.examples"):
                continue
            try:
                text = p.read_text(encoding="utf-8")
            except (OSError, UnicodeDecodeError):
                continue
            if "lake" not in text:
                continue
            d = _yaml(p)
            mod, pack = owner(p)
            isengine = rel[:2] == ("config", "engines")
            for n in _walk(d):
                st = n.get("settings") if isinstance(n.get("settings"), dict) else n
                lk = st.get("lake") if isinstance(st, dict) else None
                if isinstance(lk, str) and lk in by_lake and ("kinds" in n or "lake-tables" in st or "plugin" in n):
                    names = [t.strip() for t in str(st.get("lake-tables", "")).split(",") if t.strip()]
                    if not names:
                        for k in n.get("kinds") or []:
                            names += kinds.get(lk, {}).get(str(k).split(":")[0] if isinstance(k, str) else str(k), [])
                    for t in names:
                        add(lk, t, "packs", pack)
                        add(lk, t, "modules", mod)
                if rel[-1] == "pack.yaml" and isinstance(n.get("lake"), dict):
                    for t in (n["lake"].get("tables") or {}):
                        for ln, d2 in by_lake.items():
                            if t in (d2.get("tables") or {}):
                                add(ln, t, "packs", pack or str(d.get("pack") or d.get("name") or ""))
                                add(ln, t, "modules", mod)
            if isengine and isinstance(d, dict):
                eng = p.stem
                for n in _walk(d):
                    ins = n.get("inputs")
                    if isinstance(ins, dict):
                        for spec in ins.values():
                            for k in (spec or {}).get("kinds") or [] if isinstance(spec, dict) else []:
                                for ln, km in kinds.items():
                                    for t in km.get(str(k), []):
                                        add(ln, t, "engines", eng)
    return out


def location(lake: dict, name: str, entry: dict | None) -> tuple[str, str, str]:
    """(location, format, how) of a table in the reference installation."""
    e = entry or {}
    fmt = str(e.get("format") or lake.get("format") or "delta")
    fmt = reference(fmt)
    uri = e.get("uri") or (e.get("path") if str(e.get("path", "")).startswith("/") or "://" in str(e.get("path", "")) else None)
    if uri:
        return reference(str(uri).replace("${lake.root}", str(lake.get("root") or ""))), fmt, "configured"
    domain, tbl = name.split(".", 1)
    path = e.get("path") or f"{domain}/{tbl}"
    root = reference(lake.get("root") or "")
    cat = lake.get("catalog") or {}
    if not root and isinstance(cat, dict) and cat.get("type"):
        return f"catalog {cat['type']}: {path.replace('/', '.')}", fmt, "configured"
    return f"{root.rstrip('/')}/{path}", fmt, "fallback"


RECIPE = [
    "## Binding a bank's external lake",
    "",
    "A bank's lake keeps its own layout: its tables sit at paths and depths Drishti does not control, in Delta or Iceberg. Nothing of",
    "Drishti's layout is assumed; the lake file binds each symbolic name to where the table is. For each table:",
    "",
    "1. Copy `config/lakes.examples/other/customer-lake.yaml` to `config/lakes/<lake>.yaml` (the site folder in production). Set `format:` to",
    "   the lake's usual format; the `root:` is only the default for a table with no `uri:`.",
    "2. Under `tables:` give each symbolic name (`<domain>.<table>`, for example `trading.trade`) its entry: `uri:` the table's real location",
    "   (`s3://fo-data/mx/trades_v7`, `/mnt/mart/risk/scenario_pv`, an absolute path or URI, nothing joined to it), `format:` (`delta` or",
    "   `iceberg`) when it differs from the lake's, and `contract:` the kind's contract (`trade@1`) the table is read by. `${lake.root}/...`",
    "   anchors a uri to the lake's root when the tables do follow it.",
    "3. A kind that several tables hold is one entry per table (`trading.trade_murex` with `kind: trade`), a connector names the entry with",
    "   `lake-tables:`.",
    "4. Run `python3 tools/drishti.py lake check`: it compares each table on a local disk with its entry (format, partition columns, promoted",
    "   columns present) and names every mismatch; a table on an object store is reported as not checked from here. **Admin → Lakes** shows",
    "   each table's resolved location, format, contract and whether it is *configured* or a *fallback*.",
    "5. `python3 tools/drishti.py lake entries` lists (and with `--write` writes) an explicit entry for every table that is still a fallback,",
    "   from where the layout puts it today: the reference installation was made explicit this way once.",
    "",
    "Under the production profile a table that is a fallback is refused at start (rule T1 of the production standard): every table the server",
    "reads is resolved explicitly, by its own `uri:`. The server reads a bank's external tables, and the tools read and write them, by the",
    "symbolic name alone; the tests `ExternalLakeTest` (server) and `test_external_lake.py` (tools) bind a lake of arbitrary folders this way.",
    "",
]


def render() -> str:
    ls = lakes()
    cs = contracts()
    us = users(ls)
    n = sum(len(d.get("tables") or {}) for d in ls)
    configured = sum(1 for d in ls for t, e in (d.get("tables") or {}).items() if location(d, t, e or {})[2] == "configured")
    out = [
        "<!--",
        "  Project Drishti · Any data. Any domain. One grammar.",
        "  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.",
        "  PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository.",
        "-->",
        "",
        "# Table catalogue: every table by its symbolic name",
        "",
        "Generated from the lake files (`config/lakes/`) and the kinds' contracts by `tools/docs/table_catalogue.py` (do not edit by hand;",
        "`python3 tools/drishti.py docs generate --check` fails when this page is stale).",
        "",
        "Code, packs, Sutras, engine inputs and contracts know a table only by its symbolic name (`trading.trade`, `risk.scenario-pv`). The lake",
        "file of each installation says where that name is: per table, `uri:` (an absolute path or URI, nothing joined to it), `format:` (delta,",
        "iceberg or files) and `contract:`. A table with no `uri:` sits at `<root>/<domain>/<table>`, the layout Drishti gives its own lakes. A",
        "bank's external lake needs only its path and schema in its lake file; no folder layout of Drishti's is assumed. **Admin → Lakes** shows",
        "each table's resolved location and whether it was configured or a fallback. The locations below are the reference installation's",
        "(`<data>` is the data folder); a table is not owned by a pack or a module, which only declare the names they read.",
        "",
        f"{n} tables in {len(ls)} lakes: {configured} configured (an explicit `uri:`), {n - configured} fallback (the lake's layout puts them).",
        "",
        *RECIPE,
    ]
    for d in ls:
        tables = d.get("tables") or {}
        out += [f"## Lake `{d['lake']}`", "",
                f"File `{d['_file']}`; default format {reference(d.get('format') or 'delta')}; root `{reference(d.get('root') or '(a catalog)')}`.", ""]
        if not tables:
            out += ["No table is listed in the file: a table's entry is added from its pack the first time a writer meets its kind.", ""]
            continue
        out += ["| Symbolic name | What it holds | Key and grain | Main columns | Reference location | Format | Used by |",
                "|---|---|---|---|---|---|---|"]
        for name in sorted(tables):
            e = tables[name] or {}
            kind = e.get("kind") or name.split(".", 1)[1]
            c = cs.get(str(e.get("contract") or kind).split("@")[0]) or cs.get(str(kind))
            purpose, keygrain, cols = holds(c)
            if not keygrain and e.get("id"):
                keygrain = f"`{e['id']}`"
            loc, fmt, how = location(d, name, e)
            u = us.get((d["lake"], name), {})
            used = []
            for lab, key in (("packs", "packs"), ("modules", "modules"), ("engines", "engines")):
                if u.get(key):
                    used.append(f"{lab}: " + ", ".join(sorted(u[key])))
            row = [f"`{name}`", purpose.replace("|", "/") or "(no contract for this kind yet)", keygrain.replace("|", "/"), cols.replace("|", "/"),
                   f"`{loc}` ({how})", fmt, "; ".join(used) or "no connector or engine names it"]
            out.append("| " + " | ".join(row) + " |")
        out.append("")
    return "\n".join(out).rstrip() + "\n"


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="Write or check docs/admin/TABLE_CATALOGUE.md from the lake files.")
    ap.add_argument("--check", action="store_true", help="fail when the document is stale")
    a = ap.parse_args(argv)
    text = render()
    if a.check:
        if DOC.is_file() and DOC.read_text(encoding="utf-8") == text:
            print(f"{DOC.relative_to(ROOT)} is current")
            return 0
        print(f"{DOC.relative_to(ROOT)} is stale: run python3 tools/docs/table_catalogue.py", file=sys.stderr)
        return 1
    DOC.write_text(text, encoding="utf-8")
    print(f"wrote {DOC.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
