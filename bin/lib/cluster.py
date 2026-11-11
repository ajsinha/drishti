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

"""A Drishti*net cluster on one machine, driven by a cluster file (deploy/stack/stack.yaml is the format and the default).

    bin/start-cluster [--cluster FILE] [--data-dir DIR] [node ...]    start what is not running (every node, then the console; or only those named, or "console")
    bin/stop-cluster  [--cluster FILE] [--data-dir DIR] [node ...]    stop by the pid files (never by name), the console first
    bin/status --cluster FILE [--data-dir DIR]                        the processes, the ports, the members each node sees, the console's servers

The processes are started and stopped by bin/start-server, bin/start-console, bin/stop-server and bin/stop-console (bin/README.md;
docs/DEPLOYMENT_AND_QA_GUIDE.md section 6). This file adds what belongs to a cluster: the cluster file, the secrets, the jar copy, the
synthetic lakes and each node's settings. The folder of the cluster file is configuration only: the file, nodes/<node>/net and secrets.env.

Secrets live in secrets.env beside the cluster file (git-ignored, mode 600): DRISHTI_TOKEN_SECRET, DRISHTI_SESSION_SECRET and
DRISHTI_NET_SECRET, each made on the first start when missing and kept. They reach the processes through their environment only; nothing
here prints them. The servers run a copy of the newest built jar, <data>/stack/server.jar, so a rebuild never replaces the jar under a
running server. Process ids and logs are where bin/ keeps them (<data>/run/server-<node>.pid, <data>/run/console.pid, <data>/logs/); node
data and the synthetic lakes in <data>/stack/.

Consoles: the cluster file's ``console:`` block is one console; a ``consoles:`` list (each {name, port, distribution}, the console block
its defaults) runs several over the same cluster, each with its own distribution (docs/architecture/DISTRIBUTIONS.md section 7): Drishti
Risk on 17480 and Drishti Finance on 17481. A named console is the process console-<name> (run/console-<name>.pid); bin/status shows each
one's distribution."""
from __future__ import annotations

import argparse
import base64
import hashlib
import hmac
import json
import os
import shutil
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BIN = ROOT / "bin"
SECRET_NAMES = ("DRISHTI_TOKEN_SECRET", "DRISHTI_SESSION_SECRET", "DRISHTI_NET_SECRET")
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
import hostaddr  # noqa: E402


STACK = ROOT / "deploy" / "stack"        # the default cluster: stack.yaml, nodes/ and the git-ignored secrets.env
OLD_STACK = ROOT / "drishti-stack"       # its folder before 2.9.11.i4, read for one release only (then this fallback goes)


class Layout:
    """Where one cluster's files are: its configuration beside the cluster file, everything it writes under the data folder."""

    def __init__(self, cluster_file: str, data_dir: str) -> None:
        old = not cluster_file and (OLD_STACK / "stack.yaml").is_file() and not (STACK / "stack.yaml").is_file()
        if old:
            notice(f"the cluster file is read from {OLD_STACK / 'stack.yaml'}: the default stack moved to {STACK} (2.9.11.i4); "
                   "move your files there (this fallback is for one release)")
        self.file = Path(cluster_file or (OLD_STACK if old else STACK) / "stack.yaml").resolve()
        self.home = self.file.parent
        self.secrets = self.home / "secrets.env"
        if not cluster_file and not self.secrets.is_file() and (OLD_STACK / "secrets.env").is_file():
            notice(f"the secrets are read from {OLD_STACK / 'secrets.env'}: move the file to {self.secrets} "
                   "(the default stack moved to deploy/stack/ in 2.9.11.i4; this fallback is for one release)")
            self.secrets = OLD_STACK / "secrets.env"
        self.data = Path(data_dir or os.environ.get("DRISHTI_DATA_DIR") or ROOT / "data").resolve()
        self.stack_data = self.data / "stack"
        self.jar = self.stack_data / "server.jar"


at: Layout = None   # set once by main()


def say(text: str) -> None:
    print(text, flush=True)


def notice(text: str) -> None:
    print(f"NOTICE: {text}", file=sys.stderr, flush=True)


# -- configuration -----------------------------------------------------------------------------------------------------------------
def load_stack() -> dict:
    try:
        import yaml
    except ImportError:
        sys.exit("needs PyYAML: run with the console's Python (drishti-console/.venv), as bin/start-cluster does")
    if not at.file.exists():
        sys.exit(f"no cluster file at {at.file} (--cluster FILE; deploy/stack/stack.yaml is the format)")
    return yaml.safe_load(at.file.read_text(encoding="utf-8"))


def python() -> str:
    """The console's Python (it has PyYAML, deltalake and the console's packages), else this one."""
    for rel in ("bin/python", "Scripts/python.exe"):
        py = ROOT / "drishti-console" / ".venv" / rel
        if py.exists():
            return str(py)
    return sys.executable


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
    have = read_env(at.secrets)
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
            lines = ["# The cluster's secrets (bin/README.md). Git-ignored: never commit this file.", ""]
            lines += [f"{k}={v}" for k, v in have.items()]
            at.secrets.write_text("\n".join(lines) + "\n", encoding="utf-8")
        finally:
            os.umask(old)
        say(f"made {', '.join(missing)} in {at.secrets}")
    at.secrets.chmod(0o600)
    return have


# -- the jar -----------------------------------------------------------------------------------------------------------------------
def server_jar() -> Path:
    """<data>/stack/server.jar: a copy of the newest kernel/drishti-server/target/drishti-server-*-exec.jar, refreshed when a newer build exists."""
    built = sorted((ROOT / "kernel/drishti-server" / "target").glob("drishti-server-*-exec.jar"), key=lambda p: p.stat().st_mtime, reverse=True)
    JAR = at.jar
    if built and (not JAR.exists() or built[0].stat().st_mtime > JAR.stat().st_mtime):
        JAR.parent.mkdir(parents=True, exist_ok=True)
        part = JAR.with_name("server-copying.jar")
        shutil.copy2(built[0], part)
        os.replace(part, JAR)                    # a server still running keeps the jar it opened
        say(f"server jar: {built[0].name} copied to {JAR}")
    if not JAR.exists():
        sys.exit("no server jar: build it first (./mvnw -o -q package -DskipTests -pl :drishti-app -am), then run bin/start-cluster again")
    return JAR


# -- process ids -----------------------------------------------------------------------------------------------------------------
def pid_file(name: str) -> Path:
    if name == CONSOLE or name.startswith(CONSOLE + "-"):         # console, or console-<name> of a stack file's consoles: list
        return at.data / "run" / f"{name}.pid"
    return at.data / "run" / f"server-{name}.pid"


def consoles(stack: dict) -> list[dict]:
    """The stack file's consoles (docs/architecture/DISTRIBUTIONS.md section 7): its ``consoles:`` list, each {name, port, distribution,
    servers, settings}, so one cluster serves a Risk console on 17480 and a Finance console on 17481; else the one ``console:`` block.
    A named console's process is console-<name> (its pid file, log and temp folder)."""
    base = stack.get("console") or {}
    out = []
    for c in stack.get("consoles") or []:
        out.append({**base, **c, "process": f"{CONSOLE}-{c['name']}", "settings": {**(base.get("settings") or {}), **(c.get("settings") or {})}})
    return out or ([{**base, "process": CONSOLE}] if base else [])


def running(name: str) -> int | None:
    """The process id of the node (or the console) when its pid file names a live process of ours, else None."""
    try:
        pid = int(pid_file(name).read_text(encoding="utf-8").splitlines()[0].strip())
    except (OSError, ValueError, IndexError):
        return None
    return pid if ours(pid) else None


def ours(pid: int) -> bool:
    """The process is alive and is a Drishti server or console, so a reused id is never stopped."""
    if os.name == "nt":
        out = subprocess.run(["tasklist", "/FI", f"PID eq {pid}", "/NH"], capture_output=True, text=True).stdout.lower()
        return "java" in out or "python" in out
    try:
        cmd = Path(f"/proc/{pid}/cmdline").read_bytes().replace(b"\0", b" ").decode("utf-8", "replace")
    except OSError:
        try:
            cmd = subprocess.run(["ps", "-p", str(pid), "-o", "command="], capture_output=True, text=True).stdout
        except OSError:
            return False
    return " -jar " in cmd or "run_drishti_web.py" in cmd


def listening(port: int) -> bool:
    with socket.socket() as s:
        s.settimeout(0.3)
        return s.connect_ex(("127.0.0.1", int(port))) == 0


def stack_host(stack: dict) -> str:
    """The address every node advertises and the console is given: stack.yaml's host (for a machine with several interfaces), else this
    machine's primary IPv4 address (the default route's interface; never a loopback or a container bridge). Members never advertise
    localhost, 127.x.x.x or 0.0.0.0 (docs/guides/OPERATIONS_GUIDE.md)."""
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
    net = at.home / "nodes" / node["name"] / "net"
    (net / "peers").mkdir(parents=True, exist_ok=True)
    if not (net / "node.yaml").exists():
        (net / "node.yaml").write_text(HEADER + f"# {node['name']} of the cluster (its cluster file). The key comes from the environment only.\n"
                                       f"enabled: true\nnode-id: {node['name']}\n"
                                       f"advertise-url: http://${{DRISHTI_STACK_HOST}}:{node['port']}     # this machine's real address (bin/start-cluster sets it)\n"
                                       "secret: ${DRISHTI_NET_SECRET}\n", encoding="utf-8")
    if not (net / "gossip.yaml").exists():
        g = stack.get("gossip") or {}
        (net / "gossip.yaml").write_text(HEADER + "# Fast failure detection for one machine (the cluster file, gossip).\n"
                                         + "".join(f"{k}: {v}\n" for k, v in g.items()), encoding="utf-8")
    seed = next((n for n in stack["nodes"] if n["name"] == stack.get("seed")), None)
    if seed and seed["name"] != node["name"] and not any((net / "peers").glob("*.yaml")):
        (net / "peers" / f"{seed['name']}.yaml").write_text(HEADER + f"# The seed: where {node['name']} knocks when it joins.\n"
                                                           f"url: http://${{DRISHTI_STACK_HOST}}:{seed['port']}     # the seed's real address (bin/start-cluster sets it)\n", encoding="utf-8")
    return net


def _lakeconfig():
    """tools/lake/lakeconfig.py: where the lakes are, and the variable that names the demo lake's root."""
    import importlib.util
    import sys as _s
    p = ROOT / "tools" / "lake"
    if str(p) not in _s.path:
        _s.path.insert(0, str(p))
    return importlib.import_module("lakeconfig")


def lake_dir(name: str) -> Path:
    return at.stack_data / "lakes" / name


def build_lake(stack: dict, name: str) -> None:
    """data/stack/lakes/<name>: synthetic trades of stack.yaml's lakes entry (tools/bench/trades.py), when the lake has no tables yet."""
    lake = lake_dir(name)
    if (lake / "trading").is_dir():
        return
    spec = (stack.get("lakes") or {}).get(name)
    if not spec:
        sys.exit(f"lake {name} is not in stack.yaml (lakes) and {lake} has no trades")
    tmp = at.stack_data / "tmp" / f"lake-{name}"
    tmp.mkdir(parents=True, exist_ok=True)
    n = str(spec.get("per-day", 1500))
    say(f"building lake {name}: {spec['systems']}, {n} trades a day for {spec.get('days', 2)} days ({lake})")
    subprocess.run([python(), str(ROOT / "tools" / "bench" / "trades.py"), "--lake", str(lake), "--tmp", str(tmp), "--days", str(spec.get("days", 2)),
                    "--min", n, "--max", n, "--systems", spec["systems"], "--counterparties", str(spec.get("counterparties", 300))],
                   check=True, env={**os.environ, "TMPDIR": str(tmp)})
    shutil.rmtree(tmp, ignore_errors=True)


def node_env(stack: dict, node: dict, keys: dict) -> tuple[dict, Path]:
    """The node's environment and working folder: the repository for home: repo, else data/stack/nodes/<node> with its own data,
    connectors and lake."""
    env = {**os.environ, **{k: str(v) for k, v in (stack.get("settings") or {}).items()}, **{k: str(v) for k, v in (node.get("env") or {}).items()}}
    env.update(keys)
    env["DRISHTI_NET_DIR"] = str(node_net(stack, node))
    env["DRISHTI_STACK_HOST"] = stack_host(stack)
    env["DRISHTI_PACKS"] = node["packs"]
    env.setdefault("DRISHTI_STORE_ROOT", str(at.data / "fstore"))
    env.setdefault("DRISHTI_UI_KIT_DIR", str(ROOT / "config" / "ui-kit"))   # the platform UI kit for every node, whatever its working folder   # one content store for every node of this machine's cluster (DATA_ARCHITECTURE.md)
    if node.get("home") == "repo":
        if at.data != ROOT / "data":
            env["DRISHTI_NODE_DATA"] = str(at.data)
        return env, ROOT
    home = at.stack_data / "nodes" / node["name"]
    (home / "data").mkdir(parents=True, exist_ok=True)
    (home / "connectors").mkdir(parents=True, exist_ok=True)
    env.update(DRISHTI_NODE_DATA=str(home / "data"), DRISHTI_CONNECTORS_DIR=str(home / "connectors"), DRISHTI_PACKS_DIR=str(ROOT / "config" / "packs"))
    # the node's demo lake is the repository's lake file, config/lakes/demo.yaml (docs/guides/DATA_LAKE_GUIDE.md): the same tables, partitions
    # and promoted columns as every node; its root, the demo lake's (config/lakes/demo.yaml), is the node's synthetic lake (stack.yaml lakes)
    env["DRISHTI_LAKES_DIR"] = str(ROOT / "config" / "lakes")
    if node.get("lake"):
        env[_lakeconfig().DEMO_ROOT_ENV] = str(lake_dir(node["lake"]))
    return env, home


def bin_run(script: str, args: list[str], env: dict | None = None) -> int:
    """Runs bin/<script> (.sh, or .ps1 on Windows), its output as it comes."""
    cmd = ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(BIN / f"{script}.ps1")] if os.name == "nt" else ["bash", str(BIN / f"{script}.sh")]
    return subprocess.run([*cmd, *args], env=env or {**os.environ, "DRISHTI_PYTHON": python()}).returncode


def start_node(stack: dict, node: dict, jar: Path) -> bool:
    """One node through bin/start-server.sh: its Drishti*net folder, its own data folder (a node with a home of its own), the stack's
    settings and secrets, in the background; pid, log and temp folder in the data folder (<data>/run/server-<node>.pid)."""
    name, port = node["name"], node["port"]
    pid = running(name)
    if pid:
        say(f"{name}: already running (pid {pid}, port {port})")
        return True
    if listening(port):
        say(f"{name}: port {port} is in use by another process: not started (stop that process, or change the port in stack.yaml)")
        return False
    if node.get("lake"):
        build_lake(stack, node["lake"])
    env, cwd = node_env(stack, node, {})
    node_data = env.pop("DRISHTI_NODE_DATA", "")
    env.pop("DRISHTI_DATA_DIR", None)
    env["DRISHTI_PYTHON"] = python()
    env["JAVA_HOME"] = env.get("JAVA_HOME") or str(Path(java(stack)).parent.parent)
    args = ["--node", name, "--node-dir", env.pop("DRISHTI_NET_DIR"), "--port", str(port), "--heap", str(node.get("heap", "1536m")),
            "--jar", str(jar), "--data-dir", str(at.data), "--env-file", str(at.secrets), "--advertise", stack_host(stack),
            "--workdir", str(cwd), "--background"]
    if node_data:
        args += ["--node-data", node_data]
    for flag in node.get("jvm", []):
        args += ["--java-opt", flag]
    for arg in [f"--drishti.sources.connectors.{c}.enabled=false" for c in node.get("off", [])] + list(node.get("args", [])):
        args += ["--server-arg", arg]
    return bin_run("start-server", args, env) == 0


def start_console(stack: dict, c: dict) -> bool:
    """A console through bin/start-console.sh, given the servers of stack.yaml (its servers) at this machine's real address, so its
    list matches what the cluster advertises; it learns the other members from the cluster. Its distribution: ``distribution:``."""
    port = c["port"]
    proc = c.get("process", CONSOLE)
    pid = running(proc)
    if pid:
        say(f"{proc}: already running (pid {pid}, port {port})")
        return True
    if listening(port):
        say(f"{proc}: port {port} is in use by another process: not started")
        return False
    by_name = {n["name"]: n for n in stack["nodes"]}
    env = {**os.environ, **{k: str(v) for k, v in (stack.get("settings") or {}).items()}, **{k: str(v) for k, v in (c.get("settings") or {}).items()}}
    env.pop("DRISHTI_DATA_DIR", None)
    env["DRISHTI_PYTHON"] = python()
    host = stack_host(stack)
    servers = ",".join(f"http://{host}:{by_name[s]['port']}" for s in (c.get("servers") or [stack["nodes"][0]["name"]]))
    args = ["--server", servers, "--port", str(port), "--data-dir", str(at.data), "--env-file", str(at.secrets), "--background"]
    if c.get("distribution"):
        args += ["--distribution", str(c["distribution"])]              # risk, finance, genomics, all, or a file (DISTRIBUTIONS.md)
    if proc != CONSOLE:
        args += ["--name", proc[len(CONSOLE) + 1:]]
    return bin_run("start-console", args, env) == 0


def _console_named(c: dict, names: list[str]) -> bool:
    return not names or CONSOLE in names or c["process"] in names or c.get("name") in names


def start(names: list[str]) -> int:
    stack = load_stack()
    secrets()
    jar = server_jar()
    ok = True
    for node in stack["nodes"]:
        if not names or node["name"] in names:
            ok = start_node(stack, node, jar) and ok
    for c in consoles(stack):
        if _console_named(c, names):
            ok = start_console(stack, c) and ok
    say("" if ok else "some did not start: see above")
    return 0 if ok else 1


def stop(names: list[str]) -> int:
    """The consoles first, then the nodes, each through bin/stop-console.sh or bin/stop-server.sh (by its pid file, never by name)."""
    stack = load_stack()
    for c in consoles(stack):
        if _console_named(c, names):
            bin_run("stop-console", ["--data-dir", str(at.data)] + (["--name", c["process"][len(CONSOLE) + 1:]] if c["process"] != CONSOLE else []))
    for node in stack["nodes"]:
        if not names or node["name"] in names:
            bin_run("stop-server", ["--node", node["name"], "--data-dir", str(at.data)])
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
    keys = read_env(at.secrets)
    auth = {"Authorization": "Bearer " + token(stack.get("admin", "drishti-dev-admin"), keys["DRISHTI_TOKEN_SECRET"])} if keys.get("DRISHTI_TOKEN_SECRET") else {}
    bin_run("status", ["--data-dir", str(at.data)])
    for node in stack["nodes"]:
        m = get_json(f"http://127.0.0.1:{node['port']}/api/v1/net/members", auth)
        if m and m.get("members"):
            states = ", ".join(f"{x['nodeId']} {x['state']} at {x.get('url') or '?'}" for x in m["members"])
            say(f"members as {node['name']} sees them: {states}")
            break
    else:
        say("members: no node answered /api/v1/net/members")
    saved = at.data / "console" / "net" / "servers.json"
    try:
        entries = json.loads(saved.read_text(encoding="utf-8")).get("servers", [])
        say("console's servers (its saved list): " + ", ".join(f"{e.get('node_id') or '?'} {e['url']}" for e in entries))
    except (OSError, ValueError):
        say("console's servers: not saved yet")
    return 0


def main(argv: list[str]) -> int:
    global at
    ap = argparse.ArgumentParser(prog="cluster", description="Start, stop or show a cluster from its cluster file.")
    ap.add_argument("command", choices=("start", "stop", "status"))
    ap.add_argument("--cluster", default="", help="the cluster file (default deploy/stack/stack.yaml)")
    ap.add_argument("--data-dir", default="", help="the data folder (default DRISHTI_DATA_DIR, else <drishti>/data)")
    ap.add_argument("names", nargs="*", help="only these nodes (or console)")
    opts = ap.parse_args(argv)
    at = Layout(opts.cluster, opts.data_dir)
    return {"start": start, "stop": stop, "status": status}[opts.command](opts.names)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
