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
"""The 2.9.7 VaR benchmark with profiling (docs/architecture/RESOURCES_AND_SCALE.md section 10, BENCHMARK_2_9_7.md): one server or a
three-node sharded cluster on this machine, on a lake of tools/bench/vargen.py (VaR) and tools/bench/trades.py (trades), with JFR, heap
histograms after a full GC, the NMT summary and GC logs; one JSON line per measure (tools/bench/report.py's format) appended to --out.

    python3 tools/bench/var_bench.py load --data <bench>/vargen --lake <bench>/lake --engine java --out results.jsonl
    python3 tools/bench/var_bench.py run --mode single --java 21 --gc g1 --lake <bench>/lake --work <bench>/run-single-21 --out results.jsonl

``run``: start the server(s) (ports --base-port.., never the product owner's 18480, 18791, 18792, 17480), a histogram at start (no date
open), the first open of each date timed (its own JFR recording, for the stages), histograms with one and two dates open, an idle window
for the log bytes at rest, a warm-up, then tools/bench/users.py at each --users level (the VaR mix of section 10.1 beside the trades mix),
a histogram at the end, the JFR recordings dumped into --work/jfr, and the servers stopped by their process id. Refuses to start, and
stops the users, when the machine has less than --min-free-gb available. Nothing is written outside --work and --out.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import hmac
import json
import os
import re
import secrets
import signal
import subprocess
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
sys.path.insert(0, str(ROOT / "tools" / "bench"))
import hostaddr  # noqa: E402
from javacmd import java_cmd  # noqa: E402

FORBIDDEN = {18480, 18791, 18792, 17480}                 # the product owner's own stack: never touched
JAVAS = {"21": "/usr/lib/jvm/java-21-openjdk-amd64", "25": "/usr/lib/jvm/java-25-openjdk-amd64"}
KEYS = {"pnl-vector": "VECTOR_ID", "scenario-set": "SCENARIO_SET_ID", "risk-hierarchy": "NODE_ID", "portfolio": "PORTFOLIO_ID", "var-limit": "LIMIT_ID"}
PACKS = "trading,counterparty-risk,market-risk"
ADMIN = "drishti-dev-admin"


def now() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat()


def available_gb() -> float:
    for line in open("/proc/meminfo"):
        if line.startswith("MemAvailable"):
            return int(line.split()[1]) / 1048576
    return 0.0


def swap_io() -> tuple[int, int, int]:
    """Pages swapped in and out since boot (/proc/vmstat) and swap used (MB): a measured run during which pages move is not valid."""
    v = dict(line.split() for line in open("/proc/vmstat") if line.startswith(("pswpin", "pswpout")))
    m = {line.split(":")[0]: int(line.split()[1]) for line in open("/proc/meminfo") if line.startswith(("SwapTotal", "SwapFree"))}
    return int(v.get("pswpin", 0)), int(v.get("pswpout", 0)), (m.get("SwapTotal", 0) - m.get("SwapFree", 0)) // 1024


def tree_rss_kb(pid: int) -> int:
    """Resident memory of a process and its descendants (kB)."""
    total, todo = 0, [pid]
    while todo:
        p = todo.pop()
        try:
            for line in open(f"/proc/{p}/status"):
                if line.startswith("VmRSS"):
                    total += int(line.split()[1])
            for t in os.listdir(f"/proc/{p}/task"):
                kids = open(f"/proc/{p}/task/{t}/children").read().split()
                todo.extend(int(k) for k in kids)
        except OSError:
            pass
    return total


class Out:
    def __init__(self, path: str, base: dict):
        self.path, self.base = Path(path), base
        self.path.parent.mkdir(parents=True, exist_ok=True)

    def put(self, measure: str, value, unit: str = "", **kw) -> None:
        line = {"date": now()[:10], "at": now(), **self.base, "measure": measure, "unit": unit, "value": value, **kw}
        with self.path.open("a", encoding="utf-8") as f:
            f.write(json.dumps(line) + "\n")
        print(json.dumps(line), flush=True)


# -- load ---------------------------------------------------------------------------------------------------------------------------
def load(a) -> int:
    """Each kind of --data into --lake with the given engine, timed, with the peak resident memory of the loader's process tree."""
    out = Out(a.out, {"run": a.run, "engine": a.engine, "positions": a.positions})
    py = str(ROOT / "drishti-console" / ".venv" / "bin" / "python")
    env = dict(os.environ, JAVA_HOME=JAVAS[a.java], TMPDIR=str(Path(a.lake).parent / "tmp"))
    Path(env["TMPDIR"]).mkdir(parents=True, exist_ok=True)
    total = 0.0
    for kind in a.kinds.split(","):
        cmd = [py if Path(py).exists() else sys.executable, str(ROOT / "tools" / "drishti.py"), "lake", "build", "--from", str(Path(a.data) / kind),
               "--pack", str(ROOT / "config" / "packs" / "market-risk"), "--connector", "risk-store", "--kind", kind, "--key", KEYS[kind],
               "--date", "BUSINESS_DATE", "--lake", a.lake, "--engine", a.engine, "-q"]
        for s in a.loader_set or []:
            cmd += ["--loader-set", s]
        t0, peak = time.time(), 0
        p = subprocess.Popen(cmd, cwd=ROOT, env=env)
        while p.poll() is None:
            peak = max(peak, tree_rss_kb(p.pid))
            if available_gb() < a.min_free_gb:
                p.kill()
                out.put(f"load_{kind}_aborted", f"under {a.min_free_gb} GB available")
                return 3
            time.sleep(0.2)
        secs = time.time() - t0
        total += secs
        out.put(f"load_{kind}_s", round(secs, 2), "s", exit=p.returncode)
        out.put(f"load_{kind}_peak_rss_mb", round(peak / 1024), "MB")
        if p.returncode:
            return p.returncode
    out.put("load_total_s", round(total, 2), "s")
    return 0


# -- servers ------------------------------------------------------------------------------------------------------------------------
def b64(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode()


def token(secret: str, ttl: int = 6 * 3600) -> str:
    head = b64(json.dumps({"alg": "HS256", "typ": "JWT"}, separators=(",", ":")).encode())
    claims = b64(json.dumps({"sub": ADMIN, "roles": ["admin"], "exp": int(time.time()) + ttl}, separators=(",", ":")).encode())
    return f"{head}.{claims}.{b64(hmac.new(secret.encode(), f'{head}.{claims}'.encode(), hashlib.sha256).digest())}"


def http(url: str, tok: str, timeout: float = 600) -> tuple[int, str, float]:
    req = urllib.request.Request(url, headers={"Authorization": "Bearer " + tok})
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, r.read().decode("utf-8", "replace"), time.time() - t0
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace"), time.time() - t0
    except OSError as e:
        return 0, str(e), time.time() - t0


def gc_flags(gc: str, java: str) -> list[str]:
    if gc == "g1":
        return ["-XX:+UseG1GC"]
    return ["-XX:+UseZGC", "-XX:+ZGenerational"] if java == "21" else ["-XX:+UseZGC"]


class Bench:
    def __init__(self, a):
        self.a = a
        self.work = Path(a.work).resolve()
        self.work.mkdir(parents=True, exist_ok=True)
        for d in ("jfr", "histo", "nmt", "logs", "users"):
            (self.work / d).mkdir(exist_ok=True)
        self.java = JAVAS[a.java]
        self.host = hostaddr.primary_ipv4()
        jars = sorted(ROOT.glob("drishti-server/target/drishti-server-*-exec.jar"), key=os.path.getmtime, reverse=True)
        if not jars:
            raise SystemExit("needs the built server jar")
        self.jar = str(jars[0])
        self.secret, self.net_key = secrets.token_urlsafe(48), secrets.token_urlsafe(48)
        self.tok = token(self.secret)
        (self.work / "token.txt").write_text(self.tok, encoding="utf-8")
        self.names = ["single"] if a.mode == "single" else ["a", "b", "c"]
        self.port = {n: a.base_port + i for i, n in enumerate(self.names)}
        assert not FORBIDDEN & set(self.port.values()), "a forbidden port"
        self.procs: dict[str, subprocess.Popen] = {}
        self.out = Out(a.out, {"run": a.run, "mode": a.mode, "java": a.java, "gc": a.gc, "positions": a.positions, "heap": a.heap})

    def url(self, n: str) -> str:
        return f"http://{self.host if self.a.mode == 'cluster' else '127.0.0.1'}:{self.port[n]}"

    def start(self, n: str, seed: str | None) -> None:
        home = self.work / "nodes" / n
        for d in ("data", "connectors", "net/peers"):
            (home / d).mkdir(parents=True, exist_ok=True)
        import net_soak
        for cname, text in net_soak.connector_files(Path(self.a.lake), PACKS).items():
            (home / "connectors" / f"{cname}.yaml").write_text(text, encoding="utf-8")
        env = dict(os.environ, JAVA_HOME=self.java, DRISHTI_TOKEN_SECRET=self.secret, DRISHTI_NET_SECRET=self.net_key,
                   DRISHTI_SECURITY_ENABLED="true", DRISHTI_DEMO_ENABLED="false", DRISHTI_OFFLINE="true", DRISHTI_DATA_DIR=str(home / "data"),
                   DRISHTI_DELTA_ROOT=str(Path(self.a.lake).resolve()), DRISHTI_PACKS=PACKS, DRISHTI_PACKS_DIR=str(ROOT / "config" / "packs"),
                   DRISHTI_CONNECTORS_DIR=str(home / "connectors"), DRISHTI_STORE_ROOT=str(home / "data" / "fstore"), MALLOC_ARENA_MAX="2")
        jfr = self.work / "jfr" / f"{n}-run.jfr"
        flags = [f"-Xmx{self.a.heap}", *gc_flags(self.a.gc, self.a.java), "-XX:MaxDirectMemorySize=256m", "-XX:TrimNativeHeapInterval=60000",
                 "-Djdk.nio.maxCachedBufferSize=262144", "-XX:NativeMemoryTracking=summary",
                 f"-XX:StartFlightRecording=name=bench,settings=profile,disk=true,maxage=6h,dumponexit=true,filename={jfr}",
                 f"-Xlog:gc*:file={self.work / 'logs' / ('gc-' + n + '.log')}:time,uptime:filecount=5,filesize=50m",
                 "-XX:+HeapDumpOnOutOfMemoryError", f"-XX:HeapDumpPath={self.work / 'logs'}"]
        args = [*flags, "-jar", self.jar, f"--server.port={self.port[n]}", "--drishti.sources.plugins.demo.enabled=false",
                "--drishti.security.registered-users-only=false", f"--drishti.logging.file={self.work / 'logs' / (n + '.log')}",
                "--logging.threshold.console=OFF", "--drishti.var.currencies=USD", "--drishti.var.official.enabled=false"]
        if self.a.mode == "cluster":
            (home / "net" / "node.yaml").write_text(f"enabled: true\nnode-id: bench-{n}\nadvertise-url: http://{self.host}:{self.port[n]}\n"
                                                    "secret: ${DRISHTI_NET_SECRET}\n", encoding="utf-8")
            (home / "net" / "gossip.yaml").write_text("interval: 1s\nsuspect-after: 3s\ndead-after: 10s\n", encoding="utf-8")
            if seed:
                (home / "net" / "peers" / "bench-a.yaml").write_text(f"url: {seed}\n", encoding="utf-8")
            args += [f"--drishti.net.dir={home / 'net'}", "--drishti.net.poll=1s", "--drishti.var.sharding.enabled=true",
                     f"--drishti.var.sharding.target-shard-mb={self.a.shard_mb}", "--drishti.var.sharding.replicas=2",
                     "--drishti.var.sharding.stable-seconds=15", "--drishti.var.sharding.grace-seconds=3",
                     # the lake holds no scenario-pv table: with it in kinds the date is never cut (VarLoader.keyCounts compares the
                     # version "risk-store=none" with "none"; reported in BENCHMARK_2_9_7.md, a product fix for after the baseline)
                     "--drishti.var.sharding.kinds=pnl-vector"]
        args =[*(self.a.jvm_arg or []), *args, *(self.a.server_arg or [])]
        log =open(self.work / "logs" / f"{n}.out", "ab")
        self.procs[n] = subprocess.Popen(java_cmd(str(Path(self.java) / "bin" / "java"), args, tmp=home / "data" / "tmp" / "server"), cwd=home,
                                         env=env, stdout=log, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL, start_new_session=True)

    def wait_health(self, n: str, seconds: float = 600) -> float:
        t0 = time.time()
        while time.time() - t0 < seconds:
            if self.procs[n].poll() is not None:
                raise SystemExit(f"server {n} exited: see {self.work / 'logs'}")
            st, _, _ = http(f"http://127.0.0.1:{self.port[n]}/actuator/health", self.tok, 5)
            if st == 200:
                return time.time() - t0
            time.sleep(1)
        raise SystemExit(f"server {n} did not answer within {seconds} s")

    def jcmd(self, n: str, *what: str) -> str:
        r = subprocess.run([str(Path(self.java) / "bin" / "jcmd"), str(self.procs[n].pid), *what], capture_output=True, text=True, timeout=600)
        return r.stdout + r.stderr

    def snapshot(self, label: str) -> None:
        """Per server: the class histogram (a full GC first), the heap after it, the NMT summary and the resident memory."""
        for n in self.names:
            histo = self.jcmd(n, "GC.class_histogram")
            (self.work / "histo" / f"{n}-{label}.txt").write_text(histo, encoding="utf-8")
            total = re.search(r"Total\s+(\d+)\s+(\d+)", histo)
            heap = self.jcmd(n, "GC.heap_info")
            nmt = self.jcmd(n, "VM.native_memory", "summary")
            (self.work / "nmt" / f"{n}-{label}.txt").write_text(heap + "\n" + nmt, encoding="utf-8")
            committed = re.search(r"Total: reserved=\d+KB, committed=(\d+)KB", nmt)
            top = []
            for line in histo.splitlines()[3:13]:
                parts = line.split()
                if len(parts) >= 4 and parts[0].rstrip(":").isdigit():
                    top.append({"class": parts[3], "instances": int(parts[1]), "bytes": int(parts[2])})
            self.out.put(f"heap_after_gc_mb_{label}", round(int(total.group(2)) / 1e6, 1) if total else None, "MB", node=n, top=top)
            self.out.put(f"nmt_committed_mb_{label}", round(int(committed.group(1)) / 1024, 1) if committed else None, "MB", node=n)
            self.out.put(f"rss_mb_{label}", round(tree_rss_kb(self.procs[n].pid) / 1024), "MB", node=n)

    def log_bytes(self) -> int:
        return sum(p.stat().st_size for p in (self.work / "logs").glob("*.log*") if not p.name.startswith("gc-"))

    def status(self, n: str) -> dict:
        st, body, _ = http(f"{self.url(n)}/api/v1/var/status", self.tok, 60)
        try:
            return json.loads(body) if st == 200 else {"http": st}
        except ValueError:
            return {"http": st}

    def first_open(self, date: str) -> None:
        """The first open of a date (the bank's VaR on it), timed end to end, under its own JFR recording (the stages), on the first server."""
        n = self.names[0]
        jfr = self.work / "jfr" / f"{n}-open-{date}.jfr"
        self.jcmd(n, "JFR.start", f"name=open-{date}", "settings=profile", f"filename={jfr}")
        st, body, secs = http(f"{self.url(n)}/api/v1/var/scope?scope=BANK&asOf={date}", self.tok, 1800)
        self.jcmd(n, "JFR.stop", f"name=open-{date}")
        self.out.put("first_open_s", round(secs, 2), "s", businessDate=date, http=st, error=None if st == 200 else body[:300])
        if st == 200:
            prov = json.loads(body).get("provenance", {})
            self.out.put("first_open_answer", json.loads(body).get("var"), "USD", businessDate=date, positions=prov.get("positions"),
                         trades=prov.get("trades"), tier=prov.get("tier"))
        for d in self.status(n).get("loaded", []) if isinstance(self.status(n).get("loaded"), list) else []:
            if d.get("businessDate") == date and "currency" not in d:
                self.out.put("server_load_ms", d.get("loadMs"), "ms", businessDate=date, positions=d.get("positions"), trades=d.get("trades"),
                             nodes=d.get("nodes"), aggregateBytes=d.get("aggregateBytes"), vectorBytes=d.get("vectorBytes"))

    def users(self, users: int, seconds: int, label: str) -> dict:
        servers = ",".join(self.url(n) for n in self.names)
        py = str(ROOT / "drishti-console" / ".venv" / "bin" / "python")
        cmd = [py, str(ROOT / "tools" / "bench" / "users.py"), "--server", servers, "--token-file", str(self.work / "token.txt"),
               "--users", str(users), "--seconds", str(seconds), "--ids", str(self.a.trade_ids), "--counterparties", str(self.a.counterparties),
               "--dates", self.a.trade_dates, "--var-share", str(self.a.var_share), "--var-dates", self.a.dates]
        swap0 = swap_io()
        p = subprocess.Popen(cmd, stdout=subprocess.PIPE, text=True, cwd=ROOT)
        low = False
        while p.poll() is None:
            if available_gb() < self.a.min_free_gb:
                p.kill()
                low = True
                break
            time.sleep(1)
        text = p.stdout.read() if p.stdout else ""
        if low:
            self.out.put(f"users_{users}_aborted", f"under {self.a.min_free_gb} GB available", users=users)
            return {}
        res = json.loads(text.strip().splitlines()[-1])
        (self.work / "users" / f"{label}-{users}.json").write_text(json.dumps(res, indent=1), encoding="utf-8")
        kinds = {k: v for k, v in res.items() if isinstance(v, dict) and "p50" in v}
        failed = sum(v["failed"] for v in kinds.values())
        swap1 = swap_io()
        if label != "warmup":
            self.out.put("users", res["perSecond"], "req/s", users=users, seconds=res["seconds"], requests=res["requests"], errors=failed,
                         by_kind=kinds, swap_in_pages=swap1[0] - swap0[0], swap_out_pages=swap1[1] - swap0[1], swap_used_mb=swap1[2],
                         available_gb=round(available_gb(), 1))
        return res

    def stop(self) -> None:
        for n, p in self.procs.items():
            if p.poll() is None:
                try:
                    self.jcmd(n, "JFR.dump", "name=bench", f"filename={self.work / 'jfr' / (n + '-run.jfr')}")
                except Exception:   # noqa: BLE001 - the stop goes on
                    pass
                p.send_signal(signal.SIGTERM)
        for p in self.procs.values():
            try:
                p.wait(90)
            except subprocess.TimeoutExpired:
                p.kill()

    def run(self) -> int:
        a = self.a
        if available_gb() < a.min_free_gb + 8:
            raise SystemExit(f"only {available_gb():.1f} GB available: not starting")
        try:
            t0 = time.time()
            for i, n in enumerate(self.names):
                self.start(n, None if i == 0 else self.url("a"))
            for n in self.names:
                self.out.put("start_s", round(self.wait_health(n), 1), "s", node=n)
            if a.mode == "cluster":
                time.sleep(a.settle)                         # the members see each other and the view stays the same (stable-seconds)
            self.out.put("up_s", round(time.time() - t0, 1), "s")
            self.snapshot("start")
            dates = a.dates.split(",")
            self.first_open(dates[-1])
            self.snapshot("one_date")
            self.first_open(dates[0])
            self.snapshot("two_dates")
            if a.mode == "cluster":
                st, body, _ = http(f"{self.url('a')}/api/v1/var/shards?asOf={dates[-1]}", self.tok, 60)
                (self.work / "shards.json").write_text(body, encoding="utf-8")
            if a.hold:
                print(f"holding {a.hold} s: {', '.join(self.url(n) for n in self.names)}; token in {self.work / 'token.txt'}", flush=True)
                time.sleep(a.hold)
                return 0
            logs0, t_idle = self.log_bytes(), time.time()
            time.sleep(a.idle)
            self.out.put("log_bytes_per_hour_idle", round((self.log_bytes() - logs0) / (time.time() - t_idle) * 3600), "bytes")
            logs1, t_load = self.log_bytes(), time.time()
            self.users(a.warm_users, a.warmup, "warmup")
            self.snapshot("warm")
            for u in [int(x) for x in a.users.split(",")]:
                if available_gb() < a.min_free_gb:
                    self.out.put("users_skipped", f"under {a.min_free_gb} GB available", users=u)
                    break
                self.users(u, a.seconds, "load")
            self.out.put("log_bytes_per_hour_load", round((self.log_bytes() - logs1) / (time.time() - t_load) * 3600), "bytes")
            self.snapshot("end")
            for n in self.names:
                st = self.status(n)
                (self.work / f"status-{n}.json").write_text(json.dumps(st, indent=1), encoding="utf-8")
            self.out.put("log_bytes_total", self.log_bytes(), "bytes", uptime_s=round(time.time() - t0))
            return 0
        finally:
            self.stop()


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    sub = ap.add_subparsers(dest="cmd", required=True)
    lp = sub.add_parser("load")
    lp.add_argument("--data", required=True)
    lp.add_argument("--lake", required=True)
    lp.add_argument("--engine", choices=("drishti", "java", "deltalake"), required=True)
    lp.add_argument("--kinds", default="scenario-set,risk-hierarchy,var-limit,portfolio,pnl-vector")
    lp.add_argument("--loader-set", action="append")
    rp = sub.add_parser("run")
    rp.add_argument("--mode", choices=("single", "cluster"), required=True)
    rp.add_argument("--gc", choices=("g1", "zgc"), default="g1")
    rp.add_argument("--heap", default="8g")
    rp.add_argument("--lake", required=True)
    rp.add_argument("--work", required=True)
    rp.add_argument("--base-port", type=int, default=19480)
    rp.add_argument("--dates", default="2026-09-29,2026-09-30")
    rp.add_argument("--trade-dates", default="2026-09-29,2026-09-30")
    rp.add_argument("--trade-ids", type=int, default=20000)
    rp.add_argument("--counterparties", type=int, default=2000)
    rp.add_argument("--users", default="50,100,200")
    rp.add_argument("--seconds", type=int, default=60)
    rp.add_argument("--warmup", type=int, default=45)
    rp.add_argument("--warm-users", type=int, default=50)
    rp.add_argument("--var-share", type=float, default=0.5)
    rp.add_argument("--idle", type=int, default=120, help="seconds at rest for the log bytes")
    rp.add_argument("--settle", type=int, default=25)
    rp.add_argument("--shard-mb", type=int, default=64)
    rp.add_argument("--jvm-arg", action="append", help="a JVM option for every server (before -jar; repeatable)")
    rp.add_argument("--server-arg", action="append", help="a server setting for every server (--drishti...; repeatable)")
    rp.add_argument("--hold", type=int, default=0, help="debug: after the two dates are open, keep the servers up this many seconds and stop")
    for p in (lp, rp):
        p.add_argument("--java", choices=tuple(JAVAS), default="21")
        p.add_argument("--out", required=True)
        p.add_argument("--run", default="r1")
        p.add_argument("--positions", type=int, default=200_000)
        p.add_argument("--min-free-gb", type=float, default=8)
    a = ap.parse_args(argv)
    if a.cmd == "load":
        return load(a)
    return Bench(a).run()


if __name__ == "__main__":
    sys.exit(main())
