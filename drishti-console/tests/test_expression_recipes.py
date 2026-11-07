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

"""Every expression recipe (tests/resources/expression_recipes.yaml, written in Rachana: RACHANA_REFERENCE.md) runs on the demo lake
(docs/architecture/BI_DESIGN.md section 12.2, wave A acceptance 6): the real server and console of tests/bi_report_stack.py on ports 18912 and
17912 with the cross-pack Delta lake (tools/packgen/banking/make_data.py --lake, a few thousand rows) and the trading-risk sample. Each recipe has
its expression, the fields it is grouped by and its scope. A measure is asked as a model query with ``define`` (as the risk reader, who sees every row) and must
answer rows; a row expression is checked by the server and asked inside ``distinct(...)`` (a value) or ``count(..., where=...)`` (a condition); a rule
is checked by the server in rule scope.

The parsing half needs nothing; the running half is skipped when the built server jar, uv or its offline cache (to make the lake) is missing, or a
scratch port is in use. Never run with pytest -n (the ports are fixed)."""
from __future__ import annotations

import os
import re
from pathlib import Path

import pytest
import yaml

os.environ.setdefault("DRISHTI_BI_REPORT_SERVER_PORT", "18912")
os.environ.setdefault("DRISHTI_BI_REPORT_CONSOLE_PORT", "17912")

import bi_report_stack as rs  # noqa: E402
from bi_stack import listening  # noqa: E402

RECIPES = Path(__file__).resolve().parent / "resources" / "expression_recipes.yaml"
MODEL = "trading-risk"


def recipes() -> list[dict]:
    """The recipes: {id, title, expr, by, scope}."""
    return [{"id": r["id"], "title": r["title"], "expr": r["expr"], "by": list(r.get("by") or []), "scope": r.get("scope", "aggregate")}
            for r in yaml.safe_load(RECIPES.read_text(encoding="utf-8"))["recipes"]]


def test_there_are_forty_and_more_recipes_each_with_its_expression_in_rachana():
    found = recipes()
    assert len(found) >= 40, len(found)
    assert [r["id"] for r in found] == [f"R{i}" for i in range(1, len(found) + 1)], "the recipes are numbered R1, R2, ... in order"
    for r in found:
        plain = re.sub(r"\b(over|ignore|keep|by) = \[[^\]]*\]", "", r["expr"])                         # keyword lists of fields
        assert r["expr"] and "[" not in plain.replace("in [", ""), r                                          # no old [Measure] references
        assert not re.search(r"\b(and|or|not|None|True|False)\b| if .* else ", r["expr"].replace("'Desk and book'", "")), r     # Rachana's spelling only
        assert r["scope"] in ("aggregate", "row", "rule"), r
        assert r["by"] or r["scope"] == "rule", r


@pytest.fixture(scope="module")
def stack(tmp_path_factory):
    for port in (rs.SERVER_PORT, rs.CONSOLE_PORT):
        if listening(port):
            pytest.skip(f"port {port} is in use: stop whatever listens there")
    tmp = tmp_path_factory.mktemp("recipes")
    lake = rs.make_lake(tmp / "lake", days=3)
    if not lake:
        pytest.skip("needs the demo lake: uv and its offline cache (deltalake, pyarrow), or DRISHTI_BI_DELTA_ROOT")
    try:
        s = rs.ReportStack(tmp, delta_root=lake).start()
    except FileNotFoundError as e:
        pytest.skip(str(e))
    yield s
    s.close()


def ask(expr: str, by: list, scope: str):
    if scope == "rule":
        return rs.api("POST", "/bi/expr/validate", "rita", ["risk"], {"expr": expr, "scope": "rule", "model": MODEL + "@1", "alias": "trades"})
    measure = expr if scope == "aggregate" else (f"count(trades, where = {expr})" if re.search(r"==|!=|<=|>=|<|>|&&|\|\||^!", expr) and " ? " not in expr
                                                 else f"distinct({expr})")
    return rs.api("POST", "/bi/mq", "rita", ["risk"], {"mq": 1, "model": MODEL + "@1", "define": {"Recipe": measure},
                                                        "select": {"fields": by, "measures": ["Recipe"]}, "limit": 5})


# server findings, each until the server lane fixes it (then the mark goes): round(x, n) over a sum emits round(DOUBLE, BIGINT), which DuckDB refuses
KNOWN: dict[str, str] = {}                                                # recipe id -> the server bug a strict xfail waits for (none open)


@pytest.mark.parametrize("recipe", [pytest.param(r, marks=pytest.mark.xfail(reason=KNOWN[r["id"]], strict=True)) if r["id"] in KNOWN else r for r in recipes()],
                         ids=lambda r: r["id"])
def test_every_recipe_runs_on_the_demo_lake(stack, recipe):
    if recipe["scope"] in ("row", "rule"):
        s, checked = rs.api("POST", "/bi/expr/validate", "rita", ["risk"], {"expr": recipe["expr"], "scope": recipe["scope"], "model": MODEL + "@1",
                                                                             "alias": recipe["expr"].split(".")[0].split("(")[-1].strip() or "trades"})
        assert s == 200 and checked["valid"], (recipe, checked)
        if recipe["scope"] == "rule":
            return
    s, answer = ask(recipe["expr"], recipe["by"], recipe["scope"])
    assert s == 200, (recipe, answer)
    assert answer["rows"], (recipe, answer)
    assert [c["name"] for c in answer["columns"]][-1] == "Recipe", answer["columns"]
    if os.environ.get("DRISHTI_RECIPES_OUT"):                   # the answers the guide quotes are captured here
        import json
        with open(os.environ["DRISHTI_RECIPES_OUT"], "a") as f:
            f.write(json.dumps({"id": recipe["id"], "columns": [c["name"] for c in answer["columns"]], "rows": answer["rows"][:5]}) + "\n")
