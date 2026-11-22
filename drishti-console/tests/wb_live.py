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
the server jar in ``<home>/lib`` (``python tools/drishti.py build jar`` puts it there) run on a free port (or DRISHTI_WB_SERVER_PORT / DRISHTI_WB_CONSOLE_PORT) in a scratch directory; when the jar or a JDK is missing the
tests that need it are skipped, never failed."""
import atexit
import json
import os
import re
from pathlib import Path
import socket
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request

import pytest

from conftest import CONSOLE
from java_launch import java_cmd, server_jars

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


def launch() -> list[str]:
    """How a test server's JVM finds the server: the class-data archive the drill builds once (DRISHTI_TEST_SERVER_CDS: the extracted jar
    and its archive, tools/drillkit/server_cds.py; about a quarter of every start), else the built jar as it is."""
    cds = Path(os.environ.get("DRISHTI_TEST_SERVER_CDS") or "/nonexistent")
    app = sorted((cds / "app").glob("drishti-server-*-exec.jar")) if (cds / "app").is_dir() else []
    if app and (cds / "app.jsa").is_file():
        return [f"-XX:SharedArchiveFile={cds / 'app.jsa'}", "-Xshare:auto", "-Xlog:cds=off", "-Xlog:cds+dynamic=off", "-jar", str(app[-1])]
    return ["-jar", str(jar())]

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
    found = server_jars()
    return Path(found[0]) if found else None


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


#: what a server writes while it only serves reads (its log, its temp and cache folders, the VaR engine's hot files and the official
#: capture it makes of the newest date at start, from the lake it reads, now kept in the file store with its sequence; the audit of the
#: reads; the derived trade pages; the personal BI workspace made on a person's first visit): a shared server's data folder is compared
#: without these (TEST_HARNESS.md, "Shared read-only servers")
DERIVED = re.compile(r"(^|/)(server\.log|jvm-tmp|tmp|logs|dumps|var-hot|var/hot|var/official|ccr/official|cache|caches|diskcache|derived"
                     r"|fstore/items/var/captures(-seq)?|fstore/items/audit|fstore/items/audit-heads|fstore/items/bi/workspaces)(/|$)"
                     r"|\.(lock|log|tmp|pid)$")


def fingerprint(work: Path) -> dict[str, tuple[int, int]]:
    """{path under work: (size, modified ns)} of every file a test could have written there (DERIVED left out)."""
    out: dict[str, tuple[int, int]] = {}
    for p in work.rglob("*"):
        rel = p.relative_to(work).as_posix()
        if DERIVED.search(rel) or p.is_symlink() or rel.startswith("config/") and not rel.startswith("config/connectors/"):
            continue
        try:
            st = p.stat()
        except OSError:
            continue
        if p.is_file():
            out[rel] = (st.st_size, st.st_mtime_ns)
    return out


def written(before: dict, after: dict) -> list[str]:
    """The files added, changed or removed between two fingerprints."""
    return sorted(k for k in set(before) | set(after) if before.get(k) != after.get(k))


INBOX = "data/fstore/items/collab/inbox/"
INBOX_COUNTERS = ("data/fstore/items/collab/counters/inbox", "data/fstore/items/collab/inbox-seq/")


def without_platform_alerts(work: Path, wrote: list[str]) -> list[str]:
    """``wrote`` less the server's own platform alerts: an alert rule that fired while the file ran (the memory budget squeezed, a slow
    disk) writes a notice to the administrator's inbox, as a server does whatever is asked of it (drill of 2026-11-15). Only when every
    row of every inbox file written is such an alert are the inbox files and its counters left out; a share, a comment or any other
    notice a test made is still a write."""
    boxes = [k for k in wrote if k.startswith(INBOX)]
    if not boxes:
        return wrote
    for k in boxes:
        try:
            rows = json.loads((work / k).read_text(encoding="utf-8")).get("rows") or []
        except (OSError, ValueError):
            return wrote
        if not rows or any(r.get("type") != "alert" or r.get("kind") != "platform" for r in rows):
            return wrote
    return [k for k in wrote if not k.startswith(INBOX) and not k.startswith(INBOX_COUNTERS)]


class StackPool:
    """This worker's real servers (docs/architecture/TEST_HARNESS.md, "The server pool"). Every server a browser test starts through
    ``_stack`` passes here: the pool counts and times the starts (DRISHTI_BROWSER_PROFILE), and keeps at most one idle server, so servers
    never pile up on a worker. A stack asked for with ``shared=True`` is kept when its file is done and handed to the next file on this
    worker that asks for the same settings (same environment, sign-in, heap); any other start stops the idle one first.

    A shared server is read-only for the files that share it: its data folder is fingerprinted when a file gets it and again when the
    file is done; a file that wrote anything there (a saved basket, an upload, a setting) fails in its teardown with the files it wrote,
    and the server is stopped rather than handed on, so no later file ever sees another file's data."""

    def __init__(self):
        self.idle: dict[tuple, tuple] = {}
        self.starts = 0

    def stop_idle(self) -> None:
        for key in list(self.idle):
            gen, _st = self.idle.pop(key)
            next(gen, None)                    # the stack's own teardown: console stopped, server terminated


POOL = StackPool()


def _stop_pool():
    POOL.stop_idle()


atexit.register(_stop_pool)


def _stack(tmp_path_factory, extra_env, work=None, auth=False, heap="768m", shared=False, ready=None, lane=False, own_console=False):
    """A real server and a console in front of it, from this worker's pool (StackPool): yields the Stack, stops it (or keeps it idle for the
    next file asking the same, when ``shared``) when the caller's fixture ends. ``ready(stack)``: run once after a start, before a shared
    server's folder is fingerprinted (what the server does by itself at start is never counted against the file)."""
    import lane_servers
    if lane_servers.enabled() and work is None and (shared or lane or (not extra_env and not auth)):
        # one server per lane (TEST_HARNESS.md, "Lane servers"): the default set-up and every read-only shared one; the file keeps its own
        # names on it. own_console: the file's console reads settings from this process's environment (a console of its own, in front of
        # the lane's server).
        st = lane_stack(tmp_path_factory, extra_env, auth, None if heap == "768m" else heap, ready)
        if own_console:
            for url in _console_in_process(st.server, auth):
                yield Stack(url, st.work, st.server)
        else:
            yield st
        return
    key = (tuple(sorted(extra_env.items())), auth, heap)
    if shared and work is None and key in POOL.idle:
        gen, st = POOL.idle.pop(key)
        POOL.shared_hits = getattr(POOL, "shared_hits", 0) + 1
    else:
        POOL.stop_idle()
        from browser_harness import Phases
        ph = Phases("server-start")
        gen = _start_stack(tmp_path_factory, extra_env, work, auth, heap)
        st = next(gen)
        if ready:
            ready(st)
        ph.mark("ready")
        ph.done()
        POOL.starts += 1
    keep = shared and work is None
    before = fingerprint(Path(st.work)) if keep else None
    try:
        yield st
    finally:
        wrote = without_platform_alerts(Path(st.work), written(before, fingerprint(Path(st.work)))) if keep else []
        if wrote:
            next(gen, None)          # never handed on: the next file would see this one's data
            pytest.fail("this file shares its server (shared=True) but wrote to it; a file that changes data starts its own server:\n  "
                        + "\n  ".join(wrote[:20]), pytrace=False)
        if keep:
            POOL.idle[key] = (gen, st)
        else:
            next(gen, None)


def _prepare_work(work: Path) -> None:
    """The server's folder: the committed config linked in (the site's own parts never), the module folders beside it."""
    if (ROOT / "config").exists():                 # the server reads config beside where it runs; its data stays in the scratch directory
        (work / "config").mkdir()
        for sub in (ROOT / "config").iterdir():    # each committed part linked; the site's own parts never (SITE_CONFIG)
            if sub.name not in SITE_CONFIG:
                (work / "config" / sub.name).symlink_to(sub)
        (work / "config" / "connectors").mkdir()   # empty: the server writes the packs' connector files there at its first start, as in a
                                                   # clean checkout, and a deployed test pack's file lands in the scratch copy
    for name in ("modules", "lib"):                # the module folders (the kernel's packs and jars) beside the config, as the server reads them
        if (ROOT / name).exists() and not (work / name).exists():
            (work / name).symlink_to(ROOT / name, target_is_directory=True)


def _launch_server(work: Path, port: int, extra_env: dict, heap: str, own_session: bool = False) -> subprocess.Popen:
    env = {"PATH": os.environ.get("PATH", ""), "HOME": str(work), "DRISHTI_PORT": str(port),
           **{k: os.environ[k] for k in ("DRISHTI_DISK_MINFREEGB", "DRISHTI_DISK_MINFREEPERCENT") if k in os.environ}, **extra_env}  # conftest's guard
    # the server's heap: 768 MB for a page's worth of data; a file whose server holds a year of VaR history says so (heap="1536m")
    return subprocess.Popen(java_cmd(str(JAVA), [f"-Xmx{heap}", *launch()], tmp=work / "jvm-tmp", cwd=work, env=env), cwd=work, env=env,
                            stdout=(work / "server.log").open("w"), stderr=subprocess.STDOUT, start_new_session=own_session)


def _wait_server(proc: subprocess.Popen, port: int, work: Path) -> None:
    """Waits until the server is ready: skips when it exited, fails when it never answers."""
    # a server that is still starting is waited for (on a loaded machine a cold start takes minutes): only one that exited is skipped, and one
    # that never answers fails rather than skipping every test of the file in silence. Ready is Spring's own readiness state, which turns
    # UP only after the start's last step (the distribution's modules installed and recorded in data/modules/registry.json): the web
    # layer answers seconds earlier, and a shared server fingerprinted then saw its own start's registry write as the file's (drill of
    # 2026-11-16), and a page of a module still loading (EXP of banking) had no console part yet (as bi_stack.wait_ready).
    deadline = time.monotonic() + 600
    while time.monotonic() < deadline:
        if proc.poll() is not None:
            pytest.skip("the server did not start: " + (work / "server.log").read_text()[-400:])
        try:
            urllib.request.urlopen(f"http://127.0.0.1:{port}/actuator/health/readiness", timeout=2).read(10)
            return
        except urllib.error.HTTPError as e:      # 503: listening, still starting
            if e.code != 503:
                return                           # sign-in on (401): the server answering
            time.sleep(0.5)   # wait-ok: polling a server that is still starting
        except OSError:
            time.sleep(0.5)   # wait-ok: polling a server that is still starting
    pytest.fail("the server did not answer within 10 minutes: " + (work / "server.log").read_text()[-400:])


def _console_settings(backend: str, auth: bool):
    from lane_console import console_settings
    return console_settings(backend, auth, TOKEN_SECRET)


def _console_in_process(backend: str, auth: bool):
    """A console in this process (a thread) in front of ``backend``: yields its URL, stops it after."""
    import uvicorn

    from core.app import create_app

    app = create_app(_console_settings(backend, auth))
    cport = free_port("DRISHTI_WB_CONSOLE_PORT")
    server = uvicorn.Server(uvicorn.Config(app, host="127.0.0.1", port=cport, log_level="warning", timeout_graceful_shutdown=2))
    thread = threading.Thread(target=server.run, daemon=True)
    thread.start()
    deadline = time.monotonic() + 20
    while not server.started and time.monotonic() < deadline:
        time.sleep(0.05)
    assert server.started, "the console did not start"
    try:
        yield f"http://127.0.0.1:{cport}"
    finally:
        server.should_exit = True
        thread.join(10)


def _start_stack(tmp_path_factory, extra_env, work=None, auth=False, heap="768m"):
    if jar() is None or not JAVA.exists():
        pytest.skip("needs the server jar (python tools/drishti.py build jar) and JDK 25")
    port = free_port("DRISHTI_WB_SERVER_PORT")
    work = work or tmp_path_factory.mktemp("wb-server")
    _prepare_work(work)
    proc = _launch_server(work, port, extra_env, heap)
    try:
        _wait_server(proc, port, work)
        for url in _console_in_process(f"http://127.0.0.1:{port}", auth):
            yield Stack(url, work, f"http://127.0.0.1:{port}")
    finally:
        # a scratch server need not drain: its graceful shutdown waits for requests still open (a page's live channel) and took the full
        # 15 s the tests used to wait on every VaR file; 3 s, then it is killed (its folder is thrown away)
        proc.terminate()
        try:
            proc.wait(3)
        except subprocess.TimeoutExpired:
            proc.kill()
            proc.wait(10)


# ---- lane servers (TEST_HARNESS.md, "Lane servers"): one server and one console per lane for every worker ----------------------------

#: the set-ups every file of the lane shares: the default server (no settings), and any server a file asks for with shared=True
#: the lane server's heap: one server serves every worker, so it gets room (DRILL_TEST_SERVER_HEAP, default 12g; 16g allowed)
LANE_HEAP = os.environ.get("DRILL_TEST_SERVER_HEAP") or os.environ.get("DRISHTI_LANE_HEAP", "12g")
#: the environment as the test run set it, before any file changed it for a console of its own: the lane console's
_BASE_ENV = dict(os.environ)
#: the lane server's room for Designs: with sign-in off every worker's test is the one desk user, so the whole lane's designs count against
#: ONE person's limits (drishti.builder.designs: 50 named, 10 scratch with the oldest evicted, 250 MB). The lane gives that user the room of
#: every test of the lane, as its heap is the room of every worker (drill of 2026-11-22: the 51st named design of the lane was refused,
#: DRS-5005, and a scratch design could be evicted by another worker's). A file that tests the limits themselves keeps a server of its own.
LANE_DESIGNS = {"DRISHTI_BUILDER_DESIGNS_MAXPERUSER": "100000", "DRISHTI_BUILDER_DESIGNS_MAXSCRATCH": "10000",
                "DRISHTI_BUILDER_DESIGNS_MAXUSERMB": "100000"}


def _lane_base(tmp_path_factory) -> Path:
    import lane_servers
    from browser_harness import run_dir
    return lane_servers.lane_dir(run_dir(tmp_path_factory) / "lane")


def _lane_start(extra_env: dict, auth: bool, heap: str, ready):
    def start(work: Path) -> dict:
        _prepare_work(work)
        port = free_port()
        proc = _launch_server(work, port, {**LANE_DESIGNS, **extra_env}, heap, own_session=True)
        try:
            _wait_server(proc, port, work)
        except BaseException:
            proc.kill()
            raise
        backend = f"http://127.0.0.1:{port}"
        cport = free_port()
        cdir = work / "console"
        env = {**_BASE_ENV, "DRISHTI_CONSOLE_DATA_DIR": str(cdir / "data"), "DRISHTI_CONSOLE_TMP_DIR": str(cdir / "tmp"),
               "DRISHTI_DATA_DIR": str(cdir / "java-data"), "PYTHONPATH": os.pathsep.join([str(CONSOLE), str(Path(__file__).resolve().parent)])}
        env.pop("DRISHTI_CONSOLE_TEST_DATA_PID", None)
        env.pop("DRISHTI_TEST_DATA_DIR_PID", None)
        for k in ("data", "tmp", "java-data"):
            (cdir / k).mkdir(parents=True, exist_ok=True)
        con = subprocess.Popen([sys.executable, str(Path(__file__).resolve().parent / "lane_console.py"), backend, str(cport), "1" if auth else "0", TOKEN_SECRET],
                               cwd=str(CONSOLE), env=env, stdin=subprocess.DEVNULL, stdout=(work / "console.log").open("w"),
                               stderr=subprocess.STDOUT, start_new_session=True)
        url = f"http://127.0.0.1:{cport}"
        deadline = time.monotonic() + 120
        while True:
            if con.poll() is not None or time.monotonic() > deadline:
                proc.kill()
                con.kill()
                pytest.fail("the lane console did not start: " + (work / "console.log").read_text()[-600:])
            try:
                urllib.request.urlopen(url + "/healthz", timeout=2).read(10)
                break
            except urllib.error.HTTPError:
                break                                    # answering
            except OSError:
                time.sleep(0.2)   # wait-ok: polling a console that is still starting
        st = {"pids": [proc.pid, con.pid], "url": url, "server": backend}
        if ready:
            ready(Stack(url, work, backend))
        return st
    return start


def _healthy(state: dict) -> bool:
    try:
        urllib.request.urlopen(state["server"] + "/actuator/health/readiness", timeout=5).read(10)
        return True
    except urllib.error.HTTPError as e:
        return e.code == 401
    except OSError:
        return False


def lane_stack(tmp_path_factory, extra_env=None, auth=False, heap=None, ready=None) -> "Stack":
    """The lane's server (and console) of this set-up, started by the first worker that asks, attached to by every other."""
    import lane_servers
    if jar() is None or not JAVA.exists():
        pytest.skip("needs the server jar (python tools/drishti.py build jar) and JDK 25")
    extra_env = dict(extra_env or {})
    heap = heap or LANE_HEAP
    key = lane_servers.key_of("wb", sorted(extra_env.items()), auth, heap)
    st = lane_servers.attach(_lane_base(tmp_path_factory), key, _lane_start(extra_env, auth, heap, ready), _healthy)
    return Stack(st["url"], Path(st["work"]), st["server"])


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
