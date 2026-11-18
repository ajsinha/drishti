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

"""``drishti.py start server`` and ``stop server``: a Drishti server, alone or as a node of a Drishti*net cluster.

Java 21+ (the one lookup: --java-home, drishti.java.home of --settings, DRISHTI_JAVA_HOME, JAVA_HOME, the PATH), the newest server jar, the temporary folder, the native
memory settings and the logs under the data folder; waits for /actuator/health and prints the address other machines open. The options
and the environment are in ``drishti.py start server --help`` and docs/DEPLOYMENT_AND_QA_GUIDE.md section 6."""
from __future__ import annotations

import os
import re
import subprocess
import sys
from pathlib import Path

import procs
from procs import LaunchError, say, warn

TOOLS = procs.TOOLS
import javacmd  # noqa: E402 - tools/ is on the path through procs
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "lake"))
import lakeconfig  # noqa: E402 - the one name of the demo lake's root variable

DEMO_PACKS = "core,market-data,trading,counterparty-risk,market-risk,world-economy,news-and-markets,us-equities,gleif,funds"
DEV = {"DRISHTI_SEED_PASSWORD": "drishti-dev-admin123",
       "DRISHTI_TOKEN_SECRET": "drishti-dev-only-token-secret-change-me-0123456789",
       "DRISHTI_SESSION_SECRET": "drishti-dev-only-session-secret-change-me-0123456789",
       "DRISHTI_NET_SECRET": "drishti-dev-only-cluster-key-change-me-0123456789"}
HEADER = "".join(line + "\n" for line in Path(__file__).read_text(encoding="utf-8").splitlines()[:13])


def dev_secrets(env: dict, names) -> list:
    """The development secrets, only where none is set; the names set."""
    out = []
    for n in names:
        if not (env.get(n) or "").strip():
            env[n] = DEV[n]
            out.append(n)
    return out


def roots_line(roots: dict, java: str | None = None, why: str = "") -> list:
    """The resolved launch roots, each with where it came from (``start``, ``status`` and ``setup`` print them)."""
    out = [f"  {k:<8} {v[0]}  ({v[1]})" for k, v in roots.items() if k in ("home", "data", "modules")]
    if java:
        out.append(f"  {'java':<8} {java}  ({why})")
    return out


def check_options(a) -> None:
    if a.engine and a.engine not in ("native", "hadoop", "auto"):
        raise LaunchError("--engine is native, hadoop or auto", 2)
    if a.node and not re.fullmatch(r"[a-z0-9][a-z0-9-]*", a.node):
        raise LaunchError(f"--node {a.node}: lower-case letters, digits and dashes", 2)
    if a.advertise and procs.loopback_or_wildcard(a.advertise):
        raise LaunchError(f"--advertise {a.advertise} is a loopback or wildcard address: members advertise an address other machines can call", 2)
    if (a.node_dir or a.node_data or a.seed or a.advertise) and not a.node:
        raise LaunchError("--node-dir, --node-data, --seed and --advertise belong to a cluster node: give --node <name> too", 2)


def node_files(a, data: Path, port: int, env: dict) -> tuple:
    """A cluster node's Drishti*net folder (node.yaml, peers/ with the seeds) and the address it advertises; (folder, host)."""
    net = Path(a.node_dir or data / "nodes" / a.node / "net").resolve()
    (net / "peers").mkdir(parents=True, exist_ok=True)
    if not (net / "node.yaml").exists():
        (net / "node.yaml").write_text(HEADER + f"# {a.node}, made by drishti.py start server (docs/guides/CLUSTER_GUIDE.md). References only: the key "
                                       "and the address come from the environment.\n"
                                       f"enabled: true\nnode-id: {a.node}\n"
                                       "advertise-url: http://${DRISHTI_ADVERTISE_HOST}:${DRISHTI_PORT}     # this machine's real address (drishti.py start sets both)\n"
                                       "secret: ${DRISHTI_NET_SECRET}\n", encoding="utf-8")
        say(f"{a.node}: made {net / 'node.yaml'}")
    for seed in [s for x in (a.seed or []) for s in x.split(",") if s.strip()]:
        seed = seed.strip().removeprefix("http://").removeprefix("https://").rstrip("/")
        if ":" not in seed:
            seed = f"{seed}:{procs.DEFAULT_SERVER_PORT}"
        if procs.loopback_or_wildcard(seed.rsplit(":", 1)[0]):
            raise LaunchError(f"--seed {seed}: a seed is named by its real address, never a loopback or wildcard one", 2)
        if not any(seed in p.read_text(encoding="utf-8") for p in (net / "peers").glob("*.yaml")):
            f = net / "peers" / ("seed-" + seed.replace(":", "-").replace(".", "-") + ".yaml")
            f.write_text(HEADER + f"# A seed of {a.node} (drishti.py start server --seed).\nurl: http://{seed}\n", encoding="utf-8")
            say(f"{a.node}: seed {seed} written to {f}")
    host = procs.advertise_host(a.advertise or "")
    if not (env.get("DRISHTI_NET_SECRET") or "").strip():
        raise LaunchError("a cluster node needs the cluster key DRISHTI_NET_SECRET (32+ bytes, the same on every node): put it in the --env-file")
    return net, host


def memory_flags(workdir: Path, env: dict) -> list:
    """Direct memory beyond 256 MB for the in-memory lake's stores and Arrow's budget, from tools/lake/memory_caps.py (a later flag wins)."""
    out = []
    for flag, fmt in (("--extra-only", lambda n: f"-XX:MaxDirectMemorySize={256 + n}m"), ("--arrow-only", lambda n: f"-Ddrishti.arrow.max-bytes={n * 1048576}")):
        try:
            r = subprocess.run([procs.tools_python(env), str(TOOLS / "lake" / "memory_caps.py"), flag], cwd=str(workdir), env=env,
                               capture_output=True, text=True, timeout=60)
            n = int(r.stdout.strip() or 0)
        except (OSError, ValueError, subprocess.SubprocessError):
            n = 0
        if n > 0:
            out.append(fmt(n))
    return out


def iceberg_settings(a, env: dict, roots: dict) -> None:
    """``--iceberg``: the banking domains read from Iceberg, given to the server as settings only; nothing is written (connector files are
    read-only, the install folder is never written): the connectors folder is the shipped Iceberg templates,
    ``config/connectors.examples/iceberg`` (the modules' own connector templates stay as they are), the bindings and placement files stay
    where they are without it, and the warehouse is under the data folder (``<data>/iceberg``) unless --iceberg-root or
    ``DRISHTI_ICEBERG_ROOT`` names one."""
    config, data = roots["config"][0], roots["data"][0]
    env["DRISHTI_ICEBERG_ENGINE"] = "native"
    env["DRISHTI_ICEBERG_ROOT"] = a.iceberg_root or env.get("DRISHTI_ICEBERG_ROOT") or str(data / "iceberg")
    if (env.get("DRISHTI_CONNECTORS_DIR") or "").strip():
        say(f"--iceberg: DRISHTI_CONNECTORS_DIR is set ({env['DRISHTI_CONNECTORS_DIR']}): kept; put the Iceberg connector files there yourself")
    else:
        env["DRISHTI_CONNECTORS_DIR"] = str(config / "connectors.examples" / "iceberg")
        env.setdefault("DRISHTI_BINDINGS_FILE", str(config / "bindings.yaml"))      # where they are without --iceberg (beside config/connectors)
        env.setdefault("DRISHTI_PLACEMENT_FILE", str(config / "placement.yaml"))
    say(f"--iceberg: connectors {env['DRISHTI_CONNECTORS_DIR']}, warehouse {env['DRISHTI_ICEBERG_ROOT']} (settings only; nothing written)")


def find_jar(given: str | None, home: Path) -> Path:
    """--jar (a one-off run), else the newest server jar in <home>/lib, the one place (javacmd.server_jar)."""
    try:
        return javacmd.server_jar(given, home)
    except javacmd.JarError as e:
        raise LaunchError(str(e), 2 if given else 1) from None


def check_modules(a, modules: Path, jar: Path) -> str:
    """The modules beside the jar: how many packages the folder holds (a line for the start's output); LaunchError in words when this
    checkout's packages cannot be run: sources and no package, or a package older than its folder or built for another jar (R2).
    An install holds packages and no sources and is never stale; an empty folder starts a server with no module (Health says so)."""
    import modulesbuild
    dmods = modulesbuild.packages(modules)
    if getattr(a, "modules_dev", False):
        return f"modules: development switch on, the module folders of {modules} load as they are (unverified)"
    problems = modulesbuild.missing_report(modules) or modulesbuild.fresh_report(modules, jar)
    if problems:
        raise LaunchError("the modules beside the jar are not current:\n  " + "\n  ".join(problems)
                          + "\n(python tools/drishti.py build all builds the jar and every module; start server --modules-dev loads the folders as they are)")
    if not dmods:
        return f"modules: none ({modules} holds no .dmod: the server starts with no module)"
    return f"modules: {len(dmods)} package(s) in {modules}: {', '.join(p.name.removesuffix(modulesbuild.PACKAGE) for p in dmods)}"


def start(a, env: dict | None = None) -> int:
    """Starts one server (or a cluster node) from the options of ``drishti.py start server``; the exit code."""
    env = dict(os.environ if env is None else env)
    check_options(a)
    if a.env_file:
        procs.load_env_file(a.env_file, env)
    if a.distribution:
        env["DRISHTI_DISTRIBUTION"] = a.distribution         # the server reads it as drishti.distribution
    settings, keys = javacmd.settings()
    roots = javacmd.launch_roots(getattr(a, "home", None), a.data_dir, env, keys)
    home = roots["home"][0]
    data = roots["data"][0]
    data.mkdir(parents=True, exist_ok=True)
    server_data = Path(a.node_data).resolve() if a.node_data else data
    server_data.mkdir(parents=True, exist_ok=True)
    env["DRISHTI_DATA_DIR"] = str(server_data)
    # the launch roots, absolute (docs/admin/CONFIGURATION.md, "Launch roots"): the server takes no root from its working folder
    env["DRISHTI_HOME"] = str(home)
    env["DRISHTI_MODULES_HOME"] = str(roots["modules"][0])
    port = int(a.port or env.get("DRISHTI_PORT") or procs.DEFAULT_SERVER_PORT)
    stem = f"server-{a.node}" if a.node else "server"
    label = a.node or "server"
    pid_file, log, tmp = data / "run" / f"{stem}.pid", data / "logs" / f"{stem}.log", data / "tmp" / stem

    pid, fields = procs.read_pid(pid_file)
    if procs.alive(pid, procs.SERVER_MARK):
        say(f"{label}: already running (pid {pid}, port {fields.get('port', '?')}); drishti.py stop server{' --node ' + a.node if a.node else ''} stops it")
        return 3
    if pid_file.exists():
        pid_file.unlink()
        say(f"{label}: stale {pid_file} removed (pid {pid or '?'} is not running)")
    for f in [*(data / "run").glob("server.pid"), *(data / "run").glob("server-*.pid")]:   # two servers never share a data folder
        if f != pid_file:
            p, fl = procs.read_pid(f)
            if fl.get("data") == str(server_data) and procs.alive(p, procs.SERVER_MARK):
                raise LaunchError(f"{f.stem} (pid {p}) already uses the data folder {server_data}: give this node its own with --node-data", 3)
    if procs.port_in_use(port):
        raise LaunchError(f"port {port} is in use by another process: stop it, or pick another with --port", 3)

    java = javacmd.find_java(None, a.java_home, env)
    why = javacmd.locate_java(None, a.java_home, env)[1]
    major = javacmd.java_version(java)
    jar = find_jar(a.jar, home)
    modules_line = check_modules(a, roots["modules"][0], jar)
    if not (home / "config" / "packs").is_dir():
        warn(f"no config/packs/ in {home}: the server starts without domain packs")

    dev, packs, heap = [], a.packs, a.heap
    if a.demo:
        packs = packs or env.get("DRISHTI_PACKS") or DEMO_PACKS
        heap = heap or "4g"
        env.update(DRISHTI_DEMO_ENABLED="false", DRISHTI_VAR_SYSTEM_BASKETS_SEED="true", DRISHTI_OFFLINE="true", DRISHTI_SECURITY_ENABLED="true",
                   DRISHTI_SEED_ADMIN="true", DRISHTI_STUDIO_SAVE="true")
        env.setdefault("DRISHTI_LENSES", "risk,world,market,trading")
        env.setdefault("DRISHTI_SEED_USERNAME", "drishti-dev-admin")
        dev = dev_secrets(env, ["DRISHTI_SEED_PASSWORD", "DRISHTI_TOKEN_SECRET", "DRISHTI_SESSION_SECRET"] + (["DRISHTI_NET_SECRET"] if a.node else []))
        if not a.node:
            env.setdefault("DRISHTI_DATALOAD_ENABLED", "true")
            env.setdefault("DRISHTI_DATALOAD_OWNER", "true")
    heap = heap or "2g"
    if packs:
        env["DRISHTI_PACKS"] = packs
    if a.engine:
        env["DRISHTI_DELTA_ENGINE"] = a.engine
    if a.lake_root:
        env[lakeconfig.DEMO_ROOT_ENV] = a.lake_root
    if a.iceberg:
        iceberg_settings(a, env, roots)
    env["DRISHTI_PORT"] = str(port)

    url_host = procs.lan_ip()
    net = None
    if a.node:
        net, url_host = node_files(a, data, port, env)
        env["DRISHTI_ADVERTISE_HOST"] = url_host
        env["DRISHTI_NET_DIR"] = str(net)
    url_host = url_host or "localhost"

    tmp.mkdir(parents=True, exist_ok=True)
    for d in (data / "logs", data / "run", data / "dumps" / label):
        d.mkdir(parents=True, exist_ok=True)
    env["DRISHTI_TMP_DIR"] = str(tmp)
    env.setdefault("MALLOC_ARENA_MAX", "2")                        # glibc: two malloc arenas, not one per busy thread
    flags = [f"-Djava.io.tmpdir={tmp}", f"-Xmx{heap}", "-XX:MaxDirectMemorySize=256m", "-XX:TrimNativeHeapInterval=60000",
             "-Djdk.nio.maxCachedBufferSize=262144", "-XX:NativeMemoryTracking=summary", "-XX:+HeapDumpOnOutOfMemoryError",
             f"-XX:HeapDumpPath={data / 'dumps' / label}", f"-Xlog:gc*:file={data / 'logs' / f'gc-{stem}-%p.log'}:time,uptime:filecount=5,filesize=20m",
             f"-Ddrishti.data.dir={server_data}"]
    if settings:
        flags.append(f"-Ddrishti.settings.file={settings}")
    workdir = Path(a.workdir).resolve() if a.workdir else home
    flags += memory_flags(workdir, env)
    if getattr(a, "modules_dev", False):
        flags.append("-Ddrishti.modules.dev=true")
    flags += list(a.java_opt or [])
    args = [f"--server.port={port}"]
    if a.background:
        args += [f"--drishti.logging.file={log}", "--logging.threshold.console=OFF"]
    args += list(a.server_arg or []) + list(getattr(a, "rest", None) or [])

    url, health = f"http://{url_host}:{port}", f"http://127.0.0.1:{port}/actuator/health"
    say(f"Drishti {'node ' + a.node + ' ' if a.node else ''}server: {jar.name} on Java {major}, port {port}, heap {heap}, "
        f"packs {env.get('DRISHTI_PACKS') or 'the default'}, data {server_data}")
    for line in roots_line(roots, java, why):
        say(line)
    say("  " + modules_line)
    if net:
        say(f"  Drishti*net folder {net}, advertising {url}")
    if dev:
        warn(f"DEVELOPMENT secrets in use (NOT FOR PRODUCTION): {' '.join(dev)}. Put your own in an --env-file for anything shared.")

    # the one way a Drishti tool starts a JVM (tools/javacmd.py): the flags above win; it adds compact object headers on Java 25+
    cmd = javacmd.java_cmd(java, [*flags, "-jar", str(jar), *args], cwd=workdir, env=env)
    # the launcher's stop key: the server answers POST /launcher/stop with it (procs.stop_pid_file), the same stop on every OS
    fields = dict(port=port, url=url, health=health, data=server_data, distribution=env.get("DRISHTI_DISTRIBUTION") or "all",
                  **{procs.STOP_FIELD: procs.stop_key(env)})
    if a.background:
        proc = procs.spawn(cmd, workdir, env, log=data / "logs" / f"{stem}.out")
        procs.write_pid(pid_file, proc.pid, **fields)
        say(f"{label}: started (pid {proc.pid}); log {log}; waiting up to {a.wait} s for {health}")
        if procs.wait_health(health, a.wait, proc):
            say(f"{label}: up on {url}")
            return 0
        if proc.poll() is None:
            say(f"{label}: still starting after {a.wait} s (pid {proc.pid}): see {log}; drishti.py status shows when it answers")
        else:
            pid_file.unlink(missing_ok=True)
            say(f"{label}: stopped while starting: see {log} and {data / 'logs' / (stem + '.out')}")
        return 1
    proc = procs.spawn(cmd, workdir, env)
    procs.write_pid(pid_file, proc.pid, **fields)

    def up():
        import threading
        threading.Thread(target=lambda: procs.wait_health(health, a.wait, proc) and say(f"{label}: up on {url}"), daemon=True).start()
    return procs.run_foreground(proc, pid_file, up)


def stop(a, env: dict | None = None) -> int:
    """``drishti.py stop server``: by the pid file only (never by name); asked over HTTP first (POST /launcher/stop), forced after --timeout."""
    env = dict(os.environ if env is None else env)
    if a.node and a.all_nodes:
        raise LaunchError("--node and --all-nodes: one or the other", 2)
    if a.env_file:
        procs.load_env_file(a.env_file, env, quiet=True)
    data = javacmd.launch_roots(None, a.data_dir, env, javacmd.settings()[1])["data"][0]
    if a.all_nodes:
        files = [*(data / "run").glob("server.pid"), *sorted((data / "run").glob("server-*.pid"))]
        if not files:
            say(f"no server pid file in {data / 'run'}: nothing to stop")
        for f in files:
            procs.stop_pid_file(f, f.stem.removeprefix("server-"), procs.SERVER_MARK, a.timeout)
        return 0
    procs.stop_pid_file(data / "run" / (f"server-{a.node}.pid" if a.node else "server.pid"), a.node or "server", procs.SERVER_MARK, a.timeout)
    return 0


if __name__ == "__main__":
    sys.exit("run it through tools/drishti.py start server")
