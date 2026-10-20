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

"""The console and several servers (docs/architecture/DRISHTI_NET.md section 11.1): ``backend.urls`` names one or more servers, and with
Drishti*net on the console learns the rest of the cluster from the server it is connected to, so it always has one to fall back to.

* **One active server per session, the rest standing by.** A session (a browser's sign-in) is sent to the first healthy server, the configured
  ones first, then the discovered ones nearest first by measured latency; it stays on it while it answers, so its caches and live views stay put.
* **Falling over.** A server that refuses connections, times out or answers 502/503 ``fail_after`` times in a row is set aside and the session
  moves to the next healthy one. A read that failed on the way is tried once more on another server (the person sees the page, not an error);
  a write (a save) never is: the page says it did not save.
* **Discovery.** Every ``discover_every`` the console asks the server it uses for the member list (``GET /api/v1/net/members``) and keeps the whole
  list: new members are added, members the cluster reports dead or left are dropped (the configured ``urls`` never are), and a member now
  advertised at another address replaces its old one. Members advertise only real addresses (never localhost, 127.x.x.x or 0.0.0.0), so a
  loopback or wildcard address is never discovered or kept (only the configured ``urls`` may name one: that is the console's choice). The list is saved to
  ``<console data dir>/net/servers.json`` (atomically, and at shutdown), so a console restarted while its configured server is down still knows
  where else to go.
* **Coming back.** A set-aside server is checked every ``check_every``; once healthy for a minute it is used again, for new sessions only.

Every failover is logged and kept for the last hour (Admin -> Net and Admin -> Health show them). Without ``backend.urls`` nothing here runs and the
console uses ``backend.url`` exactly as before. The pool is per console process; nothing in it is a secret.
"""
from __future__ import annotations

import asyncio
import contextvars
import ipaddress
import json
import logging
import os
import time
import urllib.parse
from collections import deque
from dataclasses import asdict, dataclass, field
from pathlib import Path

import httpx

LOG = logging.getLogger("drishti.console.cluster")
SESSION: contextvars.ContextVar[str] = contextvars.ContextVar("drishti_cluster_session", default="")
RECOVER_SECONDS = 60.0


def loopback_url(url: str) -> bool:
    """True when a URL's host is localhost, a loopback or a wildcard address: never a member's advertisement."""
    try:
        h = (urllib.parse.urlsplit(url).hostname or "").lower()
    except ValueError:
        return False
    if h == "localhost" or h.endswith(".localhost"):
        return True
    try:
        a = ipaddress.ip_address(h)
    except ValueError:
        return False
    return a.is_loopback or a.is_unspecified
HOUR = 3600.0


def _seconds(v, default: float) -> float:
    text = str(v if v is not None else default).strip().lower()
    for unit, mul in (("ms", 0.001), ("s", 1), ("m", 60), ("h", 3600)):
        if text.endswith(unit) and text[: -len(unit)].replace(".", "", 1).isdigit():
            return float(text[: -len(unit)]) * mul
    try:
        return float(text)
    except ValueError:
        return float(default)


@dataclass
class Entry:
    """One server the console may use."""
    url: str
    origin: str                                   # configured | discovered
    node_id: str = ""
    healthy: bool = True
    fails: int = 0
    set_aside_at: float = 0.0
    healthy_since: float = field(default_factory=time.time)
    latency_ms: float | None = None
    last_error: str = ""
    checked: float = 0.0

    def usable(self, now: float) -> bool:
        """Healthy, and (when it was set aside) healthy again for a minute."""
        return self.healthy and (self.set_aside_at == 0 or now - self.healthy_since >= RECOVER_SECONDS)


class ServerPool:
    """The servers of one console process, their health, discovery and failover."""

    def __init__(self, settings, data_dir: Path):
        raw = settings.get("backend.urls") or ""
        urls = [u.strip().rstrip("/") for u in (raw if isinstance(raw, list) else str(raw).split(",")) if u and str(u).strip()]
        self.enabled = bool(urls)
        self.discover = str(settings.get("backend.discover", True)).lower() not in ("false", "0", "no", "off")
        self.discover_every = _seconds(settings.get("backend.discover_every"), 60)
        self.check_every = _seconds(settings.get("backend.check_every"), 5)
        self.fail_after = max(1, int(settings.get("backend.fail_after", 2) or 2))
        self.timeout = float(settings.get("backend.timeout_seconds", 5) or 5)
        self.file = Path(data_dir) / "net" / "servers.json"
        self.entries: dict[str, Entry] = {u: Entry(u, "configured") for u in urls}
        self.pins: dict[str, str] = {}
        self.failovers: deque = deque(maxlen=200)
        self._load()

    # -- the list ---------------------------------------------------------------------------------------------------------------
    def _load(self) -> None:
        """Discovered servers from the last run: where to go when the configured ones are down."""
        if not self.enabled:
            return
        try:
            saved = json.loads(self.file.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return
        for s in saved.get("servers", []):
            url = str(s.get("url") or "").rstrip("/")
            if url and url not in self.entries and not loopback_url(url):
                self.entries[url] = Entry(url, "discovered", str(s.get("node_id") or ""))

    def save(self) -> None:
        """``<console data dir>/net/servers.json``, written atomically (a temporary file in the same folder, then a rename)."""
        if not self.enabled:
            return
        try:
            self.file.parent.mkdir(parents=True, exist_ok=True)
            tmp = self.file.with_name(f"servers-{os.getpid()}.tmp")
            body = {"saved_at": time.time(), "servers": [{"url": e.url, "origin": e.origin, "node_id": e.node_id} for e in self.entries.values()]}
            tmp.write_text(json.dumps(body, indent=2), encoding="utf-8")
            os.replace(tmp, self.file)
        except OSError as e:
            LOG.warning("could not save %s: %s", self.file, e)

    def ordered(self, now: float | None = None) -> list[Entry]:
        """Configured servers in their order, then discovered ones nearest first."""
        found = sorted((e for e in self.entries.values() if e.origin == "discovered"),
                       key=lambda e: (e.latency_ms if e.latency_ms is not None else 1e9, e.url))
        return [e for e in self.entries.values() if e.origin == "configured"] + found

    def preferred(self, avoid: str | None = None) -> str:
        """The first usable server (not ``avoid``); when none is usable, the first one that is at least healthy, else the first."""
        now = time.time()
        order = [e for e in self.ordered() if e.url != avoid]
        for test in (lambda e: e.usable(now), lambda e: e.healthy):
            for e in order:
                if test(e):
                    return e.url
        return order[0].url if order else (avoid or "")

    def current(self) -> str:
        """The server this session uses: its pin while that server is not set aside, else the preferred one (a failover)."""
        sid = SESSION.get()
        url = self.pins.get(sid)
        if url and url in self.entries and self.entries[url].healthy:
            return url
        nxt = self.preferred()
        if url and url != nxt:
            self._failover(url, nxt, self.entries[url].last_error if url in self.entries else "removed from the list")
        self.pins[sid] = nxt
        if len(self.pins) > 10_000:                           # sessions come and go: forget the oldest pins
            for k in list(self.pins)[:5_000]:
                self.pins.pop(k, None)
        return nxt

    on_failover = None                                   # set by the app: tells the new server, which records it in its audit log

    def _failover(self, frm: str, to: str, why: str) -> None:
        event = {"at": time.time(), "from": frm, "to": to, "why": why}
        self.failovers.append(event)
        LOG.warning("console moved from %s to %s: %s", frm, to, why or "it was set aside")
        if self.on_failover is not None:
            try:
                self.on_failover(event)
            except Exception:  # noqa: BLE001 - telling the server is best effort; the log line is there either way
                LOG.debug("could not report the failover", exc_info=True)

    # -- what requests report ----------------------------------------------------------------------------------------------------
    def failed(self, url: str, why: str) -> bool:
        """A request to ``url`` failed (refused, timed out, 502/503); True when the server is now set aside."""
        e = self.entries.get(url)
        if e is None:
            return False
        e.fails += 1
        e.last_error = why
        if e.healthy and e.fails >= self.fail_after:
            e.healthy = False
            e.set_aside_at = time.time()
            LOG.warning("server %s set aside after %d failed requests in a row: %s", url, e.fails, why)
            return True
        return not e.healthy

    def answered(self, url: str, ms: float) -> None:
        e = self.entries.get(url)
        if e is not None:
            e.fails = 0
            e.latency_ms = ms if e.latency_ms is None else round(0.7 * e.latency_ms + 0.3 * ms, 1)

    def another(self, url: str) -> str | None:
        """A server to try a failed read on once: the preferred one other than ``url``."""
        other = self.preferred(avoid=url)
        return other if other and other != url else None

    # -- the background: health and discovery --------------------------------------------------------------------------------------
    async def probe(self, url: str) -> float:
        """Milliseconds the server took to answer its public about; raises when it does not."""
        t0 = time.perf_counter()
        async with httpx.AsyncClient(timeout=min(self.timeout, 3.0)) as c:
            r = await c.get(f"{url}/public/about")
            r.raise_for_status()
        return (time.perf_counter() - t0) * 1000

    async def check(self, e: Entry) -> None:
        now = time.time()
        try:
            ms = await self.probe(e.url)
            if not e.healthy:
                e.healthy = True
                e.healthy_since = now
                e.fails = 0
                LOG.warning("server %s answers again; it is used for new sessions after %d s healthy", e.url, int(RECOVER_SECONDS))
            e.latency_ms = round(ms, 1) if e.latency_ms is None else round(0.7 * e.latency_ms + 0.3 * ms, 1)
            e.last_error = ""
        except Exception as ex:  # noqa: BLE001 - any failure is a failed check
            e.last_error = f"{type(ex).__name__}"
            if e.healthy:
                e.fails += 1
                if e.fails >= self.fail_after:
                    e.healthy = False
                    e.set_aside_at = now
                    LOG.warning("server %s set aside: it does not answer its health check (%s)", e.url, e.last_error)
        e.checked = now

    async def discover_once(self, headers: dict) -> None:
        """The member list of the server this console uses: new members added, dead or left ones dropped (never a configured one)."""
        url = self.preferred()
        try:
            async with httpx.AsyncClient(timeout=self.timeout) as c:
                r = await c.get(f"{url}/api/v1/net/members", headers=headers)
                r.raise_for_status()
                body = r.json()
        except Exception as ex:  # noqa: BLE001 - discovery tries again later
            LOG.debug("discovery from %s failed: %s", url, ex)
            return
        if not body.get("running", body.get("enabled")):
            return
        changed = False
        for m in body.get("members") or []:
            murl = str(m.get("url") or "").rstrip("/")
            if not murl or loopback_url(murl):
                continue                                  # a member never advertises a loopback address: not kept
            gone = m.get("state") in ("dead", "left")
            node = str(m.get("nodeId") or "")
            if node and not gone:                         # the member moved: its old address goes
                for old in [x for x in self.entries.values() if x.origin == "discovered" and x.node_id == node and x.url != murl]:
                    del self.entries[old.url]
                    changed = True
            e = self.entries.get(murl)
            if e is None and not gone:
                self.entries[murl] = Entry(murl, "discovered", str(m.get("nodeId") or ""))
                changed = True
                LOG.info("console learnt of server %s (node %s)", murl, m.get("nodeId"))
            elif e is not None:
                e.node_id = str(m.get("nodeId") or e.node_id)
                if gone and e.origin == "discovered":
                    del self.entries[murl]
                    changed = True
        if changed:
            self.save()

    async def run(self, headers) -> None:
        """Checks every server every ``check_every`` and discovers every ``discover_every``; never dies."""
        last_discovery = 0.0
        while True:
            try:
                await asyncio.gather(*(self.check(e) for e in list(self.entries.values())))
                if self.discover and time.time() - last_discovery >= self.discover_every:
                    last_discovery = time.time()
                    await self.discover_once(headers() if callable(headers) else headers)
            except asyncio.CancelledError:
                raise
            except Exception:  # noqa: BLE001 - the pool's loop never dies
                LOG.exception("server pool check failed")
            await asyncio.sleep(self.check_every)

    def snapshot(self) -> dict:
        """For Admin -> Net and Admin -> Health: the server in use, every server with its state, the failovers of the last hour."""
        now = time.time()
        active = self.preferred() if not SESSION.get() else self.current()
        return {"enabled": self.enabled, "active": active, "file": str(self.file),
                "servers": [{"url": e.url, "origin": e.origin, "nodeId": e.node_id, "healthy": e.usable(now), "latencyMs": e.latency_ms,
                             "fails": e.fails, "lastError": e.last_error} for e in self.ordered()],
                "failovers": [{**f, "at": f["at"]} for f in self.failovers if now - f["at"] <= HOUR]}

    def as_dict(self) -> dict:
        return {"entries": [asdict(e) for e in self.entries.values()]}
