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

"""Each page's load time in the browser tests (owner 2026-11-10): with DRISHTI_PAGELOAD_LOG set (the drill sets it to
<data>/drill/pageloads.jsonl), every page a test opens appends one line {page, secs, test}: from the main document's request to the page's
load event, measured in the test process (no question to the page, so nothing waits on it). conftest.py watches every page a Browser
opens; tools/drillkit/pageloads.py prints p95 and median per page and writes the table into docs/admin/BENCHMARK.md section 18.2.
A slow page is reported there, never failed here: the tests' own page budget is 10 s (page_walk.BUDGET_S)."""
import json
import os
import time
from urllib.parse import urlsplit


def page_key(url: str) -> str | None:
    """The page a URL is: its path (the query and fragment are a page's state, not another page); None for what is not a console page."""
    u = urlsplit(url or "")
    if u.scheme not in ("http", "https") or u.path.startswith(("/static/", "/api/", "/embed/")):
        return None
    return u.path or "/"


def record(url: str, secs: float) -> None:
    out = os.environ.get("DRISHTI_PAGELOAD_LOG")
    key = page_key(url)
    if not out or key is None:
        return
    test = os.environ.get("PYTEST_CURRENT_TEST", "").split("::")[0].rsplit("/", 1)[-1]
    line = json.dumps({"page": key, "secs": round(secs, 3), "test": test}) + "\n"
    try:                                                    # one short append: whole lines from many workers
        with open(out, "a", encoding="utf-8") as f:
            f.write(line)
    except OSError:
        pass


def watch(page) -> None:
    """Times each navigation of ``page``'s main frame to its load event."""
    if not os.environ.get("DRISHTI_PAGELOAD_LOG") or getattr(page, "_drishti_pageload", False):
        return
    page._drishti_pageload = True
    started: dict = {}

    def on_request(req):
        try:
            if req.is_navigation_request() and req.frame == page.main_frame:
                started["url"], started["t"] = req.url, time.monotonic()
        except Exception:                                   # the page is closing
            pass

    def on_load(_page):
        if "t" in started:
            record(started.pop("url"), time.monotonic() - started.pop("t"))

    page.on("request", on_request)
    page.on("load", on_load)
