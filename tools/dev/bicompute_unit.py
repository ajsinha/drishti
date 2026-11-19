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

"""``drishti.py dev bicompute-unit``: a light check of the pivot engine (drishti-bicompute) without Maven, for when a drill or a benchmark
holds the machine: javac and the JUnit launcher with -Xmx768m, against the jars of the last installed release in ~/.m2 (drishti-api,
drishti-common) and this checkout's ErrorCode. Never a substitute for the module's Maven build; a quick compile and the named test classes only.

    python tools/drishti.py dev bicompute-unit                       compile the module's main and test sources
    python tools/drishti.py dev bicompute-unit LivePivotTest ...     and run these test classes (simple names, package com.ash.drishti.bicompute)
    python tools/drishti.py dev bicompute-unit --server [Test ...]   also compile the server's pivot package against the installed release's jars

DRISHTI_M2_VERSION picks the release (default: the newest installed drishti-common); Java is found as by every Drishti tool (--java,
drishti.java.home, DRISHTI_JAVA_HOME, JAVA_HOME, the PATH), javac beside it. Its temporary folder is removed on exit."""
from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

TOOLS = Path(__file__).resolve().parent.parent
ROOT = TOOLS.parent
if str(TOOLS) not in sys.path:
    sys.path.insert(0, str(TOOLS))
import javacmd  # noqa: E402  (the one way a Drishti tool starts Java)

PKG = "com.ash.drishti.bicompute"
HEAP = "-Xmx768m"
#: the main class path: (group path, version or None for the newest installed)
MAIN = [("com/ash/drishti/drishti-api", "@"), ("com/ash/drishti/drishti-common", "@"), ("org/duckdb/duckdb_jdbc", "1.5.6.0"),
        ("org/slf4j/slf4j-api", "2.0.17"), ("com/fasterxml/jackson/core/jackson-databind", "2.21.4"),
        ("com/fasterxml/jackson/core/jackson-core", "2.21.4"), ("com/fasterxml/jackson/core/jackson-annotations", None),
        ("com/fasterxml/jackson/dataformat/jackson-dataformat-yaml", "2.21.4"), ("org/yaml/snakeyaml", None)]
TEST = [("org/junit/jupiter/junit-jupiter-api", "5.14.4"), ("org/junit/jupiter/junit-jupiter-engine", "5.14.4"),
        ("org/junit/jupiter/junit-jupiter-params", "5.14.4"), ("org/junit/platform/junit-platform-commons", "1.14.4"),
        ("org/junit/platform/junit-platform-engine", "1.14.4"), ("org/junit/platform/junit-platform-launcher", "1.14.4"),
        ("org/opentest4j/opentest4j", "1.3.0"), ("org/apiguardian/apiguardian-api", "1.1.2"), ("org/assertj/assertj-core", "3.27.7"),
        ("net/bytebuddy/byte-buddy", None)]
RUNNER = """import org.junit.platform.launcher.*;
import org.junit.platform.launcher.core.*;
import org.junit.platform.launcher.listeners.*;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
public class Run {
    public static void main(String[] a) throws Exception {
        var b = LauncherDiscoveryRequestBuilder.request();
        for (String c : a) b.selectors(selectClass(Class.forName("%s." + c)));
        var l = LauncherFactory.create();
        var s = new SummaryGeneratingListener();
        l.execute(b.build(), s);
        s.getSummary().printTo(new java.io.PrintWriter(System.out));
        s.getSummary().printFailuresTo(new java.io.PrintWriter(System.out), 30);
        System.exit(s.getSummary().getTotalFailureCount() == 0 ? 0 : 1);
    }
}
""" % PKG


class UnitError(RuntimeError):
    """A jar or a tool is missing: said in words, exit 1."""


def _vkey(v: str) -> list:
    return [(0, int(x), "") if x.isdigit() else (1, 0, x) for x in re.split(r"[.-]", v)]   # as sort -V: numbers by value


def newest(folder: Path) -> str:
    """The newest version folder (numeric) under ``folder``."""
    vs = sorted((p.name for p in folder.iterdir() if p.is_dir() and p.name[:1].isdigit()), key=_vkey) if folder.is_dir() else []
    if not vs:
        raise UnitError(f"nothing installed in {folder}")
    return vs[-1]


def jar(m2: Path, group: str, version: str | None) -> str:
    """The plain jar (no -sources, -javadoc, -tests) of ``group`` at ``version`` (None: the newest installed)."""
    folder = m2 / group / (version or newest(m2 / group))
    jars = sorted(p for p in folder.glob("*.jar") if not re.search(r"-(sources|javadoc|tests)\.jar$", p.name)) if folder.is_dir() else []
    if not jars:
        raise UnitError(f"no jar in {folder}")
    return str(jars[0])


def classpaths(m2: Path, ver: str) -> tuple[str, str]:
    """(the main class path, the test class path); "@" is the release version ``ver``."""
    def cp(items):
        return os.pathsep.join(jar(m2, g, ver if v == "@" else v) for g, v in items)
    return cp(MAIN), cp(TEST)


def sources(folder: Path) -> list[str]:
    return sorted(str(p) for p in folder.rglob("*.java"))


def run(tests: list[str], server: bool = False, java: str | None = None, m2: Path | None = None, env=None, say=print) -> int:
    env = os.environ if env is None else env
    m2 = m2 or Path.home() / ".m2" / "repository"
    try:
        ver = (env.get("DRISHTI_M2_VERSION") or "").strip() or newest(m2 / "com/ash/drishti/drishti-common")
        cp, tcp = classpaths(m2, ver)
        java = javacmd.find_java(java)
        javac = str(Path(java).with_name("javac.exe" if os.name == "nt" else "javac"))
        if not Path(javac).is_file():
            raise UnitError(f"no javac beside {java}: point drishti.java.home (or DRISHTI_JAVA_HOME, JAVA_HOME) at a JDK, not a JRE")
    except (UnitError, javacmd.JavaError) as e:
        say(f"bicompute-unit: {e}", file=sys.stderr)
        return 1
    compile_ = [javac] + [f"-J{HEAP}", "-nowarn", "-encoding", "UTF-8", "--release", "21"]   # javac compiles; it unpacks no native library
    with tempfile.TemporaryDirectory(prefix="drishti-bicompute-unit.") as tmp:
        work = Path(tmp)
        main_out, test_out = work / "main", work / "test"
        main_src = [str(ROOT / "kernel/drishti-api/src/main/java/com/ash/drishti/api/error/ErrorCode.java"),
                    *sources(ROOT / "compute/drishti-bicompute/src/main/java")]
        if subprocess.run([*compile_, "-d", str(main_out), "-cp", cp, *main_src]).returncode:
            return 1
        say("main: compiled")
        if subprocess.run([*compile_, "-d", str(test_out), "-cp", os.pathsep.join([str(main_out), cp, tcp]),
                           *sources(ROOT / "compute/drishti-bicompute/src/test/java")]).returncode:
            return 1
        say("test: compiled")
        if server:
            code = _server(m2, ver, work, main_out, compile_, say)
            if code:
                return code
        if not tests:
            return 0
        (work / "Run.java").write_text(RUNNER, encoding="utf-8")
        run_cp = os.pathsep.join([str(main_out), str(test_out), str(ROOT / "compute/drishti-bicompute/src/test/resources"), cp, tcp])
        return subprocess.run(javacmd.java_cmd(java, [HEAP, "-cp", run_cp, str(work / "Run.java"), *tests], tmp=work / "jvm")).returncode


def _server(m2: Path, ver: str, work: Path, main_out: Path, compile_: list, say) -> int:
    """The server's pivot package (its sources come from the modules: PivotSourceProvider) against the installed release's executable jar
    (drishti-app's: the core with the domain modules) and its libraries."""
    exec_jar = m2 / f"com/ash/drishti/drishti-app/{ver}/drishti-app-{ver}-exec.jar"
    plain = m2 / f"com/ash/drishti/drishti-server/{ver}/drishti-server-{ver}.jar"
    libs_dir = work / "libs"
    say(f"server: unpacking the libraries of {exec_jar}")
    try:
        with zipfile.ZipFile(exec_jar) as z:
            for n in z.namelist():
                if n.startswith("BOOT-INF/lib/") and n.endswith(".jar"):
                    z.extract(n, libs_dir)
    except OSError as e:
        say(f"server: {e}", file=sys.stderr)
        return 1
    libs = sorted(str(p) for p in (libs_dir / "BOOT-INF" / "lib").glob("*.jar")
                  if not re.match(r"drishti-(bicompute|common)-\d", p.name))
    if not libs:
        say("no libraries", file=sys.stderr)
        return 1
    say(f"server: compiling against {len(libs)} libraries")
    srv = ROOT / "kernel/drishti-server/src/main/java/com/ash/drishti/server"
    code = subprocess.run([*compile_, "-proc:none", "-d", str(work / "server"),
                           "-cp", os.pathsep.join([str(main_out), str(plain), *libs, jar(m2, "com/ash/drishti/drishti-common", ver)]),
                           *sources(srv / "pivot"), str(srv / "net" / "PeerCalls.java")]).returncode
    if code:
        say(f"server pivot package: javac failed ({code})", file=sys.stderr)
        return 1
    say("server pivot package: compiled")
    return 0


def add_arguments(p: argparse.ArgumentParser) -> None:
    p.add_argument("tests", nargs="*", metavar="TEST", help=f"test classes to run (simple names in {PKG})")
    p.add_argument("--server", action="store_true", help="also compile the server's pivot package against the installed release's jars")
    p.add_argument("--java", help="the java binary (default: drishti.java.home, DRISHTI_JAVA_HOME, JAVA_HOME, then java on the PATH); javac beside it")


def register(groups) -> None:
    """Adds the ``dev`` group: ``dev bicompute-unit``."""
    g = groups.add_parser("dev", help="dev bicompute-unit: developer checks that need no Maven", description="Developer checks that need no Maven")
    sub = g.add_subparsers(dest="cmd", metavar="COMMAND", required=True)
    p = sub.add_parser("bicompute-unit", help="dev bicompute-unit: compile the pivot engine and run named tests without Maven (-Xmx768m)",
                       description=__doc__.split("\n\n")[0], epilog=__doc__.split("\n\n")[1], formatter_class=argparse.RawDescriptionHelpFormatter)
    add_arguments(p)
    p.set_defaults(func=lambda a, extra=None: run(a.tests, a.server, a.java))


if __name__ == "__main__":
    ap = argparse.ArgumentParser(prog="bicompute_unit.py", description=__doc__.split("\n\n")[0])
    add_arguments(ap)
    a = ap.parse_args()
    sys.exit(run(a.tests, a.server, a.java))
