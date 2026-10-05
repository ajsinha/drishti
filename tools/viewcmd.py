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

"""`drishti.py view get|explain KIND/ID`: a rendered entity view and its About answer, as text in the terminal.

The server's own answers (/api/v1/views/{kind}/{id} and /explain) for the caller's token, so field masks and entitlements
apply exactly as in the browser. Panels are drawn by the shape of their data, not by kind name: key/value fields, tables and
ladders, timelines, waterfalls and bars, series, graphs, links; anything else is shown as compact JSON.
"""
from __future__ import annotations

import json
import urllib.parse


def q(s: str) -> str:
    return urllib.parse.quote(s, safe="")


def split_ref(ref: str) -> tuple[str, str]:
    kind, sep, doc = ref.partition("/")
    if not sep or not kind or not doc:
        raise ValueError(f"expected KIND/ID (for example trade/BBG-60000001), got {ref!r}")
    return kind, doc


def path_for(ref: str, date: str | None, suffix: str = "", panel: str | None = None) -> str:
    kind, doc = split_ref(ref)
    params = [f"{k}={q(v)}" for k, v in (("asOf", date), ("panel", panel)) if v]
    return f"/api/v1/views/{q(kind)}/{q(doc)}{suffix}" + ("?" + "&".join(params) if params else "")


def table(headers: list[str], rows: list[list], say, indent: str = "  ", right: set[int] = frozenset(), width: int = 60) -> None:
    cells = [[str(c if c is not None else "")[:width] for c in r] for r in rows]
    w = [max([len(h)] + [len(r[i]) for r in cells if i < len(r)]) for i, h in enumerate(headers)]
    fmt = lambda r: indent + "  ".join((r[i].rjust(w[i]) if i in right else r[i].ljust(w[i])) if i < len(r) else " " * w[i] for i in range(len(headers))).rstrip()   # noqa: E731
    say(fmt(headers))
    say(indent + "  ".join("-" * x for x in w))
    for r in cells:
        say(fmt(r))


def panel_text(p: dict, say, rows_max: int = 15) -> None:
    d = p.get("data") or {}
    if p.get("empty"):
        say("  (empty)")
    elif "fields" in d:
        table(["field", "value"], [[f.get("label"), f.get("text")] for f in d["fields"]], say)
    elif "columns" in d and "rows" in d:
        rows = d["rows"]
        num = {i for i, n in enumerate(d.get("numeric") or []) if n}
        table(d["columns"], [[c.get("text") for c in (r.get("cells") or [])] for r in rows[:rows_max]], say, right=num)
        if len(rows) > rows_max:
            say(f"  ... {len(rows) - rows_max} more row(s)")
    elif "events" in d:
        table(["date", "event", "status", "detail"], [[e.get("date"), e.get("label"), e.get("status"), e.get("detail")] for e in d["events"][:rows_max]], say)
    elif "steps" in d:
        table(["step", "value"], [[s.get("label"), s.get("text")] for s in d["steps"]], say, right={1})
    elif "bars" in d:
        table(["bar", "value"], [[b.get("label"), b.get("text")] for b in d["bars"][:rows_max]], say, right={1})
    elif "x" in d and "series" in d:
        rows = []
        for s in d["series"]:
            vals = [v for v in s.get("values", []) if isinstance(v, (int, float))]
            rows.append([s.get("label"), len(vals), vals[0] if vals else "", vals[-1] if vals else "", min(vals) if vals else "", max(vals) if vals else ""])
        table(["series", "points", "first", "last", "min", "max"], rows, say, right={1, 2, 3, 4, 5})
        say(f"  x: {d['x'][0] if d['x'] else ''} .. {d['x'][-1] if d['x'] else ''}")
    elif "nodes" in d:
        table(["node", "group", "focus"], [[n.get("label"), n.get("group"), "*" if n.get("focus") else ""] for n in d["nodes"][:rows_max]], say)
        say(f"  {len(d['nodes'])} node(s), {len(d.get('edges') or [])} edge(s)")
    elif "links" in d:
        table(["link", "text", "status", "opens"], [[x.get("label"), x.get("text"), x.get("status"),
                                                       f"{x['link']['kind']}/{x['link']['id']}" if x.get("link") else ""] for x in d["links"]], say)
    else:
        say("  " + json.dumps(d, ensure_ascii=False)[:300])


def render_view(v: dict, say=print) -> None:
    t = v.get("title") or {}
    say(f"{t.get('pill') or v['ref']['kind']}  {t.get('id') or v['ref']['id']}" + (f"  with {t['with']['text']}" if t.get("with") else "")
        + f"   [{v.get('mnemonic')}]")
    if v.get("strip"):
        say("\nKey figures")
        table(["figure", "value"], [[s.get("label"), s.get("text")] for s in v["strip"]], say)
    for p in v.get("panels") or []:
        say(f"\n{p.get('title') or p['id']}  ({p['kind']}, {p['id']}{', ' + p['key'] if p.get('key') else ''})")
        panel_text(p, say)
    pr, tm = v.get("provenance") or {}, v.get("timings") or {}
    say(f"\nsource {pr.get('source')} gen {pr.get('generation')}  layout {pr.get('layout')}  built in {tm.get('total', '?')} ms")


def render_explain(c: dict, say=print) -> None:
    a = c.get("about") or {}
    say(f"What you are looking at: {a.get('kindTitle') or c['ref']['kind']} {c['ref']['id']}"
        + (f"  (pack {a['pack']['title']})" if a.get("pack") else ""))
    if a.get("text"):
        say("  " + a["text"])
    if a.get("sutraDescription"):
        say("  " + a["sutraDescription"])
    g = c.get("glossary") or []
    if g:
        say("\nGlossary")
        table(["field", "shown in", "means"], [[x.get("label") or x.get("term"), ",".join(x.get("shownIn") or []), x.get("means")] for x in g], say, width=90)
    d = c.get("data") or {}
    say("\nWhere the data came from")
    table(["item", "value"], [[k, d.get(k)] for k in ("source", "generation", "fetchedAt", "updatedAt", "current", "live", "stale", "health") if k in d], say)
    lay = c.get("layout") or {}
    s = lay.get("sutra") or {}
    say(f"\nWhy this layout: {lay.get('label')}" + (" (a Sutra plus inference for what it left out)" if lay.get("inferred") else ""))
    if s:
        say(f"  Sutra {s.get('name')} v{s.get('version')} of pack {s.get('pack')}, priority {s.get('priority')}, where {s.get('where') or '(no where: matches every document of the kind)'}")
    cands = lay.get("candidates") or []
    if cands:
        chosen = next((x for x in cands if x.get("chosen")), None)
        others = [x for x in cands if not x.get("chosen")]
        won = [x for x in others if str(x.get("result")).lower() == "true"]
        say(f"  Match trace: {chosen['name']} (priority {chosen.get('priority')}) is the chosen Sutra; {len(others)} other Sutra(s) were tried and "
            f"{'did not match' if not won else str(len(won)) + ' matched too, at a lower priority'} (first 10 shown)" if chosen else
            f"  Match trace: no Sutra matched; {len(others)} were tried (first 10 shown)")
        table(["sutra", "priority", "where", "result"],
              [[("* " if x.get("chosen") else "") + str(x.get("name")), x.get("priority"), x.get("where"), x.get("result")]
               for x in (([chosen] if chosen else []) + won + [x for x in others if x not in won])[:10]], say, width=70)
    keys = (c.get("next") or {}).get("keys") or []
    if keys:
        say("\nNext: " + "; ".join(f"{k['key']} {k['label']}" for k in keys))
