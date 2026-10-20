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

"""The Drishti*net soak's judgements that need no server (tools/bench/net_soak.py, DRISHTI_NET.md section 17): a history answer
against the reference server's, the kill windows, the memory slope, the latency and error table and the run's verdict. Plain
functions on plain data, so tools/bench/test_net_soak.py checks them without starting anything."""
from __future__ import annotations

import json
import re

#: the codes a history answer may carry while a node is down (HISTORY_QUERIES.md section 12): the node cannot be reached, or a peer did
#: not answer in time. Anything else in ``problems`` (and DRS-1013, *no data*, at any time) is a wrong answer.
NAMED_CODES = ("DRS-5052", "DRS-1014")
#: verdicts that are fine at any time
RIGHT = ("right", "right-links-pending")
#: verdicts that are errors: allowed only inside a kill window
ERRORS = ("partial", "timeout", "unavailable", "asked-a-node-going-down")
_CODE = re.compile(r"DRS-\d{4}")


def _code(status: int, body) -> str | None:
    """The problem code of an error answer: its ``code``, else the first DRS code in its text."""
    if isinstance(body, dict) and isinstance(body.get("code"), str):
        return body["code"]
    m = _CODE.search(json.dumps(body) if isinstance(body, (dict, list)) else str(body or ""))
    return m.group(0) if m else None


def history_row(r: dict, full: bool = True) -> dict:
    """What must be equal in one history row: its date, the date its data is for, carried and deleted, the values (masked values are the
    mask), part counts and, when ``full``, what changed (the flags, and with diff=true the changes). A partial answer's rows are compared
    without ``changed``: a row after a day that could not be read compares with an earlier day."""
    out = {"date": r.get("date"), "dataDate": r.get("dataDate"), "carried": bool(r.get("carried")), "deleted": bool(r.get("deleted")),
           "values": r.get("values"), "parts": r.get("parts")}
    if full:
        out["changed"] = sorted(r.get("changed") or [])
        out["changes"] = [{k: c.get(k) for k in ("path", "kind", "before", "after")} for c in r.get("changes") or []] or None
    return out


def history_essence(body) -> dict | None:
    """What must be equal in a history answer (HISTORY_QUERIES.md section 8): the masked columns, the window's ends and baseline, and every
    row. Not its timings, cache state, generations, sources or data version: those are the node's own."""
    if not isinstance(body, dict) or not isinstance(body.get("rows"), list):
        return None
    w = body.get("window") or {}
    return {"masked": sorted(str(c.get("path")) for c in body.get("columns") or [] if c.get("masked")),
            "window": {k: w.get(k) for k in ("from", "to", "baseline", "knownAt")},
            "rows": [history_row(r) for r in body["rows"]]}


def masked_leaks(body) -> list[str]:
    """Masked fields that leak: a masked column whose values differ between rows (the mask is the same on every row) or that is named
    as changed. Empty when nothing leaks."""
    if not isinstance(body, dict):
        return []
    masked = [c.get("path") for c in body.get("columns") or [] if c.get("masked")]
    rows = body.get("rows") or []
    out = []
    for p in masked:
        if len({json.dumps((r.get("values") or {}).get(p), sort_keys=True, default=str) for r in rows}) > 1:
            out.append(f"{p}: values differ between rows")
        if any(p in (r.get("changed") or []) for r in rows):
            out.append(f"{p}: shown as changed")
    return out


def judge_history(ref: dict, status: int, body, downs: list[str]) -> str:
    """A history answer against the reference's essence: right | partial (named: DRS-5052 naming a node that is down) | timeout (DRS-1014)
    | unavailable | wrong. Wrong is any other answer: different rows, changed flags or masks, a short answer not marked partial, a partial
    one without a named problem or with rows the reference does not have, a leaked mask, and DRS-1013 at any time."""
    if masked_leaks(body):
        return "wrong"
    named = lambda x: any(d in json.dumps(x, default=str) for d in downs)  # noqa: E731
    if status == 200 and isinstance(body, dict):
        got = history_essence(body)
        if got is None:
            return "wrong"
        if not body.get("partial"):
            return "right" if got == ref else "wrong"
        problems = body.get("problems") or []
        if not problems or any(p.get("code") not in NAMED_CODES for p in problems):
            return "wrong"
        if got["masked"] != ref["masked"]:
            return "wrong"
        mine = {r["date"]: r for r in ref["rows"]}
        for r in body["rows"]:
            if r.get("date") not in mine or history_row(r, full=False) != {k: v for k, v in mine[r["date"]].items() if k not in ("changed", "changes")}:
                return "wrong"
        if len(body["rows"]) >= len(ref["rows"]):
            return "wrong"                                    # partial yet nothing is missing: the flag is wrong
        unreachable = [p for p in problems if p.get("code") == "DRS-5052"]
        if unreachable:
            return "partial" if all(named(p) for p in unreachable) else "wrong"
        return "timeout"
    code = _code(status, body)
    if code == "DRS-1013":
        return "wrong"                                        # never "no data" for an entity the reference has days of
    if code == "DRS-5052":
        return "partial" if downs and named(body) else "unavailable"
    if code == "DRS-1014" and status == 504:
        return "timeout"
    if status == 0 or status in (502, 503):
        return "unavailable"
    return "wrong"


# -- kill windows ----------------------------------------------------------------------------------------------------------------------
def kill_windows(plan: list[tuple[float, str, str]], restarted: dict[str, float], seconds: float, grace: float) -> list[dict]:
    """Each kill's window, in seconds from the start of the load: from the kill to the node's restart having converged (``restarted``,
    when that happened) plus ``grace``, else to the end of the run."""
    out = []
    for t, act, node in sorted(plan):
        if act != "kill" or t >= seconds:
            continue
        back = [r for r in [restarted.get(node)] if r is not None and r >= t]
        out.append({"node": node, "from": t, "to": min(seconds, (back[0] if back else seconds) + grace)})
    return out


def inside(t: float, windows: list[dict], width: float = 0.0) -> bool:
    """True when the moment ``t`` (or the span ``t`` .. ``t + width``) touches a kill window."""
    return any(t <= w["to"] and t + width >= w["from"] for w in windows)


# -- memory -----------------------------------------------------------------------------------------------------------------------------
def slope_per_hour(points: list[tuple[float, float]]) -> float | None:
    """The least-squares slope of (seconds, value) points, per hour; None with fewer than three points."""
    if len(points) < 3:
        return None
    n = len(points)
    mx = sum(x for x, _ in points) / n
    my = sum(y for _, y in points) / n
    den = sum((x - mx) ** 2 for x, _ in points)
    return None if den == 0 else sum((x - mx) * (y - my) for x, y in points) / den * 3600


def memory_trend(samples: list[dict], starts: dict[str, float], warmup: float, max_mb_per_hour: float) -> dict:
    """Per process, the slope of its memory after warm-up: each sample is ``{"t", "node", "rssMb", "heapMb", "liveMb"}``; only samples
    taken ``warmup`` seconds after the process last started count (a restarted node starts afresh). The measure is the heap's live data
    after the last collection when the server reports it, else the heap used, else the resident set (the console). ``ok`` is None when
    there are too few samples to say."""
    out = {}
    for node in sorted({s["node"] for s in samples}):
        mine = [s for s in samples if s["node"] == node and s["t"] >= starts.get(node, 0) + warmup]
        measure = next((m for m in ("liveMb", "heapMb", "rssMb") if mine and all(s.get(m) is not None for s in mine)), "rssMb")
        heap = slope_per_hour([(s["t"], s[measure]) for s in mine if s.get(measure) is not None])
        rss = slope_per_hour([(s["t"], s["rssMb"]) for s in mine if s.get("rssMb") is not None])
        out[node] = {"samples": len(mine), "measure": measure, "slopeMbPerHour": None if heap is None else round(heap, 1),
                     "rssSlopeMbPerHour": None if rss is None else round(rss, 1),
                     "ok": None if heap is None else heap < max_mb_per_hour}
    return out


# -- the table and the verdict ----------------------------------------------------------------------------------------------------------
def table(users: dict, checker: dict) -> str:
    """The latency and error table: a line per action of the load (users.py: n, failed, p50, p95, p99), then a line per kind of checked
    request with its verdicts."""
    lines = [f"{'action':<14}{'n':>9}{'failed':>8}{'p50 s':>8}{'p95 s':>8}{'p99 s':>8}"]
    for label, v in sorted((k, v) for k, v in (users or {}).items() if isinstance(v, dict) and "p95" in v):
        lines.append(f"{label:<14}{v['n']:>9}{v['failed']:>8}{v['p50']:>8.3f}{v['p95']:>8.3f}{v['p99']:>8.3f}")
    lines.append("")
    lines.append(f"{'checked':<14}" + "".join(f"{x:>10}" for x in ("right", "partial", "timeout", "unavail", "wrong")))
    whats = sorted({k.split(":", 1)[0] for k in checker})
    for w in whats:
        c = lambda *vs: sum(checker.get(f"{w}:{v}", 0) for v in vs)  # noqa: E731
        lines.append(f"{w:<14}{c(*RIGHT):>10}{c('partial'):>10}{c('timeout'):>10}{c('unavailable', 'asked-a-node-going-down'):>10}{c('wrong'):>10}")
    return "\n".join(lines)


def verdict(wrong_answers: int, errors: list[dict], windows: list[dict], users: dict, memory: dict, max_p99: float = 0.0) -> dict:
    """The run's verdict: no wrong answers; errors only inside kill windows (``errors`` are ``{"t", "width", "what", "n"}``: the checker's
    at a moment, the load's per bucket of seconds); p95 and p99 per action (failing only past ``max_p99`` when it is set); no process whose
    memory grows after warm-up. ``pass`` is all four."""
    outside = [e for e in errors if not inside(e["t"], windows, e.get("width", 0.0))]
    latency = {k: {"p95": v["p95"], "p99": v["p99"]} for k, v in (users or {}).items() if isinstance(v, dict) and "p95" in v}
    slow = [k for k, v in latency.items() if max_p99 and v["p99"] > max_p99]
    growing = [n for n, m in memory.items() if m.get("ok") is False]
    checks = {
        "noWrongAnswers": {"ok": wrong_answers == 0, "wrong": wrong_answers},
        "errorsOnlyInKillWindows": {"ok": not outside, "outside": sum(e.get("n", 1) for e in outside), "first": outside[:5], "windows": windows},
        "latency": {"ok": not slow, "perAction": latency, "maxP99": max_p99 or None, "over": slow},
        "memoryFlat": {"ok": not growing, "growing": growing, "perProcess": memory},
    }
    return {"pass": all(c["ok"] for c in checks.values()), **checks}
