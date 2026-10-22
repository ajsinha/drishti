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

"""The stand-in server of the chip designer's tests (UI Board P4b), on top of ui_board_backend.py: drafts usable on the author's boards, the chip
lifecycle with four eyes (submit runs a small publish check; approve by anyone but the author; publish; certify), versions and where-used,
``POST /chips/check`` (CP-01, CP-02, CP-03, CP-07 and the compatibility against the latest released version, with P2's answer shape),
``POST /convert/sutra`` and ``/convert/board`` with P5's answer shapes, and ``GET /candidates``. Synthetic data only."""
from __future__ import annotations

import json
import re

import yaml

from core.backend import BackendError
from ui_board_backend import UiBoardBackend

MANUAL_KEYS = ("purpose", "pins", "behaviour", "defaults", "limits", "examples", "not-for", "accessibility")
CANDIDATE = {"rachana": 1, "chip": "local-limits", "version": 1, "title": "limits", "category": "local",
             "manual": {"purpose": "Panel 'limits' of the page book, converted as it was.", "pins": {"doc": "The record."}, "behaviour": "", "defaults": "",
                        "limits": "", "examples": [], "not-for": "", "accessibility": ""},
             "pins": {"in": {"doc": {"role": "data", "type": "document", "required": True}}, "out": {}},
             "body": {"panels": [{"id": "limits", "kind": "table", "title": "Limits", "rows": "@doc.LIMITS",
                                 "columns": [{"label": "Limit", "bind": "@.NAME"}, {"label": "Used", "bind": "@.USED"}]}]}}


def _ins(d):
    return ((d or {}).get("pins") or {}).get("in") or {}


def _outs(d):
    return ((d or {}).get("pins") or {}).get("out") or {}


class UiChipBackend(UiBoardBackend):
    """Drafts, versions and the lifecycle of chips, and the converter's answers, as P2 and P5 give them."""

    def __init__(self):
        super().__init__()
        self.versions: dict[str, dict[int, dict]] = {}       # chip -> version -> {state, author, reviewer, doc}
        self.draft_owner: dict[str, str] = {}
        self.acting = None                                       # the tests' second person (a reviewer): every call is made as them while set

    def check(self, doc: dict) -> list[dict]:
        """The board check, with the board's own local chips (P5's converted boards) beside the store's."""
        saved = self.chips
        self.chips = {**saved, **{f"{c.get('chip')}@{c.get('version')}": c for c in doc.get("local-chips") or [] if isinstance(c, dict)}}
        try:
            return super().check(doc)
        finally:
            self.chips = saved

    # ---- the publish check, small -----------------------------------------------------------------------------------------------------------
    def chip_check(self, doc: dict) -> list[dict]:
        p = []
        err = lambda rule, path, msg: p.append({"rule": rule, "level": "error", "path": path, "message": msg, "fix": None})  # noqa: E731
        m = doc.get("manual") or {}
        for k in MANUAL_KEYS:
            if not m.get(k):
                err("CP-01", f"manual.{k}", f"the manual has no '{k}'")
        for pin in list(_ins(doc)) + list(_outs(doc)):
            if not (m.get("pins") or {}).get(pin):
                err("CP-01", f"manual.pins.{pin}", f"the manual does not say what pin '{pin}' means")
        body = doc.get("body") or {}
        text = json.dumps({k: v for k, v in body.items() if k != "board"})
        for x in re.findall(r"%\{([A-Za-z][\w-]*)", text):
            if x not in _ins(doc) and x != "title":
                err("CP-02", "body", f"%{{{x}}} names no in pin")
        for x in re.findall(r"(?<![\w@.])@([A-Za-z][\w-]*)", text):
            if x not in _ins(doc) and x not in ("name", "value", "filtered", "row", "index", "count", "total"):
                err("CP-02", "body", f"@{x} names no in pin")
        emitted = set(re.findall(r'"emits": "([\w-]+)"', text)) | set((body.get("exposes") or {}).keys())
        for o in _outs(doc):
            if o not in emitted:
                err("CP-03", f"pins.out.{o}", f"out pin '{o}' is never driven by the body")
        board = body.get("board")
        if isinstance(board, dict):
            fed = {t.get("to") for t in board.get("traces") or []} | set((body.get("wires") or {}).values())
            for inst, i in (board.get("chips") or {}).items():
                if i.get("supply"):
                    err("CP-07", f"body.board.chips.{inst}.supply", f"a composite chip has no connector: {inst} names a supply, but data enters a chip only through its pins")
                c = self.chips.get(str(i.get("use"))) or {}
                for pin, s in _ins(c).items():
                    if s.get("required") and s.get("default") is None and f"{inst}.{pin}" not in fed and pin not in (i.get("controls") or {}):
                        err("CP-07", f"body.board.chips.{inst}.{pin}", f"the required pin {inst}.{pin} is not fed")
        return p

    def _latest_released(self, name: str, below: int):
        vs = [n for n, v in (self.versions.get(name) or {}).items() if n < below and v["state"] in ("published", "certified")]
        return max(vs) if vs else None

    def _where_used(self, name: str) -> list[dict]:
        out = {}
        for b, rec in self.boards.items():
            for i in (rec["doc"].get("chips") or {}).values():
                n, _, v = str(i.get("use")).partition("@")
                if n == name:
                    out.setdefault(int(v), []).append(f"user:{rec['doc'].get('owner', 'ann')}:{b}")
        return [{"version": v, "boards": bs, "others": 0} for v, bs in sorted(out.items())]

    # ---- the routes -------------------------------------------------------------------------------------------------------------------------
    async def ui(self, method, path, ident, content=None, content_type=None, headers=None, wait=None, **params):
        parts = [x for x in path.split("/") if x]
        if self.acting:
            from types import SimpleNamespace
            ident = SimpleNamespace(user=self.acting, token=getattr(ident, "token", None))
        if parts[:1] in (["convert"], ["candidates"]) or parts == ["chips", "check"]:
            self.calls.append(("ui", method, path, params))
        if parts == ["chips", "check"]:
            doc = self._doc(content)
            probs = self.chip_check(doc)
            name, n = str(doc.get("chip") or ""), doc.get("version") if isinstance(doc.get("version"), int) else 1
            out = {"problems": probs, "whereUsed": self._where_used(name)}
            prev = self._latest_released(name, n)
            if prev:
                old = self.versions[name][prev]["doc"]
                breaking = [f"in pin {k} was removed" for k in _ins(old) if k not in _ins(doc)]
                breaking += [f"in pin {k} changed type" for k in _ins(old) if k in _ins(doc) and _ins(old)[k].get("type") != _ins(doc)[k].get("type")]
                breaking += [f"out pin {k} was removed" for k in _outs(old) if k not in _outs(doc)]
                breaking += [f"out pin {k} changed type" for k in _outs(old) if k in _outs(doc) and _outs(old)[k].get("type") != _outs(doc)[k].get("type")]
                claims = prev in (doc.get("compatible-with") or [])
                out.update(previous=prev, breaking=breaking, claimsCompatible=claims)
                if claims and breaking:
                    probs.append({"rule": "CP-06", "level": "error", "path": "compatible-with", "message": f"version {n} claims to be compatible with {prev} but breaks it", "fix": None})
            out["ok"] = not [x for x in probs if x["level"] == "error"]
            return 200, out, None
        if parts and parts[0] == "chips" and len(parts) == 3 and parts[2] == "draft" and method == "PUT":
            doc = self._doc(content)
            self.draft_owner.setdefault(parts[1], ident.user)
            if isinstance(doc.get("version"), int):
                self.chips[f"{parts[1]}@{doc['version']}"] = dict(doc, chip=parts[1])       # usable on the author's own boards
        if parts and parts[0] == "chips" and len(parts) == 2 and method == "GET" and parts[1] in self.versions:
            return 200, {"chip": parts[1], "versions": [{"chip": parts[1], "version": n, **{k: v[k] for k in ("state", "author", "reviewer")}}
                                                        for n, v in sorted(self.versions[parts[1]].items())]}, None
        if parts and parts[0] == "chips" and len(parts) == 3 and parts[2] == "where-used":
            return 200, {"chip": parts[1], "versions": self._where_used(parts[1])}, None
        if parts and parts[0] == "chips" and len(parts) == 4 and method == "POST":
            return 200, self._act(parts[1], int(parts[2]), parts[3], ident, json.loads(content or b"{}")), None
        if parts == ["convert", "sutra"]:
            return 200, self._from_sutra(json.loads(content or b"{}")), None
        if parts == ["convert", "board"]:
            doc = json.loads(content or b"{}").get("document") or {}
            if not ((doc.get("params") or {}).get("id") or {}).get("of"):
                return 200, {"yaml": None, "notes": ["a Sutra paints one record: give the board a parameter id: { type: id, of: <pack>/<kind> } first"]}, None
            panels = [p for loc in doc.get("local-chips") or [] for p in (loc.get("body") or {}).get("panels") or []]
            s = {"rachana": 1, "sutra": doc.get("board"), "version": 1, "match": {"kind": doc["params"]["id"]["of"].split("/")[1], "priority": 10}, "panels": panels}
            return 200, {"yaml": yaml.safe_dump(s, sort_keys=False), "notes": []}, None
        if parts == ["candidates"]:
            return 200, [{"pack": "core", "board": "core-book", "screen": "sutra:book@1", "chip": "local-limits", "from": "limits", "reason": "the table reads record paths"}], None
        if parts[:1] == ["candidates"] and len(parts) == 4:
            if parts[3] != "local-limits":
                self._refuse(404, "DRS-2072", "no candidate")
            return 200, {"pack": parts[1], "board": parts[2], "chip": parts[3], "document": CANDIDATE, "yaml": yaml.safe_dump(CANDIDATE, sort_keys=False)}, None
        return await super().ui(method, path, ident, content, content_type, headers, wait, **params)

    def _act(self, name, n, action, ident, body):
        vs = self.versions.setdefault(name, {})
        v = vs.get(n)
        if action == "submit":
            doc = yaml.safe_load(self.drafts.get(name) or "") or {}
            if doc.get("version") != n:
                self._refuse(409, "DRS-2078", f"the draft of {name} is version {doc.get('version')}, not {n}")
            probs = [x for x in self.chip_check(doc) if x["level"] == "error"]
            if probs:
                self._refuse(422, "DRS-2071", f"{name}@{n} fails the chip publish check", probs)
            vs[n] = {"state": "in-review", "author": ident.user, "reviewer": None, "doc": doc}
            return {"chip": name, "version": n, "state": "in-review", "author": ident.user}
        if v is None:
            self._refuse(404, "DRS-2072", f"no chip {name}@{n}")
        if action in ("approve", "reject"):
            if v["author"] == ident.user:
                self._refuse(403, "DRS-2007", f"a reviewer may not {action} their own chip ({name}@{n})")
            if action == "reject":
                vs.pop(n)
                return {"chip": name, "version": n, "state": "draft"}
            v.update(state="approved", reviewer=ident.user)
        elif action == "publish":
            if v["state"] != "approved":
                self._refuse(409, "DRS-2078", f"{name}@{n} is {v['state']}, not approved")
            v["state"] = "published"
            self.chips[f"{name}@{n}"] = v["doc"]
        elif action == "certify":
            v["state"] = "certified"
        else:
            raise BackendError(400, "DRS-1000", f"no action {action}")
        return {"chip": name, "version": n, "state": v["state"], "author": v["author"], "reviewer": v["reviewer"]}

    def _from_sutra(self, body: dict) -> dict:
        s = yaml.safe_load(body.get("sutra") or "") or {}
        kind = (s.get("match") or {}).get("kind") or body.get("kind") or "thing"
        pack = "trading" if kind in ("trade", "deal") else "core"
        board = f"{pack}-{s.get('sutra')}"
        chips, locals_, made = {"header": {"use": "local-header@1", "supply": {"connector": f"{pack}/{kind}", "query": {"id": "%{board.params.id}"}}, "map": {"doc.id": "id"}}}, [], []
        locals_.append({"rachana": 1, "chip": "local-header", "version": 1, "title": "header", "category": "local",
                        "pins": {"in": {"doc": {"role": "data", "type": "document", "required": True}}, "out": {}}, "body": {"page": {"title": s.get("title")}}})
        for p in s.get("panels") or []:
            inst = p.get("id") or p.get("kind")
            panel = json.loads(json.dumps(p).replace('"$.', '"@doc.'))
            locals_.append({"rachana": 1, "chip": f"local-{inst}", "version": 1, "title": inst, "category": "local",
                            "pins": {"in": {"doc": {"role": "data", "type": "document", "required": True}}, "out": {}}, "body": {"panels": [panel]}})
            chips[inst] = {"use": f"local-{inst}@1", "supply": {"connector": f"{pack}/{kind}", "query": {"id": "%{board.params.id}"}}, "map": {"doc.id": "id"}}
            made.append({"chip": f"local-{inst}", "from": inst, "reason": f"no Chip Store chip (panel-{p.get('kind')}) has a setting for {', '.join(k for k in p if k not in ('id', 'kind', 'title')) or 'title'}"})
        doc = {"rachana": 1, "board": board, "title": s.get("description") or board, "params": {"id": {"type": "id", "of": f"{pack}/{kind}", "required": True}},
               "chips": chips, "local-chips": locals_, "traces": [],
               "layout": {"desktop": {"columns": 12, "areas": [{"chip": k, "at": [1, 1 + 4 * i], "span": [12, 4]} for i, k in enumerate(chips)]}, "phone": {"order": list(chips), "collapsed": []}}}
        return {"document": doc, "status": "local", "pack": pack, "screen": f"sutra:{s.get('sutra')}@1", "seeds": [], "locals": made, "traces": 0, "links": 0, "reason": None, "problems": []}
