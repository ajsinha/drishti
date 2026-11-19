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

"""A scratch Drishti server for the evidence tools: a server jar and an install folder (a checkout of the tag, with its .dmod files) run on
a free port with its own data folder. It refuses the ports of the owner's stack (18480, 18791, 18792, 17480) and any port that answers.

    with ScratchServer(home, jar, data, 19183) as s:   # waits for readiness, stops (and removes nothing) on exit
        s.url"""
from __future__ import annotations

import os
import socket
import subprocess
import time
import urllib.request
from pathlib import Path

FORBIDDEN = frozenset({18480, 18791, 18792, 17480, 5432, 9092})


def listening(port: int) -> bool:
    with socket.socket() as s:
        s.settimeout(0.3)
        return s.connect_ex(("127.0.0.1", port)) == 0


class ScratchServer:
    def __init__(self, home: Path, jar: Path, data: Path, port: int, heap: str = "3g", java: str | None = None):
        assert port not in FORBIDDEN, f"port {port} belongs to a running stack"
        self.home, self.jar, self.data, self.port, self.heap = Path(home), Path(jar), Path(data), port, heap
        self.java = java or os.environ.get("JAVA_HOME", "/usr/lib/jvm/java-21-openjdk-amd64") + "/bin/java"
        self.proc = None

    @property
    def url(self) -> str:
        return f"http://127.0.0.1:{self.port}"

    def __enter__(self) -> "ScratchServer":
        if listening(self.port):
            raise RuntimeError(f"port {self.port} is in use: stop whatever listens there")
        self.data.mkdir(parents=True, exist_ok=True)
        (self.data / "jvmtmp").mkdir(exist_ok=True)
        env = dict(os.environ, DRISHTI_HOME=str(self.home), DRISHTI_DATA_DIR=str(self.data), DRISHTI_SECURITY_ENABLED="false")
        log = open(self.data / "server.log", "ab")
        self.proc = subprocess.Popen([self.java, f"-Djava.io.tmpdir={self.data / 'jvmtmp'}", f"-Xmx{self.heap}", "-jar", str(self.jar), f"--server.port={self.port}"],
                                     cwd=self.home, env=env, stdout=log, stderr=subprocess.STDOUT)
        self.wait_ready()
        return self

    def wait_ready(self, seconds: float = 600.0) -> None:
        end = time.time() + seconds
        while time.time() < end:
            if self.proc.poll() is not None:
                raise RuntimeError(f"the server exited with {self.proc.returncode}: see {self.data / 'server.log'}")
            try:
                urllib.request.urlopen(self.url + "/actuator/health/readiness", timeout=2).read()
                return
            except Exception:  # noqa: BLE001 - not up yet
                time.sleep(0.5)
        raise RuntimeError("the server did not become ready")

    def __exit__(self, *exc) -> None:
        if self.proc and self.proc.poll() is None:
            self.proc.terminate()
            try:
                self.proc.wait(30)
            except subprocess.TimeoutExpired:
                self.proc.kill()
