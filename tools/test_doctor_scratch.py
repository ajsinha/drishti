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

"""A doctor run on a scratch data folder probes no server and no default port unless one is named."""
import sys
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
if str(TOOLS) not in sys.path:
    sys.path.insert(0, str(TOOLS))
import doctor  # noqa: E402


def _run(monkeypatch, tmp_path, scratch, env=None):
    probed = []
    monkeypatch.setattr(doctor, "probe", lambda port, timeout=1.5: probed.append(port) or ("free", ""))
    def no_jar():
        raise RuntimeError("no jar")
    def no_java(_=None):
        raise RuntimeError("no java")
    out = doctor.run(env or {}, tmp_path, no_jar, no_java, None, None, scratch)
    return out, probed


def test_scratch_run_probes_no_port(monkeypatch, tmp_path):
    out, probed = _run(monkeypatch, tmp_path, True)
    assert probed == []
    assert any(r["name"] == "ports" and r["level"] == "ok" for r in out)


def test_default_run_probes_the_default_ports(monkeypatch, tmp_path):
    _, probed = _run(monkeypatch, tmp_path, False)
    assert sorted(probed) == sorted(p for p, _ in doctor.PORTS)
