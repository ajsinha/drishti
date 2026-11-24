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
                            "python tools/drishti.py setup   (installs what is missing into this Python from the offline wheel pack: ~/drishti-wheels, or --wheels <folder>)"))
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


def acceptance(console: str, password: str, expect_var: int = ACCEPT_VAR, expect_es: int = ACCEPT_ES, wait: int = 0, opener=None) -> list[dict]:
    """Signs in to the console as the development administrator, asks ``VAR BANK`` through it (so the console and the server are proved
    to share their secrets) and finds the release's two figures on the page; one result line each step."""
    import time
    from urllib.parse import quote, urlencode
    base = console.rstrip("/")
    op = opener or _opener()
    out = []
    deadline = time.time() + max(0, wait)
    while True:
        try:
            with op.open(base + "/healthz", timeout=5) as r:
                r.read()
            break
        except OSError as e:
            if time.time() >= deadline:
                return [item("fail", "console", f"{base}: {e}", "start it: drishti.py start all --demo (or the launcher Drishti: run console)")]
            time.sleep(3)
    out.append(item("ok", "console", f"{base} answers"))
    try:
        with op.open(base + "/login", data=urlencode({"user": ACCEPT_USER, "password": password, "next": "/t"}).encode(), timeout=60) as r:
            page = r.read().decode("utf-8", "replace")
            landed = r.geturl()
    except urllib.error.HTTPError as e:
        return out + [item("fail", "sign-in", f"{ACCEPT_USER}: HTTP {e.code}", "start the server and the console with the same settings (drishti.py start all --demo)")]
    except OSError as e:
        return out + [item("fail", "sign-in", f"{e}", "is the server up? drishti.py status")]
    if "/login" in landed and "password" in page.lower():
        return out + [item("fail", "sign-in", f"{ACCEPT_USER} was refused", "the demo's development administrator: start with --demo")]
    out.append(item("ok", "sign-in", f"{ACCEPT_USER} signed in to the console"))
    try:
        with op.open(base + "/go?q=" + quote("VAR BANK"), timeout=300) as r:       # the command a person types: the VaR screen opens
            r.read()
        with op.open(base + "/api/var/scope?scope=BANK", timeout=300) as r:       # its figures, as the screen asks for them, through the console
            doc = json.loads(r.read().decode("utf-8", "replace"))
    except (urllib.error.HTTPError, OSError, ValueError) as e:
        return out + [item("fail", "VAR BANK", f"{e}", "drishti.py status; the server's log under <data>/logs")]
    for name, key, want in (("VaR 99%", "var", expect_var), ("ES 97.5%", "es", expect_es)):
        got = doc.get(key)
        good = isinstance(got, (int, float)) and round(got) == want
        out.append(item("ok" if good else "fail", f"VAR BANK {name}", f"{got:,.0f}" if isinstance(got, (int, float)) else str(got),
                        "" if good else f"the release's figure is {want:,}: is the demo lake built? drishti.py lake demo --engine java"))
    return out
