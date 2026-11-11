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
"""The console's visual regression check: the same screens, in every theme, from two checkouts, compared pixel by pixel.

A CSS change that should not change the look (tokens in place of literals, folded rules, specificity in place of !important) is proved
by pictures: a representative set of screens (landing, the VaR explorer, Pivot, BI, Build home, Admin, Help, and the phone width) in each
theme of drishti_tokens.css, taken from a "before" checkout and an "after" checkout against ONE scratch server, so both read the very
same data, and compared pixel by pixel with a tiny tolerance for anti-aliasing.

    # both checkouts, one server, then the comparison (exit 1 when a picture differs)
    drishti-console/.venv/bin/python tools/dev/css_visual_check.py run --before <base checkout> --after . --out data/tmp/cssvis/out

    # the parts on their own
    ... css_visual_check.py serve                                  # the scratch server alone (Ctrl+C stops it)
    ... css_visual_check.py shoot --tree <checkout> --out <dir>    # one checkout's pictures (starts the server unless one answers)
    ... css_visual_check.py compare <before dir> <after dir> --out <diff dir>

Everything it makes lives under <this checkout>/data/tmp/cssvis/: the VaR lake (built once with `drishti.py lake demo --only` the VaR
kinds, as tools/docs/shots/_var.py does, and reused), the server's work folder (a copy of config/, never a link), each console's data
folder (fresh per picture run) and the temp folders (TMPDIR; /tmp can be RAM). The theme is set as the app sets it before first paint
(localStorage 'drishti.theme', read by static/js/drishti_theme-init.js); the pictures wait on the page's own signal
(window.Drishti.settled, static/js/drishti_settled.js), with animations off and the caret hidden; live parts that change on their own
(the clock, toasts) are masked."""
from __future__ import annotations

import argparse
import contextlib
import json
import os
import shutil
import signal
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
from javacmd import java_cmd  # noqa: E402
sys.path.insert(0, str(ROOT / "tools" / "lake"))
import lakeconfig  # noqa: E402  (the demo lake root's variable)

WORK = ROOT / "data" / "tmp" / "cssvis"
MAIN = Path("/home/ashutosh/IdeaProjects/drishti")
VENV_PY = Path(os.environ.get("CSSVIS_PYTHON", str(MAIN / "drishti-console" / ".venv" / "bin" / "python")))
JAR = Path(os.environ.get("CSSVIS_JAR", str(MAIN / "kernel/drishti-server" / "target" / "drishti-server-2.9.11.g-exec.jar")))
JAVA = Path(os.environ.get("JAVA_HOME", "/usr/lib/jvm/java-21-openjdk-amd64")) / "bin" / "java"
SERVER_PORT = int(os.environ.get("CSSVIS_SERVER_PORT", 18991))
CONSOLE_PORTS = (int(os.environ.get("CSSVIS_CONSOLE_PORT_A", 17991)), int(os.environ.get("CSSVIS_CONSOLE_PORT_B", 17992)))
VAR_DATA = ",".join([f"market-risk/{k}" for k in ("pnl-vector", "scenario-pv", "fx-scenario", "scenario-set", "portfolio", "var-limit")] + ["core/desk", "core/book", "core/legal-entity"]
                    + ["trades/murex"])
SITE_CONFIG = {"connectors", "net"}           # the site's own config parts: never copied (the server writes its connector files itself)

THEMES = ("terminal", "light", "blue", "green", "wallstreet", "crimson", "crimson-dark", "terminal-green", "terminal-crimson", "terminal-blue")
WIDE, HIGH = 1440, 900
PHONE = (390, 844)
#: name, path, (width, height), a JavaScript condition the page must reach before it is pictured (None: settled is enough)
SCREENS = (
    ("landing", "/", (WIDE, HIGH), None),
    ("var", "/var?scope=BANK", (WIDE, HIGH), "document.querySelector('[data-var]') && document.querySelector('[data-var]').dataset.painted === 'BANK'"),
    ("pivot", "/pivot?source=trade", (WIDE, HIGH), "document.querySelector('[data-pv-grid] .dg-cell[data-cell]') && !/computing/.test((document.querySelector('[data-pv-meta]') || {}).textContent || '')"),
    ("bi", "/bi/r/file:report:market-risk-var", (WIDE, HIGH),
     "document.querySelector('.bi-vis, .bi-dvis') && !document.querySelector('.bi-vis-busy, .bi-busy, [aria-busy=\"true\"]')"),
    ("build", "/build", (WIDE, HIGH), None),
    ("admin", "/admin", (WIDE, HIGH), None),
    ("help", "/help", (WIDE, HIGH), None),
    ("phone-landing", "/", PHONE, None),
    ("phone-var", "/var?scope=BANK", PHONE, "document.querySelector('[data-var]') && document.querySelector('[data-var]').dataset.painted === 'BANK'"),
    ("phone-help", "/help", PHONE, None),
)
#: parts that change on their own between two loads of the same page: masked (a flat box) in every picture
MASKS = ("[data-clock]", ".toast-a", ".toast", "[data-toast]", "[data-live-age]", "[data-ago]", "time[data-relative]",
         ".bi-state-chip")   # a BI visual's live state: "Listening", or "Too many live queries" while the server still holds closed pages' queries
#: texts that carry a measured time (a paint or compute in ms): replaced by a fixed text before the picture, so their width (and what
#: sits after them on the line) is the same in every run; a mask would not do, the box of a longer text moves its neighbours
TIMED_TEXT = ("[data-vx-timing]", "[data-vx-fresh]", "[data-xx-timing]", "[data-pv-meta]", ".bi-vis-ms")
FREEZE_JS = "(sels) => sels.forEach(s => document.querySelectorAll(s).forEach(e => { if (e.textContent.trim()) e.textContent = 'timed'; }))"
SETTLED_JS = "drishti-console/web/static/js/drishti_settled.js"
STABLE_TRIES = 6                              # pictures 0.5 s apart until two are the same


# ---- processes -------------------------------------------------------------------------------------------------------------------------

def _answers(url: str, timeout: float = 2) -> bool:
    try:
        urllib.request.urlopen(url, timeout=timeout).read(10)
        return True
    except urllib.error.HTTPError:
        return True
    except OSError:
        return False


def _env(extra: dict) -> dict:
    tmp = WORK / "tmp"
    tmp.mkdir(parents=True, exist_ok=True)
    return {**os.environ, "TMPDIR": str(tmp), "TMP": str(tmp), "TEMP": str(tmp), **extra}


def lake() -> Path:
    """The scratch VaR lake, built once and reused, so every run reads the same data."""
    out = WORK / "lake" / "delta"
    if (out / "risk" / "pnl-vector").is_dir() and (WORK / "lake" / "built").exists():
        return out
    shutil.rmtree(WORK / "lake", ignore_errors=True)
    (WORK / "lake").mkdir(parents=True)
    print("building the VaR lake (once) ...", flush=True)
    r = subprocess.run([str(VENV_PY), str(ROOT / "tools" / "drishti.py"), "lake", "demo", "--lake", str(out), "--data-dir", str(WORK / "lake" / "data"),
                        "--connectors-dir", str(WORK / "lake" / "connectors"), "--as-of", "data", "--only", VAR_DATA, "--no-signal", "-q",
                        "--engine", "deltalake"], capture_output=True, text=True, cwd=ROOT, env=_env({"DRISHTI_JAR": str(JAR)}), timeout=1800)
    if r.returncode != 0 or not (out / "risk" / "pnl-vector").is_dir():
        sys.exit("could not build the VaR lake: " + (r.stderr or r.stdout)[-800:])
    (WORK / "lake" / "built").write_text(time.strftime("%Y-%m-%dT%H:%M:%S"))
    return out


def lake_env(delta: Path) -> dict:
    return {lakeconfig.DEMO_ROOT_ENV: str(delta), "DRISHTI_DEMO_ENABLED": "false", "DRISHTI_OFFLINE": "true", "DRISHTI_SECURITY_ENABLED": "false",
            "DRISHTI_COMPUTE_ENABLED": "false"}


def start_server() -> subprocess.Popen | None:
    """The one scratch server (None when one already answers on SERVER_PORT: a `serve` started earlier is reused)."""
    if _answers(f"http://127.0.0.1:{SERVER_PORT}/api/v1/rachana/schema"):
        print(f"reusing the server on {SERVER_PORT}", flush=True)
        return None
    if not JAR.exists() or not JAVA.exists():
        sys.exit(f"needs the server jar ({JAR}) and Java ({JAVA})")
    delta = lake()
    work = WORK / "server"
    if not (work / "config").exists():               # a copy, never a link: nothing the server writes reaches a checkout's config/
        (work / "config").mkdir(parents=True)
        for sub in (ROOT / "config").iterdir():
            if sub.name in SITE_CONFIG:
                continue
            (shutil.copytree if sub.is_dir() else shutil.copy2)(sub, work / "config" / sub.name)
        (work / "config" / "connectors").mkdir()
    env = _env({"HOME": str(work), "DRISHTI_PORT": str(SERVER_PORT), **lake_env(delta)})
    log = (work / "server.log").open("w")
    proc = subprocess.Popen(java_cmd(str(JAVA), ["-Xmx1536m", "-jar", str(JAR)], tmp=work / "jvm-tmp"), cwd=work, env=env, stdout=log,
                            stderr=subprocess.STDOUT)
    deadline = time.monotonic() + 600
    while not _answers(f"http://127.0.0.1:{SERVER_PORT}/api/v1/rachana/schema"):
        if proc.poll() is not None or time.monotonic() > deadline:
            proc.kill()
            sys.exit("the server did not start; see " + str(work / "server.log"))
        time.sleep(0.5)
    print(f"server on {SERVER_PORT}", flush=True)
    return proc


def start_console(tree: Path, port: int, tag: str) -> subprocess.Popen:
    """One console from `tree` (its templates and static files) in front of the scratch server, its data folder fresh."""
    data = WORK / f"console-{tag}"
    shutil.rmtree(data, ignore_errors=True)
    data.mkdir(parents=True)
    env = _env({"DRISHTI_BACKEND_URL": f"http://127.0.0.1:{SERVER_PORT}", "DRISHTI_CONSOLE_PORT": str(port), "DRISHTI_DATA_DIR": str(data),
                "DRISHTI_CONSOLE_TMP_DIR": str(data / "tmp"), **lake_env(WORK / "lake" / "delta")})
    proc = subprocess.Popen([str(VENV_PY), str(tree / "drishti-console" / "run_drishti_web.py")], cwd=tree, env=env,
                            stdout=(data / "console.log").open("w"), stderr=subprocess.STDOUT)
    deadline = time.monotonic() + 120
    while not _answers(f"http://127.0.0.1:{port}/"):
        if proc.poll() is not None or time.monotonic() > deadline:
            proc.kill()
            sys.exit("the console did not start; see " + str(data / "console.log"))
        time.sleep(0.3)
    # the VaR engine answers a moment after the server (the Delta connector comes up later): wait, or the page draws its error
    deadline = time.monotonic() + 300
    while True:
        try:
            with urllib.request.urlopen(f"http://127.0.0.1:{port}/api/var/scope?scope=BANK", timeout=60) as r:
                if r.status == 200:
                    break
        except (urllib.error.URLError, OSError):
            pass
        if time.monotonic() > deadline:
            sys.exit("the VaR engine did not answer within 300 s")
        time.sleep(1)
    print(f"console {tag} on {port} from {tree}", flush=True)
    return proc


def stop(*procs) -> None:
    for p in procs:
        if p is None:
            continue
        with contextlib.suppress(Exception):
            p.send_signal(signal.SIGTERM)
    for p in procs:
        if p is None:
            continue
        try:
            p.wait(20)
        except subprocess.TimeoutExpired:
            p.kill()


# ---- pictures --------------------------------------------------------------------------------------------------------------------------

def _until(page, js: str, seconds: int = 90) -> None:
    for _ in range(seconds * 10):
        if page.evaluate(f"() => Boolean({js})"):
            return
        page.wait_for_timeout(100)
    raise TimeoutError("still false: " + js)


def shoot(base: str, out: Path, themes=THEMES, screens=SCREENS, settled_js: Path | None = None, only: set | None = None) -> list:
    """Every screen in every theme from the console at `base`, as full-page PNGs <screen>__<theme>.png in `out` (`only`: just these
    (screen, theme) pairs). Returns the problems."""
    from playwright.sync_api import sync_playwright
    out.mkdir(parents=True, exist_ok=True)
    settled_js = settled_js or ROOT / SETTLED_JS
    problems = []
    with sync_playwright() as pw:
        browser = pw.chromium.launch(args=["--font-render-hinting=none", "--disable-lcd-text", "--force-color-profile=srgb"])
        try:
            for theme in themes:
                for name, path, (w, h), ready in screens:
                    if only is not None and (name, theme) not in only:
                        continue
                    ctx = browser.new_context(viewport={"width": w, "height": h}, device_scale_factor=1, reduced_motion="no-preference",
                                              locale="en-US", timezone_id="UTC")
                    ctx.add_init_script(f"try {{ localStorage.setItem('drishti.theme', {json.dumps(theme)}); }} catch (e) {{}}")
                    ctx.add_init_script(path=str(settled_js))
                    page = ctx.new_page()
                    try:
                        page.goto(base + path, wait_until="load", timeout=120000)
                        if ready:
                            _until(page, ready)
                        page.evaluate("() => document.fonts ? document.fonts.ready.then(() => true) : true")
                        with contextlib.suppress(Exception):
                            page.evaluate("(o) => window.Drishti && window.Drishti.settled ? window.Drishti.settled(o) : null",
                                          {"quiet": 400, "timeout": 60000})
                        page.evaluate(FREEZE_JS, list(TIMED_TEXT))
                        page.evaluate("() => window.scrollTo(0, 0)")
                        page.wait_for_timeout(300)
                        got = page.evaluate("() => document.documentElement.getAttribute('data-theme')")
                        if got != theme:
                            problems.append(f"{name} {theme}: the page is in theme {got!r}")
                        # a picture is kept once two in a row are the same: a chart still drawing (canvas animations are not CSS ones,
                        # Playwright cannot stop them) or a layout still moving is waited out
                        def take():
                            return page.screenshot(full_page=True, animations="disabled", caret="hide",
                                                   mask=[page.locator(s) for s in MASKS], mask_color="#ff00ff")
                        last = take()
                        for _ in range(STABLE_TRIES):
                            page.wait_for_timeout(500)
                            now = take()
                            if now == last:
                                break
                            last = now
                        else:   # a note, not a failure: a fixed page background repainted by the full-page capture differs by a shade
                            print(f"    note: {name} {theme} was not byte-identical in two pictures in a row; the comparison's tolerance decides",
                                  flush=True)
                        (out / f"{name}__{theme}.png").write_bytes(last)
                    except Exception as e:  # noqa: BLE001 - one screen that fails is reported, the rest still pictured
                        problems.append(f"{name} {theme}: {e}")
                    finally:
                        ctx.close()
                if only is None:
                    print(f"  {theme}: {len(screens)} screens", flush=True)
        finally:
            browser.close()
    return problems


# ---- comparison ------------------------------------------------------------------------------------------------------------------------

def _load(path: Path):
    import numpy as np
    from PIL import Image
    return np.asarray(Image.open(path).convert("RGB"), dtype=np.int16)


def compare(before: Path, after: Path, out: Path, delta: int = 2, fraction: float = 0.0005, quiet: bool = False) -> int:
    """Each picture of `before` against the one of the same name in `after`. Prints a table (screen x theme); writes a diff image (changed
    pixels in magenta over a faded copy of the after picture) for every picture that differs. Returns 1 when any is DIFFERENT."""
    try:
        import numpy as np
        from PIL import Image
    except ImportError:          # the console's venv has no Pillow: the system Python does the comparison
        if os.environ.get("CSSVIS_NESTED"):
            sys.exit("the comparison needs numpy and Pillow (set CSSVIS_COMPARE_PYTHON to a Python that has both)")
        py = os.environ.get("CSSVIS_COMPARE_PYTHON", "/usr/bin/python3")
        return subprocess.call([py, str(Path(__file__).resolve()), "compare", str(before), str(after), "--out", str(out),
                                "--delta", str(delta), "--fraction", str(fraction)] + (["--quiet"] if quiet else []),
                               env={**os.environ, "CSSVIS_NESTED": "1"})
    shutil.rmtree(out, ignore_errors=True)
    out.mkdir(parents=True, exist_ok=True)
    names = sorted({p.name for p in before.glob("*.png")} | {p.name for p in after.glob("*.png")})
    rows, bad = [], 0
    for n in names:
        b, a = before / n, after / n
        screen, _, theme = n[:-4].partition("__")
        if not b.exists() or not a.exists():
            rows.append((screen, theme, "DIFFERENT", "missing " + ("before" if not b.exists() else "after")))
            bad += 1
            continue
        x, y = _load(b), _load(a)
        if x.shape != y.shape:
            rows.append((screen, theme, "DIFFERENT", f"size {x.shape[1]}x{x.shape[0]} -> {y.shape[1]}x{y.shape[0]}"))
            bad += 1
            hgt, wid = max(x.shape[0], y.shape[0]), max(x.shape[1], y.shape[1])
            xp = np.zeros((hgt, wid, 3), np.int16)
            yp = np.zeros((hgt, wid, 3), np.int16)
            xp[:x.shape[0], :x.shape[1]], yp[:y.shape[0], :y.shape[1]] = x, y
            x, y = xp, yp
            changed = np.abs(x - y).max(axis=2) > 0
            _diff_image(y, changed, out / n)
            continue
        d = np.abs(x - y).max(axis=2)
        if not d.any():
            rows.append((screen, theme, "identical", ""))
            continue
        changed = d > delta
        share = changed.sum() / changed.size
        if share <= fraction:
            rows.append((screen, theme, "within tolerance", f"max delta {int(d.max())}, {share:.5%} beyond {delta}"))
            continue
        ys, xs = np.nonzero(changed)
        rows.append((screen, theme, "DIFFERENT", f"{share:.3%} of pixels; box x {xs.min()}-{xs.max()}, y {ys.min()}-{ys.max()}"))
        bad += 1
        _diff_image(y, changed, out / n)
    (out / "result.json").write_text(json.dumps([dict(zip(("screen", "theme", "result", "note"), r)) for r in rows], indent=1))
    if quiet:
        return 1 if bad else 0
    width = max([len(r[0]) for r in rows] + [6])
    tw = max([len(r[1]) for r in rows] + [5])
    print(f"{'screen'.ljust(width)}  {'theme'.ljust(tw)}  result")
    for s, t, res, note in rows:
        print(f"{s.ljust(width)}  {t.ljust(tw)}  {res}{('  ' + note) if note else ''}")
    tally = {k: sum(1 for r in rows if r[2] == k) for k in ("identical", "within tolerance", "DIFFERENT")}
    print(f"\n{len(rows)} pictures: {tally['identical']} identical, {tally['within tolerance']} within tolerance, {tally['DIFFERENT']} DIFFERENT")
    return 1 if bad else 0


def _diff_image(img, changed, path: Path) -> None:
    import numpy as np
    from PIL import Image
    faded = (img * 0.25 + 191).astype(np.uint8)
    faded[changed] = (255, 0, 255)
    Image.fromarray(faded).save(path)


# ---- the command line ------------------------------------------------------------------------------------------------------------------

def _pick(arg: str | None, all_: tuple, key=lambda x: x) -> tuple:
    if not arg:
        return all_
    want = set(arg.split(","))
    return tuple(x for x in all_ if key(x) in want)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("serve", help="the scratch server alone, until Ctrl+C")
    sh = sub.add_parser("shoot", help="one checkout's pictures")
    sh.add_argument("--tree", type=Path, required=True)
    sh.add_argument("--out", type=Path, required=True)
    sh.add_argument("--console-port", type=int, default=CONSOLE_PORTS[0])
    cp = sub.add_parser("compare", help="two folders of pictures, pixel by pixel")
    cp.add_argument("before", type=Path)
    cp.add_argument("after", type=Path)
    cp.add_argument("--out", type=Path, required=True)
    cp.add_argument("--quiet", action="store_true", help="no table: result.json only")
    rn = sub.add_parser("run", help="both checkouts against one server, then the comparison")
    rn.add_argument("--before", type=Path, required=True)
    rn.add_argument("--after", type=Path, required=True)
    rn.add_argument("--out", type=Path, required=True)
    rn.add_argument("--retries", type=int, default=3, help="a picture that differs is taken again on both sides up to this many times "
                    "(a difference in the data's timing, such as the order of rows with equal values, goes; one in the CSS stays)")
    for p in (sh, rn):
        p.add_argument("--themes", help="comma-separated (default: all " + str(len(THEMES)) + ")")
        p.add_argument("--screens", help="comma-separated names (default: all " + str(len(SCREENS)) + ")")
    for p in (cp, rn):
        p.add_argument("--delta", type=int, default=2, help="per-channel difference taken as anti-aliasing (default 2)")
        p.add_argument("--fraction", type=float, default=0.0005, help="share of pixels beyond --delta still within tolerance (default 0.0005)")
    a = ap.parse_args(argv)
    if a.cmd == "compare":
        return compare(a.before, a.after, a.out, a.delta, a.fraction, a.quiet)
    if a.cmd == "serve":
        proc = start_server()
        if proc is None:
            return 0
        try:
            proc.wait()
        except KeyboardInterrupt:
            stop(proc)
        return 0
    themes, screens = _pick(a.themes, THEMES), _pick(a.screens, SCREENS, key=lambda s: s[0])
    t0 = time.monotonic()
    server = start_server()
    consoles = []
    try:
        if a.cmd == "shoot":
            tree = a.tree.resolve()
            consoles.append(start_console(tree, a.console_port, "shoot"))
            problems = shoot(f"http://127.0.0.1:{a.console_port}", a.out, themes, screens, tree / SETTLED_JS)
            print("\n".join(problems) or "all pictured", f"({time.monotonic() - t0:.0f} s)")
            return 1 if problems else 0
        trees = (a.before.resolve(), a.after.resolve())
        sides = tuple(zip(trees, CONSOLE_PORTS, ("before", "after")))
        for tree, port, tag in sides:
            consoles.append(start_console(tree, port, tag))
        # one pass in one theme first, unpictured: the server's caches are warm for both sides alike
        shoot(f"http://127.0.0.1:{CONSOLE_PORTS[0]}", WORK / "warm", themes[:1], screens, trees[0] / SETTLED_JS)
        problems = []
        for tree, port, tag in sides:
            print(f"{tag}: {tree}", flush=True)
            problems += [f"{tag}: {p}" for p in shoot(f"http://127.0.0.1:{port}", a.out / tag, themes, screens, tree / SETTLED_JS)]
        for attempt in range(a.retries):
            if not compare(a.out / "before", a.out / "after", a.out / "diff", a.delta, a.fraction, quiet=True):
                break
            again = {(r["screen"], r["theme"]) for r in json.loads((a.out / "diff" / "result.json").read_text()) if r["result"] == "DIFFERENT"}
            print(f"taking {len(again)} differing pictures again (attempt {attempt + 1})", flush=True)
            for tree, port, tag in sides:
                shoot(f"http://127.0.0.1:{port}", a.out / tag, themes, screens, tree / SETTLED_JS, only=again)
        if problems:
            print("\n".join(problems))
        rc = compare(a.out / "before", a.out / "after", a.out / "diff", a.delta, a.fraction)
        print(f"({time.monotonic() - t0:.0f} s)")
        return rc or (1 if problems else 0)
    finally:
        stop(*consoles, server)


if __name__ == "__main__":
    sys.exit(main())
