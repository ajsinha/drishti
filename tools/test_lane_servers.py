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

"""Lane servers (tools/lane_servers.py; TEST_HARNESS.md, "Lane servers"): one server per lane for every process that asks, stopped by
the last one out, and by the watchdog when the processes attached to it were killed outright (no orphan servers). A ``sleep`` stands in
for the server."""
from __future__ import annotations

import os
import subprocess
import sys
import time
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import lane_servers  # noqa: E402

pytestmark = pytest.mark.skipif(sys.platform == "win32", reason="process groups and /proc: Linux and macOS")

HERE = Path(__file__).resolve().parent


def _start(work: Path) -> dict:
    p = subprocess.Popen(["sleep", "300"], start_new_session=True)
    (work / "started").write_text(str(p.pid))
    return {"pids": [p.pid]}


def _gone(pid: int, seconds: float = 20) -> bool:
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        with_reap(pid)
        if not lane_servers.alive(pid):
            return True
        time.sleep(0.2)   # wait-ok: waiting for the reaper
    return False


def with_reap(pid: int) -> None:
    try:
        os.waitpid(pid, os.WNOHANG)
    except (ChildProcessError, OSError):
        pass


def test_one_server_for_every_asker_and_the_last_one_out_stops_it(tmp_path, monkeypatch):
    monkeypatch.delenv(lane_servers.ENV_HOLDER, raising=False)
    a = lane_servers.attach(tmp_path, "k", _start)
    b = lane_servers.attach(tmp_path, "k", _start)                 # attached: no second start
    assert a["pids"] == b["pids"] and len(list(tmp_path.glob("k/work-*"))) == 1
    pid = a["pids"][0]
    lane_servers.release(tmp_path / "k")
    assert _gone(pid) and not (tmp_path / "k" / "state.json").exists()


def test_a_holder_keeps_it_until_stopped(tmp_path, monkeypatch):
    monkeypatch.setenv(lane_servers.ENV_HOLDER, str(os.getpid()))
    pid = lane_servers.attach(tmp_path, "k", _start)["pids"][0]
    lane_servers.release(tmp_path / "k")
    assert lane_servers.alive(pid)                                  # the holder (this process) lives: kept for the serial group
    assert lane_servers.stop_all(tmp_path) == 1 and _gone(pid)


def test_the_watchdog_stops_the_server_of_a_killed_lane(tmp_path):
    code = (f"import sys, time; sys.path.insert(0, {str(HERE)!r}); import lane_servers, subprocess; from pathlib import Path\n"
            "def start(work):\n"
            "    p = subprocess.Popen(['sleep', '300'], start_new_session=True)\n"
            "    return {'pids': [p.pid]}\n"
            f"s = lane_servers.attach(Path({str(tmp_path)!r}), 'k', start)\n"
            "print(s['pids'][0], flush=True)\n"
            "time.sleep(300)\n")
    env = {k: v for k, v in os.environ.items() if k != lane_servers.ENV_HOLDER}
    worker = subprocess.Popen([sys.executable, "-c", code], stdout=subprocess.PIPE, text=True, env=env)
    pid = int(worker.stdout.readline())
    assert lane_servers.alive(pid)
    worker.kill()                                                   # a worker killed outright: no clean-up of its own
    worker.wait()
    assert _gone(pid), "the watchdog left the server running"
    assert not (tmp_path / "k" / "state.json").exists()


def test_a_dead_server_is_replaced(tmp_path, monkeypatch):
    monkeypatch.setenv(lane_servers.ENV_HOLDER, str(os.getpid()))
    first = lane_servers.attach(tmp_path, "k", _start)["pids"][0]
    os.killpg(first, 9)
    assert _gone(first)
    second = lane_servers.attach(tmp_path, "k", _start)["pids"][0]
    assert second != first and lane_servers.alive(second)
    lane_servers.stop_all(tmp_path)
    assert _gone(second)
