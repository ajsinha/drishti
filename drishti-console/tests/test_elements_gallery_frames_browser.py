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

"""The frame-time budget of every gallery page (a browser check): each component on the stage is drawn, then 30 animation frames are timed; the
median frame and the slowest must stay under the budget, and it must be ready (audited) within its time. A page that draws slowly shows here
before a person feels it. The budgets are generous on purpose (a headless browser on a shared machine); a component that needs more says why
in OVERRIDES, and the file only shrinks the list."""
import pytest

pytestmark = pytest.mark.spread

from test_elements_gallery_browser import audited, frames, run  # noqa: E402 - the same static origin, strict CSP and router

MEDIAN_MS = 40        # a frame on a quiet machine is 16; headless on a busy one is 2 to 3 times that
WORST_MS = 250        # the slowest of 30 frames
OVERRIDES = {"chart-map": {"worst": 600}, "map": {"worst": 600}, "graph": {"worst": 500}, "pivot": {"worst": 500}}   # a map and a layout draw in one long task


def test_every_gallery_page_stays_within_the_frame_budget(browsers):
    async def body(pg):
        demos = await pg.evaluate("() => window.__gallery.demos.map((d) => d.id)")
        slow = []
        for cid in demos:
            await pg.click(f".gx-nav-item[data-c='{cid}']")
            await audited(pg, cid)
            await frames(pg, 3)
            gaps = await pg.evaluate("() => new Promise((done) => { const t = []; let last = performance.now(); const f = () => { const n = performance.now(); t.push(n - last); last = n; if (t.length < 30) { requestAnimationFrame(f); } else { done(t); } }; requestAnimationFrame(f); })")
            gaps.sort()
            median, worst = gaps[len(gaps) // 2], gaps[-1]
            if median > MEDIAN_MS or worst > OVERRIDES.get(cid, {}).get("worst", WORST_MS):
                slow.append(f"{cid}: median {median:.0f} ms, worst {worst:.0f} ms")
        assert not slow, "gallery pages over the frame budget (" + str(MEDIAN_MS) + " ms median, " + str(WORST_MS) + " ms worst):\n" + "\n".join(slow)
    run(browsers, body)
