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

"""The page walk's helpers (docs/architecture/TEST_HARNESS.md, "The page walk"): the addresses to open, read from the sitemap
(config/sitemap.yaml: every menu item, the in-context pages, Help's sections) and from the top bar a browser draws (each lens's too),
and one page opened and judged: its status, a server error page, a browser console error, an uncaught exception, an empty main area
and its time against the budget. The layout rules of a whole page: no sideways scroll, no two controls on top of each other, each
label beside its control, no field group taller than what it holds, the main area using the width."""
from __future__ import annotations

import os
import time
from pathlib import Path
from urllib.parse import quote_plus, urlsplit

import yaml

CONSOLE = Path(__file__).resolve().parents[1]
SETTLED_JS = CONSOLE / "web" / "static" / "js" / "drishti_settled.js"
BUDGET_S = float(os.environ.get("DRISHTI_PAGEWALK_BUDGET", "5"))   # a page slower than this fails (opened, drawn and settled)

#: parameterised pages, opened with a known demo id (the VaR lake's bank and desk)
PARAMETERISED = ["/var?scope=BANK", "/var?scope=DESK:DESK-RATES", "/go?q=" + quote_plus("VAR DESK DESK-RATES <GO>")]
#: addresses never opened: they leave the session or the lens, or are downloads
SKIP_PREFIX = ("/logout", "/lens/leave", "/auth/", "/login", "/static/", "/api/")
#: in-context pages opened with what the page they open from gives them; None: only reached inside a lens (the lens walk opens it there)
IN_CONTEXT = {"/w/new": "/w/new?name=page-walk", "/home/trading/view-as": None}
#: pages allowed over the budget, each with its reason (a page leaves the list when it is made fast)
SLOW_ALLOWED: dict[str, str] = {}
#: known faults not yet fixed, each with its reason: reported on every walk, not failed (a page leaves the list when it is fixed)
KNOWN_FAULTS: dict[str, str] = {}


def sitemap() -> dict:
    return yaml.safe_load((CONSOLE / "config" / "sitemap.yaml").read_text(encoding="utf-8"))


def go(cmd: str) -> str:
    return "/go?q=" + quote_plus(f"{cmd} <GO>")


def sitemap_addresses() -> list[str]:
    """Every menu item's link (an item with only a command: /go?q=<cmd> <GO>; one that only fills the command line is skipped), the
    in-context pages, Help's section pages and the parameterised pages, each once, in the sitemap's order."""
    spec, out = sitemap(), []
    for m in spec["menus"]:
        for c in m.get("columns") or []:
            for it in c.get("items") or []:
                href = it.get("href") or (go(str(it["cmd"])) if it.get("cmd") else "")
                if href:
                    out.append(str(href))
    out += [IN_CONTEXT.get(str(p), str(p)) for p in (spec.get("in-context") or {}) if IN_CONTEXT.get(str(p), str(p))]
    out += [f"/help/section/{s['id']}" for s in spec.get("help-sections") or []]
    out += PARAMETERISED
    return dedupe(out)


def dedupe(hrefs) -> list[str]:
    seen, out = set(), []
    for h in hrefs:
        h = h.split("#", 1)[0] or "/"
        if h.startswith(SKIP_PREFIX) or h in seen or "{" in h:
            continue
        seen.add(h)
        out.append(h)
    return out


def menu_links(pg, base: str) -> list[str]:
    """The links of the top bar as drawn (every mega-menu item, the lens links, the account menu), same origin only."""
    hrefs = pg.evaluate("() => [...document.querySelectorAll('header.tbar a[href]')].map((a) => a.href)")
    host = urlsplit(base).netloc
    out = []
    for h in hrefs:
        u = urlsplit(h)
        if u.netloc == host and u.path:
            out.append(u.path + (("?" + u.query) if u.query else ""))
    return dedupe(out)


def new_page(browser, width: int = 1366, height: int = 900):
    """A page that records its console errors, uncaught exceptions and failed responses (pg.problems)."""
    ctx = browser.new_context(viewport={"width": width, "height": height})
    ctx.add_init_script(path=str(SETTLED_JS))
    ctx.add_init_script(_CSP)
    pg = ctx.new_page()
    pg.problems = []
    pg.on("console", lambda m: pg.problems.append(f"console: {m.text[:300]}") if m.type == "error" and not m.text.startswith("Failed to load resource") else None)
    pg.on("pageerror", lambda e: pg.problems.append(f"exception: {str(e)[:200]} at {(e.stack or '').splitlines()[1:2]}"))
    pg.on("response", lambda r: pg.problems.append(f"HTTP {r.status} {urlsplit(r.url).path}") if _failed(r) else None)
    return pg


#: where a Content Security Policy refusal came from (Chromium's own message does not say)
_CSP = """document.addEventListener('securitypolicyviolation', (e) => {
  console.error('CSP ' + e.violatedDirective + ' at ' + (e.sourceFile || '?') + ':' + e.lineNumber + ' ' + (e.sample || ''));
});"""


def _failed(r) -> bool:
    """A request that failed: a file (script, style, image, font) answering 4xx or 5xx, or an /api/ call answering 5xx. An /api/ call
    answering 4xx is the page's own answer (no data for this date, a refusal it shows) and the page handles it."""
    if r.request.resource_type == "document" or r.status < 400:
        return False
    return r.status >= 500 or "/api/" not in urlsplit(r.url).path


_JUDGE = """() => {
  const main = document.querySelector('#main') || document.body;
  const text = (main.innerText || '').trim();
  const drawn = main.querySelector('canvas, svg, img, iframe, drishti-grid, input, select, textarea, [data-var], table');
  const alerts = [...document.querySelectorAll('[role=alert]')].filter((e) => e.offsetParent !== null).map((e) => e.innerText || '').join(' ');
  return { empty: !text && !drawn, down: document.body.dataset.serverDown === '1', off: /is not running on this server/.test(main.innerText || ''),
           server: /Internal Server Error|Bad Gateway|Traceback \\(most recent|DRS-5\\d\\d\\d/.test(alerts) || /^Internal Server Error/.test(document.body.innerText || '') };
}"""


def open_page(pg, base: str, href: str, timeout_s: float = 60) -> dict:
    """Opens ``href`` and waits until it settles; returns {href, status, secs, faults}. Faults: a 4xx/5xx answer, a server error page,
    a console error, an uncaught exception, a failed request, an empty main area."""
    pg.problems.clear()
    t0 = time.monotonic()
    faults = []
    try:
        resp = pg.goto(base + href, wait_until="load", timeout=timeout_s * 1000)
        status = resp.status if resp else 0
        pg.evaluate("(o) => window.Drishti && window.Drishti.settled ? window.Drishti.settled(o) : null", {"quiet": 50, "timeout": int(timeout_s * 1000), "animations": False})
    except Exception as e:  # noqa: BLE001 - a page that does not open is the fault reported
        return {"href": href, "status": 0, "secs": time.monotonic() - t0, "faults": [f"did not open: {str(e).splitlines()[0][:200]}"]}
    secs = time.monotonic() - t0
    j = pg.evaluate(_JUDGE)
    if status == 404 and j["off"]:   # an engine switched off on this server: its pages are gone everywhere, by design
        return {"href": href, "status": status, "secs": secs, "faults": [], "off": True}
    if status >= 400:
        faults.append(f"HTTP {status}")
    if j["down"] or j["server"]:
        faults.append("server error page")
    if j["empty"]:
        faults.append("empty main area")
    faults += list(dict.fromkeys(pg.problems))
    return {"href": href, "status": status, "secs": secs, "faults": faults}


def report(results: list[dict], budget: float = BUDGET_S) -> tuple[list[str], list[str]]:
    """(faults, slow): one line per faulty page, one per page over the budget (slowest first)."""
    for r in results:
        if r["faults"] and r["href"] in KNOWN_FAULTS:
            print(f"  known {r['href']} ({KNOWN_FAULTS[r['href']]}): " + "; ".join(r["faults"]))
    bad = [f"{r['href']}: " + "; ".join(r["faults"]) for r in results if r["faults"] and r["href"] not in KNOWN_FAULTS]
    slow = [f"{r['href']}: {r['secs']:.1f}s" for r in sorted(results, key=lambda r: -r["secs"]) if r["secs"] > budget and r["href"] not in SLOW_ALLOWED]
    return bad, slow


# ---- the layout rules of a whole page ----------------------------------------------------------------------------------------------

_LAYOUT = """() => {
  const vis = (e) => { const r = e.getBoundingClientRect(); const s = getComputedStyle(e);
    return r.width > 0 && r.height > 0 && s.visibility !== 'hidden' && s.display !== 'none' && e.offsetParent !== null; };
  const R = (e) => e.getBoundingClientRect();
  const name = (e) => (e.tagName.toLowerCase() + (e.id ? '#' + e.id : '') + (e.className && typeof e.className === 'string' ? '.' + e.className.trim().split(/\\s+/).slice(0, 2).join('.') : ''));
  const sw = document.documentElement.scrollWidth, cw = document.documentElement.clientWidth;
  const main = document.querySelector('#main');
  // the main area's content: the union of its visible children's boxes
  let left = Infinity, right = -Infinity;
  for (const c of (main ? main.querySelectorAll(':scope > *, :scope > * > *') : [])) { if (vis(c)) { const r = R(c); if (r.width > 40) { left = Math.min(left, r.left); right = Math.max(right, r.right); } } }
  const share = main && right > left ? (Math.min(right, cw) - Math.max(left, 0)) / cw : 1;
  // controls drawn on top of each other (in the main area, not inside one another)
  const ctrls = main ? [...main.querySelectorAll('button, input:not([type=hidden]), select, textarea, drishti-select, [role=combobox]')].filter(vis)
                      .filter((e) => !e.closest('[role=dialog], .modal, .dropdown-menu, [popover], .cmd-list, drishti-grid, .dg-root')) : [];
  const overlaps = [];
  for (let i = 0; i < ctrls.length && overlaps.length < 5; i++) {
    for (let j = i + 1; j < ctrls.length; j++) {
      const a = ctrls[i], b = ctrls[j];
      if (a.contains(b) || b.contains(a)) { continue; }
      const p = R(a), q = R(b);
      const w = Math.min(p.right, q.right) - Math.max(p.left, q.left), h = Math.min(p.bottom, q.bottom) - Math.max(p.top, q.top);
      if (w > 4 && h > 4 && w * h > 0.25 * Math.min(p.width * p.height, q.width * q.height)) { overlaps.push(name(a) + ' / ' + name(b)); }
    }
  }
  // a label and its control: a <label for> lies near the control it names
  const far = [];
  for (const l of (main ? main.querySelectorAll('label[for]') : [])) {
    const c = document.getElementById(l.htmlFor);
    if (!c || !vis(l) || !vis(c) || l.classList.contains('visually-hidden')) { continue; }
    const a = R(l), b = R(c);
    const dx = Math.max(0, Math.max(a.left, b.left) - Math.min(a.right, b.right)), dy = Math.max(0, Math.max(a.top, b.top) - Math.min(a.bottom, b.bottom));
    if (dx > 160 || dy > 40) { far.push((l.textContent || '').trim().slice(0, 40) + ` (${Math.round(dx)}x${Math.round(dy)} px)`); }
  }
  // a field group (fieldset) taller than what it holds
  const tall = [];
  for (const f of (main ? main.querySelectorAll('fieldset') : [])) {
    if (!vis(f)) { continue; }
    const kids = [...f.children].filter((c) => c.tagName !== 'LEGEND' && vis(c));
    if (!kids.length) { continue; }
    const cs = getComputedStyle(f), r = R(f);
    const slack = r.bottom - parseFloat(cs.paddingBottom) - parseFloat(cs.borderBottomWidth) - Math.max(...kids.map((c) => R(c).bottom));
    if (slack > 24) { tall.push(name(f) + ' ' + Math.round(slack) + ' px'); }
  }
  return { hscroll: sw - cw, share, overlaps, far, tall };
}"""


def layout_faults(pg, min_share: float = 0.6) -> list[str]:
    """The page's layout faults by the rules above (an empty list: none)."""
    m = pg.evaluate(_LAYOUT)
    out = []
    if m["hscroll"] > 2:
        out.append(f"scrolls sideways by {m['hscroll']} px")
    if m["share"] < min_share:
        out.append(f"the main content uses {m['share']:.0%} of the width")
    out += [f"controls overlap: {o}" for o in m["overlaps"]]
    out += [f"label away from its control: {f}" for f in m["far"]]
    out += [f"field group taller than its content: {t}" for t in m["tall"]]
    return out
