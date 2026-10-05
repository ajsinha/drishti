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

"""`drishti.py server smoke`: prove a running server serves what it should.

Checks, in order: health UP; each pack named (or every enabled pack) is loaded and enabled; no Sutra problems (the same list the
Admin pages show); then for every kind of those packs, a few sample ids on the latest business date and on earlier ones: open
the view, expect 200 and non-empty panels, record the time. `run(api, ...)` returns a report dict; `render(report)` prints it.
`api` is anything with `json(method, path)` that raises an exception with a `status` attribute on an HTTP error.
"""
from __future__ import annotations

import datetime
import time
import urllib.parse


def q(s: str) -> str:
    return urllib.parse.quote(s, safe="")


def status_of(e: Exception) -> int | None:
    return getattr(e, "status", None)


class Report:
    def __init__(self):
        self.checks: list[dict] = []
        self.views: list[dict] = []

    def check(self, name: str, status: str, detail: str = "") -> None:
        self.checks.append({"name": name, "status": status, "detail": detail})

    @property
    def failures(self) -> int:
        return sum(1 for c in self.checks if c["status"] == "fail") + sum(1 for v in self.views if v["status"] == "fail")

    def as_dict(self) -> dict:
        ms = sorted(v["ms"] for v in self.views if v.get("ms") is not None)
        return {"ok": self.failures == 0, "failures": self.failures, "checks": self.checks, "views": self.views,
                "timings": {"count": len(ms), "p50Ms": ms[len(ms) // 2] if ms else None, "maxMs": ms[-1] if ms else None}}


def earlier_dates(bd: dict, count: int) -> list[str]:
    """The latest business date and up to count-1 earlier ones (the server rolls a non-business day back)."""
    out = [bd["current"]]
    if count > 1 and bd.get("previous"):
        out.append(bd["previous"])
        d = datetime.date.fromisoformat(bd["previous"])
        floor = bd.get("earliest") or "0000-01-01"
        while len(out) < count:
            d -= datetime.timedelta(days=7 if len(out) > 2 else 1)
            if d.isoformat() < floor:
                break
            out.append(d.isoformat())
    return out[:count]


def sample_ids(api, kind: str, n: int) -> list[str]:
    res = api.json("GET", "/api/v1/search?q=" + q(kind))
    return [r["ref"]["id"] for r in (res.get("rows") or [])[:n]]


def run(api, packs: list[str] | None = None, ids: int = 3, dates: int = 2, kinds: list[str] | None = None) -> dict:
    rep = Report()
    try:
        h = api.json("GET", "/actuator/health")
        rep.check("health", "ok" if h.get("status") == "UP" else "fail", f"status {h.get('status')}")
    except Exception as e:                       # noqa: BLE001 - every failure is a line of the report
        rep.check("health", "fail", str(e))
        return rep.as_dict()
    rows, problems = [], {}
    try:
        rows = api.json("GET", "/api/v1/admin/packs")
        admin = api.json("GET", "/api/v1/admin/health")
        problems = {p["name"]: p.get("sutraProblems") or [] for p in admin.get("packs", [])}
    except Exception as e:                       # noqa: BLE001
        if status_of(e) in (401, 403) and kinds:
            rep.check("packs", "warn", "the pack list needs an administrator; checking the --kind list only")
        else:
            rep.check("packs", "fail", f"{e}  (the pack list needs an administrator token, or pass --kind)")
            return rep.as_dict()
    by_name = {r["name"]: r for r in rows}
    chosen = packs or [r["name"] for r in rows if r.get("enabled")]
    kind_list = list(kinds or [])
    for name in chosen:
        r = by_name.get(name)
        if r is None:
            rep.check(f"pack {name}", "fail", "not known to the server (is it in DRISHTI_PACKS or on disk?)")
            continue
        if not r.get("loaded") or not r.get("enabled"):
            rep.check(f"pack {name}", "fail", f"loaded {bool(r.get('loaded'))}, enabled {bool(r.get('enabled'))}")
            continue
        bad = problems.get(name) or []
        if bad:
            rep.check(f"pack {name}", "fail", f"{len(bad)} Sutra file(s) with problems: " + "; ".join(f"{b['file']}: {b['problems'][0]}" for b in bad[:3]))
        else:
            rep.check(f"pack {name}", "ok", f"loaded, enabled, v{r.get('version')}, {len(r.get('kinds') or [])} kinds, no Sutra problems")
        kind_list += [k for k in (r.get("kinds") or []) if k not in kind_list]
    try:
        days = earlier_dates(api.json("GET", "/api/v1/business-date"), max(1, dates))
    except Exception as e:                       # noqa: BLE001
        rep.check("business dates", "fail", str(e))
        return rep.as_dict()
    for kind in kind_list:
        try:
            picked = sample_ids(api, kind, ids)
        except Exception as e:                   # noqa: BLE001
            rep.check(f"kind {kind}", "warn", f"no sample ids ({str(e)[:90]})")
            continue
        if not picked:
            rep.check(f"kind {kind}", "warn", "no documents to sample")
            continue
        for i, date in enumerate(days):
            for doc in picked:
                rep.views.append(open_view(api, kind, doc, date, latest=i == 0))
    return rep.as_dict()


def open_view(api, kind: str, doc: str, date: str, latest: bool) -> dict:
    row = {"kind": kind, "id": doc, "date": date, "status": "ok", "panels": 0, "empty": 0, "ms": None, "detail": ""}
    t0 = time.perf_counter()
    try:
        v = api.json("GET", f"/api/v1/views/{q(kind)}/{q(doc)}?asOf={date}")
    except Exception as e:                       # noqa: BLE001
        row["ms"] = round((time.perf_counter() - t0) * 1000, 1)
        if status_of(e) == 404 and not latest:
            row.update(status="skip", detail="not present on this date")
        else:
            row.update(status="fail", detail=str(e)[:160])
        return row
    row["ms"] = round((time.perf_counter() - t0) * 1000, 1)
    panels = v.get("panels") or []
    row["panels"], row["empty"] = len(panels), sum(1 for p in panels if p.get("empty"))
    if not panels or row["empty"] == len(panels):
        row.update(status="fail", detail="the view has no panel with content")
    return row


def render(rep: dict, say=print) -> None:
    for c in rep["checks"]:
        say(f"{c['status'].upper():<5}{c['name']:<28}{c['detail']}")
    if rep["views"]:
        say(f"\n{'kind':<22}{'id':<26}{'date':<12}{'panels':<8}{'empty':<7}{'ms':<9}result")
        for v in rep["views"]:
            say(f"{v['kind']:<22}{v['id'][:24]:<26}{v['date']:<12}{v['panels']:<8}{v['empty']:<7}{v['ms'] if v['ms'] is not None else '':<9}"
                f"{v['status'].upper()}{('  ' + v['detail']) if v['detail'] else ''}")
        t = rep["timings"]
        say(f"\n{t['count']} views, p50 {t['p50Ms']} ms, max {t['maxMs']} ms")
    say("smoke: " + ("passed" if rep["ok"] else f"FAILED ({rep['failures']} failure(s))"))
