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

"""The launch groups of ``tools/drishti.py``: ``setup``, ``build jar``, ``start``, ``stop`` and ``status`` (server, console, all, cluster).
Python only, the same on Linux, macOS and Windows: they replace the bin/ shell and PowerShell scripts. ``--settings`` is drishti.py's own
(read before the command; handed to the server and the console). Exit codes: 0 ok, 1 failed, 2 a wrong option, 3 already running or the
port in use."""
from __future__ import annotations

import argparse
import os
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))
import procs  # noqa: E402
import javacmd  # noqa: E402

RAW = argparse.RawDescriptionHelpFormatter
P = "python tools/drishti.py"


def add_options(p: argparse.ArgumentParser, verb: str, what: str) -> None:
    """The options of one ``start``/``stop`` command (also what clusterctl parses for each node and console it starts)."""
    common = [("--data-dir", dict(metavar="DIR", help="the data folder (default drishti.data.dir of --settings, else DRISHTI_DATA_DIR, else <home>/data): run/, logs/, tmp/ are under it")),
              ("--env-file", dict(metavar="FILE", help="KEY=VALUE lines put into the process's environment (secrets stay off the command line; values never printed)"))]
    if verb == "stop":
        for flag, kw in common:
            p.add_argument(flag, **kw)
        if what == "server":
            p.add_argument("--node", help="the cluster node to stop (its pid file server-<node>.pid); without it, the single server")
            p.add_argument("--all-nodes", action="store_true", help="every server pid file of the data folder (the single server and every node)")
            p.add_argument("--timeout", type=int, default=60, metavar="SECONDS", help="how long to wait after the polite stop before forcing it (default 60)")
        elif what == "console":
            p.add_argument("--name", help="the console started with --name (run/console-<name>.pid)")
            p.add_argument("--timeout", type=int, default=30, metavar="SECONDS", help="how long to wait before forcing the stop (default 30)")
        else:
            p.add_argument("--all-nodes", action="store_true", help="every server pid file, not only server.pid")
        return
    p.add_argument("--home", help="the install folder (default DRISHTI_HOME, else drishti.home of --settings, else the folder these tools are in; "
                                  "another install's folder is refused)")
    for flag, kw in common:
        p.add_argument(flag, **kw)
    p.add_argument("--demo", action="store_true", help="the offline demo on the lakes, with DEVELOPMENT secrets and the drishti-dev-admin account: NOT FOR PRODUCTION")
    p.add_argument("--distribution", metavar="NAME", help="risk, finance, genomics, all, or a file path (DRISHTI_DISTRIBUTION); docs/architecture/DISTRIBUTIONS.md")
    p.add_argument("--background", action="store_true", help="detach: log and pid file under the data folder (logs/, run/)")
    if what == "server":
        p.add_argument("--port", type=int, help="the server's port (default DRISHTI_PORT, else 18480)")
        p.add_argument("--heap", metavar="SIZE", help="the heap limit, -Xmx (default 2g; 4g with --demo)")
        p.add_argument("--jar", metavar="FILE", help="a server jar for a one-off run (default the newest in <home>/lib: drishti.py build jar)")
        p.add_argument("--java-home", metavar="DIR", help="the JDK (default DRISHTI_JAVA_HOME, then JAVA_HOME, then java on the PATH; Java 21+)")
        p.add_argument("--modules-dev", action="store_true", help="the development switch (drishti.modules.dev): module source folders are loaded as they are, unverified "
                                                                  "(no .dmod needed, no stale check); refused in a cluster and under prod")
        p.add_argument("--packs", metavar="LIST", help="the packs to load (DRISHTI_PACKS; default the server's own, or the demo's ten with --demo)")
        p.add_argument("--engine", help="the Delta engine: native, hadoop or auto (DRISHTI_DELTA_ENGINE)")
        p.add_argument("--lake-root", metavar="DIR", help="the Delta lake root (DRISHTI_DELTA_ROOT)")
        p.add_argument("--iceberg", action="store_true", help="serve the stores from Iceberg tables: the connectors folder is config/connectors.examples/iceberg (settings only; nothing is written)")
        p.add_argument("--iceberg-root", metavar="DIR", help="the Iceberg tables' root (DRISHTI_ICEBERG_ROOT, default <data>/iceberg), with --iceberg")
        p.add_argument("--java-opt", action="append", metavar="FLAG", help="one more JVM flag (repeatable)")
        p.add_argument("--server-arg", action="append", metavar="ARG", help="one more server argument, e.g. --drishti.x=y (repeatable)")
        p.add_argument("--workdir", metavar="DIR", help="the server's working folder (default <home>)")
        p.add_argument("--wait", type=int, default=300, metavar="SECONDS", help="how long to wait for the health answer (default 300)")
        g = p.add_argument_group("a cluster node (docs/guides/CLUSTER_GUIDE.md)")
        g.add_argument("--node", help="the node's id: its pid, log and temp folder are named after it, so several run on one machine")
        g.add_argument("--node-dir", metavar="DIR", help="the node's Drishti*net folder (default <data>/nodes/<node>/net, made when missing)")
        g.add_argument("--node-data", metavar="DIR", help="the node's own data folder (its identity database, saved members, inbox); default <data>")
        g.add_argument("--seed", action="append", metavar="HOST:PORT", help="a member to join through (repeatable, or comma-separated)")
        g.add_argument("--advertise", metavar="IP", help="the address the node advertises (default this machine's real LAN address; never a loopback one)")
    elif what == "console":
        p.add_argument("--server", action="append", metavar="URLS", help="the server, or a cluster's entry nodes, comma-separated (default DRISHTI_SERVER_URL, else this machine's LAN address :18480)")
        p.add_argument("--port", type=int, help="the console's port (default DRISHTI_CONSOLE_PORT, else 17480)")
        p.add_argument("--python", metavar="FILE", help="the Python 3.11+ to make drishti-console/.venv with (default this one)")
        p.add_argument("--reinstall", action="store_true", help="remake drishti-console/.venv (after drishti-console/requirements.txt changed)")
        p.add_argument("--wait", type=int, default=90, metavar="SECONDS", help="how long to wait for the health answer (default 90)")
        p.add_argument("--name", help="a name for this console when several run from one data folder (run/console-<name>.pid)")
    else:
        p.add_argument("--server-port", type=int, default=procs.DEFAULT_SERVER_PORT, help="the server's port (default 18480)")
        p.add_argument("--console-port", type=int, help="the console's port (default 17480)")
        p.add_argument("--heap", metavar="SIZE", help="the server's heap (-Xmx)")
        p.add_argument("--java-home", metavar="DIR", help="the JDK (default DRISHTI_JAVA_HOME, then JAVA_HOME, then the PATH)")


def _ns(**kw) -> argparse.Namespace:
    return argparse.Namespace(**kw)


def _server_ns(a, **over) -> argparse.Namespace:
    p = argparse.ArgumentParser()
    add_options(p, "start", "server")
    ns = p.parse_args([])
    for k in ("home", "data_dir", "env_file", "demo", "distribution", "heap", "java_home"):
        setattr(ns, k, getattr(a, k, None))
    for k, v in over.items():
        setattr(ns, k, v)
    return ns


def _console_ns(a, **over) -> argparse.Namespace:
    p = argparse.ArgumentParser()
    add_options(p, "start", "console")
    ns = p.parse_args([])
    for k in ("home", "data_dir", "env_file", "demo", "distribution"):
        setattr(ns, k, getattr(a, k, None))
    for k, v in over.items():
        setattr(ns, k, v)
    return ns


def _run(fn, *args) -> int:
    try:
        return fn(*args) or 0
    except procs.LaunchError as e:
        print(f"drishti: {e}", file=sys.stderr, flush=True)
        return e.code


def cmd_start(a, extra) -> int:
    import consolestart
    import serverstart
    if a.what == "server":
        a.rest = extra
        return _run(serverstart.start, a)
    if extra:
        raise SystemExit("unrecognized arguments: " + " ".join(extra))
    if a.what == "console":
        return _run(consolestart.start, a)
    # all: one server and one console on this machine, in the background, the console pointed at the server's LAN address
    host = procs.lan_ip() or "localhost"
    rc = _run(serverstart.start, _server_ns(a, port=a.server_port, background=True))
    if rc:
        return rc
    return _run(consolestart.start, _console_ns(a, port=a.console_port, background=True, server=[f"http://{host}:{a.server_port}"]))


def cmd_stop(a, extra) -> int:
    import consolestart
    import serverstart
    if a.what == "server":
        return _run(serverstart.stop, a)
    if a.what == "console":
        return _run(consolestart.stop, a)
    rc = _run(consolestart.stop, _ns(data_dir=a.data_dir, env_file=a.env_file, name=None, timeout=30))
    return _run(serverstart.stop, _ns(data_dir=a.data_dir, env_file=a.env_file, node=None, all_nodes=a.all_nodes, timeout=60)) or rc


def cmd_cluster(a, extra) -> int:
    """start|stop cluster, status --cluster: tools/launch/clusterctl.py, which reads the cluster file with PyYAML: run with the console's
    Python when this one has no PyYAML."""
    try:
        import yaml  # noqa: F401
    except ModuleNotFoundError:
        py = procs.venv_python()
        if py.exists() and Path(sys.executable).resolve() != py.resolve():
            return subprocess.run([str(py), str(HERE.parent / "drishti.py"), *a._argv]).returncode
        raise SystemExit("drishti: the cluster file needs PyYAML: run  python tools/drishti.py setup  (it makes drishti-console/.venv), "
                         "then run this with that Python") from None
    import clusterctl
    verb = getattr(a, "verb", "status")
    data = a.data_dir[0] if isinstance(a.data_dir, list) else a.data_dir
    args = [verb, *(["--cluster", a.cluster] if a.cluster else []), *(["--data-dir", data] if data else [])]
    settings = javacmd.settings()[0]
    if settings:
        args += ["--settings", settings]
    return clusterctl.main(args + list(getattr(a, "names", []) or []))


def status_table(dirs: list) -> int:
    """For each pid file under <data>/run of each data folder: pid, state, port, health answer, distribution, address. 1 when one is not
    running or not answering."""
    rc, any_found = 0, False
    say = procs.say
    say(f"{'process':<22} {'pid':>8}  {'state':<10} {'port':<6} {'health':<10} {'distribution':<16} address")
    for data in dirs:
        run = Path(data) / "run"
        files = [*run.glob("server.pid"), *sorted(run.glob("server-*.pid")), *run.glob("console.pid"), *sorted(run.glob("console-*.pid"))]
        for f in files:
            any_found = True
            pid, fields = procs.read_pid(f)
            mark = procs.CONSOLE_MARK if f.stem.startswith("console") else procs.SERVER_MARK
            state = "running" if procs.alive(pid, mark) else "stale pid"
            answer = "-"
            if fields.get("health"):
                answer = "UP" if procs.http_ok(fields["health"]) else "no answer"
            if state != "running" or answer == "no answer":
                rc = 1
            say(f"{f.stem:<22} {pid or '-':>8}  {state:<10} {fields.get('port', '-'):<6} {answer:<10} {fields.get('distribution', 'all'):<16} {fields.get('url', '-')}")
    if not any_found:
        say("(no Drishti process started with drishti.py start in this data folder)")
    return rc


def cmd_status(a, extra) -> int:
    if a.cluster:
        return cmd_cluster(a, extra)
    env = dict(os.environ)
    if a.env_file:
        procs.load_env_file(a.env_file, env, quiet=True)
    roots = javacmd.launch_roots(a.home, a.data_dir[0] if a.data_dir else None, env, javacmd.settings()[1])
    dirs = [Path(d).resolve() for d in a.data_dir] if a.data_dir else [roots["data"][0]]
    for k in ("home", "data", "modules"):
        procs.say(f"{k:<8} {roots[k][0]}  ({roots[k][1]})")
    import modulesbuild
    procs.say(f"{'packages':<8} " + (", ".join(p.name.removesuffix(modulesbuild.PACKAGE) for p in modulesbuild.packages(roots["modules"][0])) or "none (.dmod files in the modules folder)"))
    j = javacmd.describe_java(None, None, env)
    procs.say(f"{'java':<8} {j['java'] or '-'}  ({j['why'] or j['problem']}{'' if j['ok'] else '; ' + j['problem']})")
    procs.say("")
    return status_table(dirs)


def cmd_setup(a, extra) -> int:
    import envsetup
    if a.home:
        javacmd.find_home(a.home)
    return _run(envsetup.setup, a)


def cmd_build(a, extra) -> int:
    import envsetup
    import modulesbuild
    if a.cmd == "modules":
        return _run(modulesbuild.build_modules, a)
    if a.cmd == "all":
        a.ids = []
        return _run(modulesbuild.build_modules, a, None, True)
    return _run(envsetup.build_jar, a)


def register(groups, add) -> None:
    """Adds setup, build, start, stop and status to drishti.py's command line."""
    s = groups.add_parser("setup", help="setup: prepare this machine (Python, Java 21, the data folder, ~/drishti-envs/dev.ini, drishti-console/.venv)",
                          description=__import__("envsetup").__doc__, formatter_class=RAW,
                          epilog=f"examples:\n  {P} setup\n  {P} setup --check\n  {P} setup --data-dir D:/drishti-data --skip-venv")
    s.add_argument("--check", action="store_true", help="only report; change nothing (exit 1 when something needs doing)")
    s.add_argument("--home", help="the install folder (default DRISHTI_HOME, else the folder these tools are in)")
    s.add_argument("--data-dir", metavar="DIR", help="the data folder to prepare (default DRISHTI_DATA_DIR, else <home>/data)")
    s.add_argument("--envs-dir", metavar="DIR", help="the settings folder (default ~/drishti-envs; Windows %%USERPROFILE%%\\drishti-envs)")
    s.add_argument("--java-home", metavar="DIR", help="the JDK to check (default DRISHTI_JAVA_HOME, then JAVA_HOME, then the PATH)")
    s.add_argument("--python", metavar="FILE", help="the Python to make drishti-console/.venv with (default this one)")
    s.add_argument("--skip-venv", action="store_true", help="leave drishti-console/.venv alone")
    s.add_argument("--reinstall", action="store_true", help="remake drishti-console/.venv and install drishti-console/requirements.txt")
    s.set_defaults(func=cmd_setup, cmd=None)

    b = groups.add_parser("build", help="build jar|modules|all: the server jar (copied into <home>/lib) and the modules (packages in <home>/modules), with Maven, never install")
    bs = b.add_subparsers(dest="cmd", metavar="COMMAND", required=True)
    bj = bs.add_parser("jar", help="build jar: mvn -q clean package -DskipTests -pl :drishti-app -am, then the exec jar into <home>/lib", formatter_class=RAW,
                       description="Builds the server's exec jar with the install's Maven wrapper (Java from the one lookup: --java-home, "
                                   "DRISHTI_JAVA_HOME, JAVA_HOME, the PATH) and copies it into <home>/lib, where every tool finds it.",
                       epilog=f"examples:\n  {P} build jar\n  {P} build jar --offline\n  {P} build jar --no-copy")
    bj.add_argument("--java-home", metavar="DIR", help="the JDK to build with (Java 21+)")
    bj.add_argument("--offline", action="store_true", help="Maven offline (-o)")
    bj.add_argument("--no-copy", action="store_true", help="leave the jar in kernel/drishti-server/target only")
    bj.add_argument("--dry-run", action="store_true", help="print the Maven command; build nothing")
    bj.set_defaults(func=cmd_build)
    for verb, text in (("modules", "the module packages (.dmod) and first-party.yaml into <home>/modules, stamped for the server jar in <home>/lib"),
                       ("all", "the server jar into <home>/lib, then the modules into <home>/modules: one Maven run, everything stamped for that jar")):
        bm = bs.add_parser(verb, help=f"build {verb}: {text}", formatter_class=RAW,
                           description=f"Builds {text}. The server jar holds no module: a module is a package beside it. Modules with Java are built "
                                       "and packed by Maven (packaging/dist, through the one packer tools/modulepack.py); the others are packed from their folders.",
                           epilog=f"examples:\n  {P} build {verb}" + (f"\n  {P} build modules banking\n  {P} build modules source-endur   (no Maven: packed from its folder)" if verb == "modules" else ""))
        if verb == "modules":
            bm.add_argument("ids", nargs="*", metavar="ID", help="only these modules (default: every module folder under <home>/modules)")
        bm.add_argument("--java-home", metavar="DIR", help="the JDK to build with (Java 21+)")
        bm.add_argument("--offline", action="store_true", help="Maven offline (-o)")
        bm.add_argument("--no-copy", action="store_true", help=("leave the jar in kernel/drishti-server/target only" if verb == "all" else argparse.SUPPRESS))
        bm.add_argument("--dry-run", action="store_true", help="print the Maven command; build nothing")
        bm.set_defaults(func=cmd_build)

    for verb, fn in (("start", cmd_start), ("stop", cmd_stop)):
        g = groups.add_parser(verb, help=f"{verb} server|console|all|cluster: " + ("start Drishti (Java 21+, the jar of <home>/lib, logs and pid files under the data folder)"
                                                                                  if verb == "start" else "stop by the pid files only (never by name)"))
        sub = g.add_subparsers(dest="what", metavar="WHAT", required=True)
        for what, text in (("server", "the server, alone or as a cluster node"), ("console", "the console (the web front end)"),
                           ("all", "one server and one console on this machine, in the background")):
            ex = {("start", "server"): f"  {P} start server --demo --background\n  {P} start server --node drishti-b --port 18481 --seed 192.168.1.20:18480 --env-file cluster.env --background",
                  ("start", "console"): f"  {P} start console --demo --background\n  {P} start console --server http://192.168.1.20:18480 --env-file console.env --background",
                  ("start", "all"): f"  {P} start all --demo\n  {P} --settings ~/drishti-envs/dev.ini start all --demo --distribution finance",
                  ("stop", "server"): f"  {P} stop server\n  {P} stop server --all-nodes",
                  ("stop", "console"): f"  {P} stop console", ("stop", "all"): f"  {P} stop all"}[(verb, what)]
            p = sub.add_parser(what, help=f"{verb} {what}: {text}", formatter_class=RAW, description=f"{verb} {what}: {text}.",
                               epilog="examples:\n" + ex + "\nExit codes: 0 ok, 1 failed, 2 a wrong option, 3 already running or the port in use.")
            add_options(p, verb, what)
            p.set_defaults(func=fn, cmd=what)
        c = sub.add_parser("cluster", help=f"{verb} cluster: a cluster on one machine from a cluster file (default deploy/stack/stack.yaml)",
                           formatter_class=RAW, epilog=f"examples:\n  {P} {verb} cluster\n  {P} {verb} cluster --cluster deploy/stack/stack.yaml drishti-b console")
        c.add_argument("--cluster", metavar="FILE", help="the cluster file (default deploy/stack/stack.yaml); its folder holds nodes/ and secrets.env")
        c.add_argument("--data-dir", metavar="DIR", help="the data folder (default DRISHTI_DATA_DIR, else <home>/data)")
        c.add_argument("names", nargs="*", help='only these nodes; "console" for the console')
        c.set_defaults(func=cmd_cluster, cmd="cluster", verb=verb)

    st = groups.add_parser("status", help="status: the resolved home, data, modules and java; then every process started with drishti.py start",
                           formatter_class=RAW, epilog=f"examples:\n  {P} status\n  {P} status --cluster deploy/stack/stack.yaml\n"
                           "Exit codes: 0 every process found runs and answers (or none was started); 1 one is not running or not answering.")
    st.add_argument("--home", help="the install folder (default DRISHTI_HOME, else the folder these tools are in)")
    st.add_argument("--data-dir", action="append", metavar="DIR", help="the data folder (repeatable; default DRISHTI_DATA_DIR, else <home>/data)")
    st.add_argument("--env-file", metavar="FILE", help="read DRISHTI_DATA_DIR from this file")
    st.add_argument("--cluster", metavar="FILE", help="a cluster file: also the members each node sees and the console's servers")
    st.set_defaults(func=cmd_status, cmd=None, verb="status", names=[])
