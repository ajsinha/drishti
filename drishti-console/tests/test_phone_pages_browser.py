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

"""Every console page on a phone (390 px, touch) with a real server behind the console (UX-02, UX-03): real content, finger-sized
controls, nothing clipped. The pages are the width sweep's (test_page_widths_browser.py), with the trading pack's sample trade as the
view; they are opened PAGES_AT_ONCE at a time on the worker's Chromium (``browsers``) in one touch context. One test on one server
(the file runs on one worker; under 300 s, docs/architecture/TEST_HARNESS.md rule 6). Skipped without the built server jar, Playwright
or its Chromium."""
import asyncio

import pytest

from wb_live import BROWSER_WAIT_MS, _stack

pytest.importorskip("playwright.async_api", reason="needs Playwright (pip install playwright)")

from browser_harness import PAGES_AT_ONCE, SETTLED_JS, settled  # noqa: E402
from test_page_widths_browser import NO_SHARE, PACK, _pages  # noqa: E402 - the sweep's pages

# Pages that are meant to be problem pages (a missing entity, a kind that does not exist, a share nobody may open).
PROBLEM_PAGES = ("/v/trade/NOPE-404", "/v/nosuchkind/X", f"/share/{NO_SHARE}")
# What marks a page that fell over instead of showing its content: the problem template's alert, an error status.
CONTENT = r"""() => {
  const bad = [...document.querySelectorAll('.thome > .pnl-err, .dv-error, .adm-err, [data-problem]')].map(e => e.textContent.trim().slice(0, 90));
  return { bad, text: document.body.innerText.trim().length };
}"""

# Visible controls smaller than 44 px in their smaller side (the console's own bar for touch). Not counted: a link inside a
# sentence of running text (the inline exception of WCAG 2.5.8: its size is set by the line), a control whose label or wrapping
# link/button gives it a 44 px hit area, the page footer, and
# CodeMirror's hidden input proxy inside an editor (the editor itself is the target).
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
    if (e.tagName === 'TEXTAREA' && e.closest('.CodeMirror')) continue;   // the editor's hidden input proxy: people tap the editor itself
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
    if (e.scrollWidth <= e.clientWidth + 1 || e.clientWidth <= 1 || s.textOverflow === 'ellipsis' || s.display === 'none') continue;
    if (e.classList.contains('CodeMirror')) continue;   // CodeMirror hides native scrollbars with a -50 px margin on its own scroller; it scrolls, it does not clip
    if (!e.textContent.trim() || e.closest('[aria-hidden=true], svg, canvas, .chart, .xchart, .surface, .visually-hidden, .sr-only, .asof-live-l')) continue;
    out.push(e.tagName.toLowerCase() + (typeof e.className === 'string' && e.className ? '.' + e.className.trim().split(/\s+/)[0] : '') + ' ' + e.scrollWidth + '>' + e.clientWidth);
  }
  return [...new Set(out)].slice(0, 6);
}"""


@pytest.fixture(scope="module")
def phone_console(tmp_path_factory):
    """The real server with the trading pack (its sample trade exists) behind a console; skipped without the built jar."""
    yield from _stack(tmp_path_factory, {"DRISHTI_PACKS": PACK})


async def _sweep(browsers, base: str, urls: list[str]) -> list[tuple]:
    """Each page's (url, status, CONTENT, TARGETS, CLIPPED), opened PAGES_AT_ONCE at a time in one phone context."""
    ctx = await browsers.browser.new_context(viewport={"width": 390, "height": 844}, is_mobile=True, has_touch=True)
    await ctx.add_init_script(path=str(SETTLED_JS))
    gate = asyncio.Semaphore(PAGES_AT_ONCE)

    async def one(url: str) -> tuple:
        async with gate:
            page = await ctx.new_page()
            try:
                resp = await page.goto(base + url, wait_until="load", timeout=BROWSER_WAIT_MS)
                await settled(page)                           # laid out by its own scripts, its requests answered, quiet
                return url, resp.status, await page.evaluate(CONTENT), await page.evaluate(TARGETS), await page.evaluate(CLIPPED)
            finally:
                await page.close()
    try:
        return await asyncio.gather(*(one(u) for u in urls))
    finally:
        await ctx.close()


def test_phone_pages_show_content_have_finger_sized_targets_and_clip_nothing(browsers, phone_console):
    urls = [u for u in _pages(["trade/1000008"]) if u != "/history"]   # /history does not exist (it is in the sweep as a 404 page)
    broken, small, clipped = [], [], []
    for url, status, seen, targets, clips in browsers.run(_sweep(browsers, str(phone_console), urls)):
        if url not in PROBLEM_PAGES and (status >= 400 or seen["bad"] or not seen["text"]):
            broken.append(f"{url}: status {status} {seen['bad'][:1]}")
        small += [f"{url}: {t}" for t in targets]
        clipped += [f"{url}: {c}" for c in clips]
    assert not broken, "pages that show a problem instead of content:\n" + "\n".join(broken)
    assert not small, "tap targets under 44 px:\n" + "\n".join(small)
    assert not clipped, "text clipped horizontally:\n" + "\n".join(clipped)
