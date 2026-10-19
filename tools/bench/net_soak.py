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
Packs overlap: a trading + counterparty-risk, b trading, c trading + counterparty-risk + market-risk (trading brings core and market-data).
Security is on: one token secret, the console signs in once as the seeded administrator.

The run: the reference server answers a fixed set of requests (trades of each system and date, searches by counterparty, counterparty
trade pages) and stops; the cluster starts (a the seed; b and c seeded with a), the console starts with ``backend.urls`` = a; the time until
the console lists all three is measured; users.py runs for --seconds; meanwhile a checker asks the same requests of a random running node and
through the console and compares: an answer equal to the reference is right, an answer that is partial or refused naming a node that is down
(DRS-5052, ``failed``) is named-partial, anything else is wrong. At --kill-shard-at c is killed (SIGKILL by PID) and restarted at
--restart-shard-at; at --kill-seed-at a is killed and the console's move timed (its next page that answers), and a restarted at
--restart-seed-at with no seed of its own (it rejoins from its peers.json). The result is one JSON object (also in <work>/soak.json).
Processes are stopped by PID; nothing is written outside --work.
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
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
from javacmd import java_cmd  # noqa: E402

FORBIDDEN = {18480, 17480}
KEY = "net-soak-cluster-key-0123456789abcdef-xyz"
TOKEN_SECRET = "net-soak-token-secret-0123456789abcdef-0123"
SESSION_SECRET = "net-soak-session-secret-0123456789abcdef-0123"
ADMIN, PASSWORD = "drishti-dev-admin", "drishti-dev-admin123"
SYSTEMS = {"endur": lambda i: str(10_000_000 + i), "calypso": lambda i: f"CLY-{30_000_000 + i}", "murex": lambda i: f"MX-{50_000_000 + i}"}
NODES = {  # name: (lake, packs, connectors switched off, port offset)
    "a": ("shared", "trading,counterparty-risk", ["trade-murex"], 0),
    "b": ("shared", "trading", ["trade-murex"], 1),
    "c": ("murex", "trading,counterparty-risk,market-risk", ["trade-endur", "trade-calypso"], 2),
}


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


class Soak:
    def __init__(self, a):
        self.a = a
        self.work = Path(a.work).expanduser().resolve()
        self.port = {n: a.base_port + off for n, (_, _, _, off) in NODES.items()}
        self.procs: dict[str, subprocess.Popen] = {}
        self.down: dict[str, float] = {}                   # node -> when it went down (still down)
        self.events: list[dict] = []
        self.t0 = time.time()
        jars = sorted(ROOT.glob("drishti-server/target/drishti-server-*-exec.jar"), key=os.path.getmtime, reverse=True)
        self.java = os.environ.get("JAVA_HOME", "/usr/lib/jvm/java-21-openjdk-amd64") + "/bin/java"
        if not jars or not Path(self.java).exists():
            raise SystemExit("needs the built server: ./mvnw -o -q package -DskipTests -pl drishti-server -am")
        self.jar = str(jars[0])
        self.dates = a.dates.split(",")

    def url(self, name: str) -> str:
        return f"http://127.0.0.1:{self.port[name]}"

    def note(self, what: str, **kw) -> None:
        e = {"t": round(time.time() - self.t0, 1), "what": what, **kw}
        self.events.append(e)
        print(json.dumps(e), flush=True)

    # -- data -------------------------------------------------------------------------------------------------------------------
    def lakes(self) -> None:
        for lake, systems in (("all", "endur,calypso,murex"), ("shared", "endur,calypso"), ("murex", "murex")):
            root = self.work / "lakes" / lake
            if (root / "trading").exists():
                continue
            subprocess.run([sys.executable, str(ROOT / "tools/bench/trades.py"), "--lake", str(root), "--tmp", str(self.work / "tmp" / lake),
                            "--days", str(len(self.dates)), "--end", self.dates[-1], "--min", str(self.a.trades), "--max", str(self.a.trades),
                            "--counterparties", str(self.a.counterparties), "--systems", systems], check=True, stdout=subprocess.DEVNULL)

    # -- processes --------------------------------------------------------------------------------------------------------------
    def start_server(self, name: str, lake: str, packs: str, off: list, port: int, seed: str | None, net: bool = True) -> None:
        work = self.work / "nodes" / name
        for d in ("data", "connectors", "net/peers"):
            (work / d).mkdir(parents=True, exist_ok=True)
        if net:
            (work / "net" / "node.yaml").write_text(f"enabled: true\nnode-id: drishti-{name}\nadvertise-url: http://127.0.0.1:{port}\n"
                                                    "secret: ${DRISHTI_NET_SECRET}\n", encoding="utf-8")
            (work / "net" / "gossip.yaml").write_text("interval: 1s\nsuspect-after: 3s\ndead-after: 10s\npersist-every: 30s\n", encoding="utf-8")
            if seed:
                (work / "net" / "peers" / "drishti-a.yaml").write_text(f"url: {seed}\n", encoding="utf-8")
        assert port not in FORBIDDEN and not listening(port), f"port {port} is in use"
        env = dict(os.environ, DRISHTI_NET_SECRET=KEY, DRISHTI_TOKEN_SECRET=TOKEN_SECRET, DRISHTI_SECURITY_ENABLED="true",
                   DRISHTI_DATA_DIR=str(work / "data"), DRISHTI_DELTA_ROOT=str(self.work / "lakes" / lake), DRISHTI_PACKS=packs,
                   DRISHTI_PACKS_DIR=str(ROOT / "config" / "packs"), DRISHTI_CONNECTORS_DIR=str(work / "connectors"),
                   DRISHTI_NET_DIR=str(work / "net"), DRISHTI_ACCESS_LOG="false")
        args = [f"-Xmx{self.a.heap}", "-jar", self.jar, f"--server.port={port}", f"--drishti.net.dir={work / 'net'}", "--drishti.net.poll=1s",
                "--drishti.sources.plugins.demo.enabled=false", "--drishti.security.registered-users-only=false"]
        args += [f"--drishti.sources.connectors.{c}.enabled=false" for c in off]
        log = open(self.work / f"server-{name}.log", "ab")
        self.procs[name] = subprocess.Popen(java_cmd(self.java, args, tmp=work / "data" / "tmp" / "server"), cwd=work, env=env, stdout=log,
                                            stderr=subprocess.STDOUT)
        wait_up(f"http://127.0.0.1:{port}/actuator/health", 240)

    def node(self, name: str) -> None:
        lake, packs, off, _ = NODES[name]
        self.start_server(name, lake, packs, off, self.port[name], None if name == "a" else self.url("a"))

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
        return out

    def ask(self, base: str, q: dict) -> tuple[int, dict | str]:
        """One request of the set, to a server's API as the administrator."""
        h = {"Authorization": "Bearer " + token(), "X-Drishti-As-Of": q["date"]}
        if q["what"] == "trade":
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
        self.start_server("ref", "all", "trading,counterparty-risk,market-risk", [], port, None, net=False)
        base = f"http://127.0.0.1:{port}"
        reqs = self.requests()
        for q in reqs:
            status, body = self.ask(base, q)
            q["refStatus"], q["ref"] = status, self.essence(q, body)
        (self.work / "reference.json").write_text(json.dumps(reqs, indent=1, default=str), encoding="utf-8")
        self.stop("ref")
        bad = [q for q in reqs if q["refStatus"] != 200]
        self.note("reference", requests=len(reqs), notOk=len(bad), sample=bad[:3])
        return [q for q in reqs if q["refStatus"] == 200]

    # -- checking ---------------------------------------------------------------------------------------------------------------
    def judge(self, q: dict, status: int, body) -> str:
        """right | partial (named, a node down) | wrong."""
        downs = [f"drishti-{n}" for n in self.down]
        text = json.dumps(body) if isinstance(body, dict) else str(body)
        if status == 200:
            got = self.essence(q, body)
            if got == q["ref"]:
                return "right"
            if q["what"] != "search" and self.loose(got) == self.loose(q["ref"]):
                return "right-links-pending"
            if q["what"] == "search" and isinstance(body, dict) and body.get("partial"):
                named = [f for f in (body.get("failed") or []) if any(d in json.dumps(f) for d in downs)]
                return "partial" if named and set(got) <= set(q["ref"]) else "wrong"
            pv = body.get("provenance") or {} if isinstance(body, dict) else {}
            if q["what"] != "search" and pv.get("partial"):              # a grouped page with a node down: named on the page
                return "partial" if any(d in json.dumps(pv.get("missing")) for d in downs) else "wrong"
            return "wrong"
        if status in (502, 503) and downs and any(d in text for d in downs):
            return "partial"
        if status == 0 or status in (502, 503):
            return "unavailable"
        return "wrong"

    def checker(self, stop_at: float, stats: dict, wrong: list) -> None:
        r = random.Random(7)
        while time.time() < stop_at:
            q = r.choice(self.reqs)
            up = [n for n in NODES if n in self.procs]
            if not up:
                time.sleep(0.5)
                continue
            n = r.choice(up)
            status, body = self.ask(self.url(n), q)
            verdict = self.judge(q, status, body)              # partial only when it names a node that is down (killed by then)
            if verdict == "unavailable" and n in self.down:
                verdict = "asked-a-node-going-down"
            key = f"{q['what']}:{verdict}"
            stats[key] = stats.get(key, 0) + 1
            if verdict == "wrong" and sum(1 for w in wrong if w["q"]["what"] == q["what"]) < 5:
                wrong.append({"t": round(time.time() - self.t0, 1), "node": n, "q": {k: q[k] for k in q if k not in ("ref",)},
                              "status": status, "got": self.essence(q, body), "ref": q["ref"], "body": (json.dumps(body) if isinstance(body, dict) else str(body))[:600]})
            time.sleep(self.a.check_every)

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

    # -- the run ----------------------------------------------------------------------------------------------------------------
    def run(self) -> dict:
        out: dict = {"trades": {"perSystemPerDay": self.a.trades, "dates": self.dates, "distinct": 3 * self.a.trades}}
        self.lakes()
        self.reqs = self.reference()
        out["requests"] = len(self.reqs)
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
                                 "--counterparties", str(self.a.counterparties), "--dates", ",".join(self.dates)], stdout=subprocess.PIPE, text=True)
        self.t0 = time.time()
        stop_at = self.t0 + self.a.seconds
        stats: dict = {}
        wrong: list = []
        check = threading.Thread(target=self.checker, args=(stop_at, stats, wrong), daemon=True)
        check.start()
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
                    started = round(time.time() - t, 1)
                    self.converge(len(NODES), 90)
                    out[f"restart-{n}"] = {"started": started, "rejoinedAllAlive": round(time.time() - t, 1)}
                    self.note(f"restarted {n}", **out[f"restart-{n}"])
            q = r.choice([("/v/trade/CLY-30000001", "CLY-30000001"), ("/v/trade/10000002", "10000002"), ("/v/trade/MX-50000003", "MX-50000003")])
            ok, status, why = self.page_ok(opener, *q)
            if ok:
                console_checks["ok"] += 1
            elif why == "signed out":
                console_checks["signedOut"] += 1
            elif not (q[1].startswith("MX") and "c" in self.down):
                console_checks["failed"] += 1
            time.sleep(1.0)
        check.join(30)
        users_out, _ = load.communicate(timeout=120)
        try:
            out["users"] = json.loads(users_out.strip().splitlines()[-1])
        except (ValueError, IndexError):
            out["users"] = users_out[-2000:]
        out["checker"] = stats
        out["wrong"] = wrong
        out["wrongAnswers"] = sum(v for k, v in stats.items() if k.endswith(":wrong"))
        out["checked"] = sum(stats.values())
        out["console"] = console_checks
        out["consoleFailovers"] = self.console_members(opener).get("failovers", [])
        out["events"] = self.events
        return out

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
    ap.add_argument("--check-every", type=float, default=0.05)
    ap.add_argument("--heap", default="1536m")
    ap.add_argument("--base-port", type=int, default=18783)
    ap.add_argument("--console-port", type=int, default=17783)
    a = ap.parse_args(argv)
    soak = Soak(a)
    soak.work.mkdir(parents=True, exist_ok=True)
    try:
        out = soak.run()
    finally:
        soak.close()
    (soak.work / "soak.json").write_text(json.dumps(out, indent=1, default=str), encoding="utf-8")
    print(json.dumps(out, default=str))
    return 0 if out.get("wrongAnswers") == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
