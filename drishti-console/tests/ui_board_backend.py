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

"""A stand-in server for the UI Board console (P4): the Chip Store from the real seed chips (``config/ui/chips``), saved boards with revisions,
ETags and shares, a small board check (BC-03 required column unmapped, BC-04 incompatible trace, BC-06 unknown instance or pin), and the render
stream with P3's event shapes exactly (UI_BOARD.md 12.3, ``UiRenderApiTest``): ``render``, ``layout`` (frames before data), ``chip`` with
``state`` data / waiting / running / error, ``frame``, ``done``, ``ended``; pin changes repaint the dependants on the same stream. Synthetic data
only: twenty trades of the trading pack (``MTM_USD`` masked) and their cash flows from a ``trading-risk`` model. Used by test_ui_board.py,
test_ui_board_browser.py and ui_board_shots.py."""
from __future__ import annotations

import asyncio
import copy
import hashlib
import json
import threading
import time
from pathlib import Path

import yaml

from conftest import CONSOLE, FakeBackend
from core.backend import BackendError

CHIPS = CONSOLE.parent / "config" / "ui" / "chips"
SCHEMA = json.loads((Path(__file__).resolve().parent / "fixtures" / "rachana_schema.json").read_text())    # RachanaSchema.build: the chip body's inspector
MASK = "•••"
CCY = ["USD", "EUR", "GBP", "JPY"]
TRADES = [{"TRADE_ID": str(1000010 + i), "DEAL_NUMBER": f"D-{5000 + i}", "TRADE_DATE": f"2026-0{1 + i % 9}-1{i % 10}",
           "COUNTERPARTY_NAME": ["Acme Bank", "Borealis Capital", "Cobalt Funds", "Delta Asset Mgmt"][i % 4], "BOOK": "FX-LDN-01",
           "NOTIONAL": 1_000_000.0 * (i + 1), "MTM_USD": round((i - 7) * 12_345.5, 2), "INSTRUMENT_TYPE": ["FX_FWD", "FX_SWAP"][i % 2],
           "BUY_SELL": ["BUY", "SELL"][i % 2], "CCY": CCY[i % 4], "START_DATE": "2026-03-01", "MATURITY_DATE": f"2027-0{1 + i % 9}-15", "STATUS": "VALIDATED"}
          for i in range(20)]
FLOWS = [{"cashflows.TRADE_ID": t["TRADE_ID"], "cashflows.PAY_DATE": f"2026-{k + 4:02d}-15", "cashflows.CCY": t["CCY"], "FLOW_AMOUNT": round(t["NOTIONAL"] * 0.01 * (k + 1), 2)}
         for t in TRADES for k in range(3)]
FORM = [{"path": "TRADE_ID", "label": "Trade id", "type": "id"}, {"path": "DEAL_NUMBER", "label": "Deal", "type": "text"},
        {"path": "TRADE_DATE", "label": "Trade date", "type": "date"}, {"path": "COUNTERPARTY_NAME", "label": "Counterparty", "type": "text"},
        {"path": "NOTIONAL", "label": "Notional", "type": "amount"}, {"path": "MTM_USD", "label": "MTM (USD)", "type": "amount"},
        {"path": "INSTRUMENT_TYPE", "label": "Instrument", "type": "enum"}, {"path": "BUY_SELL", "label": "Buy/sell", "type": "enum"},
        {"path": "CCY", "label": "Currency", "type": "enum"}, {"path": "START_DATE", "label": "Start", "type": "date"},
        {"path": "MATURITY_DATE", "label": "Maturity", "type": "date"}, {"path": "STATUS", "label": "Status", "type": "enum"}]
MODEL = {"name": "trading-risk", "measures": [{"name": "FLOW_AMOUNT"}],
         "tables": [{"name": "cashflows", "columns": [{"name": "TRADE_ID", "key": True}, {"name": "PAY_DATE", "type": "date"}, {"name": "CCY", "type": "string"}]}]}
COMPAT = {"id": {"id", "filter", "dimension", "text"}, "dimension": {"dimension", "filter", "text"}, "date": {"date", "filter", "period", "text"},
          "period": {"period", "filter"}, "rowset": {"rowset"}, "filter": {"filter"}, "measure": {"measure", "number", "text"}, "text": {"text"},
          "number": {"number", "measure", "text"}, "document": {"document"}}


def _t(spec) -> str:
    t = spec.get("type") if isinstance(spec, dict) else spec
    return str(t or "").split("<")[0]


def load_chips() -> dict:
    out = {}
    for f in sorted(CHIPS.glob("*.chip.yaml")):
        d = yaml.safe_load(f.read_text())
        out[f"{d['chip']}@{d['version']}"] = d
    return out


class UiBoardBackend(FakeBackend):
    """The stand-in server of the UI Board tests."""

    def __init__(self):
        super().__init__()
        self.chips = load_chips()
        self.boards: dict[str, dict] = {}            # name -> {doc, version, etag, revisions: {n: yaml}, share}
        self.renders: dict[str, dict] = {}            # id -> {queue, board, chips, sel}
        self.pin_calls: list = []
        self.drafts: dict = {}
        self.live_every = 0.5

    # ---- the check -----------------------------------------------------------------------------------------------------------------
    def check(self, doc: dict) -> list[dict]:
        p = []
        chips = doc.get("chips") or {}
        fed = {t.get("to") for t in doc.get("traces") or []}
        for name, inst in chips.items():
            c = self.chips.get(str(inst.get("use")))
            if c is None:
                p.append({"rule": "BC-02", "level": "error", "path": f"chips.{name}.use", "message": f"no chip {inst.get('use')} in the Chip Store", "fix": "choose one from the store"})
                continue
            for pin, spec in (c.get("pins", {}).get("in") or {}).items():
                if spec.get("role") != "data" or f"{name}.{pin}" in fed or not inst.get("supply"):
                    continue
                for col, cs in (spec.get("shape") or {}).items():
                    if cs.get("required") and f"{pin}.{col}" not in (inst.get("map") or {}):
                        p.append({"rule": "BC-03", "level": "error", "path": f"chips.{name}.map.{pin}.{col}", "message": f"the required column {pin}.{col} is not mapped",
                                  "fix": "map a supplier attribute to it"})
        for i, t in enumerate(doc.get("traces") or []):
            f, to = str(t.get("from", "")), str(t.get("to", ""))
            if f.startswith("board.params."):
                ps = doc.get("params") or {}
                src = _t(ps.get(f[13:], {})) if f[13:] in ps else None
                if src is None:
                    p.append({"rule": "BC-06", "level": "error", "path": f"traces[{i}].from", "message": f"the board has no parameter '{f[13:]}'", "fix": "declare it under params:"})
                    continue
            else:
                a, _, pin = f.partition(".")
                c = self.chips.get(str((chips.get(a) or {}).get("use")))
                outs = (c or {}).get("pins", {}).get("out") or {}
                if a not in chips or pin not in outs:
                    p.append({"rule": "BC-06", "level": "error", "path": f"traces[{i}].from", "message": f"'{f}' names no out pin", "fix": "write <instance>.<out pin>"})
                    continue
                src = _t(outs[pin])
            b, _, pin = to.partition(".")
            c = self.chips.get(str((chips.get(b) or {}).get("use")))
            ins = (c or {}).get("pins", {}).get("in") or {}
            if b not in chips or pin not in ins:
                p.append({"rule": "BC-06", "level": "error", "path": f"traces[{i}].to", "message": f"'{to}' names no in pin", "fix": "write <instance>.<in pin>"})
                continue
            dst = _t(ins[pin])
            if dst not in COMPAT.get(src, {src}):
                p.append({"rule": "BC-04", "level": "error", "path": f"traces[{i}]", "message": f"a {src} cannot feed the {dst} pin {to}", "fix": "wire a compatible out pin"})
        return p

    # ---- the routes ------------------------------------------------------------------------------------------------------------------
    @staticmethod
    def _doc(content) -> dict:
        try:
            d = yaml.safe_load(content.decode() if isinstance(content, bytes) else content or "")
        except yaml.YAMLError as e:
            raise BackendError(400, "DRS-2070", f"not YAML: {e}") from e
        if not isinstance(d, dict):
            raise BackendError(400, "DRS-2070", "a board is a map")
        return d

    def _refuse(self, status, code, detail, problems=None):
        e = BackendError(status, code, detail)
        e.extra = {"code": code, "detail": detail, **({"problems": problems} if problems is not None else {})}
        e.problems = problems or []
        raise e

    async def ui(self, method, path, ident, content=None, content_type=None, headers=None, wait=None, **params):
        self.calls.append(("ui", method, path, params))
        parts = [x for x in path.split("/") if x]
        h = {k.lower(): v for k, v in (headers or {}).items()}
        if parts == ["boards"] and method == "GET":
            return 200, [{"id": f"user:{ident.user}:{n}", "scope": "user", "owner": ident.user, "board": n, "access": "mine", "title": b["doc"].get("title", ""),
                          "version": b["version"], "chips": len(b["doc"].get("chips") or {}), "etag": b["etag"]} for n, b in sorted(self.boards.items())], None
        if parts == ["boards", "check"]:
            probs = self.check(self._doc(content))
            return 200, {"ok": not [x for x in probs if x["level"] == "error"], "problems": probs}, None
        if parts and parts[0] == "boards" and len(parts) >= 2:
            name = parts[1]
            b = self.boards.get(name)
            if len(parts) == 2 and method == "GET":
                if not b:
                    self._refuse(404, "DRS-2073", f"no board {name}")
                return 200, {"id": f"user:{ident.user}:{name}", "board": name, "scope": "user", "owner": ident.user, "etag": b["etag"], "sharedWith": b.get("share", []),
                             "document": copy.deepcopy(b["doc"]), "yaml": b["yaml"]}, b["etag"]
            if len(parts) == 2 and method == "PUT":
                doc = self._doc(content)
                if b and h.get("if-match") != b["etag"]:
                    self._refuse(409, "DRS-5057", "the board changed since you read it: reload")
                if not b and h.get("if-match"):
                    self._refuse(409, "DRS-5057", "the board is gone")
                probs = [x for x in self.check(doc) if x["level"] == "error"]
                if probs:
                    self._refuse(422, "DRS-2071", "the board check failed", probs)
                version = (b["version"] + 1) if b else 1
                doc.update({"rachana": 1, "board": name, "version": version, "owner": ident.user})
                text = yaml.safe_dump(doc, sort_keys=False)
                etag = '"' + hashlib.sha256(text.encode()).hexdigest()[:16] + '"'
                revs = dict(b["revisions"]) if b else {}
                if b:
                    revs[b["version"]] = b["yaml"]
                self.boards[name] = {"doc": doc, "yaml": text, "version": version, "etag": etag, "revisions": revs, "share": (b or {}).get("share", [])}
                return 200, {"id": f"user:{ident.user}:{name}", "board": name, "etag": etag, "document": doc, "yaml": text}, etag
            if parts[2:] == ["revisions"]:
                return 200, [{"revision": n} for n in sorted((b or {}).get("revisions", {}), reverse=True)], None
            if len(parts) == 4 and parts[2] == "revisions":
                return 200, {"yaml": b["revisions"][int(parts[3])]}, None
            if parts[2:] == ["share"]:
                b["share"] = json.loads(content or b"{}").get("people", [])
                return 200, {"board": name, "sharedWith": b["share"]}, None
            if parts[2:] == ["copy"]:
                body = json.loads(content or b"{}")
                return 200, {"id": f"workspace:{body['workspace']}:{body.get('name') or name}", "board": body.get("name") or name}, None
        if parts and parts[0] == "chips":
            if len(parts) == 1:
                q = str(params.get("q") or "").lower()
                latest = {}
                for ref, c in self.chips.items():
                    if q and q not in (c["chip"] + " " + c.get("title", "") + " " + " ".join(c.get("tags") or [])).lower():
                        continue
                    if c["chip"] not in latest or c["version"] > latest[c["chip"]]["version"]:
                        latest[c["chip"]] = c
                return 200, [{"chip": c["chip"], "title": c.get("title"), "category": c.get("category"), "tags": c.get("tags"), "purpose": c["manual"]["purpose"],
                              "latest": c["version"], "certified": True, "seed": True, "mine": False} for c in latest.values()], None
            ref = f"{parts[1]}@{parts[2]}" if len(parts) >= 3 else ""
            if len(parts) == 3 and parts[2] == "draft":
                if method == "PUT":
                    self.drafts[parts[1]] = content.decode()
                    return 200, {"chip": parts[1]}, '"d1"'
                if parts[1] not in self.drafts:
                    self._refuse(404, "DRS-2072", "no draft")
                return 200, {"chip": parts[1], "yaml": self.drafts[parts[1]]}, '"d1"'
            c = self.chips.get(ref)
            if c is None:
                self._refuse(404, "DRS-2072", f"no chip {ref}")
            if parts[3:] == ["manual"]:
                return 200, {"chip": c["chip"], "version": c["version"], "title": c.get("title"), "state": "certified", "pins": c["pins"], "manual": c["manual"]}, None
            return 200, {"chip": c["chip"], "version": c["version"], "state": "certified", "document": c, "yaml": yaml.safe_dump(c, sort_keys=False)}, None
        if parts and parts[0] == "renders" and len(parts) == 3 and parts[2] == "pins":
            return 200, await self._pin(parts[1], json.loads(content or b"{}")), None
        if parts and parts[0] == "renders" and len(parts) == 2 and method == "DELETE":
            r = self.renders.pop(parts[1], None)
            if r:
                r["queue"].put_nowait(None)
            return 204, None, None
        self._refuse(404, "DRS-9001", f"no stand-in route {method} {path}")

    async def rachana_schema(self, ident=None):
        return SCHEMA

    async def form(self, pack, kind="", ident=None):
        if pack != "trading":
            raise BackendError(404, "DRS-1001", "no such pack")
        return {"pack": pack, "form": {"kind": kind, "fields": copy.deepcopy(FORM)}}

    async def bi(self, method, path, ident, body=None, **params):
        if path == "/models/trading-risk":
            return copy.deepcopy(MODEL)
        return await super().bi(method, path, ident, body, **params)

    # ---- the render ---------------------------------------------------------------------------------------------------------------
    def _rows(self, inst: dict, pin: str, src: list[dict]) -> list[dict]:
        cols = {k[len(pin) + 1:]: v for k, v in (inst.get("map") or {}).items() if k.startswith(pin + ".")}
        out = []
        for r in src:
            row = {}
            for col, attr in cols.items():
                if isinstance(attr, list):
                    row[col] = [{"name": a, "value": MASK if a == "MTM_USD" else r.get(a)} for a in attr]
                else:
                    row[col] = MASK if attr == "MTM_USD" else r.get(attr)
            out.append(row)
        return out

    def _paint(self, r: dict, name: str) -> dict:
        inst, chip = r["board"]["chips"][name], self.chips[r["board"]["chips"][name]["use"]]
        sup = inst.get("supply") or {}
        traced = {t["to"].split(".", 1)[1]: t for t in r["board"].get("traces") or [] if t["to"].split(".")[0] == name}
        ins = chip["pins"].get("in") or {}
        if sup.get("connector") and not sup["connector"].endswith("-search"):
            sel = next((r["sel"].get(t["from"]) for p, t in traced.items() if p == "id"), None)
            if sel is None:
                return {"instance": name, "state": "waiting", "waiting": ["id"]}
            src = [t for t in TRADES if t["TRADE_ID"] == sel]
        elif sup.get("model"):
            if sup["model"] == "broken-model":
                return {"instance": name, "state": "error", "code": "DRS-9010", "message": "the model broken-model is not readable by you"}
            src = FLOWS
            for p, t in traced.items():
                v = r["sel"].get(t["from"])
                col = (t.get("transform") or {}).get("filter-on")
                if v is not None and col:
                    attr = (inst.get("map") or {}).get(col)
                    src = [x for x in src if str(x.get(attr)) == str(v)]
        elif sup.get("connector") or sup.get("live"):
            src = TRADES
        else:
            src = []
        pins, keys = {}, None
        for p, spec in ins.items():
            if spec.get("role") == "data" and p not in traced:
                rows = self._rows(inst, p, src)
                pins[p] = (rows[0] if rows else None) if _t(spec) == "document" else rows
        if any(o.get("of") for o in (chip["pins"].get("out") or {}).values()) and sup.get("connector"):
            keys = [x["TRADE_ID"] for x in src]
        controls = dict(inst.get("controls") or {})
        vm = {"chip": inst["use"], "title": inst.get("title") or chip.get("title"), "body": chip.get("body"), "pins": pins, "controls": controls}
        if keys is not None:
            vm["keys"] = keys
        masked = [k.split(".", 1)[1] for k, v in (inst.get("map") or {}).items() if (v == "MTM_USD" or (isinstance(v, list) and "MTM_USD" in v)) and "." in k]
        return {"instance": name, "state": "data", "viewmodel": vm, "meta": {"rows": len(src), "truncated": False, "reused": False, "masked": masked}}

    def _layout(self, board: dict, device: str) -> dict:
        lay = board.get("layout") or {}
        out = {"device": device, "theme": lay.get("theme")}
        if device == "phone":
            order = list((lay.get("phone") or {}).get("order") or [])
            order += [c for c in board["chips"] if c not in order]
            out.update(order=order, collapsed=(lay.get("phone") or {}).get("collapsed") or [])
        else:
            g = lay.get("desktop") or {}
            out.update(columns=g.get("columns", 12), areas=[{"chip": a["chip"], "at": a["at"], "span": a["span"]} for a in g.get("areas") or []])
        out["frames"] = {n: {"title": i.get("title") or self.chips[i["use"]].get("title"), "chip": i["use"], "skeleton": self.chips[i["use"]].get("category") or "panel"}
                         for n, i in board["chips"].items()}
        return out

    async def _pin(self, rid: str, body: dict) -> dict:
        r = self.renders.get(rid)
        if r is None:
            self._refuse(410, "DRS-2079", "the render ended")
        self.pin_calls.append(body)
        src = f"{body['instance']}.{body['pin']}"
        r["sel"][src] = body.get("value")
        downstream = [t["to"].split(".")[0] for t in r["board"].get("traces") or [] if t["from"] == src]
        t0 = time.monotonic()
        for name in dict.fromkeys(downstream):
            r["queue"].put_nowait(("chip", self._paint(r, name)))
        ms = int((time.monotonic() - t0) * 1000)
        r["queue"].put_nowait(("done", {"ms": ms, "chips": len(downstream), "errors": 0}))
        return {"renderId": rid, "chips": list(dict.fromkeys(downstream)), "errors": 0, "ms": ms}

    async def ui_events(self, method, path, ident, body=None, params=None, opened=None):
        params = params or {}
        if method == "POST":
            board = body.get("board")
            board = self._doc(board) if isinstance(board, str) else copy.deepcopy(board)
            device = body.get("device") or "desktop"
        else:
            name = path.split("/")[2]
            if name not in self.boards:
                self._refuse(404, "DRS-2073", f"no board {name}")
            board, device = copy.deepcopy(self.boards[name]["doc"]), params.get("device") or "desktop"
        probs = [x for x in self.check(board) if x["level"] == "error"]
        if probs:
            self._refuse(422, "DRS-2071", "the board check failed", probs)
        rid = f"rnd-{len(self.renders) + 1:04d}"
        r = self.renders[rid] = {"queue": asyncio.Queue(), "board": board, "sel": {}}
        yield "render", json.dumps({"renderId": rid, "board": board.get("board"), "boardHash": "sha256:stand-in", "chips": {n: i["use"] for n, i in board["chips"].items()}})
        yield "layout", json.dumps(self._layout(board, device))
        errors = 0
        for name, inst in board["chips"].items():
            if (inst.get("supply") or {}).get("model"):
                yield "chip", json.dumps({"instance": name, "state": "running", "runId": "run-77"})
            e = self._paint(r, name)
            errors += e["state"] == "error"
            yield "chip", json.dumps(e)
        yield "done", json.dumps({"ms": 12, "chips": len(board["chips"]), "errors": errors})
        if params.get("stream") == "once" or (body or {}).get("stream") == "once":
            self.renders.pop(rid, None)
            return
        live = [n for n, i in board["chips"].items() if (i.get("supply") or {}).get("live")]
        tick = 0
        while rid in self.renders:
            try:
                item = await asyncio.wait_for(r["queue"].get(), timeout=self.live_every if live else 3600)
            except asyncio.TimeoutError:
                tick += 1
                for n in live:
                    e = self._paint(r, n)
                    rows = e["viewmodel"]["pins"].get("doc") or {}
                    if isinstance(rows, dict):
                        rows["id"] = f"{rows.get('id')} #{tick}"
                    yield "frame", json.dumps({"instance": n, "patch": e["viewmodel"]})
                continue
            if item is None:
                yield "ended", json.dumps({"code": "DRS-2079", "message": "the render was closed"})
                return
            yield item[0], json.dumps(item[1])




def serve(backend=None):
    """Starts the console in this process against the stand-in server; returns (url, backend, stop)."""
    import uvicorn

    from core.app import create_app
    from core.config import load_settings
    from test_live_tabs_browser import _free_port

    app = create_app(load_settings(CONSOLE / "config"))
    app.state.backend = backend or UiBoardBackend()
    port = _free_port()
    server = uvicorn.Server(uvicorn.Config(app, host="127.0.0.1", port=port, log_level="warning", timeout_graceful_shutdown=2))
    thread = threading.Thread(target=server.run, daemon=True)
    thread.start()
    deadline = time.monotonic() + 15
    while not server.started and time.monotonic() < deadline:
        time.sleep(0.05)
    assert server.started

    def stop():
        server.should_exit = True
        thread.join(10)
    return f"http://127.0.0.1:{port}", app.state.backend, stop
