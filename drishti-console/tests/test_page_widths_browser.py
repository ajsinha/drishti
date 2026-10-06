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

"""Every console page at a phone (390 px), a desktop (1600 px) and a wide screen (2560 px) (UX-02, UX-03): the page never
scrolls sideways (wide tables scroll inside their own container), the top bar fits with its Live pill and avatar on
screen, and no image is wider than its column; help-centre screenshots keep their aspect ratio and open full size.

Runs Chromium through Playwright, with the console and stand-in server of test_live_tabs_browser.py (the same fixtures,
the same DRISHTI_LIVE_E2E_URL / DRISHTI_LIVE_E2E_VIEWS to run against a real console); skipped when Playwright or its
Chromium is not installed (`pip install playwright` and `playwright install chromium`)."""
import re

import pytest
import yaml

from conftest import CONSOLE
from wb_live import BROWSER_WAIT_MS, _stack  # the real server: the phone sweep needs real pages, not the stand-in's

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")

from test_live_tabs_browser import _views, browser, console_url  # noqa: E402,F401 - the shared browser fixtures

WIDTHS = (390, 1600, 2560)
PACK = "trading"                      # a pack the stand-in server loads (its loads page is the same shape for any pack)
NO_SHARE = "sh_0123456789abcdef"     # a share id nobody can open: the clean no-access page
ADMIN = ("users", "audit", "health", "caches", "packs", "roles", "tokens", "access")


def _pages(views: list[str] | None = None) -> list[str]:
    views = views or _views()
    first = views[0]
    pages = ["/", "/t", *(f"/v/{v}" for v in views), "/v/trade/NOPE-404", "/v/nosuchkind/X",
             "/s?q=TRD%20where%20mtm%20%3E%201m%20order%20by%20mtm%20desc%20limit%2020",
             f"/compare/{first}?a=2026-09-29&b=2026-09-30", f"/impact/{first}", "/history", "/m", "/alerts", "/w",
             "/build", "/build/reviews", "/reports", "/servers", "/account", *(f"/admin/{a}" for a in ADMIN),
             "/help", "/help/search?q=pivot", "/about", "/about/competitive",
             # the newer pages: data loads (all, and one pack's), collaboration admin, the inbox, a share nobody may open, the landing page's way back
             "/admin/loads", f"/admin/packs/{PACK}/loads", "/admin/collab", "/admin/embedding", "/inbox", "/inbox?tab=mentions&unread=1", f"/share/{NO_SHARE}",
             f"/?from=/v/{first}", "/?from=/admin/loads"]
    catalogue = yaml.safe_load((CONSOLE / "config" / "help.yaml").read_text())
    pages += [f"/help/{g['slug']}" for cat in catalogue["categories"] for g in cat.get("guides", [])]
    # last, a view at a past business date and then as known at a time: the top bar's widest state
    pages += [f"/asof?d=2026-09-29&next=/v/{first}", f"/asof?d=2026-09-29&k=2026-09-29T10:00&next=/v/{first}"]
    return pages


# What a person would see as broken at this width: the page scrolling sideways, a top-bar control off screen, an image
# wider than the column it sits in or stretched out of shape, a guide screenshot that cannot be opened full size.
AUDIT = r"""() => {
  const doc = document.documentElement, cw = doc.clientWidth, out = [];
  if (doc.scrollWidth > cw + 1) {
    const wide = [...document.querySelectorAll('body *')].filter(e => {
      const r = e.getBoundingClientRect();
      if (!r.width || r.right <= cw + 1 || getComputedStyle(e).position === 'fixed') return false;
      for (let p = e.parentElement; p; p = p.parentElement) { if (/auto|scroll|hidden|clip/.test(getComputedStyle(p).overflowX)) return false; }
      return true;
    }).slice(0, 3).map(e => e.tagName.toLowerCase() + (typeof e.className === 'string' && e.className ? '.' + e.className.split(' ')[0] : '') + '@' + Math.round(e.getBoundingClientRect().right));
    out.push('scrolls sideways: ' + doc.scrollWidth + ' px wide in ' + cw + ' (' + wide.join(', ') + ')');
  }
  for (const e of document.querySelectorAll('.tbar-row1 > *, .tbar-tools > *')) {
    const r = e.getBoundingClientRect(), s = getComputedStyle(e);
    if (r.width && s.display !== 'none' && s.visibility !== 'hidden' && (r.right > cw + 1 || r.left < -1)) {
      out.push('top bar: ' + (e.className || e.tagName) + ' off screen (' + Math.round(r.left) + '..' + Math.round(r.right) + ')');
    }
  }
  for (const img of document.images) {
    const r = img.getBoundingClientRect();
    if (!r.width || !img.naturalWidth || img.closest('[aria-hidden=true]')) continue;
    const box = img.parentElement.closest('p, figure, div, li, td, section, article, main') || document.body;
    const cs = getComputedStyle(box), inner = box.clientWidth - parseFloat(cs.paddingLeft) - parseFloat(cs.paddingRight);
    const name = img.getAttribute('src');
    if (r.width > inner + 1) out.push('image ' + name + ' ' + Math.round(r.width) + ' px in a ' + Math.round(inner) + ' px column');
    const is = getComputedStyle(img), px = k => parseFloat(is[k]) || 0;             // the picture itself: less frame and padding
    const w = r.width - px('borderLeftWidth') - px('borderRightWidth') - px('paddingLeft') - px('paddingRight');
    const h = r.height - px('borderTopWidth') - px('borderBottomWidth') - px('paddingTop') - px('paddingBottom');
    const want = img.naturalWidth / img.naturalHeight, got = w / h;
    if (Math.abs(got - want) / want > 0.02) out.push('image ' + name + ' out of shape (' + got.toFixed(2) + ' for ' + want.toFixed(2) + ')');
    if (img.closest('.help-article')) {
      const a = img.closest('a[href]');
      if (!a || a.getAttribute('href') !== name) out.push('image ' + name + ' does not open full size');
    }
  }
  return out;
}"""


# Images loaded and decoded (lazy ones off screen never load: they are skipped, at most a second is spent waiting).
DECODED = "() => Promise.race([Promise.all([...document.images].map(i => i.decode().catch(() => null))), new Promise(r => setTimeout(r, 1000))])"


@pytest.mark.parametrize("width", WIDTHS)
def test_no_page_scrolls_sideways_and_images_fit(browser, console_url, width):
    ctx = browser.new_context(viewport={"width": width, "height": 900 if width < 2000 else 1440})
    page = ctx.new_page()
    problems = []
    try:
        for url in _pages():
            page.goto(console_url + url, wait_until="load", timeout=BROWSER_WAIT_MS)
            page.wait_for_timeout(250)                       # let the page's own scripts lay it out
            page.evaluate(DECODED)
            problems += [f"{url} @ {width} px: {p}" for p in page.evaluate(AUDIT)]
    finally:
        ctx.close()
    assert not problems, "\n".join(problems)


def test_the_sweep_covers_every_help_guide_and_admin_page():
    pages = _pages()
    assert sum(1 for p in pages if re.match(r"/help/[a-z]", p) and "search" not in p) >= 30
    assert all(f"/admin/{a}" in pages for a in ADMIN)


# ---- a phone (390 px, touch): real content, finger-sized controls, nothing clipped ----------------------------------------
# Pages that are meant to be problem pages (a missing entity, a kind that does not exist, a share nobody may open).
PROBLEM_PAGES = ("/v/trade/NOPE-404", "/v/nosuchkind/X", f"/share/{NO_SHARE}")
# What marks a page that fell over instead of showing its content: the problem template's alert, an error status.
CONTENT = r"""() => {
  const bad = [...document.querySelectorAll('.thome > .pnl-err, .dv-error, .adm-err, [data-problem]')].map(e => e.textContent.trim().slice(0, 90));
  return { bad, text: document.body.innerText.trim().length };
}"""

# Visible controls smaller than 44 px in their smaller side (the console's own bar for touch). Not counted: a link inside a
# sentence of running text (the inline exception of WCAG 2.5.8: its size is set by the line), a control whose label or wrapping
# link/button gives it a 44 px hit area, and the page footer.
TARGETS = r"""() => {
  const out = [], seen = new Set();
  const vis = e => { const r = e.getBoundingClientRect(), s = getComputedStyle(e);
    return r.width > 0 && r.height > 0 && s.visibility !== 'hidden' && s.display !== 'none' && !e.closest('[hidden], [aria-hidden=true], .skip-link') && r.bottom > 0; };
  const big = r => Math.min(r.width, r.height) >= 43.5;
  const inline = e => { if (e.tagName !== 'A' || getComputedStyle(e).display !== 'inline') return false;
    const p = e.closest('p, li, td, dd, dt, h1, h2, h3, h4, label, small, figcaption') || e.parentElement;
    return p && (p.textContent || '').trim().length > (e.textContent || '').trim().length + 12; };
  for (const e of document.querySelectorAll('a[href], button, input:not([type=hidden]), select, textarea, [role=button], summary')) {
    if (!vis(e) || e.closest('footer')) continue;
    const r = e.getBoundingClientRect();
    const label = e.labels && e.labels[0] && vis(e.labels[0]) ? e.labels[0].getBoundingClientRect() : null;
    if (big(r) || (label && big(label)) || inline(e)) continue;
    const wrap = e.parentElement && e.parentElement.closest('a[href], button, label, summary');
    if (wrap && big(wrap.getBoundingClientRect())) continue;
    const cls = x => typeof x.className === 'string' && x.className ? '.' + x.className.trim().split(/\s+/)[0] : '';
    const name = (e.parentElement ? e.parentElement.tagName.toLowerCase() + cls(e.parentElement) + ' > ' : '') + e.tagName.toLowerCase() + cls(e) + (e.getAttribute('type') ? '[' + e.getAttribute('type') + ']' : '');
    const key = name + ' ' + Math.round(r.width) + 'x' + Math.round(r.height);
    if (!seen.has(key)) { seen.add(key); out.push(key + ' "' + (e.textContent || e.getAttribute('aria-label') || e.getAttribute('title') || '').trim().slice(0, 24) + '"'); }
  }
  return out;
}"""

# Text cut off by overflow:hidden without an ellipsis or a way to scroll (text-overflow: ellipsis is a decision; clipping silently is not).
CLIPPED = r"""() => {
  const out = [];
  for (const e of document.querySelectorAll('body *')) {
    const s = getComputedStyle(e);
    if (s.overflowX !== 'hidden' && s.overflowX !== 'clip') continue;
    if (e.scrollWidth <= e.clientWidth + 1 || !e.clientWidth || s.textOverflow === 'ellipsis' || s.display === 'none') continue;
    if (!e.textContent.trim() || e.closest('[aria-hidden=true], svg, canvas, .chart, .xchart, .surface, .visually-hidden, .sr-only, .asof-live-l')) continue;
    out.push(e.tagName.toLowerCase() + (typeof e.className === 'string' && e.className ? '.' + e.className.trim().split(/\s+/)[0] : '') + ' ' + e.scrollWidth + '>' + e.clientWidth);
  }
  return [...new Set(out)].slice(0, 6);
}"""


@pytest.fixture(scope="module")
def phone_console(tmp_path_factory):
    """The real server with the trading pack (its sample trade exists) behind a console; skipped without the built jar."""
    yield from _stack(tmp_path_factory, {"DRISHTI_PACKS": PACK})


def test_phone_pages_show_content_have_finger_sized_targets_and_clip_nothing(browser, phone_console):
    console_url = str(phone_console)
    ctx = browser.new_context(viewport={"width": 390, "height": 844}, is_mobile=True, has_touch=True)
    page = ctx.new_page()
    broken, small, clipped = [], [], []
    try:
        for url in _pages(["trade/END-1000008"]):
            if url == "/history":                      # a page that does not exist (it is in the sweep as a 404 page)
                continue
            resp = page.goto(console_url + url, wait_until="load", timeout=BROWSER_WAIT_MS)
            page.wait_for_timeout(250)
            seen = page.evaluate(CONTENT)
            if url not in PROBLEM_PAGES and (resp.status >= 400 or seen["bad"] or not seen["text"]):
                broken.append(f"{url}: status {resp.status} {seen['bad'][:1]}")
            small += [f"{url}: {t}" for t in page.evaluate(TARGETS)]
            clipped += [f"{url}: {c}" for c in page.evaluate(CLIPPED)]
    finally:
        ctx.close()
    assert not broken, "pages that show a problem instead of content:\n" + "\n".join(broken)
    assert not small, "tap targets under 44 px:\n" + "\n".join(small)
    assert not clipped, "text clipped horizontally:\n" + "\n".join(clipped)
