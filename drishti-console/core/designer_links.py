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

"""Customize's "When I click a row in A show B" (SCREEN_DESIGNER.md section 31): what B can be, besides the row's details.

* **a linked record**: a field of the clicked row that names another entity (the packs' ``graph.fields``: ``COUNTERPARTY`` is a
  counterparty, ``TRADE_ID`` a trade) opens that record (``source: {kind, id: "@parent.open.F"}``), across packs;
* **a list inside it**: the linked record's own rows (a trade's cash flows), read from one sample of it;
* **the records that point at it**: kinds whose key columns name the same entity (a counterparty's credit limits:
  ``where: "@.COUNTERPARTY == @parent.open.COUNTERPARTY"``);
* **an existing panel of the page**, moved under the table as its child.

A choice travels as a ``spec`` (plain identifiers only, checked again here before any Sutra is written)."""
from __future__ import annotations

import re

IDENT = re.compile(r"[A-Za-z_]\w{0,63}")
KIND = re.compile(r"[a-z0-9-]{1,40}")
MAX_COLUMNS = 6


class LinkError(ValueError):
    """A choice that cannot be written, in plain words."""


def catalog(packs: list, overviews: dict) -> dict:
    """What the enabled packs know: the fields naming entities (``links``), the pack serving each kind, a kind's label and key columns."""
    links, pack_of, labels, columns = {}, {}, {}, {}
    for p in packs or []:
        if not isinstance(p, dict) or not p.get("active", True):
            continue
        for f, ref in (p.get("links") or {}).items():
            if isinstance(ref, dict) and ref.get("kind"):
                links.setdefault(f, {"kind": ref["kind"], "label": ref.get("label") or f})
        for k in p.get("kinds") or []:
            pack_of.setdefault(k, p.get("name"))
        for k in (overviews.get(p.get("name")) or {}).get("kinds") or []:
            if isinstance(k, dict) and k.get("kind"):
                labels.setdefault(k["kind"], k.get("label") or k["kind"])
                columns.setdefault(k["kind"], [c for c in k.get("columns") or [] if isinstance(c, str)])
    return {"links": links, "pack_of": pack_of, "labels": labels, "columns": columns}


def first_row(record, rows: str):
    """The first row a table's ``rows:`` reads from the record (``$.A``, ``$.A[*].B``, ``$.A[0].B``, ``flatten(...)``), or None."""
    text = re.sub(r"^\s*flatten\((.*)\)\s*$", r"\1", str(rows or ""))
    m = re.fullmatch(r"\s*\$((?:\.[A-Za-z_]\w*(?:\[(?:\*|\d+)\])?)*)\s*", text)
    if not m or record is None:
        return None
    cur = [record]
    for name, idx in re.findall(r"\.([A-Za-z_]\w*)(\[(?:\*|\d+)\])?", m.group(1)):
        nxt = []
        for c in cur:
            v = c.get(name) if isinstance(c, dict) else None
            if idx == "[*]" and isinstance(v, list):
                nxt += v
            elif idx and isinstance(v, list):
                nxt += v[int(idx[1:-1]):int(idx[1:-1]) + 1]
            elif v is not None:
                nxt.append(v)
        cur = nxt
    return next((r for c in cur for r in (c if isinstance(c, list) else [c]) if isinstance(r, dict)), None)


def _plain(field: str) -> str:
    return field.replace("_", " ").replace("-", " ").strip().lower()


def _plural(word: str) -> str:
    return word if word.endswith("s") else word + "s"


def _scalars(row) -> list:
    return [k for k, v in (row or {}).items() if not isinstance(v, (dict, list))][:MAX_COLUMNS]


def targets(row, cat: dict) -> list:
    """The fields of a row that name another entity: ``[(field, kind, label)]``, the row's own identity (``*_ID``) first, then by field name.
    Never by the row's own key order: rows of different sources order their fields differently, and the first row of a list is not always
    of the same source, so the choices (and which links are sampled) would change from one reading to the next."""
    out = [(f, cat["links"][f]["kind"], cat["links"][f]["label"]) for f, v in (row or {}).items()
           if f in cat["links"] and isinstance(v, str) and v]
    return sorted(out, key=lambda t: (not t[0].endswith("_ID"), t[0]))


def onward(samples: dict, cat: dict) -> list:
    """The records the samples name in turn (``[(kind, id)]``, kinds not sampled yet): read once each, so a choice can find the field of
    theirs that points back (a credit limit's ``COUNTERPARTY``)."""
    out = []
    for rec in samples.values():
        for f, v in (rec or {}).items() if isinstance(rec, dict) else []:
            k = (cat["links"].get(f) or {}).get("kind")
            if (k and isinstance(v, str) and v and k not in samples and cat["pack_of"].get(k) and all(k != o[0] for o in out)
                    and not f.endswith("_ID")):
                out.append((k, v))
    return out


def choices(row, cat: dict, samples: dict) -> list:
    """What a click on this row can show, in plain words, each with its ``spec``. ``samples`` holds one record of each linked kind
    (``{kind: record}``) for the lists inside it."""
    out = [{"label": "the row’s details", "spec": {"how": "row"}}]
    for f, kind, label in targets(row, cat):
        pack = cat["pack_of"].get(kind)
        if not pack:
            continue                                          # a kind no enabled pack serves: nothing to open
        own = f.endswith("_ID")
        whose = "its" if own else f"its {label.lower()}’s"
        if not own:
            out.append({"label": f"its {label.lower()} ({pack})", "spec": {"how": "one", "pack": pack, "kind": kind, "field": f}})
        else:
            out.append({"label": f"the {label.lower()} itself ({pack})", "spec": {"how": "one", "pack": pack, "kind": kind, "field": f}})
        sample = samples.get(kind)
        for lf, lv in (sample or {}).items() if isinstance(sample, dict) else []:
            if isinstance(lv, list) and lv and isinstance(lv[0], dict) and IDENT.fullmatch(lf):
                out.append({"label": f"{whose} {_plain(lf)}", "spec": {"how": "list", "pack": pack, "kind": kind, "field": f, "list": lf,
                                                                       "columns": _scalars(lv[0])}})
        for other in dict.fromkeys([*cat["columns"], *samples]):
            cols = cat["columns"].get(other) or []
            if other == kind or not cat["pack_of"].get(other):
                continue
            match = next((c for c in cols if (cat["links"].get(c) or {}).get("kind") == kind), None)
            back = samples.get(other) if isinstance(samples.get(other), dict) else None
            if not match and back:                            # its key columns do not say: a sample of it that names this record does
                match = next((c for c, v in back.items() if v == row.get(f) and IDENT.fullmatch(c)), None)
                cols = _scalars(back)
            if match:
                out.append({"label": f"{whose} {_plural(cat['labels'].get(other, other).lower())}",
                            "spec": {"how": "where", "pack": cat["pack_of"][other], "kind": other, "field": f, "match": match,
                                     "columns": [c for c in cols if IDENT.fullmatch(c)][:MAX_COLUMNS]}})
    seen, unique = set(), []
    for c in out:
        if c["label"] not in seen:
            seen.add(c["label"])
            unique.append(c)
    return unique[:16]


def _label(field: str) -> str:
    return field.replace("_", " ").strip().capitalize()


def _check(spec: dict) -> dict:
    if not isinstance(spec, dict) or spec.get("how") not in ("one", "list", "where"):
        raise LinkError("pick what to show from the list")
    if not (KIND.fullmatch(str(spec.get("kind") or "")) and KIND.fullmatch(str(spec.get("pack") or ""))
            and IDENT.fullmatch(str(spec.get("field") or ""))):
        raise LinkError("that linked record cannot be shown: pick again")
    for k in ("list", "match"):
        if k in spec and not IDENT.fullmatch(str(spec[k] or "")):
            raise LinkError("that linked record cannot be shown: pick again")
    spec["columns"] = [c for c in spec.get("columns") or [] if isinstance(c, str) and IDENT.fullmatch(c)][:MAX_COLUMNS]
    return spec


def child(spec: dict, pid: str) -> tuple[dict, str]:
    """The child group a linked choice writes (``source`` + one panel), and the row field the click must send."""
    spec = _check(spec)
    how, f, kind = spec.get("how"), spec.get("field"), spec.get("kind")
    words = _plain(kind)
    if how == "one":
        return ({"source": {"pack": spec["pack"], "kind": kind, "id": f"@parent.open.{f}"},
                 "panels": [{"use": "field-list@1", "with": {"id": f"{pid}-{kind}", "title": f"{words.capitalize()} ${{@parent.open.{f}}}", "rows": "$src"}}]},
                f)
    cols = [{"label": _label(c), "bind": f"@.{c}"} for c in spec["columns"]]
    if not cols:
        raise LinkError("those records have no fields to show")
    if how == "list":
        return ({"source": {"pack": spec["pack"], "kind": kind, "id": f"@parent.open.{f}"},
                 "panels": [{"id": f"{pid}-{_plain(spec['list']).replace(' ', '-')}", "kind": "table",
                             "title": f"{_plain(spec['list']).capitalize()} of ${{@parent.open.{f}}}", "rows": f"$src.{spec['list']}", "columns": cols}]},
                f)
    if how == "where":
        return ({"source": {"pack": spec["pack"], "kind": kind, "where": f"@.{spec['match']} == @parent.open.{f}", "limit": 20},
                 "panels": [{"id": f"{pid}-{kind}", "kind": "table", "title": f"{_plural(words).capitalize()} of ${{@parent.open.{f}}}",
                             "rows": "$src", "columns": cols}]},
                f)
    raise LinkError("pick what to show from the list")
