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

"""A real Drishti server and a console in front of it, for the workbench's browser tests (the stand-in server cannot apply
operations; the real one is what makes "the YAML changes and the comments stay" a true statement).

Not collected as tests (the name does not start with ``test_``); ``test_workbench_browser.py`` imports the fixtures. The server is
the repackaged jar under ``drishti-server/target`` run on a free port in a scratch directory; when the jar or a JDK is missing the
tests that need it are skipped, never failed."""
import json
import os
import re
from pathlib import Path
import socket
import subprocess
import threading
import time
import urllib.error
import urllib.request

import pytest

from conftest import CONSOLE

ROOT = CONSOLE.parent
EXAMPLES = ROOT / "docs" / "guides" / "examples"


def _java() -> Path:
    """A JDK 21 or newer (the server is Java 21 bytecode): JAVA_HOME when it is one, else the usual places, 25 first."""
    for home in (os.environ.get("JAVA_HOME", ""), "/usr/lib/jvm/java-25-openjdk-amd64", "/usr/lib/jvm/java-25-openjdk-arm64",
                 "/usr/lib/jvm/java-21-openjdk-amd64", "/usr/lib/jvm/java-21-openjdk-arm64"):
        rel = Path(home) / "release" if home else None
        m = re.search(r'JAVA_VERSION="(\d+)', rel.read_text()) if rel and rel.exists() else None
        if m and int(m.group(1)) >= 21:
            return Path(home) / "bin" / "java"
    return Path("/nonexistent/java")


JAVA = _java()

# How long a browser test waits for the workbench to draw (milliseconds). Generous on purpose: the drill and the builders run
# the browser suite beside Maven builds, and a loaded machine once took over 20 s to draw a preview that takes 1 s alone.
BROWSER_WAIT_MS = int(os.environ.get("DRISHTI_BROWSER_WAIT_MS", "45000"))
TOKEN_SECRET = "browser-test-token-secret-0123456789abcdef"


class Stack(str):
    """The console's URL (a string) that also knows the server's scratch directory, ``.work``."""

    def __new__(cls, url, work):
        s = super().__new__(cls, url)
        s.work = Path(work)
        return s


def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def jar() -> Path | None:
    found = sorted((ROOT / "drishti-server" / "target").glob("drishti-server-*-exec.jar"))
    return found[-1] if found else None


@pytest.fixture(scope="session")          # one server and console for every browser file that needs no special set-up
def live_console(tmp_path_factory):
    """The console's URL, with a real server behind it."""
    yield from _stack(tmp_path_factory, {})


@pytest.fixture(scope="module")
def live_ship_console(tmp_path_factory):
    """As :func:`live_console`, with Studio saving and development file binding on, Sutras written under the scratch directory (step 8)."""
    work = tmp_path_factory.mktemp("wb-ship-server")
    yield from _stack(tmp_path_factory, {"DRISHTI_STUDIO_SAVE": "true", "DRISHTI_BUILDER_FILE_BINDING": "true", "DRISHTI_SUTRAS": str(work / "sutras"), "DRISHTI_BUILDER_DEV_DIR": str(work / "dev-sutras")}, work)


@pytest.fixture(scope="module")
def live_auth_console(tmp_path_factory):
    """As :func:`live_console` with sign-in on at the server and the console (the development administrator: drishti-dev-admin / drishti-dev-admin123)."""
    yield from _stack(tmp_path_factory, {"DRISHTI_SECURITY_ENABLED": "true", "DRISHTI_TOKEN_SECRET": TOKEN_SECRET}, auth=True)


def _stack(tmp_path_factory, extra_env, work=None, auth=False):
    if jar() is None or not JAVA.exists():
        pytest.skip("needs the built server jar (./mvnw -o package -DskipTests -pl drishti-server -am) and JDK 25")
    import uvicorn

    from core.app import create_app
    from core.config import Settings, load_settings

    port = free_port()
    work = work or tmp_path_factory.mktemp("wb-server")
    for name in ("packs", "config"):                       # the server reads these beside where it runs; its data stays in the scratch directory
        if (ROOT / name).exists():
            (work / name).symlink_to(ROOT / name)
    env = {"PATH": os.environ.get("PATH", ""), "HOME": str(work), "DRISHTI_PORT": str(port), **extra_env}
    proc = subprocess.Popen([str(JAVA), "-Xmx768m", "-jar", str(jar())], cwd=work, env=env, stdout=(work / "server.log").open("w"), stderr=subprocess.STDOUT)
    try:
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            if proc.poll() is not None:
                pytest.skip("the server did not start: " + (work / "server.log").read_text()[-400:])
            try:
                urllib.request.urlopen(f"http://127.0.0.1:{port}/api/v1/rachana/schema", timeout=2).read(10)
                break
            except urllib.error.HTTPError:           # sign-in on: a 401 is the server answering
                break
            except OSError:
                time.sleep(0.5)
        else:
            pytest.skip("the server did not start in time")
        data = load_settings(CONSOLE / "config").as_dict()
        data.setdefault("backend", {})["url"] = f"http://127.0.0.1:{port}"
        if auth:
            data["auth"] = {**data.get("auth", {}), "enabled": True, "session_secret": "s" * 40, "token_secret": TOKEN_SECRET, "secure_cookie": False}
        app = create_app(Settings(data))
        cport = free_port()
        server = uvicorn.Server(uvicorn.Config(app, host="127.0.0.1", port=cport, log_level="warning", timeout_graceful_shutdown=2))
        thread = threading.Thread(target=server.run, daemon=True)
        thread.start()
        deadline = time.monotonic() + 20
        while not server.started and time.monotonic() < deadline:
            time.sleep(0.05)
        assert server.started, "the console did not start"
        yield Stack(f"http://127.0.0.1:{cport}", work)
        server.should_exit = True
        thread.join(10)
    finally:
        proc.terminate()
        try:
            proc.wait(15)
        except subprocess.TimeoutExpired:
            proc.kill()


def post(page, url: str, body: dict) -> dict:
    r = page.request.post(url, data=json.dumps(body), headers={"Content-Type": "application/json"})
    return {**(r.json() if r.headers.get("content-type", "").startswith("application/json") else {}), "http": r.status}


def new_design(page, base: str, name: str, *, sutra: str | None = None, files: dict | None = None, kind: str = "trade") -> str:
    """A Design made through the console: from ``sutra`` or an empty one, with ``files`` ({name: text}) as its samples. Returns its id."""
    made = post(page, base + "/build/designs", {"name": name, "kind": kind, **({"sutra": sutra} if sutra else {"empty": True})})
    assert made["http"] == 201, made
    if files:
        sent = post(page, f"{base}/build/designs/{made['id']}/files", {"files": [{"name": n, "text": t} for n, t in files.items()]})
        assert sent["http"] == 200, sent
    return made["id"]


def showcase_json() -> str:
    return (EXAMPLES / "all-panels-showcase.json").read_text()


def showcase_sutra() -> str:
    return (EXAMPLES / "all-panels-showcase.sutra.yaml").read_text()
