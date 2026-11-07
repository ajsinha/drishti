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

"""The docs gates (tools/docs/docs_gates.py, DOCS_PLAN.md section 5) in WARN mode: the feature register must be well formed (that fails
now), every other gate reports its findings as warnings until the end of 2.9.11.g, when the gates switch to fail (--strict)."""
from __future__ import annotations

import importlib.util
import warnings

import pytest

from conftest import CONSOLE

ROOT = CONSOLE.parent
STRICT = False          # the end of 2.9.11.g: True, and every gate fails on a finding


def _tool():
    spec = importlib.util.spec_from_file_location("drishti_docs_gates", ROOT / "tools" / "docs" / "docs_gates.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


@pytest.mark.static
def test_the_feature_register_is_well_formed():
    reg, errors = _tool().register()
    assert not errors, errors
    ids = [f["id"] for f in reg["features"]]
    assert len(ids) >= 22 and {"F1", "R1", "B1", "O1"} <= set(ids)
    for f in reg["features"]:
        t = str(f["tutorial"])
        assert t.startswith(("tutorials/", "after ", "none")), f["id"]       # one tutorial, or the release it waits for


@pytest.mark.static
def test_the_gates_run_and_report_in_warn_mode():
    found, errors = _tool().run()
    assert not errors
    assert set(found) == {"feature register", "count caps", "page budgets", "one home", "no repeated prose", "customer text",
                          "help is user docs", "generated sections"}
    assert found["generated sections"] == [], found["generated sections"]      # generated sections are current already (they fail now)
    for gate, items in found.items():
        if items:
            message = f"docs gate {gate!r}: {len(items)} findings (python3 tools/docs/docs_gates.py), e.g. {items[0]}"
            if STRICT:
                pytest.fail(message)
            warnings.warn(message, stacklevel=1)
