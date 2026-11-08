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

One spread test per page (docs/architecture/TEST_HARNESS.md, section 3.1), the three widths of a page opened side by side on the
worker's Chromium (``browsers``) against a stand-in console of this file, one per worker (the ticking stand-in server of
test_live_tabs_browser.py; DRISHTI_LIVE_E2E_URL and DRISHTI_LIVE_E2E_VIEWS run it against a real console). The phone sweep on a real
server is test_phone_pages_browser.py. Skipped when Playwright or its Chromium is not installed (`pip install playwright` and
`playwright install chromium`)."""
import asyncio
import os
import re

import pytest
import yaml

from conftest import CONSOLE

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")

from browser_harness import open_settled  # noqa: E402
from table_chart_backend import serve  # noqa: E402
from test_live_tabs_browser import TickingBackend, _views  # noqa: E402 - the ticking stand-in server and its views

WIDTHS = (390, 1600, 2560)
PACK = "trading"                      # a pack the stand-in server loads (its loads page is the same shape for any pack)
NO_SHARE = "sh_0123456789abcdef"     # a share id nobody can open: the clean no-access page
ADMIN = ("users", "audit", "health", "caches", "capacity", "packs", "roles", "tokens", "access")


def _pages(views: list[str] | None = None) -> list[str]:
    views = views or _views()
    first = views[0]
    pages = ["/", "/t", *(f"/v/{v}" for v in views), "/v/trade/NOPE-404", "/v/nosuchkind/X",
             "/s?q=TRD%20where%20mtm%20%3E%201m%20order%20by%20mtm%20desc%20limit%2020",
             f"/compare/{first}?a=2026-09-29&b=2026-09-30", f"/impact/{first}", "/history", "/m", "/alerts", "/w",
             "/build", "/build/pack/new", "/approvals", "/reports", "/forms", "/servers", "/account", *(f"/admin/{a}" for a in ADMIN),
             "/help", "/help/search?q=pivot", "/about", "/help/competitive-analysis",
             # the newer pages: data loads (all, and one pack's), collaboration admin, the inbox, a share nobody may open, the landing page's way back
             "/bi/catalogue", "/bi/workspaces", "/bi/w/me", "/bi/datasets", "/bi/designer", "/bi/notebook", "/admin/bi",
             # Rupaka phase 3: the report viewer (a page and a drill-through page), the designer on a draft (their shells: no data behind the stand-in server)
             "/bi/r/file:report:trades-overview", "/bi/r/file:report:counterparty-risk/counterparty", "/bi/design/bi-draft",
             # Rupaka phase 5: a live report (and its Perspective pages) and a board, in kiosk too (their shells: no data behind the stand-in server)
             "/bi/r/file:report:live-trading-desk", "/bi/r/file:report:live-trading-desk/grid", "/bi/b/file:board:desk-wall", "/bi/b/file:board:desk-wall?kiosk=1",
             "/admin/loads", f"/admin/packs/{PACK}/loads", f"/admin/packs/{PACK}", "/admin/connectors", "/admin/connectors/trade-endur", "/admin/artifacts", "/admin/collab", "/admin/embedding", "/inbox", "/inbox?tab=mentions&unread=1", f"/share/{NO_SHARE}",
             f"/?from=/v/{first}", "/?from=/admin/loads"]
    catalogue = yaml.safe_load((CONSOLE / "config" / "help.yaml").read_text())
    # an engine off as shipped (config/engines/<engine>.yaml, e.g. the in-memory lake) takes its Help pages away (404): not read here
    off = set()
    from core import engines as engine_catalogue
    for e in engine_catalogue.entries():                      # the core's and every module's
        f = CONSOLE.parent / "config" / "engines" / str(e.get("file", ""))
        if f.is_file() and re.search(r"enabled:\s*\$\{[A-Z_]+:false\}", f.read_text()):
            off.update(e.get("help") or [])
            off.add("engine:" + str(e.get("id")))
    guides = [g for s in catalogue["sections"] for grp in s["groups"] for g in grp["guides"]]     # the help hub's sections (2.9.11.a)
    pages += [f"/help/section/{s['id']}" for s in catalogue["sections"]]
    pages += [f"/help/{g['slug']}" for g in guides if g["slug"] not in off and "engine:" + str(g.get("engine")) not in off]
    # last, a view at a past business date and then as known at a time: the top bar's widest state
    pages += [f"/asof?d=2026-09-29&next=/v/{first}", f"/asof?d=2026-09-29&k=2026-09-29T10:00&next=/v/{first}"]
    return list(dict.fromkeys(pages))       # once each: a page named above may also be a catalogue guide (/help/competitive-analysis)


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


def _id(url: str) -> str:
    return re.sub(r"[^A-Za-z0-9]+", "-", url).strip("-") or "home"


PAGES = _pages()


@pytest.fixture(scope="session")
def widths_console():
    """The console under test, one per worker: a real one when DRISHTI_LIVE_E2E_URL is set, else the stand-in started here."""
    if os.environ.get("DRISHTI_LIVE_E2E_URL"):
        yield os.environ["DRISHTI_LIVE_E2E_URL"].rstrip("/")
        return
    url, _backend, stop = serve(TickingBackend())
    yield url
    stop()


async def _audit(browsers, url: str, width: int) -> list[str]:
    async with browsers.page(width, 900 if width < 2000 else 1440, show_everything=False) as page:
        await open_settled(page, url)                      # loaded, laid out by its own scripts and quiet (the settled signal)
        await page.evaluate(DECODED)
        return await page.evaluate(AUDIT)


@pytest.mark.spread
@pytest.mark.parametrize("path", PAGES, ids=_id)
def test_no_page_scrolls_sideways_and_images_fit(browsers, widths_console, path):
    async def every_width():
        return await asyncio.gather(*(_audit(browsers, widths_console + path, w) for w in WIDTHS))
    problems = [f"{path} @ {w} px: {p}" for w, found in zip(WIDTHS, browsers.run(every_width())) for p in found]
    assert not problems, "\n".join(problems)


@pytest.mark.spread
def test_the_sweep_covers_every_help_guide_and_admin_page():
    pages = PAGES
    assert len({_id(p) for p in pages}) == len(pages)           # one test id per page
    assert sum(1 for p in pages if re.match(r"/help/[a-z]", p) and "search" not in p) >= 30
    assert all(f"/admin/{a}" in pages for a in ADMIN)
