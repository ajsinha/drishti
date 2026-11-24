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

"""`drishti.py doctor`: is this machine ready to build, run and talk to Drishti?

Each check is one line: green (ok), yellow (warn: works, but something is missing or unusual) or red (fail: will not work) with
the fix. Nothing is changed or killed; ports are only checked with a GET of /actuator/health on localhost.
`run(env, ...)` returns a list of {level, name, detail, fix} dicts; `exit_code(results)` is 1 on any red.
"""
from __future__ import annotations

import importlib.util
import json
import os
import pathlib
import re
import subprocess
import sys
import urllib.error
import urllib.request


def _lakeconfig():
    """tools/lake/lakeconfig.py: where the lakes are."""
    p = str(pathlib.Path(__file__).resolve().parent / "lake")
    if p not in sys.path:
        sys.path.insert(0, p)
    import lakeconfig
    return lakeconfig


MIN_JAVA = 21
LIBS = (("yaml", "pyyaml", "every command that reads YAML (pack, sutra gen)"),
        ("deltalake", "deltalake", "data ingest --store delta"), ("pyarrow", "pyarrow", "data ingest --store delta"))
DIR_VARS = ((_lakeconfig().DEMO_ROOT_ENV, "the demo lake's root"), ("DRISHTI_FILES_ROOT", "files root"), ("DRISHTI_PACKS_INSTALLED", "installed packs"),
            ("DRISHTI_PACKS_DIR", "packs dir"))
PORTS = ((18480, "server"), (17480, "console"))
if os.environ.get("DRISHTI_DOCTOR_PORTS"):            # "18971:server,17971:console": check these instead (a machine whose defaults are not yours to probe)
    PORTS = tuple((int(x.split(":")[0]), x.split(":")[1]) for x in os.environ["DRISHTI_DOCTOR_PORTS"].split(",") if ":" in x)


def item(level: str, name: str, detail: str, fix: str = "") -> dict:
    return {"level": level, "name": name, "detail": detail, "fix": fix}


def java_check(java: str) -> dict:
    try:
        r = subprocess.run([java, "-XshowSettings:properties", "-version"], text=True, capture_output=True, timeout=30)
    except (OSError, subprocess.SubprocessError) as e:
        return item("fail", "java", f"cannot run {java}: {e}", f"install a JDK {MIN_JAVA}+ and set drishti.java.home in your settings file (or pass --java)")
    text = r.stderr + r.stdout
    m = re.search(r'version "(\d+)(?:\.(\d+))?', text)
    if not m:
        return item("fail", "java", f"{java} printed no version", "check the JDK installation")
    major = int(m.group(1)) if m.group(1) != "1" else int(m.group(2) or 8)
    vendor = re.search(r"java\.vendor = (.+)", text)
    detail = f"{java}: Java {m.group(0)[9:]} ({vendor.group(1).strip() if vendor else 'vendor unknown'})"
    if major < MIN_JAVA:
        return item("fail", "java", detail, f"Drishti needs Java {MIN_JAVA} or newer: install a JDK {MIN_JAVA} and set drishti.java.home in your settings file")
    return item("ok", "java", detail)


def jar_check(find_jar) -> dict:
    try:
        jar = find_jar(None)
    except Exception as e:                       # noqa: BLE001
        return item("fail", "server jar", str(e), "python tools/drishti.py build jar   (or pass --jar)")
    m = re.search(r"drishti-server-(.+?)-exec\.jar", os.path.basename(jar))
    return item("ok", "server jar", f"{jar} (version {m.group(1) if m else 'unknown'})")


def python_check() -> list[dict]:
    v = sys.version_info
    out = [item("ok" if v >= (3, 10) else "fail", "python", f"{sys.executable}: Python {v.major}.{v.minor}.{v.micro}",
                "" if v >= (3, 10) else "the tools need Python 3.10+")]
    for mod, pip, why in LIBS:
        if importlib.util.find_spec(mod):
            out.append(item("ok", f"python {pip}", "installed"))
        else:
            out.append(item("warn", f"python {pip}", f"not installed (needed for {why})",
                            "python tools/drishti.py setup   (checks this Python's libraries; you install what is missing with the pip commands it prints)"))
    return out


def layout_check(root: pathlib.Path) -> list[dict]:
    out = [item("ok" if (root / "config" / "packs").is_dir() else "fail", "repo config/packs/", str(root / "config" / "packs"),
                "" if (root / "config" / "packs").is_dir() else "run from a Drishti checkout (config/packs/ holds the shipped packs)")]
    return out


def dir_check(env) -> list[dict]:
    out = []
    for var, what in DIR_VARS:
        val = env.get(var)
        if not val:
            continue
        p = pathlib.Path(val)
        if not p.is_dir():
            out.append(item("warn", var, f"{val} ({what}) does not exist yet", f"mkdir -p {val}   (or fix {var})"))
            continue
        can_r, can_w = os.access(p, os.R_OK | os.X_OK), os.access(p, os.W_OK)
        if not can_r:
            out.append(item("fail", var, f"{val} ({what}) is not readable", f"chmod u+rx {val}"))
        elif not can_w:
            out.append(item("warn", var, f"{val} ({what}) is readable but not writable (fine for the server, not for ingest)", f"chmod u+w {val}"))
        else:
            out.append(item("ok", var, f"{val} ({what}) readable and writable"))
    if not out:
        out.append(item("ok", "configured directories", "none of " + ", ".join(v for v, _ in DIR_VARS[:3]) + " is set"))
    return out


def probe(port: int, timeout: float = 1.5) -> tuple[str, str]:
    """(state, detail) for localhost:port: free, drishti, other. Only a GET of /actuator/health."""
    try:
        with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(f"http://localhost:{port}/actuator/health", timeout=timeout) as r:     # noqa: S310
            body = r.read(2000)
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return "console", "answers 404 on /actuator/health, as the Drishti console does"
        return "other", f"answers HTTP {e.code} on /actuator/health"
    except (urllib.error.URLError, OSError, TimeoutError):
        return "free", ""
    try:
        return ("drishti", f"status {json.loads(body).get('status')}") if "status" in json.loads(body) else ("other", "answers, but not as Drishti")
    except ValueError:
        return "other", "answers, but not as Drishti"


def port_check(ports=PORTS) -> list[dict]:
    out = []
    for port, what in ports:
        state, detail = probe(port)
        if state == "free":
            out.append(item("ok", f"port {port}", f"free (the default {what} port)"))
        elif state in ("drishti", "console"):
            out.append(item("ok", f"port {port}", f"in use by Drishti: {detail}"))
        else:
            out.append(item("warn", f"port {port}", f"in use ({detail}); a {what} started on it would fail",
                            f"start on another port (server.port / the console's port), or stop what holds {port}; Drishti does not touch it"))
    return out


def server_check(api) -> list[dict]:
    out = []
    try:
        h = api.json("GET", "/api/v1/admin/health")
        v = h.get("server", {})
        out.append(item("ok" if h.get("status") == "OK" else "warn", "server", f"{api.base}: version {v.get('version')}, java {v.get('java')}, status {h.get('status')}"))
    except Exception as e:                       # noqa: BLE001
        status = getattr(e, "status", None)
        if status in (401, 403):
            out.append(item("ok", "server", f"{api.base} is reachable (the admin health needs an administrator)"))
        else:
            return [item("fail", "server", str(e), "start the server, or fix --server / DRISHTI_SERVER")]
    if not api.token:
        out.append(item("warn", "token", "none given: calls run anonymously or as --user", "set DRISHTI_TOKEN or --token-file (My account, API tokens)"))
        return out
    try:                                         # the token this very call used: exact id, scopes and expiry
        me = api.json("GET", "/api/v1/me/token")
        out.append(item("ok", "token", f"accepted; this token '{me.get('id')}' acts as {me.get('user')}: scopes {', '.join(me.get('scopes') or ['read'])}"
                        f", expires {me.get('expiresAt') or 'never'}"))
        return out
    except Exception as e:                       # noqa: BLE001
        status = getattr(e, "status", None)
        if status == 401:
            out.append(item("fail", "token", "the server rejected the token (401)", "make a new one: My account, API tokens; check it is not expired or revoked"))
            return out
        if status == 404:
            out.append(item("ok", "token", "accepted as a signed-in session (not a personal drk_ token): its power is the user's roles"))
            return out
    try:                                         # an older server without /me/token: guess from the token list
        toks = api.json("GET", "/api/v1/me/tokens")
    except Exception as e:                       # noqa: BLE001
        out.append(item("warn", "token", f"accepted, but its scopes could not be read: {str(e)[:100]}"))
        return out
    active = [t for t in toks if t.get("active")] if isinstance(toks, list) else []
    if not active:
        out.append(item("warn", "token", "accepted; this user lists no personal (drk_) token, so scopes are the user's roles"))
    else:
        used = max(active, key=lambda t: t.get("lastUsedAt") or "")
        out.append(item("ok", "token", f"accepted; most recently used personal token '{used.get('name')}': scopes {', '.join(used.get('scopes') or ['read'])}"
                        f", expires {used.get('expiresAt') or 'never'}"))
    return out


def run(env, root: pathlib.Path, find_jar, find_java, api=None, java: str | None = None) -> list[dict]:
    try:
        found = java_check(find_java(java))
    except Exception as e:                       # noqa: BLE001 - no Java, or one older than 21: said in words
        found = item("fail", "java", str(e), f"install a JDK {MIN_JAVA} and set drishti.java.home in your settings file (or pass --java)")
    out = [found, jar_check(find_jar)]
    out += python_check() + layout_check(root) + dir_check(env) + port_check()
    if api is not None:
        out += server_check(api)
    return out


def exit_code(results: list[dict]) -> int:
    return 1 if any(r["level"] == "fail" for r in results) else 0


def render(results: list[dict], say=print) -> None:
    if results and all(r.get("acceptance") for r in results):
        return render_acceptance(results, say)
    mark = {"ok": "GREEN ", "warn": "YELLOW", "fail": "RED   "}
    for r in results:
        say(f"{mark[r['level']]} {r['name']:<24}{r['detail']}")
        if r["fix"] and r["level"] != "ok":
            say(f"       {'':<24}fix: {r['fix']}")
    bad, warn = sum(r["level"] == "fail" for r in results), sum(r["level"] == "warn" for r in results)
    say(f"doctor: {'all clear' if not bad and not warn else f'{bad} red, {warn} yellow'}")


# ---- the acceptance check: the end-to-end proof a person (or the release workflow) runs after the demo stack is up ------------------

#: the release's figures for the demo lake (docs/DEPLOYMENT_AND_QA_GUIDE.md 9.6, QUICKSTART_DEVELOPERS.md 3.1)
ACCEPT_VAR, ACCEPT_ES = 167_372_692, 210_131_972
ACCEPT_USER = "drishti-dev-admin"


def _opener():
    """An HTTP opener for Drishti's own addresses: a cookie jar, and NO proxy (a company proxy never sits between Drishti and itself)."""
    import http.cookiejar
    return urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))


def _procs():
    """tools/launch/procs.py: the one pasteable command (procs.command) and the one failure shape (procs.fail)."""
    p = str(pathlib.Path(__file__).resolve().parent / "launch")
    if p not in sys.path:
        sys.path.insert(0, p)
    import procs
    return procs


def _settings() -> tuple[str, dict]:
    """(the settings files this run was given, comma-joined; their keys), as tools/drishti.py handed them to javacmd; ("", {}) alone."""
    try:
        p = str(pathlib.Path(__file__).resolve().parent)
        if p not in sys.path:
            sys.path.insert(0, p)
        import javacmd
        return javacmd.settings()
    except Exception:                                   # noqa: BLE001 - doctor used as a library without the CLI
        return "", {}


def demo_profile(settings: str) -> bool:
    """The run names the demo profile (``--profile demo`` or ``start ... --demo``: config/profiles/demo.ini among the settings files)."""
    return any(pathlib.PurePath(f.strip()).name == "demo.ini" and pathlib.PurePath(f.strip()).parent.name == "profiles"
               for f in (settings or "").split(",") if f.strip())


def own_settings(settings: str) -> str | None:
    """The person's own settings file(s) of ``settings``: the committed profiles taken out (a fix line names them by --profile)."""
    mine = [f for f in (settings or "").split(",") if f.strip() and pathlib.PurePath(f.strip()).parent.name != "profiles"]
    return ",".join(mine) or None


def _get(op, url: str, timeout: float = 5) -> tuple[int, bytes]:
    """(status, body) of a GET; status 0 when nothing answers."""
    try:
        with op.open(url, timeout=timeout) as r:
            return r.status, r.read(20000)
    except urllib.error.HTTPError as e:
        return e.code, e.read(2000) if e.fp else b""
    except (OSError, ValueError):
        return 0, b""


def _a(level: str, name: str, detail: str, fix: str = "") -> dict:
    return {**item(level, name, detail, fix), "acceptance": True}


def acceptance(console: str, password: str | None = None, expect_var: int = ACCEPT_VAR, expect_es: int = ACCEPT_ES, wait: int = 0,
               opener=None, server: str | None = None, settings: str | None = None, keys: dict | None = None, env=None) -> list[dict]:
    """The end-to-end proof that a first run worked: the server answers its health, the console answers and reaches that server, the
    development administrator signs in to the console (ONLY under the demo profile; another profile needs DRISHTI_SEED_PASSWORD, and
    DRISHTI_SEED_USERNAME when not the development administrator), ``VAR BANK`` is asked through the console (so the two are proved to
    share their secrets) and both figures equal the release's for the configured demo lake. One result per step; drishti.py renders
    them as one PASS line, or one ``[FAIL] ... Fix: <command>`` per problem (exit 1). ``password``: what drishti.py found (the
    environment's, else the development one); used only as the rules above allow. Every call is direct (no proxy)."""
    import time
    from urllib.parse import quote, urlencode
    procs = _procs()
    env = os.environ if env is None else env
    if settings is None or keys is None:
        s, k = _settings()
        settings = s if settings is None else settings
        keys = k if keys is None else keys
    mine = own_settings(settings)
    cmd = lambda *a: procs.command(*a, settings=mine)            # noqa: E731 - every fix line pasteable as printed
    start_line = cmd("start", "all", "--demo")
    base = console.rstrip("/")
    srv = (server or f"http://127.0.0.1:{(keys.get('server.port') or '').strip() or env.get('DRISHTI_PORT') or 18480}").rstrip("/")
    explicit = (env.get("DRISHTI_SEED_PASSWORD") or "").strip()
    if not demo_profile(settings) and not explicit:
        return [_a("fail", "demo profile", "the development administrator signs in only under the demo profile, and this run names none "
                   "(nor DRISHTI_SEED_PASSWORD for another administrator)", cmd("--profile", "demo", "doctor", "--acceptance"))]
    user = ((env.get("DRISHTI_SEED_USERNAME") or "").strip() or ACCEPT_USER) if explicit else ACCEPT_USER
    password = explicit or password or ""
    if not password:
        sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent / "launch"))
        import devsecrets
        password = devsecrets.DEV["DRISHTI_SEED_PASSWORD"]
    op = opener or _opener()
    out = []
    deadline = time.time() + max(0, wait)
    while True:                                    # both health checks, waiting up to --wait for them together
        s_code, s_body = _get(op, srv + "/actuator/health")
        c_code, _ = _get(op, base + "/healthz")
        s_up = s_code == 200 and b'"UP"' in s_body.replace(b" ", b"")
        if (s_up and c_code == 200) or time.time() >= deadline:
            break
        time.sleep(2)
    if not s_up:
        out.append(_a("fail", "server", f"{srv}/actuator/health " + (f"answers HTTP {s_code}" if s_code else "does not answer"), start_line))
    else:
        out.append(_a("ok", "server", f"{srv} is UP"))
    if c_code != 200:
        out.append(_a("fail", "console", f"{base}/healthz " + (f"answers HTTP {c_code}" if c_code else "does not answer"), start_line))
        return out
    out.append(_a("ok", "console", f"{base} answers"))
    if not s_up:
        return out
    while True:
        r_code, r_body = _get(op, base + "/readyz", 30)
        if r_code == 200 or time.time() >= deadline:
            break
        time.sleep(2)
    if r_code != 200:
        why = ""
        try:
            why = json.loads(r_body or b"{}").get("detail") or ""
        except ValueError:
            pass
        return out + [_a("fail", "console to server", f"{base} cannot reach its server" + (f" ({why[:160]})" if why else ""),
                         cmd("stop", "all") + "   then   " + start_line)]
    out.append(_a("ok", "console to server", "the console reaches its server"))
    try:
        with op.open(base + "/login", data=urlencode({"user": user, "password": password, "next": "/t"}).encode(), timeout=60) as r:
            page = r.read().decode("utf-8", "replace")
            landed = r.geturl()
    except urllib.error.HTTPError as e:
        refused = e.code == 401
        return out + [_a("fail", "sign-in", f"{user} was refused by the console (HTTP {e.code})" + (
            ": the server and the console were not started with the same secrets, or this is not the demo's store" if refused else ""),
            cmd("stop", "all") + "   then   " + start_line)]
    except OSError as e:
        return out + [_a("fail", "sign-in", f"{base}/login: {e}", cmd("status"))]
    if "/login" in landed and "password" in page.lower():
        return out + [_a("fail", "sign-in", f"{user} was refused", cmd("stop", "all") + "   then   " + start_line)]
    out.append(_a("ok", "sign-in", f"{user} signed in to the console"))
    try:
        with op.open(base + "/go?q=" + quote("VAR BANK"), timeout=300) as r:       # the command a person types: the VaR screen opens
            r.read()
        with op.open(base + "/api/var/scope?scope=BANK", timeout=300) as r:       # its figures, as the screen asks for them, through the console
            doc = json.loads(r.read().decode("utf-8", "replace"))
    except urllib.error.HTTPError as e:
        detail = ""
        try:
            detail = json.loads(e.read(4000) or b"{}").get("detail") or ""
        except (ValueError, OSError):
            pass
        return out + [_a("fail", "VAR BANK", f"the console answered HTTP {e.code}" + (f": {detail[:200]}" if detail else ""),
                         cmd("lake", "demo", "--engine", "java") + "   then restart:   " + cmd("stop", "all") + "   then   " + start_line)]
    except (OSError, ValueError) as e:
        return out + [_a("fail", "VAR BANK", f"no answer from {base}: {e}", cmd("status"))]
    for name, key, want in (("VaR 99%", "var", expect_var), ("ES 97.5%", "es", expect_es)):
        got = doc.get(key) if isinstance(doc, dict) else None
        good = isinstance(got, (int, float)) and not isinstance(got, bool) and round(got) == want
        shown = f"{got:,.0f}" if isinstance(got, (int, float)) and not isinstance(got, bool) else ("no figure" if got is None else str(got))
        out.append(_a("ok" if good else "fail", f"VAR BANK {name}", shown if good else f"{shown}; the release's figure is {want:,} on the demo lake",
                      "" if good else cmd("lake", "demo", "--engine", "java") + "   (the demo lake as released), then restart:   "
                      + cmd("stop", "all") + "   then   " + start_line))
    return out


def render_acceptance(results: list[dict], say=print) -> None:
    """One PASS line, or one ``[FAIL] <what>: <why>. Fix: <command>`` line per problem; nothing else."""
    bad = [r for r in results if r["level"] == "fail"]
    if bad:
        procs = _procs()
        for r in bad:
            say(procs.fail(r["name"], r["detail"], r["fix"] or "see the line above"))
        return
    got = {r["name"]: r["detail"] for r in results}
    who = next((d.split(" signed in")[0] for n, d in got.items() if n == "sign-in"), ACCEPT_USER)
    say(f"PASS  Drishti acceptance: VAR BANK VaR 99% {got.get('VAR BANK VaR 99%', '?')} and ES 97.5% {got.get('VAR BANK ES 97.5%', '?')}, "
        f"the release's figures; {got.get('server', '')}, console {got.get('console', '')}, signed in as {who}")
