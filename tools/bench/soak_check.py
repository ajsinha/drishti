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
against the reference server's, the kill windows, the memory slope, the latency and error table, the run's verdict and the
record of wrong answers. Plain functions on plain data, so tools/bench/test_net_soak.py checks them without starting anything."""
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
    """judge_history_why's verdict alone."""
    return judge_history_why(ref, status, body, downs)[0]


def judge_history_why(ref: dict, status: int, body, downs: list[str]) -> tuple[str, str]:
    """A history answer against the reference's essence: (verdict, the rule that decided it). The verdict is right | partial (named:
    DRS-5052 naming a node that is down) | timeout (DRS-1014) | unavailable | wrong. Wrong is any other answer: different rows, changed
    flags or masks, a short answer not marked partial, a partial one without a named problem or with rows the reference does not have, a
    leaked mask, and DRS-1013 at any time."""
    leaks = masked_leaks(body)
    if leaks:
        return "wrong", "mask leaks: " + "; ".join(leaks)
    named = lambda x: any(d in json.dumps(x, default=str) for d in downs)  # noqa: E731
    if status == 200 and isinstance(body, dict):
        got = history_essence(body)
        if got is None:
            return "wrong", "no rows"
        if not body.get("partial"):
            return ("right", "equal") if got == ref else ("wrong", "complete answer differs from the reference")
        problems = body.get("problems") or []
        if not problems:
            return "wrong", "partial without problems"
        other = sorted({str(p.get("code")) for p in problems if p.get("code") not in NAMED_CODES})
        if other:
            return "wrong", "partial with " + ",".join(other)
        if got["masked"] != ref["masked"]:
            return "wrong", "partial with other masked columns"
        mine = {r["date"]: r for r in ref["rows"]}
        for r in body["rows"]:
            if r.get("date") not in mine or history_row(r, full=False) != {k: v for k, v in mine[r["date"]].items() if k not in ("changed", "changes")}:
                return "wrong", f"partial with a row not the reference's ({r.get('date')})"
        if len(body["rows"]) >= len(ref["rows"]):
            return "wrong", "partial yet nothing missing"                 # the flag is wrong
        unreachable = [p for p in problems if p.get("code") == "DRS-5052"]
        if unreachable:
            return ("partial", "named") if all(named(p) for p in unreachable) else ("wrong", "partial naming a node that is up")
        return "timeout", "DRS-1014"
    code = _code(status, body)
    if code == "DRS-1013":
        return "wrong", "DRS-1013 no data"                                # never for an entity the reference has days of
    if code == "DRS-5052":
        return ("partial", "named") if downs and named(body) else ("unavailable", "DRS-5052")
    if code == "DRS-1014" and status == 504:
        return "timeout", "DRS-1014"
    if status == 0 or status in (502, 503):
        return "unavailable", f"status {status}" + (f" {code}" if code else "")
    return "wrong", f"status {status}" + (f" {code}" if code else "")


# -- wrong answers ----------------------------------------------------------------------------------------------------------------------
def compact_diff(ref, got, limit: int = 20) -> list[dict]:
    """Where ``got`` differs from ``ref`` (two essences): up to ``limit`` of ``{"path", "ref", "got"}``, values cut to 120 characters, lists
    of other lengths also as their lengths."""
    out: list[dict] = []

    def short(v):
        t = json.dumps(v, default=str, ensure_ascii=False)
        return v if len(t) <= 120 else t[:117] + "..."

    def walk(a, b, p):
        if len(out) >= limit:
            return
        if isinstance(a, dict) and isinstance(b, dict):
            for k in sorted(set(a) | set(b), key=str):
                walk(a.get(k), b.get(k), f"{p}.{k}" if p else str(k))
        elif isinstance(a, list) and isinstance(b, list):
            if len(a) != len(b):
                out.append({"path": p + ".length", "ref": len(a), "got": len(b)})
            for i, (x, y) in enumerate(zip(a, b)):
                walk(x, y, f"{p}[{i}]")
        elif a != b:
            out.append({"path": p, "ref": short(a), "got": short(b)})

    walk(ref, got, "")
    return out[:limit]


def sample_body(body, cut: int = 600) -> dict:
    """A wrong answer's body as kept: ``partial`` and ``problems`` whole (they are often what made it wrong), then the rest cut at ``cut``
    characters."""
    if not isinstance(body, dict):
        return {"text": str(body)[:cut]}
    rest = {k: v for k, v in body.items() if k not in ("partial", "problems")}
    return {"partial": body.get("partial"), "problems": body.get("problems"), "rest": json.dumps(rest, default=str)[:cut]}


class WrongLog:
    """Every wrong answer (SOAK-08): its time, node, request and the rule that failed, for all of them (up to ``keep_all``); counts by kind
    of request and rule; and for the first ``detailed`` also the status, the body's ``partial`` and ``problems`` whole and a compact diff
    against the reference."""

    def __init__(self, detailed: int = 200, keep_all: int = 20_000):
        self.detailed, self.keep_all = detailed, keep_all
        self.all: list[dict] = []
        self.samples: list[dict] = []
        self.by_kind: dict[str, dict[str, int]] = {}
        self.count = 0

    @staticmethod
    def request(q: dict) -> dict:
        return {k: q[k] for k in ("what", "system", "kind", "id", "cp", "params", "role", "date") if k in q}

    def add(self, t: float, node: str, q: dict, status: int, body, reason: str, got=None, ref=None) -> None:
        self.count += 1
        kinds = self.by_kind.setdefault(q["what"], {})
        rule = re.sub(r"\(\d{4}-\d{2}-\d{2}\)|\d+ of \d+", "...", reason)      # one count per rule, not per date or row count
        kinds[rule] = kinds.get(rule, 0) + 1
        line = {"t": t, "node": node, "q": self.request(q), "status": status, "reason": reason}
        if len(self.all) < self.keep_all:
            self.all.append(line)
        if len(self.samples) < self.detailed:
            self.samples.append({**line, "body": sample_body(body), "diff": compact_diff(ref, got)})

    def report(self) -> dict:
        return {"count": self.count, "byKind": self.by_kind, "all": self.all, "allCut": self.count > len(self.all), "samples": self.samples}


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


def steady_segments(t0: float, t1: float, windows: list[dict], settle: float) -> list[tuple[float, float]]:
    """The spans of ``t0`` .. ``t1`` outside every kill window and the ``settle`` seconds after it, in order: the load moves between nodes
    when one dies and when it comes back, so those spans are not steady."""
    out, at = [], t0
    for lo, hi in sorted((w["from"], w["to"] + settle) for w in windows):
        if lo > at:
            out.append((at, min(lo, t1)))
        at = max(at, hi)
        if at >= t1:
            break
    if at < t1:
        out.append((at, t1))
    return [(x, y) for x, y in out if y > x]


def pooled_slope_per_hour(groups: list[list[tuple[float, float]]]) -> float | None:
    """The common slope of several groups of (seconds, value) points, each with a level of its own (a fit after each failover: a step
    between groups is not growth), per hour; groups of fewer than two points are left out; None with fewer than three points in all."""
    groups = [g for g in groups if len(g) >= 2]
    if sum(len(g) for g in groups) < 3:
        return None
    num = den = 0.0
    for g in groups:
        mx = sum(x for x, _ in g) / len(g)
        my = sum(y for _, y in g) / len(g)
        num += sum((x - mx) * (y - my) for x, y in g)
        den += sum((x - mx) ** 2 for x, _ in g)
    return None if den == 0 else num / den * 3600


def memory_trend(samples: list[dict], starts: dict[str, float], warmup: float, max_mb_per_hour: float, windows: list[dict] | None = None,
                 settle: float = 300.0) -> dict:
    """Per process, the slope of its memory after warm-up: each sample is ``{"t", "node", "rssMb", "heapMb", "liveMb"}``; only samples
    taken ``warmup`` seconds after the process last started count (a restarted node starts afresh). The measure is the heap's live data
    after the last collection when the server reports it, else the heap used, else the resident set (the console: its parent and
    workers summed).

    Two slopes (SOAK-04): ``wholeRunSlopeMbPerHour`` over every such sample, and ``slopeMbPerHour`` over the steady spans only, outside
    the kill ``windows`` and ``settle`` seconds after each, fitted with a level of its own per span, so that a node taking the load of a
    dead one (a step) is not read as a leak. ``ok`` is the steady slope below ``max_mb_per_hour``; None when there are too few samples."""
    out = {}
    windows = windows or []
    for node in sorted({s["node"] for s in samples}):
        mine = [s for s in samples if s["node"] == node and s["t"] >= starts.get(node, 0) + warmup]
        measure = next((m for m in ("liveMb", "heapMb", "rssMb") if mine and all(s.get(m) is not None for s in mine)), "rssMb")
        pts = [(s["t"], s[measure]) for s in mine if s.get(measure) is not None]
        whole = slope_per_hour(pts)
        rss = slope_per_hour([(s["t"], s["rssMb"]) for s in mine if s.get("rssMb") is not None])
        spans = steady_segments(pts[0][0], pts[-1][0] + 1e-6, windows, settle) if pts else []
        groups = [[p for p in pts if lo <= p[0] < hi] for lo, hi in spans]
        steady = pooled_slope_per_hour(groups)
        out[node] = {"samples": len(mine), "steadySamples": sum(len(g) for g in groups if len(g) >= 2), "measure": measure,
                     "slopeMbPerHour": None if steady is None else round(steady, 1),
                     "wholeRunSlopeMbPerHour": None if whole is None else round(whole, 1),
                     "rssSlopeMbPerHour": None if rss is None else round(rss, 1),
                     "steadySpans": [[round(x, 1), round(y, 1)] for x, y in spans],
                     "ok": None if steady is None else steady < max_mb_per_hour}
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
