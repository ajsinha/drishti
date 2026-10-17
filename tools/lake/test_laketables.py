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

"""lake status's table figures (laketables.py): rows, files, partitions, dates and the last build, read from a Delta log alone."""
import json
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import laketables  # noqa: E402


def _log(t: pathlib.Path, n: int, actions: list[dict]) -> None:
    (t / "_delta_log").mkdir(parents=True, exist_ok=True)
    (t / "_delta_log" / f"{n:020d}.json").write_text("\n".join(json.dumps(a) for a in actions) + "\n", encoding="utf-8")


def _add(path, rows, day, product):
    return {"add": {"path": path, "size": 1000, "partitionValues": {"business_date": day, "PRODUCT_TYPE": product}, "stats": json.dumps({"numRecords": rows})}}


def test_a_lake_lists_each_table_with_rows_partitions_dates_and_last_build(tmp_path):
    t = tmp_path / "trading" / "trade_endur"
    _log(t, 0, [{"metaData": {"partitionColumns": ["business_date", "PRODUCT_TYPE"]}}, _add("a", 10, "2026-10-15", "ComSwap"),
                _add("b", 5, "2026-10-15", "Cash"), {"commitInfo": {"timestamp": 1792269960000}}])
    _log(t, 1, [_add("c", 12, "2026-10-16", "ComSwap"), {"remove": {"path": "b"}}, {"commitInfo": {"timestamp": 1792270000000}}])
    got = laketables.tables(tmp_path)
    assert len(got) == 1
    r = got[0]
    assert r["table"] == "trading/trade_endur"
    assert (r["rows"], r["files"], r["partitions"], r["dates"], r["latestDate"]) == (22, 2, 2, 2, "2026-10-16")
    assert r["lastBuild"].startswith("2026-")
    text = laketables.table_text(got)
    assert "trading/trade_endur" in text and "22" in text and "(PRODUCT_TYPE)" in text


def test_an_empty_or_missing_lake_says_so(tmp_path):
    assert laketables.tables(tmp_path / "none") == []
    assert "no tables" in laketables.table_text([])
