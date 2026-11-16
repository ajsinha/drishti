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

"""Moves the first-party packs into the modules that own them and rewrites every path that names them (the one-off of "packs live in
their modules"). Idempotent: a pack already moved is skipped, and a path already rewritten matches nothing, so it can be run again
over files that arrived later (a merged documentation pass).

    python3 tools/packs_to_modules.py            move what is still under config/packs, then rewrite the references
    python3 tools/packs_to_modules.py --rewrite  only rewrite the references (the files' own text; CHANGELOG.md and docs/releases are history)
"""
from __future__ import annotations

import os
import re
import subprocess
import sys

#: old "<domain>/<pack>" under config/packs -> the packs folder of the module that owns it
MAP = {
    **{f"banking/{p}": "modules/banking/dmod/packs" for p in ("trading", "market-risk", "counterparty-risk")},
    "banking/market-data": "modules/market-data/packs",
    **{f"capitalmarkets/{p}": "modules/capital-markets/packs" for p in ("us-equities", "funds")},
    **{f"economy/{p}": "modules/economy/packs" for p in (
        "world-economy", "news-and-markets", "world-development", "central-banks", "national-statistics", "oecd", "official-news", "gdelt-events")},
}
NEW = {old: f"{root}/{old.split('/')[1]}" for old, root in MAP.items()}
SKIP_NAMES = {"CHANGELOG.md"}
SKIP_PREFIX = ("docs/releases/",)
EXT = {".example", ".java", ".py", ".yaml", ".yml", ".md", ".sh", ".ps1", ".xml", ".json", ".html", ".js", ".txt", ".properties", ".svg",
       ".css", ".toml", ".cfg", ".jsonl", ".bat", ""}

PAT_SLASH = re.compile(r"config/packs/(" + "|".join(sorted((re.escape(k) for k in NEW), key=len, reverse=True)) + r")(?![\w-])")
PAT_PY = re.compile(r'"config"\s*/\s*"packs"\s*/\s*"(banking|capitalmarkets|economy)"\s*/\s*"([\w-]+)"')
PAT_TUPLE = re.compile(r'"config",\s*"packs",\s*"(banking|capitalmarkets|economy)",\s*"([\w-]+)"')


def move() -> None:
    for old, root in MAP.items():
        src, dst = f"config/packs/{old}", f"{root}/{old.split('/')[1]}"
        if os.path.isdir(src) and not os.path.exists(dst):
            os.makedirs(root, exist_ok=True)
            subprocess.check_call(["git", "mv", src, dst])
    for d in ("banking", "capitalmarkets", "economy"):
        try:
            os.rmdir(f"config/packs/{d}")
        except OSError:
            pass


def rewrite() -> int:
    files = subprocess.check_output(["git", "ls-files", "-z"]).decode().split("\0")
    changed = 0
    for f in files:
        if not f or f in SKIP_NAMES or f.startswith(SKIP_PREFIX) or os.path.splitext(f)[1].lower() not in EXT or not os.path.isfile(f):
            continue
        try:
            t = open(f, encoding="utf-8").read()
        except (OSError, UnicodeDecodeError):
            continue
        if "packs" not in t:
            continue
        n = PAT_SLASH.sub(lambda m: NEW[m.group(1)], t)
        n = PAT_PY.sub(lambda m: " / ".join(f'"{p}"' for p in NEW[f"{m.group(1)}/{m.group(2)}"].split("/")) if f"{m.group(1)}/{m.group(2)}" in NEW else m.group(0), n)
        n = PAT_TUPLE.sub(lambda m: ", ".join(f'"{p}"' for p in NEW[f"{m.group(1)}/{m.group(2)}"].split("/")) if f"{m.group(1)}/{m.group(2)}" in NEW else m.group(0), n)
        n = re.sub(r"config/packs/<domain>/(<[a-z]+>)", r"modules/<module>/packs/\1", n)
        if n != t:
            open(f, "w", encoding="utf-8").write(n)
            changed += 1
    return changed


if __name__ == "__main__":
    if "--rewrite" not in sys.argv:
        move()
    print("files rewritten:", rewrite())
