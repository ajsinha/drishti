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

"""The release works from anywhere (a release blocker: the drill's ``anywhere`` lane, docs/architecture/TEST_HARNESS.md, "The release from
another folder").

The release as shipped is unpacked into a scratch install folder (``git archive`` of HEAD plus the gate's server jar, as
docs/DEPLOYMENT_AND_QA_GUIDE.md builds the ZIP), its lake root is the ONE shared test lake (read only, an absolute path in the drill's lake
cache, TEST_HARNESS.md section 3.12; the install builds no lake), its data folder its own scratch for the server's state, and the server is
started with the install's ``tools/drishti.py start server`` from a DIFFERENT working folder (the process folder and ``--workdir`` both), the demo off:
VAR BANK has a headline, VAR EXPLAIN BANK answers, POS, TRD and FORMS answer, and after two restarts VAR BANK is the same figure. The
``resolved paths:`` block of the server's log has only absolute paths, each under the install or the data folder, never the working folder. A
required root that is missing stops the start at once with its message. No browser, no console. The server runs with sign-in off (as
every drill server does, so every request is the administrator's); the one Python command runs the same on Windows.

    python3 -m pytest -q drishti-console/tests/test_release_from_other_folder.py
"""
from __future__ import annotations

import json
import os
import re
import shutil
import socket
import subprocess
import sys
import tarfile
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

import pytest

import java_launch  # noqa: E402
import var_stack  # noqa: E402 - the ONE shared read-only test lake

ROOT = Path(__file__).resolve().parents[2]

def _free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def _env(**more: str) -> dict[str, str]:
    """The caller's environment without any DRISHTI_ setting but the Java home and the lake and resolver caches (nothing leaks in from the drill), plus ``more``."""
    keep = {"DRISHTI_JAVA_HOME", "DRISHTI_LAKE_CACHE", "DRISHTI_PYTHON", "DRISHTI_RESOLVED_CACHE"}
    env = {k: v for k, v in os.environ.items() if not k.startswith("DRISHTI_") or k in keep}
    env.update(more)
    return env


class _Scratch:
    """Scratch folders on the disk under data/tmp (the release is a gigabyte: /tmp is memory on a build machine), removed at the end."""

    def __init__(self) -> None:
        base = Path(os.environ.get("DRISHTI_ANYWHERE_TMP") or ROOT / "data" / "tmp")
        base.mkdir(parents=True, exist_ok=True)
        self.root = Path(tempfile.mkdtemp(prefix="anywhere-", dir=base))

    def mktemp(self, name: str) -> Path:
        p = self.root / name
        p.mkdir(parents=True, exist_ok=True)
        return p


@pytest.fixture(scope="module")
def tmp_path_factory():   # noqa: F811 - this file's own: on the disk, and gone when the module's tests are
    s = _Scratch()
    yield s
    shutil.rmtree(s.root, ignore_errors=True)


@pytest.fixture(scope="module")
def other(tmp_path_factory) -> Path:
    """The folder every process of the test runs in: not the install, not the data."""
    return tmp_path_factory.mktemp("cwd")


@pytest.fixture(scope="module")
def install(tmp_path_factory) -> Path:
    """The release unpacked: every file git tracks at HEAD (the committed release, never the working tree's edits) and the server jar."""
    try:
        jar = java_launch.server_jar()
    except java_launch.JarError as e:
        pytest.fail(f"{e} (the drill's gate builds it)")
    dest = tmp_path_factory.mktemp("install") / "drishti"
    dest.mkdir()
    tar = dest.parent / "release.tar"
    subprocess.run(["git", "archive", "--format=tar", "-o", str(tar), "HEAD"], cwd=ROOT, check=True)
    with tarfile.open(tar) as t:
        t.extractall(dest, filter="data")
    tar.unlink()
    target = dest / "lib"
    target.mkdir(parents=True, exist_ok=True)
    shutil.copy2(jar, target / jar.name)
    # a module is a package beside the jar, and git holds none: the ones the build made (python tools/drishti.py build all, the drill's
    # gate) go in beside the unpacked sources; the install's start refuses them when its sources differ (the stale rule), so a working
    # tree edited since the commit fails here in words, never passes on old content
    built = sorted((ROOT / "modules").glob("*.dmod"))
    if not built or not (ROOT / "modules" / "first-party.yaml").is_file():
        pytest.fail("no module packages in modules/ (python tools/drishti.py build modules; the drill's gate builds them)")
    for f in [*built, ROOT / "modules" / "first-party.yaml"]:
        shutil.copy2(f, dest / "modules" / f.name)
    return dest


@pytest.fixture(scope="module")
def lake() -> Path:
    """The ONE shared test lake, read only (an absolute path in the lake cache, DRISHTI_LAKE_CACHE): the install reads it, never writes it;
    the server's VaR hot folder and official roots are in its own data folder (Server.env). Asked of the cache directly: this file's
    tmp_path_factory is its own scratch on the disk, not pytest's."""
    try:
        out = var_stack.shared_cache_folder() / "delta"
    except RuntimeError as e:
        pytest.fail(f"the shared test lake: {e}")
    assert (out / "risk" / "scenario-pv").is_dir(), f"no scenario-pv in the shared lake {out}"
    return out


class Server:
    """One server of the install, started and stopped with the shipped scripts, from the other folder."""

    def __init__(self, install: Path, other: Path, data: Path, lake: Path, port: int):
        self.install, self.other, self.data, self.lake, self.port = install, other, data, lake, port
        self.base = f"http://127.0.0.1:{port}"
        self.as_of = var_stack.SHARED_AS_OF                 # the shared lake's last business date

    def script(self, command: str, *args: str, env: dict[str, str] | None = None, timeout: float = 600) -> subprocess.CompletedProcess:
        """The install's tools/drishti.py with ``command`` (e.g. "start server") and ``args``, run from the other folder."""
        return subprocess.run([sys.executable, str(self.install / "tools" / "drishti.py"), *command.split(), *args], cwd=self.other, capture_output=True, text=True,
                              timeout=timeout, env=env or self.env())

    def env(self, **more: str) -> dict[str, str]:
        return _env(DRISHTI_DEMO_ENABLED="false", DRISHTI_OFFLINE="true", DRISHTI_VAR_SYSTEM_BASKETS_SEED="true",
                    DRISHTI_VAR_HOT_DIR=str(self.data / "var" / "hot"), DRISHTI_VAR_OFFICIAL_ROOT=str(self.data / "var" / "official"),
                    DRISHTI_CCR_EXPOSURE_OFFICIAL_ROOT=str(self.data / "ccr" / "official"), **more)

    def start(self) -> subprocess.CompletedProcess:
        return self.script("start server", "--background", "--port", str(self.port), "--data-dir", str(self.data), "--lake-root", str(self.lake),
                           "--heap", "3072m", "--workdir", str(self.other), "--wait", "420", timeout=480)

    def stop(self) -> None:
        self.script("stop server", "--data-dir", str(self.data), timeout=120)

    def get(self, path: str, timeout: float = 120) -> tuple[int, object]:
        try:
            with urllib.request.urlopen(self.base + path, timeout=timeout) as r:
                body = r.read().decode()
                return r.status, json.loads(body) if body[:1] in "{[" else body
        except urllib.error.HTTPError as e:
            return e.code, e.read().decode()[:500]

    def get_within(self, what: str, path: str, seconds: float = 600) -> tuple[int, object]:
        """``path`` until it answers (any status that is not a timeout or a refused connection) within ``seconds``: a first answer that computes
        something (the explanation of the bank's VaR) can take longer than one request's wait on a loaded machine, and the server is then
        working, not hung; the message names what was waited for and the last error."""
        deadline = time.monotonic() + seconds
        last = ""
        while time.monotonic() < deadline:
            try:
                return self.get(path, min(120, max(5, deadline - time.monotonic())))
            except OSError as e:   # a read timeout (TimeoutError) or a refused connection: ask again until the deadline
                last = f"{type(e).__name__}: {e}"
                time.sleep(2)   # wait-ok: the answer is still being computed
        raise AssertionError(f"{what} did not answer in {seconds:.0f} s ({last})")

    def wait_var(self, seconds: float = 300) -> object:
        """The bank's VaR once the engine answers."""
        deadline = time.monotonic() + seconds
        last: object = None
        while time.monotonic() < deadline:
            try:
                status, last = self.get(f"/api/v1/risk/var/scope?scope=BANK&asOf={self.as_of}", 60)
                if status == 200:
                    return last
            except OSError as e:
                last = str(e)
            time.sleep(2)   # wait-ok: the VaR engine is still starting
        raise AssertionError(f"VAR BANK did not answer in {seconds:.0f} s: {str(last)[:400]}")

    def log(self) -> str:
        parts = []
        for name in ("server.log", "server.out"):
            f = self.data / "logs" / name
            if f.is_file():
                parts.append(f.read_text(errors="replace"))
        return "\n".join(parts)


def _headline(var: object) -> float:
    """The bank's VaR figure from the scope answer (the first number under a var-like key)."""
    def walk(o):
        if isinstance(o, dict):
            for k, v in o.items():
                if k.lower() in ("var", "value", "headline") and isinstance(v, (int, float)) and not isinstance(v, bool):
                    yield float(v)
                yield from walk(v)
        elif isinstance(o, list):
            for v in o:
                yield from walk(v)
    nums = [abs(n) for n in walk(var)]
    assert nums, f"no VaR figure in the answer: {str(var)[:400]}"
    return nums[0]


def _checks(s: Server) -> float:
    """VAR BANK, VAR EXPLAIN BANK, POS, TRD and FORMS answer; returns the bank's VaR headline."""
    var = s.wait_var()
    head = _headline(var)
    assert head > 0, f"the bank's VaR headline is zero: {str(var)[:400]}"
    status, body = s.get_within("VAR EXPLAIN BANK", f"/api/v1/explain?kind=var&scope=BANK&asOf={s.as_of}&part=why")
    assert status == 200, f"VAR EXPLAIN BANK: {status} {str(body)[:400]}"
    assert not re.search(r"DRS-\d{4}", json.dumps(body) if not isinstance(body, str) else body), f"VAR EXPLAIN BANK carries an error code: {str(body)[:400]}"
    for kind in ("position", "trade"):
        status, body = s.get_within(f"{kind} count", f"/api/v1/data/{kind}/count")
        assert status == 200, f"{kind} count: {status} {str(body)[:300]}"
        n = (body.get("total") or body.get("rows") or body.get("count")) if isinstance(body, dict) else body
        assert n and n > 0, f"{kind}: no rows ({body})"
    status, body = s.get_within("the pack list", "/api/v1/packs")
    rows = body if isinstance(body, list) else (body.get("packs", []) if isinstance(body, dict) else [])
    names = sorted({str(p.get("name") or p.get("pack")) for p in rows if isinstance(p, dict) and not p.get("sample")} & _packs_with_form(s.install))
    assert status == 200 and names, f"FORMS: the server lists no pack with a form: {status} {str(body)[:300]}"
    bad = []
    for n in names:   # FORMS lists the packs whose pack.yaml has a form: section
        st, b = s.get_within(f"the form of {n}", "/api/v1/forms/" + urllib.parse.quote(n))
        if st != 200:
            bad.append(f"{n}: {st} {str(b)[:150]}")
    assert not bad, "FORMS does not answer for: " + "; ".join(bad)
    return head


def _packs_with_form(install: Path) -> set[str]:
    """The install's packs with a form (the site's and kernel's, and the code module's .dmod folder), found through the one pack
    resolution (the Java resolver's answer, tools/resolved.py) read from the install folder."""
    import yaml
    import resolved as R
    out = set()
    res = R.repository(install)
    packs = res.packs

    def code(mid):                               # a module with code: its manifest has a java: block
        m = res.modules.get(mid) if mid else None
        return m is not None and bool((yaml.safe_load((m.home / "module.yaml").read_text(encoding="utf-8")) or {}).get("java"))

    for p in (packs[n] for n in packs):
        if p.folder is None or (p.layer == R.MODULE and not code(p.module)):
            continue
        m = yaml.safe_load((p.folder / "pack.yaml").read_text(encoding="utf-8")) or {}
        if isinstance(m.get("form"), dict) and m["form"]:
            out.add(p.name)
    return out


def _resolved(log: str) -> dict[str, str]:
    """The last ``resolved paths:`` block of the log: {name: path}."""
    at = log.rfind("resolved paths:")
    assert at >= 0, "the server's log has no 'resolved paths:' block"
    out: dict[str, str] = {}
    for line in log[at:].splitlines()[1:]:
        m = re.match(r"^\s+(.+?)\s*= (.+?)  \(from: ", line)
        if not m:
            break
        out[m.group(1).strip()] = m.group(2)
    assert len(out) >= 8, f"the 'resolved paths:' block is short: {out}"
    return out


def test_the_release_runs_from_another_folder_and_restarts(install, other, lake, tmp_path_factory):
    data = tmp_path_factory.mktemp("data")
    s = Server(install, other, data, lake, _free_port())
    try:
        heads = []
        for run in range(3):   # the first start and two restarts
            r = s.start()
            assert r.returncode == 0, f"start {run + 1}: exit {r.returncode}\n{r.stdout[-1500:]}\n{r.stderr[-800:]}\n{s.log()[-3000:]}"
            try:
                heads.append(_checks(s))
            except AssertionError as e:
                lines = [ln[:300] for ln in s.log().splitlines() if re.search(r"ERROR|WARN|pnl|DRS-", ln)][-25:]
                raise AssertionError(f"start {run + 1} of 3: {e}\nthe server's log:\n" + "\n".join(lines)) from None
            paths = _resolved(s.log())
            for name, p in paths.items():
                if p == "(none)":
                    continue
                assert os.path.isabs(p), f"resolved root {name} is not absolute: {p}"
                inside = [str(base) + os.sep for base in (install.resolve(), data.resolve(), lake.parent.resolve())]   # the shared lake's folder too
                real = os.path.realpath(p) + os.sep
                assert any(real.startswith(os.path.realpath(b)) or real.startswith(b) for b in inside), \
                    f"resolved root {name} = {p} is under none of the install {install}, the data folder {data} and the shared lake {lake.parent}"
                assert not real.startswith(os.path.realpath(other) + os.sep), f"resolved root {name} = {p} is under the working folder {other}"
            if run < 2:
                s.stop()
                time.sleep(2)   # wait-ok: the stop script has returned; the port is released
        assert heads[0] == heads[1] == heads[2], f"VAR BANK changed over the restarts: {heads}"
        assert not list(other.iterdir()), f"the server wrote into the working folder: {[p.name for p in other.iterdir()]}"
    finally:
        s.stop()


def test_a_missing_required_root_stops_the_start_at_once(install, other, lake, tmp_path_factory):
    data = tmp_path_factory.mktemp("data-missing")
    s = Server(install, other, data, lake, _free_port())
    gone = data / "no-such-modules-folder"
    t0 = time.monotonic()
    r = s.script("start server", "--background", "--port", str(s.port), "--data-dir", str(data), "--lake-root", str(lake), "--workdir", str(other),
                 "--wait", "120", env=s.env(DRISHTI_MODULES_HOME=str(gone)), timeout=180)
    took = time.monotonic() - t0
    try:
        assert r.returncode != 0, "the server started without its modules folder"
        assert took < 90, f"the start took {took:.0f} s to fail"
        text = s.log() + r.stdout + r.stderr
        assert "the server cannot start" in text and "modules" in text and str(gone) in text and "is not there" in text, \
            f"no clear message about the missing modules folder:\n{text[-2000:]}"
    finally:
        s.stop()


def test_a_relative_home_is_refused(install, other, lake, tmp_path_factory):
    data = tmp_path_factory.mktemp("data-relative")
    s = Server(install, other, data, lake, _free_port())
    r = s.script("start server", "--background", "--port", str(s.port), "--data-dir", str(data), "--lake-root", str(lake), "--workdir", str(other),
                 "--wait", "120", env=s.env(DRISHTI_HOME="drishti"), timeout=180)
    try:
        assert r.returncode != 0
        text = s.log() + r.stdout + r.stderr
        assert "relative path" in text and "install folder" in text, f"no clear message about the relative home:\n{text[-1500:]}"
    finally:
        s.stop()
