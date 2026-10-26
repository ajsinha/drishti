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

"""The Python exposure engine (docs/guides/EXPOSURE_GUIDE.md sections 2 and 19): its measures give the worked example's numbers as the
Java engine does (4 paths, 3 primary dates, thresholds of 1); the what-if engine of ``exposure-whatif`` adds a candidate's paths to its
netting set: trade A's set plus trade B as a candidate is the worked example's set again (EE 1.0, 1.5, 0.75; PFE 75% 1, 2, 1; EPE 1.0;
EEPE 1.375), the incremental figures are with minus without, a candidate of its own is uncollateralised, nothing added changes nothing,
and refusals are in words."""
import os

import pytest

from drishti_compute import arrow as A
from drishti_compute.engine import ComputeError, Inputs, Progress, Request
from drishti_compute.exposure import measures as M
from drishti_compute.exposure.whatif import ExposureWhatIfEngine

P = 4
# grid: three look-back dates, then the three primaries (t = 0.25, 0.5, 1.0); a look-back k serves primary k + 3
GRID = M.Grid(primary=[False] * 3 + [True] * 3, lookback_for=[-1, -1, -1, 0, 1, 2])
TIMES = [0.25, 0.5, 1.0]
A_ = [[3, -2, 3, 0], [5, -1, 4, -2], [2, -3, 6, -1]]
B_ = [[-1, 1, -2, 1], [-1, 2, 0, 0], [1, 1, -1, -2]]
L_ = [[1, -1, 0, 1], [2, 0, 3, -1], [3, -1, 4, -3]]          # the set's value at the look-back dates (carried by A)
CSA = M.Csa(present=True, margined=True, th_them=1, th_us=1)


def flat(rows):
    return [float(x) for r in rows for x in r]


V_A = flat(L_ + A_)                                            # trade A with the look-back values: G x P
M_B = flat([[0] * 4] * 3 + B_)                                 # trade B: 0 at the look-backs


def test_the_measures_give_the_worked_example():
    e, n = M.exposure([a + b for a, b in zip(V_A, M_B)], GRID, P, CSA)
    assert e == flat([[2, 0, 1, 1], [3, 1, 2, 0], [1, 0, 2, 0]])
    assert n == flat([[0, -1, 0, 0], [0, 0, 0, -2], [0, -2, 0, -1]])
    s = M.statistics(e, n, TIMES, P, 1.0, 0.75)
    assert s.ee == [1.0, 1.5, 0.75] and s.pfe == [1, 2, 1] and s.eee == [1.0, 1.5, 1.5]
    assert s.epe == pytest.approx(1.0) and s.eepe == pytest.approx(1.375)
    assert (s.peak_pfe, s.peak_at) == (2, 1)
    assert M.statistics(e, n, TIMES, P, 1.0, 0.95).pfe == [2, 3, 2]
    assert M.rank(200, 0.95) == 190 and M.rank(4, 0.75) == 3


def _req(scratch, **params):
    return Request(id="req-1", kind="exposure-whatif", params=params, user="asha", roles=(), view_signature="",
                   cls="interactive", seed=0, scratch=str(scratch))


def _inputs(tmp_path, items=None):
    os.makedirs(tmp_path / "in", exist_ok=True)
    e, n = M.exposure(V_A, GRID, P, CSA)                       # the scope: the netting set of trade A alone
    prim = [1.0 if x else 0.0 for x in GRID.primary]
    lb = [float(x) for x in GRID.lookback_for]
    A.write_file(A.Table([A.utf8("SCOPE", ["NSET:NS-X"]), A.utf8("CANONICAL", ["NSET:NS-X"]), A.utf8("BUSINESS_DATE", ["2026-09-30"]),
                          A.utf8("RUN_ID", ["XR-1"]), A.int64("PATHS", [P]), A.float64("CONFIDENCE", [0.75]), A.utf8("RULE", ["kth"]),
                          A.utf8("TENORS", ["3M,6M,1Y"]), A.utf8("DATES", ["2026-12-30,2027-03-30,2027-09-30"]), A.float64("CE_SCOPE", [0.0]),
                          A.float64("LIFE_YEARS", [1.0]), A.utf8("WHATIF_SET_IDS", ["NS-X"]), A.vectors("E_SCOPE", len(e), e),
                          A.vectors("N_SCOPE", len(n), n), A.vectors("TIMES", 3, TIMES), A.vectors("PRIMARY", 6, prim),
                          A.vectors("LOOKBACK_FOR", 6, lb)]), tmp_path / "in" / "base.arrows")
    A.write_file(A.Table([A.utf8("NETTING_SET", ["NS-X"]), A.vectors("V", len(V_A), V_A), A.vectors("E_SET", len(e), e),
                          A.vectors("N_SET", len(n), n), A.boolean("CSA_PRESENT", [True]), A.boolean("MARGINED", [True]),
                          A.float64("TH_THEM", [1.0]), A.float64("TH_US", [1.0]), A.float64("MTA_THEM", [0.0]), A.float64("MTA_US", [0.0]),
                          A.float64("IA", [0.0]), A.float64("ROUNDING", [0.0]), A.float64("HELD_TODAY", [0.0]), A.float64("IM_HELD", [0.0]),
                          A.float64("V0", [0.0]), A.float64("CE_SET", [0.0])]), tmp_path / "in" / "sets.arrows")
    files = {"base": "in/base.arrows", "sets": "in/sets.arrows"}
    if items is not None:
        A.write_file(A.Table([A.utf8("TRADE_ID", [i[0] for i in items]), A.utf8("NETTING_SET", [i[1] for i in items]),
                              A.float64("MTM0", [0.0 for _ in items]), A.float64("LIFE_YEARS", [1.0 for _ in items]),
                              A.vectors("MTM", len(items[0][2]), [x for i in items for x in i[2]])]), tmp_path / "in" / "sandbox.arrows")
        files["sandbox"] = "in/sandbox.arrows"
    return Inputs(str(tmp_path), files)


def test_a_candidate_joining_its_netting_set_is_exact(tmp_path):
    seen = []
    r = ExposureWhatIfEngine().compute(_req(tmp_path, q=0.75), _inputs(tmp_path, [("B", "NS-X", M_B)]), Progress(seen.append))
    row = r.rows[0]
    assert row["SCOPE"] == "NSET:NS-X" and row["METHOD"] == "paths" and row["ITEMS"] == 1
    assert row["AFTER_EPE"] == pytest.approx(1.0) and row["AFTER_EEPE"] == pytest.approx(1.375) and row["AFTER_PEAK_PFE"] == 2
    assert row["AFTER_PEAK_PFE_TENOR"] == "6M"
    before = M.statistics(*M.exposure(V_A, GRID, P, CSA), TIMES, P, 1.0, 0.75)
    assert row["BEFORE_PEAK_PFE"] == before.peak_pfe and row["BEFORE_EPE"] == pytest.approx(before.epe)
    assert row["CHANGE_PEAK_PFE"] == pytest.approx(row["AFTER_PEAK_PFE"] - row["BEFORE_PEAK_PFE"])
    prof = r.tables["profile"].to_rows()
    assert [x["EE"] for x in prof] == pytest.approx([1.0, 1.5, 0.75]) and [x["PFE"] for x in prof] == [1, 2, 1]
    assert all(x["INCREMENTAL_EE"] == pytest.approx(x["EE"] - x["EE_BASE"]) for x in prof)
    items = r.tables["items"].to_rows()
    assert items[0]["INCREMENTAL_PEAK_PFE"] == pytest.approx(row["CHANGE_PEAK_PFE"])
    assert items[0]["PEAK_PFE_ALONE"] == 1                         # B alone, uncollateralised: 75% of max(B, 0) is 1, 0, 1
    assert seen[-1] == {"done": 1, "of": 1}


def test_a_candidate_of_its_own_is_uncollateralised_and_nothing_changes_nothing(tmp_path):
    r = ExposureWhatIfEngine().compute(_req(tmp_path, q=0.75), _inputs(tmp_path, [("B", "", M_B)]), Progress(lambda d: None))
    prof = r.tables["profile"].to_rows()
    pos = [sum(max(x, 0) for x in row) / 4 for row in B_]
    assert [x["INCREMENTAL_EE"] for x in prof] == pytest.approx(pos)          # max(B, 0) added path by path, no netting with A
    r0 = ExposureWhatIfEngine().compute(_req(tmp_path, q=0.75), _inputs(tmp_path), Progress(lambda d: None))
    row = r0.rows[0]
    assert row["ITEMS"] == 0 and row["CHANGE_EPE"] == 0 and row["AFTER_PEAK_PFE"] == row["BEFORE_PEAK_PFE"]
    assert "items" not in r0.tables


def test_refusals_are_in_words(tmp_path):
    with pytest.raises(ComputeError, match="not of the run XR-1: 3 values, the run has 6 grid dates x 4 paths"):
        ExposureWhatIfEngine().compute(_req(tmp_path), _inputs(tmp_path, [("short", "NS-X", [1.0, 2.0, 3.0])]), Progress(lambda d: None))
    with pytest.raises(ComputeError, match="joins the netting set NS-Y"):
        ExposureWhatIfEngine().compute(_req(tmp_path), _inputs(tmp_path, [("B", "NS-Y", M_B)]), Progress(lambda d: None))
    os.makedirs(tmp_path / "empty", exist_ok=True)
    A.write_file(A.Table([A.utf8("CANONICAL", ["X"])]), tmp_path / "empty" / "base.arrows")
    with pytest.raises(ComputeError, match="no exposure answer to start from"):
        ExposureWhatIfEngine().compute(_req(tmp_path), Inputs(str(tmp_path), {"base": "empty/base.arrows"}), Progress(lambda d: None))
