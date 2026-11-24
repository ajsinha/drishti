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

"""Finds a procedure or text written in two documents (i7k, plan section 3.2). REPORT mode: prints the findings and the baseline counts and
exits 0; `--fail` exits 1 when there is any finding (switched on at the end of the consolidation).

1. command runs: three normalised command lines in a row in a fenced block, found in two hand-written documents (tutorials exempt, pairs in
   config/docs-owners.yaml `allow_runs`)
2. owned commands: a fenced line that matches a pattern of config/docs-owners.yaml outside its home (tutorials exempt)
3. settings tables: a table row whose first cell is a `drishti.*` key or a `DRISHTI_*` variable, outside CONFIGURATION.md and connector READMEs
4. repeated text: a window of 40 normalised words of prose (not code) found in two hand-written documents
5. folder notes: over 60 lines

The hand-written corpus is read from config/docs-corpus.yaml (classes keep, rename, move, connector, note; a document that is merged or
deleted goes away and is not counted).
"""
from __future__ import annotations

import re
import subprocess
import sys
from collections import defaultdict
from itertools import combinations
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
CLASSES = {"keep", "rename", "move", "connector", "note"}
WINDOW = 40
CMD = re.compile(r"^\s*(\$ |> |PS> )?(py\s+-3\.\d+|python3?|mvn|mvnw|\./mvnw|pip3?|git\s+clone|systemctl|sudo)\b")
DRISHTI = re.compile(r"(py\s+-3\.\d+|python3?(\.\d+)?)\s+(\./)?tools[/\\]drishti\.py")
SETTING = re.compile(r"^\|\s*`(drishti\.[a-z0-9_.\-\[\]]+|DRISHTI_[A-Z0-9_]+)`")


def corpus(root: Path = ROOT) -> list[str]:
    rows = (yaml.safe_load((root / "config" / "docs-corpus.yaml").read_text(encoding="utf-8")) or {}).get("files") or []
    return [r["path"] for r in rows if r["class"] in CLASSES]


def split(text: str) -> tuple[list[list[str]], list[str]]:
    """(fenced blocks as line lists, prose lines)."""
    blocks, prose, cur, fence = [], [], [], False
    for line in text.splitlines():
        if line.lstrip().startswith("```"):
            if fence:
                blocks.append(cur)
                cur = []
            fence = not fence
        elif fence:
            cur.append(line)
        else:
            prose.append(line)
    return blocks, prose


def norm_cmd(line: str) -> str:
    s = DRISHTI.sub("drishti", line.strip().lstrip("$>").strip().replace("\\", "/"))
    s = re.sub(r"\s+#.*$", "", s)
    s = re.sub(r"\d+(\.\d+)+", "N", s)
    s = re.sub(r"[\"']?[~/\w.\-]*/[\w./\-]+[\"']?", "PATH", s)
    return re.sub(r"\s+", " ", s)


def runs(blocks: list[list[str]]) -> set[tuple[str, ...]]:
    out: set[tuple[str, ...]] = set()
    for b in blocks:
        cmds = [norm_cmd(x) for x in b if CMD.match(x)]
        for i in range(len(cmds) - 2):
            out.add(tuple(cmds[i:i + 3]))
    return out


def words(prose: list[str]) -> list[str]:
    keep = [x for x in prose if not x.lstrip().startswith(("<!--", "|---", "|:--", "#"))]
    return re.findall(r"[a-z0-9]+", " ".join(keep).lower())


def analyse(texts: dict[str, str], owners: dict | None = None) -> dict:
    owners = owners or {}
    tut = lambda p: p.startswith("docs/tutorials/")
    run_in: dict[tuple, set[str]] = defaultdict(set)
    win_in: dict[int, set[str]] = defaultdict(set)
    findings: dict[str, list[str]] = {"runs": [], "owned": [], "settings": [], "text": [], "notes": []}
    allow = [tuple(sorted(a[:2])) for a in owners.get("allow_runs") or []]
    pats = [(re.compile(o["pattern"]), o["home"]) for o in owners.get("owners") or []]
    for path, text in texts.items():
        blocks, prose = split(text)
        for r in runs(blocks):
            run_in[r].add(path)
        w = words(prose)
        for i in range(0, max(0, len(w) - WINDOW + 1)):
            win_in[hash(tuple(w[i:i + WINDOW]))].add(path)
        if not tut(path):
            for b in blocks:
                for line in b:
                    for rx, home in pats:
                        if rx.search(line) and path != home:
                            findings["owned"].append(f"{path}: {line.strip()[:90]}  (home: {home})")
        if "CONFIGURATION" not in path and "/connectors/" not in path and not path.startswith("plugins/"):
            for line in prose:
                if SETTING.match(line):
                    findings["settings"].append(f"{path}: {line[:90]}")
        if len(text.splitlines()) > 60 and path in owners.get("_notes", ()):
            findings["notes"].append(f"{path}: {len(text.splitlines())} lines")
    for r, docs in run_in.items():
        d = sorted(x for x in docs if not tut(x))
        for a, b in combinations(d, 2):
            if (a, b) not in allow:
                findings["runs"].append(f"{a} and {b}: {' ; '.join(r)[:120]}")
    pair: dict[tuple[str, str], int] = defaultdict(int)
    for docs in win_in.values():
        if len(docs) > 1:
            for a, b in combinations(sorted(docs), 2):
                pair[(a, b)] += 1
    findings["text"] = [f"{a} and {b}: {n} repeated 40-word windows" for (a, b), n in sorted(pair.items(), key=lambda kv: -kv[1])]
    return findings


def main(argv: list[str]) -> int:
    paths = corpus()
    texts = {p: (ROOT / p).read_text(encoding="utf-8") for p in paths if (ROOT / p).exists()}
    owners = yaml.safe_load((ROOT / "config" / "docs-owners.yaml").read_text(encoding="utf-8")) or {}
    notes = [r["path"] for r in yaml.safe_load((ROOT / "config" / "docs-corpus.yaml").read_text(encoding="utf-8"))["files"] if r["class"] == "note"]
    owners["_notes"] = set(notes)
    f = analyse(texts, owners)
    titles = {"runs": "1 command runs in two documents", "owned": "2 owned commands outside their home",
              "settings": "3 settings tables outside CONFIGURATION.md", "text": "4 repeated text (pairs, most first)",
              "notes": "5 folder notes over 60 lines"}
    top = int(argv[argv.index("--top") + 1]) if "--top" in argv else 10
    for k, t in titles.items():
        print(f"== {t}: {len(f[k])}")
        for line in f[k][:top]:
            print("  ", line)
    total = sum(len(v) for v in f.values())
    print(f"baseline: {len(texts)} hand-written documents, {total} findings ({', '.join(f'{k} {len(v)}' for k, v in f.items())})")
    return 1 if "--fail" in argv and total else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
