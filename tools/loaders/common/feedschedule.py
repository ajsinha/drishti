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

"""``drishti.py feed schedule --cron | --systemd | --windows``: ready-to-use DAILY entries, one per enabled feed at the time its feed
file's ``schedule`` gives (docs/guides/WORLD_DATA_GUIDE.md section "Run it every day"). Every entry runs the one command on every system:
the one Python Drishti runs with (DRISHTI_PYTHON, else drishti.python of the settings file, else the Python running drishti.py; its
full path) running tools/drishti.py feed run <feed>."""
from __future__ import annotations

from pathlib import Path

import feedbase as FB

ROOT = FB.ROOT


def _hm(spec: FB.FeedSpec) -> tuple[int, int]:
    h, m = spec.schedule_at.split(":")
    return int(h), int(m)


def python() -> str:
    """The one Python Drishti runs with (procs.tools_python): DRISHTI_PYTHON, drishti.python, else this one."""
    import sys
    launch = str(ROOT / "tools" / "launch")
    if launch not in sys.path:
        sys.path.insert(0, launch)
    import procs
    return procs.tools_python()[0]


def command(root: Path, feed: str, py: str | None = None) -> str:
    """The scheduled command of one feed on Linux and macOS: the one Python running tools/drishti.py feed run."""
    return f"{py or python()} {root}/tools/drishti.py feed run {feed} --trigger schedule"


def cron(specs: list[FB.FeedSpec], root: Path = ROOT, data: str | None = None, py: str | None = None) -> str:
    log = (data or "$HOME/drishti-feeds-logs").rstrip("/")
    lines = ["# Drishti data feeds: daily (crontab -e, then paste; the times are this machine's local time)",
             f"# mkdir -p {log}   (one log file per feed; each run appends its one log line and any messages)",
             "SHELL=/bin/bash"]
    if not any(s.enabled for s in specs):
        lines.append("# no feed is enabled (enabled: false in every config/feeds/*.yaml)")
    for s in specs:
        h, m = _hm(s)
        dow = "1-5" if s.schedule_days == "weekdays" else "*"
        prefix = "" if s.enabled else "# (disabled) "
        lines.append(f"{prefix}{m} {h} * * {dow} {command(root, s.name, py)} >> {log}/{s.name}.log 2>&1")
    return "\n".join(lines) + "\n"


def systemd(specs: list[FB.FeedSpec], root: Path = ROOT, user_unit: bool = True, py: str | None = None) -> str:
    """One service and one timer per feed, as files to save under ~/.config/systemd/user/ (or /etc/systemd/system/)."""
    out = ["# Drishti data feeds: systemd units. Save each block as the file it names under ~/.config/systemd/user/ (or",
           "# /etc/systemd/system/ with User= added), then: systemctl --user daemon-reload && systemctl --user enable --now drishti-feed-<feed>.timer",
           "# journalctl --user -u drishti-feed-<feed> shows each run's one log line; Persistent=true runs a missed day at the next boot.", ""]
    for s in specs:
        if not s.enabled:
            out.append(f"# {s.name}: disabled in config/feeds/{s.name}.yaml (enabled: false); no units")
            continue
        h, m = _hm(s)
        days = "Mon..Fri " if s.schedule_days == "weekdays" else ""
        out += [f"# ---- drishti-feed-{s.name}.service",
                "[Unit]", f"Description=Drishti data feed {s.name}: {s.title}", "Wants=network-online.target", "After=network-online.target", "",
                "[Service]", "Type=oneshot", f"WorkingDirectory={root}", f"ExecStart={command(root, s.name, py)}",
                "Nice=10", "", f"# ---- drishti-feed-{s.name}.timer",
                "[Unit]", f"Description=Run the Drishti data feed {s.name} daily", "",
                "[Timer]", f"OnCalendar={days}*-*-* {h:02d}:{m:02d}:00", "Persistent=true", "RandomizedDelaySec=120", "",
                "[Install]", "WantedBy=timers.target", ""]
    return "\n".join(out)


def windows(specs: list[FB.FeedSpec], root: Path = ROOT, py: str | None = None) -> str:
    """schtasks commands (cmd or PowerShell), one per feed: the task runs the one Python on tools\\drishti.py feed run <feed> daily."""
    win_root = str(root).replace("/", "\\")
    win_py = (py or python()).replace("/", "\\")
    out = ["REM Drishti data feeds: Windows Task Scheduler. Run these in a command prompt (or PowerShell) as the user the feeds run as;",
           "REM schtasks /Query /TN \"Drishti feed <feed>\" /V shows the last result (0 ok, 1 failed, 2 refused, 3 locked, 4 partial)."]
    for s in specs:
        h, m = _hm(s)
        sched = "/SC WEEKLY /D MON,TUE,WED,THU,FRI" if s.schedule_days == "weekdays" else "/SC DAILY"
        ps = (f"\\\"{win_py}\\\" \\\"{win_root}\\tools\\drishti.py\\\" feed run {s.name} --trigger schedule")
        line = f"schtasks /Create /F /TN \"Drishti feed {s.name}\" {sched} /ST {h:02d}:{m:02d} /TR \"{ps}\""
        out.append(("REM (disabled) " if not s.enabled else "") + line)
    return "\r\n".join(out) + "\r\n"
