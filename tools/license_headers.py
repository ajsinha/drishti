#!/usr/bin/env python3
"""Check or insert the Drishti copyright header in every source file.

Usage: python3 tools/license_headers.py [--fix]

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository.
"""
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
MARK = "Copyright (c) 2026 Ashutosh Sinha"
TEXT = (ROOT / "config" / "license-header.txt").read_text().strip().splitlines()
SKIP_DIRS = {"target", ".git", ".claude", ".venv", "vendor", "recorded", "__pycache__", ".mvn", "node_modules", ".pytest_cache", "requirements"}
SKIP_FILES = {"mvnw", "mvnw.cmd", "LICENSE"}


def block(style):
    if style == "java":
        return "/*\n" + "".join(f" * {l}".rstrip() + "\n" for l in TEXT) + " */\n"
    if style == "hash":
        return "".join(f"# {l}".rstrip() + "\n" for l in TEXT) + "\n"
    if style == "xml":
        return "<!--\n" + "".join(f"  {l}".rstrip() + "\n" for l in TEXT) + "-->\n"
    if style == "jinja":
        return "{#\n" + "".join(f"  {l}".rstrip() + "\n" for l in TEXT) + "#}\n"
    raise ValueError(style)


STYLES = {".java": "java", ".js": "java", ".css": "java", ".py": "hash", ".yaml": "hash", ".yml": "hash",
          ".properties": "hash", ".sh": "hash", ".ps1": "hash", ".xml": "xml", ".md": "xml", ".svg": None, ".html": "jinja",
          ".json": None, ".txt": None}


def ignored():
    """What git ignores (runtime data such as data/identity, data/packs): not source, so no header. With --directory git names
    a wholly ignored folder once instead of listing its files, which matters when data/ holds millions of them (lakes, drill
    copies); listing them all took longer than the timeout, the set came back empty and every ignored file was reported."""
    import subprocess
    try:
        out = subprocess.run(["git", "ls-files", "--others", "--ignored", "--exclude-standard", "--directory"], cwd=ROOT,
                             capture_output=True, text=True, timeout=60, check=True).stdout
    except (OSError, subprocess.SubprocessError) as e:
        sys.exit(f"license_headers: cannot ask git what it ignores ({e}); refusing to guess")
    return {ROOT / line.rstrip("/") for line in out.splitlines() if line}


def files():
    import os
    skip = ignored()
    for top, dirs, names in os.walk(ROOT):
        here = pathlib.Path(top)
        dirs[:] = sorted(d for d in dirs if d not in SKIP_DIRS and here / d not in skip)
        for name in sorted(names):
            p = here / name
            if name not in SKIP_FILES and p not in skip and STYLES.get(p.suffix):
                yield p


def fix(p):
    s = p.read_text(encoding="utf-8")
    style = STYLES[p.suffix]
    hdr = block(style)
    if style == "xml" and s.startswith("<?xml"):
        first, rest = s.split("\n", 1)
        p.write_text(first + "\n" + hdr + rest, encoding="utf-8")
    elif style == "hash" and s.startswith("#!"):
        first, rest = s.split("\n", 1)
        p.write_text(first + "\n" + hdr + rest, encoding="utf-8")
    elif p.suffix == ".html" and s.lstrip().startswith("<!doctype"):
        p.write_text(hdr + s, encoding="utf-8")
    else:
        p.write_text(hdr + s, encoding="utf-8")


def main():
    do_fix = "--fix" in sys.argv
    missing = [p for p in files() if MARK not in p.read_text(encoding="utf-8", errors="ignore")[:2000]]
    for p in missing:
        if do_fix:
            fix(p)
        print(("fixed " if do_fix else "missing ") + str(p.relative_to(ROOT)))
    sys.exit(1 if missing and not do_fix else 0)


if __name__ == "__main__":
    main()
