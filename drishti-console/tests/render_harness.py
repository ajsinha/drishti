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

"""Draws one ViewModel two ways for the parity suite (CLIENT_RENDERER.md section 14), with no server and no port: (a) the console's
server HTML, rendered by the console's own macros (_macros/view.html, panels.html, terminal/_grid.html) into a page with the console's
sheets and chart scripts, and (b) the client-side renderer into a blank page. Playwright serves both from one made-up origin
(``http://render.test``) by request interception, with the console's Content-Security-Policy on every answer, and records every request
(none may leave that origin) and every CSP violation. ``read(page)`` takes what is compared: the accessible tree, the visible text, every
cell, every link and its target, every tone, and every chart's series from its ECharts option."""
from __future__ import annotations

import json
import mimetypes
import re
from pathlib import Path
from urllib.parse import urlparse

from jinja2 import Environment, FileSystemLoader

from core.app import CSP
from core import render_dist, screen_grid

CONSOLE = Path(__file__).resolve().parent.parent
WEB = CONSOLE / "web"
ORIGIN = "http://render.test"
AFFORDANCES = re.compile(r'<details class="pnl-dl" data-export-menu.*?</details>', re.S)
THEMES = ["", "light", "terminal", "blue", "green", "crimson", "crimson-dark", "wallstreet", "terminal-blue", "terminal-green", "terminal-crimson"]
CONSOLE_SHEETS = ["vendor/bootstrap/css/bootstrap.min.css", "vendor/bootstrap-icons/bootstrap-icons.css"] + [s for s in render_dist.SHEETS]
LIB = f"/static/render/{render_dist.VERSION}/drishti-render.js"

_envs: dict = {}


def env(design_on: bool) -> Environment:
    """One environment per design_on value: a macro module keeps the globals it was first made with."""
    if design_on not in _envs:
        e = Environment(loader=FileSystemLoader(str(WEB / "templates")), extensions=["jinja2.ext.do"], autoescape=True)
        e.globals.update(ASSET_V="", design_on=(lambda: design_on))
        _envs[design_on] = e
    return _envs[design_on]


def console_html(vm: dict, theme: str = "", design_on: bool = True) -> str:
    """The view as the console's macros draw it (banners, the head of an embedded view, the panels in their grid), on a page with the
    console's sheets and the scripts that enhance first paint (charts, tabs, widths, the grid)."""
    e = env(design_on)
    parts = e.get_template("_macros/view.html").module
    panels = vm.get("panels") or []
    grid = screen_grid.arrange(vm.get("layout"), panels, {}) if vm.get("layout") else None
    if grid:
        body = e.get_template("terminal/_grid.html").render(grid=grid, vm=vm, embed=True, ASSET_V="")
    else:
        main = [p for p in panels if p.get("area") != "right"]
        right = [p for p in panels if p.get("area") == "right"]
        body = ('<div class="vgrid"><div class="vmain">' + str(parts.panels(main, True, vm.get("ref"))) + '</div><aside class="vright" aria-label="Context">'
                + str(parts.panels(right, True, vm.get("ref"))) + "</aside></div>")
    body = AFFORDANCES.sub("", body)                 # the grid template's panels carry the console-only download menu
    ref = vm.get("ref") or {}
    view = (f'<div class="view" data-view data-kind="{ref.get("kind", "")}" data-id="{ref.get("id", "")}">'
            + str(parts.provenance_banners(vm, "live", {}, False, "")) + str(parts.vhead(vm)) + body + "</div>")
    links = "".join(f'<link rel="stylesheet" href="/static/{s.removeprefix("static/")}">' for s in CONSOLE_SHEETS)
    scripts = "".join(f'<script src="{s}"></script>' for s in ("/render-test/csp.js", "/static/vendor/echarts/echarts.min.js", "/static/js/geomap.js", "/static/js/charts.js", "/static/js/view.js"))
    t = f' data-theme="{theme}"' if theme else ""
    return f'<!DOCTYPE html><html lang="en"{t}><head><meta charset="utf-8"><title>console</title>{links}</head><body class="terminal">{view}{scripts}</body></html>'


def library_html() -> str:
    return ('<!DOCTYPE html><html lang="en"><head><meta charset="utf-8"><title>library</title><link rel="stylesheet" href="/render-test/page.css"></head><body>'
            '<div id="host"></div><script src="/render-test/csp.js"></script><script type="module" src="/render-test/harness.js"></script></body></html>')


CSP_JS = "window.__csp = []; document.addEventListener('securitypolicyviolation', function (e) { window.__csp.push(e.violatedDirective + ' ' + e.blockedURI); });"

HARNESS_JS = """
import { render } from '%s';
const q = new URLSearchParams(location.search);
const vm = await (await fetch('/render-test/vm/' + q.get('case') + '.json')).json();
const opts = JSON.parse(q.get('o') || '{}');
const host = document.getElementById('host');
window.__handle = render(host, vm, Object.assign({ tables: false, designOn: q.get('design') === '1', theme: q.get('theme') || 'inherit' }, opts));
await window.__handle.ready;
window.__rendered = true;
""" % LIB


LAB_HTML = ('<!DOCTYPE html><html lang="en"><head><meta charset="utf-8"><title>lab</title><link rel="stylesheet" href="/render-test/page.css"></head><body>'
            '<div id="host"></div><script src="/render-test/csp.js"></script><script type="module" src="/render-test/lab.js"></script></body></html>')
# the lab page: the library's module and its parts on window, every drishti:* event recorded, window.open counted (never called in place)
LAB_JS = """
import * as R from '%s';
window.R = R;
window.__events = [];
window.__opened = [];
window.open = function () { window.__opened.push([...arguments]); return null; };
const host = document.getElementById('host');
['navigate', 'row-click', 'select', 'paint', 'loaded', 'tick', 'ready', 'error', 'deleted', 'restored', 'pivot', 'key'].forEach((n) =>
  host.addEventListener('drishti:' + n, (e) => { const d = Object.assign({}, e.detail); delete d.host; window.__events.push([n, JSON.parse(JSON.stringify(d))]);
    if (window.__cancel && window.__cancel.includes(n)) { e.preventDefault(); } }));
window.vm = async (name) => (await fetch('/render-test/vm/' + name + '.json')).json();
window.__lab = true;
""" % LIB


class Site:
    """The made-up origin: static files from web/static, test pages and ViewModels from memory; every request is recorded."""

    def __init__(self):
        self.pages: dict[str, str] = {"/render-test/library.html": library_html(), "/render-test/csp.js": CSP_JS, "/render-test/harness.js": HARNESS_JS,
                                     "/render-test/page.css": "body { margin: 0; }", "/render-test/lab.html": LAB_HTML, "/render-test/lab.js": LAB_JS}
        self.requests: list[str] = []

    def add_vm(self, name: str, vm: dict):
        self.pages[f"/render-test/vm/{name}.json"] = json.dumps(vm)

    def add_console(self, name: str, html: str):
        self.pages[f"/render-test/console/{name}.html"] = html

    def handle(self, route):
        url = route.request.url
        self.requests.append(url)
        u = urlparse(url)
        if f"{u.scheme}://{u.netloc}" != ORIGIN:
            return route.abort()
        path = u.path
        headers = {"Content-Security-Policy": CSP, "X-Content-Type-Options": "nosniff"}
        if path in self.pages or (path + "?" + u.query) in self.pages:
            path = path if path in self.pages else path + "?" + u.query
            body = self.pages[path]
            ctype = ("text/event-stream" if path.startswith(("/render-test/sse/", "/api/stream/")) else "text/html" if path.endswith(".html") else "application/json" if (path.endswith(".json") or path.startswith("/api/"))
                     else "text/css" if path.endswith(".css") else "text/javascript")
            return route.fulfill(status=200, body=body, headers={**headers, "Content-Type": ctype + "; charset=utf-8"})
        if path.startswith("/static/"):
            f = (WEB / "static" / path[len("/static/"):]).resolve()
            if f.is_file() and (WEB / "static") in f.parents:
                ctype = {".js": "text/javascript", ".css": "text/css", ".json": "application/json", ".woff2": "font/woff2"}.get(f.suffix) or mimetypes.guess_type(f.name)[0] or "application/octet-stream"
                return route.fulfill(status=200, body=f.read_bytes(), headers={**headers, "Content-Type": ctype})
        return route.fulfill(status=404, body="not found", headers=headers)


READ_JS = """
async () => {
  const host = document.querySelector('#host');
  const scope = host && host.shadowRoot ? host.shadowRoot : document;
  const view = scope.querySelector('.view');
  const vis = (el) => el.checkVisibility ? el.checkVisibility() : !!(el.offsetWidth || el.offsetHeight || el.getClientRects().length);
  const norm = (s) => String(s || '').replace(/\\s+/g, ' ').trim();
  const E = window.echarts;
  const charts = [];
  view.querySelectorAll('.pnl').forEach((p) => {
    p.querySelectorAll('.chart, .xchart').forEach((el, i) => {
      const c = E && E.getInstanceByDom(el);
      if (!c) { charts.push([p.getAttribute('data-panel'), i, el.className.includes('pnl-empty') ? 'empty' : 'none']); return; }
      const o = c.getOption();
      const series = (o.series || []).map((s) => ({ type: s.type, name: s.name == null ? '' : String(s.name), data: JSON.parse(JSON.stringify(s.data || null, (k, v) => typeof v === 'function' ? undefined : v)) }));
      charts.push([p.getAttribute('data-panel'), i, series]);
    });
  });
  // what ECharts draws inside a chart's box (axis ticks laid out to the pixel width) is compared above as series, not as text
  view.querySelectorAll('.chart > *, .xchart > *').forEach((c) => { c.hidden = true; });
  const lines = view.innerText.split('\\n').map(norm).filter(Boolean);
  const cells = [...view.querySelectorAll('td, th')].filter(vis).map((c) => norm(c.textContent));
  const links = [...view.querySelectorAll('a[href]')].filter(vis).map((a) => [norm(a.textContent), a.getAttribute('href').startsWith('?') ? a.getAttribute('href') : a.href]);
  const tones = [...view.querySelectorAll('[class*="t-"]')].map((e) => [e.textContent.replace(/\\s+/g, ''), [...e.classList].filter((c) => /^t-/.test(c)).join(' ')]).filter((x) => x[1]);
  const se = document.scrollingElement;
  return { lines, cells, links, tones, charts, overflow: se.scrollWidth - window.innerWidth, csp: window.__csp || [] };
}
"""


def settle(page, timeout=15000):
    """Waits until the charts and the map shapes have been drawn (network idle, then two frames)."""
    page.wait_for_load_state("networkidle", timeout=timeout)
    page.evaluate("() => new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)))")
    page.wait_for_timeout(150)


def read(page) -> dict:
    out = page.evaluate(READ_JS)
    host = page.locator("#host")
    view = host.locator(".view") if host.count() else page.locator("body > .view")
    out["aria"] = view.first.aria_snapshot()
    return out
