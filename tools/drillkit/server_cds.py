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
"""The test servers' class-data archive (AppCDS; docs/architecture/TEST_HARNESS.md, "Faster server starts").

  server_cds.py build --out DIR [--java JAVA]
      Extracts the built server jar (Spring Boot's tools jar mode: the application jar beside its libraries, which class-data sharing
      can map; the nested jar cannot) into DIR/app, starts it once until it answers, stops it, and keeps the classes it loaded in
      DIR/app.jsa. Prints DIR on success. The drill builds it once, after the gate's build, and the browser tests' servers start from it
      (DRISHTI_TEST_SERVER_CDS=DIR, read by drishti-console/tests/wb_live.py): about a quarter of each start (9.4 s to 6.9 s idle).

A server started this way runs the same classes from the same jars; only where the JVM reads them from changes. Spring AOT was left out:
the module host registers beans at run time from each module's package, which an ahead-of-time bean graph cannot know.
Standard library only.
"""
from __future__ import annotations

import argparse
import os
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
from javacmd import java_cmd  # noqa: E402

FLAGS = ["-Xshare:auto", "-Xlog:cds=off", "-Xlog:cds+dynamic=off"]   # an archive that does not match is ignored, quietly


def jar() -> Path | None:
    found = sorted((ROOT / "kernel" / "drishti-server" / "target").glob("drishti-server-*-exec.jar"))
    return found[-1] if found else None


def launch_args(cds: Path) -> list[str] | None:
    """The JVM arguments that start the server from the archive in ``cds`` (built by ``build``), or None when it is not there."""
    app = sorted((cds / "app").glob("drishti-server-*-exec.jar")) if (cds / "app").is_dir() else []
    if not app or not (cds / "app.jsa").is_file():
        return None
    return [f"-XX:SharedArchiveFile={cds / 'app.jsa'}", *FLAGS, "-jar", str(app[-1])]


def build(out: Path, java: str) -> int:
    src = jar()
    if src is None:
        print("server_cds: no server jar (the gate's build makes it)", file=sys.stderr)
        return 1
    if out.exists():
        shutil.rmtree(out)
    out.mkdir(parents=True)
    t0 = time.monotonic()
    r = subprocess.run(java_cmd(java, ["-Djarmode=tools", "-jar", str(src), "extract", "--destination", str(out / "app")], tmp=out / "tmp"),
                       capture_output=True, text=True, check=False)
    if r.returncode != 0:
        print("server_cds: extract failed: " + (r.stderr or r.stdout)[-400:], file=sys.stderr)
        return 1
    work = Path(tempfile.mkdtemp(prefix="drishti-cds-", dir=out))
    (work / "config").mkdir()
    for sub in (ROOT / "config").iterdir():          # as the tests' servers see it (wb_live._start_stack)
        if sub.name not in ("connectors", "net"):
            (work / "config" / sub.name).symlink_to(sub)
    (work / "config" / "connectors").mkdir()
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        port = s.getsockname()[1]
    app = sorted((out / "app").glob("drishti-server-*-exec.jar"))[-1]
    env = {"PATH": os.environ.get("PATH", ""), "HOME": str(work), "DRISHTI_PORT": str(port), "DRISHTI_DEMO_ENABLED": "false",
           "DRISHTI_OFFLINE": "true"}
    cmd = java_cmd(java, ["-Xmx768m", f"-XX:ArchiveClassesAtExit={out / 'app.jsa'}", "-Xlog:cds=off", "-Xlog:cds+dynamic=off", "-jar", str(app)],
                   tmp=work / "jvm-tmp")
    with (work / "server.log").open("w") as log:
        proc = subprocess.Popen(cmd, cwd=work, env=env, stdout=log, stderr=subprocess.STDOUT)
        try:
            deadline = time.monotonic() + 300
            while time.monotonic() < deadline and proc.poll() is None:
                try:
                    urllib.request.urlopen(f"http://127.0.0.1:{port}/api/v1/rachana/schema", timeout=2).read(10)
                    break
                except urllib.error.HTTPError:
                    break
                except OSError:
                    time.sleep(0.5)
        finally:
            proc.terminate()                           # a normal exit: the JVM writes the archive as it ends
            try:
                proc.wait(120)
            except subprocess.TimeoutExpired:
                proc.kill()
    shutil.rmtree(work, ignore_errors=True)
    if launch_args(out) is None:
        print("server_cds: no archive written (the server did not start or did not exit cleanly)", file=sys.stderr)
        return 1
    print(f"server_cds: {out} ({(out / 'app.jsa').stat().st_size // 2**20} MB archive) in {time.monotonic() - t0:.0f} s", file=sys.stderr)
    print(out)
    return 0


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(prog="server_cds.py", description="The test servers' class-data archive.")
    sub = p.add_subparsers(dest="cmd", required=True)
    b = sub.add_parser("build")
    b.add_argument("--out", required=True)
    b.add_argument("--java", default=str(Path(os.environ.get("JAVA_HOME", "/usr/lib/jvm/java-21-openjdk-amd64")) / "bin" / "java"))
    a = p.parse_args(argv)
    return build(Path(a.out).resolve(), a.java)


if __name__ == "__main__":
    sys.exit(main())
