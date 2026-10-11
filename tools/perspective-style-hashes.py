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

"""Measures the style hashes the vendored Perspective needs on a real report page (Rupaka phase 5, docs/architecture/RUPAKA_POC.md section 12).

Perspective writes ``<style>`` elements into its shadow roots, which ``style-src 'self'`` refuses; the console allows exactly their SHA-256 hashes on a page that holds
a Perspective visual (``drishti-console/core/perspective_csp.py``, ``STYLE_HASHES``). A new Perspective version may write other styles, so after vendoring one, start a
console and server with the shipped live samples (``drishti-console/tests/bi_live_stack.py`` makes one) and run:

    python tools/perspective-style-hashes.py --console http://127.0.0.1:17995 --user rita --password long-enough-pass-1
    python tools/perspective-style-hashes.py ... --check        # exit 1 when STYLE_HASHES is not exactly this

It signs in with Chromium (Playwright), opens the sample report's Perspective pages, takes the policy off their styles (the header is rewritten in the browser to the
strict policy with WebAssembly and no hashes), and reads the hash of every style the browser then refuses from its console. Nothing is written; nothing leaves the
machine."""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CONSOLE = ROOT / "drishti-console"
sys.path.insert(0, str(CONSOLE))

from core.app import CSP  # noqa: E402
from core.perspective_csp import PERSPECTIVE_VERSION, STYLE_HASHES  # noqa: E402

NO_HASHES = CSP.replace("script-src 'self'", "script-src 'self' 'wasm-unsafe-eval'")
PAGES = ("file:report:live-trading-desk/grid", "file:report:live-trading-desk/pivot")
SHA = re.compile(r"sha256-[A-Za-z0-9+/]+=*")


def measure(console: str, user: str, password: str, pages=PAGES, browser=None) -> list[str]:
    """The sorted hashes of the styles the Perspective pages write, as the browser reports them under a policy that allows none. ``browser`` is a running Playwright
    browser to use (a test that already holds one); without it Chromium is started here."""
    found: set[str] = set()

    def run(b) -> None:
        context = b.new_context()
        page = context.new_page()
        page.goto(f"{console}/login?next=/bi")
        page.fill("#lu", user)
        page.fill("#lp", password)
        page.click("button[type=submit]")
        page.wait_for_url("**/bi")

        def strip(route):
            response = route.fetch()
            headers = dict(response.headers)
            headers["content-security-policy"] = NO_HASHES
            route.fulfill(response=response, headers=headers)

        page.route("**/bi/r/**", strip)
        page.on("console", lambda m: found.update(SHA.findall(m.text)))
        for path in pages:
            page.goto(f"{console}/bi/r/{path}")
            page.wait_for_selector("[data-ready=yes]", state="attached", timeout=60000)
            page.wait_for_selector("[data-psp=ready]", state="attached", timeout=60000)
            page.wait_for_timeout(1500)
        context.close()

    if browser is not None:
        run(browser)
    else:
        from playwright.sync_api import sync_playwright

        with sync_playwright() as p:
            b = p.chromium.launch()
            run(b)
            b.close()
    return sorted(found)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--console", required=True, help="the console's address, for example http://127.0.0.1:17995")
    ap.add_argument("--user", required=True)
    ap.add_argument("--password", required=True)
    ap.add_argument("--check", action="store_true", help="exit 1 when core/perspective_csp.py STYLE_HASHES is not exactly what the vendored Perspective needs")
    args = ap.parse_args()
    measured = measure(args.console, args.user, args.password)
    print(f"Perspective {PERSPECTIVE_VERSION} writes {len(measured)} styles on the grid and pivot pages:")
    for h in measured:
        print(f"  '{h}'" + ("" if h in STYLE_HASHES else "   <-- not in STYLE_HASHES"))
    for h in (x for x in STYLE_HASHES if x not in measured):
        print(f"  '{h}'   <-- in STYLE_HASHES but not written on these pages")
    exact = sorted(STYLE_HASHES) == measured
    print("STYLE_HASHES is exact." if exact else "Update STYLE_HASHES in drishti-console/core/perspective_csp.py to the list above.")
    return 0 if exact or not args.check else 1


if __name__ == "__main__":
    sys.exit(main())
