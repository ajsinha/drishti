#   Project Drishti · Any data. Any domain. One grammar.
#
#   Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
#   All rights reserved.
#
#   PROPRIETARY AND CONFIDENTIAL.
#
#   This file is the confidential and proprietary property of Ashutosh Sinha.
#   Unauthorised copying, use, modification, distribution or disclosure of this
#   file, via any medium, is strictly prohibited except with the express prior
#   written permission of the copyright holder.
#
#   See the LICENSE file in the root of this repository for the full terms.

"""Builds the console's favicon and app icons from the logo mark (drishti-console/web/static/img/drishti-mark.svg).

The mark is the logo's own drawing: amber braces around an eye. Every icon puts it, scaled to fill, on the logo's navy,
with the eye's outline in white (as on the dark app icon) so it reads on light and dark browser tabs alike. Sizes of 32
pixels and under use heavier strokes, so the braces and the eye survive at tab size.

    python3 tools/make_icons.py            # writes drishti-console/web/static/img/icons/
    python3 tools/make_icons.py --check    # exit 1 when the committed icons are not what this script makes

Needs Inkscape (SVG to PNG) and ImageMagick (the .ico) on the PATH.
"""
from __future__ import annotations

import argparse
import hashlib
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "drishti-console" / "web" / "static" / "img" / "icons"

NAVY, AMBER, WHITE = "#16233F", "#E39B2D", "#FFFFFF"
# the mark's drawing, from drishti-mark.svg (viewBox 0 0 200 120; content spans x 19..181, y 18..102)
BRACES = ("M42,18 C29,18 31,30 31,40 L31,49 C31,56 27,60 19,60 C27,60 31,64 31,71 L31,80 C31,90 29,102 42,102",
          "M158,18 C171,18 169,30 169,40 L169,49 C169,56 173,60 181,60 C173,60 169,64 169,71 L169,80 C169,90 171,102 158,102")
EYE = "M50,60 Q100,12 150,60 Q100,108 50,60 Z"


def icon_svg(*, fill: float, radius: float, heavy: bool) -> str:
    """A square icon: navy tile (corner radius as a fraction of the side), the mark scaled to `fill` of the width."""
    side = 200.0
    scale = side * fill / 162.0                                  # 162 = the mark's content width
    tx = (side - 162.0 * scale) / 2 - 19.0 * scale
    ty = (side - 84.0 * scale) / 2 - 18.0 * scale                # 84 = the mark's content height
    brace_w, eye_w = (11, 10) if heavy else (8, 7)
    r = side * radius
    return f"""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 200 200" width="200" height="200">
  <title>Drishti</title>
  <rect width="200" height="200" rx="{r:.1f}" fill="{NAVY}"/>
  <g transform="translate({tx:.2f} {ty:.2f}) scale({scale:.4f})">
    <g fill="none" stroke-linecap="round" stroke-linejoin="round">
      <path d="{BRACES[0]}" stroke="{AMBER}" stroke-width="{brace_w}"/>
      <path d="{BRACES[1]}" stroke="{AMBER}" stroke-width="{brace_w}"/>
      <path d="{EYE}" stroke="{WHITE}" stroke-width="{eye_w}"/>
    </g>
    <circle cx="100" cy="60" r="{20 if heavy else 18}" fill="{AMBER}"/>
    <circle cx="100" cy="60" r="{9.5 if heavy else 8.5}" fill="{NAVY}"/>
    {'' if heavy else f'<circle cx="105" cy="55" r="3" fill="{WHITE}"/>'}
  </g>
</svg>
"""


# name -> (pixels, svg options); the svg files themselves are written too
PNGS = {
    "favicon-16.png": (16, dict(fill=0.90, radius=0.18, heavy=True)),
    "favicon-32.png": (32, dict(fill=0.92, radius=0.18, heavy=True)),
    "favicon-48.png": (48, dict(fill=0.92, radius=0.18, heavy=False)),
    "apple-touch-icon.png": (180, dict(fill=0.80, radius=0.0, heavy=False)),     # iOS rounds the corners itself
    "icon-192.png": (192, dict(fill=0.86, radius=0.18, heavy=False)),
    "icon-512.png": (512, dict(fill=0.86, radius=0.18, heavy=False)),
    "icon-maskable-512.png": (512, dict(fill=0.66, radius=0.0, heavy=False)),   # mark inside the 80 % safe zone
}
SVGS = {"favicon.svg": dict(fill=0.92, radius=0.18, heavy=False)}


def build(dest: Path) -> list[Path]:
    for tool in ("inkscape", "convert"):
        if not shutil.which(tool):
            sys.exit(f"make_icons: {tool} is not on the PATH")
    dest.mkdir(parents=True, exist_ok=True)
    made = []
    for name, opts in SVGS.items():
        (dest / name).write_text(icon_svg(**opts), encoding="utf-8")
        made.append(dest / name)
    with tempfile.TemporaryDirectory() as tmp:
        for name, (px, opts) in PNGS.items():
            src = Path(tmp) / (name + ".svg")
            src.write_text(icon_svg(**opts), encoding="utf-8")
            subprocess.run(["inkscape", str(src), "--export-type=png", f"--export-filename={dest / name}",
                            f"--export-width={px}", f"--export-height={px}"], check=True, capture_output=True)
            made.append(dest / name)
    ico = dest / "favicon.ico"
    subprocess.run(["convert", str(dest / "favicon-16.png"), str(dest / "favicon-32.png"), str(dest / "favicon-48.png"), str(ico)],
                   check=True, capture_output=True)
    made.append(ico)
    return made


def digest(p: Path) -> str:
    return hashlib.sha256(p.read_bytes()).hexdigest()


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--check", action="store_true", help="compare the SVG icons with the committed ones (PNG output varies by renderer version)")
    args = ap.parse_args(argv)
    if args.check:
        stale = [n for n, o in SVGS.items() if not (OUT / n).is_file() or (OUT / n).read_text(encoding="utf-8") != icon_svg(**o)]
        missing = [n for n in [*PNGS, "favicon.ico"] if not (OUT / n).is_file()]
        for n in stale + missing:
            print(f"make_icons: {n} is stale or missing; run python3 tools/make_icons.py")
        return 1 if stale or missing else 0
    for p in build(OUT):
        print(f"{p.relative_to(ROOT)}  {p.stat().st_size:,} bytes")
    return 0


if __name__ == "__main__":
    sys.exit(main())
