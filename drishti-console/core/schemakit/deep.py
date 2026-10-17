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

"""The deep design: a Sutra for a document known from its data whose arrays hold arrays (a deal's legs, each leg's cash flows, each
flow's resets, at any depth), read the way a desk reads a deal ticket. Generic: nothing here knows a trade; it reads the plan's fields
(path, role, fill, format) and the shape of the arrays (how many items they hold).

* the title names the product and the main party; the strip is the plan's ticket strip (status, side, size, product, who, when);
* ``details``: the document's own fields, those with values first, as a dense key-value block;
* an array of a few items that hold arrays of their own (legs): side by side (``tabs``, ``layout: columns``), each with its terms (its
  fields, the objects inside it flattened, the count and total of each array it holds) and a table of its first deeper array that drills
  into the levels below (``levels:``), with the breadcrumb from the document down;
* an array of many items that hold arrays: one table that drills level by level;
* an array of plain rows (events): a table, with "No <rows>" when the document has none (missing, null or empty);
* a dated array two levels down with a payment-like date: a timeline across all of it (``flatten``);
* pages for each value of a field (``related.py``): the field links to its page, and the page's other documents sit on the right.

``applies(kp)`` says whether a kind gets it; ``sutra(kp, sp, plan)`` writes the YAML (rachana 1)."""
from __future__ import annotations

import json
import re

from .plan import ID_NAME, KindPlan, Plan, SutraPlan, humanize, singular

TABS_MAX = 4                 # an array of at most this many items (and arrays inside) is shown side by side
TAB_MAX = 8                  # at most this many: tabs; more: one drill table
KV_MAX = 32
COLS_MAX = 9
TYPEISH = re.compile(r"(?i)(type|kind|side|direction|pay.?rec|buy.?sell|status|class|category)")
TOTALISH = re.compile(r"(?i)(notional|quantity|qty|amount|volume|pv|mtm|value|balance|exposure|cash|principal)")
UNITISH = re.compile(r"(?i)(^|_)(price_?unit|unit|uom)$|(Unit|Uom)$")
PAYDATE = re.compile(r"(?i)(pay(ment)?.?date|settle(ment)?.?date|value.?date)")
ROLE_W = {"status": 90, "id": 80, "link": 76, "dimension": 70, "date": 60, "datetime": 58, "number": 55, "text": 45, "flag": 30}


def q(s: str) -> str:
    return json.dumps(s, ensure_ascii=False)


def link_expr(value: str, kind: str) -> str:
    return "link(" + value + ", '" + kind + "')"


def call(fn: str, *args: str) -> str:
    return fn + "(" + ", ".join(args) + ")"


def lit(s: str) -> str:
    return "'" + s.replace("'", "\\'") + "'"


# ----------------------------------------------------------------------------------------------- reading the plan's tree

def _vis(f) -> bool:
    return not f.hidden and f.fill > 0 and f.role not in ("object", "map", "list")


def direct(kp: KindPlan, prefix: str, objects: bool = True) -> list:
    """The scalar fields of one level (``prefix`` is "" or "LEGS[]."), with the fields of plain objects inside it (INDEX.NAME)."""
    out = []
    for f in kp.fields:
        if not f.path.startswith(prefix) or f.role == "table" or not _vis(f):
            continue
        rest = f.path[len(prefix):]
        if "[" in rest:
            continue
        if "." in rest:
            head = rest.split(".")[0]
            parent = kp.index.get(prefix + head)
            if not objects or rest.count(".") > 1 or parent is None or parent.role != "object":
                continue
        out.append(f)
    return out


def arrays(kp: KindPlan, prefix: str) -> list:
    """The arrays of objects directly at one level."""
    return [f for f in kp.fields if f.role == "table" and not f.hidden and f.fill > 0 and f.path.startswith(prefix) and "[" not in f.path[len(prefix):]
            and "." not in f.path[len(prefix):]]


def items(kp: KindPlan, f) -> dict:
    """How many items the array holds in the samples: {min, max, avg, absent, empty} (the inferred node keeps it)."""
    node = kp.node
    for part in f.path.split("."):
        arr = part.endswith("[]")
        node = node.props.get(part[:-2] if arr else part) if node is not None else None
        if node is not None and arr:
            node = node.items
    return (node.ann.get("items") if node is not None else None) or {"min": 0, "max": 0, "avg": 0}


def depth_below(kp: KindPlan, f) -> int:
    """How many levels of arrays sit under the items of array ``f`` (resets under flows under legs: 2 for the legs)."""
    subs = arrays(kp, f.path + "[].")
    return 1 + max((depth_below(kp, s) for s in subs), default=0) if subs else 0


def rank(f) -> tuple:
    w = ROLE_W.get(f.role, 40) + (10 if TYPEISH.search(f.name) and f.role in ("dimension", "text", "status") else 0) \
        + (8 if f.role == "number" and TOTALISH.search(f.name) else 0)
    return (-(w + 20 * f.fill),)


def ordered(fields: list, n: int) -> list:
    """The ``n`` most telling fields, shown in a reading order: ids and words, then dates, then numbers (document order within)."""
    best = sorted(fields, key=rank)[:n]
    group = {"id": 0, "status": 1, "link": 1, "dimension": 1, "text": 1, "flag": 2, "date": 3, "datetime": 3, "number": 4}
    return sorted(best, key=lambda f: (group.get(f.role, 2), fields.index(f)))


def rel(f, prefix: str) -> str:
    return f.path[len(prefix):]


def fmt_of(f) -> str:
    if f.role in ("date", "datetime"):
        return "date"
    if f.role == "number":
        return f.fmt or ("" if f.type == "integer" else "amount2")
    return ""


def label_for(f, prefix: str) -> str:
    """A field's label; a field of an object inside the row reads with the object's name (INDEX.NAME: Index name)."""
    path = f.path[len(prefix):]
    if "." in path:
        head = path.split(".")[0]
        h = humanize(head.lower() if head.isupper() else head)
        return f"{h} {f.label[:1].lower() + f.label[1:]}" if not f.label.lower().startswith(h.lower()) else f.label
    return f.label


def column(f, prefix: str, at: str, total: bool = False, related: dict | None = None, label: str | None = None) -> str:
    """One column or kv field: ``{ label, bind, fmt, tone, total }`` reading ``at`` + the field's path below ``prefix``."""
    path = rel(f, prefix)
    bind = f"{at}.{path}"
    rk = (related or {}).get(f.path)
    if rk:
        bind = f"{at}.{path} != '' ? link({at}.{path}, '{rk}') : null" if f.fill < 1 else f"link({at}.{path}, '{rk}')"
    parts = [f"label: {q(label or label_for(f, prefix))}", f"bind: {q(bind)}"]
    fm = fmt_of(f)
    if fm:
        parts.append(f"fmt: {q(fm)}")
    if f.role == "status":
        parts.append('tone: "status"')
    elif fm.startswith("signed"):
        parts.append('tone: "sign"')
    if total and f.role == "number" and TOTALISH.search(f.name) and not ID_NAME.search(f.name):
        parts.append("total: true")
    return "{ " + ", ".join(parts) + " }"


def label_of_array(f) -> str:
    return f.label


def noun(f) -> str:
    """One item of the array, in words: LEGS -> Leg, CASH_FLOWS -> Cash flow, couponPeriods -> Coupon period."""
    s = singular(f.name)
    return humanize(s.lower() if s.isupper() else s) or "Row"


def crumb(kp: KindPlan, arr, at: str = "@") -> str:
    """An item's name for tab titles and the breadcrumb: 'Leg ' + @.LEG_ID + ' · ' + @.LEG_TYPE (the type only where it has one)."""
    prefix = arr.path + "[]."
    fs = direct(kp, prefix, objects=False)
    ident = next((f for f in fs if f.role == "id" and f.fill >= 0.9), None)
    kind = next((f for f in sorted(fs, key=lambda f: fs.index(f)) if TYPEISH.search(f.name) and f.role in ("dimension", "text", "status")
                 and f.fill >= 0.5 and not re.search(r"(?i)(economic|flow_type)$", f.name)), None)
    head = f"'{noun(arr)} ' + " + (f"{at}.{rel(ident, prefix)}" if ident else "(#index + 1)")
    if kind:
        k = f"{at}.{rel(kind, prefix)}"
        head += f" + ({k} != null && {k} != '' ? ' · ' + {k} : '')" if kind.fill < 1 else f" + ' · ' + {k}"
    return head


def unit_bind(kp: KindPlan, prefix: str, at: str):
    """A unit field beside a quantity (PRICE_UNIT, uom): its bind at this level, or None."""
    f = next((f for f in direct(kp, prefix, objects=False) if UNITISH.search(f.name) and f.fill >= 0.5), None)
    return f"{at}.{rel(f, prefix)}" if f else None


# ----------------------------------------------------------------------------------------------- the panels

def level_spec(kp: KindPlan, arr, related: dict) -> tuple[list, str, str]:
    """(columns, crumb, title) of one level of a drill table: the array's rows read with @."""
    prefix = arr.path + "[]."
    fs = ordered(direct(kp, prefix), COLS_MAX)
    cols = [column(f, prefix, "@", True, related) for f in fs]
    for sub in arrays(kp, prefix):                       # how many rows each row holds, as a column the eye can scan
        cols.append(f"{{ label: {q(sub.label)}, bind: {q(f'size(@.{rel(sub, prefix)})')}, fmt: \"amount0\" }}")
    return cols[:COLS_MAX + 2], crumb(kp, arr), arr.label


def chain(kp: KindPlan, arr) -> list:
    """The arrays below ``arr``, one per level, following the biggest array at each level (cash flows, then resets)."""
    out = []
    cur = arr
    while True:
        subs = arrays(kp, cur.path + "[].")
        if not subs:
            return out
        cur = max(subs, key=lambda s: (items(kp, s).get("avg", 0), -kp.fields.index(s)))
        out.append(cur)


def drill_table(kp: KindPlan, pid: str, title: str, rows: str, top, levels: list, related: dict, indent: str, root: str,
                key: str = "", at_rows: bool = False) -> list:
    """A table over ``top``'s rows that drills into ``levels`` (lists of arrays, outermost first)."""
    cols, crumb_e, _ = level_spec(kp, top, related)
    L = [f"{indent}- id: {pid}", f"{indent}  kind: table", f"{indent}  title: {q(title)}"]
    if key:
        L.append(f"{indent}  key: {q(key)}")
    L += [f"{indent}  rows: {q(rows)}", f"{indent}  crumb: {q(crumb_e)}", f"{indent}  root: {q(root)}",
          f"{indent}  empty: {q('No ' + top.label.lower())}", f"{indent}  columns:"]
    L += [f"{indent}    - {c}" for c in cols]
    if levels:
        L.append(f"{indent}  levels:")
        for lv in levels:
            lcols, lcrumb, ltitle = level_spec(kp, lv, related)
            L.append(f"{indent}    - rows: {q('@.' + lv.name)}")
            L.append(f"{indent}      title: {q(ltitle)}")
            L.append(f"{indent}      crumb: {q(lcrumb)}")
            L.append(f"{indent}      columns:")
            L += [f"{indent}        - {c}" for c in lcols]
    return L


def terms_columns(kp: KindPlan, arr, related: dict) -> list:
    """The kv of one item: its fields (objects flattened), a unit beside each quantity, the count and total of each array it holds."""
    prefix = arr.path + "[]."
    fs = ordered(direct(kp, prefix), 14)
    unit = unit_bind(kp, prefix, "@")
    out = []
    for f in fs:
        if unit and f.role == "number" and TOTALISH.search(f.name):
            bind = call("fmt", "@." + rel(f, prefix), lit("amount0")) + " + ' ' + " + unit
            out.append(f"{{ label: {q(f.label)}, bind: {q(bind)} }}")
        else:
            out.append(column(f, prefix, "@", False, related))
    for sub in arrays(kp, prefix):
        out.append(f"{{ label: {q(sub.label)}, bind: {q(call('size', '@.' + sub.name))}, fmt: \"amount0\" }}")
        sp = sub.path + "[]."
        for g in [g for g in direct(kp, sp, objects=False) if g.role == "number" and TOTALISH.search(g.name) and not ID_NAME.search(g.name)][:1]:
            out.append(f"{{ label: {q(g.label + ' (' + sub.label.lower() + ', total)')}, bind: {q(call('sum', '@.' + sub.name, lit(g.name)))}, fmt: \"amount0\" }}")
    return out


def related_map(kp: KindPlan) -> dict:
    return {r["path"]: r["kind"] for r in kp.related}


def deep_panels(kp: KindPlan, plan: Plan | None) -> list:
    related = related_map(kp)
    root_crumb = f"'{kp.title} ' + $.{kp.key}" if kp.key else f"'{kp.title}'"
    L = ["panels:"]
    tops = ordered(direct(kp, ""), KV_MAX)
    L += ["  - id: details", "    kind: kv", f"    title: {q(kp.title)}", '    key: "F2"', "    columns:"]
    L += [f"      - {column(f, '', '$', False, related)}" for f in tops]
    fkey = 3
    flat = []
    for arr in sorted(arrays(kp, ""), key=lambda a: (-depth_below(kp, a), kp.fields.index(a))):
        pid = re.sub(r"[^a-z0-9]+", "-", arr.name.lower()).strip("-") or "rows"
        key = f"F{fkey}" if fkey <= 8 else ""
        fkey += 1
        below = depth_below(kp, arr)
        n = items(kp, arr)
        if below == 0:
            flat.append((arr, pid, key))
            continue
        if n.get("max", 0) <= TAB_MAX:
            L += [f"  - id: {pid}", "    kind: tabs", f"    title: {q(arr.label)}"]
            if key:
                L.append(f"    key: {q(key)}")
            L += [f"    each: {q('$.' + arr.name)}"]
            if n.get("max", 0) <= TABS_MAX:
                L.append("    layout: columns")
            L += [f"    tabTitle: {q(crumb(kp, arr))}", "    panels:", f"      - id: {pid}-terms", "        kind: kv", '        title: "Terms"', "        columns:"]
            L += [f"          - {c}" for c in terms_columns(kp, arr, related)]
            levels = chain(kp, arr)
            if levels:
                # the schedule of each item, a whole row wide (one item at a time: schedules side by side would not fit), drilling below
                first = levels[0]
                sid = f"{pid}-{re.sub(r'[^a-z0-9]+', '-', first.name.lower()).strip('-')}"
                key2 = f"F{fkey}" if fkey <= 8 else ""
                fkey += 1
                L += [f"  - id: {sid}", "    kind: tabs", f"    title: {q(first.label + ' by ' + noun(arr).lower())}"]
                if key2:
                    L.append(f"    key: {q(key2)}")
                L += [f"    each: {q('$.' + arr.name)}", f"    tabTitle: {q(crumb(kp, arr))}", "    panels:"]
                L += drill_table(kp, f"{sid}-rows", first.label, "@." + first.name, first, levels[1:], related, "      ",
                                 f"{root_crumb} + ' › ' + {crumb(kp, arr)}")
        else:
            L += drill_table(kp, pid, arr.label, "$." + arr.name, arr, chain(kp, arr), related, "  ", root_crumb, key)
    for arr, pid, key in flat:
        prefix = arr.path + "[]."
        cols = [column(f, prefix, "@", True, related) for f in ordered(direct(kp, prefix), COLS_MAX)]
        if not cols:
            continue
        L += [f"  - id: {pid}", "    kind: table", f"    title: {q(arr.label)}"]
        if key:
            L.append(f"    key: {q(key)}")
        L += [f"    rows: {q('$.' + arr.name)}", f"    empty: {q('No ' + arr.label.lower())}", "    columns:"]
        L += [f"      - {c}" for c in cols]
    L += timeline(kp)
    L += right_panels(kp, plan)
    L += ["  - { id: refs, kind: links, title: Linked entities, area: right }", "  - { id: built, kind: provenance, title: How this view was built }"]
    return L


def timeline(kp: KindPlan) -> list:
    """Every payment two levels down (each leg's cash flows), in date order: flatten() across the items of the first level."""
    for arr in arrays(kp, ""):
        for sub in arrays(kp, arr.path + "[]."):
            sp = sub.path + "[]."
            fs = direct(kp, sp, objects=False)
            date = next((f for f in fs if f.role == "date" and PAYDATE.search(f.name) and f.fill >= 0.8), None)
            if not date:
                continue
            label = next((f for f in fs if f.role == "id" and f.fill >= 0.9), None)
            detail = next((f for f in fs if f.role == "number" and TOTALISH.search(f.name)), None)
            L = ["  - id: payments", "    kind: timeline", f"    title: {q(date.label + 's, every ' + arr.label.lower()[:-1] if arr.label.lower().endswith('s') else date.label)}",
                 f"    rows: {q(f'flatten($.{arr.name}[*].{sub.name})')}", f"    date: {date.name}"]
            if label:
                L.append(f"    label: {label.name}")
            if detail:
                L.append(f"    detail: {detail.name}")
            L.append("    area: right")
            return L
    return []


def right_panels(kp: KindPlan, plan: Plan | None) -> list:
    """For the first page-per-value with a top-level field: the other documents of that page, linked."""
    L = []
    for r in kp.related:
        if "[" in r["path"]:
            continue
        d = plan.kind(r["kind"]) if plan is not None else None
        rows = (d.derived.get("rows") if d is not None else None) or {kp.key: f"$.{kp.key}"}
        cols = [f"{{ label: {q(kp.index[kp.key].label if kp.key in kp.index else kp.key)}, bind: {q(link_expr('@.' + kp.key, kp.kind))} }}"]
        for c in [c for c in rows if c != kp.key][:3]:
            f = kp.index.get(c)
            fm = fmt_of(f) if f is not None else ""
            cols.append(f"{{ label: {q(f.label if f is not None else humanize(c))}, bind: {q('@.' + c)}" + (f", fmt: {q(fm)}" if fm else "") + " }")
        source = link_expr("$." + r["path"], r["kind"])
        L += [f"  - id: same-{re.sub(r'[^a-z0-9]+', '-', r['label'].lower()).strip('-')}", "    kind: table",
              f"    title: {q('Same ' + r['label'].lower())}", "    area: right", f"    source: {q(source)}",
              '    rows: "$.rows"', "    limit: 12", f"    empty: {q('No other ' + kp.title.lower())}", "    columns:"]
        L += [f"      - {c}" for c in cols]
        break
    return L


# ----------------------------------------------------------------------------------------------- the derived pages

def derived_panels(kp: KindPlan, plan: Plan | None) -> list:
    d = kp.derived
    parent = plan.kind(d["from"]) if plan is not None else None
    L = ["panels:", "  - id: summary", "    kind: kv", f"    title: {q(d.get('label', kp.title))}", '    key: "F2"', "    columns:"]
    L.append('      - { label: "Documents", bind: "$.memberCount", fmt: "amount0" }')
    for name, agg in d["fields"].items():
        if name == "count":
            continue
        fm = ', fmt: "amount0"' if agg.startswith("sum") else ""
        L.append(f"      - {{ label: {q(humanize(name))}, bind: {q('$.' + name)}{fm} }}")
    pkey = parent.key if parent is not None else None
    L += ["  - id: members", "    kind: table", f"    title: {q(parent.title if parent is not None else 'Documents')}", '    key: "F3"', '    rows: "$.rows"',
          f"    empty: {q('No ' + (parent.title.lower() if parent is not None else 'documents'))}", "    columns:"]
    for c in d["rows"]:
        f = parent.index.get(c) if parent is not None else None
        label = f.label if f is not None else humanize(c)
        if c == pkey:
            L.append(f"      - {{ label: {q(label)}, bind: {q(link_expr('@.' + c, d['from']))} }}")
            continue
        fm = fmt_of(f) if f is not None else ""
        tot = ", total: true" if f is not None and f.role == "number" and TOTALISH.search(f.name) else ""
        tone = ', tone: "status"' if f is not None and f.role == "status" else ""
        L.append(f"      - {{ label: {q(label)}, bind: {q('@.' + c)}" + (f", fmt: {q(fm)}" if fm else "") + tone + tot + " }")
    L += ["  - { id: refs, kind: links, title: Linked entities, area: right }", "  - { id: built, kind: provenance, title: How this view was built }"]
    return L


# ----------------------------------------------------------------------------------------------- the Sutra

def applies(kp: KindPlan) -> bool:
    """A kind known from its data (no schema) whose documents hold arrays of objects, or a page per value."""
    if kp.derived:
        return True
    return kp.real and not kp.source and kp.node is not None and any(f.role == "table" and "[" not in f.path for f in kp.fields)


def title_line(kp: KindPlan) -> str:
    prod = next((kp.index[p] for p in kp.strip if p in kp.index and re.search(r"(?i)(product|instrument|type)", kp.index[p].name)
                 and kp.index[p].role in ("dimension", "text")), None)
    pill = f"{kp.title} · ${{$.{prod.path}}}" if prod else kp.title
    if kp.derived:
        pill = kp.derived.get("label", kp.title)
    parts = [f"pill: {q(pill)}", f"id: {q('$.' + (kp.key or 'id'))}"]
    who = next((r for r in kp.related if "[" not in r["path"] and re.search(r"(?i)(counterpart|cpty|client|customer|issuer)", r["path"])), None)
    if who:
        parts.append(f"with: {q(link_expr('$.' + who['path'], who['kind']))}")
    return "title: { " + ", ".join(parts) + " }"


def typed(kp: KindPlan, sp: SutraPlan) -> KindPlan:
    """The kind as one type's Sutra sees it: each field's fill and each array's size from that type's own rows (its profile from the
    browser, else its sample documents), so a comswap's view shows what comswaps fill and a future's what futures fill."""
    if not sp.values or kp.derived:
        return kp
    from .plan import apply_profile, node_from_docs
    import copy
    view = copy.copy(kp)
    view.fields = [copy.copy(f) for f in kp.fields]
    view.index = {f.path: f for f in view.fields}
    docs = [d for d in kp.samples if all(d.get(f) == v for f, v in sp.values.items())]
    view.samples = docs or kp.samples
    prof = (kp.profile or {}).get("types", {}) if len(sp.values) == 1 and (kp.profile or {}).get("by") in sp.values else {}
    tp = prof.get(str(next(iter(sp.values.values())))) if prof else None
    if docs:
        view.node = node_from_docs(docs)
        fills: dict = {}

        def walk(n, prefix):
            for name, c in n.props.items():
                fills[prefix + name] = c.ann.get("fill", 0)
                if c.type == "array" and c.items is not None and c.items.props:
                    walk(c.items, prefix + name + "[].")
                elif c.props:
                    walk(c, prefix + name + ".")
        walk(view.node, "")
        for f in view.fields:
            f.fill = float(fills.get(f.path, 0))
            f.hidden = f.hidden or f.fill == 0
    if tp:
        apply_profile(view, tp)
    return view


def sutra(kp: KindPlan, sp: SutraPlan, plan: Plan | None, strip_lines: list) -> str:
    """The whole Sutra text (header lines are set again by decorate.header)."""
    kp = typed(kp, sp)
    where = f"where: {q(sp.where)}, " if sp.where else ""
    L = ["rachana: 1", f"sutra: {sp.name}", "version: 1", f"description: {q(kp.description or kp.title)}",
         f"match: {{ kind: {kp.kind}, {where}priority: {sp.priority} }}", title_line(kp)]
    if strip_lines:
        L.append("strip:")
        L += strip_lines
    L += derived_panels(kp, plan) if kp.derived else deep_panels(kp, plan)
    L.append("keys: { F9: raw }")
    return "\n".join(L) + "\n"
