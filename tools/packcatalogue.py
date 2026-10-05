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

"""`drishti.py pack catalogue PACK`: a readable catalogue of a pack for business review (Markdown or one self-contained HTML page).

Per kind: its title, the About sentence, mnemonic, the glossary (term, meaning, unit, sign, notes, values) and the fields its Sutras show that no
glossary entry explains yet. Per Sutra: what it shows (description, the strip figures, the panels with their kind and title), and the rule that
selects it (match where, priority). `--shots` also renders every Sutra's samples with `sutra preview` (HTML snapshots, needs the jar) and links them.
"""
from __future__ import annotations

import html
import pathlib


class CatalogueError(Exception):
    def __init__(self, message: str, code: int = 2):
        super().__init__(message)
        self.code = code


def collect(pack: pathlib.Path, yaml) -> dict:
    def ld(p):
        return (yaml.safe_load(p.read_text(encoding="utf-8")) or {}) if p.is_file() else {}
    meta = ld(pack / "pack.yaml")
    if not meta:
        raise CatalogueError(f"{pack} is not a pack folder (no pack.yaml)")
    about = ld(pack / "config" / "about.yaml")
    kinds = {k: {"mnemonic": next((m for m, v in (meta.get("mnemonics") or {}).items() if v.get("kind") == k), ""),
                 "about": (about.get("kinds") or {}).get(k) or {}, "sutras": []} for k in meta.get("kinds") or []}
    for f in sorted(pack.glob(f"{meta.get('sutras', 'sutras')}/**/*.sutra.yaml")):
        d = ld(f)
        m = d.get("match") or {}
        kind = m.get("kind") or "?"
        shown = sorted({b[2:] for b in _binds(d) if b.startswith("$.")})
        kinds.setdefault(kind, {"mnemonic": "", "about": {}, "sutras": []})["sutras"].append({
            "name": d.get("sutra") or f.stem, "file": f.relative_to(pack).as_posix(), "description": d.get("description") or "", "where": m.get("where"),
            "priority": m.get("priority"), "strip": [s.get("label") for s in d.get("strip") or [] if isinstance(s, dict)],
            "panels": [{"id": p.get("id"), "kind": p.get("kind"), "title": p.get("title")} for p in d.get("panels") or [] if isinstance(p, dict)],
            "fields": shown})
    return {"meta": meta, "kinds": kinds}


def _binds(node):
    if isinstance(node, dict):
        for k, v in node.items():
            if k in ("bind", "id", "value", "x", "y") and isinstance(v, str):
                yield v
            else:
                yield from _binds(v)
    elif isinstance(node, list):
        for v in node:
            yield from _binds(v)


def _gloss_rows(about: dict) -> list[tuple]:
    rows = []
    for f, e in sorted((about.get("glossary") or {}).items()):
        if isinstance(e, dict) and "use" not in e:
            vals = "; ".join(f"{k}: {v}" for k, v in (e.get("values") or {}).items())
            rows.append((f, e.get("term") or f, e.get("means") or "", e.get("unit") or "", " ".join(x for x in (e.get("sign"), e.get("note"), vals) if x)))
        elif isinstance(e, dict):
            rows.append((f, e.get("use"), "(defined once in the pack's vocabulary)", "", ""))
    return rows


def render_md(c: dict, shots: dict) -> str:
    m = c["meta"]
    o = [f"# {m.get('title') or m.get('pack')}", "", f"Pack `{m.get('pack')}` version {m.get('version')}. {m.get('description') or ''}", "",
         f"{len(c['kinds'])} kind(s), {sum(len(k['sutras']) for k in c['kinds'].values())} Sutra(s). Text in `${{...}}` is filled in from the document when a screen is shown.", ""]
    for kind, k in sorted(c["kinds"].items()):
        a = k["about"]
        o += [f"## {a.get('title') or kind} (`{kind}`" + (f", mnemonic {k['mnemonic']}" if k["mnemonic"] else "") + ")", "", a.get("about") or "_No description yet._", ""]
        rows = _gloss_rows(a)
        if rows:
            o += ["### Fields and glossary", "", "| Field | Term | Meaning | Unit | Sign, notes |", "|---|---|---|---|---|"]
            o += ["| " + " | ".join(str(x).replace("|", "\\|").replace("\n", " ") for x in r) + " |" for r in rows] + [""]
        explained = {r[0] for r in rows}
        missing = sorted({f for s in k["sutras"] for f in s["fields"]} - explained)
        if missing:
            o += ["Shown but not yet explained: " + ", ".join(f"`{f}`" for f in missing[:40]) + (" ..." if len(missing) > 40 else ""), ""]
        o += [f"### Screens for {kind}", ""]
        for s in k["sutras"]:
            o += [f"#### {s['name']}", "", s["description"] or "", "",
                  f"- **Selected when:** {('`' + s['where'] + '`') if s['where'] else 'always (fallback)'}, priority {s['priority']}",
                  "- **Key figures:** " + (", ".join(s["strip"]) or "none")]
            o += ["- **Panels:** " + (", ".join(f"{p['title'] or p['id']} ({p['kind']})" for p in s["panels"]) or "none")]
            if s["name"] in shots:
                o += [f"- **Preview:** [{shots[s['name']]}]({shots[s['name']]})"]
            o += [""]
    return "\n".join(o) + "\n"


def render_html(c: dict, shots: dict) -> str:
    e = html.escape
    m = c["meta"]
    css = ("body{font:15px/1.5 system-ui,sans-serif;max-width:68rem;margin:2rem auto;padding:0 1rem;color:#1b1f24;background:#fff}"
           "table{border-collapse:collapse;width:100%;margin:.5rem 0}th,td{border:1px solid #ccd;padding:.3rem .5rem;text-align:left;vertical-align:top}"
           "th{background:#eef}code{background:#f3f3f8;padding:0 .2rem}.s{border-left:4px solid #88a;padding:.2rem 1rem;margin:1rem 0}"
           "@media(prefers-color-scheme:dark){body{background:#14161a;color:#e6e6ea}th{background:#25283a}code{background:#25283a}td,th{border-color:#444}}")
    o = [f"<!doctype html><html lang=en><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'><title>{e(str(m.get('title') or m.get('pack')))} catalogue</title><style>{css}</style>",
         f"<h1>{e(str(m.get('title') or m.get('pack')))}</h1><p>Pack <code>{e(str(m.get('pack')))}</code> version {e(str(m.get('version')))}. {e(str(m.get('description') or ''))}</p>"]
    for kind, k in sorted(c["kinds"].items()):
        a = k["about"]
        o.append(f"<h2>{e(str(a.get('title') or kind))} <small><code>{e(kind)}</code>{(' · ' + e(k['mnemonic'])) if k['mnemonic'] else ''}</small></h2><p>{e(str(a.get('about') or 'No description yet.'))}</p>")
        rows = _gloss_rows(a)
        if rows:
            o.append("<table><tr><th>Field<th>Term<th>Meaning<th>Unit<th>Sign, notes" + "".join("<tr>" + "".join(f"<td>{e(str(x))}" for x in r) for r in rows) + "</table>")
        explained = {r[0] for r in rows}
        missing = sorted({f for s in k["sutras"] for f in s["fields"]} - explained)
        if missing:
            o.append("<p><b>Shown but not yet explained:</b> " + ", ".join(f"<code>{e(f)}</code>" for f in missing[:40]) + "</p>")
        for s in k["sutras"]:
            panels = ", ".join("%s (%s)" % (p["title"] or p["id"], p["kind"]) for p in s["panels"])
            link = f" <a href='{e(shots[s['name']])}'>preview</a>" if s["name"] in shots else ""
            o.append(f"<div class=s><h3>{e(str(s['name']))}{link}</h3><p>{e(s['description'])}</p><ul><li><b>Selected when:</b> "
                     f"{('<code>' + e(s['where']) + '</code>') if s['where'] else 'always (fallback)'}, priority {e(str(s['priority']))}"
                     f"<li><b>Key figures:</b> {e(', '.join(s['strip']) or 'none')}"
                     f"<li><b>Panels:</b> {e(panels or 'none')}</ul></div>")
    return "\n".join(o) + "\n"


def run(a, cli) -> int:
    yaml = cli.need_yaml()
    pack = pathlib.Path(a.pack).resolve()
    c = collect(pack, yaml)
    out = pathlib.Path(a.out or pathlib.Path("build") / f"{pack.name}-catalogue").resolve()
    out.mkdir(parents=True, exist_ok=True)
    shots: dict[str, str] = {}
    if a.shots:
        pv = out / "previews"
        a.jar, a.java = cli.find_jar(a.jar), cli.find_java(a.java)
        r = cli.run_java(a, ["preview", str(pack), "--out", str(pv)], capture=True)
        if r.returncode:
            raise CatalogueError("sutra preview failed:\n" + (r.stdout or r.stderr or "")[-800:], 1)
        files = sorted(p for p in pv.rglob("*.html")) if pv.is_dir() else []
        for k in c["kinds"].values():
            for s in k["sutras"]:
                hit = next((p for p in files if str(s["name"]) in p.name), None)
                if hit:
                    shots[s["name"]] = hit.relative_to(out).as_posix()
    md = a.format == "md"
    target = out / ("catalogue.md" if md else "index.html")
    target.write_text(render_md(c, shots) if md else render_html(c, shots), encoding="utf-8")
    n_s = sum(len(k["sutras"]) for k in c["kinds"].values())
    rep = {"pack": str(pack), "file": str(target), "kinds": len(c["kinds"]), "sutras": n_s, "previews": len(shots)}
    if a.json:
        cli.say_json(rep)
    else:
        print(f"pack catalogue: {len(c['kinds'])} kind(s), {n_s} Sutra(s)" + (f", {len(shots)} preview(s)" if a.shots else "") + f" -> {target}")
    return 0
