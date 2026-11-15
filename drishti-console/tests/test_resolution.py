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

"""Configuration resolution, the Python twin (core/resolution.py): every case of the two shared fixtures the Java twin passes too
(packs: kernel/drishti-api/src/test/resources/pack-resolution/cases.yaml; connectors:
kernel/drishti-packs/src/test/resources/connector-layers/cases.yaml), and a module switched off and on again in a scratch tree: its packs,
connectors and inbox rules go and come back."""
from __future__ import annotations

from pathlib import Path

import pytest
import yaml

from core import resolution as R

REPO = Path(__file__).resolve().parents[2]
PACK_CASES = yaml.safe_load((REPO / "kernel/drishti-api/src/test/resources/pack-resolution/cases.yaml").read_text(encoding="utf-8"))
CONN_CASES = yaml.safe_load((REPO / "kernel/drishti-packs/src/test/resources/connector-layers/cases.yaml").read_text(encoding="utf-8"))

pytestmark = pytest.mark.static


def _tree(root: Path, files: dict) -> None:
    for rel, text in (files or {}).items():
        f = root / rel
        f.parent.mkdir(parents=True, exist_ok=True)
        f.write_text(text, encoding="utf-8")


@pytest.mark.parametrize("case", PACK_CASES["cases"], ids=[c["name"] for c in PACK_CASES["cases"]])
def test_pack_fixture(case, tmp_path):
    _tree(tmp_path, case.get("tree"))
    r = R.resolve(R.Layout.of(tmp_path / "site", tmp_path / "modules", tmp_path / "store"), case.get("active") or [])
    assert {n: p.label for n, p in r.packs.items()} == (case.get("packs") or {})
    assert r.packs.off == (case.get("off") or case.get(False) or {})       # YAML 1.1 reads the key `off` as false
    for name, rel in (case.get("dirs") or {}).items():
        assert r.packs.dir(name) == R._norm(tmp_path / rel)
    msgs = [p.message for p in r.problems]
    want = case.get("problems") or []
    assert len(msgs) == len(want), msgs
    for frag in want:
        assert any(frag in m for m in msgs), (frag, msgs)


@pytest.mark.parametrize("case", CONN_CASES["cases"], ids=[f"{c['name']}: {c['why']}" for c in CONN_CASES["cases"]])
def test_connector_fixture(case):
    t = CONN_CASES["templates"].get(case["template"]) if case.get("template") else None
    if case.get("error"):
        with pytest.raises(R.Unusable):
            R.effective(case["name"], case["file"], t)
        return
    e = R.effective(case["name"], case["file"], t)
    assert e.layer == case["layer"]
    assert e.superseded == case["superseded"]
    assert e.plugin == case["plugin"]
    assert e.enabled == str(case["enabled"])
    assert e.settings == {k: str(v) for k, v in case["settings"].items()}
    if "companion-of" in case:
        assert e.companion_of == case["companion-of"]
    for k, layer in (case.get("origins") or {}).items():
        assert e.origins.get(k) == layer, (k, e.origins)


@pytest.mark.parametrize("case", CONN_CASES["companions"], ids=[c["why"] for c in CONN_CASES["companions"]])
def test_companion_fixture(case):
    """Live companions (LIVE_CONNECTORS_DESIGN.md 2.2): the same problems and live connectors as Companions in Java."""
    conns = {n: (d["plugin"], d.get("companion-of") or "", list(d.get("kinds") or [])) for n, d in case["connectors"].items()}
    problems = R.companion_problems(conns)
    assert set(problems) == set(case["problems"] or {})
    for n, frag in (case["problems"] or {}).items():
        assert frag in problems[n] and "DRS-1061" in problems[n], problems[n]
    for want in case["live"]:
        assert R.live_connectors(want["kind"], want["read"], conns) == want["want"], want


def _site(tmp_path: Path, switch: bool | None) -> R.Settings:
    """A scratch site: a kernel pack, a module 'trading' with a pack, and a source module 'source-endur' (requires trading) with a
    connector template and inbox rules; ``switch``: source-endur's switch file (None: none)."""
    _tree(tmp_path, {
        "config/packs/core/core/pack.yaml": "pack: core\n",
        "config/dataload.yaml": "dataload:\n  rules:\n  - {match: 'site-*.jsonl', pack: core, kind: note}\n",
        "modules/trading/module.yaml": "id: trading\npacks: {folder: packs, list: [trading]}\n",
        "modules/trading/packs/trading/pack.yaml": "pack: trading\nroutes:\n  trade: trade-pages\n",
        "modules/source-endur/module.yaml": ("id: source-endur\nrequires:\n  trading: '>=1.0'\ndataload: dataload.yaml\n"
                                             "connectors:\n  folder: connectors\n"),
        "modules/source-endur/dataload.yaml": "dataload:\n  rules:\n  - {match: 'endur-*.jsonl.gz', pack: trading, kind: trade}\n",
        "modules/source-endur/connectors/trade-endur.yaml": ("name: trade-endur\nplugin: delta\nkinds: [trade]\nsettings:\n  lake: demo\n"
                                                             "  lake-tables: trading.trade_endur\n  union: true\n"),
        "modules/source-endur/connectors/examples/iceberg.yaml": "plugin: iceberg\nkinds: [trade]\n",
        "config/connectors/trade-endur.yaml": "template: module source-endur\nplugin: iceberg\n",
    })
    if switch is not None:
        _tree(tmp_path, {"config/modules/source-endur.yaml": f"enabled: {str(switch).lower()}\n"})
    return R.Settings.from_env(env={}, base=tmp_path)


@pytest.mark.parametrize("on", [True, False])
def test_a_module_off_brings_nothing_on_brings_it_back(tmp_path, on):
    r = R.build(_site(tmp_path, on))
    assert r.module_on("trading") and r.module_on("source-endur") == on
    assert r.packs.names() == ["core", "trading"]
    rules = [(x["match"], x["origin"]) for x in r.inbox_rules()]
    assert rules[0] == ("site-*.jsonl", "site")
    assert (("endur-*.jsonl.gz", "module:source-endur") in rules) == on
    e = r.connectors.find("trade-endur")
    if on:
        assert (e.plugin, e.layer, e.settings["lake-tables"], e.origins["lake-tables"]) == ("iceberg", "override", "trading.trade_endur", "template")
        assert r.bindings.read("trade")["connectors"] == ["trade-endur"]
        assert "iceberg" not in r.connectors.all()                   # an example is not a template
    else:
        assert e is None
        assert [u["code"] for u in r.connectors.unused()] == ["DRS-1046"]
        assert r.bindings.read("trade")["state"] == "UNBOUND"


def test_a_module_off_takes_the_modules_that_need_it(tmp_path):
    s = _site(tmp_path, None)
    _tree(tmp_path, {"config/modules/trading.yaml": "enabled: false\n"})
    r = R.build(s)
    assert not r.module_on("trading") and not r.module_on("source-endur")
    assert "needs the module 'trading'" in r.reasons["source-endur"]
    assert r.packs.names() == ["core"] and r.packs.off == {"trading": "trading"}
    assert r.packs.find("trading").state == R.MODULE_OFF


def test_the_servers_module_list_decides_on_a_console(tmp_path):
    s = _site(tmp_path, True)
    assert R.build(s, modules=["trading"]).packs.names() == ["core", "trading"]
    assert not R.build(s, modules=["trading"]).module_on("source-endur")
    assert R.build(s, modules=[]).packs.names() == ["core"]


def test_a_setting_wins_over_the_switch_file(tmp_path):
    s = _site(tmp_path, True)
    r = R.build(R.Settings.from_env(env={"DRISHTI_MODULES_SOURCE_ENDUR_ENABLED": "false"}, base=tmp_path))
    assert R.build(s).module_on("source-endur") and not r.module_on("source-endur")


def test_the_repository_reads_every_shipped_pack_once():
    r = R.repository()
    assert not r.problems, [p.message for p in r.problems]
    assert {"core", "gleif", "compute-demo"} <= {p.name for p in r.packs.of_origin(R.KERNEL)}
    assert {"trading", "market-risk", "market-data"} <= set(r.packs.names())
    for p in r.packs.values():
        assert (p.folder / "pack.yaml").is_file()
    assert r.connectors.find("trade-endur").template_from == "module source-endur"
    assert not [x.message for x in r.connectors.problems]


@pytest.mark.parametrize("on", [True, False])
def test_a_live_companion_follows_its_data_connector(tmp_path, on):
    """A module's live companion (companion-of: trade-endur) is the kind's live connector, never a read one; with its module off it
    goes with its data connector (LIVE_CONNECTORS_DESIGN.md section 2.2)."""
    s = _site(tmp_path, on)
    _tree(tmp_path, {"modules/source-endur/connectors/trade-endur-live.yaml":
                     "name: trade-endur-live\nplugin: kafka\nenabled: false\ncompanion-of: trade-endur\nsettings:\n  topics: [t]\n"})
    r = R.build(s)
    if on:
        assert r.connectors.find("trade-endur-live").companion_of == "trade-endur"
        assert r.bindings.read("trade")["connectors"] == ["trade-endur"]
        assert r.bindings.live("trade") == ["trade-endur-live"]
        assert r.connectors.companion_kinds("trade-endur-live") == ["trade"]
        assert r.connectors.companion_problems() == {}
    else:
        assert r.connectors.find("trade-endur-live") is None
        assert r.bindings.live("trade") == []


def test_every_shipped_companion_is_usable():
    """Every live companion the modules ship names a data connector in force, on a messaging plugin (DRS-1061)."""
    assert R.repository().connectors.companion_problems() == {}
