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

"""flip.py <target file> <seconds> <pause>: atomically replaces <target> (tmp + rename, as JsonlLoader does) with
shuffled orderings of its own lines, so every entity's line moves to a different offset each time."""
import os, random, sys, time

target, secs, pause = sys.argv[1], float(sys.argv[2]), float(sys.argv[3])
lines = open(target, "rb").read().splitlines(keepends=True)
orig = b"".join(lines)
rnd = random.Random(7)
end = time.time() + secs
n = 0
try:
    while time.time() < end:
        rnd.shuffle(lines)
        tmp = target + ".tmp"
        with open(tmp, "wb") as f:
            f.write(b"".join(lines))
        os.replace(tmp, target)
        n += 1
        time.sleep(pause)
finally:
    tmp = target + ".tmp"
    with open(tmp, "wb") as f:
        f.write(orig)
    os.replace(tmp, target)
print("flips", n)
