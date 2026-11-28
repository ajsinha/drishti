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

"""A fresh build folder: the generator's first pass and its --check pass agree (a Sutra's sample-row comes from the samples of the build)."""
from __future__ import annotations

import os
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
MAKE = ROOT / "tools" / "packgen" / "world" / "make.py"


def test_fresh_build_folder_passes_its_own_check(tmp_path):
    env = dict(os.environ, DRISHTI_PACKS_OUT=str(tmp_path / "packs"), DRISHTI_ABOUT_DIR=str(tmp_path / "about"))
    assert subprocess.run([sys.executable, str(MAKE)], cwd=ROOT, env=env, capture_output=True, text=True).returncode == 0
    r = subprocess.run([sys.executable, str(MAKE), "--check"], cwd=ROOT, env=env, capture_output=True, text=True)
    assert r.returncode == 0, r.stdout + r.stderr
    sutra = (tmp_path / "packs" / "economy" / "world-economy" / "sutras" / "politics-and-society" / "party.v1.sutra.yaml").read_text(encoding="utf-8")
    assert "sample-row:" in sutra
