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

"""The local Drishti*net cluster of drishti-stack/ (README.md): start, stop and status, driven by stack.yaml.

    start.sh [node ...]     start what is not running (every node, then the console; or only the nodes named, or "console")
    stop.sh [node ...]      stop by the process ids in run/pids.txt (never by name), the console first
    status.sh               the processes, the ports, the members each node sees, and the console's servers

Secrets live in secrets.env (git-ignored, mode 600): DRISHTI_TOKEN_SECRET, DRISHTI_SESSION_SECRET and DRISHTI_NET_SECRET, each made with
``openssl rand -hex 32`` on the first start when missing and kept. They reach the processes through their environment only; nothing here
prints them. The servers run a copy of the newest built jar, run/server.jar, so a rebuild never replaces the jar under a running server.
Logs and process ids are in run/ (git-ignored); node data and the synthetic lakes in data/stack/ (data/ is git-ignored)."""
from __future__ import annotations

import base64
import hashlib
import hmac
import json
import os
import shutil
import signal
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
RUN = HERE / "run"
LOGS = RUN / "logs"
PIDS = RUN / "pids.txt"
JAR = RUN / "server.jar"
SECRETS = HERE / "secrets.env"
SECRET_NAMES = ("DRISHTI_TOKEN_SECRET", "DRISHTI_SESSION_SECRET", "DRISHTI_NET_SECRET")
STACK_DATA = ROOT / "data" / "stack"
CONSOLE = "console"
HEADER = """# Project Drishti · Any data. Any domain. One grammar.
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
"""

sys.path.insert(0, str(ROOT / "tools"))
from javacmd import java_cmd, server_tmp  # noqa: E402
import hostaddr  # noqa: E402


def say(text: str) -> None:
    print(text, flush=True)


# -- configuration -----------------------------------------------------------------------------------------------------------------
def load_stack() -> dict:
    try:
        import yaml
    except ImportError:
        sys.exit("needs PyYAML: run with the console's Python (drishti-console/.venv/bin/python), as start.sh does")
    return yaml.safe_load((HERE / "stack.yaml").read_text(encoding="utf-8"))


def python() -> str:
    """The console's Python (it has PyYAML, deltalake and the console's packages), else this one."""
    py = ROOT / "drishti-console" / ".venv" / "bin" / "python"
    return str(py) if py.exists() else sys.executable


def java(stack: dict) -> str:
    home = os.environ.get("JAVA_HOME")
    return str(Path(home) / "bin" / "java") if home else stack.get("java", "java")


def read_env(path: Path) -> dict:
    out = {}
    if path.exists():
        for line in path.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                out[k.strip()] = v.strip()
    return out


def secrets() -> dict:
    """secrets.env, with any missing secret made (openssl rand -hex 32) and kept; mode 600. The values are never printed."""
    have = read_env(SECRETS)
    missing = [n for n in SECRET_NAMES if not have.get(n)]
    if missing:
        for n in missing:
            try:
                have[n] = subprocess.run(["openssl", "rand", "-hex", "32"], capture_output=True, text=True, check=True).stdout.strip()
            except (OSError, subprocess.CalledProcessError):
                import secrets as rnd
                have[n] = rnd.token_hex(32)
        old = os.umask(0o077)
        try:
            lines = ["# The local cluster's secrets (drishti-stack/README.md, 'Secrets'). Git-ignored: never commit this file.", ""]
            lines += [f"{k}={v}" for k, v in have.items()]
            SECRETS.write_text("\n".join(lines) + "\n", encoding="utf-8")
        finally:
            os.umask(old)
        say(f"made {', '.join(missing)} in {SECRETS.relative_to(ROOT)}")
    SECRETS.chmod(0o600)
    return have


# -- the jar -----------------------------------------------------------------------------------------------------------------------
def server_jar() -> Path:
    """run/server.jar: a copy of the newest drishti-server/target/drishti-server-*-exec.jar, refreshed when a newer build exists."""
    built = sorted((ROOT / "drishti-server" / "target").glob("drishti-server-*-exec.jar"), key=lambda p: p.stat().st_mtime, reverse=True)
    if built and (not JAR.exists() or built[0].stat().st_mtime > JAR.stat().st_mtime):
        RUN.mkdir(parents=True, exist_ok=True)
        part = JAR.with_name("server-copying.jar")
        shutil.copy2(built[0], part)
        os.replace(part, JAR)                    # a server still running keeps the jar it opened
        say(f"server jar: {built[0].name} copied to {JAR.relative_to(ROOT)}")
    if not JAR.exists():
        sys.exit("no server jar: build it first (./mvnw -o -q package -DskipTests -pl drishti-server -am), then run start.sh again")
    return JAR


# -- process ids -------------------------------------------------------------------------------------------------------------------
def read_pids() -> dict:
    out = {}
    if PIDS.exists():
        for line in PIDS.read_text(encoding="utf-8").splitlines():
            parts = line.split()
            if len(parts) == 2 and parts[1].isdigit():
                out[parts[0]] = int(parts[1])
    return out


def write_pids(pids: dict) -> None:
    RUN.mkdir(parents=True, exist_ok=True)
    PIDS.write_text("".join(f"{k} {v}\n" for k, v in pids.items()), encoding="utf-8")


def ours(pid: int) -> bool:
    """The process is alive and is one this stack started (its command line names run/server.jar or the console), so a reused id is never
    stopped."""
    try:
        cmd = Path(f"/proc/{pid}/cmdline").read_bytes().replace(b"\0", b" ").decode("utf-8", "replace")
    except OSError:
        return False
    return str(JAR) in cmd or "run_drishti_web.py" in cmd


def listening(port: int) -> bool:
    with socket.socket() as s:
        s.settimeout(0.3)
        return s.connect_ex(("127.0.0.1", int(port))) == 0


def wait_up(url: str, seconds: float, proc: subprocess.Popen | None = None) -> bool:
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if proc is not None and proc.poll() is not None:
            return False
        try:
            with urllib.request.urlopen(url, timeout=3):
                return True
        except (OSError, urllib.error.URLError):
            time.sleep(2)
    return False


def stack_host(stack: dict) -> str:
    """The address every node advertises and the console is given: stack.yaml's host (for a machine with several interfaces), else this
    machine's primary IPv4 address (the default route's interface; never a loopback or a container bridge). Members never advertise
    localhost, 127.x.x.x or 0.0.0.0 (docs/admin/NET.md)."""
    host = str(stack.get("host") or "").strip() or hostaddr.primary_ipv4()
    if not host:
        sys.exit("this machine has no address but loopback: set host: in stack.yaml to the address the nodes advertise")
    if hostaddr.loopback_or_wildcard(host):
        sys.exit(f"stack.yaml host: {host} is a loopback or wildcard address; members advertise a real one"
                 + (f" (this machine's is {hostaddr.primary_ipv4()})" if hostaddr.primary_ipv4() else ""))
    return host


# -- nodes -------------------------------------------------------------------------------------------------------------------------
def node_net(stack: dict, node: dict) -> Path:
    """nodes/<node>/net: made from stack.yaml the first time (node.yaml, gossip.yaml, peers/<seed>.yaml); kept as it is after that."""
    net = HERE / "nodes" / node["name"] / "net"
    (net / "peers").mkdir(parents=True, exist_ok=True)
    if not (net / "node.yaml").exists():
        (net / "node.yaml").write_text(HEADER + f"# {node['name']} of the local cluster (drishti-stack/stack.yaml). The key comes from the environment only.\n"
                                       f"enabled: true\nnode-id: {node['name']}\n"
                                       f"advertise-url: http://${{DRISHTI_STACK_HOST}}:{node['port']}     # this machine's real address (start.sh sets it)\n"
                                       "secret: ${DRISHTI_NET_SECRET}\n", encoding="utf-8")
    if not (net / "gossip.yaml").exists():
        g = stack.get("gossip") or {}
        (net / "gossip.yaml").write_text(HEADER + "# Fast failure detection for one machine (drishti-stack/stack.yaml, gossip).\n"
                                         + "".join(f"{k}: {v}\n" for k, v in g.items()), encoding="utf-8")
    seed = next((n for n in stack["nodes"] if n["name"] == stack.get("seed")), None)
    if seed and seed["name"] != node["name"] and not any((net / "peers").glob("*.yaml")):
        (net / "peers" / f"{seed['name']}.yaml").write_text(HEADER + f"# The seed: where {node['name']} knocks when it joins.\n"
                                                           f"url: http://${{DRISHTI_STACK_HOST}}:{seed['port']}     # the seed's real address (start.sh sets it)\n", encoding="utf-8")
    return net


def lake_dir(name: str) -> Path:
    return STACK_DATA / "lakes" / name


def build_lake(stack: dict, name: str) -> None:
    """data/stack/lakes/<name>: synthetic trades of stack.yaml's lakes entry (tools/bench/trades.py), when the lake has no tables yet."""
    lake = lake_dir(name)
    if (lake / "trading").is_dir():
        return
    spec = (stack.get("lakes") or {}).get(name)
    if not spec:
        sys.exit(f"lake {name} is not in stack.yaml (lakes) and {lake} has no trades")
    tmp = STACK_DATA / "tmp" / f"lake-{name}"
    tmp.mkdir(parents=True, exist_ok=True)
    n = str(spec.get("per-day", 1500))
    say(f"building lake {name}: {spec['systems']}, {n} trades a day for {spec.get('days', 2)} days ({lake})")
    subprocess.run([python(), str(ROOT / "tools" / "bench" / "trades.py"), "--lake", str(lake), "--tmp", str(tmp), "--days", str(spec.get("days", 2)),
                    "--min", n, "--max", n, "--systems", spec["systems"], "--counterparties", str(spec.get("counterparties", 300))],
                   check=True, env={**os.environ, "TMPDIR": str(tmp)})
    shutil.rmtree(tmp, ignore_errors=True)


# Native memory of a long-running server (SOAK-05, PERFORMANCE.md *Resident memory*): direct buffers capped, freed native memory given back,
# the JDK's per-thread temporary direct buffers cached only up to 256 KB (a larger one is freed after its read), and native memory tracked
# (jcmd <pid> VM.native_memory summary)
SERVER_MEMORY_FLAGS = ["-XX:MaxDirectMemorySize=256m", "-XX:TrimNativeHeapInterval=60000", "-Djdk.nio.maxCachedBufferSize=262144",
                       "-XX:NativeMemoryTracking=summary"]
DUMPS = RUN / "dumps"


def diagnostic_flags(name: str) -> list[str]:
    """A heap dump on OutOfMemoryError (run/dumps/<node>) and a rotated GC log (run/logs/gc-<node>-<pid>.log, 5 x 20 MB): what a
    multi-day soak needs to explain a failure (OPERATIONS.md, Diagnostics of a long run). The folders are made first: the JVM does
    not start when the GC log's folder is missing."""
    dumps = DUMPS / name
    dumps.mkdir(parents=True, exist_ok=True)
    LOGS.mkdir(parents=True, exist_ok=True)
    return ["-XX:+HeapDumpOnOutOfMemoryError", f"-XX:HeapDumpPath={dumps}",
            f"-Xlog:gc*:file={LOGS / ('gc-' + name + '-%p.log')}:time,uptime:filecount=5,filesize=20m"]


def node_env(stack: dict, node: dict, keys: dict) -> tuple[dict, Path]:
    """The node's environment and working folder: the repository for home: repo, else data/stack/nodes/<node> with its own data,
    connectors and lake."""
    env = {**os.environ, **{k: str(v) for k, v in (stack.get("settings") or {}).items()}, **{k: str(v) for k, v in (node.get("env") or {}).items()}}
    env.update(keys)
    env["DRISHTI_NET_DIR"] = str(node_net(stack, node))
    env["DRISHTI_STACK_HOST"] = stack_host(stack)
    env["DRISHTI_PACKS"] = node["packs"]
    env.setdefault("MALLOC_ARENA_MAX", "2")                 # glibc: two malloc arenas, not one per busy thread (PERFORMANCE.md, Resident memory)
    env.setdefault("DRISHTI_STORE_ROOT", str(ROOT / "data" / "fstore"))
    env.setdefault("DRISHTI_UI_KIT_DIR", str(ROOT / "config" / "ui-kit"))   # the platform UI kit for every node, whatever its working folder   # one content store for every node of this machine's cluster (CONTENT_STORE.md)
    if node.get("home") == "repo":
        return env, ROOT
    home = STACK_DATA / "nodes" / node["name"]
    (home / "data").mkdir(parents=True, exist_ok=True)
    (home / "connectors").mkdir(parents=True, exist_ok=True)
    env.update(DRISHTI_DATA_DIR=str(home / "data"), DRISHTI_CONNECTORS_DIR=str(home / "connectors"), DRISHTI_PACKS_DIR=str(ROOT / "config" / "packs"))
    if node.get("lake"):
        env["DRISHTI_DELTA_ROOT"] = str(lake_dir(node["lake"]))
    return env, home


def start_node(stack: dict, node: dict, keys: dict, jar: Path, pids: dict) -> bool:
    name, port = node["name"], node["port"]
    if name in pids and ours(pids[name]):
        say(f"{name}: already running (pid {pids[name]}, port {port})")
        return True
    if listening(port):
        say(f"{name}: port {port} is in use by another process: not started (stop that process, or change the port in stack.yaml)")
        return False
    if node.get("lake"):
        build_lake(stack, node["lake"])
    env, cwd = node_env(stack, node, keys)
    args = [f"-Xmx{node.get('heap', '1536m')}", *SERVER_MEMORY_FLAGS, *diagnostic_flags(name), *node.get("jvm", []), "-jar", str(jar), f"--server.port={port}"]
    args += [f"--drishti.sources.connectors.{c}.enabled=false" for c in node.get("off", [])]
    # 2.9.7 (RESOURCES_AND_SCALE.md 6.4): the server writes run/logs/<node>.log itself, rotated (100 MB a file, 1 GB in all); standard
    # output (the JVM's own lines, a crash) goes to run/logs/<node>.out, which stays small
    args += [f"--drishti.logging.file={LOGS / (name + '.log')}", "--logging.threshold.console=OFF"]
    args += node.get("args", [])
    cmd = java_cmd(java(stack), args, tmp=server_tmp(cwd, env), cwd=cwd, env=env)
    LOGS.mkdir(parents=True, exist_ok=True)
    with open(LOGS / f"{name}.out", "ab") as log:
        proc = subprocess.Popen(cmd, cwd=cwd, env=env, stdout=log, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL, start_new_session=True)
    pids[name] = proc.pid
    write_pids(pids)
    say(f"{name}: started (pid {proc.pid}, port {port}); waiting for it to answer")
    if not wait_up(f"http://127.0.0.1:{port}/actuator/health", 300, proc):
        say(f"{name}: did not come up: see {(LOGS / (name + '.log')).relative_to(ROOT)}")
        return False
    say(f"{name}: up on http://{stack_host(stack)}:{port}")
    return True


def start_console(stack: dict, keys: dict, pids: dict) -> bool:
    c = stack["console"]
    port = c["port"]
    if CONSOLE in pids and ours(pids[CONSOLE]):
        say(f"console: already running (pid {pids[CONSOLE]}, port {port})")
        return True
    if listening(port):
        say(f"console: port {port} is in use by another process: not started")
        return False
    by_name = {n["name"]: n for n in stack["nodes"]}
    env = {**os.environ, **{k: str(v) for k, v in (stack.get("settings") or {}).items()}, **{k: str(v) for k, v in (c.get("settings") or {}).items()}}
    env.update({k: keys[k] for k in ("DRISHTI_TOKEN_SECRET", "DRISHTI_SESSION_SECRET")})
    host = stack_host(stack)                               # by real address too, so its list matches what the cluster advertises
    env["DRISHTI_BACKEND_URLS"] = ",".join(f"http://{host}:{by_name[s]['port']}" for s in c.get("servers", []))
    env["DRISHTI_CONSOLE_PORT"] = str(port)
    LOGS.mkdir(parents=True, exist_ok=True)
    with open(LOGS / "console.log", "ab") as log:
        proc = subprocess.Popen([python(), str(ROOT / "drishti-console" / "run_drishti_web.py")], cwd=ROOT, env=env, stdout=log,
                                stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL, start_new_session=True)
    pids[CONSOLE] = proc.pid
    write_pids(pids)
    if not wait_up(f"http://127.0.0.1:{port}/healthz", 90, proc):
        say(f"console: did not come up: see {(LOGS / 'console.log').relative_to(ROOT)}")
        return False
    say(f"console: up on http://{host}:{port} (given {env['DRISHTI_BACKEND_URLS']})")
    return True


def start(names: list[str]) -> int:
    stack = load_stack()
    keys = {k: v for k, v in secrets().items() if k in SECRET_NAMES}
    jar = server_jar()
    pids = {k: v for k, v in read_pids().items() if ours(v)}
    write_pids(pids)
    ok = True
    for node in stack["nodes"]:
        if not names or node["name"] in names:
            ok = start_node(stack, node, keys, jar, pids) and ok
    if not names or CONSOLE in names:
        ok = start_console(stack, keys, pids) and ok
    say("" if ok else "some did not start: see above")
    return 0 if ok else 1


def stop(names: list[str]) -> int:
    pids = read_pids()
    order = ([CONSOLE] if CONSOLE in pids else []) + [n for n in pids if n != CONSOLE]
    for name in order:
        if names and name not in names:
            continue
        pid = pids[name]
        if not ours(pid):
            say(f"{name}: not running (pid {pid} is gone or is not this stack's)")
            pids.pop(name)
            continue
        os.kill(pid, signal.SIGTERM)
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline and ours(pid):
            time.sleep(0.5)
        if ours(pid):
            os.kill(pid, signal.SIGKILL)
            say(f"{name}: stopped after 60 s with SIGKILL (pid {pid})")
        else:
            say(f"{name}: stopped (pid {pid})")
        pids.pop(name)
    write_pids(pids)
    return 0


# -- status ------------------------------------------------------------------------------------------------------------------------
def b64(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode()


def token(user: str, secret: str, ttl: int = 300) -> str:
    """An HS256 token as the console mints them (drishti-console/core/auth.py mint_token)."""
    head = b64(json.dumps({"alg": "HS256", "typ": "JWT"}, separators=(",", ":")).encode())
    claims = b64(json.dumps({"sub": user, "roles": ["admin"], "exp": int(time.time()) + ttl}, separators=(",", ":")).encode())
    return f"{head}.{claims}.{b64(hmac.new(secret.encode(), f'{head}.{claims}'.encode(), hashlib.sha256).digest())}"


def get_json(url: str, headers: dict | None = None):
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers=headers or {}), timeout=5) as r:
            return json.load(r)
    except (OSError, urllib.error.URLError, ValueError):
        return None


def status(_names: list[str]) -> int:
    stack = load_stack()
    keys = read_env(SECRETS)
    pids = read_pids()
    auth = {"Authorization": "Bearer " + token(stack.get("admin", "drishti-dev-admin"), keys["DRISHTI_TOKEN_SECRET"])} if keys.get("DRISHTI_TOKEN_SECRET") else {}
    say(f"{'process':<14}{'pid':>9}  {'port':<6} state")
    for node in stack["nodes"] + [{"name": CONSOLE, "port": stack["console"]["port"]}]:
        name, pid = node["name"], pids.get(node["name"])
        alive = pid is not None and ours(pid)
        path = "/healthz" if name == CONSOLE else "/actuator/health"
        up = get_json(f"http://127.0.0.1:{node['port']}{path}") is not None
        say(f"{name:<14}{pid or '-':>9}  {node['port']:<6} {'running' if alive else 'not running'}{', answering' if up else ''}"
            + ("" if alive or not listening(node["port"]) else " (the port is used by another process)"))
    for node in stack["nodes"]:
        m = get_json(f"http://127.0.0.1:{node['port']}/api/v1/net/members", auth)
        if m and m.get("members"):
            states = ", ".join(f"{x['nodeId']} {x['state']} at {x.get('url') or '?'}" for x in m["members"])
            say(f"members as {node['name']} sees them: {states}")
            break
    else:
        say("members: no node answered /api/v1/net/members")
    saved = ROOT / "data" / "console" / "net" / "servers.json"
    try:
        entries = json.loads(saved.read_text(encoding="utf-8")).get("servers", [])
        say("console's servers (its saved list): " + ", ".join(f"{e.get('node_id') or '?'} {e['url']}" for e in entries))
    except (OSError, ValueError):
        say("console's servers: not saved yet")
    return 0


if __name__ == "__main__":
    if len(sys.argv) < 2 or sys.argv[1] not in ("start", "stop", "status"):
        sys.exit("usage: stack.py start|stop|status [node ...]")
    sys.exit({"start": start, "stop": stop, "status": status}[sys.argv[1]](sys.argv[2:]))
