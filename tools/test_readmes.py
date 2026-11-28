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

"""The README standard (BUILD_GUIDE.md Part IV and DEVELOPER_GUIDE.md, "The README standard"): every pack, every
connector type (plugin or built-in) and every folder of connector templates has a README.md with a valid front matter and the
fixed sections, none empty; a pack's README describes every kind it owns under Data structures and is what its generator writes;
a connector README's generated blocks match the settings catalogue and the template files. Packs, plugins and template folders
are listed from the disk, so a new one is caught the day it lands."""
from __future__ import annotations

import importlib.util
import re
import sys
from pathlib import Path

import pytest
import yaml

pytestmark = pytest.mark.static   # a quick file check: the drill's gate runs it (TEST_HARNESS.md, "Quick file checks")

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))


def _load(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


R = _load("drishti_pack_readme", ROOT / "tools" / "packgen" / "common" / "readme.py")
PACKS_CLI = _load("drishti_pack_readme_cli", ROOT / "tools" / "packgen" / "readme.py")
CONN = _load("drishti_connector_readmes", ROOT / "tools" / "connector_readmes.py")
MODS = _load("drishti_module_readmes", ROOT / "tools" / "packgen" / "module_readme.py")

PACKS = list(R.PP.pack_dirs().values())
TYPES = CONN.type_readmes()
TEMPLATES = CONN.template_readmes()


@pytest.mark.parametrize("pack", PACKS, ids=lambda d: d.name)
def test_every_pack_has_a_readme_that_follows_the_standard(pack):
    assert PACKS_CLI.check(pack) == []


@pytest.mark.parametrize("readme", TYPES + TEMPLATES, ids=lambda p: str(p.parent.relative_to(ROOT)))
def test_every_connector_type_and_template_folder_has_a_readme_that_follows_the_standard(readme):
    assert R.problems_connector(readme) == []
    cat = yaml.safe_load(CONN.CATALOGUE.read_text(encoding="utf-8"))
    assert CONN.regenerate(readme, cat) == readme.read_text(encoding="utf-8"), "stale: run tools/connector_readmes.py"


def test_every_plugin_of_the_settings_catalogue_and_every_built_in_has_a_type_readme():
    cat = yaml.safe_load(CONN.CATALOGUE.read_text(encoding="utf-8"))
    named = {R.split(p.read_text(encoding="utf-8"))[0].get("connector") for p in TYPES if p.is_file()}
    assert set(cat["plugins"]) | {"derived", "net"} <= named


def test_a_type_readme_names_its_own_plugin_and_a_template_readme_lists_its_files():
    for p in TYPES:
        meta = R.split(p.read_text(encoding="utf-8"))[0]
        if p.parent.parent.name == "plugins":
            src = "\n".join(f.read_text(encoding="utf-8") for f in p.parent.rglob("src/main/java/**/*Plugin.java"))
            assert f'"{meta["connector"]}"' in src or meta["connector"] == "feed", p
        assert meta["category"] == ("plugin" if p.parent.parent.name == "plugins" else "built-in"), p
    for p in TEMPLATES:
        meta = R.split(p.read_text(encoding="utf-8"))[0]
        assert meta["files"] == sorted(f.name for f in p.parent.glob("*.yaml")), p


def test_the_validator_catches_what_the_standard_forbids(tmp_path):
    good = (PACKS[0] / "README.md").read_text(encoding="utf-8")
    kinds = yaml.safe_load((PACKS[0] / "pack.yaml").read_text(encoding="utf-8"))["kinds"]
    bad = tmp_path / PACKS[0].name / "README.md"
    bad.parent.mkdir()
    bad.write_text(good.replace("## Refreshing the data", "## Refreshing"), encoding="utf-8")
    assert "no '## Refreshing the data' section" in R.problems_pack(bad, kinds)
    bad.write_text(re.sub(r"### `" + re.escape(kinds[0]) + "`", "### something", good), encoding="utf-8")
    assert f"kind '{kinds[0]}' is missing from Data structures" in R.problems_pack(bad, kinds)
    bad.write_text(good.replace("\n---\n# ", "\n# ", 1).replace("---\npack:", "pack:", 1), encoding="utf-8")
    assert R.problems_pack(bad, kinds) == ["no valid YAML front matter"]
    conn = tmp_path / "x" / "README.md"
    conn.parent.mkdir()
    text = TYPES[0].read_text(encoding="utf-8")
    conn.write_text(re.sub(r"## Troubleshooting\n\n.*?\n## See also", "## Troubleshooting\n\n## See also", text, flags=re.S), encoding="utf-8")
    assert "section 'Troubleshooting' is empty" in R.problems_connector(conn)


def test_a_pack_readme_data_structure_table_names_fields_types_and_nested_lists():
    text = (ROOT / "modules" / "banking" / "packs" / "trading" / "README.md").read_text(encoding="utf-8")
    meta, body = R.split(text)
    assert meta["pack"] == "trading" and "trade" in meta["kinds"] and meta["commands"]["TRD"] == "trade"
    data = R.sections(body)["Data structures"]
    assert "| `TRADE_ID` | string |" in data
    assert "`LEGS[].CASH_FLOWS[]`" in data and "`LEGS[].CASH_FLOWS[].RESETS[]`" in data


@pytest.mark.parametrize("module", MODS.module_dirs(), ids=lambda d: d.name)
def test_a_module_readme_is_what_its_manifest_and_about_yaml_write(module):
    assert MODS.check(module) == []


def test_every_module_with_a_readme_has_its_text_in_docs_about_yaml():
    for m in sorted((ROOT / "modules").iterdir()):
        if (m / "docs" / "README.md").is_file():
            assert (m / "docs" / "about.yaml").is_file(), f"{m.name}: docs/README.md is generated from docs/about.yaml"


def test_a_stale_generated_readme_is_caught(tmp_path):
    mod = tmp_path / "m"
    (mod / "docs").mkdir(parents=True)
    (mod / "module.yaml").write_text("id: m\ntitle: M\ndomain: d\n", encoding="utf-8")
    (mod / "docs" / "about.yaml").write_text("front: |\n  summary: s\nbody: |\n  # M\n", encoding="utf-8")
    assert MODS.check(mod)                                      # missing
    (mod / "docs" / "README.md").write_text(MODS.render(mod), encoding="utf-8")
    assert MODS.check(mod) == []
    (mod / "docs" / "README.md").write_text(MODS.render(mod) + "a hand edit\n", encoding="utf-8")
    assert "stale" in MODS.check(mod)[0]


def test_a_pack_readme_has_its_intro_in_the_packs_about_yaml():
    for pack in PACKS:
        about = yaml.safe_load((pack / "config" / "about.yaml").read_text(encoding="utf-8"))
        assert about.get("intro", {}).get("sections", {}).get("What it is"), f"{pack.name}: intro: in config/about.yaml"
        assert not list((ROOT / "tools" / "packgen").rglob("*.intro.md"))


# ------------------------------------------------------------------------------------------------ the loaders' READMEs

LOADERS = ROOT / "tools" / "loaders"
DAILY = ("mrloader", "external", "news", "genomics")          # the loaders run every day: the full set of sections, Windows and Linux commands
SECTIONS = ("## What it loads", "## Prerequisites", "## How to check it worked", "## When something goes wrong")


@pytest.mark.parametrize("folder", ["", "mrloader", "external", "news", "genomics", "common"])
def test_every_loader_folder_has_a_readme_with_the_license_header(folder):
    text = (LOADERS / folder / "README.md").read_text(encoding="utf-8")
    assert text.startswith("<!--") and "Ashutosh Sinha" in text.split("-->")[0], f"tools/loaders/{folder}/README.md: the copyright header"
    assert (LOADERS / folder / "README.md").read_text(encoding="utf-8").count("\n") <= 420, "a loader README stays about 400 lines: link the guides for depth"


@pytest.mark.parametrize("folder", DAILY)
def test_a_daily_loader_readme_has_the_operators_sections_and_both_operating_systems(folder):
    text = (LOADERS / folder / "README.md").read_text(encoding="utf-8")
    for s in SECTIONS:
        assert s in text, f"tools/loaders/{folder}/README.md: section {s!r}"
    assert "daily routine" in text.lower()
    assert "py -3.13" in text and "python3" in text, f"tools/loaders/{folder}/README.md: commands for Windows (py -3.13) and Linux (python3)"
    assert ".venv" not in text, "no virtual environment, ever"


def test_every_feed_is_named_in_the_external_or_the_news_readme():
    text = "".join((LOADERS / f / "README.md").read_text(encoding="utf-8") for f in ("external", "news", "genomics"))
    for f in sorted((ROOT / "config" / "feeds").glob("*.yaml")):
        assert f"`{f.stem}`" in text, f"config/feeds/{f.name}: name the feed in the README of its loader (external, news or genomics)"


def test_every_runnable_script_of_a_loader_is_named_in_its_readme():
    for folder in ("mrloader", "external", "news", "genomics"):
        text = (LOADERS / folder / "README.md").read_text(encoding="utf-8")
        for py in sorted((LOADERS / folder).glob("*.py")):
            body = py.read_text(encoding="utf-8")
            if py.name.startswith("test_") or "__main__" not in body and folder == "mrloader":
                continue
            assert py.name in text or py.stem in text, f"tools/loaders/{folder}/README.md does not name {py.name}"
