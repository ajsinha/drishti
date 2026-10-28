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
the repackaged jar under ``drishti-server/target`` run on a free port (or DRISHTI_WB_SERVER_PORT / DRISHTI_WB_CONSOLE_PORT) in a scratch directory; when the jar or a JDK is missing the
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
from java_launch import java_cmd

ROOT = CONSOLE.parent
# The site's own configuration (git-ignored, written by a running server and Admin): a checkout someone runs a server from has them,
# e.g. its connectors (stale ones from older pack templates among them) and Drishti*net membership. The test server never sees them,
# or what it serves (sources, caches, timings) depends on that checkout's history, not on the code under test.
SITE_CONFIG = {"connectors", "net"}
EXAMPLES = ROOT / "docs" / "guides" / "examples"


def _java() -> Path:
    """A JDK 21 or newer (the server is Java 21 bytecode): JAVA_HOME when it is one, else the usual places, 21 (production) first."""
    for home in (os.environ.get("JAVA_HOME", ""), "/usr/lib/jvm/java-21-openjdk-amd64", "/usr/lib/jvm/java-21-openjdk-arm64",
                 "/usr/lib/jvm/java-25-openjdk-amd64", "/usr/lib/jvm/java-25-openjdk-arm64"):
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
    """The console's URL (a string) that also knows the server's scratch directory, ``.work``, and the server's own URL, ``.server``."""

    def __new__(cls, url, work, server=None):
        s = super().__new__(cls, url)
        s.work = Path(work)
        s.server = server
        return s


NETWORK_CHANGED = "net::ERR_NETWORK_CHANGED"


def should_reload(network, reloaded: bool) -> bool:
    """Whether a wait that timed out is worth one reload: Chromium cancels the requests in flight when the machine's network
    interfaces change (Docker starting or stopping a container does it), so a page can sit unstarted with ERR_NETWORK_CHANGED in
    its failed requests. Once per page, and only for that cause: any other timeout is a real failure."""
    return (not reloaded) and any(NETWORK_CHANGED in str(n) for n in (network or []))


def free_port(env: str = "") -> int:
    """A free port, or the one the environment variable ``env`` names (a run that must stay on fixed scratch ports sets DRISHTI_WB_SERVER_PORT and
    DRISHTI_WB_CONSOLE_PORT; only one such stack may then be up at a time)."""
    if env and os.environ.get(env):
        return int(os.environ[env])
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def jar() -> Path | None:
    found = sorted((ROOT / "drishti-server" / "target").glob("drishti-server-*-exec.jar"), key=lambda p: p.stat().st_mtime)
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

    port = free_port("DRISHTI_WB_SERVER_PORT")
    work = work or tmp_path_factory.mktemp("wb-server")
    if (ROOT / "config").exists():                 # the server reads config beside where it runs; its data stays in the scratch directory
        (work / "config").mkdir()
        for sub in (ROOT / "config").iterdir():    # each committed part linked; the site's own parts never (SITE_CONFIG)
            if sub.name not in SITE_CONFIG:
                (work / "config" / sub.name).symlink_to(sub)
        (work / "config" / "connectors").mkdir()   # empty: the server writes the packs' connector files there at its first start, as in a
                                                   # clean checkout, and a deployed test pack's file lands in the scratch copy
    env = {"PATH": os.environ.get("PATH", ""), "HOME": str(work), "DRISHTI_PORT": str(port), **extra_env}
    proc = subprocess.Popen(java_cmd(str(JAVA), ["-Xmx768m", "-jar", str(jar())], tmp=work / "jvm-tmp"), cwd=work, env=env, stdout=(work / "server.log").open("w"), stderr=subprocess.STDOUT)
    try:
        # a server that is still starting is waited for (on a loaded machine a cold start takes minutes): only one that exited is skipped, and one
        # that never answers fails rather than skipping every test of the file in silence
        deadline = time.monotonic() + 600
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
            pytest.fail("the server did not answer within 10 minutes: " + (work / "server.log").read_text()[-400:])
        data = load_settings(CONSOLE / "config").as_dict()
        data.setdefault("backend", {})["url"] = f"http://127.0.0.1:{port}"
        if auth:
            data["auth"] = {**data.get("auth", {}), "enabled": True, "session_secret": "s" * 40, "token_secret": TOKEN_SECRET, "secure_cookie": False}
        app = create_app(Settings(data))
        cport = free_port("DRISHTI_WB_CONSOLE_PORT")
        server = uvicorn.Server(uvicorn.Config(app, host="127.0.0.1", port=cport, log_level="warning", timeout_graceful_shutdown=2))
        thread = threading.Thread(target=server.run, daemon=True)
        thread.start()
        deadline = time.monotonic() + 20
        while not server.started and time.monotonic() < deadline:
            time.sleep(0.05)
        assert server.started, "the console did not start"
        yield Stack(f"http://127.0.0.1:{cport}", work, f"http://127.0.0.1:{port}")
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
