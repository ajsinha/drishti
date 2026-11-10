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

"""The visual snapshots of the key pages (docs/architecture/TEST_HARNESS.md, "The visual snapshots"): each page captured in the
browser's viewport (animations off, caret hidden, fonts loaded, the live clock and figures that move masked), scaled to half its size and
mapped onto the golden's own palette, then compared pixel by pixel with its golden image (tests/snapshots/<page>/<width>-<theme>.png).

The golden is a half-size PNG of at most 128 colours: a quarter of the pixels and a palette keep the whole set (132 images) near 4 MB, small enough
to commit and to refresh in a release, yet a moved panel, a lost border, a wrong theme colour or a missing chart still shows (a full-size
capture would be about ten times the size; a perceptual hash alone would not draw what changed). Mapping the capture onto the golden's
palette makes an unchanged page compare to zero pixels changed.

A page fails when the share of its pixels that changed (any channel by more than THRESHOLD) passes its tolerance; a diff image (golden,
capture, changed pixels in red) is written to DRISHTI_SNAPSHOT_DIFFS (default: the test's temporary folder) and one line per page to
DRISHTI_SNAPSHOT_REPORT (the drill: <data>/drill/snapshots.jsonl). ``pytest --update-snapshots`` writes the goldens instead; the drill
never does (it refuses under DRILL_TIMINGS_OUT)."""
from __future__ import annotations

import io
import json
import os
from pathlib import Path

GOLDEN = Path(__file__).resolve().parent / "snapshots"
SCALE = 2           # the golden's pixels: one per SCALE x SCALE of the capture
COLOURS = 128       # the golden's palette
THRESHOLD = 40      # a pixel changed: any channel off by more than this (of 255)
TOLERANCE = 0.002   # a page changed: more than this share of its pixels changed (per page in the test's KEY_SNAPSHOTS)

#: masked on every page: the live clock, the business date box, Home's fact of the day (it changes on "Another fact" and by day), the
#: data inbox's facts (its next cycle, the free disk, this host's name), relative times and
#: anything marked data-snapshot-mask
MASK = ["[data-clock]", "#asofDate", "[data-dyk]", "[data-ready-facts]", "[data-inbox-next]", "[data-snapshot-mask]", "time.timeago",
        "[data-relative-time]"]
#: timings that change their own width (a mask over them would move with it): the VaR explorer's paint, server and aggregation
#: times, a live board's last update; their words replaced by one dot
QUIET = ["[data-vx-timing]", "[data-vx-fresh]", "[data-live-time]"]

_STILL = """async (quiet) => {
  for (const e of document.querySelectorAll(quiet.join(','))) { if (e.textContent) { e.textContent = '\u00b7'; } }
  if (document.fonts && document.fonts.ready) { await document.fonts.ready; }
  if (document.activeElement && document.activeElement.blur) { document.activeElement.blur(); }
  window.scrollTo(0, 0);
  for (const e of document.querySelectorAll('*')) { if (e.scrollTop > 0) { e.scrollTop = 0; } }   // a page that scrolls a panel to its anchor
  const st = document.createElement('style');
  st.textContent = '*, *::before, *::after { transition: none !important; animation: none !important; caret-color: transparent !important; scroll-behavior: auto !important; }'
                 + ' ::-webkit-scrollbar { display: none; } .toasts { display: none !important; }';
  document.head.appendChild(st);
  await new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)));
}"""


def capture(pg, masks: list[str] = (), part: str | None = None) -> bytes:
    """The page's viewport at its top as PNG, or only the element ``part`` (a page that scrolls itself to a part when it has drawn),
    still (toasts hidden) with ``MASK`` and ``masks`` covered."""
    pg.evaluate(_STILL, QUIET)
    pg.mouse.move(0, 0)
    locs = [pg.locator(s) for s in (*MASK, *masks)]
    shot = dict(animations="disabled", caret="hide", mask=locs, mask_color="#808080")
    if not part:
        return pg.screenshot(full_page=False, **shot)
    # the part's first screenful, the sticky top bar left out (it would be drawn over the part wherever the capture scrolled)
    pg.evaluate("() => document.querySelectorAll('header.tbar').forEach((h) => { h.style.visibility = 'hidden'; })")
    png = pg.locator(part).first.screenshot(**shot)
    from PIL import Image

    im = Image.open(io.BytesIO(png))
    out = io.BytesIO()
    im.crop((0, 0, im.width, min(im.height, pg.viewport_size["height"]))).save(out, format="PNG")
    return out.getvalue()


def _small(png: bytes):
    from PIL import Image

    im = Image.open(io.BytesIO(png)).convert("RGB")
    return im.resize((im.width // SCALE, im.height // SCALE), Image.Resampling.BOX)


def golden_path(page: str, width: int, theme: str) -> Path:
    return GOLDEN / page / f"{width}-{theme}.png"


def update(page: str, width: int, theme: str, png: bytes) -> Path:
    from PIL import Image

    out = golden_path(page, width, theme)
    out.parent.mkdir(parents=True, exist_ok=True)
    _small(png).quantize(colors=COLOURS, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.NONE).save(out, optimize=True)
    return out


def compare(page: str, width: int, theme: str, png: bytes, tolerance: float, diffs: Path) -> dict:
    """{page, width, theme, changed, share, diff}: ``changed`` when the capture's changed share passes ``tolerance`` or no golden exists;
    then the capture and a diff image (golden | capture | changed pixels in red) are written under ``diffs``."""
    from PIL import Image, ImageChops

    gp = golden_path(page, width, theme)
    row = {"page": page, "width": width, "theme": theme, "changed": True, "share": 1.0, "diff": None}
    now = _small(png)
    if gp.is_file():
        ref = Image.open(gp)
        now_p = now.quantize(palette=ref, dither=Image.Dither.NONE).convert("RGB")
        ref_rgb = ref.convert("RGB")
        if ref_rgb.size == now_p.size:
            delta = ImageChops.difference(ref_rgb, now_p).split()
            mask = ImageChops.lighter(ImageChops.lighter(delta[0], delta[1]), delta[2]).point(lambda v: 255 if v > THRESHOLD else 0)
            share = mask.histogram()[255] / (mask.width * mask.height)
            row.update(share=round(share, 5), changed=share > tolerance)
            if row["changed"]:
                red = Image.new("RGB", now_p.size, (255, 0, 0))
                marked = Image.composite(red, Image.blend(now_p, Image.new("RGB", now_p.size, (255, 255, 255)), 0.6), mask)
                sheet = Image.new("RGB", (now_p.width * 3, now_p.height), (255, 255, 255))
                for i, im in enumerate((ref_rgb, now_p, marked)):
                    sheet.paste(im, (i * now_p.width, 0))
                row["diff"] = str(_write(diffs, page, width, theme, sheet, png))
    else:
        row["diff"] = str(_write(diffs, page, width, theme, now, png))
    _report(row)
    return row


def _write(diffs: Path, page: str, width: int, theme: str, sheet, png: bytes) -> Path:
    d = diffs / page
    d.mkdir(parents=True, exist_ok=True)
    (d / f"{width}-{theme}.capture.png").write_bytes(png)
    out = d / f"{width}-{theme}.diff.png"
    sheet.save(out)
    return out


def _report(row: dict) -> None:
    out = os.environ.get("DRISHTI_SNAPSHOT_REPORT")
    if not out:
        return
    try:
        with open(out, "a", encoding="utf-8") as f:
            f.write(json.dumps(row) + "\n")
    except OSError:
        pass


def summary(path: str) -> list[str]:
    """The drill's lines: each changed page (and its diff image), from DRISHTI_SNAPSHOT_REPORT."""
    p = Path(path)
    if not p.is_file():
        return []
    rows = [json.loads(line) for line in p.read_text(encoding="utf-8").splitlines() if line.strip()]
    return [f"{r['page']} {r['width']} {r['theme']}: {r['share']:.2%} changed, {r['diff']}" for r in rows if r["changed"]]


if __name__ == "__main__":   # the drill's summary: python3 drishti-console/tests/snapshots.py <data>/drill/snapshots.jsonl
    import sys

    lines = summary(sys.argv[1])
    print(f"drill: visual snapshots: {len(lines)} changed" + (":" if lines else ""))
    for line in lines:
        print("  " + line)
