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
"""The product's modules as packages: every module folder without a server part (capital-markets, economy, the source-* modules) is packed
by the one packager (tools/modulepack.py) and goes through the conformance checks C1-C14 of the server jar's verifier (ModuleCli, when the
jar is built: python tools/drishti.py build jar); their manifests say what they require; a module with a server part is not packed without
its jar; and every module under modules/ with a java: block is in the set packaging/dist ships (or is listed here as not shipped, with the reason).
"""
from __future__ import annotations

import re
import subprocess
import sys
import zipfile
from pathlib import Path

import pytest
import yaml

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
import modulepack  # noqa: E402
import drishti  # noqa: E402

MODULES = ROOT / "modules"
CONTENT = ["capital-markets", "economy", "source-calypso", "source-endur", "source-ici", "source-murex", "source-swapone", "source-wss"]

# a module with a java: block that packaging/dist does NOT pack: the reason (none today: every one is shipped)
NOT_SHIPPED: dict[str, str] = {}


def manifest(mid: str) -> dict:
    return yaml.safe_load((MODULES / mid / "module.yaml").read_text(encoding="utf-8"))


def required(mid: str) -> list[str]:
    """The ids a module requires, required or optional (module.yaml's `requires:` is ids, or `required:`/`optional:` groups of ids)."""
    r = manifest(mid).get("requires") or {}
    if isinstance(r, list):
        return [x if isinstance(x, str) else next(iter(x)) for x in r]
    out = []
    for k, v in r.items():
        out += list(v) if k in ("required", "optional") and isinstance(v, dict) else [k]
    return out


def verifier_built() -> bool:
    try:
        drishti.find_jar(None)
        drishti.find_java(None)
        return True
    except Exception:  # CliError: no jar or no java in this checkout
        return False


def shipped_by_dist() -> set[str]:
    """The modules packaging/dist packs: its pack-<module> executions."""
    pom = (ROOT / "packaging/dist/pom.xml").read_text(encoding="utf-8")
    return set(re.findall(r"<id>pack-([a-z0-9-]+)</id>", pom))


@pytest.mark.parametrize("mid", CONTENT)
def test_a_content_module_is_packed_and_passes_the_fourteen_checks(mid, tmp_path):
    if not verifier_built():
        pytest.skip("needs the server jar in lib/ (python tools/drishti.py build jar) and a JDK")
    dmod, _ = modulepack.pack(MODULES / mid, out=tmp_path)
    r = drishti.run_module_cli(type("A", (), {})(), ["verify", str(dmod), "--conformance", "--with", *shipped_requirements(mid, tmp_path)], capture=True)
    assert r.returncode == 0, r.stdout + r.stderr
    assert "all 14 conformance checks passed" in r.stdout


def shipped_requirements(mid: str, tmp: Path) -> list[str]:
    """The packages of the modules this one requires (packed beside it): a module with code is built by packaging/dist, so its package is that build's."""
    out = []
    for req in required(mid):
        stems = {req, req.replace("-", "")}                  # a package is named by its id, hyphens left out (capitalmarkets-1.0.0.dmod)
        built = sorted(f for d in (ROOT / "modules", ROOT / "packaging/dist/target/dmod") for st in stems for f in d.glob(f"{st}-[0-9]*.dmod"))
        if (MODULES / req / "module.yaml").is_file() and manifest(req).get("java") is None:
            out.append(str(modulepack.pack(MODULES / req, out=tmp / "requires")[0]))
        elif built:
            out.append(str(built[-1]))
    if not out:
        pytest.skip(f"{mid} requires modules with code, built by the gate (python tools/drishti.py build modules)")
    return out


@pytest.mark.parametrize("mid", ["capital-markets", "economy"])
def test_a_content_module_carries_its_content_and_no_code(mid, tmp_path):
    dmod, _ = modulepack.pack(MODULES / mid, out=tmp_path)
    with zipfile.ZipFile(dmod) as z:
        names = z.namelist()
        m = yaml.safe_load(z.read("module.yaml"))
        assert m.get("java") is None                                   # content only
        assert modulepack.CONTENT_YAML not in names                      # the list itself is not packed
        for pack in (m.get("provides") or {}).get("kinds", {}):
            assert f"packs/{pack}/pack.yaml" in names, pack
            assert z.read(f"packs/{pack}/pack.yaml").decode() == (MODULES / mid / "packs" / pack / "pack.yaml").read_text(encoding="utf-8")
    if mid == "economy":
        assert not any(n.startswith("packs/world-economy/samples") for n in names)   # the IMF figures stay internal


@pytest.mark.parametrize("mid", ["market-data", "trading", "banking", "genomics"])
def test_a_module_with_a_server_part_is_not_packed_without_its_jar(mid, tmp_path):
    assert manifest(mid).get("java") is not None
    with pytest.raises(modulepack.PackError):
        modulepack.pack(MODULES / mid, out=tmp_path)


def test_banking_is_not_a_carrier_of_the_market_data_pack():
    banking = manifest("banking")
    assert required("banking") == ["market-data"]
    assert "market-data" not in str(banking.get("packs"))
    assert banking.get("origin") == "first-party"
    assert "market-data" in str(manifest("market-data").get("packs"))
    for mid in ("capital-markets", "economy"):
        assert required(mid) == ["market-data"]


def test_every_module_with_a_java_block_is_shipped_or_listed_as_not_shipped_with_a_reason():
    if not verifier_built():
        pytest.skip("needs the server jar in lib/ (python tools/drishti.py build jar): the modules are asked of its resolver")
    import resolved
    with_code = {mid for mid, m in resolved.module_list().items() if (yaml.safe_load((m.home / "module.yaml").read_text(encoding="utf-8")) or {}).get("java")}
    shipped = shipped_by_dist()
    missing = sorted(with_code - shipped - set(NOT_SHIPPED))
    assert not missing, f"{missing}: has a java: block but packaging/dist does not pack it (add pack-<module> there, or list it in NOT_SHIPPED with the reason)"
    assert all(NOT_SHIPPED[m].strip() for m in NOT_SHIPPED)
    assert not (set(NOT_SHIPPED) & shipped), "listed as not shipped, but packaged"
    assert {"banking", "market-data", "trading", "genomics"} <= shipped


def test_dist_pom_has_a_check_for_every_module_with_a_server_part():
    pom = (ROOT / "packaging/dist/pom.xml").read_text(encoding="utf-8")
    for mid in ("banking", "market-data", "trading", "genomics"):
        assert f"<id>check-{mid}</id>" in pom, mid
    assert "ModuleTool" not in pom and "drishti-module-tool" not in pom
