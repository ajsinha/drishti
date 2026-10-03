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

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")

from test_live_tabs_browser import _views, browser, console_url  # noqa: E402,F401 - the shared browser fixtures

WIDTHS = (390, 1600, 2560)
ADMIN = ("users", "audit", "health", "caches", "packs", "roles", "tokens", "access")


def _pages() -> list[str]:
    views = _views()
    first = views[0]
    pages = ["/", "/t", *(f"/v/{v}" for v in views), "/v/trade/NOPE-404", "/v/nosuchkind/X",
             "/s?q=TRD%20where%20mtm%20%3E%201m%20order%20by%20mtm%20desc%20limit%2020",
             f"/compare/{first}?a=2026-09-29&b=2026-09-30", f"/impact/{first}", "/history", "/m", "/alerts", "/w",
             "/build", "/build/reviews", "/reports", "/servers", "/account", *(f"/admin/{a}" for a in ADMIN),
             "/help", "/help/search?q=pivot", "/about", "/about/competitive"]
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
            page.goto(console_url + url, wait_until="load", timeout=20000)
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
