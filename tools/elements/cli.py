#!/usr/bin/env python3
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
"""``drishti.py elements``: Drishti Elements for other applications (docs/guides/ELEMENTS_IN_YOUR_APP.md).

    drishti.py elements pack --libs core,grid,chart [--theme dark] [--out DIR] [--include-domain banking] [--no-engines] [--maps]
    drishti.py elements dist [--out DIR] [--include-domain banking] [--only zip|npm]
    drishti.py elements webjar-tree --out DIR [--version V]      (the Maven module drishti-elements runs it: mvn -P elements)

Nothing is published anywhere: the outputs are files for an internal registry, a release archive or an application's own folder.
Also runs on its own: python3 tools/elements/cli.py pack --libs grid."""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import dist  # noqa: E402
import pack as packer  # noqa: E402

DEFAULT_OUT = dist.ROOT / "target" / "elements"
EXAMPLES = ("examples:\n  drishti.py elements pack --libs core,grid,chart --theme dark --out build/app-vendor\n"
            "  drishti.py elements pack --libs risk --include-domain banking\n  drishti.py elements dist --out target/elements\n"
            "Exit codes: 0 written, 1 refused (an unknown library, a licensed one without its domain), 2 usage.")


def domains(a) -> set[str]:
    return {d.strip() for x in (a.include_domain or []) for d in x.split(",") if d.strip()}


def human(n: int) -> str:
    return f"{n / 1048576:.1f} MB" if n >= 1048576 else f"{n / 1024:.0f} KB"


def cmd_pack(a, extra=None) -> int:
    try:
        rec = packer.pack([x for x in a.libs.split(",") if x.strip()], Path(a.out), theme=a.theme, include_domains=domains(a),
                          engines=not a.no_engines, maps=a.maps)
    except dist.DistError as e:
        print(f"elements pack: {e}", file=sys.stderr)
        return 1
    print("packed: " + ", ".join(f"{n} {v}" for n, v in rec["libraries"].items()) + f"; theme {rec['theme']}"
          + (f"; engines {', '.join(rec['engines'])}" if rec["engines"] else ""))
    for name in (packer.JS_NAME, packer.CSS_NAME):
        f = rec["files"][name]
        print(f"  {Path(a.out) / name}  {human(f['bytes'])}  {f['integrity']}")
    others = [p for p in rec["files"] if p not in (packer.JS_NAME, packer.CSS_NAME)]
    if others:
        print(f"  and {len(others)} file(s) the libraries read at run time under {Path(a.out) / 'vendor'}")
    return 0


def cmd_dist(a, extra=None) -> int:
    try:
        made = dist.build_all(Path(a.out), domains(a), forms=(a.only,) if a.only else ("zip", "npm"))
    except dist.DistError as e:
        print(f"elements dist: {e}", file=sys.stderr)
        return 1
    for p, size in made:
        print(f"{p}  {human(size)}")
    print("not published: see the guide's Install section for an internal Artifactory or Nexus registry; the WebJar: mvn -P elements")
    return 0


def cmd_webjar_tree(a, extra=None) -> int:
    try:
        files = dist.webjar_tree(dist.Train(), a.version)
    except dist.DistError as e:
        print(f"elements webjar-tree: {e}", file=sys.stderr)
        return 1
    dist.write_tree(files, Path(a.out))
    print(f"webjar tree: {len(files)} files under {Path(a.out) / dist.WEBJAR}")
    return 0


def add_commands(sub, add) -> None:
    p = add(sub, "pack", cmd_pack, "elements pack: one JavaScript file and one style sheet with exactly the named libraries and what they "
            "require (drishti-risk only with --include-domain banking)", epilog=EXAMPLES)
    p.add_argument("--libs", required=True, help="comma list: core, inputs, grid, chart, data, renderer, risk (or drishti-grid ...), or all")
    p.add_argument("--theme", help="one theme on :root: dark, light or a theme's name (terminal, blue, wallstreet ...); default every theme")
    p.add_argument("--out", default=str(DEFAULT_OUT / "pack"), help="the folder to write (default target/elements/pack)")
    p.add_argument("--include-domain", action="append", metavar="MODULE", help="include a domain module's libraries (banking: drishti-risk)")
    p.add_argument("--no-engines", action="store_true", help="leave the vendored engines (ECharts) out: the page loads its own first")
    p.add_argument("--maps", action="store_true", help="copy drishti-chart's map shapes (vendor/echarts/maps, about 7.7 MB) beside the files")
    d = add(sub, "dist", cmd_dist, "elements dist: the release archive drishti-elements-<version>.zip (SRI hashes, themes, changelog) and "
            "the npm tarballs @drishti/elements-<name> for an internal registry (nothing is published)", epilog=EXAMPLES)
    d.add_argument("--out", default=str(DEFAULT_OUT), help="the folder to write (default target/elements)")
    d.add_argument("--include-domain", action="append", metavar="MODULE", help="also the domain module's libraries (banking: drishti-risk)")
    d.add_argument("--only", choices=("zip", "npm"), help="one form only")
    w = add(sub, "webjar-tree", cmd_webjar_tree, "elements webjar-tree: the WebJar's files under META-INF/resources/webjars/drishti-elements/<version>/ "
            "(the Maven module drishti-elements runs it in mvn -P elements)")
    w.add_argument("--out", required=True, help="the classes folder to write into")
    w.add_argument("--version", help="the Maven module's version: refused unless it is the Elements train's")


def register(groups, add) -> None:
    """``drishti.py elements …``: ``groups`` the command line's groups, ``add`` its command maker."""
    g = groups.add_parser("elements", help="Drishti Elements for other applications: pack, dist, webjar-tree",
                          description=__doc__.split("\n\n")[0], formatter_class=argparse.RawDescriptionHelpFormatter)
    add_commands(g.add_subparsers(dest="cmd", metavar="COMMAND", required=True), add)


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(prog="elements", description=__doc__.split("\n\n")[0])

    def add(sub, name, func, help_, epilog=None):
        p = sub.add_parser(name, help=help_, description=help_, epilog=epilog, formatter_class=argparse.RawDescriptionHelpFormatter)
        p.set_defaults(func=func)
        return p

    add_commands(ap.add_subparsers(dest="cmd", metavar="COMMAND", required=True), add)
    a = ap.parse_args(argv)
    return a.func(a)


if __name__ == "__main__":
    sys.exit(main())
