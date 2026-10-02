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

"""Visit every console page at 390/1600/2560 px; record JS errors, HTTP errors, broken images, horizontal overflow,
long tasks, missing landmarks/labels; screenshot each. Usage: sweep.py [theme] [widths]"""
import json, re, sys, time, yaml
sys.path.insert(0, __file__.rsplit("/", 1)[0])
from pw import *

HERE = __file__.rsplit("/", 1)[0]
THEME = sys.argv[1] if len(sys.argv) > 1 else "terminal"
WIDTHS = [int(w) for w in (sys.argv[2] if len(sys.argv) > 2 else "390,1600,2560").split(",")]
ONLY = sys.argv[3] if len(sys.argv) > 3 else ""

PAGES = ["/", "/t", "/v/trade/MX-20000001", "/v/trade/MX-20000002", "/v/trade/BBG-60000092", "/v/netting-set/NS-SUMMIT-NY",
         "/v/counterparty/CP-NORTHBRIDGE", "/v/desk/DESK-RATES", "/v/var/VAR-COMM", "/v/commodity/CMD-BRENT",
         "/v/equity-vol-surface/EQV-CSCA", "/v/legal-entity/LE-NY", "/v/climate-stress/CST-CURRENTPOLICIES-COMM",
         "/v/trade/NOPE-404", "/v/nosuchkind/X", "/s?q=TRD%20where%20mtm%20%3E%201m%20order%20by%20mtm%20desc%20limit%2020",
         "/s?q=TRD%20MX-200000", "/compare/trade/MX-20000002?a=2026-09-29&b=2026-09-30", "/impact/trade/MX-20000002",
         "/history", "/m", "/alerts", "/w", "/studio", "/studio/reviews", "/reports", "/servers", "/account",
         "/admin/users", "/admin/audit", "/admin/health", "/admin/caches", "/admin/packs", "/admin/roles", "/admin/tokens",
         "/admin/access", "/help", "/help/search?q=pivot", "/about", "/about/competitive", "/login", "/nonexistent-page"]
helpcfg = yaml.safe_load(open(HERE + "/fresh/console/config/help.yaml"))
for cat in helpcfg.get("categories", helpcfg if isinstance(helpcfg, list) else []):
    for g in cat.get("guides", []):
        PAGES.append("/help/" + g["slug"])
if ONLY:
    PAGES = [p for p in PAGES if re.search(ONLY, p)]

INIT = """
window.__long = [];
try { new PerformanceObserver(l => { for (const e of l.getEntries()) window.__long.push(Math.round(e.duration)); }).observe({type: 'longtask', buffered: true}); } catch (e) {}
try { localStorage.setItem('drishti.theme', '%s'); } catch (e) {}
document.addEventListener('securitypolicyviolation', e => console.error('CSP ' + e.violatedDirective + ' ' + e.blockedURI));
""" % THEME

AUDIT = r"""() => {
  const out = {};
  out.sw = document.documentElement.scrollWidth; out.cw = document.documentElement.clientWidth;
  out.title = document.title;
  out.theme = document.documentElement.getAttribute('data-theme');
  out.landmarks = ['main','nav','header','footer'].map(t => t + ':' + document.querySelectorAll(t + ',[role=' + ({main:'main',nav:'navigation',header:'banner',footer:'contentinfo'})[t] + ']').length).join(' ');
  out.h1 = document.querySelectorAll('h1').length;
  out.lang = document.documentElement.lang;
  out.brokenImg = [...document.images].filter(i => i.complete && i.naturalWidth === 0 && i.src).map(i => i.src).slice(0, 5);
  const vis = e => { const r = e.getBoundingClientRect(); const s = getComputedStyle(e); return r.width > 0 && r.height > 0 && s.visibility !== 'hidden' && s.display !== 'none'; };
  out.unlabelled = [...document.querySelectorAll('input,select,textarea,button,a[href]')].filter(vis).filter(e => {
     if (e.type === 'hidden') return false;
     const name = (e.getAttribute('aria-label') || e.getAttribute('aria-labelledby') || e.getAttribute('title') || (e.labels && e.labels.length ? 'l' : '') || (e.tagName==='BUTTON'||e.tagName==='A' ? e.innerText.trim() : '') || e.getAttribute('placeholder') || (e.querySelector && e.querySelector('img[alt]') ? 'i' : '')).trim();
     return !name; }).map(e => e.tagName.toLowerCase() + (e.id ? '#' + e.id : '') + (e.className && typeof e.className === 'string' ? '.' + e.className.split(' ')[0] : '') ).slice(0, 8);
  // elements sticking out of the viewport horizontally
  out.wide = [...document.querySelectorAll('body *')].filter(vis).filter(e => e.getBoundingClientRect().right > out.cw + 2 && getComputedStyle(e).position !== 'fixed').filter(e => { let p = e.parentElement; while (p) { const o = getComputedStyle(p).overflowX; if (o === 'auto' || o === 'scroll' || o === 'hidden') return false; p = p.parentElement; } return true; }).map(e => e.tagName.toLowerCase() + '.' + (typeof e.className === 'string' ? e.className.split(' ')[0] : '') + '@' + Math.round(e.getBoundingClientRect().right)).slice(0, 6);
  out.longTasks = window.__long || [];
  out.text = (document.querySelector('main') || document.body).innerText.slice(0, 160).replace(/\n/g, ' | ');
  return out;
}"""

res = []
with sync_playwright() as p:
    b = p.chromium.launch()
    for w in WIDTHS:
        ctx = b.new_context(viewport={"width": w, "height": 900 if w < 2000 else 1440})
        ctx.add_init_script(INIT)
        page = ctx.new_page(); attach(page, "w%d" % w)
        for url in PAGES:
            tag = "%s@%d" % (url, w)
            t0 = time.time()
            try:
                r = page.goto(BASE + url, wait_until="load", timeout=30000)
                st = r.status if r else -1
            except Exception as e:
                st = repr(e)[:80]
            page.wait_for_timeout(1800)
            try:
                a = page.evaluate(AUDIT)
            except Exception as e:
                a = {"err": repr(e)[:200]}
            a.update({"url": url, "w": w, "status": st, "secs": round(time.time() - t0, 1), "theme": THEME})
            name = "sw_%s_%d_%s" % (THEME, w, re.sub(r"[^A-Za-z0-9]+", "_", url)[:60])
            try:
                shot(page, name)
            except Exception:
                pass
            a["shot"] = "shots/" + name + ".png"
            res.append(a)
            ov = a.get("sw", 0) > a.get("cw", 0) + 1
            print("%-60s %4s st=%s ovf=%s lt=%s brk=%d unl=%d %s" % (url[:60], w, st, (a.get("sw"), a.get("cw")) if ov else "-",
                  max(a.get("longTasks") or [0]), len(a.get("brokenImg") or []), len(a.get("unlabelled") or []), a.get("landmarks")), flush=True)
        ctx.close()
    b.close()
json.dump(res, open(HERE + "/sweep_%s.json" % THEME, "w"), indent=1)
dump_errs(HERE + "/errs_sweep_%s.json" % THEME)
