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

"""The launchers' direct-memory arithmetic (tools/lake/memory_caps.py; FLIGHT_GUIDE.md section 10): 256 MB, plus the in-memory lake's
stores, plus Arrow's budget (the exchange's 512 MB or DRISHTI_ARROW_EXCHANGE_MB, plus Flight's memory.total-mb only when its engine is
on); each launcher asks the one place and passes Arrow's share as -Ddrishti.arrow.max-bytes."""
import pathlib
import sys

import pytest

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(HERE))
import memory_caps  # noqa: E402


def flight_file(folder: pathlib.Path, enabled: str, total: str = "${DRISHTI_FLIGHT_MEMORY_MB:4096}") -> pathlib.Path:
    folder.mkdir(parents=True, exist_ok=True)
    f = folder / "flight.yaml"
    f.write_text(f"drishti:\n  engines:\n    flight:\n      enabled: {enabled}\n      memory:\n        total-mb: {total}\n", encoding="utf-8")
    return f


@pytest.fixture(autouse=True)
def clean_env(monkeypatch, tmp_path):
    for k in ("DRISHTI_FLIGHT_ENABLED", "DRISHTI_FLIGHT_MEMORY_MB", "DRISHTI_ARROW_EXCHANGE_MB", "DRISHTI_LAKE_MEMORY_MB", "DRISHTI_LAKES_DIR"):
        monkeypatch.delenv(k, raising=False)
    monkeypatch.setenv("DRISHTI_ENGINES_DIR", str(tmp_path / "engines"))
    monkeypatch.chdir(tmp_path)


def test_flight_off_adds_the_exchange_budget_only(tmp_path):
    flight_file(tmp_path / "engines", "${DRISHTI_FLIGHT_ENABLED:false}")
    assert memory_caps.flight_mb() == 0
    assert memory_caps.arrow_mb() == 512


def test_flight_on_adds_its_total_from_the_file_or_its_variable(tmp_path, monkeypatch):
    flight_file(tmp_path / "engines", "true")
    assert memory_caps.arrow_mb() == 512 + 4096
    monkeypatch.setenv("DRISHTI_FLIGHT_MEMORY_MB", "1024")
    assert memory_caps.arrow_mb() == 512 + 1024
    flight_file(tmp_path / "engines", "${DRISHTI_FLIGHT_ENABLED:false}")
    monkeypatch.setenv("DRISHTI_FLIGHT_ENABLED", "true")                 # the variable wins over the file's default
    assert memory_caps.flight_mb() == 1024


def test_the_exchange_budget_has_its_own_variable_and_no_file_means_the_exchange_only(tmp_path, monkeypatch):
    monkeypatch.setenv("DRISHTI_ARROW_EXCHANGE_MB", "256")
    assert memory_caps.arrow_mb() == 256
    monkeypatch.setenv("DRISHTI_ARROW_EXCHANGE_MB", "not a number")
    assert memory_caps.arrow_mb() == 512


def test_the_printed_figures_add_up(tmp_path, monkeypatch, capsys):
    flight_file(tmp_path / "engines", "true", "2048")
    monkeypatch.setenv("DRISHTI_LAKE_MEMORY_MB", "300")
    for flag, want in (([], 256 + 300 + 512 + 2048), (["--stores-only"], 300), (["--arrow-only"], 512 + 2048), (["--extra-only"], 300 + 512 + 2048)):
        memory_caps.main(flag)
        assert int(capsys.readouterr().out.strip()) == want, flag


def test_the_shipped_engine_file_is_off_and_names_its_variable():
    text = (ROOT / "config" / "engines" / "flight.yaml").read_text(encoding="utf-8")
    assert "${DRISHTI_FLIGHT_ENABLED:false}" in text and "${DRISHTI_FLIGHT_MEMORY_MB:4096}" in text
    assert memory_caps.flight_mb(ROOT / "config" / "engines" / "flight.yaml") == 0


def test_every_launcher_asks_the_one_place_and_passes_arrows_cap():
    for launcher in ("start-server.sh", "start-server.ps1"):                # drishti-stack starts its nodes through bin/start-server.sh
        text = (ROOT / "bin" / launcher).read_text(encoding="utf-8")
        assert "--extra-only" in text and "--arrow-only" in text and "drishti.arrow.max-bytes" in text, launcher
    assert "bin_run(\"start-server\"" in (ROOT / "drishti-stack" / "stack.py").read_text(encoding="utf-8")
    ecs = (ROOT / "deploy" / "docker" / "ecs" / "server-entrypoint.sh").read_text(encoding="utf-8")
    assert "DRISHTI_ARROW_MB" in ecs and "drishti.arrow.max-bytes" in ecs
    for f in (ROOT / "run-configs").glob("Drishti server*.run.xml"):
        text = f.read_text(encoding="utf-8")
        assert "--add-opens=java.base/java.nio=ALL-UNNAMED" in text and "drishti.arrow.max-bytes" in text, f.name
