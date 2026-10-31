#!/usr/bin/env python3
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

"""The Drishti*net soak on real processes (docs/architecture/DRISHTI_NET.md section 17): three servers from one seed, a console given the
first alone, tools/bench/users.py load on the console, one shard holder killed and restarted, then the console's own server killed and
restarted, and every answer checked against a single server holding all the data.

    python tools/bench/net_soak.py --work ~/drishti-scale-test/net --users 20 --seconds 600

The data (tools/bench/trades.py, small: --trades a day per system, two days): a lake ``shared`` (ENDUR and CALYPSO) read by servers a and b
(replicas: the same tables), a lake ``murex`` (MUREX) read by c alone (a shard), and a lake ``all`` with all three for the reference server.
Packs overlap: a trading + counterparty-risk, b trading, c trading + counterparty-risk (trading brings core and market-data); only packs whose
tables the lakes hold, and each node switches off, with a connector file, every Delta connector whose tables its lake lacks (SOAK-10).
Security is on: one token secret, the console signs in once as the seeded administrator.

The run: the reference server answers a fixed set of requests (trades of each system and date, searches by counterparty, counterparty
trade pages) and stops; the cluster starts (a the seed; b and c seeded with a), the console starts with ``backend.urls`` = a; the time until
the console lists all three is measured; users.py runs for --seconds; meanwhile a checker asks the same requests of a random running node and
through the console and compares: an answer equal to the reference is right, an answer that is partial or refused naming a node that is down
(DRS-5052, ``failed``) is named-partial, anything else is wrong. At --kill-shard-at c is killed (SIGKILL by PID) and restarted at
--restart-shard-at; at --kill-seed-at a is killed and the console's move timed (its next page that answers), and a restarted at
--restart-seed-at with no seed of its own (it rejoins from its peers.json).

History (HISTORY_QUERIES.md, 2.6.5): the load's users ask ``/v/{kind}/{id}/history`` (--history-share of their requests: 30 and 60 days,
3M, DIFF, now and then "known at"), and the checked set holds history requests of trades of every system (MUREX ones only c holds), of
counterparties and of --equities, asked as the administrator and as a viewer (masked fields), compared row by row (dates, values, changed
flags, masked columns) with the reference (tools/bench/soak_check.py). Every range is asked in both roles, and the checker asks the twin
(the same entity and range in the other role) of the same node at once, so the administrator and the viewer meet in both orders within
the history cache's life (SOAK2-01; ``maskPairs`` in the report counts the pairs per order, peer-held or not, and their wrong answers). While a node is down an answer may be partial or refused only naming
it (DRS-5052) or a time-out (DRS-1014); a short answer not marked partial, or DRS-1013, is wrong.

Long runs: every --sample-every seconds each process's resident memory, heap (used, and live data after the last collection), open files
and threads go into the report; it ends with a latency and error table (one line per action, history its own) and a verdict: no wrong
answers, errors only inside the kill windows, p95/p99 per action, memory not growing after --warmup (the steady slope, outside the kill
windows and --settle seconds after each, a level per span; the whole-run slope is reported beside it). The result is one JSON object (also in
<work>/soak.json, the table and verdict in <work>/soak-report.txt); the exit code is 0 only when the verdict passes. Processes are stopped by
PID; nothing is written outside --work.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import hmac
import http.cookiejar
import json
import os
import random
import re
import signal
import socket
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
sys.path.insert(0, str(ROOT / "tools" / "bench"))
from javacmd import java_cmd  # noqa: E402
import hostaddr  # noqa: E402
import soak_check  # noqa: E402

HOST = hostaddr.primary_ipv4()      # members advertise this machine's real address, never a loopback one (docs/admin/OPERATIONS.md)

FORBIDDEN = {18480, 17480}
KEY = "net-soak-cluster-key-0123456789abcdef-xyz"
TOKEN_SECRET = "net-soak-token-secret-0123456789abcdef-0123"
SESSION_SECRET = "net-soak-session-secret-0123456789abcdef-0123"
ADMIN, PASSWORD = "drishti-dev-admin", "drishti-dev-admin123"
VIEWER = "soak-viewer"                  # a token with the viewer role: no raw, so drishti.security.redact fields read masked
#: drishti.security.redact on every server of the soak: fields its synthetic data holds (tools/bench/trades.py), so that masks mask
#: something (SOAK-09: the default list names fields such as 'trader' that these trades do not have, DRS-6011)
REDACT = ("PRICE", "TRADER_ID", "NAME")
#: per kind, the masked fields that are columns of its history: a viewer's history must show them masked on every row and in every diff.
#: PRICE moves when a trade is amended, so "never shown as changed" is exercised; a counterparty's NAME is its first column.
MUST_MASK = {"trade": ("PRICE",), "counterparty": ("NAME",)}
#: the history ranges the checked set asks (HISTORY_QUERIES.md section 7.1); "now" is the instant the lakes were built (knownAt), "day:i"
#: the i-th date of the run (from Python's index, clamped)
HISTORY_ASKS = ({"days": "30"}, {"days": "60"}, {"range": "3M"}, {"days": "30", "diff": "true"}, {"days": "30", "knownAt": "now"},
                {"days": "5"}, {"range": "1M"}, {"days": "10", "diff": "true"}, {"from": "day:2", "to": "day:-2"},
                {"from": "day:1", "to": "day:-1", "diff": "true"})
SYSTEMS = {"endur": lambda i: str(10_000_000 + i), "calypso": lambda i: f"CLY-{30_000_000 + i}", "murex": lambda i: f"MX-{50_000_000 + i}"}
NODES = {  # name: (lake, packs, port offset); only packs whose tables the lakes hold (no market-risk: SOAK-10)
    "a": ("shared", "trading,counterparty-risk", 0),
    "b": ("shared", "trading", 1),
    "c": ("murex", "trading,counterparty-risk", 2),
}
REF_PACKS = "trading,counterparty-risk"


def pack_closure(packs: str, packs_dir: Path) -> list[str]:
    """The packs named and every pack they extend, each once, the extended ones first."""
    import yaml
    out: list[str] = []

    def add(p: str) -> None:
        if p in out:
            return
        spec = yaml.safe_load((packs_dir / p / "pack.yaml").read_text(encoding="utf-8")) or {}
        for e in spec.get("extends") or []:
            add(e)
        out.append(p)

    for p in (x.strip() for x in packs.split(",") if x.strip()):
        add(p)
    return out


def connector_files(lake: Path, packs: str, packs_dir: Path = ROOT / "config" / "packs") -> dict[str, str]:
    """name -> the text of a connector file that switches off each Delta connector of the packs (and those they extend) whose tables the
    lake holds none of (<lake>/<domain>/<table.kind or kind>/_delta_log), so that the server neither starts nor retries it (SOAK-10). It is
    the documented way (a file in the connector folder, CONNECTOR_FILES.md section 3), not ``--drishti.sources.connectors.<name>.enabled``,
    which is deprecated and warns at every start. The file is the pack's template with ``enabled: false``, a YAML boolean (SOAK2-02)."""
    import yaml
    out = {}
    for p in pack_closure(packs, packs_dir):
        spec = yaml.safe_load((packs_dir / p / "pack.yaml").read_text(encoding="utf-8")) or {}
        for name, c in (spec.get("connectors") or {}).items():
            if c.get("plugin") != "delta":
                continue
            st = c.get("settings") or {}
            tables = st.get("table") if isinstance(st.get("table"), dict) else {}
            held = [k for k in c.get("kinds") or [] if (lake / str(st.get("domain") or "") / str(tables.get(k) or st.get(f"table.{k}") or k)
                                                       / "_delta_log").is_dir()]
            if held:
                continue
            body = {"plugin": "delta", "enabled": False, "kinds": list(c.get("kinds") or []),
                    "description": f"switched off by the Drishti*net soak: the lake {lake.name} holds none of its tables", "settings": st}
            out[name] = (f"# Written by tools/bench/net_soak.py: pack '{p}' connector, off for this node (SOAK-10).\n"
                         + yaml.safe_dump(body, sort_keys=False, width=140))
    return out


def b64(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode()


def token(user: str = ADMIN, roles=("admin",), ttl: int = 3600) -> str:
    """An HS256 token as the console mints them (core/auth.py mint_token)."""
    head = b64(json.dumps({"alg": "HS256", "typ": "JWT"}, separators=(",", ":")).encode())
    claims = b64(json.dumps({"sub": user, "roles": list(roles), "exp": int(time.time()) + ttl}, separators=(",", ":")).encode())
    return f"{head}.{claims}.{b64(hmac.new(TOKEN_SECRET.encode(), f'{head}.{claims}'.encode(), hashlib.sha256).digest())}"


def listening(port: int) -> bool:
    with socket.socket() as s:
        s.settimeout(0.3)
        return s.connect_ex(("127.0.0.1", port)) == 0


def fetch(url: str, headers: dict | None = None, timeout: float = 10, opener=None) -> tuple[int, str]:
    req = urllib.request.Request(url, headers=headers or {})
    try:
        with (opener.open(req, timeout=timeout) if opener else urllib.request.urlopen(req, timeout=timeout)) as r:
            return r.status, r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")
    except (OSError, ValueError) as e:
        return 0, repr(e)


def walk(x, drop, change):
    """``x`` with every dict for which ``drop`` holds left out of its list, and every dict passed through ``change``."""
    if isinstance(x, list):
        return [walk(v, drop, change) for v in x if not (drop and isinstance(v, dict) and drop(v))]
    if isinstance(x, dict):
        d = {k: walk(v, drop, change) for k, v in x.items()}
        return change(d) if change else d
    return x


def wait_up(url: str, seconds: float) -> None:
    end = time.time() + seconds
    while time.time() < end:
        if fetch(url, timeout=2)[0] in (200, 401, 403):
            return
        time.sleep(0.5)
    raise RuntimeError(f"{url} did not come up in {seconds:.0f} s")


def server_env(given: list[str] | None) -> dict[str, str]:
    """``--server-env KEY=VAL`` (repeatable) as a dict: added to every server's environment (for example ``MALLOC_ARENA_MAX=2``)."""
    out: dict[str, str] = {}
    for item in given or []:
        key, sep, value = item.partition("=")
        if not sep or not key.strip():
            raise SystemExit(f"--server-env wants KEY=VAL, got {item!r}")
        out[key.strip()] = value
    return out


def jvm_args(heap: str, extra: list[str] | None) -> list[str]:
    """The servers' JVM options before ``-jar``: ``-Xmx<heap>`` then every ``--jvm-arg`` in order (for example
    ``-XX:MaxDirectMemorySize=256m``, ``-XX:TrimNativeHeapInterval=60000``, docs/admin/PERFORMANCE.md)."""
    return [f"-Xmx{heap}", *(extra or [])]


class Soak:
    def __init__(self, a):
        self.a = a
        self.work = Path(a.work).expanduser().resolve()
        self.port = {n: a.base_port + off for n, (_, _, off) in NODES.items()}
        self.procs: dict[str, subprocess.Popen] = {}
        self.down: dict[str, float] = {}                   # node -> when it went down (still down)
        self.back: dict[str, float] = {}                   # node -> when it was restarted (peers may still name it for a while)
        self.started: dict[str, float] = {}                # node -> when it last started, in seconds from the start of the load
        self.errors: list[dict] = []                       # the checker's errors: {"t", "what", "verdict"}
        self.samples: list[dict] = []                      # resources, a sample per process every --sample-every seconds
        self.events: list[dict] = []
        self.t0 = time.time()
        jars = sorted(ROOT.glob("drishti-server/target/drishti-server-*-exec.jar"), key=os.path.getmtime, reverse=True)
        self.java = os.environ.get("JAVA_HOME", "/usr/lib/jvm/java-21-openjdk-amd64") + "/bin/java"
        if not HOST:
            raise SystemExit("this machine has no address but loopback: a Drishti*net member never advertises one")
        if not jars or not Path(self.java).exists():
            raise SystemExit("needs the built server: ./mvnw -o -q package -DskipTests -pl drishti-server -am")
        self.jar = str(jars[0])
        self.dates = a.dates.split(",")
        if a.days:                                         # the N business days to the last --dates (trades.py's calendar)
            import trades
            self.dates = trades.business_days(self.dates[-1], a.days)
        self.known_at = None                               # the instant "known at" history requests name: set once the lakes are built
        self.mask_coverage: dict | None = None             # the reference's viewer histories and how many mask what they must
        self.pairs: dict = {}                              # admin/viewer pairs asked back to back (SOAK2-01), see pair_count

    def url(self, name: str) -> str:
        return f"http://{HOST}:{self.port[name]}"

    def note(self, what: str, **kw) -> None:
        e = {"t": round(time.time() - self.t0, 1), "what": what, **kw}
        self.events.append(e)
        print(json.dumps(e), flush=True)

    # -- data -------------------------------------------------------------------------------------------------------------------
    def lakes(self) -> None:
        mark = self.work / "lakes" / "dates.json"
        if mark.exists() and json.loads(mark.read_text(encoding="utf-8")) != {"dates": self.dates, "trades": self.a.trades}:
            raise SystemExit(f"{self.work / 'lakes'} holds other dates or sizes: give a new --work")
        for lake, systems in (("all", "endur,calypso,murex"), ("shared", "endur,calypso"), ("murex", "murex")):
            root = self.work / "lakes" / lake
            if (root / "trading").exists():
                continue
            subprocess.run([sys.executable, str(ROOT / "tools/bench/trades.py"), "--lake", str(root), "--tmp", str(self.work / "tmp" / lake),
                            "--days", str(len(self.dates)), "--end", self.dates[-1], "--min", str(self.a.trades), "--max", str(self.a.trades),
                            "--counterparties", str(self.a.counterparties), "--systems", systems], check=True, stdout=subprocess.DEVNULL)
        mark.write_text(json.dumps({"dates": self.dates, "trades": self.a.trades}), encoding="utf-8")
        self.known_at = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")     # after every load: "known at" sees all of it

    # -- processes --------------------------------------------------------------------------------------------------------------
    def start_server(self, name: str, lake: str, packs: str, port: int, seed: str | None, net: bool = True) -> None:
        work = self.work / "nodes" / name
        for d in ("data", "connectors", "net/peers"):
            (work / d).mkdir(parents=True, exist_ok=True)
        if net:
            (work / "net" / "node.yaml").write_text(f"enabled: true\nnode-id: drishti-{name}\nadvertise-url: http://{HOST}:{port}\n"
                                                    "secret: ${DRISHTI_NET_SECRET}\n", encoding="utf-8")
            (work / "net" / "gossip.yaml").write_text("interval: 1s\nsuspect-after: 3s\ndead-after: 10s\npersist-every: 30s\n", encoding="utf-8")
            if seed:
                (work / "net" / "peers" / "drishti-a.yaml").write_text(f"url: {seed}\n", encoding="utf-8")
        for cname, text in connector_files(self.work / "lakes" / lake, packs).items():     # the lake holds none of its tables: off
            (work / "connectors" / f"{cname}.yaml").write_text(text, encoding="utf-8")
        assert port not in FORBIDDEN and not listening(port), f"port {port} is in use"
        env = dict(os.environ, DRISHTI_NET_SECRET=KEY, DRISHTI_TOKEN_SECRET=TOKEN_SECRET, DRISHTI_SECURITY_ENABLED="true",
                   DRISHTI_DATA_DIR=str(work / "data"), DRISHTI_DELTA_ROOT=str(self.work / "lakes" / lake), DRISHTI_PACKS=packs,
                   DRISHTI_PACKS_DIR=str(ROOT / "config" / "packs"), DRISHTI_CONNECTORS_DIR=str(work / "connectors"),
                   DRISHTI_NET_DIR=str(work / "net"), DRISHTI_ACCESS_LOG="false", **server_env(getattr(self.a, "server_env", None)))
        args = [*jvm_args(self.a.heap, getattr(self.a, "jvm_arg", None)), "-jar", self.jar, f"--server.port={port}", f"--drishti.net.dir={work / 'net'}", "--drishti.net.poll=1s",
                "--drishti.sources.plugins.demo.enabled=false", "--drishti.security.registered-users-only=false",
                "--drishti.security.redact=" + ",".join(REDACT)]
        log = open(self.work / f"server-{name}.log", "ab")
        self.procs[name] = subprocess.Popen(java_cmd(self.java, args, tmp=work / "data" / "tmp" / "server"), cwd=work, env=env, stdout=log,
                                            stderr=subprocess.STDOUT)
        wait_up(f"http://127.0.0.1:{port}/actuator/health", 240)

    def node(self, name: str) -> None:
        lake, packs, _ = NODES[name]
        self.start_server(name, lake, packs, self.port[name], None if name == "a" else self.url("a"))

    def kill(self, name: str) -> None:
        p = self.procs.pop(name)
        os.kill(p.pid, signal.SIGKILL)
        p.wait(30)
        self.down[name] = time.time()

    def stop(self, name: str) -> None:
        p = self.procs.pop(name, None)
        if p is None:
            return
        p.terminate()
        try:
            p.wait(60)
        except subprocess.TimeoutExpired:
            p.kill()
            p.wait(20)

    def start_console(self) -> None:
        port = self.a.console_port
        assert port not in FORBIDDEN and not listening(port), f"port {port} is in use"
        env = dict(os.environ, DRISHTI_AUTH_ENABLED="true", DRISHTI_SESSION_SECRET=SESSION_SECRET, DRISHTI_TOKEN_SECRET=TOKEN_SECRET,
                   DRISHTI_SECURE_COOKIE="false", DRISHTI_BACKEND_URLS=self.url("a"), DRISHTI_BACKEND_URL=self.url("a"),
                   DRISHTI_CONSOLE_DATA_DIR=str(self.work / "console"), DRISHTI_DATA_DIR=str(self.work / "console-data"))
        self.procs["console"] = subprocess.Popen([self.python(), str(ROOT / "drishti-console" / "run_drishti_web.py"),
                                                  f"--server.port={port}", "--backend.discover_every=5s", "--backend.check_every=1s"], env=env,
                                                 stdout=open(self.work / "console.log", "ab"), stderr=subprocess.STDOUT)
        wait_up(self.console() + "/healthz", 60)

    @staticmethod
    def python() -> str:
        """The console's Python (it has httpx, which users.py needs), else this one."""
        py = ROOT / "drishti-console" / ".venv" / "bin" / "python"
        return str(py if py.exists() else sys.executable)

    def console(self) -> str:
        return f"http://127.0.0.1:{self.a.console_port}"

    def close(self) -> None:
        for name in list(self.procs):
            self.stop(name)

    # -- the requests and the reference -----------------------------------------------------------------------------------------
    def requests(self) -> list[dict]:
        r = random.Random(20261019)
        out = []
        for date in self.dates:
            for system, ident in SYSTEMS.items():
                for i in r.sample(range(self.a.trades), self.a.sample):
                    out.append({"what": "trade", "system": system, "id": ident(i), "date": date})
            for c in r.sample(range(self.a.counterparties), self.a.sample // 3):
                out.append({"what": "search", "cp": f"CP-{c:05d}", "date": date})
        for c in r.sample(range(self.a.counterparties), self.a.sample // 3):
            out.append({"what": "ctrd", "cp": f"CP-{c:05d}", "date": self.dates[-1]})
        return out + self.history_requests(r)

    def history_requests(self, r: random.Random) -> list[dict]:
        """History of --history-sample trades of every system (MUREX ones only c holds: peer-only from a and b), half as many
        counterparties and the --equities, each with two different ranges of HISTORY_ASKS, each range as the administrator and as a viewer
        (masked fields), so every range is asked in both roles; all to the last date. The two roles of one range share a ``pair`` key: the
        checker asks the twin straight after (SOAK2-01: the history cache served one role's answer to the other)."""
        if self.a.history_share <= 0:
            return []
        n = max(1, self.a.history_sample)
        ids = [("trade", ident(i)) for ident in SYSTEMS.values() for i in r.sample(range(self.a.trades), min(self.a.trades, n))]
        ids += [("counterparty", f"CP-{c:05d}") for c in r.sample(range(self.a.counterparties), min(self.a.counterparties, max(1, n // 2)))]
        ids += [("equity", e.strip()) for e in self.a.equities.split(",") if e.strip()]
        out = []
        for k, (kind, ident) in enumerate(ids):
            for j in range(2):
                params = self.history_params(HISTORY_ASKS[(k + 3 * j) % len(HISTORY_ASKS)])
                for role in ("admin", "viewer"):
                    out.append({"what": "history", "kind": kind, "id": ident, "params": dict(params), "role": role,
                                "date": self.dates[-1], "pair": f"{k}:{j}"})
        return out

    @staticmethod
    def twins(reqs: list[dict]) -> dict:
        """(pair, role) -> the request: a history request's twin is the same entity and range in the other role."""
        return {(q["pair"], q["role"]): q for q in reqs if q.get("pair")}

    @staticmethod
    def peer_held(node: str, q: dict) -> bool:
        """The node holds none of the entity: its history comes through a peer (MUREX trades only on c; ENDUR and CALYPSO trades only
        on a and b; counterparties on a and c)."""
        if q.get("kind") == "trade":
            return (node != "c") if str(q.get("id", "")).startswith("MX-") else node == "c"
        return node == "b" if q.get("kind") == "counterparty" else False

    def pair_count(self, node: str, first: dict, verdicts: tuple[str, str]) -> None:
        """Count one pair asked back to back: per order (adminFirst, viewerFirst), how many peer-held, and wrong answers of each."""
        order = "adminFirst" if first.get("role") == "admin" else "viewerFirst"
        c = self.pairs.setdefault(order, {"pairs": 0, "peerHeld": 0, "wrongFirst": 0, "wrongSecond": 0, "wrongSecondPeerHeld": 0})
        peer = self.peer_held(node, first)
        c["pairs"] += 1
        c["peerHeld"] += peer
        c["wrongFirst"] += verdicts[0] == "wrong"
        c["wrongSecond"] += verdicts[1] == "wrong"
        c["wrongSecondPeerHeld"] += verdicts[1] == "wrong" and peer

    def history_params(self, ask: dict) -> dict:
        """An ask of HISTORY_ASKS with "now" and "day:i" filled in (from and to in order)."""
        def value(v: str) -> str:
            if v == "now":
                return self.known_at or ""
            if v.startswith("day:"):
                i = int(v[4:])
                return self.dates[max(-len(self.dates), min(i, len(self.dates) - 1))]
            return v
        out = {k: value(v) for k, v in ask.items()}
        if out.get("from", "") > out.get("to", "￿"):
            out["from"], out["to"] = out["to"], out["from"]
        return out

    def ask(self, base: str, q: dict) -> tuple[int, dict | str]:
        """One request of the set, to a server's API as the administrator."""
        who = (VIEWER, ("viewer",)) if q.get("role") == "viewer" else (ADMIN, ("admin",))
        h = {"Authorization": "Bearer " + token(*who), "X-Drishti-As-Of": q["date"]}
        if q["what"] == "history":
            path = f"/api/v1/history/{q['kind']}/~?" + urllib.parse.urlencode({"id": q["id"], **q["params"]})
        elif q["what"] == "trade":
            path = f"/api/v1/views/trade/{urllib.parse.quote(q['id'])}"
        elif q["what"] == "search":
            path = "/api/v1/search?q=" + urllib.parse.quote(f"TRD where COUNTERPARTY = '{q['cp']}' limit 1000")
        else:
            path = f"/api/v1/views/counterparty-trades/{q['cp']}"
        status, body = fetch(base + path, h, timeout=20)
        try:
            return status, json.loads(body)
        except ValueError:
            return status, body

    @staticmethod
    def essence(q: dict, body) -> object:
        """What must be equal: a view's title, strip and panels (not its timings or provenance, nor the administrators' line naming the node
        that built it, nor the shape fingerprint, nor a source's generation counter, which counts its own rebuilds); a search's rows (id and values), as a set."""
        if not isinstance(body, dict):
            return None
        if q["what"] == "history":
            return soak_check.history_essence(body)
        if q["what"] == "search":
            return sorted(json.dumps([(r.get("ref") or {}).get("id"), r.get("values")], sort_keys=True, default=str) for r in body.get("rows") or [])
        return walk({k: body.get(k) for k in ("ref", "title", "strip", "panels")}, lambda d: d.get("label") in ("Node", "Fingerprint") and "text" in d,
                    lambda d: {**d, "text": re.sub(r", gen \d+$", "", d["text"])} if d.get("label") == "Source" and isinstance(d.get("text"), str) else d)

    @staticmethod
    def loose(x) -> object:
        """A view with every link's status (resolved, pending, missing) and badge blanked: a link a peer did not answer within link-budget is
        pending."""
        return walk(x, None, lambda d: {**d, "status": "?", "badge": "?"} if d.get("status") in ("resolved", "pending", "missing") and "text" in d else d)

    def reference(self) -> list[dict]:
        port = self.a.base_port + 3
        self.start_server("ref", "all", REF_PACKS, port, None, net=False)
        base = f"http://127.0.0.1:{port}"
        reqs = self.requests()
        masks = {"viewerHistories": 0, "masked": 0, "problems": []}         # the reference itself masks what a viewer must not see
        for q in reqs:
            status, body = self.ask(base, q)
            q["refStatus"], q["ref"] = status, self.essence(q, body)
            if self.must_mask(q) and status == 200:
                masks["viewerHistories"] += 1
                missing = soak_check.mask_missing(body, self.must_mask(q))
                masks["masked"] += not missing
                masks["problems"] += [f"{q['kind']}/{q['id']}: {m}" for m in missing][: max(0, 5 - len(masks["problems"]))]
        (self.work / "reference.json").write_text(json.dumps(reqs, indent=1, default=str), encoding="utf-8")
        self.stop("ref")
        bad = [q for q in reqs if q["refStatus"] != 200]
        if self.a.history_share > 0:                       # no history checked: nothing to mask
            self.mask_coverage = {**masks, "distinctHistories": len({(q["kind"], q["id"]) for q in reqs if q["what"] == "history"}),
                                  "historyRequests": sum(1 for q in reqs if q["what"] == "history")}
        self.note("reference", requests=len(reqs), notOk=len(bad), sample=bad[:3], masks=self.mask_coverage)
        return [q for q in reqs if q["refStatus"] == 200]

    # -- checking ---------------------------------------------------------------------------------------------------------------
    @staticmethod
    def must_mask(q: dict) -> tuple:
        """The fields a history answer must show masked: MUST_MASK of its kind for a viewer, none for the administrator (raw)."""
        return MUST_MASK.get(q.get("kind"), ()) if q.get("what") == "history" and q.get("role") == "viewer" else ()

    def judge(self, q: dict, status: int, body) -> str:
        """right | partial (named, a node down) | wrong; a history answer also timeout (DRS-1014) or unavailable (soak_check)."""
        return self.judge_why(q, status, body)[0]

    def judge_why(self, q: dict, status: int, body) -> tuple[str, str]:
        """judge's verdict and the rule that decided it (kept for every wrong answer, SOAK-08)."""
        downs = [f"drishti-{n}" for n in self.down]
        if q["what"] == "history":                         # a node just restarted may still be named by peers that saw it dead
            downs += [f"drishti-{n}" for n, t in self.back.items() if time.time() - t < self.a.kill_grace]
            return soak_check.judge_history_why(q["ref"], status, body, downs, self.must_mask(q))
        text = json.dumps(body) if isinstance(body, dict) else str(body)
        if status == 200:
            got = self.essence(q, body)
            if got == q["ref"]:
                return "right", "equal"
            if q["what"] != "search" and self.loose(got) == self.loose(q["ref"]):
                return "right-links-pending", "equal but for pending links"
            if q["what"] == "search" and isinstance(body, dict) and body.get("partial"):
                named = [f for f in (body.get("failed") or []) if any(d in json.dumps(f) for d in downs)]
                if not named:
                    return "wrong", "partial search naming no node that is down"
                return ("partial", "named") if set(got) <= set(q["ref"]) else ("wrong", "partial search with rows not the reference's")
            pv = body.get("provenance") or {} if isinstance(body, dict) else {}
            if q["what"] != "search" and pv.get("partial"):              # a grouped page with a node down: named on the page
                return ("partial", "named") if any(d in json.dumps(pv.get("missing")) for d in downs) else ("wrong", "partial page naming no node that is down")
            return "wrong", "complete answer differs from the reference"
        if status in (502, 503) and downs and any(d in text for d in downs):
            return "partial", "named"
        if status == 0 or status in (502, 503):
            return "unavailable", f"status {status}"
        return "wrong", f"status {status}"

    def checker(self, stop_at: float, stats: dict, wrong: soak_check.WrongLog) -> None:
        r = random.Random(7)
        twins = self.twins(self.reqs)
        while time.time() < stop_at:
            q = r.choice(self.reqs)
            up = [n for n in NODES if n in self.procs]
            if not up:
                time.sleep(0.5)
                continue
            n = r.choice(up)
            first = self.check_one(n, q, stats, wrong)
            twin = twins.get((q.get("pair"), "viewer" if q.get("role") == "admin" else "admin")) if q.get("pair") else None
            if twin is not None and n in self.procs:       # the other role, same node, same range, at once (SOAK2-01)
                self.pair_count(n, q, (first, self.check_one(n, twin, stats, wrong)))
            time.sleep(self.a.check_every)

    def check_one(self, n: str, q: dict, stats: dict, wrong: soak_check.WrongLog) -> str:
        """Ask one request of node n, judge it, count it; the verdict."""
        status, body = self.ask(self.url(n), q)
        verdict, why = self.judge_why(q, status, body)     # partial only when it names a node that is down (killed by then)
        if verdict == "unavailable" and n in self.down:
            verdict = "asked-a-node-going-down"
        key = f"{q['what']}:{verdict}"
        stats[key] = stats.get(key, 0) + 1
        if verdict in soak_check.ERRORS:
            self.errors.append({"t": round(time.time() - self.t0, 1), "what": f"checker {q['what']}", "verdict": verdict})
        if verdict == "wrong":                             # every one: time, node, request, rule; the first 200 with body and diff
            wrong.add(round(time.time() - self.t0, 1), n, q, status, body, why, self.essence(q, body), q["ref"])
        return verdict

    # -- the console ------------------------------------------------------------------------------------------------------------
    def signed_in(self):
        jar = http.cookiejar.CookieJar()
        opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
        data = urllib.parse.urlencode({"user": ADMIN, "password": PASSWORD}).encode()
        opener.open(urllib.request.Request(self.console() + "/login", data=data), timeout=20).read()
        return opener

    def console_members(self, opener) -> dict:
        status, body = fetch(self.console() + "/admin/net/api/members", opener=opener)
        try:
            return json.loads(body).get("console", {}) if status == 200 else {}
        except ValueError:
            return {}

    def page_ok(self, opener, path: str, must: str) -> tuple[bool, int, str]:
        """The page answers with what it must show, and the session stands (no redirect to the sign-in page)."""
        try:
            with opener.open(urllib.request.Request(self.console() + path), timeout=5) as r:
                status, body, where = r.status, r.read().decode("utf-8", "replace"), r.geturl()
        except urllib.error.HTTPError as e:
            status, body, where = e.code, e.read().decode("utf-8", "replace"), e.geturl()
        except (OSError, ValueError) as e:
            status, body, where = 0, repr(e), ""
        signed_out = "/login" in urllib.parse.urlparse(where).path
        return status == 200 and must in body and not signed_out, status, ("signed out" if signed_out else body[-300:])

    # -- resources ----------------------------------------------------------------------------------------------------------------
    @staticmethod
    def proc_status(pid: int) -> dict:
        """A process's resident memory (MB), threads and open files, from /proc (Linux); empty where /proc is not there."""
        out = {}
        try:
            for line in Path(f"/proc/{pid}/status").read_text().splitlines():
                if line.startswith("VmRSS:"):
                    out["rssMb"] = round(int(line.split()[1]) / 1024, 1)
                elif line.startswith("Threads:"):
                    out["threads"] = int(line.split()[1])
            out["openFiles"] = len(os.listdir(f"/proc/{pid}/fd"))
        except (OSError, ValueError, IndexError):
            pass
        return out

    @staticmethod
    def descendants(pid: int) -> list[int]:
        """Every process below ``pid`` (children, their children ...), from /proc: each thread's ``children`` list, else the parent field of
        every /proc/<n>/stat. Empty where /proc is not there."""
        def kids(p: int) -> list[int]:
            try:
                return [int(c) for t in os.listdir(f"/proc/{p}/task") for c in Path(f"/proc/{p}/task/{t}/children").read_text().split()]
            except (OSError, ValueError):
                pass
            out = []
            for n in (x for x in os.listdir("/proc") if x.isdigit()) if os.path.isdir("/proc") else ():
                try:
                    if int(Path(f"/proc/{n}/stat").read_text().rsplit(")", 1)[1].split()[1]) == p:
                        out.append(int(n))
                except (OSError, ValueError, IndexError):
                    continue
            return out
        seen, todo = [], kids(pid)
        while todo:
            c = todo.pop(0)
            if c not in seen:
                seen.append(c)
                todo += kids(c)
        return seen

    @classmethod
    def tree_status(cls, pid: int) -> dict:
        """A process and all its descendants (the console: the uvicorn parent and its workers, SOAK-07): resident memory, threads and open
        files summed, the number of processes, and each worker's own figures."""
        workers = [{"pid": c, **cls.proc_status(c)} for c in cls.descendants(pid)]
        workers = [w for w in workers if "rssMb" in w]                     # gone between the listing and the read
        out = cls.proc_status(pid)
        for key in ("rssMb", "threads", "openFiles"):
            vals = [x[key] for x in [out, *workers] if key in x]
            if vals:
                out[key] = round(sum(vals), 1) if key == "rssMb" else sum(vals)
        return {**out, "processes": 1 + len(workers), "workers": workers}

    def jvm(self, name: str) -> dict:
        """A server's heap used and live data after the last collection (MB) and live threads, from its metrics endpoint."""
        out = {}
        for key, metric in (("heapMb", "jvm.memory.used?tag=area:heap"), ("liveMb", "jvm.gc.live.data.size"), ("jvmThreads", "jvm.threads.live")):
            status, body = fetch(f"{self.url(name)}/actuator/metrics/{metric}", {"Authorization": "Bearer " + token()}, timeout=5)
            try:
                value = json.loads(body)["measurements"][0]["value"] if status == 200 else None
            except (ValueError, KeyError, IndexError, TypeError):
                value = None
            if value is not None:
                out[key] = round(value / 1_048_576, 1) if key != "jvmThreads" else int(value)
        return out

    def sampler(self, stop_at: float) -> None:
        """Every --sample-every seconds, each running process's resources into self.samples."""
        while time.time() < stop_at:
            t = round(time.time() - self.t0, 1)
            for name, p in list(self.procs.items()):
                s = {"t": t, "node": name, **(self.tree_status(p.pid) if name == "console" else self.proc_status(p.pid))}
                if name in NODES:
                    s.update(self.jvm(name))
                self.samples.append(s)
            time.sleep(max(0.1, min(self.a.sample_every, stop_at - time.time())))

    # -- the run ----------------------------------------------------------------------------------------------------------------
    def run(self) -> dict:
        out: dict = {"trades": {"perSystemPerDay": self.a.trades, "dates": self.dates, "distinct": 3 * self.a.trades}}
        out["serverLaunch"] = {"jvm": jvm_args(self.a.heap, getattr(self.a, "jvm_arg", None)),
                               "env": server_env(getattr(self.a, "server_env", None))}
        self.lakes()
        self.reqs = self.reference()
        out["requests"] = len(self.reqs)
        out["maskCoverage"] = self.mask_coverage
        t = time.time()
        for n in NODES:
            self.node(n)
        self.converge(len(NODES))
        out["clusterUp"] = round(time.time() - t, 1)
        t = time.time()
        self.start_console()
        opener = self.signed_in()
        while time.time() - t < 90:
            urls = [s["url"] for s in self.console_members(opener).get("servers", [])]
            if all(self.url(n) in urls for n in NODES):
                break
            time.sleep(0.5)
        out["consoleLearntAllIn"] = round(time.time() - t, 1) if all(self.url(n) in urls for n in NODES) else None
        self.note("console learnt the cluster", seconds=out["consoleLearntAllIn"], servers=urls)
        load = subprocess.Popen([self.python(), str(ROOT / "tools/bench/users.py"), "--console", self.console(), "--user", ADMIN, "--password", PASSWORD,
                                 "--users", str(self.a.users), "--seconds", str(self.a.seconds), "--ids", str(self.a.trades),
                                 "--counterparties", str(self.a.counterparties), "--dates", ",".join(self.dates),
                                 "--history-share", str(self.a.history_share), "--equities", self.a.equities, "--fail-bucket", "10"],
                                stdout=subprocess.PIPE, text=True)
        self.t0 = time.time()
        stop_at = self.t0 + self.a.seconds
        stats: dict = {}
        wrong = soak_check.WrongLog()
        restarted: dict[str, float] = {}                    # node -> seconds from the start when its restart had converged
        check = threading.Thread(target=self.checker, args=(stop_at, stats, wrong), daemon=True)
        check.start()
        sample = threading.Thread(target=self.sampler, args=(stop_at,), daemon=True)
        sample.start()
        console_checks = {"ok": 0, "failed": 0, "signedOut": 0}
        plan = sorted([(self.a.kill_shard_at, "kill", "c"), (self.a.restart_shard_at, "restart", "c"),
                       (self.a.kill_seed_at, "kill", "a"), (self.a.restart_seed_at, "restart", "a")])
        r = random.Random(11)
        while time.time() < stop_at:
            if plan and time.time() - self.t0 >= plan[0][0]:
                _, act, n = plan.pop(0)
                if act == "kill":
                    self.kill(n)
                    self.note(f"killed {n}", pid="by PID")
                    out[f"kill-{n}"] = self.after_kill(n, opener)
                else:
                    t = time.time()
                    if n == "a":                                    # a has no seed: it must rejoin from its peers.json
                        peers = self.work / "nodes" / "a" / "data" / "net" / "peers.json"
                        out["a-peers.json"] = json.loads(peers.read_text()) if peers.exists() else None
                    self.node(n)
                    self.down.pop(n, None)
                    self.back[n] = time.time()
                    self.started[n] = round(time.time() - self.t0, 1)
                    started = round(time.time() - t, 1)
                    self.converge(len(NODES), 90)
                    restarted[n] = round(time.time() - self.t0, 1)
                    out[f"restart-{n}"] = {"started": started, "rejoinedAllAlive": round(time.time() - t, 1)}
                    self.note(f"restarted {n}", **out[f"restart-{n}"])
            q = r.choice([("/v/trade/CLY-30000001", "CLY-30000001"), ("/v/trade/10000002", "10000002"), ("/v/trade/MX-50000003", "MX-50000003")])
            ok, status, why = self.page_ok(opener, *q)
            if ok:
                console_checks["ok"] += 1
            elif why == "signed out":
                console_checks["signedOut"] += 1
                self.errors.append({"t": round(time.time() - self.t0, 1), "what": "console page", "verdict": "signed out"})
            elif not (q[1].startswith("MX") and "c" in self.down):
                console_checks["failed"] += 1
                self.errors.append({"t": round(time.time() - self.t0, 1), "what": "console page", "verdict": f"failed {status}"})
            time.sleep(1.0)
        check.join(30)
        sample.join(30)
        users_out, _ = load.communicate(timeout=120)
        try:
            out["users"] = json.loads(users_out.strip().splitlines()[-1])
        except (ValueError, IndexError):
            out["users"] = users_out[-2000:]
        out["checker"] = stats
        out["maskPairs"] = self.pairs
        out["wrong"] = wrong.report()                      # every wrong answer (SOAK-08): byKind, all, samples
        out["wrongAnswers"] = sum(v for k, v in stats.items() if k.endswith(":wrong"))
        out["checked"] = sum(stats.values())
        out["console"] = console_checks
        out["consoleFailovers"] = self.console_members(opener).get("failovers", [])
        out["events"] = self.events
        out["resources"] = self.samples
        out.update(self.verdict(stats, out["users"], restarted))
        return out

    def verdict(self, stats: dict, users, restarted: dict) -> dict:
        """The kill windows, the memory trend, the latency and error table and the verdict (soak_check)."""
        plan = [(self.a.kill_shard_at, "kill", "c"), (self.a.kill_seed_at, "kill", "a")]
        windows = soak_check.kill_windows(plan, restarted, self.a.seconds, self.a.kill_grace)
        users = users if isinstance(users, dict) else {}
        errors = list(self.errors)
        for label, buckets in (users.get("failedBuckets") or {}).items():   # the load's failures, per bucket of seconds (epoch)
            for b, n in buckets.items():
                errors.append({"t": round(float(b) - self.t0, 1), "width": float(users.get("bucket", 10)), "what": f"load {label}", "n": n})
        starts = {n: self.started.get(n, 0.0) for n in [*NODES, "console"]}
        memory = soak_check.memory_trend(self.samples, starts, self.a.warmup, self.a.max_growth, windows, self.a.settle)
        wrong = sum(v for k, v in stats.items() if k.endswith(":wrong"))
        return {"killWindows": windows, "memory": memory, "table": soak_check.table(users, stats),
                "verdict": soak_check.verdict(wrong, errors, windows, users, memory, self.a.max_p99, self.mask_coverage)}

    def after_kill(self, n: str, opener) -> dict:
        """The console's next good page after n was killed (a: the console's own server), and how long the others take to see n dead."""
        t, res = time.time(), {}
        while time.time() - t < 30:
            ok, status, why = self.page_ok(opener, "/v/trade/CLY-30000005", "CLY-30000005")
            if ok:
                res["consoleNextGoodPage"] = round(time.time() - t, 2)
                break
            res["lastFailure"] = [status, why[-200:]]
            time.sleep(0.2)
        status, body = fetch(self.console() + "/s?q=" + urllib.parse.quote("TRD where SOURCE_SYSTEM = 'CALYPSO' limit 50"), opener=opener)
        res["searchAfter"] = [status, "CLY-3000" in body]
        while time.time() - t < 60:
            try:
                views = [json.loads(fetch(self.url(m) + "/api/v1/net/members", {"Authorization": "Bearer " + token()})[1]) for m in NODES if m in self.procs]
                if all(any(x["nodeId"] == f"drishti-{n}" and x["state"] in ("dead", "suspect") for x in v["members"]) for v in views):
                    res["suspectOrDeadEverywhere"] = round(time.time() - t, 1)
                    break
            except (ValueError, KeyError):
                pass
            time.sleep(0.5)
        self.note(f"after killing {n}", **res)
        return res

    def converge(self, count: int, seconds: float = 60) -> None:
        end = time.time() + seconds
        while time.time() < end:
            try:
                views = [json.loads(fetch(self.url(m) + "/api/v1/net/members", {"Authorization": "Bearer " + token()})[1]) for m in NODES if m in self.procs]
                if all(sum(1 for x in v["members"] if x["state"] == "alive") == count for v in views):
                    return
            except (ValueError, KeyError):
                pass
            time.sleep(0.3)
        raise RuntimeError("the cluster did not converge")


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--work", required=True, help="the soak's folder: lakes, nodes, logs (outside the repository)")
    ap.add_argument("--trades", type=int, default=1500, help="trades a day per system")
    ap.add_argument("--counterparties", type=int, default=300)
    ap.add_argument("--dates", default="2026-10-15,2026-10-16")
    ap.add_argument("--users", type=int, default=20)
    ap.add_argument("--seconds", type=int, default=600)
    ap.add_argument("--kill-shard-at", type=float, default=120)
    ap.add_argument("--restart-shard-at", type=float, default=210)
    ap.add_argument("--kill-seed-at", type=float, default=330)
    ap.add_argument("--restart-seed-at", type=float, default=420)
    ap.add_argument("--sample", type=int, default=60, help="trades per system and date in the checked set (a third as many searches and pages)")
    ap.add_argument("--history-sample", type=int, default=40, help="trades per system whose history is checked (half as many counterparties); "
                    "each asked as the administrator and as a viewer, with two ranges")
    ap.add_argument("--check-every", type=float, default=0.05)
    ap.add_argument("--heap", default="1536m")
    ap.add_argument("--base-port", type=int, default=18783)
    ap.add_argument("--console-port", type=int, default=17783)
    ap.add_argument("--days", type=int, default=0, help="N business days ending at the last --dates, in place of --dates (more rows of history)")
    ap.add_argument("--history-share", type=float, default=0.15, help="the share of the load's requests that ask a history (0: none, and none checked)")
    ap.add_argument("--equities", default="", help="equity ids whose history is asked and checked too, when the lakes hold equities (comma separated)")
    ap.add_argument("--kill-grace", type=float, default=30, help="seconds after a restarted node has rejoined that still count as its kill window")
    ap.add_argument("--sample-every", type=float, default=60, help="seconds between samples of each process's memory, open files and threads")
    ap.add_argument("--warmup", type=float, default=600, help="seconds after a process starts before its memory counts towards the trend")
    ap.add_argument("--max-growth", type=float, default=64, help="MB an hour a process's memory may grow after warm-up (the slope of its samples "
                    "in the steady spans: outside the kill windows and --settle seconds after each, a level of its own per span)")
    ap.add_argument("--settle", type=float, default=600, help="seconds after a kill window during which the load is still moving between nodes: "
                    "left out of the steady memory slope")
    ap.add_argument("--max-p99", type=float, default=0, help="seconds: fail when an action's p99 is above it (0: report only)")
    ap.add_argument("--jvm-arg", action="append", default=[], help="a JVM option for every server, after -Xmx (repeatable), "
                    "e.g. -XX:MaxDirectMemorySize=256m")
    ap.add_argument("--server-env", action="append", default=[], help="KEY=VAL added to every server's environment (repeatable), "
                    "e.g. MALLOC_ARENA_MAX=2")
    a = ap.parse_args(argv)
    soak = Soak(a)
    soak.work.mkdir(parents=True, exist_ok=True)
    try:
        out = soak.run()
    finally:
        soak.close()
    (soak.work / "soak.json").write_text(json.dumps(out, indent=1, default=str), encoding="utf-8")
    report = out.get("table", "") + "\n\nwrong answers by kind and rule: " + json.dumps((out.get("wrong") or {}).get("byKind"), indent=1) + "\n\nverdict: " + json.dumps(out.get("verdict"), indent=1, default=str) + "\n"
    (soak.work / "soak-report.txt").write_text(report, encoding="utf-8")
    brief = {k: v for k, v in out.items() if k != "resources"}           # every wrong answer and the samples stay in soak.json
    if isinstance(brief.get("wrong"), dict):
        brief["wrong"] = {k: v for k, v in brief["wrong"].items() if k in ("count", "byKind")}
    print(json.dumps(brief, default=str))
    print(report)
    return 0 if out.get("wrongAnswers") == 0 and (out.get("verdict") or {}).get("pass") else 1


if __name__ == "__main__":
    sys.exit(main())
