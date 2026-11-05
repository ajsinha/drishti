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

"""Pictures of tutorial 36, the data inbox (docs/tutorials/36-load-files-with-the-data-inbox.md), written to docs/tutorials/img/t36/, in
the light theme. The pictures follow the tutorial's steps, which this module runs itself, in order, against a scratch stack on the demo
lake with sign-in off and its own copy of config/ (the rules are written to that copy): start the stack, then

    DRISHTI_T36_WORK=<the server's working folder> DRISHTI_SHOTS_SERVER_PORT=<port> DRISHTI_SHOTS_CONSOLE_PORT=<port> \\
        drishti-console/.venv/bin/python tools/docs/screenshots.py --guide t36

Every command's output is printed (the tutorial quotes it). Every rule writes to the scratch lake ./data/tutorial-36/delta under the
working folder, never to the demo lake. Run the pictures all together and once: each step moves files on."""
from __future__ import annotations

import os
import shutil
import subprocess
import sys
import time
from pathlib import Path

from _tut import OUT, light
from shotlib import ROOT, SERVER_PORT, Ctx, out_dir, register

shot = register("t36", OUT / "t36")
WIDE, HIGH = 1440, 900
ENDUR = ROOT / "data" / "testdata" / "trades" / "endur"
RULE_1 = """dataload:
  rules:
    - match: "trades-endur.*.jsonl.gz"     # a glob on the file's name
      pack: trading
      kind: trade
      connector: trade-endur               # the table that connector reads (trading/trade_endur)
      date-from: name                      # the YYYY-MM-DD in the name is every row's business date
      lake: ./data/tutorial-36/delta       # tutorial only: a lake of its own
"""
RULE_2 = """    - match: "endur.*.jsonl.gz"
      pack: trading
      kind: trade
      connector: trade-endur
      date-from: name
      lake: ./data/tutorial-36/delta
"""


def work() -> Path:
    return Path(os.environ["DRISHTI_T36_WORK"])


def inbox() -> Path:
    return work() / "data" / "dataload" / "inbox"


def cli(*args: str) -> str:
    """drishti.py against the scratch server; prints and returns its output."""
    r = subprocess.run([sys.executable, str(ROOT / "tools" / "drishti.py"), *args], capture_output=True, text=True, cwd=ROOT,
                       env={**os.environ, "DRISHTI_SERVER": f"http://127.0.0.1:{SERVER_PORT}"}, timeout=120)
    out = (r.stdout + r.stderr).rstrip()
    print("  $ drishti.py " + " ".join(args) + "\n" + "\n".join("    " + x for x in out.splitlines()))
    return out


def idle(seconds: int = 300) -> None:
    """Waits until no run is loading."""
    end = time.time() + seconds
    while time.time() < end:
        if "loading" not in cli("dataload", "status").split("last cycles:")[0]:
            return
        time.sleep(3)


def _inbox(c: Ctx, heading: str = "", wait_ms: int = 1500, name: str = "", above: int = 80) -> None:
    """The Inbox screen; with `heading` and `name`, a window-sized picture from just above that section (cut from the whole page: the
    screen redraws itself, so it is not scrolled)."""
    light(c)
    c.page.set_viewport_size({"width": WIDE, "height": HIGH})
    c.page.goto(c.base + "/admin/loads/inbox")
    c.page.wait_for_timeout(wait_ms)
    if heading:
        c.until("(() => { const h = document.getElementById(%r); return h && h.getBoundingClientRect().height > 0; })()" % heading, 60)
        c.page.wait_for_timeout(1500)
        y = c.page.evaluate("(h) => document.getElementById(h).getBoundingClientRect().top + window.scrollY", heading)
        c.hush()
        d = out_dir(c.guide)
        c.page.screenshot(path=str(d / name), type="jpeg", quality=84, full_page=True, clip={"x": 0, "y": max(0, y - above), "width": WIDE, "height": HIGH})
        print("  wrote", (d / name).relative_to(ROOT))


@shot("01-engine-on.jpg")
def engine_on(c: Ctx):
    cli("server", "engines", "on", "dataload", "--reason", "tutorial 36")       # already on after the restart that stable-seconds needs
    cli("dataload", "run")                                         # an empty cycle makes inbox/ and ledger/
    time.sleep(3)
    cli("dataload", "status")
    print("  ls data/dataload:", sorted(p.name for p in (work() / "data" / "dataload").iterdir()))
    light(c)
    c.page.set_viewport_size({"width": WIDE, "height": HIGH})
    c.page.goto(c.base + "/admin/engines")
    c.page.wait_for_timeout(2000)
    c.page.evaluate("""() => { const e = Array.from(document.querySelectorAll('td, th, b, strong, span, div')).find(x => x.children.length === 0
        && /^Data inbox$/.test(x.textContent.trim())); if (e) { e.scrollIntoView({block: 'center'}); } }""")
    c.page.wait_for_timeout(600)
    c.save("01-engine-on.jpg")


@shot("02-rules.jpg")
def rules(c: Ctx):
    (work() / "config" / "dataload.yaml").write_text(RULE_1 + "  unmatched: fail\n")
    _inbox(c, "ib-rules-h", name="02-rules.jpg")


@shot("03-waiting-ready.jpg")
def waiting(c: Ctx):
    shutil.copy(ENDUR / "trade_endur.2026-09-30.jsonl.gz", inbox() / "trades-endur.2026-09-30.jsonl.gz")
    cli("dataload", "status")
    (inbox() / "trades-endur.2026-09-30.jsonl.gz.ready").touch()
    time.sleep(8)                                                  # stable-seconds: 5 in the tutorial's engine file
    cli("dataload", "status")
    _inbox(c)
    c.save("03-waiting-ready.jpg")


@shot("04-loading.jpg")
def loading(c: Ctx):
    cli("dataload", "run")
    light(c)
    c.page.set_viewport_size({"width": WIDE, "height": HIGH})
    c.page.goto(c.base + "/admin/loads/inbox")
    c.page.wait_for_timeout(2500)
    c.save("04-loading.jpg")
    idle()
    cli("dataload", "status")


@shot("05-failure.jpg")
def failure(c: Ctx):
    for d in ("28", "29"):                                         # step 5: two files and one ready.all
        shutil.copy(ENDUR / f"trade_endur.2026-09-{d}.jsonl.gz", inbox() / f"trades-endur.2026-09-{d}.jsonl.gz")
    (inbox() / "ready.all").touch()
    time.sleep(8)
    cli("dataload", "status")
    cli("dataload", "run")
    time.sleep(3)
    idle()
    cli("dataload", "status")
    shutil.copy(ENDUR / "trade_endur.2026-09-25.jsonl.gz", inbox() / "endur.2026-09-25.jsonl.gz")   # step 6: no rule matches
    (inbox() / "endur.2026-09-25.jsonl.gz.ready").touch()
    time.sleep(8)
    cli("dataload", "run")
    time.sleep(3)
    idle()
    cli("dataload", "status")
    _inbox(c, "ib-fail-h", name="05-failure.jpg", above=420)
    (work() / "config" / "dataload.yaml").write_text(RULE_1 + RULE_2 + "  unmatched: fail\n")
    cli("dataload", "retry", "endur.2026-09-25.jsonl.gz")
    time.sleep(8)
    cli("dataload", "run")
    time.sleep(3)
    idle()
    shutil.copy(ENDUR / "trade_endur.2026-09-30.jsonl.gz", inbox() / "trades-endur.2026-09-30.jsonl.gz")   # the first file again: a duplicate
    (inbox() / "trades-endur.2026-09-30.jsonl.gz.ready").touch()
    time.sleep(8)
    cli("dataload", "run")
    time.sleep(3)
    idle()
    cli("dataload", "status")


@shot("06-last-cycles.jpg")
def last_cycles(c: Ctx):
    _inbox(c, "ib-dup-h", name="06-last-cycles.jpg", above=340)
