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
"""The drill's container smoke test (docs/architecture/TEST_HARNESS.md, "The container smoke test"): the images as shipped, booted.

  container_smoke.py run [--keep]      build both images (deploy/docker/server.Dockerfile, console.Dockerfile), boot a server and a
                                       console from them over the page walk's cached demo lake (read-only), then check: the server's
                                       health, the VaR golden through the server's API (container_smoke.json: BANK and a desk, VaR and
                                       ES at the walk's fixed business date), and a few console pages through Chromium (each answers 200,
                                       draws, and raises no page error). Containers removed at the end (--keep leaves them running).
  container_smoke.py record            the same boot, then writes the figures it read to container_smoke.json (after a change that
                                       moves the demo lake's figures on purpose; the commit says why)
  container_smoke.py full-needed [--base REF]
                                       exit 0 when the commits since REF (default origin/main, the last release) touch what only the full
                                       in-container suite proves (the Dockerfiles, the JDK version, the native libraries, or a module
                                       with Testcontainers tests), printing why; exit 1 when the smoke test alone is enough

Everything runs on the host's network (docker build --network host, docker run --network host): no bridge, no virtual interface comes
or goes, so Chromium in the browser lane beside it never sees the network change (ERR_NETWORK_CHANGED, TEST_HARNESS.md rule 8), and
the smoke test runs beside the browser lane instead of after it. The server container is capped at 3 GB (its image's
MaxRAMPercentage applies to that), the console at 1 GB. Run with the console's Python (Playwright).
"""
from __future__ import annotations

import argparse
import json
import os
import re
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
GOLDEN = Path(__file__).resolve().parent / "container_smoke.json"
TAG = "drill-smoke"
LABEL = "drishti.drill=smoke"
#: the scopes whose VaR and ES must match the golden (the server's own figures, to the cent)
SCOPES = ("BANK", "DESK:DESK-RATES")
#: the console pages opened through the console container: Home, the VaR page (it draws from the engine), Help, Admin -> Engines
PAGES = ("/", "/var?scope=BANK", "/help", "/admin/engines")
#: what only the full in-container suite (every Testcontainers test, tools/drill.sh java21-docker) proves, by path (regular expressions)
FULL_TRIGGERS = {
    r"^deploy/docker/": "the Dockerfiles or the compose files",
    r"(^|/)\.dockerignore$": "the images' build context",
    r"(^|/)pom\.xml$": "a pom (the JDK version, a native library or a container image version may have changed)",
    r"^\.mvn/|^mvnw": "the Maven wrapper (the JDK it runs on)",
    r"^drishti-native/": "the native libraries",
    r"^bin/drishti-node\.sh$": "the image's entry point",
}


def sh(*cmd: str, check: bool = True, quiet: bool = False, **kw) -> subprocess.CompletedProcess:
    if not quiet:
        print("smoke: $ " + " ".join(cmd), flush=True)
    return subprocess.run(list(cmd), check=check, text=True, cwd=ROOT, **kw)


def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def container_test_modules() -> dict[str, str]:
    """{module folder: a test class} of every module with a Testcontainers test (the same search as tools/drill.sh docker_test_classes)."""
    out: dict[str, str] = {}
    for p in ROOT.rglob("*Test.java"):
        rel = p.relative_to(ROOT).as_posix()
        if "/target/" in rel or rel.startswith(".claude/") or "/src/test/java/" not in rel:
            continue
        if "org.testcontainers" in p.read_text(encoding="utf-8", errors="replace"):
            out.setdefault(rel.split("/src/test/java/")[0], p.stem)
    return out


def full_reasons(changed: list[str]) -> list[str]:
    """Why the full in-container suite must run for these changed paths (empty: the smoke test is enough)."""
    why = []
    for path in changed:
        for pattern, what in FULL_TRIGGERS.items():
            if re.search(pattern, path):
                why.append(f"{path}: {what}")
                break
    mods = container_test_modules()
    for path in changed:
        for mod in mods:
            if path.startswith(mod + "/src/") and f"module {mod}" not in " ".join(why):
                why.append(f"{path}: module {mod} has container tests ({mods[mod]} ...)")
    return why


def cmd_full_needed(a) -> int:
    r = sh("git", "diff", "--name-only", f"{a.base}..HEAD", check=False, quiet=True, capture_output=True)
    if r.returncode != 0:
        print(f"smoke: cannot diff against {a.base}: the full in-container suite runs")
        return 0
    why = full_reasons([p for p in r.stdout.splitlines() if p])
    for w in why[:20]:
        print("smoke: full in-container suite: " + w)
    return 0 if why else 1


def lake() -> Path:
    """The page walk's lake (its fixed business date), from the cache the browser lane shares: built once per hash of the sources."""
    sys.path.insert(0, str(ROOT / "drishti-console" / "tests"))
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    import lake_cache
    from test_page_walk_browser import SNAPSHOT_DATE, WALK_DATA
    from var_stack import var_data

    return (lake_cache.demo_lake(var_data(*WALK_DATA), SNAPSHOT_DATE, ("--exposure", "committed")) / "delta").resolve()


def get(url: str, timeout: float = 30) -> tuple[int, dict | str]:
    try:
        with urllib.request.urlopen(url, timeout=timeout) as r:
            body = r.read().decode()
            return r.status, json.loads(body) if body[:1] in "{[" else body
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode(errors="replace")[:300]


def wait_for(what: str, probe, seconds: float, container: str) -> None:
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        try:
            if probe():
                return
        except OSError:
            pass
        state = sh("docker", "inspect", "-f", "{{.State.Running}}", container, check=False, quiet=True, capture_output=True).stdout.strip()
        if state != "true":
            raise SystemExit(f"smoke: FAILED: {container} stopped before {what}:\n" + logs(container))
        time.sleep(1)
    raise SystemExit(f"smoke: FAILED: no {what} within {seconds:.0f} s:\n" + logs(container))


def logs(container: str) -> str:
    return sh("docker", "logs", "--tail", "40", container, check=False, quiet=True, capture_output=True).stdout


def figures(server: str, date: str) -> dict[str, dict[str, float]]:
    out = {}
    for scope in SCOPES:
        status, body = get(f"{server}/api/v1/risk/var/scope?scope={urllib.parse.quote(scope)}&asOf={date}", timeout=120)
        if status != 200 or not isinstance(body, dict):
            raise SystemExit(f"smoke: FAILED: the VaR of {scope} answered {status}: {str(body)[:300]}")
        out[scope] = {"var": round(float(body["var"]), 2), "es": round(float(body["es"]), 2)}
    return out


def pages(console: str) -> list[str]:
    from playwright.sync_api import sync_playwright

    faults = []
    with sync_playwright() as p:
        b = p.chromium.launch()
        try:
            for href in PAGES:
                pg = b.new_page(viewport={"width": 1366, "height": 900})
                errors: list[str] = []
                pg.on("pageerror", lambda e, errors=errors: errors.append(str(e)))
                r = pg.goto(console + href, wait_until="load", timeout=120_000)
                if not r or r.status != 200:
                    faults.append(f"{href}: HTTP {r.status if r else 'none'}")
                    continue
                try:   # the page's own settled signal (drishti_settled.js); the VaR page also marks itself painted
                    pg.evaluate("(o) => window.Drishti && window.Drishti.settled ? window.Drishti.settled(o) : null",
                                {"quiet": 50, "timeout": 120_000, "animations": False})
                    if href.startswith("/var"):
                        pg.wait_for_selector("[data-var][data-painted]", state="attached", timeout=120_000)
                except Exception as e:  # noqa: BLE001 - the page that never settles is the fault reported
                    faults.append(f"{href}: did not draw: {str(e).splitlines()[0][:200]}")
                text = pg.inner_text("body")[:4000]
                if re.search(r"Server error|Internal Server Error|Traceback", text):
                    faults.append(f"{href}: an error page: {text[:200]!r}")
                faults += [f"{href}: page error: {e[:200]}" for e in errors]
                print(f"smoke: page {href}: {'ok' if not any(f.startswith(href + ':') for f in faults) else 'FAULT'}", flush=True)
                pg.close()
        finally:
            b.close()
    return faults


def boot_and_check(record: bool, keep: bool) -> int:
    t0 = time.monotonic()
    jars = sorted((ROOT / "drishti-server" / "target").glob("drishti-server-*-exec.jar"))
    if not jars:
        raise SystemExit("smoke: FAILED: no server jar (the gate's build makes it: ./mvnw -o package -DskipTests)")
    for kind in ("server", "console"):
        sh("docker", "build", "-q", "--network", "host", "--label", LABEL, "-f", f"deploy/docker/{kind}.Dockerfile",
           "-t", f"drishti-{kind}:{TAG}", ".", stdout=subprocess.DEVNULL)
    print(f"smoke: images built in {time.monotonic() - t0:.0f} s", flush=True)
    lk = lake()
    from test_page_walk_browser import SNAPSHOT_DATE   # on the path once lake() ran
    sp, cp, run = free_port(), free_port(), os.getpid()
    server, console = f"drill-smoke-server-{run}", f"drill-smoke-console-{run}"
    try:
        sh("docker", "run", "-d", "--network", "host", "--memory", "3g", "--name", server, "--label", LABEL,
           "-e", f"DRISHTI_PORT={sp}", "-e", "DRISHTI_DELTA_ROOT=/lake", "-e", "DRISHTI_DEMO_ENABLED=false", "-e", "DRISHTI_OFFLINE=true",
           "-e", "DRISHTI_VAR_SYSTEM_BASKETS_SEED=true", "-e", "DRISHTI_PACKS=default",
           "-v", f"{lk}:/lake:ro", f"drishti-server:{TAG}", stdout=subprocess.DEVNULL)
        base = f"http://127.0.0.1:{sp}"
        wait_for("liveness", lambda: get(base + "/actuator/health/liveness", 3)[0] == 200, 300, server)
        wait_for("VaR engine", lambda: get(f"{base}/api/v1/risk/var/scope?scope=BANK&asOf={SNAPSHOT_DATE}", 60)[0] == 200, 300, server)
        wait_for("health UP (liveness and readiness)", lambda: get(base + "/actuator/health", 5)[0] == 200, 300, server)
        print(f"smoke: server health {get(base + '/actuator/health')}", flush=True)
        got = figures(base, SNAPSHOT_DATE)
        if record:
            GOLDEN.write_text(json.dumps({"date": SNAPSHOT_DATE, "figures": got}, indent=1) + "\n", encoding="utf-8")
            print(f"smoke: recorded {GOLDEN.relative_to(ROOT)}: {got}")
        else:
            want = json.loads(GOLDEN.read_text(encoding="utf-8"))
            if want["date"] != SNAPSHOT_DATE or want["figures"] != got:
                print(f"smoke: FAILED: the VaR golden differs (container_smoke.py record, when the change is meant):\n  want {want}\n  got  "
                      f"{{'date': {SNAPSHOT_DATE!r}, 'figures': {got}}}", file=sys.stderr)
                return 1
            print(f"smoke: VaR golden matches: {got}", flush=True)
        sh("docker", "run", "-d", "--network", "host", "--memory", "1g", "--name", console, "--label", LABEL,
           "-e", f"DRISHTI_CONSOLE_PORT={cp}", "-e", f"DRISHTI_BACKEND_URL={base}", "-e", "DRISHTI_CONSOLE_HOST=127.0.0.1",
           f"drishti-console:{TAG}", stdout=subprocess.DEVNULL)
        cbase = f"http://127.0.0.1:{cp}"
        wait_for("console", lambda: get(cbase + "/healthz", 3)[0] == 200, 120, console)
        faults = pages(cbase)
        if faults:
            print("smoke: FAILED: pages:\n  " + "\n  ".join(faults), file=sys.stderr)
            print(logs(console), file=sys.stderr)
            return 1
        print(f"smoke: passed in {time.monotonic() - t0:.0f} s", flush=True)
        return 0
    finally:
        if not keep:
            sh("docker", "rm", "-f", server, console, check=False, quiet=True, capture_output=True)
            # the images of earlier runs this tag replaced: dangling, labelled ours
            sh("docker", "image", "prune", "-f", "--filter", f"label={LABEL}", check=False, quiet=True, capture_output=True)


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0], formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    r = sub.add_parser("run")
    r.add_argument("--keep", action="store_true")
    sub.add_parser("record")
    f = sub.add_parser("full-needed")
    f.add_argument("--base", default="origin/main")
    a = ap.parse_args(argv)
    if a.cmd == "full-needed":
        return cmd_full_needed(a)
    return boot_and_check(a.cmd == "record", getattr(a, "keep", False))


if __name__ == "__main__":
    sys.exit(main())
