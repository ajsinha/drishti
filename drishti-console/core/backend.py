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

"""Async client for the Drishti server REST API: one pooled HTTP client per console process."""
from __future__ import annotations

import json
import re
from typing import Any

from urllib.parse import quote

from http.cookiejar import CookieJar, DefaultCookiePolicy

import httpx

from core import asof, collab, edition


def drs_message(code: str, detail: str, sep: str = ": ") -> str:
    """``code`` once in front of ``detail``: no prefix when the detail already starts with it, and no repeat of it
    inside (the server writes "DRS-2101 alert expression: DRS-2101 ..."). A shared helper, not string glue (UX-10)."""
    code, detail = str(code or "").strip(), str(detail or "").strip()
    if code:
        detail = re.sub(rf"{re.escape(code)}[:\s]*", "", detail).strip()
    return f"{code}{sep}{detail}" if code and detail else (code or detail)


def drs_advice(table: dict | None, code: str, kind: str = "", id_: str = "") -> str:
    """What to do about an error, for its DRS code, from the configured ``ui.error_advice`` (UX-11): the advice must
    fit the code, so a timeout is not told to check the identifier. {kind} and {id} fill in."""
    table = table or {}
    text = str(table.get(str(code or "").strip()) or table.get("default") or "")
    return text.replace("{kind}", str(kind)).replace("{id}", str(id_))


def _error(r: httpx.Response, text: str) -> "BackendError":
    """The server's problem answer (status, ``code``, ``detail``, ``problems``, ``Retry-After``) as a :class:`BackendError`."""
    try:
        body = json.loads(text)
        body = body if isinstance(body, dict) else {}
    except ValueError:
        body = {}
    err = BackendError(r.status_code, body.get("code", f"HTTP-{r.status_code}"), body.get("detail", text[:200]))
    err.problems = body.get("problems", [])
    err.extra = body
    err.retry_after = r.headers.get("retry-after")
    return err


def peer_problem(status: int, content_type: str, text: str) -> bool:
    """A 502/503 that is the server's own answer about another node of its cluster (``DRS-5050`` to ``DRS-5059``: a peer that holds the data
    is down, a node the role may not reach; or, from a server of 2.6.5 or older, ``DRS-1003`` naming a peer, ``net:<node> failed reading ...``):
    the server answered, so it is not counted against it and the console does not move away from it (CLUSTER_GUIDE.md 11.1). A time-out about
    another node (``DRS-1014``, 504) is never counted either: only 502/503 are. A 502/503 from a proxy, or a server's own failure, still
    counts (SOAK-02: the console set healthy servers aside 43 times on histories a dying peer could not answer)."""
    if status not in (502, 503) or "json" not in (content_type or ""):
        return False
    if re.search(r'"code"\s*:\s*"DRS-505\d"', text or ""):
        return True
    return bool(re.search(r'"code"\s*:\s*"DRS-1003"', text or "") and re.search(r'"detail"\s*:\s*"(DRS-1003\s+)?net:', text or ""))


def joining_problem(status: int, text: str) -> bool:
    """The server's ``502 DRS-5052`` "this node is still joining Drishti*net": it has just started or rejoined and does not know yet which
    peers hold the data (SOAK3-01). It is not counted against the server; a read is tried once on another server, which knows."""
    return status == 502 and bool(re.search(r'"code"\s*:\s*"DRS-5052"', text or "")) and "still joining" in (text or "")


class BackendError(Exception):
    """The server answered with a problem (RFC 7807) or could not be reached."""

    def __init__(self, status: int, code: str, detail: str):
        super().__init__(drs_message(code, detail, " "))
        self.status = status
        self.code = code
        code_text = str(code or "").strip()
        # the code is told once: the server's detail often starts with it ("DRS-7003 too many shares"), and callers put the code in front
        self.detail = re.sub(rf"^{re.escape(code_text)}[:\s]*", "", str(detail or "")).strip() or detail if code_text else detail
        self.retry_after: str | None = None            # the server's Retry-After (seconds), passed on to the browser by the JSON routes
        self.extra: dict = {}                           # the whole problem body (a refusal of an uninstall carries its impact)

    @property
    def page_status(self) -> int:
        """The status a console page answers with: the server's own below 500; a timeout (the server's 504 DRS-1004, or the
        console giving up waiting) is a 504 gateway timeout, any other server fault a 502."""
        if self.status < 500:
            return self.status
        return 504 if self.status == 504 else 502


def entity_path(kind: str, id_: str) -> tuple[str, dict]:
    """``kind/id`` for a server path, and the query it needs. An id a path cannot carry (one with ``/`` or ``\\``, or
    ``.``, ``..`` or ``~``) goes in the query as ``id``, with ``~`` in its place in the path, which every ``{kind}/{id}``
    endpoint of the server accepts (QA 2026-10-01, DATA-21); any other id is percent-encoded into the path."""
    k = quote(kind, safe="")
    if "/" in id_ or "\\" in id_ or id_ in (".", "..", "~"):
        return f"{k}/~", {"id": id_}
    return f"{k}/{quote(id_, safe='')}", {}


class BackendClient:
    """Thin, typed-by-convention wrapper over ``/api/v1``. Safe to share across requests."""

    def __init__(self, base_url: str, timeout: float = 5.0, pool: int = 64, servers=None):
        """``servers``: a :class:`core.cluster.ServerPool` when ``backend.urls`` names several servers (Drishti*net): every call then goes
        to the server the session uses, and a read that fails there is tried once on another; None for the one ``base_url``."""
        # one client for every person: it keeps no cookie a server sets (a jar whose policy accepts none), so a cookie one person's answer
        # carried never rides on the next person's call; who calls is said by each call's own headers
        self._client = httpx.AsyncClient(
            base_url=base_url.rstrip("/"), timeout=timeout, cookies=CookieJar(policy=DefaultCookiePolicy(allowed_domains=[])),
            limits=httpx.Limits(max_connections=pool, max_keepalive_connections=pool))
        self._servers = servers

    def _base(self) -> str:
        """The server a call goes to: the session's in the pool, else the client's own base (a relative path)."""
        return self._servers.current() if self._servers is not None else ""

    async def public(self, path: str, raw: bool = False, timeout: float = 30.0) -> Any:
        """A ``/public/...`` answer of the server (no person: the modules' console parts, KERNEL_AND_MODULES.md section 4.7); JSON, or the
        bytes with ``raw``. Raises :class:`BackendError` with the server's status."""
        try:
            r = await self._client.get(self._base() + path, timeout=timeout)
        except httpx.HTTPError as e:
            raise BackendError(503, "DRS-5003", f"backend unreachable: {e}") from e
        if r.status_code >= 400:
            raise BackendError(r.status_code, "DRS-5003" if r.status_code >= 500 else "DRS-4040", f"{path}: {r.status_code}")
        return r.content if raw else r.json()

    async def _get(self, url: str, ident=None, **params: Any) -> Any:
        return await self._send("GET", url, ident, params=params)

    async def _send(self, method: str, path: str, ident, **kw: Any) -> Any:
        headers = dict(ident.headers()) if ident is not None else {}
        headers.update(asof.headers())
        headers.update(collab.headers())
        headers.update(edition.headers())                  # the person's lens, if they opened one (LENSES.md › The design)
        headers.update(kw.pop("headers", {}))
        raw = kw.pop("raw", False)
        with_headers = kw.pop("with_headers", False)
        with_status = kw.pop("with_status", False)
        tokens = kw.pop("tokens", None)                     # a screen's selections: to the node that issued them (one server per screen)
        if self._servers is not None:
            r = await self._send_pooled(method, path, headers, kw, sticky=self._servers.for_tokens(tokens) if tokens else None,
                                        retry=tokens is not None)
        else:
            try:
                r = await self._client.request(method, "/api/v1" + path, headers=headers, **kw)
            except httpx.TimeoutException as e:
                raise BackendError(504, "DRS-1004", f"the server did not answer in time ({type(e).__name__}); try again, "
                                                     "or raise the server's drishti.sources.fetch-timeout") from e
            except httpx.HTTPError as e:
                raise BackendError(503, "DRS-5003", f"backend unreachable: {e}") from e
        if r.status_code >= 400:
            raise _error(r, r.text)
        if with_status:                                   # an answer whose status matters (BI runs: 200 inline, 202 a run): (status, body, headers)
            body = None if r.status_code == 204 or not r.content else (r.json() if "json" in r.headers.get("content-type", "") else r.text)
            return r.status_code, body, r.headers
        if r.status_code == 204:
            return None
        if raw:
            return (r.content, r.headers) if with_headers else r.content
        return r.json() if "json" in r.headers.get("content-type", "") else r.text

    async def _send_pooled(self, method: str, path: str, headers: dict, kw: dict, sticky: str | None = None, retry: bool = False) -> httpx.Response:
        """One call through the server pool: refused, timed out or 502/503 counts against the server (``fail_after`` in a row set it
        aside), and a read is tried once more on another server; a write never is, and says it did not save. ``sticky``: the server
        that holds the screen's selections, used instead of the session's; ``retry``: a click (a POST that writes nothing) is tried again
        as a read is, so another server can say the selections were reset."""
        import time as _time
        read = retry or method in ("GET", "HEAD")
        base = sticky or self._servers.current()
        for attempt in (0, 1):
            t0 = _time.perf_counter()
            why = ""
            try:
                r = await self._client.request(method, base + "/api/v1" + path, headers=headers, **kw)
                if r.status_code not in (502, 503) or peer_problem(r.status_code, r.headers.get("content-type", ""), r.text):
                    self._servers.answered(base, (_time.perf_counter() - t0) * 1000)
                    if read and attempt == 0 and joining_problem(r.status_code, r.text):
                        other = self._servers.another(base)   # a node just rejoined that has not heard from its peers yet (SOAK3-01)
                        if other and other != base:
                            base = other
                            continue
                    return r
                why = f"HTTP {r.status_code}"
                err = r
            except httpx.TimeoutException as e:
                why, err = f"no answer in time ({type(e).__name__})", BackendError(504, "DRS-1004", f"the server did not answer in time ({type(e).__name__}); try again")
            except httpx.HTTPError as e:
                why, err = f"unreachable ({type(e).__name__})", BackendError(503, "DRS-5003", f"backend unreachable: {e}")
            set_aside = self._servers.failed(base, why)
            other = self._servers.current() if set_aside else self._servers.another(base)
            if not read:
                if isinstance(err, httpx.Response):
                    return err
                err.detail = f"{err.detail}. It did not save: try again in a moment"
                raise err
            if attempt == 0 and other and other != base:
                base = other                                  # a read is tried once more, on another server
                continue
            if isinstance(err, httpx.Response):
                return err
            raise err
        raise BackendError(503, "DRS-5003", "backend unreachable")

    async def open_download(self, path: str, ident, read_timeout: float | None = None) -> httpx.Response:
        """A large answer opened for streaming (the compliance export's zip): the server's refusal is raised before a byte is read; the caller
        iterates ``aiter_bytes()`` and closes the response, so the body is never held whole in the console."""
        headers = dict(ident.headers()) if ident is not None else {}
        headers.update(asof.headers())
        headers.update(edition.headers())                  # the person's lens: a download follows it as every other read does
        try:
            timeout = httpx.Timeout(self._client.timeout.connect, read=read_timeout) if read_timeout else httpx.USE_CLIENT_DEFAULT
            r = await self._client.send(self._client.build_request("GET", self._base() + "/api/v1" + path, headers=headers, timeout=timeout), stream=True)
        except httpx.TimeoutException as e:
            raise BackendError(504, "DRS-1004", f"the server did not answer in time ({type(e).__name__})") from e
        except httpx.HTTPError as e:
            raise BackendError(503, "DRS-5003", f"backend unreachable: {e}") from e
        if r.status_code >= 400:
            text = (await r.aread()).decode("utf-8", "replace")
            await r.aclose()
            raise _error(r, text)
        return r

    async def view(self, kind: str, id_: str, ident, controls: dict | None = None, panels: list[str] | None = None,
                   sutra: str | None = None, design: str | None = None) -> dict:
        """The view; ``controls`` are the ``ctl.<id>`` values the reader set, ``panels`` the only panels to build (``[]`` builds none),
        ``sutra`` another Sutra of the kind to draw it with (``?sutra=book-desk``), ``design`` the reader's own Design (the Sutra designer)."""
        where, q = entity_path(kind, id_)
        q = {**q, **(controls or {})}
        if panels is not None:
            q["panels"] = ",".join(panels) or "-"
        if sutra:
            q["sutra"] = sutra
        if design:
            q["design"] = design
        return await self._get(f"/views/{where}", ident, **q)

    async def view_event(self, kind: str, id_: str, ident, click: dict, controls: dict | None = None, sutra: str | None = None,
                         design: str | None = None) -> dict:
        """A click on a Sutra Next screen (RACHANA_REFERENCE.md 7): ``{panel, event, row, selections}``, evaluated by the server as the reader;
        the selection's token, the panels that read it, the clicked panel's children, the selections it cleared."""
        where, q = entity_path(kind, id_)
        q = {**q, **(controls or {}), **({"sutra": sutra} if sutra else {}), **({"design": design} if design else {})}
        return await self._send("POST", f"/views/{where}/events", ident, params=q, json=click, tokens=list(click.get("selections") or []))

    async def view_children(self, kind: str, id_: str, ident, panel: str, selections: list[str], controls: dict | None = None,
                            sutra: str | None = None, design: str | None = None) -> list:
        """The children of one panel of a Sutra Next screen, given the reader's selections (tokens), lazily (RACHANA_REFERENCE.md 8)."""
        where, q = entity_path(kind, id_)
        params = [*q.items(), *(controls or {}).items(), *([("sutra", sutra)] if sutra else []), *([("design", design)] if design else []), *[("sel", s) for s in selections]]
        return await self._send("GET", f"/views/{where}/panels/{quote(panel, safe='')}/children", ident, params=params, tokens=list(selections))

    async def view_on(self, kind: str, id_: str, ident, business_date: str) -> dict:
        """The view as of a business date, whatever date the reader's top bar holds (Compare's side by side)."""
        where, q = entity_path(kind, id_)
        return await self._send("GET", f"/views/{where}", ident, params=q, headers={asof.HEADER: business_date})

    async def explain(self, kind: str, id_: str, ident, generation: int | None = None, locale: str | None = None,
                      accept_language: str | None = None) -> dict:
        """About this page: where the data came from, why the layout is what it is, where next (the server's PageContext).
        ``generation`` is that of the page the caller shows; a newer one comes back marked ``newer``. ``locale`` (the language
        asked for) and ``accept_language`` (the browser's header) choose the language of the pack's text; the server falls back to English."""
        where, q = entity_path(kind, id_)
        if generation:
            q["generation"] = generation
        if locale:
            q["locale"] = locale
        headers = {"Accept-Language": accept_language} if accept_language else {}
        return await self._send("GET", f"/views/{where}/explain", ident, params=q, headers=headers)

    async def ask(self, kind: str, id_: str, ident, question: str, locale: str | None = None, accept_language: str | None = None) -> dict:
        """Ask about this page (the server's optional LLM layer, off by default): ``{answer, sources}``, or a BackendError with
        DRS-4007 (off), DRS-4008 (the model endpoint failed) or DRS-4009 (over the rate)."""
        where, q = entity_path(kind, id_)
        body = {"question": question, **({"locale": locale} if locale else {})}
        headers = {"Accept-Language": accept_language} if accept_language else {}
        return await self._send("POST", f"/views/{where}/ask", ident, params=q, json=body, headers=headers)

    async def ai_chat(self, ident, body: dict) -> dict:
        """The server's AI layer (``POST /api/v1/ai/chat``): the ``server`` provider's one call. Errors: DRS-4015 off, 4011 failed, 4012 limit, 4013 policy, 4014 unknown."""
        return await self._send("POST", "/ai/chat", ident, json=body, timeout=130.0)

    async def ai_mcp_call(self, ident, server: str, tool: str, body: dict) -> dict:
        """A tool call on one of the server's MCP servers, through the server's policy (``POST /api/v1/ai/mcp/{server}/tools/{tool}``)."""
        return await self._send("POST", f"/ai/mcp/{quote(server, safe='')}/tools/{quote(tool, safe='')}", ident, json=body, timeout=130.0)

    async def search(self, q: str, ident=None) -> dict:
        """Structured search: TRD where mtm > 1m order by mtm desc limit 50."""
        return await self._get("/search", ident, q=q)

    async def live_query(self, q: str, ident=None, again: bool = True, limit: int = 1000) -> dict:
        """A search's matches as a live result set (``GET /api/v1/live/query``): ``{set, live, total, seq, offset, rows: [{id, version, seq,
        doc}], search}``; ``again``: Search pressed (a static set is read afresh)."""
        return await self._get("/live/query", ident, q=q, again=str(bool(again)).lower(), limit=limit)

    async def live_kind(self, word: str, ident=None, offset: int = 0, limit: int = 100, sort: str = "", descending: bool = False) -> dict:
        """A page of a kind's own live result set (a bare mnemonic, ``POS <GO>``): its module's declaration, else the default one made from
        its contract (``GET /api/v1/live/kinds/{word}/rows``)."""
        params = {"offset": offset, "limit": limit, **({"sort": sort, "descending": str(bool(descending)).lower()} if sort else {})}
        return await self._get(f"/live/kinds/{quote(word, safe='')}/rows", ident, **params)

    async def form(self, pack: str, kind: str = "", ident=None) -> dict:
        """The search form of a pack's kind (query by form): its fields, declared or inferred, and the kinds you may open."""
        return await self._get(f"/forms/{quote(pack, safe='')}", ident, **({"kind": kind} if kind else {}))

    async def form_compile(self, pack: str, kind: str, body: dict, ident=None) -> dict:
        """The form's criteria as a command of the search grammar: ``{command}``."""
        return await self._send("POST", f"/forms/{quote(pack, safe='')}/{quote(kind, safe='')}/compile", ident, json=body)

    async def series(self, kind: str, id_: str, path: str, days: int, ident=None) -> dict:
        """One field over the last ``days`` business days (oldest first)."""
        where, q = entity_path(kind, id_)
        return await self._get(f"/history/{where}/series", ident, path=path, days=days, **q)

    async def history(self, kind: str, id_: str, ident=None, **params: str) -> dict:
        """One entity over business days (DATA_ARCHITECTURE.md › History queries: an entity over time, section 8): ``days=``, ``range=`` or ``from=``/``to=``, plus
        ``fields=``, ``diff=true``, ``knownAt=``; blank parameters take the server's defaults. Rows oldest first."""
        where, q = entity_path(kind, id_)
        return await self._send("GET", f"/history/{where}", ident, params={**{k: v for k, v in params.items() if v}, **q}, timeout=20.0)

    async def search_compare(self, q: str, from_: str, to: str, ident=None) -> dict:
        """A search on two business dates, side by side, with the change of every number."""
        return await self._get("/search/compare", ident, q=q, **{"from": from_}, **({"to": to} if to else {}))

    async def history_diff(self, kind: str, id_: str, ident=None, **params: str) -> dict:
        """What changed between two dates (or two "known at" times); blank parameters take the server's defaults."""
        where, q = entity_path(kind, id_)
        return await self._get(f"/history/{where}/diff", ident, **{k: v for k, v in params.items() if v}, **q)

    async def calc_settings(self, ident) -> dict:
        """Whether the user may use Calc (a role with calc), and its limits."""
        return await self._get("/calc/settings", ident)

    async def calc_snippets(self, ident) -> list:
        return await self._get("/me/calc-snippets", ident)

    async def save_calc_snippet(self, name: str, body: dict, ident) -> dict:
        return await self._send("PUT", f"/me/calc-snippets/{quote(name, safe='')}", ident, json=body)

    async def delete_calc_snippet(self, name: str, ident) -> None:
        await self._send("DELETE", f"/me/calc-snippets/{quote(name, safe='')}", ident)

    async def columns(self, kind: str, paths: str, limit: int | None, ident=None) -> dict:
        """Whole columns of a kind on the business date (Calc's drishti.columns())."""
        params = {"paths": paths} if paths else {}
        if limit:
            params["limit"] = limit
        # the first read of a business day loads its columns (the server waits up to drishti.calc.columns-budget, 20 s)
        return await self._send("GET", f"/search/columns/{quote(kind, safe='')}", ident, params=params, timeout=45.0)

    async def raw(self, kind: str, id_: str, ident=None) -> dict:
        where, q = entity_path(kind, id_)
        return await self._get(f"/entities/{where}/raw", ident, **q)

    async def suggest(self, q: str, ident, limit: int | None = None) -> list:
        return await self._get("/command/suggest", ident, q=q, **({"limit": limit} if limit else {}))

    async def embed_search(self, q: str, ident, limit: int | None = None) -> dict:
        """The search of ``<drishti-search>`` (scope embed:search): masked, cut to the host application's kinds by the server."""
        return await self._get("/embed/search", ident, q=q, **({"limit": limit} if limit else {}))

    async def command(self, text: str, ident=None) -> dict:
        return await self._send("POST", "/command", ident, json={"text": text})

    async def engine(self, method: str, path: str, ident, /, body=None, raw: bool = False, accept: str = "", timeout: float = 60.0,
                     **params) -> Any:
        """A compute engine's API, as the user (docs/architecture/DOMAIN_MODULES.md section 5, C7: the core client names no engine; a
        module's typed calls are its own, over this one). ``path`` starts after /api/v1 (``/risk/var/scope``); a list value is sent as a
        repeated parameter. The first answer of a business date loads the engine's inputs into the server's memory, so this waits up to
        ``timeout`` seconds (a minute). ``raw`` returns the bytes and headers (an export); ``accept`` (with ``raw``) asks for another format
        (the Arrow stream)."""
        kw: dict[str, Any] = {"params": {k: v for k, v in params.items() if v not in (None, "", [])}, "timeout": timeout}
        if body is not None:
            kw["json"] = body
        if raw:
            kw.update(raw=True, with_headers=True)
        if accept:
            kw["headers"] = {"Accept": accept}
        return await self._send(method, path, ident, **kw)

    async def modules(self, ident) -> dict:
        """What the person may see (docs/architecture/ACCESS_MODEL.md section 2): the modules loaded and not (with why), the lenses open to
        them and the functions they may not use (``GET /api/v1/modules``, as the person; core/modules.py reads it)."""
        return await self._send("GET", "/modules", ident, timeout=10.0)

    async def explain_number(self, method: str, path: str, ident, body=None, **params) -> Any:
        """One Explain for every number (/api/v1/explain, docs/architecture/SHARED_SCREENS.md section 3), as the user. ``path`` starts after
        /api/v1/explain (``""`` or ``/kinds``). Why it moved reads two business dates: this waits up to two minutes."""
        kw: dict[str, Any] = {"params": {k: v for k, v in params.items() if v not in (None, "")}, "timeout": 120.0}
        if body is not None:
            kw["json"] = body
        return await self._send(method, "/explain" + path, ident, **kw)

    async def pivot(self, method: str, path: str, ident, body=None, raw: bool = False, **params) -> Any:
        """The pivot engine (/api/v1/pivot, docs/guides/PIVOT_GUIDE.md), as the user. ``path`` starts after /api/v1/pivot (``/query``);
        ``raw`` returns the bytes and headers of an export. A table's first pivot reads it through its connector: this waits up to two
        minutes."""
        kw: dict[str, Any] = {"params": {k: v for k, v in params.items() if v not in (None, "")}, "timeout": 120.0}
        if body is not None:
            kw["json"] = body
        if raw:
            kw.update(raw=True, with_headers=True)
        return await self._send(method, "/pivot" + path, ident, **kw)

    async def sources(self, ident=None) -> dict:
        return await self._get("/sources", ident)

    async def ui_kit(self, ident=None) -> dict:
        """The Drishti UI kit: its fragments with their docstrings (RACHANA_REFERENCE.md 3a)."""
        return await self._get("/ui-kit", ident)

    async def sutras(self, ident=None) -> list:
        return await self._get("/sutras", ident)

    async def sutra_source(self, name: str, version: int, ident=None) -> str:
        return await self._get(f"/sutras/{name}/{version}/source", ident)

    async def preview(self, yaml_text: str, kind: str, id_: str, ident=None, document=None) -> dict:
        body = {"yaml": yaml_text, "kind": kind, "id": id_}
        if document is not None:
            body["document"] = document
        return await self._send("POST", "/studio/preview", ident, json=body)

    async def check(self, yaml_text: str, kind: str, samples: list, ident=None) -> dict:
        """The panel by sample matrix of the server's one checker (``POST /builder/check``); samples are ``{name, document}`` or ``{name, ref: {kind, id}}``."""
        return await self._send("POST", "/builder/check", ident, json={"yaml": yaml_text, "kind": kind, "samples": samples})

    async def inferred_from(self, kind: str, id_: str, name: str, document, ident=None) -> str:
        return await self._send("POST", "/studio/inferred", ident, json={"kind": kind, "id": id_, "name": name, "document": document})

    async def inferred(self, kind: str, id_: str, name: str, ident=None) -> str:
        where, q = entity_path(kind, id_)
        return await self._get(f"/studio/inferred/{where}", ident, name=name, **q)

    async def builder_shape(self, samples: list, ident=None) -> dict:
        """Screen Builder: the shape (schema, roles, report) of sample documents, ``[{name, document}]``."""
        return await self._send("POST", "/builder/shape", ident, json={"samples": samples})

    async def builder_design(self, samples: list, kind: str, ident=None) -> dict:
        """Screen Builder, step 3: a drafted Sutra for the samples (yaml, reasons, alternatives, pruned, preview of the first)."""
        return await self._send("POST", "/builder/design", ident, json={"samples": samples, "kind": kind})

    async def designs(self, method: str, path: str, ident, body=None, **params):
        """Build workbench Designs (``/builder/designs``): the server keeps them per user (samples, Sutra, notes) and answers
        404 DRS-5006 for anyone else's. ``path`` is below the collection (``""``, ``"/{id}"``, ``"/{id}/samples"``...)."""
        kw = {"params": {k: v for k, v in params.items() if v is not None}} if params else {}
        if body is not None:
            kw["json"] = body
        return await self._send(method, "/builder/designs" + path, ident, **kw)

    async def designs_raw(self, method: str, path: str, ident, content: bytes | None = None, content_type: str | None = None) -> bytes:
        """Like :meth:`designs` for a body or an answer that is bytes (a pack fragment zip)."""
        kw: dict = {"raw": True}
        if content is not None:
            kw["content"] = content
            kw["headers"] = {"Content-Type": content_type or "application/octet-stream"}
        return await self._send(method, "/builder/designs" + path, ident, **kw)

    async def builder_suggest(self, body: dict, ident=None) -> dict:
        """Panel kinds that suit a field (``POST /builder/suggest``): ``{shape: {schema, roles}, path, at?}``."""
        return await self._send("POST", "/builder/suggest", ident, json=body)

    async def rachana_schema(self, ident=None) -> dict:
        """The Rachana JSON Schema, generated from the grammar with this server's kinds, formats and functions."""
        return await self._get("/rachana/schema", ident)

    async def studio_settings(self, ident=None) -> dict:
        return await self._get("/studio/settings", ident)

    async def save_sutra(self, yaml_text: str, ident=None, note: str = "") -> dict:
        """Saves a Sutra; with review on, the answer is {"proposal": {...}} and the Sutra is not live yet."""
        return await self._send("POST", "/sutras", ident, content=yaml_text.encode(), headers={"Content-Type": "text/yaml"},
                                params={"note": note} if note else None)

    async def impact(self, kind: str, id_: str, ident=None) -> dict:
        where, q = entity_path(kind, id_)
        return await self._get(f"/impact/{where}", ident, **q)

    async def studio_tests(self, sutra: str, ident) -> list:
        return await self._get(f"/me/studio-tests/{quote(sutra)}", ident)

    async def set_studio_tests(self, sutra: str, entities: list, ident) -> list:
        return await self._send("PUT", f"/me/studio-tests/{quote(sutra)}", ident, json=entities)

    async def my_tokens(self, ident) -> list:
        return await self._get("/me/tokens", ident)

    async def token_scopes(self, ident) -> dict:
        """The write scopes a token may be given and the longest life of a write token (server configuration)."""
        return await self._get("/me/tokens/scopes", ident)

    async def create_token(self, name: str, days, ident, scopes=None) -> dict:
        return await self._send("POST", "/me/tokens", ident, json={"name": name, "days": days, "scopes": scopes or []})

    async def revoke_token(self, id_: str, ident) -> None:
        return await self._send("DELETE", f"/me/tokens/{quote(id_)}", ident)

    async def command_history(self, ident) -> list:
        return await self._get("/command/history", ident)

    async def aliases(self, ident) -> dict:
        return await self._get("/command/aliases", ident)

    async def set_aliases(self, aliases: dict, ident) -> dict:
        return await self._send("PUT", "/command/aliases", ident, json=aliases)

    async def pack_coverage(self, name: str, ident=None) -> dict:
        """Which Sutra lays out which combination of the data's match fields (source system x product type ...), for each kind."""
        return await self._get(f"/packs/{quote(name)}/coverage", ident)

    async def pack_overview(self, name: str, ident=None) -> dict:
        """A pack's kinds with their mnemonics, counts, an example and key columns (MKT <GO>)."""
        return await self._get(f"/packs/{quote(name)}/overview", ident)

    async def pack_readme(self, name: str, ident=None) -> dict:
        """A pack's README (the README standard): ``meta`` (its front matter) and ``markdown``, without the kinds the user may not open."""
        return await self._get(f"/packs/{quote(name)}/readme", ident)

    async def packs(self, ident=None) -> list:
        return await self._get("/packs", ident)

    async def functions(self, ident=None) -> dict:
        """Every command the user can use, by pack (FUNC, /api/v1/functions): only commands that are on, of the user's packs and roles."""
        return await self._get("/functions", ident)

    async def datamap(self, ident=None) -> dict:
        """The data map (DATAMAP, /api/v1/datamap): the user's packs, kinds with counts from the lake, contract joins with their coverage;
        ``state`` says whether the server is still counting or checking."""
        return await self._get("/datamap", ident)

    async def data_datasets(self, ident=None, read_timeout: float | None = None) -> dict:
        """Export data's datasets (DATA, /api/v1/data): the ones the user may open, each with its source, format and dates held."""
        return await self._send("GET", "/data", ident,
                                timeout=httpx.Timeout(self._client.timeout.connect, read=read_timeout) if read_timeout else httpx.USE_CLIENT_DEFAULT)

    async def data_dataset(self, kind: str, ident=None) -> dict:
        """One dataset's facts, the columns the user may see and every date it holds (/api/v1/data/{kind})."""
        return await self._get(f"/data/{quote(kind, safe='')}", ident)

    async def data_count(self, kind: str, ident, date: str = "", columns: str = "", where: list[str] | None = None,
                         read_timeout: float | None = None) -> dict:
        """A selection's row count, size and read-back (/api/v1/data/{kind}/count)."""
        params: dict[str, Any] = {k: v for k, v in (("date", date), ("columns", columns)) if v}
        if where:
            params["where"] = list(where)
        return await self._send("GET", f"/data/{quote(kind, safe='')}/count", ident, params=params,
                                timeout=httpx.Timeout(self._client.timeout.connect, read=read_timeout) if read_timeout else httpx.USE_CLIENT_DEFAULT)

    async def choose_packs(self, active: list, ident) -> dict:
        return await self._send("PUT", "/me/packs", ident, json={"active": active})

    async def about(self, ident=None) -> dict:
        return await self._get("/about", ident)

    # -- personal layouts (layout mode) ------------------------------------------------------------------
    async def layouts(self, ident) -> dict:
        """Whether the user may customise layouts (and promote them), and every layout they keep."""
        return await self._get("/me/layouts", ident)

    async def save_layout(self, sutra: str, kind: str, body: dict, ident) -> dict:
        return await self._send("PUT", f"/me/layouts/{quote(sutra, safe='')}/{quote(kind, safe='')}", ident, json=body)

    async def reset_layout(self, sutra: str, kind: str, ident) -> None:
        await self._send("DELETE", f"/me/layouts/{quote(sutra, safe='')}/{quote(kind, safe='')}", ident)

    async def layout_promotion(self, sutra: str, kind: str, drop_hidden: bool, ident) -> dict:
        """The next version of the Sutra the user's layout would make, with the latest version's text."""
        return await self._get(f"/me/layouts/{quote(sutra, safe='')}/{quote(kind, safe='')}/promotion", ident,
                               dropHidden=str(bool(drop_hidden)).lower())

    async def promote_layout(self, sutra: str, kind: str, note: str, drop_hidden: bool, ident) -> dict:
        return await self._send("POST", f"/me/layouts/{quote(sutra, safe='')}/{quote(kind, safe='')}/promotion", ident,
                                json={"note": note, "dropHidden": bool(drop_hidden)})

    # -- workspaces -------------------------------------------------------------------------------
    async def workspaces(self, ident) -> list:
        return await self._get("/me/workspaces", ident)

    async def workspace(self, name: str, ident) -> dict:
        return await self._get(f"/me/workspaces/{quote(name)}", ident)

    async def save_workspace(self, name: str, body: dict, ident) -> dict:
        return await self._send("PUT", f"/me/workspaces/{quote(name)}", ident, json=body)

    async def delete_workspace(self, name: str, ident) -> None:
        return await self._send("DELETE", f"/me/workspaces/{quote(name)}", ident)

    async def workspace_share(self, name: str, ident) -> dict | None:
        """Who this workspace is shared with, or None when it is not shared."""
        try:
            return await self._get(f"/me/workspaces/{quote(name)}/share", ident)
        except BackendError as e:
            if e.status == 404:
                return None
            raise

    async def share_workspace(self, name: str, body: dict, ident) -> dict:
        return await self._send("PUT", f"/me/workspaces/{quote(name)}/share", ident, json=body)

    async def unshare_workspace(self, name: str, ident) -> None:
        return await self._send("DELETE", f"/me/workspaces/{quote(name)}/share", ident)

    async def shared_workspaces(self, ident) -> list:
        return await self._get("/workspaces/shared", ident)

    async def shared_workspace(self, owner: str, name: str, ident) -> dict:
        return await self._get(f"/workspaces/shared/{quote(owner)}/{quote(name)}", ident)

    async def phrase(self, text: str, ident=None) -> dict:
        """A phrase in plain words as a structured search, with how each part was read (it does not run it)."""
        return await self._get("/phrase", ident, text=text)

    # -- scheduled reports ------------------------------------------------------------------------
    async def reports(self, ident) -> list:
        return await self._get("/me/reports", ident)

    async def save_report(self, name: str, body: dict, ident) -> dict:
        return await self._send("PUT", f"/me/reports/{quote(name)}", ident, json=body)

    async def run_report(self, name: str, ident) -> dict:
        return await self._send("POST", f"/me/reports/{quote(name)}/run", ident)

    async def delete_report(self, name: str, ident) -> None:
        return await self._send("DELETE", f"/me/reports/{quote(name)}", ident)

    # -- distribution: export and subscriptions ----------------------------------------------------------------
    async def export(self, fmt: str, target: dict, ident) -> tuple[bytes, Any]:
        """A report, page, visual or view as a file for the signed-in reader (``pdf``, ``png``, ``xlsx``, ``csv``): ``(bytes, headers)``."""
        return await self._send("POST", f"/export/{quote(fmt)}", ident, json=target, raw=True, with_headers=True, timeout=120.0)

    async def subscriptions(self, ident) -> list:
        return await self._get("/me/subscriptions", ident)

    async def save_subscription(self, name: str, body: dict, ident) -> dict:
        return await self._send("PUT", f"/me/subscriptions/{quote(name)}", ident, json=body)

    async def distribution_status(self, ident) -> dict:
        """Whether exports and subscriptions are on, and how many subscriptions the caller owns of the most they may."""
        return await self._get("/distribution/status", ident)

    async def notify_options(self, ident) -> dict:
        """What alerts and subscriptions may use besides mail: the webhooks an administrator defined (names) and the text-message gateway."""
        return await self._get("/notify/options", ident)

    async def set_phone(self, phone: str, ident) -> dict:
        return await self._send("PUT", "/me/phone", ident, json={"phone": phone})

    async def delete_subscription(self, name: str, ident) -> None:
        return await self._send("DELETE", f"/me/subscriptions/{quote(name)}", ident)

    async def run_subscription(self, name: str, ident) -> dict:
        return await self._send("POST", f"/me/subscriptions/{quote(name)}/run", ident, timeout=300.0)

    async def pause_subscription(self, name: str, resume: bool, ident) -> dict:
        return await self._send("POST", f"/me/subscriptions/{quote(name)}/{'resume' if resume else 'pause'}", ident)

    async def preview_schedule(self, schedule: str, ident) -> dict:
        return await self._send("POST", "/subscriptions/preview", ident, json={"schedule": schedule})

    async def all_subscriptions(self, ident) -> list:
        return await self._get("/admin/subscriptions", ident)

    async def admin_pause_subscription(self, owner: str, name: str, resume: bool, ident) -> dict:
        return await self._send("POST", f"/admin/subscriptions/{quote(owner)}/{quote(name)}/{'resume' if resume else 'pause'}", ident)

    async def admin_delete_subscription(self, owner: str, name: str, ident) -> None:
        return await self._send("DELETE", f"/admin/subscriptions/{quote(owner)}/{quote(name)}", ident)

    # -- the one alert engine: rules, the alerts raised, administrators -------------------------------------------------------------
    async def alert_rules(self, ident) -> list:
        return await self._get("/alerts/rules", ident)

    async def alert_rule(self, name: str, ident) -> dict:
        return await self._get(f"/alerts/rules/{quote(name)}", ident)

    async def save_alert_rule(self, name: str, body: dict, ident) -> dict:
        return await self._send("PUT", f"/alerts/rules/{quote(name)}", ident, json=body)

    async def delete_alert_rule(self, name: str, ident) -> None:
        return await self._send("DELETE", f"/alerts/rules/{quote(name)}", ident)

    async def check_alert_rule(self, name: str, ident) -> dict:
        return await self._send("POST", f"/alerts/rules/{quote(name)}/check", ident, timeout=120.0)

    async def pause_alert_rule(self, name: str, resume: bool, ident) -> dict:
        return await self._send("POST", f"/alerts/rules/{quote(name)}/{'resume' if resume else 'pause'}", ident)

    async def preview_alert_rule(self, body: dict, ident) -> dict:
        return await self._send("POST", "/alerts/rules/preview", ident, json=body, timeout=120.0)

    # -- the one Approvals queue -------------------------------------------------------------------------------
    async def approvals_list(self, ident, box: str = "inbox", group: str = "", kind: str = "") -> dict:
        q = {"box": box, "limit": "300", **({"group": group} if group else {}), **({"kind": kind} if kind else {})}
        return await self._send("GET", "/approvals?" + "&".join(f"{k}={quote(v)}" for k, v in q.items()), ident)

    async def approvals_count(self, ident) -> dict:
        return await self._send("GET", "/approvals/count", ident)

    async def approvals_item(self, kind: str, id_: str, ident) -> dict:
        return await self._send("GET", f"/approvals/{quote(kind)}/{quote(id_, safe=':@~+')}", ident)

    async def approvals_act(self, kind: str, id_: str, action: str, body: dict, ident):
        """approve, reject, withdraw, or questions (ask)."""
        return await self._send("POST", f"/approvals/{quote(kind)}/{quote(id_, safe=':@~+')}/{action}", ident, json=body)

    async def admin_alerts(self, ident) -> dict:
        """``{rules, boards, platform}``: everyone's rules, the board tiles that notify, the platform alerts (administrators)."""
        return await self._get("/admin/alerts", ident)

    async def raised_alerts(self, ident, stage: str = "open") -> list:
        """The alerts raised for the person (and, for an administrator, the platform's), newest first."""
        return await self._send("GET", f"/alerts?stage={quote(stage)}", ident)

    async def act_on_alert(self, action: str, body: dict, ident) -> dict:
        """acknowledge, resolve, snooze or assign one raised alert: ``{id, note, for, to}``."""
        return await self._send("POST", f"/alerts/{action}", ident, json=body)

    async def admin_pause_alert_rule(self, owner: str, name: str, resume: bool, ident) -> dict:
        return await self._send("POST", f"/admin/alerts/rules/{quote(owner)}/{quote(name)}/{'resume' if resume else 'pause'}", ident)

    async def admin_delete_alert_rule(self, owner: str, name: str, ident) -> None:
        return await self._send("DELETE", f"/admin/alerts/rules/{quote(owner)}/{quote(name)}", ident)

    # -- monitors and alerts ----------------------------------------------------------------------
    async def mine(self, method: str, path: str, ident, body=None, **params):
        """Calls under /me (monitors, alerts) for the signed-in user."""
        kw = {"params": params} if params else {}
        if body is not None:
            kw["json"] = body
        return await self._send(method, "/me" + path, ident, **kw)

    async def business_date(self, ident=None) -> dict:
        """The server's business date: current, selected (rolled back to a business day), live, holidays."""
        return await self._get("/business-date", ident)

    async def sse(self, path: str, ident=None, opened: list | None = None, node: str | None = None):
        """Yields ``(event, data)`` from any server SSE endpoint under /api/v1. The open response is appended to
        ``opened`` when given, so a caller can close it from another task to end the stream. ``node``: the cluster node the stream belongs
        to (a standing query's holder, named in its id): opened on that server while the pool has it usable, so a subscription and its stream
        go through the same server whichever console worker serves them."""
        headers = {"Accept": "text/event-stream", **(ident.headers() if ident is not None else {}), **asof.headers()}
        async for pair in self._events("/api/v1" + path, None, headers, opened, node=node):
            yield pair

    async def sse_post(self, path: str, ident, body: dict, node: str | None = None):
        """Yields ``(event, data)`` from a server SSE endpoint under /api/v1 that is asked with a POST body (a progressive pivot answer:
        ``/pivot/query?progressive=true``, docs/guides/PIVOT_GUIDE.md); ``node`` as for ``sse``."""
        headers = {"Accept": "text/event-stream", **(ident.headers() if ident is not None else {}), **asof.headers()}
        async for pair in self._events("/api/v1" + path, None, headers, None, node=node, method="POST", body=body):
            yield pair

    async def _events(self, path: str, params: dict | None, headers: dict, opened: list | None, node: str | None = None,
                      method: str = "GET", body: dict | None = None):
        """An SSE stream's ``(event, data)`` pairs. Through the server pool a stream that cannot open counts against its server, so the
        browser's reconnect (it reconnects by itself) opens the stream on the next server once that one is set aside. A stream that opened and
        then broke (a restart, an idle or proxy timeout) does not count: the pool checks the server's health instead. A refusal is raised
        with the server's own code and reason (DRS-9180 "you hold no standing query", say), never a bare "stream refused"."""
        pinned = self._servers.for_node(node) if self._servers is not None and node and hasattr(self._servers, "for_node") else None
        base = pinned or self._base()
        started = False
        try:
            async with self._client.stream(method, base + path, params=params, timeout=None, headers=headers,
                                           **({"json": body} if body is not None else {})) as r:
                started = True
                if opened is not None:
                    opened.append(r)
                if r.status_code >= 400:
                    text = (await r.aread()).decode("utf-8", "replace")
                    if self._servers is not None and r.status_code in (502, 503) and not peer_problem(r.status_code, r.headers.get("content-type", ""), text):
                        self._servers.failed(base, f"HTTP {r.status_code}")
                    err = _error(r, text)
                    if err.code.startswith("HTTP-"):
                        err.code, err.detail = "DRS-5003", f"the server refused the stream (HTTP {r.status_code})"
                    raise err
                event, data = None, []
                async for line in r.aiter_lines():
                    if line.startswith("event:"):
                        event = line[6:].strip()
                    elif line.startswith("data:"):
                        data.append(line[5:])
                    elif line == "" and event:
                        yield event, "\n".join(data)
                        event, data = None, []
        except httpx.TransportError as e:
            if self._servers is None:
                raise
            if started and hasattr(self._servers, "stream_broke"):
                self._servers.stream_broke(base, f"stream broke ({type(e).__name__})")
            else:
                self._servers.failed(base, f"stream broke ({type(e).__name__})")
            raise BackendError(503, "DRS-5003", f"backend unreachable: {e}") from e

    # -- Rupaka phase 1: the BI shell and catalogue (docs/architecture/ARCHITECTURE.md §28) ---------------------------------------------
    async def bi(self, method: str, path: str, ident, body: dict | None = None, wait: float | None = None, **params):
        """``/api/v1/bi/...`` as the signed-in user: the server decides what they may see and do, every time. ``wait`` (seconds) replaces
        the console's own wait for a call that may run longer (a query: the server lets one run ``drishti.bi.query.timeout``, 30 s)."""
        kw: dict = {}
        if wait:
            kw["timeout"] = wait
        if params:
            kw["params"] = params
        if body is not None:
            kw["json"] = body
        return await self._send(method, "/bi" + path, ident, **kw)

    async def bi_async(self, method: str, path: str, ident, body: dict | None, prefer: str, wait: float | None = None, **params):
        """A BI question asked as a run (ARCHITECTURE.md §28 › BI runs: every BI query as a bounded, visible job (built in 2.6.7), section 6): ``Prefer: respond-async, wait=N`` is passed on and the answer comes back as
        ``(status, body)``: 200 the answer (done within the wait), 202 the run that goes on. A refusal (409 DRS-9082, 409 DRS-9089) raises."""
        kw: dict = {"with_status": True, "headers": {"Prefer": prefer}}
        if wait:
            kw["timeout"] = wait
        if params:
            kw["params"] = params
        if body is not None:
            kw["json"] = body
        status, answer, _ = await self._send(method, "/bi" + path, ident, **kw)
        return status, answer

    async def bi_file(self, method: str, path: str, ident, body: dict | None = None, wait: float | None = None):
        """A BI answer that is a file (a run's export): ``(bytes, response headers)``, passed through unchanged."""
        kw: dict = {"raw": True, "with_headers": True}
        if wait:
            kw["timeout"] = wait
        if body is not None:
            kw["json"] = body
        return await self._send(method, "/bi" + path, ident, **kw)

    async def bi_accepting(self, path: str, ident, body: dict | None, accept: str, wait: float | None = None, **params):
        """A BI question asked with an ``Accept`` header (2.9.7: the Arrow stream of a visual's answer): ``(bytes, response headers)``; the
        server answers JSON when it cannot answer that format, which the caller reads from the content type."""
        kw: dict = {"raw": True, "with_headers": True, "headers": {"Accept": accept}}
        if wait:
            kw["timeout"] = wait
        if params:
            kw["params"] = params
        if body is not None:
            kw["json"] = body
        return await self._send("POST", "/bi" + path, ident, **kw)

    async def bi_arrow(self, path: str, ident, body: dict | None, wait: float | None = None, **params):
        """A BI answer that is bytes (a Perspective visual's rows as an Arrow IPC stream): ``(bytes, response headers)``."""
        kw: dict = {"raw": True, "with_headers": True}
        if wait:
            kw["timeout"] = wait
        if params:
            kw["params"] = params
        if body is not None:
            kw["json"] = body
        return await self._send("POST", "/bi" + path, ident, **kw)


    # -- identity ---------------------------------------------------------------------------------
    async def login(self, username: str, password: str, service) -> dict:
        return await self._send("POST", "/auth/login", service, json={"username": username, "password": password})

    async def open_session(self, username: str, seconds: int, service) -> dict:
        """Opens a sign-in session on the server for a user it has just verified: ``{id, expiresAt, user}``."""
        return await self._send("POST", "/auth/sessions", service, json={"username": username, "seconds": seconds})

    async def session(self, session_id: str, service) -> dict:
        """Whether a session still stands, with its user as they are now (roles, password change due); 401 when it ended."""
        return await self._get(f"/auth/sessions/{quote(session_id, safe='')}", service)

    async def end_session(self, session_id: str, service) -> None:
        """Ends a session on the server (sign-out): its cookie no longer signs anyone in, on any console."""
        await self._send("DELETE", f"/auth/sessions/{quote(session_id, safe='')}", service)

    async def settings(self, ident) -> dict:
        return await self._get("/me/settings", ident)

    async def patch_settings(self, changes: dict, ident) -> dict:
        return await self._send("PATCH", "/me/settings", ident, json=changes)

    async def oidc_login(self, id_token: str, nonce: str, service) -> dict:
        """The server verifies a provider's ID token and signs the user in (single sign-on)."""
        return await self._send("POST", "/auth/oidc", service, json={"idToken": id_token, "nonce": nonce})

    async def embed_sign_in(self, id_token: str, nonce: str, origin: str, refreshed: bool, service) -> dict:
        """The renderer's sign-in window (EMBED_GUIDE.md 10.1): the server verifies the single sign-on's ID token and makes that user's
        embed token for the host application that registered ``origin`` (RFC 8693 form), audited with the origin."""
        return await self._send("POST", "/embed/sign-in-window", service, json={"idToken": id_token, "nonce": nonce, "origin": origin, "refreshed": refreshed})

    async def me(self, ident) -> dict:
        return await self._get("/auth/me", ident)

    async def change_password(self, current: str, new: str, ident) -> dict:
        return await self._send("POST", "/auth/password", ident, json={"current": current, "next": new})

    async def admin(self, method: str, path: str, ident, body: dict | None = None, timeout: float | None = None, headers: dict | None = None, **params):
        """Admin endpoints (``/admin/...``); the server enforces the admin role. ``timeout`` (seconds) overrides the console's
        own wait for a call that may legitimately take longer (a bridge test waits for the bridge's own timeout); ``headers`` are
        sent as given (``If-Match`` carries a version)."""
        kw = {"params": params} if params else {}
        if headers:
            kw["headers"] = headers
        if timeout:
            kw["timeout"] = timeout
        if body is not None:
            kw["json"] = body
        return await self._send(method, "/admin" + path, ident, **kw)

    async def net(self, method: str, path: str, ident, body: dict | None = None, headers: dict | None = None, **params):
        """Drishti*net's endpoints (``/api/v1/net/...``): the member list, seeds, drain, leave, the files of config/net/ (Admin -> Net).
        ``headers`` are sent as given (``If-Match`` carries a file's version)."""
        kw = {"params": params} if params else {}
        if headers:
            kw["headers"] = headers
        if body is not None:
            kw["json"] = body
        return await self._send(method, "/net" + path, ident, **kw)

    async def announce_load(self, pack: str, body: dict, ident) -> dict:
        """Tells the server a batch landed (``POST /api/v1/packs/{pack}/loads``), as Admin -> Data loads -> Lakes does after a build."""
        return await self._send("POST", f"/packs/{quote(pack, safe='')}/loads", ident, json=body)

    async def admin_upload(self, path: str, ident, data: bytes, headers: dict, timeout: float = 120.0):
        """Sends raw bytes to an admin endpoint (a pack archive); ``headers`` carry its file name, checksum and signature."""
        return await self._send("POST", "/admin" + path, ident, content=data, headers={"Content-Type": "application/octet-stream", **headers}, timeout=timeout)

    async def admin_stream_upload(self, path: str, ident, chunks, params: dict | None = None, timeout: float = 3600.0):
        """Streams a large body (a data file being loaded) to an admin endpoint chunk by chunk: the console never holds it whole."""
        return await self._send("POST", "/admin" + path, ident, content=chunks, params={k: v for k, v in (params or {}).items() if v},
                                headers={"Content-Type": "application/octet-stream"}, timeout=timeout)

    async def stream(self, kind: str, id_: str, ident=None, opened: list | None = None, controls: dict | None = None):
        """Yields ``(event, data)`` pairs from the server's SSE stream for a view, until it ends (see ``sse`` for ``opened``).
        ``controls`` are the ``ctl.<id>`` values the stream is built with: every frame keeps them."""
        headers = {"Accept": "text/event-stream", **(ident.headers() if ident is not None else {}), **asof.headers()}
        where, q = entity_path(kind, id_)
        q = {**q, **(controls or {})}
        async for pair in self._events(f"/api/v1/views/{where}/stream", q, headers, opened):
            yield pair

    async def aclose(self) -> None:
        await self._client.aclose()
