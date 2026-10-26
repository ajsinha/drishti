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

"""The Python VaR engine (docs/guides/VAR_GUIDE.md sections 18, 8 and 2.1): its measures give the worked example's numbers exactly as
the Java engine does (N = 10, alpha 80%, a component window of 3), and the what-if engine of ``var-whatif`` adds hypothetical trades to a
scope's vector: putting back the hedge the scope took out gives the book again, each item's incremental VaR in order and alone, the
components of the new trades, the stressed window, and refusals in words."""
import os

import pytest

from drishti_compute import arrow as A
from drishti_compute.engine import ComputeError, Inputs, Progress, Request
from drishti_compute.var import measures as M
from drishti_compute.var.whatif import VarWhatIfEngine

T1 = [5, -8, 3, -12, 6, -2, 4, -5, 7, 1]
T2 = [-2, 3, -1, 4, -3, 1, -2, 2, -3, 0]
T3 = [-4, 2, -6, 1, -9, 3, -1, 0, 2, -7]
P1 = [a + b for a, b in zip(T1, T2)]
BOOK = [a + b for a, b in zip(P1, T3)]


def test_the_measures_give_the_worked_example():
    m = M.measures(BOOK, 0.80, 0.80, window=3)
    assert m.order[:3] == [3, 4, 9]                      # ties by the older scenario
    assert (m.k, m.es_count) == (2, 2)
    assert m.var == 6 and m.es == 6.5
    assert M.component(P1, m) == pytest.approx(1.2632, abs=1e-4)
    assert M.component(T3, m) == pytest.approx(4.7368, abs=1e-4)
    assert M.component(P1, m) + M.component(T3, m) == pytest.approx(m.var)
    assert M.component_es(P1, m) + M.component_es(T3, m) == pytest.approx(m.es)
    assert m.var - M.var([b - p for b, p in zip(BOOK, P1)], 0.80) == -1       # the hedge: removing it raises VaR
    assert M.rank(509, 0.99) == 6 and M.rank(509, 0.975) == 13
    assert M.var(BOOK, 0.80, "interpolated") == pytest.approx(6.0)       # pos = 2: the 2nd worst exactly


def test_the_stressed_window_is_the_worst_run_of_days():
    v = [1, 1, 1, -50, -40, 1, 1, 1, 1, 1]
    best, at = M.stressed(v, 3, 0.80)
    assert (best, at) == (50, 1)                          # the first run of 3 days holding the -50 (k = 1 of 3 at 80%)
    assert M.stressed(v, 10, 0.80)[0] == M.var(v, 0.80)


def _req(scratch, **params):
    return Request(id="req-1", kind="var-whatif", params=params, user="asha", roles=(), view_signature="",
                   cls="interactive", seed=0, scratch=str(scratch))


def _inputs(tmp_path, base_pnl, items=None):
    os.makedirs(tmp_path / "in", exist_ok=True)
    A.write_file(A.Table([A.utf8("SCOPE", ["BOOK:BOOK-X - TRD:T2"]), A.utf8("CANONICAL", ["BOOK:BOOK-X - TRD:T2"]), A.utf8("BUSINESS_DATE", ["2026-09-30"]),
                          A.float64("CONFIDENCE", [0.8]), A.float64("ES_CONFIDENCE", [0.8]), A.float64("LIMIT", [10.0]),
                          A.vectors("PNL", len(base_pnl), base_pnl)]), tmp_path / "in" / "base.arrows")
    files = {"base": "in/base.arrows"}
    if items is not None:
        flat = [x for it in items for x in it[1]]
        A.write_file(A.Table([A.utf8("LABEL", [it[0] for it in items]), A.float64("FACTOR", [it[2] for it in items]),
                              A.vectors("PNL", len(items[0][1]), flat)]), tmp_path / "in" / "sandbox.arrows")
        files["sandbox"] = "in/sandbox.arrows"
    return Inputs(str(tmp_path), files)


def test_putting_the_hedge_back_gives_the_book_again(tmp_path):
    without_t2 = [a + b for a, b in zip(T1, T3)]
    seen = []
    r = VarWhatIfEngine().compute(_req(tmp_path, **{"component-window": 3, "stress-window": 5}),
                                  _inputs(tmp_path, without_t2, [("T2 again", T2, 1.0), ("half of T3", T3, 0.5)]), Progress(seen.append))
    row = r.rows[0]
    assert (row["BEFORE_VAR"], row["BEFORE_ES"]) == (6, 8.5)                 # the worked example's book minus T2
    assert row["ITEMS"] == 2 and row["LIMIT_USE_BEFORE"] == 0.6
    assert row["SCOPE"] == "BOOK:BOOK-X - TRD:T2"                      # the base's scope, never the request id
    items = r.tables["items"].to_rows()
    assert items[0]["LABEL"] == "T2 again"
    assert items[0]["INCREMENTAL_VAR"] == 0 and items[0]["INCREMENTAL_ALONE"] == 0     # back to the book: VaR 6 again
    after = [b + t2 + 0.5 * t3 for b, t2, t3 in zip(without_t2, T2, T3)]
    m = M.measures(after, 0.8, 0.8, 3)
    assert row["AFTER_VAR"] == m.var and row["AFTER_ES"] == m.es
    assert sum(i["INCREMENTAL_VAR"] for i in items) == pytest.approx(row["CHANGE_VAR"])
    assert items[1]["STANDALONE_VAR"] == pytest.approx(M.var([0.5 * x for x in T3], 0.8))
    assert r.tables["pnl"].column("AFTER").to_list() == pytest.approx(after)
    assert row["STRESS_WINDOW"] == 5 and row["STRESSED_VAR_BEFORE"] >= row["BEFORE_VAR"] - 1e-9
    assert seen[-1] == {"done": 1, "of": 1}


def test_without_a_sandbox_it_answers_the_stressed_window_alone(tmp_path):
    r = VarWhatIfEngine().compute(_req(tmp_path), _inputs(tmp_path, BOOK), Progress(lambda d: None))
    row = r.rows[0]
    assert row["ITEMS"] == 0 and row["AFTER_VAR"] == row["BEFORE_VAR"] == 6
    assert "items" not in r.tables


def test_refusals_are_in_words(tmp_path):
    with pytest.raises(ComputeError, match="has 3 scenario P&Ls; the scope has 10"):
        VarWhatIfEngine().compute(_req(tmp_path), _inputs(tmp_path, BOOK, [("short", [1, 2, 3], 1.0)]), Progress(lambda d: None))
    os.makedirs(tmp_path / "empty", exist_ok=True)
    A.write_file(A.Table([A.utf8("CANONICAL", ["X"])]), tmp_path / "empty" / "base.arrows")
    with pytest.raises(ComputeError, match="no VaR answer to start from"):
        VarWhatIfEngine().compute(_req(tmp_path), Inputs(str(tmp_path), {"base": "empty/base.arrows"}), Progress(lambda d: None))
