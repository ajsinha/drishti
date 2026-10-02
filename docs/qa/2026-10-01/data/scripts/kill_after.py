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

"""kill_after.py <seconds> <signal> -- <command...>: starts the command, kills it (by its own PID and its children's,
found through /proc, never by name) after <seconds> with <signal> (9 or 15), prints its output tail."""
import os, signal, subprocess, sys, time

secs, sig = float(sys.argv[1]), int(sys.argv[2])
cmd = sys.argv[sys.argv.index("--") + 1:]
p = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, start_new_session=True)
time.sleep(secs)


def children(pid):
    out = []
    for d in os.listdir("/proc"):
        if d.isdigit():
            try:
                ppid = int(open(f"/proc/{d}/stat").read().rsplit(")", 1)[1].split()[1])
            except Exception:  # noqa
                continue
            if ppid == pid:
                out.append(int(d))
                out += children(int(d))
    return out


if p.poll() is None:
    pids = [p.pid] + children(p.pid)
    for x in reversed(pids):
        try:
            os.kill(x, sig)
        except ProcessLookupError:
            pass
    print(f"killed pids {pids} with signal {sig} after {secs}s")
else:
    print(f"command finished before the kill (exit {p.returncode})")
out = p.communicate()[0]
print("\n".join(out.strip().splitlines()[-4:]))
