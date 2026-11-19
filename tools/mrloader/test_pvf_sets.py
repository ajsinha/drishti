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

"""tools/mrloader: the daily scenario set and scenario FX scripts (add and remove): the dates and their order, the contract checks, every
refusal, the inbox order, remove calling `lake delete`, and the committed samples."""
from __future__ import annotations

import sys
import tarfile
from pathlib import Path

import pytest

pytest.importorskip("yaml")

TOOLS = Path(__file__).resolve().parents[1]
if str(TOOLS) not in sys.path:
    sys.path.insert(0, str(TOOLS))

from mrloader import common, pvf_fx_scenario as F, pvf_scenario_set as S  # noqa: E402
from mrloader.common import PvfError, read_any  # noqa: E402
from mrloader.contract import DateCheck, fx_scenario, scenario_set  # noqa: E402

SAMPLES = Path(__file__).resolve().parent / "samples"
D = "2026-11-18"
DAYS = ["2026-11-10", "2026-11-11", "2026-11-12", "2026-11-13", "2026-11-16", "2026-11-17"]


def _dates(tmp_path, days, name="dates.txt"):
    p = tmp_path / name
    p.write_text("\n".join(days) + "\n")
    return p


def _set(tmp_path, days=DAYS, *extra):
    f = _dates(tmp_path, days)
    assert S.main(["--date", D, "--dates", str(f), "--out", str(tmp_path), *extra]) == 0
    return tmp_path / f"pvf-{D}"


def _fx_long(days=DAYS, ccys=("EUR", "JPY")):
    rows = ["CURRENCY,SCENARIO_DATE,RATE"]
    for c in ccys:
        rows += [f"{c},{d},{1.0 + i / 100}" for i, d in enumerate([*days, D])]
    return "\n".join(rows) + "\n"


# ---- the scenario set ----------------------------------------------------------------------------------------------------------------
def test_add_writes_set_tar_ready(tmp_path):
    folder = _set(tmp_path)
    rec = next(r for _, r in read_any(folder / f"scenario-set-{D}.tar"))
    assert rec["SCENARIO_DATES"] == DAYS and rec["SCENARIO_COUNT"] == 6 and rec["SET_TYPE"] == "HISTORICAL"
    assert (rec["WINDOW_START"], rec["WINDOW_END"], rec["SCENARIO_SET_ID"]) == (DAYS[0], DAYS[-1], "HS-PVF-251")
    assert rec["SOURCE_BATCH"] == f"B-{D}-PVF" and not common.lower_keys(rec)
    assert scenario_set().check(rec) == []
    ready = (folder / f"scenario-set-{D}.tar.ready").read_text()
    assert 'folders: {"scenario-set": "market-risk"}' in ready and "mode: \"replace\"" in ready


def test_newest_first_file_is_reordered(tmp_path):
    f = _dates(tmp_path, DAYS[::-1])
    assert S.main(["--date", D, "--dates", str(f), "--order", "newest-first", "--out", str(tmp_path)]) == 0
    rec = next(r for _, r in read_any(tmp_path / f"pvf-{D}" / f"scenario-set-{D}.tar"))
    assert rec["SCENARIO_DATES"] == DAYS


@pytest.mark.parametrize("days,why", [
    (DAYS[::-1], "not oldest-first"), (DAYS + [DAYS[0]], "more than once"), ([*DAYS, D], "before the business date"),
    ([*DAYS, "2026-11-19"], "before the business date")])
def test_set_refusals(tmp_path, days, why, caplog):
    f = _dates(tmp_path, days)
    assert S.main(["--date", D, "--dates", str(f), "--out", str(tmp_path)]) == 1
    assert why in caplog.text and not (tmp_path / f"pvf-{D}").exists()


def test_set_needs_exactly_one_way_to_name_days(tmp_path):
    assert S.main(["--date", D, "--out", str(tmp_path)]) == 1
    f = _dates(tmp_path, DAYS)
    assert S.main(["--date", D, "--dates", str(f), "--weekdays", "5", "--out", str(tmp_path)]) == 1


def test_weekdays_and_dry_run(tmp_path):
    assert S.main(["--date", D, "--weekdays", "5", "--dry-run", "--out", str(tmp_path)]) == 0
    assert not (tmp_path / f"pvf-{D}").exists()
    assert S.main(["--date", D, "--weekdays", "5", "--out", str(tmp_path)]) == 0
    rec = next(r for _, r in read_any(tmp_path / f"pvf-{D}" / f"scenario-set-{D}.tar"))
    assert rec["SCENARIO_DATES"] == ["2026-11-11", "2026-11-12", "2026-11-13", "2026-11-16", "2026-11-17"]


def test_stress_set_is_appended_and_may_use_old_days(tmp_path):
    f = _dates(tmp_path, ["2008-07-01", "2008-07-02", "2008-07-03"], "s.txt")
    assert S.main(["--date", D, "--dates", str(f), "--set-type", "stress", "--out", str(tmp_path)]) == 0
    folder = tmp_path / f"pvf-{D}"
    rec = next(r for _, r in read_any(folder / f"scenario-set-{D}-stress.tar"))
    assert rec["SET_TYPE"] == "STRESS" and rec["SCENARIO_SET_ID"] == "HS-STRESS-3"
    assert 'mode: "append"' in (folder / f"scenario-set-{D}-stress.tar.ready").read_text()
    assert S.main(["--date", D, "--dates", str(f), "--set-type", "stress", "--scenario-set-id", "HS-X", "--out", str(tmp_path)]) == 1


def test_remove_calls_lake_delete(monkeypatch):
    calls = []
    monkeypatch.setattr(common.subprocess, "run", lambda cmd, check: calls.append(cmd) or type("R", (), {"returncode": 0})())
    assert S.main(["--date", D, "--remove", "--lake", "risklake", "--yes", "--no-signal", "--server", "http://h:1", "--token-file", "t"]) == 0
    cmd = calls[0]
    assert cmd[1].endswith("tools/drishti.py") and cmd[2:] == ["lake", "delete", "--lake", "risklake", "--kind", "scenario-set", "--date", D,
                                                               "--server", "http://h:1", "--token-file", "t", "--yes", "--no-signal"]
    assert S.main(["--date", D, "--remove"]) == 1                                # a lake is required


def test_remove_failure_is_a_refusal(monkeypatch):
    monkeypatch.setattr(common.subprocess, "run", lambda cmd, check: type("R", (), {"returncode": 3})())
    assert F.main(["--date", D, "--remove", "--lake", "x", "--dry-run"]) == 1


def test_remove_fx_kind(monkeypatch):
    calls = []
    monkeypatch.setattr(common.subprocess, "run", lambda cmd, check: calls.append(cmd) or type("R", (), {"returncode": 0})())
    assert F.main(["--date", D, "--remove", "--lake", "risklake", "--dry-run"]) == 0
    assert "fx-scenario" in calls[0] and "--dry-run" in calls[0]


# ---- the scenario FX -----------------------------------------------------------------------------------------------------------------
def _fx(tmp_path, text, *extra, days=DAYS):
    folder = _set(tmp_path, days)
    f = tmp_path / "fx.csv"
    f.write_text(text)
    return folder, F.main(["--date", D, "--fx", str(f), "--out", str(tmp_path), *extra])


def test_fx_long_joins_to_the_set_in_its_order(tmp_path):
    shuffled = _fx_long().splitlines()
    text = "\n".join([shuffled[0], *reversed(shuffled[1:])]) + "\n"          # row order of the file does not matter
    folder, rc = _fx(tmp_path, text)
    assert rc == 0
    recs = {r["CURRENCY"]: r for _, r in read_any(folder / f"fx-scenario-{D}.tar")}
    eur = recs["EUR"]
    assert eur["FX_SCENARIO_ID"] == "FXS-EUR-HS-PVF-251" and eur["QUOTE"] == "USD per 1 EUR" and eur["BATCH_ID"] == f"B-{D}-PVF"
    assert eur["RATE"] == 1.06 and eur["RATES"] == [1.0, 1.01, 1.02, 1.03, 1.04, 1.05]       # by SCENARIO_DATES, oldest first
    assert fx_scenario().check(eur) == [] and not common.lower_keys(eur)
    assert 'folders: {"fx-scenario": "market-risk"}' in (folder / f"fx-scenario-{D}.tar.ready").read_text()


def test_fx_wide_equals_long(tmp_path):
    long_t = _fx_long(ccys=("EUR",))
    wide = f"CURRENCY,RATE_TODAY,{','.join(DAYS)}\nEUR,1.06,{','.join(str(1.0 + i / 100) for i in range(6))}\n"
    assert F.parse_fx_text(long_t, D) == F.parse_fx_text(wide, D)


@pytest.mark.parametrize("edit,why", [
    (lambda t: t.replace("EUR,2026-11-12,1.02\n", ""), "scenario day(s) missing"),
    (lambda t: t + "EUR,2026-11-09,1.5\n", "not in the scenario set"),
    (lambda t: t + "EUR,2026-11-12,1.5\n", "more than once"),
    (lambda t: t.replace("EUR,2026-11-12,1.02", "EUR,2026-11-12,0"), "positive"),
    (lambda t: t.replace("EUR,2026-11-12,1.02", "EUR,2026-11-12,-1"), "positive"),
    (lambda t: t.replace("EUR,2026-11-12,1.02", "EUR,2026-11-12,abc"), "not a number"),
    (lambda t: t.replace("EUR,2026-11-18,1.06\n", ""), "no rate for today"),
    (lambda t: t + "USD,2026-11-18,1\n", "USD is never sent"),
    (lambda t: t.replace("EUR,", "EU,", 1), "not a currency code")])
def test_fx_refusals_write_nothing(tmp_path, edit, why, caplog):
    folder, rc = _fx(tmp_path, edit(_fx_long()))
    assert rc == 1 and why in caplog.text
    assert not (folder / f"fx-scenario-{D}.tar").exists()


def test_fx_without_a_set_is_refused(tmp_path, caplog):
    f = tmp_path / "fx.csv"
    f.write_text(_fx_long())
    assert F.main(["--date", D, "--fx", str(f), "--out", str(tmp_path)]) == 1 and "no scenario set" in caplog.text


def test_fx_with_dates_and_set_id_and_newest_first(tmp_path):
    f, d = tmp_path / "fx.csv", _dates(tmp_path, DAYS[::-1])
    f.write_text(_fx_long())
    assert F.main(["--date", D, "--fx", str(f), "--dates", str(d), "--order", "newest-first", "--scenario-set-id", "HS-T",
                   "--out", str(tmp_path)]) == 0
    r = next(r for _, r in read_any(tmp_path / f"pvf-{D}" / f"fx-scenario-{D}.tar") if r["CURRENCY"] == "EUR")
    assert r["SCENARIO_SET_ID"] == "HS-T" and r["RATES"][0] == 1.0 and len(r["RATES"]) == 6


def test_fx_set_from_tar_path(tmp_path):
    folder = _set(tmp_path)
    f = tmp_path / "fx.csv"
    f.write_text(_fx_long())
    assert F.main(["--date", D, "--fx", str(f), "--scenario-set", str(folder / f"scenario-set-{D}.tar"), "--out", str(tmp_path / "o")]) == 0


# ---- the inbox -----------------------------------------------------------------------------------------------------------------------
def test_inbox_order_set_fx_pv(tmp_path):
    arch = tmp_path / "a"
    arch.mkdir()
    names = [f"scenario-pv-{D}.tar", f"fx-scenario-{D}.tar", f"scenario-set-{D}.tar"]
    for n in names:
        (arch / n).write_bytes(b"x")
        (arch / f"{n}.ready").write_text("rows: 1\n")
    inbox = tmp_path / "inbox"
    inbox.mkdir()
    done = common.to_inbox([str(arch / n) for n in names], inbox)
    assert done == [f"scenario-set-{D}.tar", f"fx-scenario-{D}.tar", f"scenario-pv-{D}.tar"]
    assert all((inbox / n).is_file() and (inbox / f"{n}.ready").is_file() for n in names)


def test_inbox_flag_on_the_scripts(tmp_path):
    inbox = tmp_path / "inbox"
    inbox.mkdir()
    folder, rc = _fx(tmp_path, _fx_long(), "--inbox", str(inbox))
    assert rc == 0 and (inbox / f"fx-scenario-{D}.tar.ready").is_file()


# ---- the samples ---------------------------------------------------------------------------------------------------------------------
@pytest.mark.parametrize("fx_name", ["fx_2026-11-18.csv", "fx_long_2026-11-18.csv"])
def test_samples_load_and_pass_the_contracts(tmp_path, fx_name):
    dates = SAMPLES / "scenario_dates_2026-11-18.txt"
    assert S.main(["--date", D, "--dates", str(dates), "--out", str(tmp_path)]) == 0
    assert F.main(["--date", D, "--fx", str(SAMPLES / fx_name), "--out", str(tmp_path)]) == 0
    folder = tmp_path / f"pvf-{D}"
    sset = next(r for _, r in read_any(folder / f"scenario-set-{D}.tar"))
    fx = [r for _, r in read_any(folder / f"fx-scenario-{D}.tar")]
    assert sset["SCENARIO_COUNT"] == 251 and [r["CURRENCY"] for r in fx] == ["AUD", "CAD", "CHF", "EUR", "GBP", "JPY"]
    assert all(len(r["RATES"]) == 251 and fx_scenario().check(r) == [] for r in fx)
    check = DateCheck()
    check.add_set(sset)
    assert check.problems() == []
    with tarfile.open(folder / f"fx-scenario-{D}.tar") as t:
        assert t.getnames() == [f"fx-scenario/{D}/cob.jsonl.gz"]


def test_sample_formats_agree():
    a = F.parse_fx_text((SAMPLES / "fx_2026-11-18.csv").read_text(), D)
    b = F.parse_fx_text((SAMPLES / "fx_long_2026-11-18.csv").read_text(), D)
    assert a == b and "USD" not in a


def test_pvf_run_accepts_fx():
    from mrloader import pvf_run
    assert pvf_run.parser().parse_args(["--pv", "a", "--portfolio", "b", "--positions", "c", "--date", D, "--fx", "f.csv"]).fx == Path("f.csv")
