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

"""Regenerates the screenshots of the guides into docs/guides/img/<guide>/ (connectors: docs/connectors/img/connectors/).

Playwright drives the real console over a real server: a SCRATCH server on :18997 and console on :17997 with the QUICKSTART packs. It never
touches the usual :18480 / :17480 (it refuses them). If nothing answers on the scratch ports the script starts both, in a scratch directory,
and stops what it started; if something does, it uses it. Needs the built server jar and JAVA_HOME of a JDK 21 or newer.

    drishti-console/.venv/bin/python tools/docs/screenshots.py --guide panels            # one guide's pictures
    drishti-console/.venv/bin/python tools/docs/screenshots.py --guide designer --only 07
    drishti-console/.venv/bin/python tools/docs/screenshots.py --list [--guide panels]   # the file names, one per line

Each guide is a module tools/docs/shots/<guide>.py that registers its pictures with `shot = register("<guide>")` (see shotlib.py). Every
picture is a step of a guide; when the UI changes, run this and commit the new files. drishti-console/tests/test_guide_images.py fails if a guide
shows a picture that is not there, or a picture file no guide shows."""
from __future__ import annotations

import argparse
import importlib
from pathlib import Path
import shutil
import sys
import tempfile

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE / "shots"))
from shotlib import CONSOLE_PORT, FORBIDDEN, SHOTS, Ctx, listening, start_servers  # noqa: E402


def load_guides() -> list:
    """Imports every tools/docs/shots/<guide>.py, which registers its pictures; returns the guide names."""
    names = sorted(p.stem for p in (HERE / "shots").glob("*.py") if not p.stem.startswith("_"))
    for n in names:
        importlib.import_module(n)
    return names


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--base", default=f"http://127.0.0.1:{CONSOLE_PORT}")
    ap.add_argument("--guide", default="", help="one guide (a module of tools/docs/shots); default: all")
    ap.add_argument("--only", default="", help="comma list: the pictures whose file name contains one of them")
    ap.add_argument("--list", action="store_true")
    a = ap.parse_args()
    guides = load_guides()
    if a.guide and a.guide not in guides:
        sys.exit(f"unknown guide {a.guide}; known: {', '.join(guides)}")
    chosen = [s for s in SHOTS if not a.guide or s[0] == a.guide]
    # a guide that needs its own server settings (the tutorial: sign-in on) declares SERVER_ENV and runs only on its own, with --guide
    needs_env = {g for g in guides if getattr(sys.modules[g], "SERVER_ENV", None)}
    if not a.guide:
        chosen = [s for s in chosen if s[0] not in needs_env]
    server_env = dict(getattr(sys.modules[a.guide], "SERVER_ENV", {})) if a.guide else {}
    if a.list:
        print("\n".join(x for s in chosen for x in (s[1],) + s[3]))
        return 0
    port = int(a.base.rsplit(":", 1)[-1])
    if port in FORBIDDEN:
        sys.exit(f"refusing {a.base}: screenshots are made on the scratch console (:{CONSOLE_PORT}), never on the usual one")
    from playwright.sync_api import sync_playwright

    started, work = [], Path(tempfile.mkdtemp(prefix="drishti-shots-"))
    try:
        if not listening(port):
            # a guide that needs its own scratch server (its own plugins and configuration) supplies start_servers(work);
            # otherwise the shared one starts, with the guide's SERVER_ENV (e.g. sign-in on) added to its environment
            own = getattr(sys.modules.get(a.guide), "start_servers", None) if a.guide else None
            started = own(work) if own else start_servers(work, server_env)
        failed = []
        with sync_playwright() as p:
            browser = p.chromium.launch()
            page = browser.new_page(viewport={"width": 1440, "height": 900})
            c = Ctx(page, a.base)
            for guide, name, fn, _also in chosen:
                if a.only and not any(o and o in name for o in a.only.split(",")):
                    continue
                print(guide, name)
                c.guide = guide
                module = sys.modules[guide]
                for attempt in (1, 2):                      # a slow machine can time a step out once; the second try starts from a clean page
                    try:
                        if hasattr(module, "before"):
                            module.before(c, name)
                        fn(c)
                        break
                    except Exception as e:  # noqa: BLE001 - report every failed picture, keep going
                        print("  attempt", attempt, "failed:", " | ".join(str(e).splitlines()[:5])[:500])
                        c.showcase_open = False
                        if attempt == 2:
                            failed.append(name)
            c.forget()
            browser.close()
        print("failed:", failed if failed else "none")
        return 1 if failed else 0
    finally:
        for proc in started:
            proc.terminate()
        shutil.rmtree(work, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
