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
"""The README's "By the numbers" section, generated from the files git tracks (docs/guides/TOOLS.md): lines and files by kind, the
modules, packs, tutorials and commits. Written between the README's markers, rounded and dated, refreshed with each release:

    python3 tools/docs/code_volume.py            # rewrite the section
    python3 tools/docs/code_volume.py --check    # fail when a figure is more than 10% off the code as it is (the drill's gate)

Sample data (data/) and vendored libraries (static/vendor) are not counted.
"""
from __future__ import annotations

import datetime
import os
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
if str(ROOT / "drishti-console") not in sys.path:
    sys.path.insert(0, str(ROOT / "drishti-console"))
from core.pack_paths import pack_dirs  # noqa: E402  (the one rule for where a pack's folder is)
README = ROOT / "README.md"
BEGIN, END = "<!-- code-volume:begin -->", "<!-- code-volume:end -->"
TOLERANCE = 0.10
ROWS = [("java", "Java, product"), ("java-test", "Java, tests"), ("py", "Python, product (console, tools, loaders)"),
        ("py-test", "Python, tests"), ("web", "JavaScript, HTML and CSS (console)"), ("yaml", "YAML: configuration, Sutras, data contracts"),
        ("md", "Markdown: guides, tutorials, design")]


def kind(f: str) -> str | None:
    if f.startswith("data/") or "/vendor/" in f:
        return None
    e = os.path.splitext(f)[1].lower()
    if e == ".java":
        return "java-test" if "/src/test/" in f else "java"
    if e == ".py":
        return "py-test" if ("/tests/" in f or os.path.basename(f).startswith("test_")) else "py"
    if e in (".js", ".css", ".html"):
        return "web"
    if e in (".yaml", ".yml"):
        return "yaml"
    if e == ".md":
        return "md"
    return None


def measure() -> dict:
    files = subprocess.run(["git", "ls-files"], capture_output=True, text=True, cwd=ROOT, check=True).stdout.split("\n")
    lines: dict[str, int] = {k: 0 for k, _ in ROWS}
    count: dict[str, int] = {k: 0 for k, _ in ROWS}
    for f in files:
        k = kind(f) if f else None
        if not k:
            continue
        try:
            with open(ROOT / f, "rb") as h:
                lines[k] += sum(1 for _ in h)
            count[k] += 1
        except OSError:
            continue
    pom = (ROOT / "pom.xml").read_text(encoding="utf-8")
    return {"lines": lines, "files": count, "modules": len(re.findall(r"<module>", pom)),
            "packs": len(pack_dirs()),
            "tutorials": len(list((ROOT / "docs" / "tutorials").glob("[0-9][0-9]-*.md")))}


def k(n: int) -> str:
    return f"{round(n / 1000):,}k" if n >= 1000 else str(n)


def render(m: dict) -> str:
    L = m["lines"]
    product = L["java"] + L["py"] + L["web"]
    tests = L["java-test"] + L["py-test"]
    out = [BEGIN, "", f"*Measured {datetime.date.today().isoformat()} from the files the repository tracks (`tools/docs/code_volume.py`; sample "
           "data and vendored libraries not counted).*", "",
           "| Kind | Lines | Files |", "|---|---|---|"]
    out += [f"| {label} | {k(L[key])} | {m['files'][key]:,} |" for key, label in ROWS]
    out += ["", f"About **{k(product)} lines of product code** and **{k(tests)} lines of tests** ({round(100 * tests / product)}% of the product "
            f"code), with **{k(L['md'])} lines of documentation**; {m['modules']} Maven modules, {m['packs']} domain packs and "
            f"{m['tutorials']} tutorials.", "", END]
    return "\n".join(out)


def figures(text: str) -> list[int]:
    block = text[text.index(BEGIN):text.index(END)]
    return [int(x.replace(",", "")) * 1000 for x in re.findall(r"\| ([\d,]+)k \|", block)]


def main(argv: list[str]) -> int:
    text = README.read_text(encoding="utf-8")
    if BEGIN not in text or END not in text:
        print("README.md has no code-volume markers", file=sys.stderr)
        return 1
    m = measure()
    if "--check" in argv:
        now = [m["lines"][key] for key, _ in ROWS]
        shown = figures(text)
        off = [(label, s, n) for (_, label), s, n in zip(ROWS, shown, now) if n and abs(s - n) / n > TOLERANCE]
        if len(shown) != len(ROWS) or off:
            for label, s, n in off:
                print(f"README code volume: {label} shows {s:,}, the code has {n:,} (over {int(TOLERANCE * 100)}% off)", file=sys.stderr)
            print("run: python3 tools/docs/code_volume.py", file=sys.stderr)
            return 1
        print("README code volume is current (within 10%)")
        return 0
    a, b = text.index(BEGIN), text.index(END) + len(END)
    README.write_text(text[:a] + render(m) + text[b:], encoding="utf-8")
    print("wrote README.md's code volume")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
