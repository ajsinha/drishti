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
"""The product's modules as packages: every module folder without a server part (external-data, news-and-events, the source-* modules) is packed
by the one packager and goes through the conformance checks C1-C14 (both ModuleCli of the server jar, when the jar is built:
python tools/drishti.py build jar); their manifests say what they require; a module with a server part is not packed without
its jar; and every module under modules/ with a java: block is in the set packaging/dist ships (or is listed here as not shipped, with the reason).
"""
from __future__ import annotations

import json
import re
import subprocess
import sys
import zipfile
from pathlib import Path

import pytest
import yaml

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
sys.path.insert(0, str(ROOT / "tools" / "launch"))
import drishti  # noqa: E402
import modulesbuild  # noqa: E402
from procs import LaunchError  # noqa: E402

MODULES = ROOT / "modules"
CONTENT = ["external-data", "news-and-events", "reference-data", "source-calypso", "source-endur", "source-ici", "source-murex", "source-swapone", "source-wss"]

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


def needs_packer() -> None:
    if not verifier_built():
        pytest.skip("needs the server jar in lib/ (python tools/drishti.py build jar) and a JDK: the packer is the jar's")


def pack_module(folder: Path, out: Path) -> Path:
    needs_packer()
    return modulesbuild.pack(folder, out)


def restricted_paths(folder: Path) -> list[str]:
    """The files content.yaml of a module folder restricts (a licence keeps them out of the package), package-relative."""
    f = folder / "content.yaml"
    spec = (yaml.safe_load(f.read_text(encoding="utf-8")) or {}) if f.is_file() else {}
    return [str(e["path"]).strip("/") for e in spec.get("restricted") or []]


def shipped_by_dist() -> set[str]:
    """The modules packaging/dist packs: its pack-<module> executions."""
    pom = (ROOT / "packaging/dist/pom.xml").read_text(encoding="utf-8")
    return set(re.findall(r"<id>pack-([a-z0-9-]+)</id>", pom))


@pytest.mark.parametrize("mid", CONTENT)
def test_a_content_module_is_packed_and_passes_the_fourteen_checks(mid, tmp_path):
    dmod = pack_module(MODULES / mid, tmp_path)
    r = drishti.run_module_cli(type("A", (), {})(), ["verify", str(dmod), "--conformance", "--with", *shipped_requirements(mid, tmp_path)], capture=True)
    assert r.returncode == 0, r.stdout + r.stderr
    assert "all 14 conformance checks passed" in r.stdout


def shipped_requirements(mid: str, tmp: Path) -> list[str]:
    """The packages of the modules this one requires (packed beside it): a module with code is built by packaging/dist, so its package is that build's."""
    out = []
    for req in required(mid):
        stems = {req, req.replace("-", "")}                  # a package is named by its id, hyphens left out (externaldata-1.0.0.dmod)
        built = sorted(f for d in (ROOT / "modules", ROOT / "packaging/dist/target/dmod") for st in stems for f in d.glob(f"{st}-[0-9]*.dmod"))
        if (MODULES / req / "module.yaml").is_file() and manifest(req).get("java") is None:
            out.append(str(pack_module(MODULES / req, tmp / "requires")))
        elif built:
            out.append(str(built[-1]))
    if not out:
        pytest.skip(f"{mid} requires modules with code, built by the gate (python tools/drishti.py build modules)")
    return out


@pytest.mark.parametrize("mid", ["external-data", "news-and-events"])
def test_a_content_module_carries_its_content_and_no_code(mid, tmp_path):
    dmod = pack_module(MODULES / mid, tmp_path)
    with zipfile.ZipFile(dmod) as z:
        names = z.namelist()
        m = yaml.safe_load(z.read("module.yaml"))
        assert m.get("java") is None                                   # content only
        assert "content.yaml" not in names                      # the list itself is not packed
        for pack in (m.get("provides") or {}).get("kinds", {}):
            assert f"packs/{pack}/pack.yaml" in names, pack
            assert z.read(f"packs/{pack}/pack.yaml").decode() == (MODULES / mid / "packs" / pack / "pack.yaml").read_text(encoding="utf-8")
    # a restricted file (content.yaml, with its licence reason) is absent; every other sample of the pack travels
    restricted = set(restricted_paths(MODULES / mid))
    assert not restricted & set(names), sorted(restricted & set(names))
    for r in restricted:
        assert (MODULES / mid / r).is_file(), r
    if mid == "external-data":
        assert restricted == {f"packs/world-economy/samples/{n}.jsonl" for n in ("economic-forecast", "economy", "fiscal-position", "macro-indicator")}
        shipped = {Path(n).name for n in names if n.startswith("packs/world-economy/samples/")}
        assert {"jurisdiction.jsonl", "region.jsonl", "region-economy.jsonl", "election.jsonl", "party.jsonl", "office-holder.jsonl",
                "labour-market.jsonl", "social-indicator.jsonl"} <= shipped                    # the non-IMF samples travel
        with zipfile.ZipFile(dmod) as zz:
            cat = [json.loads(l) for l in zz.read("packs/world-economy/samples/catalog.jsonl").decode().splitlines() if l.strip()]
        kinds = {c["kind"] for c in cat}
        assert {"jurisdiction", "social-indicator", "region"} <= kinds and not kinds & {"economy", "macro-indicator", "economic-forecast", "fiscal-position"}, kinds
        with zipfile.ZipFile(dmod) as zz:          # what the package carries (the catalogue as trimmed), not the source folder
            shipped_text = {n: zz.read(n).decode("utf-8") for n in names if n.startswith("packs/world-economy/samples/") and n.endswith(".jsonl")}
        assert not [n for n, t in shipped_text.items() if re.search(r"imf|weo", t, re.I)], "a shipped sample names the IMF"


@pytest.mark.parametrize("mid", ["market-data", "trading", "banking", "genomics"])
def test_a_module_with_a_server_part_is_not_packed_without_its_jar(mid, tmp_path):
    assert manifest(mid).get("java") is not None
    with pytest.raises(LaunchError, match="neither in the folder nor given"):
        pack_module(MODULES / mid, tmp_path)


def test_banking_is_not_a_carrier_of_the_market_data_pack():
    banking = manifest("banking")
    assert "market-data" in required("banking")
    assert "market-data" not in str(banking.get("packs"))
    assert banking.get("origin") == "first-party"
    assert "market-data" in str(manifest("market-data").get("packs"))
    assert required("news-and-events") == ["market-data"]
    assert required("external-data") == ["reference-data"]           # the kernel only, and the LEI link of us-equities (optional)


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


def test_packaging_dist_packs_every_module_folder():
    """One Maven run packs every module (Java, ModuleCli module pack): no module is packed anywhere else."""
    assert shipped_by_dist() == {d.name for d in MODULES.iterdir() if (d / "module.yaml").is_file()}


def test_dist_pom_has_a_check_for_every_module_with_a_server_part():
    pom = (ROOT / "packaging/dist/pom.xml").read_text(encoding="utf-8")
    for mid in ("banking", "market-data", "trading", "genomics"):
        assert f"<id>check-{mid}</id>" in pom, mid
    assert "ModuleTool" not in pom and "drishti-module-tool" not in pom
