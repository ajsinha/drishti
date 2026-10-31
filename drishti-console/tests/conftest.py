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
import os
import sys
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

CONSOLE = Path(__file__).resolve().parent.parent
FIXTURES = Path(__file__).resolve().parent / "fixtures"
sys.path.insert(0, str(CONSOLE))
# the stand-in server serves the retired example packs finance and logistics (examples/packs), as the server's tests do
os.environ.setdefault("DRISHTI_PACKS_EXTRA_DIRS", str(CONSOLE.parent / "examples" / "packs"))
if not os.environ.get("DRISHTI_CONSOLE_DATA_DIR"):
    # what the console keeps between runs (user stamps, net/servers.json): a folder of the test run's own, never the repository's data/
    import atexit
    import shutil
    import tempfile

    _DATA = tempfile.mkdtemp(prefix="drishti-console-tests-")
    os.environ["DRISHTI_CONSOLE_DATA_DIR"] = _DATA
    atexit.register(shutil.rmtree, _DATA, True)

from core.app import create_app  # noqa: E402
from core.backend import BackendError  # noqa: E402
from core.config import load_settings  # noqa: E402


class FakeBackend:
    """Stands in for the Drishti server."""

    def __init__(self):
        self.calls = []
        self.sessions = {}                       # console sign-in sessions the server keeps: id -> user name

    async def _send(self, method, path, ident=None, **kw):
        """The collaboration calls (core/collab.py: the bell's unread count every page asks, the inbox, shares): an empty inbox, so a page
        under test never answers 500 to its own bell (each one logged a long traceback)."""
        return {"unread": 0} if path.endswith("/count") else {"items": []}

    async def view(self, kind, id_, user, controls=None, panels=None):
        self.calls.append(("view", kind, id_, user))
        f = FIXTURES / f"view_{kind}_{id_}.json"
        if not f.exists():
            raise BackendError(404, "DRS-1001", f"no source holds {kind}/{id_}")
        vm = json.loads(f.read_text())
        prov = vm.get("provenance") or {}
        if "sutra" not in prov and str(prov.get("layout", "")).startswith("Sutra "):     # as the server says since layouts
            prov["sutra"] = prov["layout"].split()[1]
        return vm

    async def explain(self, kind, id_, user, generation=None, locale=None, accept_language=None):
        self.calls.append(("explain", kind, id_, generation))
        if locale or accept_language:
            self.calls.append(("explain-language", locale, accept_language))
        f = FIXTURES / f"explain_{kind}_{id_}.json"
        if not f.exists():
            raise BackendError(404, "DRS-1001", f"no source holds {kind}/{id_}")
        ex = json.loads(f.read_text())
        if generation and generation < ex["generation"]:
            ex["newer"] = True
        return ex

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

    async def proposals(self, ident=None, status="", name=""):
        return {"enabled": False, "proposals": []}

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

    designs_binding = False
    shares: dict = {}
    proposed: list = []
    file_writes: list = []
    file_edit = None
    imported: list = []
    about_calls: list = []

    async def designs_raw(self, method, path, ident, content=None, content_type=None):
        """Bytes in and out: the fragment export is a tiny zip, an import is recorded and answered with one design."""
        import io, zipfile
        if path.endswith("/export"):
            row = self._own(ident, path.strip("/").split("/")[0])
            buf = io.BytesIO()
            with zipfile.ZipFile(buf, "w") as z:
                z.writestr("frag/pack.yaml", "pack: frag\n")
                z.writestr("frag/sutras/x/x.v1.sutra.yaml", row["design"]["sutra"])
            return buf.getvalue()
        if path == "/import":
            self.imported.append((content_type, content))
            with zipfile.ZipFile(io.BytesIO(content)) as z:
                names = z.namelist()
            return json.dumps({"designs": [{"id": "imp1", "name": "imported", "samples": [], "names": names}], "skipped": ["note"]}).encode()
        raise BackendError(404, "DRS-1001", "no such design endpoint")

    async def designs(self, method, path, ident, body=None, **params):
        """The server's /builder/designs, for the console tests (same shapes, same codes)."""
        self.calls.append(("designs", method, path))
        rows, user = self._kept(), ident.user
        parts = [p for p in path.strip("/").split("/") if p]
        if not parts:
            if method == "GET":
                return {"designs": [self._slim(r["design"]) for (u, _), r in rows.items() if u == user], "limits": self.designs_limits}
            if method == "DELETE":
                gone = [k for k, r in rows.items() if k[0] == user and r["design"].get("scratch")] if params.get("scratch") == "true" else []
                for k in gone:
                    del rows[k]
                return {"deleted": len(gone)}
            if sum(1 for (u, k) in rows if u == user and not rows[(u, k)]["design"].get("scratch")) >= self.designs_limits["maxPerUser"]:
                raise BackendError(413, "DRS-5005", "you keep 50 designs, the most allowed (drishti.builder.designs.max-per-user)")
            id_ = f"d{len(rows) + 1:011d}"
            name = (body.get("name") or "").strip()
            sutra = body.get("sutra") or ""
            if body.get("base") and not sutra:
                name_, _, version_ = body["base"].partition("@")
                sutra = await self.sutra_source(name_, int(version_ or 0), ident)             # as the server copies the registry's text
            d = {"id": id_, "name": name, "scratch": not name, "kind": body.get("kind") or "sample", "status": "draft", "rev": 1 if sutra else 0,
                 "created": 1, "updated": 1, "expiresAt": 2, "expiryWarning": False, "bytes": 0, "samples": [], "sutra": sutra,
                 "notes": body.get("notes") or "", "tests": [], "ops": []}
            if body.get("base"):
                d["base"] = body["base"]
            rows[(user, id_)] = {"design": d, "docs": {}}
            return d
        if parts == ["binding"]:
            return {"enabled": self.designs_binding, "dir": "dev-sutras/ana"}
        if parts[0] == "shared":                                    # a read-only link: Sutra, ops and sample names, never contents or notes
            found = next((r["design"] for (u, i), r in rows.items() if i == parts[1] and self.shares.get(i) == params.get("token")), None)
            if found is None:
                raise BackendError(404, "DRS-5006", "this link is not valid (it may have been revoked)")
            return {"id": found["id"], "name": found["name"], "kind": found["kind"], "status": found["status"], "sutra": found["sutra"], "rev": found["rev"],
                    "owner": "ann", "sampleNames": [x["name"] for x in found["samples"]], "ops": []}
        row = self._own(ident, parts[0])
        d = row["design"]
        rest = parts[1:]
        if rest[:1] == ["about"]:                                       # the About tab: the text is a part of the Design, a PUT is a new revision
            self.about_calls.append((method, "/".join(rest), body, params))
            if method == "PUT":
                if body["baseRev"] != d["rev"]:
                    raise BackendError(409, "DRS-5007", f"this design is at revision {d['rev']}, not {body['baseRev']}")
                d["about"], d["rev"] = body["text"], d["rev"] + 1
            text = body["text"] if rest == ["about", "preview"] else d.get("about", "")
            return {"rev": d["rev"], "about": text, "kind": d["kind"], "status": d["status"], "opsAt": 1, "opsCount": 1,
                    "card": {"about": {"text": f"card of {len(text)} characters", "panels": []}, "glossary": [], "errors": 0},
                    "problems": [], "lint": [{"code": "DRS-2047", "severity": "warning", "message": "field 'x' is shown but has no glossary entry"}],
                    "coverage": {"shown": 1, "covered": 0, "missing": ["x"], "text": "help coverage 0/1 (0%)"}, "panels": [], "sample": params.get("sample")}
        if rest == ["propose"] and method == "POST":
            out = await self.save_sutra(d["sutra"], ident, note=(body or {}).get("note", ""))
            self.proposed.append({"id": d["id"], "note": (body or {}).get("note", "")})
            if "proposal" in out:
                d["status"] = f"proposed({out['proposal']['id']})"
                return {**out, "status": d["status"]}
            d["status"] = f"live(v{out.get('latest', 1)})"
            return {"name": out["name"], "version": out.get("latest", 1), "status": d["status"]}
        if rest == ["share"]:
            if method == "DELETE":
                self.shares.pop(d["id"], None)
                d["shared"] = False
                return {"shared": False}
            self.shares[d["id"]] = f"ann.secret{len(self.shares)}"
            d["shared"] = True
            return {"token": self.shares[d["id"]], "path": f"/build/d/{d['id']}?share={self.shares[d['id']]}"}
        if rest == ["bind"]:
            if not self.designs_binding:
                raise BackendError(403, "DRS-5002", "binding a design to a file is off on this server (drishti.builder.file-binding)")
            if method == "DELETE":
                d.pop("boundFile", None)
            else:
                d["boundFile"] = body["file"]
            return d
        if rest == ["save-file"]:
            self.file_writes.append((d["boundFile"], d["sutra"]))
            return d
        if rest == ["sync"]:
            changed = self.file_edit is not None
            if changed:
                d["sutra"], d["rev"], self.file_edit = self.file_edit, d["rev"] + 1, None
            return {**d, "changed": changed, "missing": False}
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
        if rest[:1] == ["versions"] and method == "GET":
            steps = row.setdefault("steps", {"list": [], "at": 0})["list"]
            text = lambda n: d["sutra"] if not steps else (steps[0][0] if n == 0 else steps[n - 1][1])      # noqa: E731
            if len(rest) == 1:
                return {"versions": [{"n": n, "at": 1, "ops": "start" if n == 0 else "text", "current": n == row["steps"]["at"]} for n in range(len(steps) + 1)],
                        "opsAt": row["steps"]["at"]}
            if not rest[1].isdigit() or int(rest[1]) > len(steps):
                raise BackendError(404, "DRS-5006", f"version {rest[1]}")
            return {"n": int(rest[1]), "yaml": text(int(rest[1]))}
        if rest in (["ops"], ["undo"], ["redo"]) and method == "POST":
            return await self._fake_step(d, row, rest[0], body or {}, ident)
        if rest == ["check"] and method == "POST":
            infos = [{"name": s["name"], **({"ref": s["ref"]} if s.get("ref") else {"document": row["docs"].get(s["name"], {})})} for s in d["samples"]]
            if not d["sutra"] or not infos:
                raise BackendError(400, "DRS-5001", "this design has no Sutra or no samples to check")
            m = await FakeBackend.check(self, d["sutra"], d["kind"], infos, ident)       # not self.check: other tests replace that on the shared backend
            d["status"] = "checked" if m["ok"] else "draft"
            return {**m, "rev": d["rev"]}
        raise BackendError(404, "DRS-1001", "no such design endpoint")

    async def _fake_step(self, d, row, what, body, ident):
        """The server's /ops, /undo and /redo in miniature: a stale baseRev is a 409, each operation appends a line to the Sutra."""
        steps = row.setdefault("steps", {"list": [], "at": 0})
        if what == "ops" and body.get("baseRev") != d["rev"]:
            raise BackendError(409, "DRS-5007", f"the design is at revision {d['rev']}, not {body.get('baseRev')}: reload and try again")
        problems = []
        if what == "ops":
            before = d["sutra"]
            for i, op in enumerate(body.get("ops") or []):
                if op.get("panel") == "nope":
                    problems.append({"op": i, "name": op["op"], "code": "DRS-5021", "message": "no panel 'nope' in this Sutra", "line": 0})
                elif op.get("op") == "text":
                    d["sutra"] = op["yaml"]
                else:
                    d["sutra"] += f"# {json.dumps(op, sort_keys=True)}\n"
            if d["sutra"] != before:
                steps["list"] = steps["list"][:steps["at"]] + [(before, d["sutra"])]
                steps["at"], d["rev"] = len(steps["list"]), d["rev"] + 1
        elif what == "undo" and steps["at"] > 0:
            steps["at"] -= 1
            d["sutra"], d["rev"] = steps["list"][steps["at"]][0], d["rev"] + 1
        elif what == "redo" and steps["at"] < len(steps["list"]):
            d["sutra"], d["rev"] = steps["list"][steps["at"]][1], d["rev"] + 1
            steps["at"] += 1
        elif what in ("undo", "redo"):
            raise BackendError(409, "DRS-5007", f"nothing to {what}")
        d["opsAt"], d["opsCount"], d["status"] = steps["at"], len(steps["list"]), "draft"
        out = {"rev": d["rev"], "yaml": d["sutra"], "status": "draft", "opsAt": steps["at"], "opsCount": len(steps["list"]), "problems": problems,
               "applied": len(body.get("ops") or []) - len(problems)}
        if d["samples"] and d["sutra"]:
            out["preview"] = await self.view("trade", "IRS-48213", ident)
        return out

    async def builder_suggest(self, body, ident=None):
        return {"path": body["path"], "suggestions": [{"kind": "kv", "score": 0.4, "reason": "the field as a labelled value", "area": "right",
                                                         "title": "Field", "options": {}, "columns": [{"label": "Field", "bind": body["path"]}]}]}

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
            return {"memory": {"budgetBytes": 2147483648, "usedBytes": 104857600, "heapMaxBytes": 4294967296, "heapUsedBytes": 1073741824},
                    "disk": {"freeBytes": 412316860416, "guardBytes": 21474836480, "state": "ok",
                             "folders": [{"name": "var/hot", "usedBytes": 12025908428, "limitBytes": 42949672960, "state": "ok", "oldest": ""}]},
                    "caches": [{"name": "engine", "type": "Layouts and shape fingerprints", "where": "heap", "stats": {"layouts": 12, "fingerprints": 40}},
                               {"name": "trading-stream", "type": "Connector", "where": "heap", "stats": {"memoryEntries": 3, "diskMb": 1.5}},
                               {"name": "var.hot-files", "owner": "var", "type": "VaR hot files", "where": "disk", "path": "data/var/hot",
                                "sizeBytes": 12025908428, "limitBytes": 42949672960, "entries": 33, "limitEntries": 60, "ttlSeconds": None,
                                "oldestAgeSeconds": 86400, "hits": 12431, "misses": 802, "freshness": "data-version", "dates": True,
                                "lastPurge": {"at": "2026-10-30T09:12:00Z", "by": "ops-admin", "date": "2026-10-29"}, "stats": {}}]}
        if path == "/caches/var.hot-files/dates":
            return [{"date": "2026-10-30", "state": "open", "sizeBytes": 5470000000, "versions": {"version": "7@1"}, "digest": "438361ce8f808055",
                     "lastUsedAt": "2026-10-30T10:00:00Z"}]
        if path.endswith("/purge"):
            return {"purged": [path.split("/")[2]], "elapsedMs": 0.4}
        if path == "/audit":
            return [{"at": "2026-09-30T12:00:00Z", "actor": "system", "action": "user-seeded", "subject": "drishti-dev-admin", "detail": ""}]
        if path == "/users" and method == "POST":
            if body.get("username") in self.users:
                raise BackendError(409, "DRS-6002", "exists")
            return {**body, "enabled": True}
        if path == "/packs" and method == "GET":                      # Admin -> Packs and the pack's own page
            return [{"name": "trading", "title": "Trading", "description": "Trades, books and counterparties", "version": "1.0.0", "loaded": True, "added": False,
                     "enabled": True, "sample": False, "extends": [], "requiredBy": [], "kinds": ["trade"], "connectors": ["trading-store"], "mnemonics": ["TRD"]}]
        if path == "/packs/trading/detail":
            return {"name": "trading", "code": "TRD", "routes": {"trade": "trading-store"}, "glossaryTerms": 0, "sutras": [{"name": "trade-view", "version": 1, "versions": [1], "kind": "trade"}],
                    "kinds": [{"kind": "trade", "mnemonic": "TRD", "label": "Trade", "declaredForm": False, "formFields": [], "about": False, "glossaryTerms": 0}]}
        if path == "/packs/trading/datasource":
            return {"directory": "/srv/connectors", "missing": [], "connectors": [{"name": "trading-store", "defined": True, "state": "RUNNING", "kinds": ["trade"]}]}
        if path == "/connectors/trading-store" and method == "GET":    # Admin -> Connectors -> one connector
            return {"name": "trading-store", "origin": "pack", "plugin": "delta", "kinds": ["trade"], "description": "", "enabled": True, "state": "RUNNING", "health": "UP",
                    "problems": [], "usedBy": [{"pack": "trading", "kinds": ["trade"]}], "template": "trading", "directory": "/srv/connectors", "settings": {"root": "/lake"},
                    "tls": None, "text": "", "history": [], "etag": None}
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

    async def functions(self, ident=None):
        """FUNC for a user (/api/v1/functions): only the commands that are on, of the user's packs; nothing of the switches."""
        return {"total": 1, "packs": [{"name": "finance", "code": "FIN", "title": "Finance", "commands": [
            {"code": "TRD", "kind": "trade", "label": "Trade", "description": "One trade.", "example": "TRD T-1"}]}]}

    datamap_state = "ready"

    async def datamap(self, ident=None):
        """DATAMAP (/api/v1/datamap): the server's inferred map, a trade joining a counterparty (every id found) and a book (one in
        four found: a finding), a computed exposure joined to the trade."""
        def kind(k, pack, code, label, count, computed=False):
            return {"kind": k, "pack": pack, "code": code, "label": label, "title": label, "description": f"One {label.lower()}.", "grain": "",
                    "class": "computed" if computed else "fundamental", "computed": computed, "count": None if computed else count,
                    "more": False, "example": None, "failed": None}

        def join(a, field, b, values, matched, state="checked"):
            return {"from": a, "field": field, "to": b, "declaredBy": "contract links", "state": state, "why": None, "rows": values,
                    "filled": values, "values": values, "matched": matched, "coverage": matched / values if values else None, "partial": False}
        return {"businessDate": "2026-10-27", "state": self.datamap_state, "built": None,
                "packs": [{"name": "trading", "code": "TRDS", "title": "Trading", "description": "", "extends": ["core"]},
                          {"name": "core", "code": "CORE", "title": "Core", "description": "", "extends": []},
                          {"name": "counterparty-risk", "code": "CCR", "title": "Counterparty credit risk", "description": "", "extends": []}],
                "kinds": [kind("trade", "trading", "TRD", "Trade", 15000), kind("counterparty", "core", "CPTY", "Counterparty", 24),
                          kind("book", "core", "BOOK", "Book", 30), kind("exposure-result", "counterparty-risk", "EXP", "Exposure", 0, True)],
                "joins": [join("trade", "COUNTERPARTY", "counterparty", 15000, 15000), join("trade", "PORTFOLIO", "book", 4, 1),
                          {**join("exposure-result", "TRADE_ID", "trade", 0, 0, "computed"), "why": "exposure-result is computed on demand, never stored"}]}

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

    subs_kept = {}
    exports_made = []
    # what a subscription or an alert may use besides mail, and whether the BI AI hooks are on (Wave D); tests change these per test
    notify_kept = {"webhooks": [], "sms": {"configured": False, "maxChars": 160, "phone": ""}}
    ai_status = {"enabled": False, "values": "labels-only", "assist": False}
    phones_set = []

    async def bi(self, method, path, ident, body=None, **params):
        if path == "/ai/status":
            return dict(self.ai_status)
        raise BackendError(404, "DRS-9001", "no BI in this stand-in")

    async def distribution_status(self, ident):
        return {"export": {"enabled": True}, "subscriptions": {"enabled": True, "perUser": 20, "mine": 0}}

    async def notify_options(self, ident):
        return json.loads(json.dumps(self.notify_kept))

    async def set_phone(self, phone, ident):
        self.phones_set.append(phone)
        return {"phone": "*" * max(0, len(phone) - 2) + phone[-2:]}

    async def embed_search(self, q, ident, limit=None):
        self.calls.append(("embed_search", q, ident.user, limit))
        if q.strip() == "boom":
            raise BackendError(403, "DRS-5002", "an embed token may only read views: this call is not in its scopes")
        return {"query": q, "results": [{"type": "entity", "kind": "trade", "id": "MX-20000001", "mnemonic": "TRD", "title": "MX-20000001", "subtitle": "Rates"}]}

    async def export(self, fmt, target, ident):
        self.exports_made.append((fmt, target, ident.user))
        if target.get("id") == "forbidden":
            raise BackendError(403, "DRS-9002", "you may not open this report")
        types = {"pdf": "application/pdf", "png": "image/png", "csv": "text/csv; charset=utf-8", "jsonl": "application/jsonl; charset=utf-8",
                 "xlsx": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"}
        return (f"file:{fmt}:{target.get('id')}".encode(), {"content-type": types[fmt], "content-disposition": f"attachment; filename=\"Daily risk.{fmt}\""})

    async def subscriptions(self, ident):
        return [s for s in self.subs_kept.values() if s["owner"] == ident.user]

    async def save_subscription(self, name, body, ident):
        if "whenever" in body.get("schedule", ""):
            raise BackendError(422, "DRS-9061", "'whenever' is not a schedule: use daily HH:MM, …")
        if any("@" in r for r in body.get("recipients", [])):
            raise BackendError(422, "DRS-9061", "is an email address, not a user: subscriptions go to users and roles only")
        t = body["target"]
        self.subs_kept[name] = {"name": name, "owner": ident.user, "title": "Report " + t.get("id", ""), "enabled": body.get("enabled", True), "nextRun": "2026-10-02T11:30:00Z",
                                "runs": [], **body}
        return self.subs_kept[name]

    async def delete_subscription(self, name, ident):
        self.subs_kept.pop(name, None)

    async def run_subscription(self, name, ident):
        run = {"id": "r1", "at": "2026-10-01T09:00:00Z", "trigger": "by hand", "status": "partial", "sent": 2, "skipped": 1, "failed": 1, "retrying": 0,
               "deliveries": [{"to": "a@x.test", "status": "sent", "attempts": 1}, {"to": "b@x.test", "status": "failed", "attempts": 3, "error": "refused"}]}
        self.subs_kept[name]["runs"].insert(0, run)
        return run

    async def pause_subscription(self, name, resume, ident):
        self.subs_kept[name]["enabled"] = resume
        return self.subs_kept[name]

    async def all_subscriptions(self, ident):
        if not getattr(ident, "is_admin", True):
            raise BackendError(403, "DRS-5002", "administrators only")
        return list(self.subs_kept.values())

    async def admin_pause_subscription(self, owner, name, resume, ident):
        self.subs_kept[name]["enabled"] = resume

    async def admin_delete_subscription(self, owner, name, ident):
        self.subs_kept.pop(name, None)

    alerts_kept = {}

    @staticmethod
    def _alert_words(b):
        c, s = b.get("condition", {}), b.get("source", {})
        label = {"visual": f"{s.get('report')} · {s.get('visual')}", "dataset": f"{s.get('dataset')} · {s.get('measure')}", "field": f"{s.get('kind')}/{s.get('id')} · {s.get('field')}"}.get(s.get("type"), "")
        say = {"threshold": f"{c.get('op')} {c.get('value'):g}" if "value" in c else "", "change": f"{c.get('op')} by {c.get('value')}", "nodata": f"no data by {c.get('by')}"}.get(c.get("type"), "")
        link = {"visual": f"/bi/r/{s.get('report')}/{s.get('page')}", "dataset": f"/bi/i/{s.get('dataset')}", "field": f"/v/{s.get('kind')}/{s.get('id')}"}.get(s.get("type"), "/")
        return label, say, link

    async def measure_alerts(self, ident):
        return [a for a in self.alerts_kept.values() if a["owner"] == ident.user]

    async def measure_alert(self, name, ident):
        if name not in self.alerts_kept:
            raise BackendError(404, "DRS-1001", f"no alert '{name}'")
        return self.alerts_kept[name]

    async def save_measure_alert(self, name, body, ident):
        if body.get("condition", {}).get("op") == "under":
            raise BackendError(422, "DRS-9062", "condition.op is above, below, between or outside")
        if body.get("condition", {}).get("type") == "threshold" and "value" not in body.get("condition", {}):
            raise BackendError(422, "DRS-9062", "condition.value is a number (for between and outside, the low edge)")
        label, say, link = self._alert_words(body)
        old = self.alerts_kept.get(name, {})
        self.alerts_kept[name] = {"name": name, "owner": ident.user, "enabled": body.get("enabled", True), "title": body.get("title") or label, "severity": body.get("severity", "warn"),
                                  "source": body["source"], "condition": body["condition"], "trigger": body["trigger"], "hysteresis": body.get("hysteresis", 0),
                                  "cooldown": body.get("cooldown", "30m"), "delivery": body["delivery"], "describe": say, "label": label, "link": link,
                                  "state": {}, "history": old.get("history", [])}
        return self.alerts_kept[name]

    async def delete_measure_alert(self, name, ident):
        self.alerts_kept.pop(name, None)

    async def check_measure_alert(self, name, ident):
        a = self.alerts_kept[name]
        a["state"] = {ident.user: {"active": True}}
        a["history"].insert(0, {"id": 7, "at": "2026-10-11T15:00:00Z", "user": ident.user, "action": "fired", "severity": "warn", "message": f"{a['title']}: now 1,200 ({a['describe']})",
                                 "delivered": {"inbox": "ok", "email": "off", "bridge": "off"}})
        return a

    async def pause_measure_alert(self, name, resume, ident):
        self.alerts_kept[name]["enabled"] = resume
        return self.alerts_kept[name]

    async def preview_measure_alert(self, body, ident):
        if body["source"].get("measure") == "secret":
            return {"status": "masked", "value": None, "asOf": None, "note": "secret", "condition": "above 1", "live": False}
        return {"status": "ok", "value": 1234.5, "asOf": "2026-10-09", "note": "", "condition": "above 1000", "holds": True, "live": True}

    async def all_measure_alerts(self, ident):
        if not getattr(ident, "is_admin", True):
            raise BackendError(403, "DRS-5002", "administrators only")
        return list(self.alerts_kept.values())

    async def board_alerts(self, ident):
        if not getattr(ident, "is_admin", True):
            raise BackendError(403, "DRS-5002", "administrators only")
        return [{"name": "t1", "owner": "ash", "title": "Desk wall · Net exposure", "severity": "critical", "board": {"board": "desk-wall", "page": "p1", "tile": "t1"},
                 "describe": "reaches crit", "delivery": {"inbox": True, "email": True, "bridge": False}, "trigger": {"mode": "live", "debounce": "10s"}, "activeFor": 2,
                 "link": "/bi/b/desk-wall", "history": [{"at": "2026-10-11T15:00:00Z", "user": "bob", "action": "fired", "message": "Desk wall · Net exposure — is now red"}]}]

    async def admin_pause_measure_alert(self, owner, name, resume, ident):
        self.alerts_kept[name]["enabled"] = resume

    async def admin_delete_measure_alert(self, owner, name, ident):
        self.alerts_kept.pop(name, None)

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

    HISTORY = {("trade", "1000015"): "trade-1000015-30.json", ("equity", "EQ-CSCA"): "equity-EQ-CSCA-3M.json"}

    async def history(self, kind, id_, ident=None, **params):
        """The history contract's fixtures (drishti-testkit history/, HISTORY_QUERIES.md section 8), whatever the range; with
        ``diff=true`` each changed row carries its changes, as the server's DIFF does."""
        self.calls.append(("history", kind, id_, dict(params)))
        name = self.HISTORY.get((kind, id_))
        if name is None:
            raise BackendError(404, "DRS-1001", f"no source holds {kind}/{id_}")
        data = json.loads((CONSOLE.parent / "drishti-testkit" / "src" / "main" / "resources" / "history" / name).read_text())
        if params.get("diff") == "true":
            labels = {c["path"]: c.get("label") for c in data["columns"]}
            prev = None
            for r in data["rows"]:
                r["changes"] = [{"path": p, "label": labels.get(p, p), "kind": "changed", "before": (prev or {}).get("values", {}).get(p),
                                 "after": r["values"].get(p), "delta": None} for p in r["changed"] if not p.startswith("parts.")] or None
                prev = r
        return data

    async def search_compare(self, q, from_, to, ident=None):
        return {"kind": "trade", "mnemonic": "TRD", "columns": ["$.mtm"], "labels": {"$.mtm": "MTM"}, "matched": 2, "scanned": 2,
                "elapsedMs": 1, "partial": False, "from": from_, "to": "2026-09-30",
                "rows": [{"ref": {"kind": "trade", "id": "T-1"}, "title": "T-1", "status": None, "values": {"$.mtm": {"from": 100, "to": 125, "delta": 25.0}}},
                         {"ref": {"kind": "trade", "id": "T-7"}, "title": "T-7", "status": "added", "values": {"$.mtm": {"from": None, "to": 5}}}]}

    async def my_tokens(self, ident=None):
        return [{"id": "abc123def456", "user": "drishti-dev-admin", "name": "Risk notebook", "createdAt": "2026-10-01T09:00:00Z",
                 "expiresAt": None, "lastUsedAt": None, "revokedAt": None, "active": True, "scopes": ["read"]},
                {"id": "ci0000000001", "user": "drishti-dev-admin", "name": "CI deploy", "createdAt": "2026-10-01T09:00:00Z",
                 "expiresAt": "2026-12-30T09:00:00Z", "lastUsedAt": None, "revokedAt": None, "active": True,
                 "scopes": ["design:write", "design:approve"]}]

    scopes_made = []

    async def token_scopes(self, ident=None):
        return {"writeMaxDays": 90, "scopes": [
            {"name": "design:write", "description": "Create, edit, check, propose, share and bind Designs."},
            {"name": "design:approve", "description": "Approve or reject proposed Sutras."},
            {"name": "packs:admin", "description": "Load, unload, switch on and off, install and roll back packs."}]}

    async def create_token(self, name, days, ident=None, scopes=None):
        if not name.strip():
            raise BackendError(400, "DRS-5001", "give the token a name of 1-100 characters (what uses it)")
        self.tokens_made.append((name, days))
        self.scopes_made.append(list(scopes or []))
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

    async def pack_coverage(self, name, ident=None):
        return {"pack": name, "kinds": []}

    async def view_on(self, kind, id_, ident, business_date):
        """Compare's side by side: the view as of a date (the fake has one view whatever the date)."""
        return await self.view(kind, id_, ident)

    async def pack_readme(self, name, ident=None):
        """A pack's README (the README standard): the trading pack has one, others none."""
        if name != "trading":
            raise BackendError(404, "DRS-1001", f"pack '{name}' has no README.md")
        return {"pack": "trading", "title": "Trading", "version": "1.2.0",
                "meta": {"pack": "trading", "summary": "Trades in 125 products.", "kinds": ["trade"], "commands": {"TRD": "trade"},
                         "sources": [{"name": "Synthetic sample data", "licence": "Proprietary", "internal-only": False},
                                     {"name": "A feed", "licence": "Restricted", "internal-only": True}], "refresh": "tools/packgen/readme.py trading"},
                "markdown": "# The trading pack\n\n## What it is\n\nTrades.\n\n## Data structures\n\n### `trade`\n\n"
                            "| Field | Type | Example | Description |\n|---|---|---|---|\n| `TRADE_ID` | string | `MX-1` | The document's id |\n\n"
                            "Nested structures:\n\n| Path | Fields |\n|---|---|\n| `LEGS[].CASH_FLOWS[]` | `FLOW_ID` (string) |\n\n"
                            "<script>alert(1)</script>\n\n[Packs](../../../docs/guides/PACK_DEVELOPER_GUIDE.md#part-v-domain-packs-the-packs-that-ship-turning-them-on-who-sees-which) [web](https://example.com) [bad](javascript:alert(1))\n"}

    async def pack_overview(self, name, ident=None):
        if name.upper() not in ("MKT", "MARKET-DATA"):
            raise BackendError(400, "DRS-5001", f"no pack '{name}' is switched on")
        return {"name": "market-data", "code": "MKT", "title": "Market data", "description": "Curves and surfaces.", "version": "1.0.0",
                "extends": ["core"], "kinds": [
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


@pytest.fixture(autouse=True)
def _backend_state_is_per_test(request):
    """The stand-in server is one object for the whole session, and several tests replace its methods or fields for good
    (``backend.admin = ...``). Put its instance state back after each test that uses it, so a test passes whichever tests ran
    before it on its worker (pytest-xdist hands each worker whole files in an order of its own)."""
    if "backend" not in request.fixturenames and "client" not in request.fixturenames:
        yield
        return
    be = request.getfixturevalue("backend")
    # one level deeper than the fields: a list or dict a test appends to in place (backend.calls) must not leak into the next test
    saved = {k: (v.copy() if isinstance(v, (list, dict, set)) else v) for k, v in be.__dict__.items()}
    yield
    be.__dict__.clear()
    be.__dict__.update(saved)


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


def open_studio(client, url, **params):
    """What the confirmation page's button does: GET the address (it must only ask), then POST the same fields to /studio. A
    ``design=`` address opens a Design that exists and is a plain 302. Returns the redirect response."""
    from urllib.parse import parse_qsl, urlsplit
    parts = urlsplit(url)
    q = dict(parse_qsl(parts.query, keep_blank_values=True))
    q.update(params)
    asked = client.get(parts.path, params=q, follow_redirects=False)
    if asked.status_code == 302:                       # a Design of yours, or another page: nothing is created by asking
        return asked
    assert asked.status_code == 200 and "data-start-form" in asked.text, (url, asked.status_code)
    form = {k: v for k, v in q.items() if k not in ("design", "sample")}
    if "sutra" in q:
        form["sutra_given"] = "1"
    return client.post("/studio", data=form, follow_redirects=False)
