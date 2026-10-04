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

"""Groups the stacks that -Djdk.tracePinnedThreads=full printed in a server log: one line per distinct pinned stack
(its reason, then the frame holding the monitor and the first frames outside the JDK's own park machinery), and how often it was seen.

  tools/bench/pinned_summary.py server.log [more.log ...]
"""
from __future__ import annotations

import collections
import re
import sys


def blocks(text: str):
    cur = None
    for line in text.splitlines():
        if re.match(r"^VirtualThread\[#\d+\]", line):
            if cur:
                yield cur
            cur = [line]
        elif cur is not None:
            if line.startswith("    "):
                cur.append(line.strip())
            else:
                yield cur
                cur = None
    if cur:
        yield cur


NOISE = re.compile(r"^java\.base/(java\.lang\.VirtualThread|jdk\.internal|java\.util\.concurrent\.locks|java\.lang\.System\$2|java\.util\.concurrent\.ForkJoinPool\.(un)?managedBlock|java\.lang\.Continuation)")


def classify(frames: list[str]) -> tuple[str, str]:
    reason = re.search(r"reason:(\w+)", frames[0])
    kind = reason.group(1).lower() if reason else "pinned"
    useful = [f for f in frames[1:] if not NOISE.match(f)]
    marked = [f for f in useful if "<== monitors" in f]
    chosen = (marked[:1] + [f for f in useful if f not in marked])[:4]
    return kind, " <- ".join(x.split("<==")[0].strip().split("(")[0] for x in chosen)


def main(argv) -> int:
    seen: collections.Counter = collections.Counter()
    for path in argv[1:]:
        with open(path, errors="replace") as fh:
            for b in blocks(fh.read()):
                seen[classify(b)] += 1
    if not seen:
        print("no pinned stacks")
    for (kind, where), n in seen.most_common():
        print(f"{n:6d}  {kind:8s}  {where}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
