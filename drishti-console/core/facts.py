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

"""Did you know: the Home page's fact of the day (docs/guides/USER_GUIDE.md, "Home: the fact of the day").

Every fact is declared by the pack it needs, in ``config/packs/<pack>/config/facts.yaml``, and computed from the server's answers on the
business date: the steps fetch (``var``, ``exposure``, ``search``, ``raw``), pick a row (``pick`` with ``where`` and ``order``) or compute
a value (``let``); ``when`` says what makes it surprising; ``text``, ``why`` and the picture are filled from the result. No number is
written in a fact: a fact whose condition does not hold today is not shown. Results are kept per server, user, business date and set of
packs, computed once (concurrent asks share one computation) and never on the page's own request path (the page asks
``/api/home/facts`` after it has drawn). ``check()`` is the config check the tests run on every shipped file.
"""
from __future__ import annotations

import asyncio
import datetime as dt
import logging
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any
from urllib.parse import quote

import yaml

from core.facts_expr import FORMATS, Expr, ExprError, Template, fmt

log = logging.getLogger(__name__)

FILE = "config/facts.yaml"                 # in a pack's folder
FETCHES = ("var", "exposure", "search", "raw")
KINDS = FETCHES + ("let", "pick")
PICTURES = ("bars", "share", "line")
TONES = ("base", "alert", "muted")
FACT_KEYS = {"id", "domain", "title", "needs", "steps", "when", "text", "why", "picture", "open", "what", "knowledge"}
STEP_KEYS = {"as", "params", "each", "limit", "where", "order", "desc", *KINDS}
MAX_EACH = 500                             # most calls one ``each`` step makes


@dataclass
class Step:
    name: str
    kind: str
    target: Any = None                     # the path (var, exposure), Template (search), (Template, Template) (raw), Expr (let, pick)
    params: dict = field(default_factory=dict)
    each: Expr | None = None
    limit: int = 100
    where: Expr | None = None
    order: Expr | None = None
    desc: bool = True


@dataclass
class Fact:
    id: str
    pack: str
    domain: str
    title: str
    needs: list
    steps: list
    when: Expr | None
    text: Template
    why: Template | None
    picture: dict | None
    open: Template
    what: str
    knowledge: str


# ---------------------------------------------------------------------------------------------------------------------- the config

def _template(value: Any, where: str) -> Template:
    if not isinstance(value, str) or not value.strip():
        raise ExprError(f"{where}: a text is needed")
    return Template(" ".join(value.split()))


def _step(raw: Any, where: str) -> Step:
    if not isinstance(raw, dict):
        raise ExprError(f"{where}: a step is a mapping")
    unknown = set(raw) - STEP_KEYS
    if unknown:
        raise ExprError(f"{where}: unknown key(s) {', '.join(sorted(unknown))}")
    kinds = [k for k in KINDS if k in raw]
    if len(kinds) != 1:
        raise ExprError(f"{where}: one of {', '.join(KINDS)} per step")
    name = str(raw.get("as") or "").strip()
    if not name.isidentifier() or name.startswith("_") or name in ("it", "asOf"):
        raise ExprError(f"{where}: 'as' names the step's result (a name, not it or asOf)")
    kind = kinds[0]
    s = Step(name, kind, limit=int(raw.get("limit", 100) or 100), desc=bool(raw.get("desc", True)))
    if kind in ("var", "exposure"):
        path = str(raw[kind] or "")
        if not path.startswith("/") or ".." in path or "?" in path:
            raise ExprError(f"{where}: {kind} takes an API path such as /tree (parameters under params)")
        s.target = Template(path)
    elif kind == "search":
        s.target = _template(raw[kind], where)
    elif kind == "raw":
        r = raw[kind]
        if not isinstance(r, dict) or not r.get("kind") or not r.get("id"):
            raise ExprError(f"{where}: raw takes {{kind, id}}")
        s.target = (_template(r["kind"], where), _template(str(r["id"]), where))
    else:
        s.target = Expr(raw[kind])
    params = raw.get("params") or {}
    if not isinstance(params, dict):
        raise ExprError(f"{where}: params is a mapping")
    s.params = {str(k): Template(str(v)) for k, v in params.items()}
    if raw.get("each") is not None:
        if kind not in FETCHES:
            raise ExprError(f"{where}: each goes with a fetch ({', '.join(FETCHES)})")
        s.each = Expr(raw["each"])
        s.limit = max(1, min(MAX_EACH, s.limit))
    if kind == "pick":
        s.where = Expr(raw["where"]) if raw.get("where") is not None else None
        s.order = Expr(raw["order"]) if raw.get("order") is not None else None
    elif raw.get("where") is not None or raw.get("order") is not None:
        raise ExprError(f"{where}: where and order go with pick")
    return s


def _picture(raw: Any, where: str) -> dict | None:
    if raw is None:
        return None
    if not isinstance(raw, dict) or raw.get("type") not in PICTURES:
        raise ExprError(f"{where}: picture.type is one of {', '.join(PICTURES)}")
    out: dict = {"type": raw["type"], "caption": _template(raw["caption"], where) if raw.get("caption") else None,
                 "label": _template(raw["label"], where) if raw.get("label") else None}
    if raw["type"] == "bars":
        bars = raw.get("bars")
        if not isinstance(bars, list) or not 2 <= len(bars) <= 6:
            raise ExprError(f"{where}: bars has 2 to 6 bars")
        out["bars"] = []
        for b in bars:
            if not isinstance(b, dict) or "value" not in b or "label" not in b:
                raise ExprError(f"{where}: a bar has label and value")
            tone = str(b.get("tone") or "base")
            if tone not in TONES:
                raise ExprError(f"{where}: a bar's tone is one of {', '.join(TONES)}")
            out["bars"].append({"label": _template(b["label"], where), "value": Expr(b["value"]), "tone": tone,
                                "format": str(b.get("format") or "money")})
            if out["bars"][-1]["format"] not in FORMATS:
                raise ExprError(f"{where}: unknown bar format {out['bars'][-1]['format']!r}")
    elif raw["type"] == "share":                  # one part of a whole: value / of, with a marker at 100% when it is over
        out["value"] = Expr(raw.get("value") or "")
        out["of"] = Expr(raw.get("of") or "1")
        out["format"] = str(raw.get("format") or "pct1")
        if out["format"] not in FORMATS:
            raise ExprError(f"{where}: unknown share format {out['format']!r}")
    else:                                         # line: a small chart of values (labels under the first and last)
        out["values"] = Expr(raw.get("values") or "")
        out["labels"] = Expr(raw["labels"]) if raw.get("labels") else None
        out["mark"] = Expr(raw["mark"]) if raw.get("mark") else None
    return out


def parse(doc: Any, pack: str, source: str = "") -> tuple[list[Fact], list[str]]:
    """The facts of one file, and its problems (a fact with a problem is left out, the others stand)."""
    where0 = source or f"{pack}/{FILE}"
    if not isinstance(doc, dict) or doc.get("facts") != 1 or not isinstance(doc.get("items"), list):
        return [], [f"{where0}: needs 'facts: 1' and a list under 'items'"]
    out, problems, seen = [], [], set()
    for i, raw in enumerate(doc["items"]):
        where = f"{where0} item {i + 1}"
        try:
            if not isinstance(raw, dict):
                raise ExprError(f"{where}: a fact is a mapping")
            fid = str(raw.get("id") or "").strip()
            where = f"{where0} fact {fid or i + 1}"
            unknown = set(raw) - FACT_KEYS
            if unknown:
                raise ExprError(f"{where}: unknown key(s) {', '.join(sorted(unknown))}")
            if not fid or fid in seen or not all(c.isalnum() or c == "-" for c in fid):
                raise ExprError(f"{where}: id is needed, unique, letters, digits and hyphens")
            seen.add(fid)
            for key in ("domain", "text", "open", "steps"):
                if not raw.get(key):
                    raise ExprError(f"{where}: '{key}' is needed")
            needs = raw.get("needs") or []
            if not isinstance(needs, list):
                raise ExprError(f"{where}: needs is a list of packs")
            steps = [_step(s, f"{where} step {j + 1}") for j, s in enumerate(raw["steps"] or [])]
            fact = Fact(fid, pack, str(raw["domain"]), str(raw.get("title") or fid), sorted({pack, *map(str, needs)}), steps,
                        Expr(raw["when"]) if raw.get("when") is not None else None, _template(raw["text"], where),
                        _template(raw["why"], where) if raw.get("why") else None, _picture(raw.get("picture"), where),
                        _template(raw["open"], where), str(raw.get("what") or ""), str(raw.get("knowledge") or ""))
            _check_names(fact, where)
            out.append(fact)
        except (ExprError, TypeError, ValueError) as e:
            problems.append(str(e) if str(e).startswith(where0) else f"{where}: {e}")
    return out, problems


def _check_names(f: Fact, where: str) -> None:
    """Every name an expression reads is bound by an earlier step (``it`` inside an ``each``; a pick's own name in its where/order)."""
    bound = {"asOf"}

    def need(names: set, extra: set, what: str) -> None:
        missing = names - bound - extra
        if missing:
            raise ExprError(f"{where}: {what} reads {', '.join(sorted(missing))}, which no earlier step sets")

    for s in f.steps:
        extra = {"it"} if s.each else set()
        if s.each:
            need(s.each.names(), set(), f"step {s.name}'s each")
        if isinstance(s.target, Expr):
            need(s.target.names(), set(), f"step {s.name}")
        elif isinstance(s.target, Template):
            need(s.target.names(), extra, f"step {s.name}")
        elif isinstance(s.target, tuple):
            need(s.target[0].names() | s.target[1].names(), extra, f"step {s.name}")
        for t in s.params.values():
            need(t.names(), extra, f"step {s.name}'s params")
        for e in (s.where, s.order):
            if e is not None:
                need(e.names(), {s.name}, f"step {s.name}'s where/order")
        bound.add(s.name)
    for label, t in (("when", f.when), ("text", f.text), ("why", f.why), ("open", f.open)):
        if t is not None:
            need(t.names(), set(), label)
    if f.picture:
        exprs: list = [f.picture.get("caption"), f.picture.get("label")]
        exprs += [b["label"] for b in f.picture.get("bars", [])] + [b["value"] for b in f.picture.get("bars", [])]
        exprs += [f.picture.get(k) for k in ("value", "of", "values", "labels", "mark")]
        for e in exprs:
            if e is not None:
                need(e.names(), set(), "picture")


def load_pack(pack: dict) -> tuple[list[Fact], list[str]]:
    """The facts a pack carries (``<pack dir>/config/facts.yaml``; none when the file is absent)."""
    path = Path(pack.get("dir") or "") / FILE
    if not path.is_file():
        return [], []
    try:
        doc = yaml.safe_load(path.read_text(encoding="utf-8"))
    except yaml.YAMLError as e:
        return [], [f"{pack.get('name')}/{FILE}: not YAML: {e}"]
    return parse(doc, str(pack.get("name")), f"{pack.get('name')}/{FILE}")


def check(packs_dir: Path) -> dict[str, list[str]]:
    """Every pack's facts file under ``packs_dir``, checked: ``{pack: [problems]}`` (empty lists when all is well)."""
    out = {}
    for d in sorted(p for p in Path(packs_dir).iterdir() if (p / FILE).is_file()):
        out[d.name] = load_pack({"name": d.name, "dir": d})[1]
    return out


# ---------------------------------------------------------------------------------------------------------------------- computing

class Fetcher:
    """What the steps fetch, as the user: the VaR and exposure APIs, structured search and raw records (core/backend.py)."""

    def __init__(self, backend, ident, as_of: str | None = None, timeout: float = 60.0):
        self.backend, self.ident, self.as_of, self.timeout = backend, ident, as_of, timeout

    async def fetch(self, kind: str, target: Any, params: dict) -> Any:
        dated = {**params, **({"asOf": self.as_of} if self.as_of and "asOf" not in params else {})}
        if kind == "var":
            return await self.backend.var("GET", target, self.ident, **dated)
        if kind == "exposure":
            return await self.backend.exposure("GET", target, self.ident, **dated)
        if kind == "search":
            return await self.backend.search(target, self.ident)
        if kind == "raw":
            return await self.backend.raw(target[0], target[1], self.ident)
        raise ValueError(kind)


async def _run_step(s: Step, env: dict, fetcher, gate: asyncio.Semaphore) -> Any:
    if s.kind == "let":
        return s.target.eval(env)
    if s.kind == "pick":
        rows = s.target.eval(env)
        rows = rows if isinstance(rows, list) else ([] if rows is None else [rows])
        kept = [r for r in rows if s.where is None or s.where.eval({**env, s.name: r})]
        if s.order is not None:
            keyed = [(s.order.eval({**env, s.name: r}), r) for r in kept]
            have = [k for k in keyed if isinstance(k[0], (int, float)) and not isinstance(k[0], bool)]
            have.sort(key=lambda k: k[0], reverse=s.desc)
            kept = [r for _, r in have]                    # a row the order cannot rank is not a candidate
        return kept[0] if kept else None

    async def one(scope: dict) -> Any:
        if s.kind == "raw":
            target: Any = (s.target[0].render(scope), s.target[1].render(scope))
            if None in target:
                return None
        elif isinstance(s.target, Template):
            target = s.target.render(scope)
            if target is None:
                return None
        params = {k: t.render(scope) for k, t in s.params.items()}
        if any(v is None for v in params.values()):
            return None
        async with gate:
            try:
                return await fetcher.fetch(s.kind, target, params)
            except Exception as e:  # noqa: BLE001 - a refusal, a missing record: this answer is None, the fact may still hold
                log.debug("fact step %s: %s", s.name, e)
                return None

    if s.each is None:
        return await one(env)
    items = s.each.eval(env)
    items = (items if isinstance(items, list) else [])[: s.limit]
    answers = await asyncio.gather(*(one({**env, "it": it}) for it in items))
    return [{"it": it, "ans": a} for it, a in zip(items, answers) if a is not None]


async def compute(f: Fact, fetcher, as_of: str, gate: asyncio.Semaphore | None = None) -> dict | None:
    """The fact on the date, filled, or None when it does not hold (a step found nothing, ``when`` is false, a text has a hole)."""
    gate = gate or asyncio.Semaphore(8)
    env: dict = {"asOf": as_of}
    for s in f.steps:
        env[s.name] = await _run_step(s, env, fetcher, gate)
        if env[s.name] is None and s.kind == "pick":
            return None
    if f.when is not None and not f.when.eval(env):
        return None
    text, command = f.text.render(env), f.open.render(env)
    if not text or not command:
        return None
    out = {"id": f.id, "pack": f.pack, "domain": f.domain, "text": text, "why": f.why.render(env) if f.why else None,
           "command": command, "what": f.what, "knowledge": f.knowledge}
    if f.picture:
        out["picture"] = _draw(f.picture, env)
    return out


def _draw(p: dict, env: dict) -> dict | None:
    caption = p["caption"].render(env) if p.get("caption") else None
    if p["type"] == "bars":
        bars = []
        for b in p["bars"]:
            v = b["value"].eval(env)
            label = b["label"].render(env)
            if not isinstance(v, (int, float)) or isinstance(v, bool) or label is None:
                return None
            bars.append({"label": label, "value": v, "display": fmt(v, b["format"]), "tone": b["tone"]})
        top = max(abs(b["value"]) for b in bars) or 1
        for b in bars:
            b["width"] = round(abs(b["value"]) / top * 100, 1)
        return {"type": "bars", "bars": bars, "caption": caption}
    if p["type"] == "share":
        v, of = p["value"].eval(env), p["of"].eval(env)
        if not isinstance(v, (int, float)) or not isinstance(of, (int, float)) or not of:
            return None
        r = v / of
        label = p["label"].render(env) if p.get("label") else ""
        return {"type": "share", "ratio": r, "display": fmt(r, p["format"]), "label": label or "", "caption": caption}
    values = p["values"].eval(env)
    if not isinstance(values, list) or len([x for x in values if isinstance(x, (int, float))]) < 2:
        return None
    labels = p["labels"].eval(env) if p.get("labels") else None
    mark = p["mark"].eval(env) if p.get("mark") else None
    return {"type": "line", "values": [x if isinstance(x, (int, float)) else None for x in values],
            "labels": labels if isinstance(labels, list) else None, "mark": mark if isinstance(mark, int) else None,
            "caption": caption}


def rotate(items: list[dict], day: str) -> list[dict]:
    """The facts in a stable order (by pack and id), turned so the day's fact is first: deterministic by the business date, so everyone
    on the same date sees the same fact first, and "Another fact" walks the rest."""
    ordered = sorted(items, key=lambda x: (x["pack"], x["id"]))
    if not ordered:
        return []
    try:
        n = dt.date.fromisoformat(str(day)[:10]).toordinal()
    except ValueError:
        n = 0
    k = n % len(ordered)
    return ordered[k:] + ordered[:k]


class FactsService:
    """The facts of the packs a user has on, computed per (server, user, date, packs) and kept ``cache_seconds`` (a business date's data
    rarely changes during the day; a pack switched on or off is a new key). Concurrent asks for one key share one computation."""

    def __init__(self, settings=None):
        get = (settings.get if settings is not None else (lambda k, d=None: d))
        self.enabled = str(get("ui.facts.enabled", True)).strip().lower() in ("true", "1", "yes", "on")
        self.ttl = float(get("ui.facts.cache_seconds", 21600) or 0)
        self.timeout = float(get("ui.facts.timeout_seconds", 90) or 90)
        self.keep = int(get("ui.facts.keep", 64) or 64)
        self._files: dict[str, tuple[float, list, list]] = {}
        self._done: dict[tuple, tuple[float, list]] = {}
        self._running: dict[tuple, asyncio.Task] = {}

    def facts_of(self, pack: dict) -> list[Fact]:
        """A pack's facts, read again when its file changes; problems are logged once per change."""
        path = Path(pack.get("dir") or "") / FILE
        try:
            stamp = path.stat().st_mtime
        except OSError:
            return []
        hit = self._files.get(str(path))
        if hit and hit[0] == stamp:
            return hit[1]
        facts, problems = load_pack(pack)
        for p in problems:
            log.warning("fact left out: %s", p)
        self._files[str(path)] = (stamp, facts, problems)
        return facts

    def eligible(self, packs: list[dict]) -> list[Fact]:
        """The facts whose packs are all on for the user."""
        names = {p.get("name") for p in packs}
        return [f for p in packs for f in self.facts_of(p) if set(f.needs) <= names]

    async def today(self, key: tuple, facts: list[Fact], fetcher, as_of: str) -> list[dict]:
        hit = self._done.get(key)
        if hit and time.monotonic() - hit[0] < self.ttl:
            return hit[1]
        task = self._running.get(key)
        if task is None:
            task = asyncio.ensure_future(self._compute_all(facts, fetcher, as_of))
            self._running[key] = task
            task.add_done_callback(lambda t, k=key: self._finish(k, t))
        return await asyncio.shield(task)

    def _finish(self, key: tuple, task: asyncio.Task) -> None:
        self._running.pop(key, None)
        if task.cancelled() or task.exception() is not None:
            return
        self._done[key] = (time.monotonic(), task.result())
        while len(self._done) > self.keep:
            self._done.pop(next(iter(self._done)))

    async def _compute_all(self, facts: list[Fact], fetcher, as_of: str) -> list[dict]:
        gate = asyncio.Semaphore(8)

        async def one(f: Fact):
            try:
                return await asyncio.wait_for(compute(f, fetcher, as_of, gate), self.timeout)
            except Exception as e:  # noqa: BLE001 - one fact failing never hides the others
                log.info("fact %s/%s not computed: %s", f.pack, f.id, e)
                return None
        found = await asyncio.gather(*(one(f) for f in facts))
        return [x for x in found if x]


def links(fact: dict, glossary, library) -> dict:
    """The fact's links: the command (``/go``), its WHAT term (``/what``) and the Knowledge page section for "Why" (the fact's
    ``knowledge`` as ``page.md#anchor``, else the WHAT term's glossary entry)."""
    out = {**fact, "href": "/go?q=" + quote(fact["command"] + " <GO>")}
    if fact.get("what"):
        out["whatHref"] = "/what?q=" + quote(fact["what"])
    page, _, anchor = str(fact.get("knowledge") or "").partition("#")
    if not page and fact.get("what") and glossary is not None:
        entry = glossary.find(fact["what"])
        if entry:
            page, anchor = entry.page, entry.anchor
    if page and library is not None:
        folder = None
        for f, slug in getattr(library, "by_file", {}).items():
            if Path(f).name == page and Path(f).parent.name == "knowledge":
                folder = slug
                break
        if folder:
            out["knowledgeHref"] = f"/help/{folder}" + (f"#{anchor}" if anchor else "")
    return out
