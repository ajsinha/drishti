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
"""The pinned Lake wheel (lib/lake-wheel.pin): one version, the exact wheel by SHA-256, never the newest file (gate 4 finding F11)."""
from __future__ import annotations

import hashlib
import importlib
import pathlib
import re
import sys
import types

import pytest

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
ROOT = HERE.parents[1]


def _load(monkeypatch, home: pathlib.Path):
    monkeypatch.setenv("DRISHTI_HOME", str(home))
    monkeypatch.delenv("LAKE_PYTHON", raising=False)
    sys.modules.pop("lakewheel", None)
    monkeypatch.setitem(sys.modules, "drishti_lake", types.ModuleType("drishti_lake"))   # importing lakewheel must not need the wheel: find() is under test
    return importlib.import_module("lakewheel")


def test_requirements_name_the_pinned_version(monkeypatch):
    pin = _load(monkeypatch, ROOT).pin(ROOT / "lib" / "lake-wheel.pin")
    req = (ROOT / "drishti-console" / "requirements.txt").read_text(encoding="utf-8")
    assert re.search(rf"^drishti-lake=={re.escape(pin['version'])}\s*$", req, re.M), "requirements.txt must pin the version of lib/lake-wheel.pin"
    assert "dev" not in pin["version"] and re.fullmatch(r"[0-9a-f]{64}", pin["sha256"]) and re.fullmatch(r"v[0-9.]+|[0-9a-f]{40}", pin["ref"])


def _home(tmp_path, version="0.9.0"):
    (tmp_path / "lib" / "wheels").mkdir(parents=True)
    wheel = tmp_path / "lib" / "wheels" / f"drishti_lake-{version}-py3-none-any.whl"
    wheel.write_bytes(b"wheel bytes")
    (tmp_path / "lib" / "lake-wheel.pin").write_text(f"ref=v{version}\nversion={version}\nsha256={hashlib.sha256(b'wheel bytes').hexdigest()}\n")
    return wheel


def test_the_pinned_wheel_is_found_by_version_and_digest(tmp_path, monkeypatch):
    wheel = _home(tmp_path)
    lw = _load(monkeypatch, tmp_path)
    assert lw.find() == str(wheel)


def test_a_newer_file_is_never_taken(tmp_path, monkeypatch):
    wheel = _home(tmp_path)
    (tmp_path / "lib" / "wheels" / "drishti_lake-0.9.1-py3-none-any.whl").write_bytes(b"newer")
    lw = _load(monkeypatch, tmp_path)
    assert lw.find() == str(wheel)
    wheel.unlink()
    assert lw.find() is None                       # only another version is there: none is used


def test_a_wheel_with_another_digest_is_refused_by_name(tmp_path, monkeypatch):
    wheel = _home(tmp_path)
    wheel.write_bytes(b"tampered")
    lw = _load(monkeypatch, tmp_path)
    with pytest.raises(ImportError, match="not the pinned wheel"):
        lw.find()
