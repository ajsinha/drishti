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

"""Shared fixtures: a console app wired to the default configuration and a fake backend that serves
ViewModels captured from the real server (tests/fixtures)."""
import json
import sys
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

CONSOLE = Path(__file__).resolve().parent.parent
FIXTURES = Path(__file__).resolve().parent / "fixtures"
sys.path.insert(0, str(CONSOLE))

from core.app import create_app  # noqa: E402
from core.backend import BackendError  # noqa: E402
from core.config import load_settings  # noqa: E402


class FakeBackend:
    """Stands in for the Drishti server."""

    def __init__(self):
        self.calls = []
        self.sessions = {}                       # console sign-in sessions the server keeps: id -> user name

    async def view(self, kind, id_, user):
        self.calls.append(("view", kind, id_, user))
        f = FIXTURES / f"view_{kind}_{id_}.json"
        if not f.exists():
            raise BackendError(404, "DRS-1001", f"no source holds {kind}/{id_}")
        vm = json.loads(f.read_text())
        prov = vm.get("provenance") or {}
        if "sutra" not in prov and str(prov.get("layout", "")).startswith("Sutra "):     # as the server says since layouts
            prov["sutra"] = prov["layout"].split()[1]
        return vm

    # personal layouts: kept per user and Sutra, validated against the Sutra's panels as the server does
    layout_allowed = True
    layout_promote = True
    layouts_kept = {}
    sutra_panels = {"irs-vanilla": ("trade", ["legs", "cashflows", "leg2", "built", "curve", "refs", "dv01"])}

    async def layouts(self, ident=None):
        self.calls.append(("layouts", ident.user if ident else None))
        return {"enabled": True, "allowed": self.layout_allowed, "promote": self.layout_allowed and self.layout_promote, "review": True,
                "layouts": [dict(v, sutra=k[0], kind=k[1]) for k, v in self.layouts_kept.items() if self.layout_allowed]}

    async def save_layout(self, sutra, kind, body, ident=None):
        if not self.layout_allowed:
            raise BackendError(403, "DRS-5002", f"{ident.user} may not customise layouts: ask an administrator for a role with layout")
        if sutra not in self.sutra_panels:
            raise BackendError(404, "DRS-2003", f"no Sutra '{sutra}'")
        known = self.sutra_panels[sutra][1]
        for p in body.get("panels") or []:
            if p.get("id") not in known:
                raise BackendError(400, "DRS-5001", f"'{p.get('id')}' is not a panel of {sutra}")
        self.layouts_kept[(sutra, kind)] = {"panels": body["panels"]}
        return self.layouts_kept[(sutra, kind)]

    async def reset_layout(self, sutra, kind, ident=None):
        if self.layouts_kept.pop((sutra, kind), None) is None:
            raise BackendError(404, "DRS-1001", f"no personal layout for {sutra} ({kind})")

    async def layout_promotion(self, sutra, kind, drop_hidden, ident=None):
        if not self.layout_promote:
            raise BackendError(403, "DRS-5002", f"{ident.user} is not a Sutra author: promoting a layout needs a role with author")
        base = "rachana: 1\nsutra: irs-vanilla\nversion: 3\npanels:\n  - { id: legs, kind: kv }\n  - { id: refs, kind: links, area: right }\n"
        text = base.replace("version: 3", "version: 4").replace("area: right", "span: 6")
        return {"sutra": sutra, "kind": kind, "fromVersion": 3, "version": 4, "base": base, "text": text, "review": True,
                "changes": ["'refs' moves to the main column"] + (["'built' is removed (hidden in the layout)"] if drop_hidden else [])}

    async def promote_layout(self, sutra, kind, note, drop_hidden, ident=None):
        self.calls.append(("promote", sutra, kind, note, drop_hidden))
        return {"proposal": {"id": "P-000042", "name": sutra, "version": 4, "status": "pending"}}

    async def raw(self, kind, id_, ident=None):
        return {"ref": {"kind": kind, "id": id_}, "provenance": {"source": "aero-risk", "generation": 1742}, "data": {"tradeId": id_}}

    async def suggest(self, q, user, limit=10):
        self.calls.append(("suggest", q))
        return json.loads((FIXTURES / "suggest.json").read_text())

    async def sutras(self, ident=None):
        return [{"name": "irs-vanilla", "latest": 3, "versions": [3], "domain": "rates", "kind": "trade", "where": None, "priority": 10}]

    async def sutra_source(self, name, version, ident=None):
        return "rachana: 1\nsutra: irs-vanilla\nversion: 3\n"

    async def rachana_schema(self, ident=None):
        return {"$schema": "https://json-schema.org/draft/2020-12/schema", "type": "object", "required": ["rachana", "sutra", "version", "match"],
                "additionalProperties": False,
                "properties": {"rachana": {"const": 1}, "sutra": {"type": "string"}, "version": {"type": "integer"},
                               "match": {"type": "object", "properties": {"kind": {"enum": ["trade"]}}},
                               "panels": {"type": "array", "items": {"$ref": "#/$defs/panel"}}},
                "$defs": {"panel": {"type": "object", "required": ["id", "kind"], "properties": {"id": {}, "kind": {"enum": ["table"]}, "rows": {}},
                                    "allOf": [{"if": {"properties": {"kind": {"const": "table"}}},
                                               "then": {"required": ["rows"], "x-rachana-options": ["rows"]}}]}},
                "x-rachana-functions": {"size": {"min": 1, "max": 1}}}

    async def settings(self, ident=None):
        return {}

    async def studio_settings(self, ident=None):
        return {"save": False}

    builder_allowed = True
    shaped = []                                   # the samples each builder_shape call was sent

    designed = []                                 # (samples, kind) of each builder_design call

    async def builder_design(self, samples, kind, ident=None):
        """Step 3: a canned draft, with the preview of the first sample the way the server sends it."""
        if not self.builder_allowed:
            raise BackendError(403, "DRS-5002", f"{ident.user} is not a Sutra author")
        self.designed.append((samples, kind))
        return {"yaml": f"rachana: 1\nsutra: {kind}-auto\nversion: 1\nmatch: {{ kind: {kind}, priority: 1 }}\npanels: []\n",
                "reasons": {"title": "tradeId is the id", "pnl": "a series of 20 points"},
                "alternatives": {"pnl": [{"kind": "area", "score": 0.6, "reason": "the same series as an area", "area": "main", "options": {}, "columns": []}]},
                "pruned": [{"panel": "rare", "kind": "line", "action": "dropped", "to": None, "bad": 4, "of": 5, "reason": "empty in 4 of 5 samples"}],
                "preview": await self.view("trade", "IRS-48213", ident), "samples": len(samples)}

    async def builder_shape(self, samples, ident=None):
        """The Screen Builder's shape of the samples: every top-level key of the first document, annotated."""
        if not self.builder_allowed:
            raise BackendError(403, "DRS-5002", f"{ident.user} is not a Sutra author")
        self.shaped.append(samples)
        keys = list(samples[0]["document"]) if isinstance(samples[0]["document"], dict) else []
        props = {k: {"type": "string", "x-drishti": {"role": "id" if k.endswith("Id") else "dimension", "reason": "test"}} for k in keys}
        paths = [{"path": "$." + k, "type": "string", "role": "id" if k.endswith("Id") else "dimension", "reason": "test", "presence": 1.0,
                  "examples": [str(samples[0]["document"][k])], "files": [s["name"] for s in samples], "conflict": False, "masked": False} for k in keys]
        return {"schema": {"$schema": "https://json-schema.org/draft/2020-12/schema", "type": "object", "properties": props,
                           "x-drishti": {"samples": len(samples)}},
                "roles": {"$." + k: {"role": p["x-drishti"]["role"], "reason": "test", "kind": None} for k, p in props.items()},
                "report": {"samples": len(samples), "files": [s["name"] for s in samples], "conflicts": [], "rare": [], "paths": paths}}

    # Build workbench Designs: kept here, as the real server keeps them, so two consoles on one FakeBackend see the same ones
    designs_limits = {"maxPerUser": 50, "maxSamples": 50, "maxMb": 25, "maxUserMb": 250, "scratchHours": 24, "namedDays": 90, "warnDays": 75}
    designs_open = {"curve"}                      # stored kinds a ref sample may name; others answer "no access"

    def _kept(self):
        if not hasattr(self, "design_rows"):
            self.design_rows = {}                 # (user, id) -> {"design": {...}, "docs": {name: document}}
        return self.design_rows

    def _own(self, ident, id_):
        row = self._kept().get((ident.user, id_))
        if row is None:
            raise BackendError(404, "DRS-5006", f"no design '{id_}'")
        return row

    @staticmethod
    def _slim(d):
        return {k: v for k, v in d.items() if k not in ("sutra", "notes", "tests", "ops")}

    async def designs(self, method, path, ident, body=None, **params):
        """The server's /builder/designs, for the console tests (same shapes, same codes)."""
        self.calls.append(("designs", method, path))
        rows, user = self._kept(), ident.user
        parts = [p for p in path.strip("/").split("/") if p]
        if not parts:
            if method == "GET":
                return {"designs": [self._slim(r["design"]) for (u, _), r in rows.items() if u == user], "limits": self.designs_limits}
            if sum(1 for (u, _) in rows if u == user) >= self.designs_limits["maxPerUser"]:
                raise BackendError(413, "DRS-5005", "you keep 50 designs, the most allowed (drishti.builder.designs.max-per-user)")
            id_ = f"d{len(rows) + 1:011d}"
            name = (body.get("name") or "").strip()
            sutra = body.get("sutra") or ""
            if body.get("base") and not sutra:
                sutra = f"rachana: 1\nsutra: {body['base'].split('@')[0]}\nversion: 1\n"
            d = {"id": id_, "name": name, "scratch": not name, "kind": body.get("kind") or "sample", "status": "draft", "rev": 1 if sutra else 0,
                 "created": 1, "updated": 1, "expiresAt": 2, "expiryWarning": False, "bytes": 0, "samples": [], "sutra": sutra,
                 "notes": body.get("notes") or "", "tests": [], "ops": []}
            if body.get("base"):
                d["base"] = body["base"]
            rows[(user, id_)] = {"design": d, "docs": {}}
            return d
        row = self._own(ident, parts[0])
        d = row["design"]
        rest = parts[1:]
        if not rest:
            if method == "GET":
                return d
            if method == "DELETE":
                del rows[(user, parts[0])]
                return None
            for k in ("name", "kind", "notes", "tests"):
                if k in body:
                    d[k] = body[k]
            if "name" in body:
                d["scratch"] = not (body["name"] or "").strip()
            if "sutra" in body and body["sutra"] != d["sutra"]:
                d["sutra"], d["rev"] = body["sutra"], d["rev"] + 1
            return d
        if rest == ["duplicate"]:
            copy = await self.designs("POST", "", ident, {"name": (body or {}).get("name") or (d["name"] or "Untitled") + " copy", "kind": d["kind"],
                                                           "sutra": d["sutra"], "notes": d["notes"]})
            new = self._own(ident, copy["id"])
            new["design"]["samples"], new["docs"] = [dict(s) for s in d["samples"]], dict(row["docs"])
            return new["design"]
        if rest == ["samples"] and method == "POST":
            for s in body.get("samples") or []:
                row["docs"][s["name"]] = s["document"]
                d["samples"] = [x for x in d["samples"] if x["name"] != s["name"]] + [{"name": s["name"], "type": "document", "synthetic": False, "bytes": 1}]
            if (body.get("refs") or {}).get("kind"):
                kind = body["refs"]["kind"]
                if kind not in self.designs_open:
                    raise BackendError(403, "DRS-5002", f"{user} may not open {kind} entities")
                for i in body["refs"].get("ids") or [f"ID-{n}" for n in range(1, int(body["refs"].get("count", 3)) + 1)]:
                    d["samples"].append({"name": f"{kind} {i}", "type": "ref", "synthetic": False, "bytes": 0, "ref": {"kind": kind, "id": i}})
            if isinstance(body.get("schema"), dict):
                for n in range(1, int(body.get("count", 5)) + 1):
                    row["docs"][f"synthetic-{n}"] = {"tradeId": f"synthetic-{n}"}
                    d["samples"].append({"name": f"synthetic-{n}", "type": "synthetic", "synthetic": True, "bytes": 1})
            if len(d["samples"]) > self.designs_limits["maxSamples"]:
                raise BackendError(413, "DRS-5005", "a design holds 50 samples, the most allowed (drishti.builder.designs.max-samples)")
            return d
        if rest == ["samples"] and method == "DELETE":
            d["samples"] = [s for s in d["samples"] if s["name"] != params.get("name")]
            row["docs"].pop(params.get("name"), None)
            return d
        if rest == ["samples", "document"]:
            if params.get("name") not in row["docs"]:
                raise BackendError(400, "DRS-5001", "a reference keeps no document")
            return row["docs"][params["name"]]
        if rest == ["shape"]:
            docs = [{"name": n, "document": v} for n, v in row["docs"].items()]
            if not docs:
                raise BackendError(400, "DRS-5001", "this design has no readable sample")
            return {**await self.builder_shape(docs, ident), "skipped": [], "samples": len(docs)}
        if rest == ["preview"]:
            if not d["sutra"]:
                raise BackendError(400, "DRS-5001", "this design has no Sutra yet")
            name = params.get("sample") or (d["samples"][0]["name"] if d["samples"] else None)
            info = next((s for s in d["samples"] if s["name"] == name), None)
            if info is None:
                raise BackendError(400, "DRS-5001", "this design has no samples to preview against")
            if info["type"] == "ref" and info["ref"]["kind"] not in self.designs_open:
                raise BackendError(403, "DRS-5002", f"no access: you may not open {info['ref']['kind']} entities")
            sutra_id = next((ln.split(":", 1)[1].strip() for ln in d["sutra"].splitlines() if ln.startswith("sutra:")), "")
            captured = FIXTURES / f"view_sutra_{sutra_id}.json"       # a view captured from the real server for this Sutra, when there is one
            if captured.exists():
                return json.loads(captured.read_text())
            v = await self.view("trade", "IRS-48213", ident)
            doc = row["docs"].get(name)
            v["title"]["id"] = doc.get("tradeId", "SAMPLE") if isinstance(doc, dict) else "SAMPLE"
            return v
        if rest == ["autodesign"]:
            docs = [{"name": n, "document": v} for n, v in row["docs"].items()]
            if not docs:
                raise BackendError(400, "DRS-5001", "this design has no readable sample")
            draft = await self.builder_design(docs, d["kind"], ident)
            if draft["yaml"] != d["sutra"]:
                d["sutra"], d["rev"] = draft["yaml"], d["rev"] + 1
            return {**draft, "rev": d["rev"], "skipped": []}
        raise BackendError(404, "DRS-1001", "no such design endpoint")

    async def inferred_from(self, kind, id_, name, document, ident=None):
        return f"sutra: {name}\nversion: 1\n# fields: {','.join(sorted(document))}\n"

    async def preview(self, yaml_text, kind, id_, ident=None, document=None):
        if document is not None:
            v = await self.view("trade", "IRS-48213", ident)
            v["title"]["id"] = document.get("tradeId", "SAMPLE")
            return v
        if "BROKEN" in yaml_text:
            e = BackendError(422, "DRS-2002", "1 problem(s)")
            e.problems = [{"code": "DRS-2101", "message": "bad expression", "location": {"file": "studio.yaml", "line": 4, "column": 3}}]
            raise e
        return await self.view(kind, id_, ident)

    async def check(self, yaml_text, kind, samples, ident=None):
        """The server's /builder/check, faked from the fake preview: a cell per panel and sample."""
        if "BROKEN" in yaml_text:
            e = BackendError(422, "DRS-2002", "1 problem(s)")
            e.problems = [{"code": "DRS-2101", "message": "bad expression", "location": {"file": "studio.yaml", "line": 4, "column": 3}}]
            raise e
        rows, infos = {}, []
        for s in samples:
            v = await self.view(kind, (s.get("ref") or {}).get("id", "IRS-48213"), ident)
            infos.append({"name": s["name"], "layout": (v.get("provenance") or {}).get("layout"), "status": "ok"})
            for p in v.get("panels", []):
                st = "error" if p.get("error") else "empty" if p.get("empty") else "ok"
                rows.setdefault(p["id"], []).append({"status": st, **({"message": p["error"]} if p.get("error") else {})})
        return {"ok": True, "samples": infos, "panels": [{"id": k, "cells": c} for k, c in rows.items()], "strip": [], "counts": {}}

    async def inferred(self, kind, id_, name, ident=None):
        return f"sutra: {name}\nversion: 1\n"

    users = {"drishti-dev-admin": {"username": "drishti-dev-admin", "displayName": "Drishti dev admin", "desk": "Administration",
                                   "roles": ["admin"], "enabled": True, "mustChangePassword": False, "locked": False, "email": "",
                                   "lastLoginAt": None}}

    passwords = {"drishti-dev-admin": "drishti-dev-admin123"}

    async def login(self, username, password, service):
        assert service.roles == ("service",)
        if username in self.users and password == self.passwords.get(username):
            return self.users[username]
        raise BackendError(401, "DRS-6004", "unknown user or wrong password")

    async def open_session(self, username, seconds, service):
        assert service.roles == ("service",)
        sid = f"sess{len(self.sessions) + 1:04d}" + "x" * 24
        self.sessions[sid] = username
        return {"id": sid, "expiresAt": "2026-10-02T20:00:00Z", "user": self.users.get(username, {"username": username})}

    async def session(self, sid, service):
        assert service.roles == ("service",)
        self.calls.append(("session", sid))
        user = self.users.get(self.sessions.get(sid, ""))
        if user is None or not user.get("enabled", True):
            raise BackendError(401, "DRS-5010", "session ended")
        return {"id": sid, "expiresAt": "2026-10-02T20:00:00Z", "user": user}

    async def end_session(self, sid, service):
        assert service.roles == ("service",)
        self.calls.append(("end_session", sid))
        self.sessions.pop(sid, None)

    async def me(self, ident):
        return self.users.get(ident.user, {"username": ident.user, "displayName": ident.display, "desk": ident.desk, "roles": list(ident.roles)})

    async def change_password(self, current, new, ident):
        if current != "drishti-dev-admin123":
            raise BackendError(401, "DRS-6004", "current password is wrong")
        return self.users["drishti-dev-admin"]

    async def admin(self, method, path, ident, body=None, **params):
        self.calls.append(("admin", method, path, body))
        if path == "/users" and method == "GET":
            return list(self.users.values())
        if path == "/roles":
            return ["admin", "author", "risk", "trader"]
        if path == "/role-definitions":
            return [{"name": "admin", "description": "", "kinds": ["*"], "raw": True, "author": True, "approve": False, "admin": True,
                     "builtIn": True, "updatedAt": None, "updatedBy": "", "users": 1},
                    {"name": "credit-analyst", "description": "Reads credit", "kinds": ["counterparty", "credit-curve"], "raw": False,
                     "author": False, "approve": False, "admin": False, "builtIn": False, "updatedAt": "2026-09-30T12:00:00Z",
                     "updatedBy": "drishti-dev-admin", "users": 0}]
        if path.startswith("/role-definitions/") and method == "PUT":
            if not body.get("kinds"):
                raise BackendError(400, "DRS-5001", "a role opens at least one kind (or * for all)")
            return {"name": path.rsplit("/", 1)[1], **body}
        if path == "/role-definitions/admin" and method == "DELETE":
            raise BackendError(400, "DRS-5001", "'admin' is a built-in role")
        if path == "/status":
            return {"defaultAdminPasswordInUse": True, "users": 1, "forceChangeOnCreate": False}
        if path == "/caches":
            return [{"name": "engine", "type": "Layouts and shape fingerprints", "stats": {"layouts": 12, "fingerprints": 40}},
                    {"name": "trading-stream", "type": "Connector", "stats": {"memoryEntries": 3, "diskMb": 1.5}}]
        if path.endswith("/purge"):
            return {"purged": [path.split("/")[2]], "elapsedMs": 0.4}
        if path == "/audit":
            return [{"at": "2026-09-30T12:00:00Z", "actor": "system", "action": "user-seeded", "subject": "drishti-dev-admin", "detail": ""}]
        if path == "/users" and method == "POST":
            if body.get("username") in self.users:
                raise BackendError(409, "DRS-6002", "exists")
            return {**body, "enabled": True}
        return {"ok": True}

    enabled_packs = ["finance", "logistics"]
    monitors = {}
    rules = {}

    async def mine(self, method, path, ident, body=None, **params):
        from urllib.parse import unquote
        parts = [unquote(p) for p in path.strip("/").split("/")]
        if parts[0] == "monitors":
            if len(parts) == 1:
                return sorted(self.monitors)
            name = parts[1]
            if method == "PUT":
                self.monitors[name] = body
                return body
            if method == "DELETE":
                self.monitors.pop(name, None)
                return None
            if name not in self.monitors:
                raise BackendError(404, "DRS-1001", "no monitor")
            rows = []
            for e in self.monitors[name]["entities"]:
                try:
                    v = await self.view(e["kind"], e["id"], ident)
                    rows.append({"ref": e, "title": v["title"], "strip": v["strip"], "live": True})
                except BackendError as err:
                    rows.append({"ref": e, "error": err.code})
            return rows
        if parts[0] == "alerts":
            if parts[-1] == "rules" and method == "GET":
                return list(self.rules.values())
            if len(parts) == 3 and method == "PUT":
                if body.get("when", "").endswith("<"):
                    raise BackendError(422, "DRS-2101", "alert expression: unexpected end")
                self.rules[parts[2]] = {"name": parts[2], "ref": {"kind": body["kind"], "id": body["id"]},
                                        "when": body["when"], "severity": body.get("severity", "warn")}
                return self.rules[parts[2]]
            if len(parts) == 3 and method == "DELETE":
                self.rules.pop(parts[2], None)
                return None
            return [{"seq": 1, "at": "2026-09-30T12:00:05Z", "user": "ash", "rule": "Deep", "kind": "trade",
                     "id": "IRS-48213", "severity": "warn", "message": "IRS-48213: MTM -412,580", "generation": 1}]
        raise BackendError(404, "DRS-1001", path)

    async def sse(self, path, ident=None, opened=None):
        yield "hello", "{}"
        if path.endswith("/alerts/stream"):
            yield "alert", json.dumps({"seq": 2, "kind": "trade", "id": "IRS-48213", "severity": "critical", "message": "x"})
        else:
            yield "row", json.dumps({"kind": "trade", "id": "IRS-48213", "patches": [], "p99Ms": 3})

    python = {}                     # pack -> what it offers Calc ({"enabled": True, "snippets": [...]}), as the server says

    async def packs(self, ident=None):
        return [{"name": n, "version": "1.0.0", "title": n.title(), "description": "", "console": {}, "assigned": True,
                 "active": n in self.enabled_packs, **({"python": self.python[n]} if n in self.python else {})}
                for n in ["finance", "logistics"] if n in self.enabled_packs or self.enabled_packs]

    calc_allowed = True
    calc_kept = {}

    async def calc_settings(self, ident=None):
        return {"enabled": True, "allowed": self.calc_allowed, "maxColumnRows": 250000, "maxSnippetChars": 50000}

    async def calc_snippets(self, ident=None):
        return [dict(v) for _, v in sorted(self.calc_kept.items())]

    async def save_calc_snippet(self, name, body, ident=None):
        if not body.get("code", "").strip():
            raise BackendError(400, "DRS-5001", "a snippet has code")
        self.calc_kept[name] = {"name": name, **body, "updatedAt": "2026-10-01T09:00:00Z"}
        return self.calc_kept[name]

    async def delete_calc_snippet(self, name, ident=None):
        if self.calc_kept.pop(name, None) is None:
            raise BackendError(404, "DRS-1001", f"no snippet '{name}'")

    async def columns(self, kind, paths, limit, ident=None):
        self.calls.append(("columns", kind, paths, limit))
        if not paths:
            return {"kind": "trade", "available": ["book", "mtm"]}
        return {"kind": "trade", "businessDate": "2026-09-30", "paths": paths.split(","), "ids": ["T-1", "T-2"],
                "values": {p: [1, 2] for p in paths.split(",")}, "rows": 2, "total": 2, "truncated": False, "masked": []}

    chosen = None

    async def choose_packs(self, active, ident):
        if not active:
            raise BackendError(403, "DRS-5002", "choose one or more")
        self.enabled_packs = active
        return {"assigned": ["finance", "logistics"], "active": active}

    saved_workspaces = {}

    async def workspaces(self, ident):
        return sorted(self.saved_workspaces)

    async def workspace(self, name, ident):
        if name not in self.saved_workspaces:
            raise BackendError(404, "DRS-1001", "no workspace")
        return self.saved_workspaces[name]

    async def save_workspace(self, name, body, ident):
        if not body.get("panes"):
            raise BackendError(400, "DRS-5001", "a workspace has 1 to 4 panes")
        self.saved_workspaces[name] = body
        return body

    async def delete_workspace(self, name, ident):
        self.saved_workspaces.pop(name, None)

    async def business_date(self, ident=None):
        from core import asof
        sel = asof.current()
        if sel != "live" and not ("2021-09-30" <= sel <= "2026-12-31"):     # as the server refuses: future, or before its history
            raise BackendError(400, "DRS-4003", f"business date {sel} is in the future")
        return {"current": "2026-09-30", "selected": "2026-09-30" if sel == "live" else ("2026-09-25" if sel == "2026-09-26" else sel),
                "live": sel == "live", "previous": "2026-09-29", "earliest": "2021-09-30", "calendar": "USNY", "zone": "America/New_York",
                "holidays": ["2026-10-12", "2026-11-11", "2026-11-26"]}

    async def about(self, ident=None):
        return {"product": "Drishti", "version": "1.2.0", "built": "2026-09-30T12:00:00Z", "java": "21.0.12 (Ubuntu)",
                "uptimeSeconds": 3725, "sutras": ["irs-vanilla v3"], "securityEnabled": False,
                "sources": [{"name": "demo", "version": "1.0", "kinds": [], "live": True, "search": True, "reverseLookup": True, "health": "UP"}]}

    aliases_saved = {"MYBOOK": "BOOK BOOK-RATES-1"}
    tests_saved = {}
    tokens_made = []

    async def phrase(self, text, ident=None):
        if "weather" in text:
            return {"query": None, "steps": [], "ignored": [], "problem": "say what to look for: a kind such as ['trades']"}
        return {"query": "TRD where status = 'Live' and mtm > 5000000 order by mtm desc", "kind": "trade",
                "steps": [{"words": "trades", "meaning": "Trade (TRD)"}, {"words": "live", "meaning": "status is Live"},
                          {"words": "over 5m", "meaning": "mtm > 5000000"}], "ignored": ["snack"], "problem": None}

    reports_kept = {}

    async def reports(self, ident):
        return list(self.reports_kept.values())

    async def save_report(self, name, body, ident):
        if "every tuesday" in body.get("schedule", ""):
            raise BackendError(400, "DRS-5001", "'every tuesday' is not a schedule: use daily HH:MM, …")
        self.reports_kept[name] = {"name": name, **body, "nextRun": "2026-10-01T22:30:00Z", "runs": []}
        return self.reports_kept[name]

    async def run_report(self, name, ident):
        run = {"at": "2026-10-01T09:00:00Z", "trigger": "by hand", "status": "ok", "rows": 12, "target": "/data/reports/ash/x.csv"}
        self.reports_kept[name]["runs"].insert(0, run)
        return run

    async def delete_report(self, name, ident):
        self.reports_kept.pop(name, None)

    notes_kept = []
    shares = {}

    async def notes(self, kind, id_, ident):
        return [n for n in self.notes_kept if (n["kind"], n["entityId"]) == (kind, id_)]

    async def add_note(self, kind, id_, body, path, ident):
        n = {"id": len(self.notes_kept) + 1, "kind": kind, "entityId": id_, "path": path, "author": ident.user, "body": body,
             "createdAt": "2026-10-01T09:00:00Z", "updatedAt": "2026-10-01T09:00:00Z"}
        self.notes_kept.append(n)
        return n

    async def edit_note(self, note_id, body, ident):
        n = next(n for n in self.notes_kept if n["id"] == note_id)
        n["body"] = body
        return n

    async def delete_note(self, note_id, ident):
        self.notes_kept[:] = [n for n in self.notes_kept if n["id"] != note_id]

    async def workspace_share(self, name, ident):
        return self.shares.get(name)

    async def share_workspace(self, name, body, ident):
        self.shares[name] = {"owner": ident.user, "name": name, "everyone": bool(body.get("everyone")),
                             "roles": body.get("roles", []), "users": body.get("users", [])}
        return self.shares[name]

    async def unshare_workspace(self, name, ident):
        self.shares.pop(name, None)

    async def shared_workspaces(self, ident):
        return [{"owner": "ravi", "name": "Rates desk", "sharedAt": "2026-10-01T09:00:00Z"}]

    async def shared_workspace(self, owner, name, ident):
        if (owner, name) != ("ravi", "Rates desk"):
            raise BackendError(404, "DRS-1001", "not shared")
        return {"layout": "2col", "owner": owner, "name": name, "readOnly": True,
                "panes": [{"ref": {"kind": "trade", "id": "IRS-48213"}, "follows": None, "title": ""},
                          {"ref": None, "hidden": True, "follows": None, "title": ""}]}

    async def series(self, kind, id_, path, days, ident=None):
        return {"ref": {"kind": kind, "id": id_}, "path": path, "label": "MTM", "dated": True,
                "points": [{"date": "2026-09-29", "value": 110, "dataDate": "2026-09-29", "source": "lake"},
                           {"date": "2026-09-30", "value": 125, "dataDate": "2026-09-30", "source": "lake"}]}

    async def search_compare(self, q, from_, to, ident=None):
        return {"kind": "trade", "mnemonic": "TRD", "columns": ["$.mtm"], "labels": {"$.mtm": "MTM"}, "matched": 2, "scanned": 2,
                "elapsedMs": 1, "partial": False, "from": from_, "to": "2026-09-30",
                "rows": [{"ref": {"kind": "trade", "id": "T-1"}, "title": "T-1", "status": None, "values": {"$.mtm": {"from": 100, "to": 125, "delta": 25.0}}},
                         {"ref": {"kind": "trade", "id": "T-7"}, "title": "T-7", "status": "added", "values": {"$.mtm": {"from": None, "to": 5}}}]}

    async def my_tokens(self, ident=None):
        return [{"id": "abc123def456", "user": "drishti-dev-admin", "name": "Risk notebook", "createdAt": "2026-10-01T09:00:00Z",
                 "expiresAt": None, "lastUsedAt": None, "revokedAt": None, "active": True}]

    async def create_token(self, name, days, ident=None):
        if not name.strip():
            raise BackendError(400, "DRS-5001", "give the token a name of 1-100 characters (what uses it)")
        self.tokens_made.append((name, days))
        return {"token": {"id": "xyz987xyz987", "name": name, "active": True}, "secret": "drk_xyz987xyz987_" + "s" * 43}

    async def revoke_token(self, id_, ident=None):
        return None

    async def studio_tests(self, sutra, ident=None):
        return list(self.tests_saved.get(sutra, []))

    async def set_studio_tests(self, sutra, entities, ident=None):
        self.tests_saved[sutra] = list(entities)
        return list(entities)

    async def command_history(self, ident=None):
        return ["TRD MX-20000001", "MKT"]

    async def aliases(self, ident=None):
        return dict(self.aliases_saved)

    async def set_aliases(self, aliases, ident=None):
        if "TRD" in {k.upper() for k in aliases}:
            raise BackendError(400, "DRS-5001", "'TRD' is already a mnemonic or a pack code; choose another name")
        self.aliases_saved = {k.upper(): v for k, v in aliases.items()}
        return dict(self.aliases_saved)

    async def pack_overview(self, name, ident=None):
        if name.upper() not in ("MKT", "MARKET-DATA"):
            raise BackendError(400, "DRS-5001", f"no pack '{name}' is switched on")
        return {"name": "market-data", "code": "MKT", "title": "Market data", "description": "Curves and surfaces.", "version": "1.0.0",
                "extends": ["banking-core"], "kinds": [
                    {"kind": "fx-vol-surface", "mnemonic": "FXV", "label": "FX volatility surface", "count": 12, "more": False,
                     "example": "FXV-EURUSD", "columns": []},
                    {"kind": "ir-curve", "mnemonic": "CRV", "label": "Interest-rate curve", "count": 1234, "more": False,
                     "example": "CRV-USD-SOFR", "columns": ["currency", "index"]}]}

    async def command(self, text, ident=None):
        if text.strip().upper() == "MKT":
            return {"ref": None, "pack": "market-data"}
        if "IRS-48213" in text.upper():
            return {"ref": {"kind": "trade", "id": "IRS-48213"}, "mnemonic": "TRD"}
        raise BackendError(400, "DRS-4001", f"cannot read command '{text}'")


@pytest.fixture(scope="session")
def backend():
    return FakeBackend()


@pytest.fixture(scope="session")
def client(backend):
    app = create_app(load_settings(CONSOLE / "config"))
    app.state.backend = backend
    return TestClient(app)


@pytest.fixture
def with_packs(client, backend, monkeypatch):
    """Switches the stand-in server to the given packs (as DRISHTI_PACKS would), for one test."""
    def use(*names):
        async def packs(ident=None):
            return [{"name": n, "version": "1.0.0", "title": n.title(), "description": "", "assigned": True, "active": True} for n in names]
        monkeypatch.setattr(backend, "packs", packs)
        client.app.state.packs.forget_all()
    yield use
    client.app.state.packs.forget_all()
