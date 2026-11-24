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

"""``drishti.py doctor --acceptance`` (docs/plans/FIRST_RUN_ROBUSTNESS.md D9, matrix row 01): the server's health, the console and its way
to the server, the development administrator's sign-in ONLY under the demo profile, VAR BANK through the console with both figures exact;
one PASS line or one ``[FAIL] ... Fix: <command>`` per problem; exit 0 or 1.

The unit tests run against a small stand-in server and console on free loopback ports (never the owner's 18480/17480/18791/18792).
``test_e2e_first_run_on_linux`` is the one Linux end-to-end run (row 01 with 02, 10, 12, 56, 67, 69 under the three oracles); it needs a
built jar and the modules, takes some minutes and runs only with DRISHTI_FIRSTRUN_E2E=1."""
from __future__ import annotations

import http.server
import io
import json
import os
import shutil
import socket
import subprocess
import sys
import threading
import time
from pathlib import Path
from urllib.parse import parse_qs, urlparse

import pytest

TOOLS = Path(__file__).resolve().parent
for p in (str(TOOLS), str(TOOLS / "launch")):
    if p not in sys.path:
        sys.path.insert(0, p)
import devsecrets  # noqa: E402
import doctor  # noqa: E402
import matrix_oracles as mo  # noqa: E402

DEMO = "/home/x/dev.ini,/install/drishti/config/profiles/demo.ini"
PASSWORD = devsecrets.DEV["DRISHTI_SEED_PASSWORD"]


def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        port = s.getsockname()[1]
    assert port not in mo.OWNER_PORTS
    return port


class Stand:
    """A stand-in server (/actuator/health) and console (/healthz, /readyz, /login, /go, /api/var/scope) on two loopback ports."""

    def __init__(self, var=doctor.ACCEPT_VAR, es=doctor.ACCEPT_ES, server_up=True, ready=True, password=PASSWORD, var_status=200):
        self.var, self.es, self.server_up, self.ready, self.password, self.var_status = var, es, server_up, ready, password, var_status
        self.seen = []
        stand = self

        class H(http.server.BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def send(self, code, body=b"", ctype="application/json", headers=()):
                self.send_response(code)
                self.send_header("Content-Type", ctype)
                for k, v in headers:
                    self.send_header(k, v)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def do_GET(self):
                u = urlparse(self.path)
                stand.seen.append(("GET", self.server.server_port, u.path))
                signed = "session=ok" in (self.headers.get("Cookie") or "")
                if self.server.server_port == stand.sport:
                    return self.send(200 if stand.server_up else 503, json.dumps({"status": "UP" if stand.server_up else "DOWN"}).encode())
                if u.path == "/healthz":
                    return self.send(200, b'{"status":"UP"}')
                if u.path == "/readyz":
                    return self.send(200 if stand.ready else 503, json.dumps({"status": "UP"} if stand.ready else {"status": "DOWN", "detail": "connect refused"}).encode())
                if u.path in ("/t", "/login"):
                    return self.send(200, b"<html>home</html>" if signed else b"<form>password</form>", "text/html")
                if not signed:
                    return self.send(303, b"", headers=[("Location", "/login")])
                if u.path == "/go":
                    return self.send(200, b"<html>VaR</html>", "text/html")
                if u.path == "/api/var/scope" and parse_qs(u.query).get("scope") == ["BANK"]:
                    if stand.var_status != 200:
                        return self.send(stand.var_status, b'{"code":"DRS-1","detail":"no demo lake"}')
                    return self.send(200, json.dumps({"scope": "BANK", "var": stand.var, "es": stand.es}).encode())
                return self.send(404)

            def do_POST(self):
                body = parse_qs(self.rfile.read(int(self.headers.get("Content-Length") or 0)).decode())
                stand.seen.append(("POST", self.server.server_port, self.path, body.get("user", [""])[0]))
                if body.get("password", [""])[0] == stand.password:
                    return self.send(303, b"", headers=[("Location", "/t"), ("Set-Cookie", "session=ok; Path=/")])
                return self.send(401, b"<form>Unknown user or wrong password. password</form>", "text/html")

        self.servers = []
        for _ in range(2):
            s = http.server.ThreadingHTTPServer(("127.0.0.1", free_port()), H)
            threading.Thread(target=s.serve_forever, daemon=True).start()
            self.servers.append(s)
        self.sport, self.cport = self.servers[0].server_port, self.servers[1].server_port

    def close(self):
        for s in self.servers:
            s.shutdown()
            s.server_close()

    def check(self, settings=DEMO, env=None, wait=0, **kw):
        return doctor.acceptance(f"http://127.0.0.1:{self.cport}", PASSWORD, wait=wait, server=f"http://127.0.0.1:{self.sport}",
                                 settings=settings, keys={}, env=env or {}, **kw)


@pytest.fixture()
def stand():
    made = []

    def make(**kw):
        s = Stand(**kw)
        made.append(s)
        return s
    yield make
    for s in made:
        s.close()


def lines(results) -> list[str]:
    out = []
    doctor.render(results, say=out.append)
    return out


def test_row01_the_release_figures_give_one_pass_line_and_exit_0(stand):
    s = stand()
    res = s.check()
    out = lines(res)
    assert doctor.exit_code(res) == 0 and len(out) == 1 and out[0].startswith("PASS")
    assert "167,372,692" in out[0] and "210,131,972" in out[0] and "drishti-dev-admin" in out[0]
    assert ("POST", s.cport, "/login", "drishti-dev-admin") in s.seen and ("GET", s.cport, "/api/var/scope") in s.seen
    assert ("GET", s.sport, "/actuator/health") in s.seen and ("GET", s.cport, "/readyz") in s.seen


@pytest.mark.parametrize("var, es, bad", [(167_372_693, doctor.ACCEPT_ES, "VaR"), (doctor.ACCEPT_VAR, 0, "ES"), (None, None, "VaR")])
def test_a_figure_that_is_not_the_releases_is_one_fail_with_the_lake_line(stand, var, es, bad):
    res = stand(var=var, es=es).check()
    out = lines(res)
    assert doctor.exit_code(res) == 1 and all(l.startswith("[FAIL] ") and "Fix: " in l for l in out)
    assert any(bad in l and "lake demo --engine java" in l for l in out)
    assert len(out) == (2 if var is None else 1), "one line per wrong figure, nothing else"


def test_the_dev_admin_signs_in_only_under_the_demo_profile(stand):
    s = stand()
    res = s.check(settings="/home/x/dev.ini")
    out = lines(res)
    assert doctor.exit_code(res) == 1 and len(out) == 1 and out[0].startswith("[FAIL] demo profile: ")
    assert "--profile demo doctor --acceptance" in out[0] and "--settings /home/x/dev.ini" in out[0]
    assert not [x for x in s.seen if x[0] == "POST"], "no sign-in was tried"
    res = s.check(settings="/home/x/dev.ini", env={"DRISHTI_SEED_PASSWORD": PASSWORD, "DRISHTI_SEED_USERNAME": "admin2"})
    assert ("POST", s.cport, "/login", "admin2") in s.seen and doctor.exit_code(res) == 0, "another profile: the administrator named by the environment"


def test_a_wrong_password_is_one_fail_naming_the_secrets(stand):
    res = stand(password="something else").check()
    out = lines(res)
    assert len(out) == 1 and out[0].startswith("[FAIL] sign-in: drishti-dev-admin was refused") and "start all --demo" in out[0]


def test_server_down_and_console_down_are_one_fail_each(stand):
    s = stand(server_up=False)
    out = lines(s.check())
    assert len(out) == 1 and out[0].startswith("[FAIL] server: ") and "start all --demo" in out[0]
    gone = free_port()
    res = doctor.acceptance(f"http://127.0.0.1:{gone}", PASSWORD, server=f"http://127.0.0.1:{free_port()}", settings=DEMO, keys={}, env={})
    out = lines(res)
    assert [l.split(":")[0] for l in out] == ["[FAIL] server", "[FAIL] console"] and doctor.exit_code(res) == 1


def test_a_console_that_cannot_reach_its_server_is_named(stand):
    out = lines(stand(ready=False).check())
    assert len(out) == 1 and out[0].startswith("[FAIL] console to server: ") and "connect refused" in out[0]


def test_an_error_answer_for_var_bank_names_the_servers_words(stand):
    out = lines(stand(var_status=503).check())
    assert len(out) == 1 and out[0].startswith("[FAIL] VAR BANK: the console answered HTTP 503: no demo lake")


def test_wait_waits_for_the_server_to_come_up(stand):
    s = stand(server_up=False)
    threading.Timer(1.0, lambda: setattr(s, "server_up", True)).start()
    assert doctor.exit_code(s.check(wait=20)) == 0


def test_the_ports_come_from_the_settings_files_and_no_proxy_is_used(stand, monkeypatch):
    s = stand()
    monkeypatch.setenv("HTTP_PROXY", mo.DEAD)
    monkeypatch.setenv("http_proxy", mo.DEAD)
    monkeypatch.setenv("NO_PROXY", "")
    monkeypatch.setenv("no_proxy", "")
    res = doctor.acceptance(f"http://127.0.0.1:{s.cport}", PASSWORD, settings=DEMO, keys={"server.port": str(s.sport)}, env={})
    assert doctor.exit_code(res) == 0, lines(res)


def test_json_output_carries_every_step():
    res = [doctor._a("ok", "server", "x"), doctor._a("fail", "console", "y", "z")]
    assert json.loads(json.dumps({"ok": doctor.exit_code(res) == 0, "checks": res}))["checks"][1]["fix"] == "z"


def test_the_cli_names_the_acceptance_and_its_options():
    r = subprocess.run([sys.executable, str(TOOLS / "drishti.py"), "doctor", "--help"], capture_output=True, text=True, timeout=120)
    assert r.returncode == 0 and "--acceptance" in r.stdout and "--wait" in r.stdout and "--expect-var" in r.stdout


def test_the_cli_refuses_the_dev_admin_without_the_demo_profile(tmp_path):
    env = mo.oracle_env(tmp_path)
    r = mo.drishti("doctor", "--acceptance", "--console", f"http://127.0.0.1:{free_port()}", env=env)
    assert r.returncode == 1 and "[FAIL] demo profile:" in r.stdout and "--profile demo doctor --acceptance" in r.stdout
    assert not mo.output_problems(r.stdout + r.stderr) and not mo.calls(tmp_path) and not mo.net(tmp_path)


# ---- the one Linux end-to-end run (section E-full on Linux; plan B1 "E") ------------------------------------------------------------------

E2E = os.environ.get("DRISHTI_FIRSTRUN_E2E") == "1"
SPORT, CPORT = int(os.environ.get("DRISHTI_FIRSTRUN_SERVER_PORT", "18991")), int(os.environ.get("DRISHTI_FIRSTRUN_CONSOLE_PORT", "17991"))


def _copy_checkout(dest: Path) -> None:
    """This checkout as a person unzips it (tracked and new files, no .git), with the built jar and modules (the build's outputs)."""
    files = subprocess.run(["git", "-C", str(mo.ROOT), "ls-files", "-co", "--exclude-standard", "-z"], capture_output=True, check=True).stdout
    for rel in filter(None, files.decode().split("\0")):
        src = mo.ROOT / rel
        if src.is_file() and not src.is_symlink():
            (dest / rel).parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(src, dest / rel)
    for pat in ("lib/drishti-server-*-exec.jar", "modules/*.dmod", "modules/first-party.yaml"):
        for f in mo.ROOT.glob(pat):
            shutil.copy2(f, dest / f.relative_to(mo.ROOT))


def _printed_offline_command(text: str) -> tuple[Path, list[list[str]]]:
    """(the folder, the commands) of the offline pip commands setup printed for the owner (``cd <folder>   then   <python> -m pip
    install --no-index --find-links <pack> -r requirements.txt   then   ... -r ../config/compute/locks/python.lock``)."""
    import shlex
    for line in text.splitlines():
        if "--no-index" in line and "-m pip install" in line:
            part = line[line.index("cd "):].split("   or   ")[0]
            cd, *cmds = part.split("   then   ")
            return Path(shlex.split(cd)[1]), [shlex.split(c) for c in cmds]
    raise AssertionError("setup printed no offline pip command:\n" + text[-3000:])


@pytest.mark.skipif(not E2E, reason="the Linux end-to-end run: DRISHTI_FIRSTRUN_E2E=1 (needs the built jar and modules; minutes)")
def test_e2e_first_run_on_linux():
    """The owner's way, on a fresh Python: setup checks and prints the pip command (it installs nothing); the owner runs it; setup is
    ready; a second setup changes no byte; lake demo; start all --demo; doctor --acceptance PASS; stop all. The Python is a virtual
    environment this HARNESS makes outside the scratch home (the owner's role; Drishti makes none), never the owner's stack venv."""
    assert not ({SPORT, CPORT} & set(mo.OWNER_PORTS))
    scratch = Path(os.environ.get("DRISHTI_FIRSTRUN_SCRATCH") or (Path.home() / "drishti-bench-scratch" / "firstrun-e2e"))
    pack_src = Path(os.environ.get("DRISHTI_FIRSTRUN_PACK") or (Path.home() / "drishti-bench-scratch" / "firstrun-pack"))
    base = os.environ.get("DRISHTI_FIRSTRUN_BASE_PYTHON") or shutil.which("python3.13") or ""
    assert pack_src.is_dir() and any(pack_src.glob("*.whl")), f"the harness's wheel pack ({pack_src}): pip download the requirements into it once"
    assert base, "a Python 3.13 for the harness's own virtual environment (DRISHTI_FIRSTRUN_BASE_PYTHON)"
    shutil.rmtree(scratch, ignore_errors=True)
    venv = scratch.parent / (scratch.name + "-py")                   # the owner's own environment: outside the scratch home
    shutil.rmtree(venv, ignore_errors=True)
    home = scratch / "John Smith"
    checkout = home / "IdeaProjects" / "drishti-platform (1)" / "drishti"
    old = scratch / "old clone"
    assert list(mo.ROOT.glob("lib/drishti-server-*-exec.jar")), "build the jar first (build jar)"
    system_before = mo.system_python_state()                         # the owner's operating-system Python, before the whole run
    subprocess.run([base, "-m", "venv", str(venv)], check=True, capture_output=True)
    py = str(venv / "bin" / "python")
    site = next((venv / "lib").glob("python3*/site-packages"))
    _copy_checkout(checkout)
    shutil.copytree(pack_src, home / "drishti-wheels")                # the owner unzipped the pack where START_HERE says
    (home / "drishti-envs").mkdir(parents=True)
    (home / "drishti-envs" / "dev.ini").write_text(f"[drishti]\ndrishti.home={old}\ndrishti.data.dir={old}/data\n", encoding="utf-8")
    env = mo.oracle_env(scratch, home=home, DRISHTI_PYTHON=py, DRISHTI_HOME=old, DRISHTI_DATA_DIR=old / "data",
                        DRISHTI_MODULES_HOME=old / "modules", DRISHTI_TMP_DIR=old / "tmp", DRISHTI_LIB_PATH=old / "lib")
    run = lambda *a, t=1800: mo.drishti(*a, env=env, python=py, home=checkout, cwd=checkout, timeout=t)   # noqa: E731
    problems = []
    try:
        bare = mo.snapshot(site)
        r0 = run("setup")                                            # a fresh Python: checked, NOTHING installed, the command printed
        assert r0.returncode == 1 and "[FAIL] libraries" in r0.stdout and r0.stdout.rstrip().splitlines()[-1].startswith("Drishti is NOT ready:")
        assert "[move]" in r0.stdout and mo.snapshot(site) == bare, "setup installed nothing into the Python"
        folder, commands = _printed_offline_command(r0.stdout)
        assert folder == checkout / "drishti-console" and len(commands) == 2 and all(c[0] == py for c in commands)
        assert commands[0][-2:] == ["-r", "requirements.txt"] and commands[1][-2:] == ["-r", "../config/compute/locks/python.lock"]
        for words in commands:                                         # the owner runs them himself, in turn
            owner = subprocess.run(words, cwd=folder, env=env, capture_output=True, text=True, timeout=1800)
            assert owner.returncode == 0, owner.stdout[-2000:] + owner.stderr[-2000:]
        r1 = run("setup")
        assert r1.returncode == 0 and "Drishti is ready." in r1.stdout, r1.stdout + r1.stderr
        problems += mo.output_problems(r1.stdout, setup=True)
        before, installed = mo.snapshot(scratch, skip=("/oracle/",)), mo.snapshot(site)
        r2 = run("setup")
        assert r2.returncode == 0 and r2.stdout.rstrip().endswith("Drishti is ready."), r2.stdout + r2.stderr
        assert "[made]" not in r2.stdout and "[move]" not in r2.stdout
        assert mo.changed(before, mo.snapshot(scratch, skip=("/oracle/",))) == [], "the second setup changed no byte"
        assert mo.snapshot(site) == installed, "setup left the Python's site-packages byte-identical"
        ini = home / "drishti-envs" / "dev.ini"                      # the person picks ports of their own (the owner's are in use)
        ini.write_text(ini.read_text(encoding="utf-8") + f"\n[server]\nserver.port={SPORT}\n\n[console.server]\nport={CPORT}\n", encoding="utf-8")
        r3 = run("lake", "demo", "--engine", "java")
        assert r3.returncode == 0, r3.stdout[-3000:] + r3.stderr[-3000:]
        r4 = run("start", "all", "--demo", "--server-port", SPORT, "--console-port", CPORT, t=900)
        assert r4.returncode == 0, r4.stdout[-3000:] + r4.stderr[-3000:]
        problems += mo.output_problems(r4.stdout + r4.stderr)
        r5 = run("--profile", "demo", "doctor", "--acceptance", "--console", f"http://127.0.0.1:{CPORT}", "--wait", "300", t=900)
        out = r5.stdout.strip().splitlines()
        assert r5.returncode == 0 and len(out) == 1 and out[0].startswith("PASS"), r5.stdout + r5.stderr
        assert "167,372,692" in out[0] and "210,131,972" in out[0]
    finally:
        r6 = run("stop", "all", t=300)
    assert r6.returncode == 0, r6.stdout + r6.stderr
    assert not old.exists(), "nothing was made in the old clone's folder"
    assert not [p for p in scratch.rglob("pyvenv.cfg")] and not [p for p in scratch.rglob("." + "venv")], "no virtual environment"
    assert not mo.net(scratch), mo.net(scratch)
    assert not [c for c in mo.calls(scratch) if c.split()[0] in ("pip", "pip3", "python", "python3", "git", "curl", "wget")], mo.calls(scratch)
    assert not problems, problems
    system_after = mo.system_python_state()
    assert system_after == system_before, {k: (system_before.get(k), v) for k, v in system_after.items() if system_before.get(k) != v}
    print(f"system Python untouched: {', '.join(system_before)}")
