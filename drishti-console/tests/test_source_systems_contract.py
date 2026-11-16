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

"""Every shipped source module's trading system is a value the trade contract allows: each connector of modules/source-*/connectors
sets ``SOURCE_SYSTEM`` (``set:``) and filters on it (``where:``); the trading pack's trade contract lists the allowed values, and the
demo-trade generator's schema (tools/packgen/trades/schema.py ``ALLOWED``) checks the same list. A new source module that is not in the
contract fails here."""
from __future__ import annotations

import importlib.util
import re
from pathlib import Path

import pytest
import yaml

pytestmark = pytest.mark.static

ROOT = Path(__file__).resolve().parents[2]
CONTRACT = ROOT / "modules" / "banking" / "packs" / "trading" / "contracts" / "trade.contract.yaml"


def _allowed() -> list[str]:
    c = yaml.safe_load(CONTRACT.read_text(encoding="utf-8"))
    field = next(f for f in c["fields"] if f.get("name") == "SOURCE_SYSTEM")
    return list(field["enum"])


def _systems() -> dict[str, set[str]]:
    """Source module -> the SOURCE_SYSTEM values its connectors set or filter on."""
    out: dict[str, set[str]] = {}
    for f in sorted((ROOT / "modules").glob("source-*/connectors/*.yaml")):
        s = (yaml.safe_load(f.read_text(encoding="utf-8")) or {}).get("settings") or {}
        vals = out.setdefault(f.parts[-3], set())
        if (s.get("set") or {}).get("SOURCE_SYSTEM"):
            vals.add(str(s["set"]["SOURCE_SYSTEM"]))
        m = re.search(r"SOURCE_SYSTEM\s*=\s*'([^']+)'", str(s.get("where") or ""))
        if m:
            vals.add(m.group(1))
    return out


def test_every_source_modules_system_is_allowed_by_the_trade_contract():
    allowed = set(_allowed())
    systems = _systems()
    assert len(systems) >= 6, f"the shipped source modules: {sorted(systems)}"
    for module, vals in systems.items():
        assert vals, f"{module}: no connector sets SOURCE_SYSTEM"
        assert vals <= allowed, f"{module} sets {sorted(vals - allowed)}, which the trade contract ({CONTRACT.relative_to(ROOT)}) does not allow"


def test_every_trade_grain_contract_allows_every_source_system():
    allowed = set(_allowed())
    for f in sorted((ROOT / "modules").glob("*/packs/*/contracts/*.contract.yaml")):
        c = yaml.safe_load(f.read_text(encoding="utf-8")) or {}
        for field in c.get("fields") or []:
            if field.get("name") == "SOURCE_SYSTEM" and field.get("enum"):
                assert allowed <= set(field["enum"]), f"{f.relative_to(ROOT)}: SOURCE_SYSTEM lacks {sorted(allowed - set(field['enum']))}"


def test_the_trade_generators_schema_allows_the_same_systems():
    spec = importlib.util.spec_from_file_location("trade_schema", ROOT / "tools" / "packgen" / "trades" / "schema.py")
    schema = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(schema)
    assert set(schema.ALLOWED) == set(_allowed())
    assert set(schema.SYSTEMS) <= set(schema.ALLOWED)
