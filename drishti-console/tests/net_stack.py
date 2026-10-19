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

"""A real Drishti*net cluster for the stack browser tests, the soak and the documentation pictures: three built servers (a, b, c) on the
scratch ports 18783, 18784 and 18785, one console on 17784 given server a alone (``backend.urls``), each server with its own data folder and
``config/net/`` folder (b and c seeded with a), the same cluster key and token secret. The data is the finance pack's demo source on b (it
ticks; the console starts on a, so a live view is relayed from b) and small synthetic trades split three ways (JSON lines, UPPER_SNAKE fields, ENDUR on a, CALYPSO on b, MUREX on c, one trade on two);
security is off on the servers and sign-in off on the console, so a test drives the pages without a password. Everything lives in a scratch
folder; a port in use refuses the start; processes are stopped by PID. Under pytest-xdist each worker adds 100 to every port.

A cross-process lock (the same file the Java in-process cluster tests hold, drishti-server/target/net-it.lock) keeps one cluster at a time on
these ports.
"""
from __future__ import annotations

import fcntl
import glob
import json
import os
import signal
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

from bi_stack import FORBIDDEN, listening, wait
from scratch_ports import worker_port, worker_ports

ROOT = Path(__file__).resolve().parents[2]
CONSOLE = ROOT / "drishti-console"
SERVER_PORTS = worker_ports(18783, 18784, 18785)
CONSOLE_PORT = worker_port(17784)
NAMES = ("a", "b", "c")
KEY = "net-stack-cluster-key-0123456789abcdef-xyz"
TOKEN_SECRET = "net-stack-token-secret-0123456789abcdef"
SYSTEMS = {"a": ("ENDUR", "END"), "b": ("CALYPSO", "CLY"), "c": ("MUREX", "MX")}


def url(name: str) -> str:
    return f"http://127.0.0.1:{SERVER_PORTS[NAMES.index(name)]}"


CONSOLE_URL = f"http://127.0.0.1:{CONSOLE_PORT}"


def trades(name: str, n: int = 40) -> list[dict]:
    """A node's own trades: UPPER_SNAKE, with LEGAL_ENTITY; one trade (SHARED-1) on a and c."""
    system, prefix = SYSTEMS[name]
    rows = [{"TRADE_ID": f"{prefix}-{i:05d}", "BOOKING_SYSTEM": system, "LEGAL_ENTITY": ["LE-NY", "LE-LDN", "LE-FRA"][i % 3],
             "COUNTERPARTY": f"CP-{i % 7:05d}", "NOTIONAL": 1_000_000 + 25_000 * i, "CURRENCY": ["USD", "EUR", "GBP"][i % 3]} for i in range(n)]
    if name in ("a", "c"):
        rows.append({"TRADE_ID": "SHARED-1", "BOOKING_SYSTEM": "MUREX", "LEGAL_ENTITY": "LE-NY", "COUNTERPARTY": "CP-00001", "NOTIONAL": 7_777_777,
                     "CURRENCY": "USD"})
    return rows


class NetStack:
    def __init__(self, tmp: Path, trades_per_node: int = 40, java_heap: str = "-Xmx768m"):
        self.tmp = Path(tmp)
        self.n = trades_per_node
        self.heap = java_heap
        self.procs: dict[str, subprocess.Popen] = {}
        self._lock = None

    def _jar(self) -> tuple[str, str]:
        jars = sorted(glob.glob(str(ROOT / "drishti-server/target/drishti-server-*-exec.jar")), key=os.path.getmtime, reverse=True)
        java = os.environ.get("JAVA_HOME", "/usr/lib/jvm/java-21-openjdk-amd64") + "/bin/java"
        if not jars or not Path(java).exists():
            raise FileNotFoundError("needs the built server: ./mvnw -o -q package -DskipTests -pl drishti-server -am")
        return jars[0], java

    def prepare(self, name: str) -> Path:
        """The node's scratch folder: data, feeds (its trades), config/net (node, gossip, its seed)."""
        work = self.tmp / name
        (work / "net" / "peers").mkdir(parents=True, exist_ok=True)
        (work / "feeds").mkdir(parents=True, exist_ok=True)
        (work / "data").mkdir(parents=True, exist_ok=True)
        with open(work / "feeds" / "trade.jsonl", "w", encoding="utf-8") as f:
            f.writelines(json.dumps(r) + "\n" for r in trades(name, self.n))
        tags = {"a": "[region:emea, tier:risk]", "b": "[region:emea]", "c": "[region:apac]"}[name]
        (work / "net" / "node.yaml").write_text(f"enabled: true\nnode-id: drishti-{name}\nadvertise-url: {url(name)}\ntags: {tags}\n"
                                                "secret: ${DRISHTI_NET_SECRET}\n", encoding="utf-8")
        (work / "net" / "gossip.yaml").write_text("interval: 1s\nsuspect-after: 3s\ndead-after: 10s\npersist-every: 30s\n", encoding="utf-8")
        if name != "a":
            (work / "net" / "peers" / "drishti-a.yaml").write_text(f"url: {url('a')}\n", encoding="utf-8")
        return work

    def start_server(self, name: str) -> None:
        jar, java = self._jar()
        work = self.prepare(name)
        port = SERVER_PORTS[NAMES.index(name)]
        env = dict(os.environ, DRISHTI_NET_SECRET=KEY, DRISHTI_TOKEN_SECRET=TOKEN_SECRET, DRISHTI_SECURITY_ENABLED="false",
                   DRISHTI_DATA_DIR=str(work / "data"), DRISHTI_PACKS_DIR=str(ROOT / "config" / "packs"), DRISHTI_PACKS="finance",
                   DRISHTI_PACKS_EXTRA_DIRS=str(ROOT / "examples" / "packs"), DRISHTI_NET_DIR=str(work / "net"))
        args = [java, self.heap, "-jar", jar, f"--server.port={port}", f"--drishti.net.dir={work / 'net'}", "--drishti.net.poll=1s",
                f"--drishti.sources.plugins.file.settings.root={work / 'feeds'}", "--drishti.sources.plugins.file.settings.id-field=TRADE_ID",
                f"--drishti.sources.connectors-dir={work / 'connectors'}", "--drishti.sources.connectors.finance-lake.enabled=false"]
        if name != "b":
            args.append("--drishti.sources.plugins.demo.enabled=false")       # the demo's trades (and their ticks) are on b only
        self.procs[name] = subprocess.Popen(args, cwd=work, env=env, stdout=open(self.tmp / f"server-{name}.log", "ab"), stderr=subprocess.STDOUT)
        wait(url(name) + "/actuator/health", 180)

    def start_console(self, urls: str | None = None) -> None:
        cenv = dict(os.environ, DRISHTI_AUTH_ENABLED="false", DRISHTI_BACKEND_URLS=urls or url("a"), DRISHTI_BACKEND_URL=url("a"),
                    DRISHTI_CONSOLE_DATA_DIR=str(self.tmp / "console"), DRISHTI_DATA_DIR=str(self.tmp / "console-data"))
        self.procs["console"] = subprocess.Popen([sys.executable, str(CONSOLE / "run_drishti_web.py"), f"--server.port={CONSOLE_PORT}",
                                                  "--backend.discover_every=5s", "--backend.check_every=1s"], env=cenv,
                                                 stdout=open(self.tmp / "console.log", "ab"), stderr=subprocess.STDOUT)
        wait(CONSOLE_URL + "/healthz")

    def start(self) -> "NetStack":
        lock_path = ROOT / "drishti-server" / "target" / "net-it.lock"
        lock_path.parent.mkdir(parents=True, exist_ok=True)
        self._lock = open(lock_path, "a")
        fcntl.lockf(self._lock, fcntl.LOCK_EX)             # one cluster at a time on these ports, Java's or this one
        try:
            for port in (*SERVER_PORTS, CONSOLE_PORT):
                assert port not in FORBIDDEN and not listening(port), f"port {port} is in use: stop whatever listens there"
            for name in NAMES:
                self.start_server(name)
            self.wait_converged()
            self.start_console()
            return self
        except BaseException:
            self.close()
            raise

    def members(self, name: str) -> dict:
        with urllib.request.urlopen(url(name) + "/api/v1/net/members", timeout=5) as r:
            return json.loads(r.read())

    def wait_converged(self, seconds: float = 30) -> None:
        end = time.time() + seconds
        while time.time() < end:
            try:
                if all(sum(1 for m in self.members(n)["members"] if m["state"] == "alive") == len(self.running()) for n in self.running()):
                    return
            except (OSError, ValueError, KeyError):
                pass
            time.sleep(0.3)
        raise RuntimeError("the cluster did not converge")

    def running(self) -> list[str]:
        return [n for n in NAMES if n in self.procs and self.procs[n].poll() is None]

    def kill(self, name: str) -> None:
        """A crash: SIGKILL by PID (no clean leave)."""
        p = self.procs.pop(name)
        os.kill(p.pid, signal.SIGKILL)
        p.wait(20)

    def stop(self, name: str) -> None:
        """A clean stop by PID: the server leaves the cluster first."""
        p = self.procs.pop(name)
        p.terminate()
        try:
            p.wait(60)
        except subprocess.TimeoutExpired:
            p.kill()

    def close(self) -> None:
        for name in list(self.procs):
            p = self.procs.pop(name)
            p.terminate()
            try:
                p.wait(30)
            except subprocess.TimeoutExpired:
                p.kill()
        if self._lock is not None:
            fcntl.lockf(self._lock, fcntl.LOCK_UN)
            self._lock.close()
            self._lock = None


def get(base: str, path: str, timeout: float = 10) -> tuple[int, str]:
    try:
        with urllib.request.urlopen(base + path, timeout=timeout) as r:
            return r.status, r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")
