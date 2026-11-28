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

    drishti.py start cluster [--cluster FILE] [--data-dir DIR] [node ...]   start what is not running (every node, then the console; or only those named, or "console")
    drishti.py stop cluster  [--cluster FILE] [--data-dir DIR] [node ...]   stop by the pid files (never by name), the console first
    drishti.py status --cluster FILE [--data-dir DIR]                       the processes, the ports, the members each node sees, the console's servers

The processes are started and stopped by ``drishti.py start server``, ``start console``, ``stop server`` and ``stop console``
(tools/launch/serverstart.py and consolestart.py; docs/DEPLOYMENT_AND_QA_GUIDE.md section 7). This file adds what belongs to a cluster: the cluster file, the secrets, the jar copy, the
synthetic lakes and each node's settings. The folder of the cluster file is configuration only: the file, nodes/<node>/net and secrets.env.

Secrets live in secrets.env beside the cluster file (git-ignored, mode 600): DRISHTI_TOKEN_SECRET, DRISHTI_SESSION_SECRET and
DRISHTI_NET_SECRET, each made on the first start when missing and kept. They reach the processes through their environment only; nothing
here prints them. The servers run a copy of the newest jar of <home>/lib, <data>/stack/server.jar, so a rebuild never replaces the jar under a
running server. Process ids and logs are where ``drishti.py start`` keeps them (<data>/run/server-<node>.pid, <data>/run/console.pid, <data>/logs/); node
data and the synthetic lakes in <data>/stack/.

Consoles: the cluster file's ``console:`` block is one console; a ``consoles:`` list (each {name, port, distribution}, the console block
its defaults) runs several over the same cluster, each with its own distribution (docs/architecture/KERNEL_AND_MODULES.md section 20.7): Drishti
Risk on 17480 and Drishti Finance on 17481. A named console is the process console-<name> (run/console-<name>.pid); ``drishti.py status``
shows each one's distribution."""
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
#: the settings files of drishti.py --settings (absolute paths); every node and the console start with them, then the cluster file's
#: profile (config/profiles/<profile>.ini), its settings-file: and a node's settings: (both beside the cluster file), a later one winning
SETTINGS = ""
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
sys.path.insert(0, str(Path(__file__).resolve().parent))
import hostaddr  # noqa: E402
import javacmd  # noqa: E402
import procs  # noqa: E402
sys.path.insert(0, str(ROOT / "tools" / "lake"))
import lakeconfig  # noqa: E402  (where a lake is: the one place the demo lake's folder is spelled)
try:
    import clusterlakes  # noqa: E402  (PyYAML: the console's Python has it)
except ImportError:                              # load_stack says what to do
    clusterlakes = None


STACK = ROOT / "deploy" / "stack"        # the default cluster: stack.yaml, nodes/ and the git-ignored secrets.env


class Layout:
    """Where one cluster's files are: its configuration beside the cluster file, everything it writes under the data folder (the one
    launch-roots lookup: --data-dir, else drishti.data.dir of the settings file, else DRISHTI_DATA_DIR, else <home>/data)."""

    def __init__(self, cluster_file: str, data_dir: str, keys: dict | None = None) -> None:
        self.file = Path(cluster_file or STACK / "stack.yaml").resolve()
        self.home = self.file.parent
        self.secrets = self.home / "secrets.env"
        self.data = javacmd.launch_roots(None, data_dir or None, os.environ, javacmd.settings()[1] if keys is None else keys)["data"][0]
        self.stack_data = self.data / "stack"
        self.jar = self.stack_data / "server.jar"


at: Layout = None   # set once by main()


def say(text: str) -> None:
    print(text, flush=True)


def notice(text: str) -> None:
    print(f"NOTICE: {text}", file=sys.stderr, flush=True)


# -- configuration -----------------------------------------------------------------------------------------------------------------
def load_stack() -> dict:
    if clusterlakes is None:
        sys.exit("needs PyYAML: run with the Python you run Drishti with, its libraries installed (python tools/drishti.py setup)")
    if not at.file.exists():
        sys.exit(f"no cluster file at {at.file} (--cluster FILE; deploy/stack/stack.yaml is the format)")
    return clusterlakes.read_stack(at.file.read_text(encoding="utf-8"))   # YAML 1.2 booleans: a node's off: list stays a list


def settings_for(stack: dict, node: dict | None = None) -> str:
    """The settings files a node (or, with no node, the console) starts with, comma-joined: --settings, the cluster file's profile, its
    settings-file: and the node's settings: (each a file beside the cluster file); "" when none."""
    import settingsfile
    named = settingsfile.with_profiles(SETTINGS or None, ROOT, [stack["profile"]] if stack.get("profile") else [])
    parts = [p for p in (named or "").split(",") if p]
    for rel in (stack.get("settings-file"), (node or {}).get("settings")):
        if rel:
            f = (at.home / str(rel)).resolve()
            if not f.is_file():
                raise procs.LaunchError(f"{at.file}: the settings file {rel} is not at {f}", 2)
            parts.append(str(f))
    return ",".join(parts)


def business_today(stack: dict) -> None:
    """The cluster's business day, DRISHTI_BUSINESS_TODAY: the environment's when set, else the newest business date of the demo lake
    (the demo lake's folder under the cluster's data folder, where lake demo builds it: lakeconfig.default_root), so the synthetic lakes and
    every server agree with the lake; nothing when it has none."""
    if os.environ.get(clusterlakes.TODAY_ENV):
        return
    demo = lakeconfig.default_root("demo", at.data)
    day = clusterlakes.last_day(demo)
    if day:
        stack.setdefault("settings", {})[clusterlakes.TODAY_ENV] = day
        say(f"business today {day}: the newest business date of {demo}")


def python() -> str:
    """The one Python (DRISHTI_PYTHON, drishti.python of the settings files, else this one): procs.tools_python."""
    return procs.tools_python()[0]


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
    """secrets.env, with any missing secret made (32 random bytes, hex) and kept; mode 600. The values are never printed."""
    have = read_env(at.secrets)
    missing = [n for n in SECRET_NAMES if not have.get(n)]
    if missing:
        import secrets as rnd
        for n in missing:
            have[n] = rnd.token_hex(32)
        old = os.umask(0o077)
        try:
            lines = ["# The cluster's secrets (docs/guides/CLUSTER_GUIDE.md). Git-ignored: never commit this file.", ""]
            lines += [f"{k}={v}" for k, v in have.items()]
            at.secrets.write_text("\n".join(lines) + "\n", encoding="utf-8")
        finally:
            os.umask(old)
        say(f"made {', '.join(missing)} in {at.secrets}")
    at.secrets.chmod(0o600)
    return have


# -- the jar -----------------------------------------------------------------------------------------------------------------------
#: beside the running copy: the name, size and SHA-256 of the <home>/lib jar it was copied from
JAR_SOURCE = "server-jar.source"


def _sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def server_jar() -> Path:
    """<data>/stack/server.jar: the running copy of THE jar of <home>/lib (javacmd.server_jar), so a rebuild never replaces the jar under a
    running server. Refreshed at every start whenever <home>/lib's jar differs from the one it was copied from (by name, size and
    SHA-256, never by time); no jar in <home>/lib stops the start with the command that makes one, whatever copy is left."""
    try:
        built = javacmd.server_jar()
    except javacmd.JarError as e:
        sys.exit(str(e))
    JAR = at.jar
    source = JAR.with_name(JAR_SOURCE)
    want = f"{built.name} {built.stat().st_size} {_sha256(built)}"
    try:
        have = source.read_text(encoding="utf-8").strip() if JAR.is_file() else ""
    except OSError:
        have = ""
    if have != want:
        JAR.parent.mkdir(parents=True, exist_ok=True)
        part = JAR.with_name("server-copying.jar")
        shutil.copy2(built, part)
        os.replace(part, JAR)                    # a server still running keeps the jar it opened
        source.write_text(want + "\n", encoding="utf-8")
        say(f"server jar: {built.name} copied to {JAR}")
    return JAR


#: beside the pinned modules: the names, sizes and SHA-256 of the files they were copied from
MODULES_SOURCE = "modules.source"


def modules_pin() -> Path:
    """<data>/stack/modules/: the running copy of THE modules of <home>/modules (the .dmod files and first-party.yaml), by the same rule
    as the jar: refreshed at every start whenever they differ from what was copied (names, sizes and SHA-256, never times), so a rebuild
    never changes the files under a running node, and a node restarted alone gets the same jar and the same packages. Each node is given
    this folder (DRISHTI_MODULES_HOME). In a checkout the packages must be current first (the same words as ``start server``)."""
    import modulesbuild
    src = javacmd.launch_roots(None, None, dict(os.environ), javacmd.settings()[1])["modules"][0]
    problems = modulesbuild.missing_report(src) or modulesbuild.fresh_report(src, javacmd.server_jar())
    if problems:
        sys.exit("the modules beside the jar are not current:\n  " + "\n  ".join(problems) + "\n(python tools/drishti.py build all)")
    files = [*modulesbuild.packages(src), *([src / "first-party.yaml"] if (src / "first-party.yaml").is_file() else [])]
    dest = at.data / "stack" / "modules"
    want = "\n".join(f"{f.name} {f.stat().st_size} {_sha256(f)}" for f in files)
    mark = dest / MODULES_SOURCE
    try:
        have = mark.read_text(encoding="utf-8").strip() if dest.is_dir() else ""
    except OSError:
        have = ""
    if have != want:
        dest.mkdir(parents=True, exist_ok=True)
        for f in files:
            part = dest / (f.name + ".part")
            shutil.copy2(f, part)
            os.replace(part, dest / f.name)
        for old in [*dest.glob("*" + modulesbuild.PACKAGE), *dest.glob("first-party.yaml")]:
            if old.name not in {f.name for f in files}:
                old.unlink()
        mark.write_text(want + "\n", encoding="utf-8")
        say(f"modules: {len([f for f in files if f.name.endswith(modulesbuild.PACKAGE)])} package(s) copied to {dest}")
    return dest


# -- process ids -----------------------------------------------------------------------------------------------------------------
def pid_file(name: str) -> Path:
    if name == CONSOLE or name.startswith(CONSOLE + "-"):         # console, or console-<name> of a stack file's consoles: list
        return at.data / "run" / f"{name}.pid"
    return at.data / "run" / f"server-{name}.pid"


def consoles(stack: dict) -> list[dict]:
    """The stack file's consoles (docs/architecture/KERNEL_AND_MODULES.md section 20.7): its ``consoles:`` list, each {name, port, distribution,
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
    return pid if procs.alive(pid, procs.CONSOLE_MARK if name.startswith(CONSOLE) else procs.SERVER_MARK) else None


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
                                       f"advertise-url: http://${{DRISHTI_STACK_HOST}}:{node['port']}     # this machine's real address (drishti.py start cluster sets it)\n"
                                       "secret: ${DRISHTI_NET_SECRET}\n", encoding="utf-8")
    if not (net / "gossip.yaml").exists():
        g = stack.get("gossip") or {}
        (net / "gossip.yaml").write_text(HEADER + "# Fast failure detection for one machine (the cluster file, gossip).\n"
                                         + "".join(f"{k}: {v}\n" for k, v in g.items()), encoding="utf-8")
    seed = next((n for n in stack["nodes"] if n["name"] == stack.get("seed")), None)
    if seed and seed["name"] != node["name"] and not any((net / "peers").glob("*.yaml")):
        (net / "peers" / f"{seed['name']}.yaml").write_text(HEADER + f"# The seed: where {node['name']} knocks when it joins.\n"
                                                           f"url: http://${{DRISHTI_STACK_HOST}}:{seed['port']}     # the seed's real address (drishti.py start cluster sets it)\n", encoding="utf-8")
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
    """data/stack/lakes/<name>: synthetic trades of stack.yaml's lakes entry (tools/bench/trades.py), ending on the cluster's business
    today: built when missing or when it ends on another day (tools/launch/clusterlakes.py)."""
    clusterlakes.build_lake(stack, name, lake_dir(name), at.stack_data / "tmp", python(), say)


def node_env(stack: dict, node: dict, keys: dict) -> tuple[dict, Path]:
    """The node's environment and working folder: the repository for home: repo, else data/stack/nodes/<node> with its own data,
    connectors and lake."""
    env = {**os.environ, **{k: str(v) for k, v in (stack.get("settings") or {}).items()}, **{k: str(v) for k, v in (node.get("env") or {}).items()}}
    env.update(keys)
    env["DRISHTI_NET_DIR"] = str(node_net(stack, node))
    env["DRISHTI_STACK_HOST"] = stack_host(stack)
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
    env["LAKE_LAKES_DIR"] = str(ROOT / "config" / "lakes")
    if node.get("lake"):
        env[_lakeconfig().DEMO_ROOT_ENV] = str(lake_dir(node["lake"]))
    return env, home


def run_command(module: str, verb: str, args: list[str], env: dict | None = None) -> int:
    """``drishti.py start|stop server|console`` in this process (tools/launch), with its own environment; its exit code."""
    import argparse as _ap
    import launchcli
    p = _ap.ArgumentParser(prog=f"{verb} {module}")
    launchcli.add_options(p, verb, module)
    before = javacmd.settings()
    if "--settings" in args:                       # this node's settings files: what the launcher hands to its JVM
        i = args.index("--settings")
        path, args = args[i + 1], args[:i] + args[i + 2:]
        import settingsfile
        javacmd.set_settings(path, settingsfile.read_all(path))
    try:
        a = p.parse_args(args)
    except SystemExit as e:                        # a wrong option in the cluster file: this node is not started, the others are
        javacmd.set_settings(before[0] or None, before[1])
        return int(e.code or 2)
    mod = __import__({"server": "serverstart", "console": "consolestart"}[module])
    try:
        return getattr(mod, verb)(a, env or {**os.environ, "DRISHTI_PYTHON": python()})
    except (procs.LaunchError, javacmd.JavaError, javacmd.JarError, javacmd.HomeError) as e:
        print(f"drishti: {e}", file=sys.stderr, flush=True)
        return getattr(e, "code", 1)
    finally:
        javacmd.set_settings(before[0] or None, before[1])


def start_node(stack: dict, node: dict, jar: Path, modules: Path | None = None) -> bool:
    """One node through ``drishti.py start server``: its Drishti*net folder, its own data folder (a node with a home of its own), the stack's
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
    if modules is not None:
        env["DRISHTI_MODULES_HOME"] = str(modules)        # the pinned copy: every node runs the same packages
    args = ["--node", name, "--node-dir", env.pop("DRISHTI_NET_DIR"), "--port", str(port), "--heap", str(node.get("heap", "1536m")),
            "--jar", str(jar), "--data-dir", str(at.data), "--env-file", str(at.secrets), "--advertise", stack_host(stack),
            "--workdir", str(cwd), "--background"]
    if node_data:
        args += ["--node-data", node_data]
    if node.get("packs"):
        args += ["--packs", str(node["packs"])]                 # a member's own packs, beating the profile's; else the profile's
    settings = settings_for(stack, node)                         # --settings, the profile, the cluster's and the node's files
    if settings:
        args += ["--settings", settings]
    for flag in node.get("jvm", []):
        args.append(f"--java-opt={flag}")             # one argument: a JVM flag starts with a dash
    own = None if node.get("home") == "repo" else Path(env.get("DRISHTI_CONNECTORS_DIR") or cwd / "connectors")
    off = clusterlakes.switched_off(node, lake_dir(node["lake"]) if node.get("lake") else None, own, ROOT / "config" / "lakes")
    if len(off) > len(node.get("off") or []):
        say(f"{name}: off on this node, its lake holds none of their tables: {', '.join(off[len(node.get('off') or []):])}")
    if own is None and off:
        say(f"{name}: off: {', '.join(off)} cannot be applied: a node with home: repo reads the repository's config/connectors; put 'enabled: false' in "
            "those connector files there (or give the node its own home) and remove off: from the node")
        return False
    if own is not None:
        clusterlakes.write_off_files(own, off)                    # the one switch: 'enabled: false' in the connector's file
    for arg in list(node.get("args", [])):
        args.append(f"--server-arg={arg}")
    return run_command("server", "start", args, env) == 0


def start_console(stack: dict, c: dict) -> bool:
    """A console through ``drishti.py start console``, given the servers of stack.yaml (its servers) at this machine's real address, so its
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
        args += ["--distribution", str(c["distribution"])]              # risk, finance, public-sector, genomics, all, or a file (KERNEL_AND_MODULES.md)
    if proc != CONSOLE:
        args += ["--name", proc[len(CONSOLE) + 1:]]
    settings = settings_for(stack)
    if settings:
        args += ["--settings", settings]
    return run_command("console", "start", args, env) == 0


def _console_named(c: dict, names: list[str]) -> bool:
    return not names or CONSOLE in names or c["process"] in names or c.get("name") in names


def unknown_names(stack: dict, names: list[str]) -> str:
    """The words refusing the names that are no node and no console of the cluster file ("" when every name is one): a name that
    matches nothing would otherwise start or stop nothing in silence (a cluster file given as a name, a typo)."""
    known = [n["name"] for n in stack.get("nodes") or []]
    cons = consoles(stack)
    known += ([CONSOLE] if cons else []) + [c["process"] for c in cons if c["process"] != CONSOLE] + [str(c["name"]) for c in cons if c.get("name")]
    bad = [n for n in names if n not in known]
    if not bad:
        return ""
    hint = ""
    if any(n.endswith((".yaml", ".yml")) or Path(n).is_file() for n in bad):
        hint = " (a cluster file is given with --cluster FILE, not as a name)"
    return (f"no node or console named {', '.join(bad)} in {at.file}{hint}; the names are: {', '.join(dict.fromkeys(known)) or 'none'}")


def start(names: list[str]) -> int:
    stack = load_stack()
    if (refused := unknown_names(stack, names)):
        say(refused)
        return 2
    secrets()
    business_today(stack)
    jar = server_jar()
    modules = modules_pin()
    ok = True
    for node in stack["nodes"]:
        if not names or node["name"] in names:
            ok = start_node(stack, node, jar, modules) and ok
    for c in consoles(stack):
        if _console_named(c, names):
            ok = start_console(stack, c) and ok
    say("" if ok else "some did not start: see above")
    return 0 if ok else 1


def stop(names: list[str]) -> int:
    """The consoles first, then the nodes, each through ``drishti.py stop console`` or ``stop server`` (by its pid file, never by name)."""
    stack = load_stack()
    if (refused := unknown_names(stack, names)):
        say(refused)
        return 2
    for c in consoles(stack):
        if _console_named(c, names):
            run_command("console", "stop", ["--data-dir", str(at.data)] + (["--name", c["process"][len(CONSOLE) + 1:]] if c["process"] != CONSOLE else []))
    for node in stack["nodes"]:
        if not names or node["name"] in names:
            run_command("server", "stop", ["--node", node["name"], "--data-dir", str(at.data)])
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
        with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(urllib.request.Request(url, headers=headers or {}), timeout=5) as r:   # no proxy for Drishti itself
            return json.load(r)
    except (OSError, urllib.error.URLError, ValueError):
        return None


def status(_names: list[str]) -> int:
    stack = load_stack()
    keys = read_env(at.secrets)
    auth = {"Authorization": "Bearer " + token(stack.get("admin", "drishti-dev-admin"), keys["DRISHTI_TOKEN_SECRET"])} if keys.get("DRISHTI_TOKEN_SECRET") else {}
    import launchcli
    launchcli.status_table([at.data])
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
    ap = argparse.ArgumentParser(prog="drishti.py start|stop|status cluster", description="Start, stop or show a cluster from its cluster file.")
    ap.add_argument("command", choices=("start", "stop", "status"))
    ap.add_argument("--cluster", default="", help="the cluster file (default deploy/stack/stack.yaml)")
    ap.add_argument("--data-dir", default="", help="the data folder (default drishti.data.dir of the settings file, else DRISHTI_DATA_DIR, else <drishti>/data)")
    ap.add_argument("--settings", default="", help="a settings file (an absolute path) every node starts with (start-server --settings)")
    ap.add_argument("names", nargs="*", help="only these nodes (or console)")
    opts = ap.parse_args(argv)
    global SETTINGS
    if opts.settings and not Path(opts.settings).is_absolute():
        say(f"--settings {opts.settings}: give the settings file as an absolute path")
        return 2
    SETTINGS = opts.settings
    keys = None
    if opts.settings:                              # the nodes' settings file names the data folder the same way it does for them
        import settingsfile
        keys = {**javacmd.settings()[1], **settingsfile.read_all(opts.settings)}
    try:
        at = Layout(opts.cluster, opts.data_dir, keys)
    except javacmd.HomeError as e:
        say(f"drishti: {e}")
        return 2
    return {"start": start, "stop": stop, "status": status}[opts.command](opts.names)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
