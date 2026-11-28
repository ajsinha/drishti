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
Python only, the same on Linux, macOS and Windows. ``--settings`` is drishti.py's own
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
    p.add_argument("--demo", action="store_true", help="the demo profile, config/profiles/demo.ini (the offline demo on the lakes; the same file the IDE's demo launcher names), with DEVELOPMENT secrets and the drishti-dev-admin account: NOT FOR PRODUCTION")
    p.add_argument("--distribution", metavar="NAME", help="risk, finance, genomics, all, or a file path (DRISHTI_DISTRIBUTION); docs/architecture/KERNEL_AND_MODULES.md")
    p.add_argument("--background", action="store_true", help="detach: log and pid file under the data folder (logs/, run/)")
    if what == "server":
        p.add_argument("--port", type=int, help="the server's port (default server.port of the settings files, else DRISHTI_PORT, else 18480)")
        p.add_argument("--heap", metavar="SIZE", help="the heap limit, -Xmx (default drishti.launch.heap of the settings files: 4g in the demo profile; else 2g)")
        p.add_argument("--jar", metavar="FILE", help="a server jar for a one-off run (default the newest in <home>/lib: drishti.py build jar)")
        p.add_argument("--java-home", metavar="DIR", help="the JDK (default drishti.java.home of --settings, DRISHTI_JAVA_HOME, then JAVA_HOME, then java on the PATH; Java 21+)")
        p.add_argument("--packs", metavar="LIST", help="the packs to load, beating any settings file (default drishti.packs.enabled of the settings files, else DRISHTI_PACKS, else the packs of the modules that are on)")
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
        # moved to setup (start never makes a Python environment): kept only to say so
        p.add_argument("--python", metavar="FILE", help=argparse.SUPPRESS)
        p.add_argument("--reinstall", action="store_true", help=argparse.SUPPRESS)
        p.add_argument("--wait", type=int, default=90, metavar="SECONDS", help="how long to wait for the health answer (default 90)")
        p.add_argument("--name", help="a name for this console when several run from one data folder (run/console-<name>.pid)")
    else:
        p.add_argument("--server-port", type=int, help="the server's port (default server.port of the settings files, else DRISHTI_PORT, else 18480)")
        p.add_argument("--console-port", type=int, help="the console's port (default DRISHTI_CONSOLE_PORT, else console.server.port of the "
                       "settings files, else 17480)")
        p.add_argument("--heap", metavar="SIZE", help="the server's heap (-Xmx)")
        p.add_argument("--java-home", metavar="DIR", help="the JDK (default drishti.java.home of --settings, DRISHTI_JAVA_HOME, then JAVA_HOME, then the PATH)")


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
        text = str(e)
        print(text if text.startswith("[FAIL]") else f"[FAIL] {text}", file=sys.stderr, flush=True)
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
    # on one machine the console talks to the server over loopback (no proxy, no VPN or adapter change in the way); the addresses
    # printed for people stay the real ones. The ports in each one's own order (serverstart.start, consolestart.start), so start all and
    # start server / start console pick the same ones: server.port and console.server.port of the settings files count here too
    keys = serverstart.profiled(a)[1]
    env = os.environ
    if getattr(a, "env_file", None):
        env = dict(os.environ)
        procs.load_env_file(a.env_file, env)
    sport = int(a.server_port or (keys.get("server.port") or "").strip() or env.get("DRISHTI_PORT") or procs.DEFAULT_SERVER_PORT)
    cport = int(a.console_port or env.get("DRISHTI_CONSOLE_PORT") or (keys.get("console.server.port") or "").strip() or procs.DEFAULT_CONSOLE_PORT)
    console = _console_ns(a, port=cport, background=True, server=[f"http://127.0.0.1:{sport}"])
    rc = _run(lambda ns: consolestart.prepared_python(ns) and 0, console)   # the console's Python first: an unprepared one stops before the server starts
    if rc:
        return rc
    if procs.port_in_use(cport):                  # both ports before either starts (no server left without its console)
        print(procs.fail(f"port {cport}", "is in use by another process", f"stop it, or {procs.command('start', 'all', '--console-port', '<another port>')}"),
              file=sys.stderr, flush=True)
        return 3
    rc = _run(serverstart.start, _server_ns(a, port=sport, background=True))
    if rc:
        return rc
    return _run(consolestart.start, console)


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
    """start|stop cluster, status --cluster: tools/launch/clusterctl.py, which reads the cluster file with PyYAML: run with the one Python
    (DRISHTI_PYTHON, drishti.python) when this one has no PyYAML."""
    try:
        import yaml  # noqa: F401
    except ModuleNotFoundError:
        py, why = procs.tools_python()
        if Path(py).exists() and Path(sys.executable).resolve() != Path(py).resolve():
            return subprocess.run([py, str(HERE.parent / "drishti.py"), *a._argv]).returncode
        raise SystemExit("[FAIL] the cluster file needs PyYAML, which this Python has not got. Fix: " + procs.setup_line() + "  "
                         "(setup checks the libraries of the Python you run Drishti with; you install them with the pip commands it prints)") from None
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
    known = {procs.read_pid(f)[0] for data in dirs for f in (Path(data) / "run").glob("*.pid")}
    home = javacmd.launch_roots(None, None, None, javacmd.settings()[1])["home"][0]
    for p in procs.drishti_processes(home):           # started from an IDE or by hand: seen too (LAUNCHER_REVIEW.md F20)
        if p["pid"] in known:
            continue
        any_found = True
        health = f"http://127.0.0.1:{p['port']}" + ("/actuator/health" if p["kind"] == "server" else "/healthz")
        answer = "UP" if procs.http_ok(health) else "no answer"
        say(f"{p['kind'] + ' (elsewhere)':<22} {p['pid']:>8}  {'running':<10} {p['port']:<6} {answer:<10} {'-':<16} http://127.0.0.1:{p['port']}  "
            "(started elsewhere: an IDE or by hand; stop it there)")
    if not any_found:
        say("(no Drishti process of this install runs)")
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
    import modulesbuild
    if a.cmd == "sdk":
        import sdkbuild
        return _run(sdkbuild.build_sdk, a)
    return _run(modulesbuild.build, a, a.cmd)


def register(groups, add) -> None:
    """Adds setup, build, start, stop and status to drishti.py's command line."""
    s = groups.add_parser("setup", help="setup [drishti|libs]: prepare this machine and CHECK the libraries of the Python you run Drishti with "
                                         "(setup installs nothing and makes no virtual environment)",
                          description=__import__("envsetup").__doc__, formatter_class=RAW,
                          epilog=f"examples:\n  {P} setup\n  {P} setup --check\n  {P} setup --wheels %USERPROFILE%\\drishti-wheels\n  {P} setup drishti --data-dir D:/drishti-data\n"
                                 f"  {P} setup libs     (only the libraries of this Python, checked; you install them with the two pip commands it prints)")
    s.add_argument("part", nargs="?", choices=("drishti", "libs"),
                   help="drishti: the settings folder, dev.ini, data folder, JVM argument files, Java, jar and modules checks; libs: the libraries of this "
                        "Python (the Lake wheel and requirements.txt, then config/compute/locks/python.lock) checked, never installed: what is missing is a [FAIL] with the "
                        "two pip commands you run yourself; none: drishti, then libs")
    s.add_argument("--check", action="store_true", help="only report; change nothing (exit 1 when something needs doing); also verifies the libraries name by name")
    s.add_argument("--home", help="the install folder (default DRISHTI_HOME, else the folder these tools are in)")
    s.add_argument("--data-dir", metavar="DIR", help="the data folder to prepare (default DRISHTI_DATA_DIR, else <home>/data)")
    s.add_argument("--envs-dir", metavar="DIR", help="the settings folder (default ~/drishti-envs; Windows %%USERPROFILE%%\\drishti-envs)")
    s.add_argument("--java-home", metavar="DIR", help="the JDK to check (default drishti.java.home of --settings, DRISHTI_JAVA_HOME, then JAVA_HOME, then the PATH)")
    s.add_argument("--system", action="store_true", help="accepted for old commands and ignored (setup always uses the Python you run it with)")
    s.add_argument("--wheels", metavar="FOLDER", help="the unzipped offline wheel pack folder (a .zip is never unzipped: unzip it into ~/drishti-wheels); it only names the pack in the printed pip "
                                                      "commands (setup installs nothing); default DRISHTI_WHEELS, else ~/drishti-wheels, else the pack zip in your Downloads folder")
    s.set_defaults(func=cmd_setup, cmd=None)

    b = groups.add_parser("build", help="build jar|modules|all|sdk: the server jar (copied into <home>/lib) and the modules (packages in <home>/modules), with Maven, never install")
    bs = b.add_subparsers(dest="cmd", metavar="COMMAND", required=True)
    bj = bs.add_parser("jar", help="build jar: mvn -q clean package -DskipTests -pl :drishti-app -am (Maven installs the exec jar into <home>/lib)", formatter_class=RAW,
                       description="Builds the server's exec jar with the install's Maven wrapper (Java from the one lookup: --java-home, "
                                   "drishti.java.home, DRISHTI_JAVA_HOME, JAVA_HOME, the PATH); Maven itself installs it into <home>/lib, where every tool finds it.",
                       epilog=f"examples:\n  {P} build jar\n  {P} build jar --offline\n  {P} build jar --no-copy")
    bj.add_argument("--java-home", metavar="DIR", help="the JDK to build with (Java 21+)")
    bj.add_argument("--offline", action="store_true", help="Maven offline (-o)")
    bj.add_argument("--no-copy", action="store_true", help="install nothing: the jar stays in kernel/drishti-server/target")
    bj.add_argument("--dry-run", action="store_true", help="print the Maven command; build nothing")
    bj.set_defaults(func=cmd_build)
    bk = bs.add_parser("sdk", help="build sdk: the Module SDK folder <home>/sdk (a Maven file repository and the parent pom), for building a module with no kernel source",
                       formatter_class=RAW, description=__import__("sdkbuild").__doc__, epilog=f"examples:\n  {P} build sdk\n  {P} build sdk --offline")
    bk.add_argument("--java-home", metavar="DIR", help="the JDK to build with (Java 21+)")
    bk.add_argument("--offline", action="store_true", help="Maven offline (-o)")
    bk.add_argument("--no-maven", action="store_true", help="no Maven: assemble from the jars and poms an earlier build left in target/ (the archetype's jar is then left out)")
    bk.add_argument("--out", metavar="DIR", help="write the SDK folder here instead of <home>/sdk")
    bk.add_argument("--dry-run", action="store_true", help="print the Maven command; build nothing")
    bk.set_defaults(func=cmd_build)
    for verb, text in (("modules", "the module packages (.dmod) and first-party.yaml into <home>/modules, stamped for the server jar in <home>/lib"),
                       ("all", "the server jar into <home>/lib, then the modules into <home>/modules: one Maven run, everything stamped for that jar")):
        bm = bs.add_parser(verb, help=f"build {verb}: {text}", formatter_class=RAW,
                           description=f"Builds {text}. The server jar holds no module: a module is a package beside it. One Maven run (packaging/dist) "
                                       "builds the modules' jars, packs every module with the one packer (module pack of the server classes, Java), runs the "
                                       "conformance checks and installs the packages (an older version of a module removed, first-party.yaml written).",
                           epilog=f"examples:\n  {P} build {verb}" + (f"\n  {P} build modules banking source-endur   (only those packages are installed)" if verb == "modules" else f"\n  {P} build all --no-copy"))
        if verb == "modules":
            bm.add_argument("ids", nargs="*", metavar="ID", help="only these modules (default: every module folder under <home>/modules)")
        bm.add_argument("--java-home", metavar="DIR", help="the JDK to build with (Java 21+)")
        bm.add_argument("--offline", action="store_true", help="Maven offline (-o)")
        bm.add_argument("--no-copy", action="store_true", help="install nothing: the jar stays in kernel/drishti-server/target, the packages in packaging/dist/target/dmod")
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

    __import__("nodecmd").register(groups)

    lg = groups.add_parser("launchers", help="launchers [--check|--machine|--list|--run NAME]: the IDE launchers and IDE_GUIDE section 4 from tools/launch/launchers.yaml; this machine's JVM arguments files",
                           description=__import__("launchers").__doc__, formatter_class=RAW,
                           epilog=f"examples:\n  {P} launchers\n  {P} launchers --check\n  {P} launchers --list\n  {P} launchers --run 'Drishti: run server (demo)'\n  {P} --settings ~/drishti-envs/dev.ini launchers --machine")
    lg.add_argument("--check", action="store_true", help="change nothing; exit 1 naming each committed launcher (or IDE_GUIDE section 4) that differs from the table")
    lg.add_argument("--machine", action="store_true", help="write this machine's JVM arguments files, <settings folder>/jvm-<profile>.args (setup runs it)")
    lg.add_argument("--envs-dir", metavar="DIR", help="the settings folder (default ~/drishti-envs)")
    lg.add_argument("--list", action="store_true", help="print every committed launcher by group with the exact command it runs and its working folder (read from the run-configs/*.run.xml files)")
    lg.add_argument("--run", metavar="NAME", help='run the launcher called NAME (the whole name or a prefix naming one), exactly as its file holds it: its folder, command and environment; e.g. --run "Drishti: run server (demo)"')
    lg.add_argument("--input", metavar="VALUE", help="with --run: the value a launcher asks for in the IDE (for example the file to load)")
    lg.set_defaults(func=lambda a, extra: __import__("launchers").main(a), cmd=None)

    up = groups.add_parser("upgrade", help="upgrade check <new-release-dir>: read-only; what to do before switching to a new release (Python pins, settings keys, modules)",
                           formatter_class=RAW, description=__import__("upgradecheck").__doc__,
                           epilog=f"examples:\n  {P} --settings /etc/drishti/prod.ini upgrade check /opt/drishti/releases/2.9.12 --python /opt/drishti/python/2.9.12/bin/python3.13\n"
                                  f"  {P} upgrade check /opt/drishti/releases/2.9.12 --current /opt/drishti/current --json")
    ups = up.add_subparsers(dest="cmd", metavar="COMMAND", required=True)
    uc = ups.add_parser("check", help="check <new-release-dir>: compare the new release with the running one; change nothing (exit 1: something blocks the switch)", formatter_class=RAW)
    uc.add_argument("new_release", metavar="NEW_RELEASE_DIR", help="the new release's folder (the one holding drishti-console/ and tools/, or its parent holding drishti/)")
    uc.add_argument("--current", metavar="DIR", help="the running release (default drishti.home of --settings, else DRISHTI_HOME)")
    uc.add_argument("--python", metavar="PY", help="the Python made for the new release (default drishti.python of --settings); it is only asked what it has installed")
    uc.add_argument("--json", action="store_true", help="the report as JSON")
    uc.set_defaults(func=lambda a, extra: _run(__import__("upgradecheck").main, a))

    st = groups.add_parser("status", help="status: the resolved home, data, modules and java; then every process started with drishti.py start",
                           formatter_class=RAW, epilog=f"examples:\n  {P} status\n  {P} status --cluster deploy/stack/stack.yaml\n"
                           "Exit codes: 0 every process found runs and answers (or none was started); 1 one is not running or not answering.")
    st.add_argument("--home", help="the install folder (default DRISHTI_HOME, else the folder these tools are in)")
    st.add_argument("--data-dir", action="append", metavar="DIR", help="the data folder (repeatable; default DRISHTI_DATA_DIR, else <home>/data)")
    st.add_argument("--env-file", metavar="FILE", help="read DRISHTI_DATA_DIR from this file")
    st.add_argument("--cluster", metavar="FILE", help="a cluster file: also the members each node sees and the console's servers")
    st.set_defaults(func=cmd_status, cmd=None, verb="status", names=[])
