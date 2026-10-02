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

"""Hostile Calc (Alt+C) runs."""
import sys, time; sys.path.insert(0, __file__.rsplit("/", 1)[0])
from pw import *

SET = """(code) => { const cm = document.querySelector('#calcDrawer .CodeMirror'); if (cm && cm.CodeMirror) { cm.CodeMirror.setValue(code); return 'cm'; }
  const t = document.querySelector('[data-calc-code]'); t.value = code; t.dispatchEvent(new Event('input')); return 'ta'; }"""
ST = "() => document.querySelector('[data-calc-status]').innerText"
OUT = "() => document.querySelector('[data-calc-out]').innerText.slice(-600)"

CASES = [
    ("hello", "print('hello', 1+1)", 30),
    ("exception", "def f():\n    raise ValueError('boom from user code')\nf()", 20),
    ("syntax error", "def (:", 20),
    ("unavailable pkg", "import requests", 30),
    ("unavailable pkg2", "import torch", 30),
    ("micropip", "import micropip\nawait micropip.install('requests')", 40),
    ("network pyfetch", "from pyodide.http import pyfetch\nr = await pyfetch('https://example.com')\nprint(r.status)", 30),
    ("js dom", "import js\nprint(js.document.title)", 20),
    ("drishti get missing", "import drishti\nprint(drishti.get('trade', 'NOPE-1'))", 30),
    ("drishti get ok", "import drishti\nd = drishti.get('trade', 'MX-20000002')\nprint(d['tradeId'], d['mtm'])", 30),
    ("search bad", "import drishti\nprint(drishti.search('TRD where mtm >'))", 30),
    ("huge print", "for i in range(300000):\n    print('line', i)", 60),
    ("huge string", "print('x' * 50_000_000)", 60),
    ("huge df", "import pandas as pd, numpy as np\ndf = pd.DataFrame(np.random.rand(200000, 30))\ndf", 90),
    ("recursion", "def r(n): return r(n+1)\nr(0)", 30),
    ("memory bomb", "a = []\nwhile True:\n    a.append(bytearray(50_000_000))", 90),
    ("after memory bomb", "print('alive')", 40),
    ("infinite loop", "while True:\n    pass", 8),
]

with sync_playwright() as p:
    b = p.chromium.launch()
    pg = b.new_page(viewport={"width": 1600, "height": 1000}); attach(pg, "calc")
    pg.goto(BASE + "/v/trade/MX-20000002"); pg.wait_for_timeout(2000)
    pg.keyboard.press("Alt+c"); pg.wait_for_timeout(1000)
    for name, code, wait in CASES:
        mode = pg.evaluate(SET, code)
        t0 = time.time()
        pg.click("[data-calc-run]")
        status = ""
        while time.time() - t0 < wait:
            pg.wait_for_timeout(500)
            try:
                status = pg.evaluate(ST)
            except Exception as e:
                status = "EVAL-ERR " + repr(e)[:80]; break
            if any(k in status for k in ("Done", "Error", "error", "failed", "Failed", "stopped", "Stopped")) and "Running" not in status:
                break
        el = round(time.time() - t0, 1)
        out = pg.evaluate(OUT).replace("\n", " | ")
        print("== %-20s %5ss [%s] status=%s\n   out=%s" % (name, el, mode, status[:160], out[-400:]), flush=True)
        shot(pg, "calc_" + name.replace(" ", "_"))
        if name == "infinite loop":
            stop_enabled = pg.is_enabled("[data-calc-stop]")
            pg.click("[data-calc-stop]"); t1 = time.time(); pg.wait_for_timeout(2500)
            print("   stop enabled=%s; after Stop status=%s (%.1fs)" % (stop_enabled, pg.evaluate(ST)[:160], time.time() - t1))
            pg.evaluate(SET, "print('after stop ok')"); pg.click("[data-calc-run]"); pg.wait_for_timeout(8000)
            print("   rerun:", pg.evaluate(ST)[:120], "|", pg.evaluate(OUT)[-80:].replace("\n", " "))
            shot(pg, "calc_after_stop")
        # is the page itself still responsive?
        t1 = time.time(); pg.evaluate("1+1"); r = time.time() - t1
        if r > 1: print("   PAGE UNRESPONSIVE %.1fs" % r)
    b.close()
for e in ERRS: print("ERR", e)
